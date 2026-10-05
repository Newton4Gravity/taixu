package top.wkbin.taixu.runtime.rootfs

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.runtime.DistributionSpec
import top.wkbin.taixu.runtime.DownloadProgress

/**
 * lxc-images rootfs download fallback channel (single file `rootfs.tar.xz` + SHA256SUMS checksum verification).
 *
 * Mirrors tried in order: Tsinghua TUNA (CN acceleration, syncs most distros) -> LXC official
 * (images.linuxcontainers.org, covers distros TUNA misses, e.g., archlinux arm64).
 * Only used as fallback after all OCI routes (DaoCloud / Docker Hub) fail, and only for
 * distros mapped to lxc-images.
 */
class LxcImagesClient(
    private val http: OkHttpClient,
    private val logger: AppLogger,
) {
    /** Whether this distro has an lxc-images fallback image. */
    fun supports(distributionId: String): Boolean = lxcPathFor(distributionId) != null

    suspend fun pull(
        distribution: DistributionSpec,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
        applyLayer: suspend (File, String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val lxcPath = lxcPathFor(distribution.id)
            ?: error("lxc-images does not support distro ${distribution.id}")
        cacheDir.mkdirs()
        var lastFailure: Throwable? = null
        for (base in MIRROR_BASES) {
            try {
                val buildDir = resolveLatestBuild(base, lxcPath)
                val expectedSha = fetchExpectedSha256(base, lxcPath, buildDir)
                val blob = downloadRootfs(base, lxcPath, buildDir, expectedSha, cacheDir, onProgress)
                applyLayer(blob, MEDIA_TYPE_ROOTFS_TAR_XZ)
                return@withContext "lxc-5.8.0-${distribution.id}-$buildDir"
            } catch (failure: Throwable) {
                lastFailure = failure
                logger.w("lxc-images mirror $base unavailable (${distribution.id})", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("No available lxc-images mirrors")
    }

    /** Fetch directory listing and parse latest build timestamp directory (e.g., 20260820_05:24). */
    private fun resolveLatestBuild(mirrorBase: String, lxcPath: String): String {
        val url = "$mirrorBase/$lxcPath/arm64/default/"
        val body = fetchText(url, metadataClient())
        val builds = Regex("(\\d{8}_\\d{2}(?:%3A|:)\\d{2})/")
            .findAll(body)
            .map { it.groupValues[1].replace("%3A", ":") }
            .distinct()
            .sorted()
            .toList()
        check(builds.isNotEmpty()) { "lxc-images directory parse failed: $url" }
        return builds.last()
    }

    /** Read SHA256SUMS in build directory, get rootfs.tar.xz digest. */
    private fun fetchExpectedSha256(mirrorBase: String, lxcPath: String, buildDir: String): String {
        val url = buildUrl(mirrorBase, lxcPath, buildDir, "SHA256SUMS")
        val body = fetchText(url, metadataClient())
        val expected = body.lineSequence()
            .firstOrNull { it.trimEnd().endsWith("rootfs.tar.xz") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()
            ?: error("SHA256SUMS missing rootfs.tar.xz entry: $url")
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "lxc-images SHA256SUMS digest format invalid: $expected" }
        return expected
    }

    private suspend fun downloadRootfs(
        mirrorBase: String,
        lxcPath: String,
        buildDir: String,
        expectedSha: String,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): File {
        val target = File(cacheDir, "sha256-$expectedSha.rootfs.tar.xz")
        if (target.isFile && sha256(target) == expectedSha) {
            logger.i("lxc-images layer cache hit: ${target.name}")
            return target
        }
        val partial = File(cacheDir, "sha256-$expectedSha.part")
        val url = buildUrl(mirrorBase, lxcPath, buildDir, "rootfs.tar.xz")
        # Resume download: leftover .part resumes via Range, avoiding full re-download of large files
        val existing = if (partial.isFile) partial.length() else 0L
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        http.newCall(request).execute().use { response ->
            val append = response.code == 206
            if (response.code != 206 && response.code != 200) {
                check(false) { "Download lxc-images rootfs failed HTTP ${response.code}" }
            }
            val md = MessageDigest.getInstance("SHA-256")
            if (append && existing > 0) {
                partial.inputStream().use { input -> hashInto(md, input) }
            } else if (existing > 0) {
                partial.delete()
            }
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { if (append) existing + it else it }
            var count = if (append) existing else 0L
            response.body.byteStream().use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        md.update(buffer, 0, read)
                        count += read
                        onProgress(DownloadProgress(count, total))
                    }
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            check(actual == expectedSha) { "lxc-images rootfs digest verify failed: expected $expectedSha, actual $actual" }
        }
        check(partial.renameTo(target)) { "Failed to commit lxc-images rootfs cache" }
        return target
    }

    private fun hashInto(md: MessageDigest, input: java.io.InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
    }

    /** Colon in timestamp dir name must be encoded, some HTTP stacks reject unencoded paths. */
    private fun buildUrl(mirrorBase: String, lxcPath: String, buildDir: String, fileName: String): String =
        "$mirrorBase/$lxcPath/arm64/default/${buildDir.replace(":", "%3A")}/$fileName"

    private fun fetchText(url: String, client: OkHttpClient): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "lxc-images metadata request failed HTTP ${response.code}: $url" }
            return response.body.string()
        }
    }

    /** Metadata requests (directory listing / SHA256SUMS) use short timeouts to avoid fallback slowing overall install. */
    private fun metadataClient(): OkHttpClient = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Mirrors tried in order: CN acceleration first, official as fallback (covers distros TUNA misses). */
        val MIRROR_BASES = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images",
            "https://images.linuxcontainers.org/images",
        )
        const val USER_AGENT = "TaiXu/proot-distro-5.8.0"
        const val MEDIA_TYPE_ROOTFS_TAR_XZ = "application/x-tar.xz"

        /**
         * Distro id -> lxc-images path mapping.
         * Note: lxc-images only has default (minimal) rootfs, no buildpack-deps toolchain,
         * so only as fallback, cannot equivalently replace OCI images.
         */
        fun lxcPathFor(distributionId: String): String? = when (distributionId.lowercase()) {
            "debian" -> "debian/bookworm"
            "ubuntu" -> "ubuntu/noble"
            "kali" -> "kali/current"
            "arch" -> "archlinux/current"
            else -> null
        }
    }
}
