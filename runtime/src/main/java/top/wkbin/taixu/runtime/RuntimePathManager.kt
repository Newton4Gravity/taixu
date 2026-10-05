package top.wkbin.taixu.runtime

import android.content.Context
import top.wkbin.taixu.runtime.rootfs.RootfsValidator
import java.io.File

class RuntimePathManager(
    context: Context,
    private val rootfsValidator: RootfsValidator,
) {
    private val appFilesDir: File = context.filesDir
    val bundledProotFile: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val nativeLibraryDir: File = File(context.applicationInfo.nativeLibraryDir)
    val bundledProotLoaderFile: File = File(nativeLibraryDir, "libproot-loader.so")
    val bundledProotLoader32File: File = File(nativeLibraryDir, "libproot-loader32.so")

    val baseDir: File = File(appFilesDir, "linux-runtime")
    val binDir: File = File(baseDir, "bin")
    val prootFile: File = File(binDir, "proot")
    val distrosDir: File = File(baseDir, "distros")
    val rootfsDir: File get() {
        val installed = listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        return rootfsDir(installed)
    }
    val stagingRootfsDir: File = File(baseDir, "rootfs.staging")
    val homeDir: File get() {
        val installed = listInstalledDistroIds().firstOrNull() ?: "ubuntu"
        return homeDir(installed)
    }
    val optDir: File = File(baseDir, "opt")
    val workspaceDir: File = File(baseDir, "workspace")
    /** Host-side shared attachment directory, mounted into every distro at /attachments. */
    val attachmentsDir: File = File(baseDir, "attachments")
    private val legacyAttachmentsDir: File = File(appFilesDir, "attachments")
    val cacheDir: File = File(baseDir, "cache")
    val tmpDir: File = File(baseDir, "tmp")
    val logsDir: File = File(baseDir, "logs")
    val metadataDir: File = File(baseDir, "metadata")

    // ==================== Plugin directories (isolated per distro) ====================
    // Since v17 plugin binaries/data/commands and Runtime dependencies follow distro:
    // linux-runtime/distros/<id>/opt/taixu/{tools,data,bin,runtimes,...}

    fun distroOptDir(distroId: String): File = File(distroDir(distroId), "opt")

    fun taixuRootDir(distroId: String): File = File(distroOptDir(distroId), "taixu")

    fun taixuRuntimesDir(distroId: String): File = File(taixuRootDir(distroId), "runtimes")

    fun taixuToolsDir(distroId: String): File = File(taixuRootDir(distroId), "tools")

    fun taixuDataDir(distroId: String): File = File(taixuRootDir(distroId), "data")

    fun taixuScriptsDir(distroId: String): File = File(taixuRootDir(distroId), "scripts")

    fun taixuBinDir(distroId: String): File = File(taixuRootDir(distroId), "bin")

    fun ensureDistroDirectories(distroId: String) {
        val safeId = distroId.lowercase().trim()
        listOf(
            taixuRootDir(safeId),
            taixuRuntimesDir(safeId),
            taixuToolsDir(safeId),
            taixuScriptsDir(safeId),
            taixuDataDir(safeId),
            taixuBinDir(safeId),
        ).forEach { it.mkdirs() }
    }
    private val hostLibraries = mapOf(
        "libtalloc.so" to "libtalloc.so.2",
        "libandroid-shmem.so" to "libandroid-shmem.so",
    )

    // ==================== Multi-distro path resolution ====================

    fun distroDir(distroId: String): File = File(distrosDir, distroId.lowercase().trim())
    fun rootfsDir(distroId: String): File = File(distroDir(distroId), "rootfs")
    fun homeDir(distroId: String): File = File(distroDir(distroId), "home")
    fun metadataDir(distroId: String): File = File(distroDir(distroId), "metadata")
    fun stagingRootfsDir(distroId: String): File = File(distroDir(distroId), "rootfs.staging")
    fun rootfsPreviousDir(distroId: String): File = File(distroDir(distroId), "rootfs.previous")
    fun rootfsInstalledMarker(distroId: String): File = File(metadataDir(distroId), "rootfs.installed")
    fun rootfsUpdatePendingMarker(distroId: String): File = File(metadataDir(distroId), "rootfs.update.pending")
    fun distroLayersFile(distroId: String): File = File(metadataDir(distroId), "layers.txt")

    fun isDistroInstalled(distroId: String): Boolean =
        rootfsInstalledMarker(distroId).isFile && rootfsValidator.isValid(rootfsDir(distroId))

    /**
     * Cached installed distro IDs.
     *
     * `rootfsDir` / `homeDir` getters and every command build go through here; original
     * implementation re-scanned distros dir and did ELF validation for each candidate
     * on every access, making it a hidden hot spot for cold start and first command.
     * Only caches non-empty results: empty result scan is cheap (marker missing shortcuts).
     * Structural changes (install/update/reset/uninstall) explicitly invalidate via
     * RootfsInstaller / refreshInstalledDistros.
     */
    @Volatile
    private var installedDistroIdsCache: List<String>? = null

    fun listInstalledDistroIds(): List<String> {
        installedDistroIdsCache?.let { return it }
        if (!distrosDir.exists()) return emptyList()
        val ids = distrosDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isDistroInstalled(it.name) }
            .map { it.name }
        if (ids.isNotEmpty()) installedDistroIdsCache = ids
        return ids
    }

    fun invalidateInstalledDistrosCache() {
        installedDistroIdsCache = null
    }

    /** Distro disk usage snapshot cache file (metadata/distro.size), avoids full tree traversal on startup. */
    fun distroSizeMarker(distroId: String): File = File(metadataDir(distroId.lowercase().trim()), "distro.size")

    fun invalidateDistroSizeCache(distroId: String) {
        runCatching { distroSizeMarker(distroId).delete() }
    }

    fun rootfsVersion(distroId: String): String? = rootfsInstalledMarker(distroId)
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull()?.substringAfter("rootfs-version=", "")?.trim()
        }
        ?.takeIf { it.isNotBlank() }

    fun rootfsDigest(distroId: String): String? = rootfsInstalledMarker(distroId)
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull { it.startsWith("rootfs-digest=") }
                ?.substringAfter("rootfs-digest=", "")
                ?.trim()
        }
        ?.takeIf { it.isNotBlank() }

    fun distroSizeBytes(distroId: String): Long {
        val safeId = distroId.lowercase().trim()
        val dir = distroDir(safeId)
        if (!dir.exists()) return 0L
        // Hot path on startup: prefer cached size snapshot from install/update, avoids walkTopDown on tens of thousands of rootfs files.
        distroSizeMarker(safeId).takeIf { it.isFile }
            ?.useLines { lines -> lines.firstOrNull()?.trim()?.toLongOrNull() }
            ?.takeIf { it >= 0L }
            ?.let { return it }
        val computed = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        if (computed > 0L) {
            runCatching {
                metadataDir(safeId).mkdirs()
                distroSizeMarker(safeId).writeText(computed.toString())
            }
        }
        return computed
    }

    /**
     * Android 10+ blocks execve() from writable app data for apps targeting API 29+.
     * The small Android-native PRoot fallback is therefore shipped as an extracted
     * native library, while the Debian Linux system remains an online download.
     */
    fun activeProotFile(): File = bundledProotFile.takeIf { isUsableProot(it) } ?: prootFile

    fun ensureDirectories() {
        listOf(
            baseDir,
            binDir,
            distrosDir,
            optDir,
            workspaceDir,
            attachmentsDir,
            cacheDir,
            tmpDir,
            logsDir,
            metadataDir,
        ).forEach { it.mkdirs() }
        migrateLegacyAttachments()
        listInstalledDistroIds().forEach(::ensureDistroDirectories)
    }

    /** Preserve attachments created before the shared runtime mount was introduced. */
    private fun migrateLegacyAttachments() {
        if (!legacyAttachmentsDir.isDirectory) return
        legacyAttachmentsDir.listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { source ->
                val target = File(attachmentsDir, source.name)
                if (!target.exists()) runCatching { source.copyTo(target, overwrite = false) }
            }
    }

    fun cleanupStalePtyMarkers(distroId: String = "ubuntu") {
        val safeId = distroId.lowercase().trim()
        taixuRootDir(safeId)
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith(".pty-") }
            .forEach { it.delete() }
    }

    /**
     * PRoot is an Android-native executable, but its Termux build keeps talloc as a
     * dynamically linked library. Android's APK native-library packaging only accepts
     * the `.so` suffix, so copy the bundled library to the exact SONAME expected by
     * the linker in the app-private runtime directory before launching PRoot.
     */
    fun hostProcessEnvironment(distroId: String = "ubuntu", rootfsOverride: File? = null): Map<String, String> {
        ensureDirectories()
        val rfs = rootfsOverride ?: rootfsDir(distroId)
        // Remove the app-managed QEMU payload left by versions that used an
        // x86_64 AAPT2 fallback. Current Android tooling is ARM64-native.
        File(taixuBinDir(distroId), "qemu-x86_64-static").delete()
        hostLibraries.forEach { (bundledName, hostName) ->
            val bundledFile = File(nativeLibraryDir, bundledName)
            val hostFile = File(binDir, hostName)
            if (bundledFile.isFile && bundledFile.length() > MIN_TALLOC_BYTES) {
                // Refresh tiny native dependencies after an APK update; equal
                // file length alone does not prove that an old SONAME copy matches.
                val needsCopy = !hostFile.isFile ||
                    hostFile.length() != bundledFile.length() ||
                    !bundledFile.readBytes().contentEquals(hostFile.readBytes())
                if (needsCopy) bundledFile.copyTo(hostFile, overwrite = true)
                hostFile.setReadable(true, false)
                hostFile.setWritable(true, true)
            }
        }
        return buildMap {
            put("PROOT_L2S_DIR", File(rfs, ".l2s").apply { mkdirs() }.absolutePath)
            if (isUsableNativeArtifact(bundledProotLoaderFile, MIN_PROOT_LOADER_BYTES)) {
                put("PROOT_LOADER", bundledProotLoaderFile.absolutePath)
            }
            if (isUsableNativeArtifact(bundledProotLoader32File, MIN_PROOT_LOADER_BYTES)) {
                put("PROOT_LOADER_32", bundledProotLoader32File.absolutePath)
            }
            put("TMPDIR", tmpDir.absolutePath)
            put("TMP", tmpDir.absolutePath)
            put("TEMP", tmpDir.absolutePath)
            put("PROOT_TMP_DIR", tmpDir.absolutePath)
            if (hostLibraries.values.any { File(binDir, it).isFile }) {
                put("LD_LIBRARY_PATH", binDir.absolutePath)
            }
        }
    }

    fun rootfsInstalledMarker(): File = File(metadataDir, "rootfs.installed")

    fun rootfsUpdatePendingMarker(): File = File(metadataDir, "rootfs.update.pending")

    fun rootfsPreviousDir(): File = File(baseDir, "rootfs.previous")

    fun rootfsVersion(): String? = rootfsInstalledMarker()
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull()?.substringAfter("rootfs-version=", "")?.trim()
        }
        ?.takeIf { it.isNotBlank() }

    /** PRoot is usable only when both the tracer and its external ARM64 loader are in the APK. */
    fun isProotInstalled(): Boolean =
        isUsableProot(activeProotFile()) &&
            isUsableNativeArtifact(bundledProotLoaderFile, MIN_PROOT_LOADER_BYTES)

    fun isRootfsInstalled(): Boolean = listInstalledDistroIds().isNotEmpty()

    private fun isUsableProot(file: File): Boolean =
        file.isFile && file.length() > MIN_PROOT_BYTES && (file.canExecute() || file == bundledProotFile)

    private fun isUsableNativeArtifact(file: File, minimumBytes: Long): Boolean =
        file.isFile && file.length() > minimumBytes && file.canRead()

    private companion object {
        const val MIN_PROOT_BYTES = 4096L
        const val MIN_PROOT_LOADER_BYTES = 4096L
        const val MIN_TALLOC_BYTES = 4096L
    }
}
