package top.wkbin.taixu.runtime

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.database.WorkspaceRepository
import top.wkbin.taixu.core.database.WorkspaceEntity
import top.wkbin.taixu.template.ProjectTemplateEngine
import top.wkbin.taixu.template.TemplateProjectType
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class ProjectType {
    ANDROID,
    FLUTTER,
    REVERSE,
    GENERAL;

    val displayName: String
        get() = when (this) {
            ANDROID -> "Android"
            FLUTTER -> "Flutter"
            REVERSE -> "APK Reverse"
            GENERAL -> "General"
        }
}

enum class ProjectImportSource {
    LOCAL_ARCHIVE,
    GITHUB,
    TEMPLATE,
}

enum class GitTransport {
    HTTP,
    SSH,
}

data class ProjectArchiveSource(
    val uri: String,
    val fileName: String,
)

enum class ProjectTemplate {
    EMPTY,
    APK_REVERSE,
    GIT_IMPORT;

    val displayName: String
        get() = when (this) {
            EMPTY -> "Empty Project"
            APK_REVERSE -> "APK Reverse"
            GIT_IMPORT -> "Import from Git"
        }
}

/**
 * APK reverse template installation package sources:
 * - [FromInstalledApp]: extract apk from installed app on device (applicationInfo.sourceDir);
 * - [FromFileUri]: select .apk file via system file manager (SAF OpenDocument).
 */
sealed class ApkImportSource {
    data class FromInstalledApp(
        val packageName: String,
        val appLabel: String,
    ) : ApkImportSource()

    data class FromFileUri(
        val uri: String,
        val fileName: String,
    ) : ApkImportSource()

    val displayName: String
        get() = when (this) {
            is FromInstalledApp -> "$appLabel ($packageName)"
            is FromFileUri -> fileName
        }
}

data class WorkspaceProject(
    val name: String,
    val path: String,
    val linuxPath: String,
    val sizeBytes: Long,
    val ownsDirectory: Boolean = true,
    val projectType: ProjectType = ProjectType.GENERAL,
    val packageName: String = "",
)

enum class WorkspaceStorage { INTERNAL, SHARED }

data class WorkspaceFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String = "",
)

