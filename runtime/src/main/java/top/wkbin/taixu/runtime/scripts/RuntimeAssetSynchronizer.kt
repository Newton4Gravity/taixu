package top.wkbin.taixu.runtime.scripts

import android.content.Context
import top.wkbin.taixu.runtime.RuntimePathManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 🛠️ TaiXu Runtime Asset Synchronizer
 * Extracts standardized Shell scripts from APK assets/scripts/ and tools,
 * syncs them to Linux sandbox directories (/opt/taixu/scripts/ and /opt/taixu/tools/),
 * and grants execute permissions.
 */
class RuntimeAssetSynchronizer(
    private val context: Context,
    private val pathManager: RuntimePathManager,
) {
    suspend fun syncWorkshopScript(distroId: String, fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val safeDistro = distroId.lowercase().trim()
        pathManager.ensureDistroDirectories(safeDistro)
        val target = File(pathManager.taixuScriptsDir(safeDistro), fileName)
        target.parentFile?.mkdirs()
        target.writeText(content.removePrefix("\uFEFF").replace("\r\n", "\n"), Charsets.UTF_8)
        target.setExecutable(true, false)
        "/opt/taixu/scripts/$fileName"
    }
    /**
     * Sync scripts and asset tools into the specified distro sandbox
     */
    suspend fun syncAssetsToDistro(distroId: String) = withContext(Dispatchers.IO) {
        val safeDistro = distroId.lowercase().trim()
        pathManager.ensureDistroDirectories(safeDistro)

        val scriptsTargetDir = pathManager.taixuScriptsDir(safeDistro)
        val toolsTargetDir = pathManager.taixuToolsDir(safeDistro)
        val binTargetDir = pathManager.taixuBinDir(safeDistro)

        // 1. Sync assets/scripts/ -> /opt/taixu/scripts/
        syncAssetFolder("scripts", scriptsTargetDir)

        // 2. Sync assets/tools/ -> /opt/taixu/tools/
        syncAssetFolder("tools", toolsTargetDir)

        // 3. /opt/taixu/bin is first in terminal/Agent PATH, contains scripts and ELF native tools.
        syncAssetExecutableFolder("bin", binTargetDir)

        // 4. Sync assets/certs/ -> /opt/taixu/certs/ and /etc/ssl/certs/java/cacerts
        val certsTargetDir = File(pathManager.taixuRootDir(safeDistro), "certs")
        syncAssetBinaryFolder("certs", certsTargetDir)

        // 5. RTK telemetry, history, and raw output logging off by default; config applies only to Agent-wrapped commands.
        syncAssetTree("rtk", File(pathManager.taixuRootDir(safeDistro), "data/rtk/config"))

        // 6. Android/Flutter project templates live outside a distro so that
        // creating a workspace does not depend on which distro is active.
        syncAssetTree("templates", File(pathManager.baseDir, "templates"))

        // 7. Inject standard cacerts into sandbox OpenJDK and system cert paths
        val builtinCacerts = File(certsTargetDir, "cacerts")
        if (builtinCacerts.exists() && builtinCacerts.length() > 0) {
            val rootfsRoot = pathManager.rootfsDir(safeDistro)
            val targetCacertsLocations: List<File> = listOf(
                File(rootfsRoot, "etc/ssl/certs/java/cacerts"),
                File(rootfsRoot, "usr/lib/jvm/java-17-openjdk-arm64/lib/security/cacerts"),
                File(rootfsRoot, "usr/lib/jvm/default-java/lib/security/cacerts"),
            )
            for (dest in targetCacertsLocations) {
                runCatching {
                    dest.parentFile?.mkdirs()
                    builtinCacerts.copyTo(dest, overwrite = true)
                    dest.setReadable(true, false)
                }
            }
        }
    }

    private fun syncAssetBinaryFolder(assetSubDir: String, targetDir: File) {
        targetDir.mkdirs()
        val assetList = runCatching { context.assets.list(assetSubDir) }.getOrNull().orEmpty()
        for (filename in assetList) {
            val assetPath = "$assetSubDir/$filename"
            val targetFile = File(targetDir, filename)
            runCatching {
                context.assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setReadable(true, false)
            }
        }
    }

    /** Binary-safe sync of executable assets; cannot read via UTF-8 text path. */
    private fun syncAssetExecutableFolder(assetSubDir: String, targetDir: File) {
        targetDir.mkdirs()
        val assetList = runCatching { context.assets.list(assetSubDir) }.getOrNull().orEmpty()
        for (filename in assetList) {
            val assetPath = "$assetSubDir/$filename"
            val targetFile = File(targetDir, filename)
            runCatching {
                context.assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                targetFile.setExecutable(true, false)
                targetFile.setReadable(true, false)
            }
        }
    }

    private fun syncAssetFolder(assetSubDir: String, targetDir: File) {
        targetDir.mkdirs()
        val assetList = runCatching { context.assets.list(assetSubDir) }.getOrNull().orEmpty()
        for (filename in assetList) {
            val assetPath = "$assetSubDir/$filename"
            val targetFile = File(targetDir, filename)
            runCatching {
                val content = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                // Strip UTF-8 BOM (\uFEFF) and normalize line endings to Unix LF
                val cleanContent = content.removePrefix("\uFEFF").replace("\r\n", "\n")
                targetFile.writeText(cleanContent, Charsets.UTF_8)
                targetFile.setExecutable(true, false)
                targetFile.setReadable(true, false)
            }
        }
    }

    private fun syncAssetTree(assetPath: String, targetDir: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) return
        children.forEach { child ->
            val childAsset = "$assetPath/$child"
            val childTarget = File(targetDir, child)
            val nested = runCatching { context.assets.list(childAsset) }.getOrNull().orEmpty()
            if (nested.isEmpty()) {
                runCatching {
                    childTarget.parentFile?.mkdirs()
                    context.assets.open(childAsset).use { input ->
                        childTarget.outputStream().use { output -> input.copyTo(output) }
                    }
                    childTarget.setReadable(true, false)
                }
            } else {
                syncAssetTree(childAsset, childTarget)
            }
        }
    }
}
