package top.wkbin.taixu.core.tools

import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.runtime.RuntimePathManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides a recoverable program-directory transaction for tool install/update.
 * Tool data is intentionally outside this snapshot and survives program updates.
 *
 * Cleanup is best-effort: leftovers handled by next begin / recover / cleanupOrphans.
 * Any cleanup failure only logs, never crashes install/rollback.
 */
class InstallTransactionManager(
    private val pathManager: RuntimePathManager,
    private val logger: AppLogger,
) {
    suspend fun begin(distroId: String, toolId: String, preserveExisting: Boolean): InstallTransaction =
        withContext(Dispatchers.IO) {
            require(SAFE_TOOL_ID.matches(toolId)) { "Invalid tool ID: $toolId" }
            val safeDistro = distroId.lowercase().trim()
            pathManager.ensureDistroDirectories(safeDistro)
            val target = File(pathManager.taixuToolsDir(safeDistro), toolId)
            val transactionRoot = File(pathManager.taixuRootDir(safeDistro), ".transactions")
            transactionRoot.mkdirs()
            val snapshot = File(transactionRoot, "$toolId-${System.nanoTime()}")
            if (preserveExisting && target.isDirectory) {
                SafeFileTree.copy(target, snapshot)
            } else {
                safeDelete(target, "begin($safeDistro:$toolId)")
            }
            InstallTransaction(target = target, snapshot = snapshot.takeIf { it.exists() })
        }

    suspend fun commit(transaction: InstallTransaction) = withContext(Dispatchers.IO) {
        transaction.snapshot?.let { safeDelete(it, "commit(${transaction.target.name})") }
    }

    /** Restores the newest orphaned transaction after an app-process crash. */
    suspend fun recover(distroId: String, toolId: String, preserveExisting: Boolean): Boolean = withContext(Dispatchers.IO) {
        require(SAFE_TOOL_ID.matches(toolId)) { "Invalid tool ID: $toolId" }
        val safeDistro = distroId.lowercase().trim()
        val transactionRoot = File(pathManager.taixuRootDir(safeDistro), ".transactions")
        val candidates = transactionRoot.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("$toolId-") }
            .sortedByDescending { it.lastModified() }
        val target = File(pathManager.taixuToolsDir(safeDistro), toolId)
        if (preserveExisting && candidates.isNotEmpty()) {
            safeDelete(target, "recover($safeDistro:$toolId) target")
            SafeFileTree.copy(candidates.first(), target)
        } else if (!preserveExisting) {
            safeDelete(target, "recover($safeDistro:$toolId) target")
        }
        candidates.forEach { safeDelete(it, "recover($safeDistro:$toolId) snapshot") }
        candidates.isNotEmpty() || !preserveExisting
    }

    suspend fun cleanupOrphans(activeToolIds: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        val distroIds = pathManager.listInstalledDistroIds().ifEmpty { listOf("ubuntu") }
        distroIds.forEach { distroId ->
            val safeDistro = distroId.lowercase().trim()
            File(pathManager.taixuRootDir(safeDistro), ".transactions")
                .listFiles()
                .orEmpty()
                .filter { file -> activeToolIds.none { file.name.startsWith("$it-") } }
                .forEach { safeDelete(it, "cleanupOrphans($safeDistro)") }
        }
    }

    suspend fun rollback(transaction: InstallTransaction) = withContext(Dispatchers.IO) {
        safeDelete(transaction.target, "rollback(${transaction.target.name}) target")
        transaction.snapshot?.let { snapshot ->
            runCatching {
                SafeFileTree.copy(snapshot, transaction.target)
                check(transaction.target.exists()) { "Failed to restore tool program dir: ${transaction.target.name}" }
            }.onFailure { logger.e("Rollback restore dir failed: ${transaction.target.name}", it) }
            safeDelete(snapshot, "rollback(${transaction.target.name}) snapshot")
        }
    }

    private fun safeDelete(file: File, what: String) {
        runCatching { SafeFileTree.delete(file) }
            .onFailure { logger.e("Cleanup failed ($what): $file", it) }
    }

    private companion object {
        val SAFE_TOOL_ID = Regex("[a-z0-9][a-z0-9-]{1,63}")
    }
}

data class InstallTransaction(
    val target: File,
    val snapshot: File?,
)