/** Workspace: directories in app private mount point, metadata (path/created time) stored in Room. */
class WorkspaceManager(
    private val context: Context,
    private val pathManager: RuntimePathManager,
    private val workspaceDao: WorkspaceRepository,
    private val fileService: WorkspaceFileService,
    private val linuxRuntime: Lazy<LinuxRuntime>,
    private val projectTemplateEngine: ProjectTemplateEngine,
    private val buildScriptRepository: Lazy<top.wkbin.taixu.core.database.BuildScriptRepository>? = null,
) {
    constructor(
        pathManager: RuntimePathManager,
        workspaceDao: WorkspaceRepository,
        projectTemplateEngine: ProjectTemplateEngine,
    ) : this(
        ContextWrapper(null),
        pathManager,
        workspaceDao,
        WorkspaceFileService(pathManager, workspaceDao),
        lazy<LinuxRuntime> { error("Linux runtime is unavailable in this test constructor") },
        projectTemplateEngine,
        null,
    )
    fun observeProjects(): Flow<List<WorkspaceProject>> = workspaceDao.observeAll().map { entities ->
        val projectPaths = entities.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val filtered = entities.filter { entity ->
            val entityCanonical = runCatching { File(entity.path).canonicalPath }.getOrNull() ?: return@filter true
            projectPaths.none { otherPath ->
                otherPath != entityCanonical && otherPath.startsWith(entityCanonical + File.separator)
            }
        }
        filtered.mapNotNull(::projectFromEntity)
    }.flowOn(Dispatchers.IO)

    suspend fun listProjects(): List<WorkspaceProject> = withContext(Dispatchers.IO) {
        pathManager.workspaceDir.mkdirs()
        // Directory-based; missing directories backfilled from Room
        val known = workspaceDao.listAll().associateBy { it.name }
        val knownPaths = known.values.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val directories = pathManager.workspaceDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isValidProjectName(it.name) && !File(it, UNLINKED_MARKER).exists() }
        directories.forEach { directory ->
            if (directory.name !in known && directory.canonicalPath !in knownPaths) {
                workspaceDao.upsert(
                    WorkspaceEntity(directory.name, directory.absolutePath, System.currentTimeMillis()),
                )
            }
        }
        // Collect all valid entities first, then filter out entities that are parent directories of other projects
        // (avoid parent directory being incorrectly registered as independent project when creating nested projects)
        val allEntities = workspaceDao.listAll().filter { it.name in known || File(it.path).isDirectory }
        val projectPaths = allEntities.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val filtered = allEntities.filter { entity ->
            val entityCanonical = runCatching { File(entity.path).canonicalPath }.getOrNull() ?: return@filter true
            // If another project path has this entity path as prefix, this entity is a parent directory and should be filtered out
            projectPaths.none { otherPath ->
                otherPath != entityCanonical && otherPath.startsWith(entityCanonical + File.separator)
            }
        }
        filtered
            .filter { entity -> File(entity.path).isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull(::projectFromEntity)
    }

    suspend fun createProject(
        name: String,
        storage: WorkspaceStorage = WorkspaceStorage.INTERNAL,
        directoryPath: String = "",
        template: ProjectTemplate = ProjectTemplate.EMPTY,
        packageName: String = "",
        apkSource: ApkImportSource? = null,
        exportApkToDownload: Boolean = false,
        gitUrl: String = "",
        templateVariables: Map<String, String> = emptyMap(),
        templateId: String = "",
        trustTemplateScripts: Boolean = false,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        try {
            val safeName = name.trim()
            require(isValidProjectName(safeName)) { "Name must start with letter/digit, only letters, digits, dots, underscores, hyphens allowed" }
            require(safeName != "sdcard") { "sdcard is reserved system shared space name" }
            check(workspaceDao.findByName(safeName) == null) { "Project already exists: $safeName" }
            val resolvedTemplateId = templateId
            val resolvedManifest = resolvedTemplateId.takeIf(String::isNotBlank)?.let(projectTemplateEngine::inspect)
            resolvedManifest?.variables?.firstOrNull { it.name == "projectName" }?.let { variable ->
                require(!variable.required || safeName.isNotBlank()) { "Project name cannot be empty" }
                if (variable.validationRegex.isNotBlank()) {
                    require(Regex(variable.validationRegex).matches(safeName)) {
                        variable.description.ifBlank { "Project name does not match selected template format requirements" }
                    }
                }
            }
            pathManager.workspaceDir.mkdirs()
            val base = when (storage) {
                WorkspaceStorage.INTERNAL -> pathManager.workspaceDir
                WorkspaceStorage.SHARED -> SHARED_STORAGE_ROOT
            }
            check(base.isDirectory || base.mkdirs()) { "Associated space unavailable: ${base.absolutePath}" }
            val prefix = if (storage == WorkspaceStorage.INTERNAL) "/workspace/" else "/sdcard/"
            val requested = directoryPath.trim().replace('\\', '/').removePrefix(prefix).trim('/')
            val relative = requested.ifBlank { safeName }
            require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Associated directory contains invalid path" }
            val directory = File(base, relative).canonicalFile
            check(isInside(base.canonicalFile, directory) && directory != base.canonicalFile) { "Associated directory out of bounds" }
            val duplicate = workspaceDao.listAll().any {
                it.name != safeName && runCatching { File(it.path).canonicalFile == directory }.getOrDefault(false)
            }
            check(!duplicate) { "Directory already associated with another project" }
            val existed = directory.exists()
            if (template == ProjectTemplate.GIT_IMPORT) {
                require(isValidGitUrl(gitUrl)) { "Git repo URL must be HTTPS, SSH, or git@ address" }
                require(!existed || directory.isDirectory) { "Git import target is not a directory" }
                require(!existed || directory.listFiles().orEmpty().isEmpty()) { "Git import target directory must be empty" }
                if (!existed) require(directory.mkdirs()) { "Failed to create Git import directory" }
            } else {
                check((existed && directory.isDirectory) || (!existed && directory.mkdirs())) { "Failed to create or access associated directory" }
                if (template != ProjectTemplate.EMPTY || templateId.isNotBlank()) {
                    check(!existed || directory.listFiles().orEmpty().isEmpty()) { "Template target directory must be empty" }
                }
            }
            File(directory, UNLINKED_MARKER).delete()

            // Template initialization handling
            // APK reverse template: package name not user input, determined by imported apk (left empty if none)
            val needsPackageName = resolvedManifest?.variables.orEmpty().any {
                it.name == "packageName" || it.name == "packagePath"
            }
            var effectivePackage = ""
            if (needsPackageName) {
                val packageDefault = resolvedManifest?.variables?.firstOrNull { it.name == "packageName" }?.defaultValue.orEmpty()
                val cleanPkg = templateVariables["packageName"].orEmpty().trim()
                    .ifBlank { packageName.trim() }
                    .ifBlank { packageDefault }
                    .ifBlank { "com.example.${safeName.lowercase().filter { it.isLetterOrDigit() }}" }
                require(PACKAGE_NAME.matches(cleanPkg)) { "Package name must be valid Java/Kotlin package name: $cleanPkg" }
                effectivePackage = cleanPkg
            }
            if (resolvedTemplateId.isNotBlank() && resolvedManifest != null) {
                val suppliedValues = templateVariables.toMutableMap().apply {
                    this["projectName"] = safeName
                    this["projectPath"] = linuxPathFor(directory)
                    putIfAbsent("appName", safeName)
                    if (needsPackageName) {
                        this["packageName"] = effectivePackage
                        this["packagePath"] = effectivePackage.replace('.', '/')
                    } else {
                        remove("packageName")
                        remove("packagePath")
                    }
                }
                val values = projectTemplateEngine.resolvedValues(resolvedManifest, suppliedValues)
                val hasScripts = resolvedManifest.hooks.beforeCreate.isNotBlank() || resolvedManifest.hooks.afterCreate.isNotBlank()
                require(!hasScripts || trustTemplateScripts) { "This template contains build scripts, please review and explicitly authorize" }
                if (resolvedManifest.hooks.beforeCreate.isNotBlank()) {
                    executeTemplateHook(
                        resolvedTemplateId,
                        "before-create",
                        resolvedManifest.hooks.beforeCreate,
                        directory,
                        values,
                    )
                }
                projectTemplateEngine.materialize(
                    resolvedTemplateId,
                    directory,
                    values,
                )
                if (resolvedManifest.hooks.afterCreate.isNotBlank()) {
                    executeTemplateHook(
                        resolvedTemplateId,
                        "after-create",
                        resolvedManifest.hooks.afterCreate,
                        directory,
                        values,
                    )
                }
                projectTemplateEngine.validateMaterialized(resolvedTemplateId, directory, values)
                writeProjectTypeMetadata(
                    directory = directory,
                    type = when (resolvedManifest.projectType) {
                        TemplateProjectType.ANDROID -> ProjectType.ANDROID
                        TemplateProjectType.FLUTTER -> ProjectType.FLUTTER
                        TemplateProjectType.GENERAL -> ProjectType.GENERAL
                    },
                    source = ProjectImportSource.TEMPLATE,
                )
            } else when (template) {
                ProjectTemplate.APK_REVERSE -> {
                    val imported = importApkForReverse(directory, safeName, apkSource)
                    effectivePackage = imported.packageName
                    if (exportApkToDownload) {
                        exportApkToDownload(imported.apkFileName, directory, safeName)
                    }
                }
                ProjectTemplate.GIT_IMPORT -> cloneGitRepository(directory, gitUrl, cleanupOnFailure = !existed)
                ProjectTemplate.EMPTY -> { /* keep empty directory */ }
            }

            val ownsDirectory = storage == WorkspaceStorage.INTERNAL && !existed
            workspaceDao.upsert(
                WorkspaceEntity(safeName, directory.absolutePath, System.currentTimeMillis(), ownsDirectory),
            )
            val createdEntity = workspaceDao.findByName(safeName)
            if (createdEntity == null) {
                AppResult.Failure(AppError(ErrorCode.IO, "Project query failed after write", null))
            } else {
                val created = projectFromEntity(createdEntity)
                if (created == null) {
                    AppResult.Failure(AppError(ErrorCode.IO, "Project data conversion failed", null))
                } else {
                    AppResult.Success(created)
                }
            }
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Project creation failed", throwable))
        }
    }

    /**
     * Imports a ZIP project archive into an internal sandbox directory and records the user-selected
     * project label. Extraction always happens under /workspace and rejects path traversal entries.
     */
    suspend fun importProjectArchive(
        name: String,
        directoryPath: String = "",
        projectType: ProjectType,
        source: ProjectArchiveSource,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        importProject(name, directoryPath, projectType, ProjectImportSource.LOCAL_ARCHIVE) { directory, cleanupOnFailure ->
            try {
                extractProjectArchive(source, directory)
            } catch (throwable: Throwable) {
                if (cleanupOnFailure) SafeFileTree.delete(directory)
                throw throwable
            }
        }
    }

    /** Imports a GitHub repository over the explicitly selected HTTP(S) or SSH transport. */
    suspend fun importGithubProject(
        name: String,
        directoryPath: String = "",
        projectType: ProjectType,
        gitUrl: String,
        transport: GitTransport,
        onProgress: ((String) -> Unit)? = null,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        importProject(name, directoryPath, projectType, ProjectImportSource.GITHUB) { directory, cleanupOnFailure ->
            require(isValidGitUrlForTransport(gitUrl, transport)) {
                when (transport) {
                    GitTransport.HTTP -> "HTTP URL must start with http:// or https://"
                    GitTransport.SSH -> "SSH URL must use ssh:// or git@host:path format"
                }
            }
            cloneGitRepository(directory, gitUrl, cleanupOnFailure, onProgress)
        }
    }

    /** Compresses all regular project files and exports the ZIP into a SAF-selected local directory. */
    suspend fun exportProject(name: String, targetTreeUri: String): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            require(isValidProjectName(name)) { "Invalid project name" }
            val entity = workspaceDao.findByName(name) ?: error("Project not found: $name")
            val projectDir = File(entity.path).canonicalFile
            check(projectDir.isDirectory) { "Project directory not found: $name" }

            val treeUri = Uri.parse(targetTreeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val fileName = "$name-${System.currentTimeMillis()}.zip"
            val outputUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                "application/zip",
                fileName,
            ) ?: error("Failed to create export file in selected directory")
            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("Failed to write export file")
            output.use { stream -> writeProjectZip(projectDir, stream) }
            AppResult.Success(fileName)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Project export failed", throwable))
        }
    }

    suspend fun deleteProject(name: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            require(isValidProjectName(name)) { "Invalid project name" }
            val entity = workspaceDao.findByName(name) ?: error("Project not found: $name")
            val directory = File(entity.path)
            if (entity.ownsDirectory && directory.exists()) {
                SafeFileTree.delete(directory)
            } else if (directory.isDirectory) {
                File(directory, UNLINKED_MARKER).writeText("unlinkedAt=${System.currentTimeMillis()}\n")
            }
            workspaceDao.delete(name)
            runCatching { buildScriptRepository?.value?.unbind(name) }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Project deletion failed", throwable))
        }
    }

    suspend fun linuxWorkingDirectory(name: String): String {
        if (name == "sdcard") return "/sdcard"
        require(isValidProjectName(name)) { "Invalid project name" }
        val entity = workspaceDao.findByName(name) ?: error("Project not found: $name")
        check(File(entity.path).isDirectory) { "Associated directory not found: $name" }
        return linuxPathFor(File(entity.path))
    }

    /** Session-associated workspace directory; returns null if not associated. */
    suspend fun workspaceForName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return runCatching { linuxWorkingDirectory(name) }.getOrNull()
    }

    // ==================== In-Project File Management API ====================

    /**
     * Whether project root directory is under host shared storage (/storage/emulated/0).
     * Shared storage constrained by Android 11+ "All files access" permission: unauthorized
     * filters other apps' files (typically only lists folders), requires user permission grant.
     */
    suspend fun usesSharedStorage(projectName: String): Boolean = withContext(Dispatchers.IO) {
        if (projectName == "sdcard") return@withContext true
        val entityPath = workspaceDao.findByName(projectName)?.path ?: return@withContext false
        val canonical = runCatching { File(entityPath).canonicalPath }.getOrDefault(entityPath)
        val sharedRoot = runCatching { SHARED_STORAGE_ROOT.canonicalPath }.getOrDefault(SHARED_STORAGE_ROOT.absolutePath)
        canonical == sharedRoot || canonical.startsWith(sharedRoot + File.separator)
    }

    /** List all files and subdirectories under specified relative path in project (directories first). */
    suspend fun listFiles(projectName: String, relativePath: String = ""): AppResult<List<WorkspaceFileItem>> =
        fileService.listFiles(projectName, relativePath)

    /** Read file content (UTF-8, limit max read size per file). */
    suspend fun readFile(projectName: String, relativePath: String): AppResult<String> =
        fileService.readFile(projectName, relativePath)

    /** Write file content (atomic temp file replace). */
    suspend fun writeFile(projectName: String, relativePath: String, content: String): AppResult<Unit> =
        fileService.writeFile(projectName, relativePath, content)

    /** Create new file (empty). */
    suspend fun createFile(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.createFile(projectName, relativePath)

    /** Create new directory. */
    suspend fun createDirectory(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.createDirectory(projectName, relativePath)

    /** Rename file or directory. */
    suspend fun renameItem(projectName: String, oldRelativePath: String, newName: String): AppResult<Unit> =
        fileService.renameItem(projectName, oldRelativePath, newName)

    /** Delete file or directory. */
    suspend fun deleteItem(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.deleteItem(projectName, relativePath)

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.absolutePath == root.absolutePath ||
            candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun projectFromEntity(entity: WorkspaceEntity): WorkspaceProject? {
        val directory = File(entity.path)
        if (!directory.isDirectory) return null
        val type = detectProjectType(directory)
        val pkg = extractPackageName(directory, type)
        return WorkspaceProject(
            name = entity.name,
            path = entity.path,
            linuxPath = linuxPathFor(directory),
            sizeBytes = sizeOf(directory),
            ownsDirectory = entity.ownsDirectory,
            projectType = type,
            packageName = pkg,
        )
    }


    private fun detectProjectType(directory: File): ProjectType {
        readProjectTypeMetadata(directory)?.let { return it }
        return when {
            File(directory, "pubspec.yaml").exists() -> ProjectType.FLUTTER
            File(directory, "settings.gradle.kts").exists() ||
                File(directory, "app/build.gradle.kts").exists() ||
                File(directory, "build.gradle").exists() -> ProjectType.ANDROID
            // APK reverse: prefer import metadata marker, then fall back to .apk/unpacked dir
            File(directory, "apk-info.properties").isFile ||
                directory.listFiles().orEmpty().any { it.isFile && it.extension.equals("apk", ignoreCase = true) } ||
                (File(directory, "unpacked").isDirectory && File(directory, "unpacked").listFiles().orEmpty()
                    .any { it.isFile && it.name.startsWith("classes") && it.extension == "dex" }) -> ProjectType.REVERSE
            else -> ProjectType.GENERAL
        }
    }

    private fun extractPackageName(directory: File, type: ProjectType): String {
        return runCatching {
            when (type) {
                ProjectType.ANDROID -> {
                    val appBuild = File(directory, "app/build.gradle.kts").takeIf { it.exists() }
                        ?: File(directory, "app/build.gradle").takeIf { it.exists() }
                    val content = appBuild?.readText()
                    val namespaceMatch = Regex("""(?:namespace|applicationId)\s*=\s*["']([^"']+)["']""").find(content ?: "")
                    namespaceMatch?.groupValues?.get(1) ?: ""
                }
                ProjectType.FLUTTER -> {
                    val pubspec = File(directory, "pubspec.yaml").takeIf { it.exists() }
                    val nameMatch = Regex("""name:\s*([a-zA-Z0-9_]+)""").find(pubspec?.readText() ?: "")
                    nameMatch?.groupValues?.get(1) ?: ""
                }
                ProjectType.REVERSE -> {
                    val info = File(directory, "apk-info.properties").takeIf { it.exists() }?.readText().orEmpty()
                    Regex("""packageName\s*=\s*(.+)""").find(info)?.groupValues?.get(1)?.trim() ?: ""
                }
                ProjectType.GENERAL -> ""
            }
        }.getOrDefault("")
    }

    private fun isValidGitUrl(url: String): Boolean =
        url.trim().let { value ->
            value.startsWith("https://") || value.startsWith("http://") ||
                value.startsWith("ssh://") || Regex("^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:.+").matches(value)
        }

    private fun isValidGitUrlForTransport(url: String, transport: GitTransport): Boolean =
        url.trim().let { value ->
            when (transport) {
                GitTransport.HTTP -> value.startsWith("https://") || value.startsWith("http://")
                GitTransport.SSH -> value.startsWith("ssh://") ||
                    Regex("^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:.+").matches(value)
            }
        }

    private suspend fun cloneGitRepository(
        directory: File,
        url: String,
        cleanupOnFailure: Boolean,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val result = linuxRuntime.value.execute(
            ShellCommand(
                // --progress makes git output clone progress even in non-TTY pipes (remote:/Receiving objects: etc)
                commandLine = "git clone --depth 1 --progress -- ${shellQuote(url.trim())} ${shellQuote(linuxPathFor(directory))}",
                timeoutMs = GIT_CLONE_TIMEOUT_SECONDS * 1_000L,
                onOutput = onProgress,
            ),
        )
        check(result.isSuccess) {
            if (cleanupOnFailure) SafeFileTree.delete(directory)
            val output = (result.stderr + "\n" + result.stdout).trim().takeLast(1200)
            "Git clone failed: ${output.ifBlank { "Ensure Git is installed, repo URL and auth are valid" }}"
        }
        SafeFileTree.delete(File(directory, ".git/hooks"))
    }

    private suspend fun importProject(
        name: String,
        directoryPath: String,
        projectType: ProjectType,
        source: ProjectImportSource,
        materialize: suspend (directory: File, cleanupOnFailure: Boolean) -> Unit,
    ): AppResult<WorkspaceProject> {
        return try {
            val safeName = name.trim()
            require(isValidProjectName(safeName)) { "Name must start with letter/digit, only letters, digits, dots, underscores, hyphens allowed" }
            require(safeName != "sdcard") { "sdcard is reserved system shared space name" }
            check(workspaceDao.findByName(safeName) == null) { "Project already exists: $safeName" }
            pathManager.workspaceDir.mkdirs()
            val base = pathManager.workspaceDir.canonicalFile
            check(base.isDirectory || base.mkdirs()) { "Internal sandbox directory unavailable" }
            val requested = directoryPath.trim().replace('\\', '/').removePrefix("/workspace/").trim('/')
            val relative = requested.ifBlank { safeName }
            require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Associated directory contains invalid path" }
            val directory = File(base, relative).canonicalFile
            check(isInside(base, directory) && directory != base) { "Associated directory out of bounds" }
            val duplicate = workspaceDao.listAll().any {
                runCatching { File(it.path).canonicalFile == directory }.getOrDefault(false)
            }
            check(!duplicate) { "Directory already associated with another project" }
            val existed = directory.exists()
            require(!existed || directory.isDirectory) { "Import target is not a directory" }
            require(!existed || directory.listFiles().orEmpty().isEmpty()) { "Import target directory must be empty" }
            if (!existed) require(directory.mkdirs()) { "Failed to create import directory" }
            materialize(directory, !existed)
            writeProjectTypeMetadata(directory, projectType, source)
            workspaceDao.upsert(
                WorkspaceEntity(safeName, directory.absolutePath, System.currentTimeMillis(), ownsDirectory = !existed),
            )
            val createdEntity = workspaceDao.findByName(safeName)
            if (createdEntity == null) {
                AppResult.Failure(AppError(ErrorCode.IO, "Project query failed after write", null))
            } else {
                val created = projectFromEntity(createdEntity)
                if (created == null) {
                    AppResult.Failure(AppError(ErrorCode.IO, "Project data conversion failed", null))
                } else {
                    AppResult.Success(created)
                }
            }
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Project import failed", throwable))
        }
    }

    private fun extractProjectArchive(source: ProjectArchiveSource, directory: File) {
        require(source.fileName.endsWith(".zip", ignoreCase = true)) { "Local import currently only supports ZIP project archives" }
        val input = context.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: error("Failed to read selected project archive (URI permission may have expired, please reselect)")
        extractProjectArchive(input, source.fileName, directory)
    }

    internal fun extractProjectArchive(input: java.io.InputStream, fileName: String, directory: File) {
        require(fileName.endsWith(".zip", ignoreCase = true)) { "Local import currently only supports ZIP project archives" }
        val staging = File(directory, IMPORT_STAGING_DIRECTORY).canonicalFile
        check(isInside(directory.canonicalFile, staging)) { "Import staging directory out of bounds" }
        SafeFileTree.delete(staging)
        check(staging.mkdirs()) { "Failed to create import staging directory" }
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Archive contains too many files" }
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    require(normalizedName.isNotBlank()) { "Archive contains empty path" }
                    require(normalizedName.split('/').none { it == ".." }) { "Archive contains out-of-bounds path: ${entry.name}" }
                    val target = File(staging, normalizedName).canonicalFile
                    require(isInside(staging, target) && target != staging) { "Archive contains out-of-bounds path: ${entry.name}" }
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "Failed to create directory: ${entry.name}" }
                    } else {
                        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                            "Failed to create directory: ${entry.name}"
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                require(entryBytes <= MAX_ARCHIVE_ENTRY_BYTES) { "Archive single file too large: ${entry.name}" }
                                require(totalBytes <= MAX_ARCHIVE_TOTAL_BYTES) { "Archive extracted size too large" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(entryCount > 0) { "Project archive is empty" }
            val meaningful = staging.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            val contentRoot = meaningful.singleOrNull()?.takeIf { it.isDirectory } ?: staging
            val children = contentRoot.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            require(children.isNotEmpty()) { "Project archive has no importable files" }
            children.forEach { child ->
                val destination = File(directory, child.name).canonicalFile
                check(isInside(directory.canonicalFile, destination) && !destination.exists()) { "Import file conflict: ${child.name}" }
                check(child.renameTo(destination)) { "Failed to write import file: ${child.name}" }
            }
        } finally {
            SafeFileTree.delete(staging)
        }
    }

    private fun writeProjectZip(projectDir: File, output: java.io.OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            projectDir.walkTopDown()
                .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it != projectDir && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    val relative = file.toRelativeString(projectDir).replace(File.separatorChar, '/')
                    if (relative == UNLINKED_MARKER || relative == IMPORT_STAGING_DIRECTORY) return@forEach
                    val entryName = if (file.isDirectory) "$relative/" else relative
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun writeProjectTypeMetadata(directory: File, type: ProjectType, source: ProjectImportSource) {
        File(directory, PROJECT_METADATA_FILE).writeText(
            "type=${type.name}\nsource=${source.name}\nimportedAt=${System.currentTimeMillis()}\n",
            Charsets.UTF_8,
        )
    }

    private fun readProjectTypeMetadata(directory: File): ProjectType? = runCatching {
        val metadata = File(directory, PROJECT_METADATA_FILE)
        if (!metadata.isFile) return@runCatching null
        val typeName = metadata.useLines { lines ->
            lines.firstOrNull { it.startsWith("type=") }?.substringAfter("type=")?.trim()
        }
        ProjectType.entries.firstOrNull { it.name == typeName }
    }.getOrNull()

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private suspend fun executeTemplateHook(
        templateId: String,
        stage: String,
        relativePath: String,
        projectDir: File,
        values: Map<String, String>,
    ) {
        val hookFile = File(projectDir, ".taixu-template-$stage-${UUID.randomUUID()}.sh")
        try {
            hookFile.writeBytes(projectTemplateEngine.readHook(templateId, relativePath))
            hookFile.setExecutable(true)
            val result = linuxRuntime.value.execute(
                ShellCommand(
                    commandLine = "sh ${shellQuote(linuxPathFor(hookFile))}",
                    workingDirectory = linuxPathFor(projectDir),
                    environment = values.mapKeys { (name, _) -> "TAIXU_VAR_${name.uppercase()}" } +
                        ("TAIXU_PROJECT_DIR" to linuxPathFor(projectDir)),
                    timeoutMs = TEMPLATE_HOOK_TIMEOUT_MS,
                ),
            )
            check(result.isSuccess) {
                "Template script execution failed ($stage): ${result.stderr.ifBlank { result.stdout }.takeLast(2_000)}"
            }
        } finally {
            hookFile.delete()
        }
    }

    // ==================== APK Reverse Template ====================

    private data class ImportedApk(
        val packageName: String,
        val apkFileName: String,
        val sourceLabel: String,
        val sourceKind: String,
    )

    /**
     * APK reverse template init: import apk into project dir and do first layer "unpack".
     *
     * Output structure (using project name [name] as example):
     * ```
     * <project>/
     * ├── <name>.apk            # Original apk (can be directly fed to jadx/apktool/MT Manager)
     * ├── unpacked/             # Standard ZIP unpack output (dex/res/assets/lib/binary AXML)
     * │   ├── AndroidManifest.xml
     * │   ├── classes.dex
     * │   ├── resources.arsc
     * │   └── ...
     * ├── apk-info.properties   # Source and metadata (project package name read here)
     * └── REVERSE.md            # Reverse workflow guide (jadx/apktool/MCP)
     * ```
     */
    private fun importApkForReverse(
        projectDir: File,
        name: String,
        apkSource: ApkImportSource?,
    ): ImportedApk {
        requireNotNull(apkSource) { "APK reverse template must select apk source (installed app or apk file)" }
        projectDir.mkdirs()

        // 1. Resolve source and copy apk to project dir
        val apkFileName: String
        val sourceLabel: String
        val sourceKind: String
        val packageHint: String
        val apkFile: File
        when (apkSource) {
            is ApkImportSource.FromInstalledApp -> {
                val info = runCatching {
                    context.packageManager.getApplicationInfo(apkSource.packageName, 0)
                }.getOrElse { error("Failed to read installed app info: ${apkSource.packageName}") }
                val source = File(info.sourceDir)
                require(source.isFile) { "App apk not readable: ${source.absolutePath}" }
                apkFileName = "${apkSource.packageName}.apk"
                sourceLabel = apkSource.appLabel
                sourceKind = "installed-app"
                packageHint = apkSource.packageName
                apkFile = File(projectDir, apkFileName).canonicalFile
                check(isInside(projectDir.canonicalFile, apkFile)) { "APK output path out of bounds" }
                source.copyTo(apkFile, overwrite = true)
            }
            is ApkImportSource.FromFileUri -> {
                val uri = android.net.Uri.parse(apkSource.uri)
                val safeBase = apkSource.fileName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .trim('.')
                    .ifBlank { "target" }
                val displayName = if (safeBase.endsWith(".apk", ignoreCase = true)) safeBase else "$safeBase.apk"
                apkFileName = displayName
                sourceLabel = apkSource.displayName.ifBlank { displayName }
                sourceKind = "file-uri"
                packageHint = ""
                apkFile = File(projectDir, apkFileName).canonicalFile
                check(isInside(projectDir.canonicalFile, apkFile)) { "APK output path out of bounds" }
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to read selected APK file (URI permission may have expired, please reselect)")
                input.use { source ->
                    apkFile.outputStream().use { output -> source.copyTo(output) }
                }
            }
        }
        require(apkFile.isFile && apkFile.length() > 0) { "Apk import failed: $apkFileName" }

        // 2. Standard ZIP unpack -> unpacked/
        val unpackedDir = File(projectDir, "unpacked").apply { mkdirs() }
        unpackApk(apkFile, unpackedDir)

        // 3. Write metadata and reverse workflow guide
        File(projectDir, "apk-info.properties").writeText(
            buildString {
                appendLine("apk=${apkFile.name}")
                appendLine("apkSizeBytes=${apkFile.length()}")
                appendLine("source=$sourceKind")
                appendLine("sourceLabel=$sourceLabel")
                appendLine("packageName=$packageHint")
                appendLine("importedAt=${System.currentTimeMillis()}")
            },
            Charsets.UTF_8,
        )
        writeReverseReadme(projectDir, name, apkFile.name, unpackedDir, sourceLabel)

        return ImportedApk(
            packageName = packageHint,
            apkFileName = apkFileName,
            sourceLabel = sourceLabel,
            sourceKind = sourceKind,
        )
    }

    /** Use standard ZIP reader to unpack APK entry by entry to [unpackedDir] (prevents zip-slip path traversal). */
    private fun unpackApk(apkFile: File, unpackedDir: File) {
        val unpackedCanonical = unpackedDir.canonicalFile
        java.util.zip.ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val rawName = entry.name.replace('\\', '/')
                // Prevent zip-slip: reject absolute paths and .. traversal
                if (rawName.startsWith("/") || rawName.split('/').any { it == ".." }) return@forEach
                val target = File(unpackedDir, rawName)
                if (!isInside(unpackedCanonical, target.canonicalFile)) return@forEach
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** Generate reverse workflow README, connects to Taixu built-in jadx/apktool/reverse MCP capabilities. */
    private fun writeReverseReadme(
        projectDir: File,
        name: String,
        apkFileName: String,
        unpackedDir: File,
        sourceLabel: String,
    ) {
        val entryCount = unpackedDir.walkTopDown().count { it.isFile }
        File(projectDir, "REVERSE.md").writeText(
            """
            # $name · APK Reverse Project

            > Source: $sourceLabel
            Import time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}

            ## Project Structure

            | Path | Description |
            | :--- | :--- |
            | `$apkFileName` | Original apk (unchanged) |
            | `unpacked/` | First layer ZIP unpack output ($entryCount files): `classes.dex`, `resources.arsc`, `AndroidManifest.xml` (binary AXML), `res/`, `assets/`, `lib/` etc |
            | `apk-info.properties` | Source and metadata |

            ## Next Steps: Continue in Taixu Terminal / Agent

            Sandbox includes built-in reverse toolchain (Android & Mobile Full-Stack Dev Kit or apktool kit when assembled):

            ```bash
            # 1) DEX -> Java source (recommended, best readability)
            jadx -d java-src "$apkFileName"

            # 2) Full resource unpack + Smali (rebuildable)
            apktool d "$apkFileName" -o apktool-out
            #   Rebuild: apktool b apktool-out -o rebuilt.apk

            # 3) Binary manifest decode (with apktool output)
            #    aapt dump badging "$apkFileName"   # package name / version / permissions
            #    aapt dump xmltree "$apkFileName" AndroidManifest.xml
            ```

            Agent chat can also enable built-in **Android Reverse MCP Service** (`mcp_apktool`, enable in MCP settings):
            `decode_apk` / `analyze_manifest` / `extract_strings` / `search_smali` / `build_apk` / `sign_apk`.

            ## Analysis Focus Points

            - **AndroidManifest.xml**: 4 component export states, permissions, Application class
            - **classes.dex**: Core business logic (search URLs / keys / crypto after jadx decompile)
            - **lib/**: native .so (IDA / Xuanxing reverse SOMCP for deep analysis)
            - **assets/** and **res/**: embedded resources, configs, possible packer signatures

            > Note: if `unpacked/AndroidManifest.xml` shows garbled text, normal (AXML binary format),
            > decode with `apktool d` or `aapt dump xmltree`.

            ## Identify Packer (when jadx shows no real code)

            If `unpacked/classes.dex` decompiles to only packer stub loader, APK is packed. Check `lib/` so names to identify vendor:

            | Characteristic so | Packer Vendor |
            | :--- | :--- |
            | `libjiagu.so` / `libjiagu_art.so` | **360 Pack** (entry `com.stub.StubApp`) |
            | `libDexHelper.so` / `libSecShell.so` / `libsecexe.so` | **Bangcle (SecNeo)** (entry `com.secneo.apkwrapper.ApplicationWrapper`) |
            | `libshellx-super*.so` / `libtup.so` / `libexec.so` | **Tencent Legu / YUSecurity** (`com.tencent.StubShell`) |
            | `libnesec.so` | **NetEase Yidun** (`com.netease.nis.wrapper`) |
            | `ijiami.ajm` / `libexecmain.so` / `assets/ijm_lib/` | **Ijiami** (entry `s.h.e.l.l.S`) |
            | `libbaiduprotect.so` / `assets/baiduprotect*` | **Baidu Pack** |
            | `libzuma.so` / `assets/qihoo/` | **Ali Jusec** |
            | `libddog.so` / `libchaosvmp.so` | **Nagain (VMP Pack)** |
            | `libx3g.so` | **Dingxiang** |
            | `libkwscmm.so` / `libkwsgmain.so` | **Jiwei** |
            | `libnqshield.so` / `libmobisec.so` / `libkiroro.so` | NetQin / Ali old / Kiro etc |

            Auxiliary indicators: characteristic files in `assets/` (`ijiami.dat`, `bangcleplugin/`, `libjiagu*`, `appsealing*`), and AndroidManifest entry `android:name`.

            ## Packer Encountered: Unpack Guide

            | Pack Level | Characteristics | Unpack Solution |
            | :--- | :--- | :--- |
            | **Gen 1 Pack** (full dex encryption) | jadx only sees stub | **Generic unpack**: FRIDA-DEXDump (`frida -U -f pkg -l frida-dexdump.js`), BlackDex/FullDump (no-root one-click), MT Manager unpack plugin |
            | **Gen 2 Pack** (method extraction) | method body runtime backfill | **Active call unpack**: FART/Youpk/ReflectionMaster (custom ROM or Xposed-level framework triggers dump after each method backfill) |
            | **VMP Pack** (instruction virtualization, e.g. Nagain chaosvmp) | code virtualized | extremely hard to fully unpack, usually only **dynamic debug key logic** (Frida hook / Unidbg emulation) |

            Post-unpack: dumped `classesN.dex` may have broken header/checksum → fix dex header then `jadx` decompile; to modify logic, most packs allow patching smali/so in original APK then repack.
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }

    /**
     * Sync imported apk from project to host public download dir (best-effort, for host-side tools like MT Manager to read directly;
     * Android 11+ requires "All files access" permission, silently skips if unauthorized, does not affect project creation).
     */
    private fun exportApkToDownload(apkFileName: String, projectDir: File, projectName: String) {
        runCatching {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists() && !downloadDir.mkdirs()) return
            val source = File(projectDir, apkFileName)
            if (!source.isFile) return
            source.copyTo(File(downloadDir, "$projectName.apk"), overwrite = true)
        }
    }

    private fun linuxPathFor(directory: File): String {
        val canonical = directory.canonicalFile
        val internal = pathManager.workspaceDir.canonicalFile
        val shared = SHARED_STORAGE_ROOT.canonicalFile
        return when {
            isInside(internal, canonical) -> "/workspace/${canonical.toRelativeString(internal).replace(File.separatorChar, '/')}"
            isInside(shared, canonical) -> "/sdcard/${canonical.toRelativeString(shared).replace(File.separatorChar, '/')}"
            else -> error("Directory not in associable space")
        }.trimEnd('/')
    }

    private fun sizeOf(file: File): Long = file.walkTopDown()
        .onEnter { directory -> !java.nio.file.Files.isSymbolicLink(directory.toPath()) }
        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .sumOf { it.length() }

    private fun isValidProjectName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_PROJECT_NAME_LENGTH) return false
        if (!name.first().isLetterOrDigit()) return false
        return name.drop(1).all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    companion object {
        private const val UNLINKED_MARKER = ".taixu-unlinked-project"
        private const val PROJECT_METADATA_FILE = ".taixu-project.properties"
        private const val IMPORT_STAGING_DIRECTORY = ".taixu-import-staging"
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_ARCHIVE_ENTRY_BYTES = 1024L * 1024L * 1024L
        private const val MAX_ARCHIVE_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
        private const val GIT_CLONE_TIMEOUT_SECONDS = 15 * 60L
        private const val TEMPLATE_HOOK_TIMEOUT_MS = 60_000L
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
        const val MAX_PROJECT_NAME_LENGTH = 64
        const val MAX_FILE_READ_BYTES = 4 * 1024 * 1024L // 4 MB
        const val MAX_FILE_WRITE_CHARS = 4 * 1024 * 1024 // 4 M chars
        val SHARED_STORAGE_ROOT: File = File("/storage/emulated/0")
    }
}
