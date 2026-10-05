package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.network.DownloadEvent
import top.wkbin.taixu.core.network.DownloadRequest
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.core.network.ChecksumVerifier
import top.wkbin.taixu.runtime.RuntimePathManager
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FlutterSdkArchive(
    val hostFile: File,
    val guestPath: String,
    val version: String,
)

/**
 * Downloads Flutter SDK archives in the Android app process.
 *
 * The Linux setup script runs inside PRoot and should not own large network
 * transfers. The shared downloader provides HTTPS checks, retries, resume and
 * checksum verification; the archive is placed in the bind-mounted /opt/taixu
 * tree so the script can extract it without downloading again.
 */
class FlutterSdkDownloader(
    private val fileDownloader: FileDownloader,
    private val checksumVerifier: ChecksumVerifier,
    private val pathManager: RuntimePathManager,
    private val json: Json,
) {
    suspend fun prepare(
        distroId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): FlutterSdkArchive {
        val cacheDir = File(pathManager.taixuRootDir(distroId), "flutter-cache-arm64").apply { mkdirs() }
        val metadata = File(cacheDir, "releases_linux_arm64.json")
        val release = fetchRelease(metadata)
        val archiveName = release.archive.substringAfterLast('/').ifBlank {
            error("Flutter release index returned invalid archive: ${release.archive}")
        }
        val archive = File(cacheDir, archiveName)
        val cacheValid = archive.isFile && archive.length() > 0L &&
            (release.sha256.isBlank() || runCatching {
                checksumVerifier.verify(archive, release.sha256)
            }.isSuccess)
        if (!cacheValid) {
            archive.delete()
            downloadArchive(release, archive, onProgress)
        }
        return FlutterSdkArchive(
            hostFile = archive,
            guestPath = "/opt/taixu/flutter-cache-arm64/$archiveName",
            version = release.version,
        )
    }

    private suspend fun fetchRelease(metadata: File): ReleaseInfo {
        var lastError: Throwable? = null
        for (url in METADATA_URLS) {
            try {
                metadata.delete()
                fileDownloader.download(
                    DownloadRequest(
                        url = url,
                        destination = metadata,
                        partialFile = File("${metadata.absolutePath}.part"),
                        maxAttempts = 3,
                        maxBytes = 32L * 1024L * 1024L,
                    ),
                ).collect { }
                return parseRelease(metadata)
            } catch (throwable: Throwable) {
                lastError = throwable
            }
        }
        throw IllegalStateException(
            "Flutter release info download failed: ${lastError?.message ?: "unknown network error"}",
            lastError,
        )
    }

    private fun parseRelease(metadata: File): ReleaseInfo {
        val document = json.parseToJsonElement(metadata.readText()).jsonObject
        val armAsset = document["assets"]?.jsonArray?.firstOrNull { item ->
            item.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                .contains("linux_arm64_android_web_sdk")
        }?.jsonObject
        if (armAsset != null) {
            val archive = armAsset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
            check(archive.isNotBlank()) { "ARM64 Flutter release asset missing download URL" }
            return ReleaseInfo(
                archive = archive,
                sha256 = "",
                version = document["tag_name"]?.jsonPrimitive?.content ?: "stable-arm64",
                absoluteUrl = true,
            )
        }
        val releases = document["releases"]?.jsonArray
            ?: error("Flutter release index missing ARM64 assets field")
        val release = releases.firstOrNull { item ->
            val objectValue = item.jsonObject
            objectValue["channel"]?.jsonPrimitive?.content == "stable" &&
                objectValue["dart_sdk_arch"]?.jsonPrimitive?.content == "arm64"
        }?.jsonObject ?: error("Flutter release index has no stable Linux ARM64 SDK")
        val archive = release["archive"]?.jsonPrimitive?.content.orEmpty()
        val sha256 = release["sha256"]?.jsonPrimitive?.content.orEmpty()
        val version = release["version"]?.jsonPrimitive?.content ?: "stable"
        check(archive.isNotBlank() && sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Flutter release index archive or sha256 invalid"
        }
        return ReleaseInfo(archive, sha256, version, absoluteUrl = false)
    }

    private suspend fun downloadArchive(
        release: ReleaseInfo,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        var lastError: Throwable? = null
        val bases = if (release.absoluteUrl) GITHUB_ARCHIVE_PROXIES else ARCHIVE_BASE_URLS
        for (base in bases) {
            val url = if (release.absoluteUrl) "${base}${release.archive}" else "${base.trimEnd('/')}/${release.archive}"
            try {
                fileDownloader.download(
                    DownloadRequest(
                        url = url,
                        destination = destination,
                        partialFile = File("${destination.absolutePath}.part"),
                        sha256 = release.sha256.takeIf { it.isNotBlank() },
                        maxAttempts = 4,
                        maxBytes = 2L * 1024L * 1024L * 1024L,
                    ),
                ).collect { event ->
                    if (event is DownloadEvent.Progress) {
                        onProgress(event.downloadedBytes, event.totalBytes)
                    }
                }
                return
            } catch (throwable: Throwable) {
                lastError = throwable
                destination.delete()
                File("${destination.absolutePath}.part").delete()
            }
        }
        throw IllegalStateException(
            "Flutter SDK download or SHA-256 verify failed: ${lastError?.message ?: "unknown error"}",
            lastError,
        )
    }

    private data class ReleaseInfo(
        val archive: String,
        val sha256: String,
        val version: String,
        val absoluteUrl: Boolean,
    )

    private companion object {
        // Pin Flutter SDK to specific tag to avoid latest raising Gradle/AGP/Kotlin minimum versions.
        # This version maps to min toolchain: Gradle 8.14+ / AGP 8.11.1+ / Kotlin 2.2.20+.
        private const val PINNED_FLUTTER_TAG = "flutter-3.47.1-87-linux"
        val METADATA_URLS = listOf(
            "https://api.github.com/repos/MohamedAlkindi/flutter-native-arm64/releases/tags/$PINNED_FLUTTER_TAG",
            "https://storage.googleapis.com/flutter_infra_release/releases/releases_linux.json",
            "https://storage.flutter-io.cn/flutter_infra_release/releases/releases_linux.json",
        )
        val ARCHIVE_BASE_URLS = listOf(
            "https://storage.flutter-io.cn/flutter_infra_release/releases",
            "https://storage.googleapis.com/flutter_infra_release/releases",
        )
        // GitHub direct in CN often blocked; download ARM64 release via proxy mirrors first, fallback to direct last.
        val GITHUB_ARCHIVE_PROXIES = listOf(
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://gh.llkk.cc/",
            "https://gh-proxy.com/",
            "",
        )
    }
}
