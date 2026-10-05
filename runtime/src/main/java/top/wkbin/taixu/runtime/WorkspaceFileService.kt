package top.wkbin.taixu.runtime

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.database.WorkspaceRepository

/** Boundary-safe file operations for a registered workspace. */
class WorkspaceFileService(
    private val pathManager: RuntimePathManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun listFiles(projectName: String, relativePath: String = ""): AppResult<List<WorkspaceFileItem>> = ioResult("Failed to read file list") {
        val directory = resolve(projectName, relativePath)
        check(directory.isDirectory) { "Not a directory: ${displayPath(projectName, relativePath)}" }
        val root = projectRoot(projectName)
        directory.listFiles().orEmpty().map { file ->
            WorkspaceFileItem(
                name = file.name,
                relativePath = file.toRelativeString(root).replace(File.separatorChar, '/'),
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
                extension = if (file.isFile) file.extension.lowercase() else "",
            )
        }.sortedWith(compareBy<WorkspaceFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun readFile(projectName: String, relativePath: String): AppResult<String> = ioResult("Failed to read file") {
        val file = resolve(projectName, relativePath)
        check(file.isFile) { "Not a file: ${displayPath(projectName, relativePath)}" }
        check(file.length() <= WorkspaceManager.MAX_FILE_READ_BYTES) {
            "File too large (${file.length()} bytes, limit ${WorkspaceManager.MAX_FILE_READ_BYTES / 1024 / 1024} MB)"
        }
        file.readText(Charsets.UTF_8)
    }

    suspend fun writeFile(projectName: String, relativePath: String, content: String): AppResult<Unit> = ioResult("Failed to save file") {
        require(content.length <= WorkspaceManager.MAX_FILE_WRITE_CHARS) {
            "Content too long (${content.length} chars, limit ${WorkspaceManager.MAX_FILE_WRITE_CHARS})"
        }
        val file = resolve(projectName, relativePath, allowMissing = true)
        require(!file.isDirectory) { "Target is a directory: ${displayPath(projectName, relativePath)}" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
    }

    suspend fun createFile(projectName: String, relativePath: String): AppResult<Unit> = ioResult("Failed to create file") {
        val file = resolve(projectName, relativePath, allowMissing = true)
        check(!file.exists()) { "File already exists: ${file.name}" }
        file.parentFile?.mkdirs()
        check(file.createNewFile()) { "Failed to create file" }
    }

    suspend fun createDirectory(projectName: String, relativePath: String): AppResult<Unit> = ioResult("Failed to create directory") {
        val directory = resolve(projectName, relativePath, allowMissing = true)
        check(!directory.exists()) { "Directory already exists: ${directory.name}" }
        check(directory.mkdirs()) { "Failed to create directory" }
    }

    suspend fun renameItem(projectName: String, oldRelativePath: String, newName: String): AppResult<Unit> = ioResult("Failed to rename") {
        val safeName = newName.trim()
        require(safeName.isNotBlank() && '/' !in safeName && '\\' !in safeName) { "Invalid new name" }
        val file = resolve(projectName, oldRelativePath)
        val target = File(file.parentFile, safeName)
        check(!target.exists()) { "Target already exists: $safeName" }
        check(file.renameTo(target)) { "Rename failed" }
    }

    suspend fun deleteItem(projectName: String, relativePath: String): AppResult<Unit> = ioResult("Failed to delete") {
        val file = resolve(projectName, relativePath)
        check(file.canonicalFile != projectRoot(projectName).canonicalFile) { "Cannot delete workspace root via this interface" }
        if (file.isDirectory) SafeFileTree.delete(file) else check(file.delete()) { "Failed to delete file" }
    }

    private suspend fun projectRoot(projectName: String): File {
        if (projectName == "sdcard") File("/storage/emulated/0").takeIf { it.exists() }?.let { return it }
        require(isValidProjectName(projectName)) { "Invalid project name: $projectName" }
        val entity = workspaceRepository.findByName(projectName) ?: error("Project not found: $projectName")
        return File(entity.path).also { check(it.isDirectory) { "Associated directory not found: $projectName" } }
    }

    private suspend fun resolve(projectName: String, relativePath: String, allowMissing: Boolean = false): File {
        val root = projectRoot(projectName)
        val trimmed = relativePath.trim().removePrefix("/workspace/$projectName").removePrefix("/sdcard").removePrefix("/")
        val segments = trimmed.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "Path contains out-of-bounds operator (..)" }
        val candidate = segments.fold(root) { parent, segment -> File(parent, segment) }
        val canonical = candidate.canonicalFile
        require(isInside(root.canonicalFile, canonical)) { "Path out of bounds: $relativePath" }
        if (!allowMissing) require(candidate.exists()) { "Target not found: ${displayPath(projectName, relativePath)}" }
        return candidate
    }

    private suspend fun displayPath(projectName: String, relativePath: String): String =
        if (projectName == "sdcard") "/sdcard/${relativePath.trimStart('/')}"
        else "${linuxPathFor(projectRoot(projectName))}/${relativePath.trimStart('/')}"

    private fun linuxPathFor(directory: File): String {
        val canonical = directory.canonicalFile
        val internal = pathManager.workspaceDir.canonicalFile
        val shared = WorkspaceManager.SHARED_STORAGE_ROOT.canonicalFile
        return when {
            isInside(internal, canonical) -> "/workspace/${canonical.toRelativeString(internal).replace(File.separatorChar, '/')}"
            isInside(shared, canonical) -> "/sdcard/${canonical.toRelativeString(shared).replace(File.separatorChar, '/')}"
            else -> error("Directory not in associable space")
        }.trimEnd('/')
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate == root || candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun isValidProjectName(name: String): Boolean = name.isNotEmpty() &&
        name.length <= WorkspaceManager.MAX_PROJECT_NAME_LENGTH && name.first().isLetterOrDigit() &&
        name.drop(1).all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private suspend fun <T> ioResult(fallback: String, block: suspend () -> T): AppResult<T> = withContext(Dispatchers.IO) {
        try {
            AppResult.Success(block())
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: fallback, throwable))
        }
    }
}
