package top.wkbin.taixu.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.runtime.proot.ProotMountLayout

/** Risk describes deletion consequences; READONLY means managed by owning feature, not bulk file deletion. */
enum class StorageRiskLevel { SAFE, CAUTION, DANGEROUS, READONLY }

data class StorageEntry(
    val id: String,
    val name: String,
    val detail: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val path: String? = null,
    val subItems: List<StorageEntry> = emptyList(),
    val reclaimableBytes: Long = 0L,
)

data class StorageCategory(
    val id: String,
    val name: String,
    val description: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val entries: List<StorageEntry> = emptyList(),
    val reclaimableBytes: Long = 0L,
)

data class StorageUsage(
    val availableBytes: Long = 0L,
    val safeCleanableBytes: Long = 0L,
    val cautionCleanableBytes: Long = 0L,
    val categories: List<StorageCategory> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
    val projectCleanableBytes: Long = 0L,
    val scanWarnings: List<String> = emptyList(),
    val excludedMountPointCount: Int = 0,
) {
    /** File logical size, not equivalent to app disk allocation in Android settings. */
    val totalManagedBytes: Long get() = categories.sumOf { it.bytes }
}

/**
 * Each file assigned once by longest path rule. Scan produces cleanup plan; execution does not
 * resolve UI IDs to paths, does not recursively delete directories, and does not delete files
 * added/modified/replaced after scan. Unknown data always retained in owning category.
 */
class StorageManager(
    private val context: Context,
    private val pathManager: RuntimePathManager,
    private val runtime: Lazy<LinuxRuntime>,
) {
    private enum class Cleanup { NONE, OLD_LOG, DEPENDENCY, PROJECT_CACHE }
    private data class Rule(
        val root: Path,
        val category: String,
        val name: String,
        val hint: String,
        val cleanup: Cleanup = Cleanup.NONE,
        val risk: StorageRiskLevel = StorageRiskLevel.READONLY,
    ) {
        val id: String get() = "$category:$root"
    }
    private data class Target(val path: Path, val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?)
    private data class Plan(val rule: Rule, val targets: List<Target>)
    private val mutex = Mutex()
    private var plans: Map<String, Plan> = emptyMap()
    private var inspected = false
    private val categoryInfo = linkedMapOf(
        "projects" to ("Projects & Personal Files" to "Projects, personal dirs & project caches; source code and exports managed by workspace"),
        "environment" to ("Linux & Dev Environment" to "View system, SDK & shared runtimes per distro; uninstall via env or tool management"),
        "plugins" to ("Plugins & Model Data" to "Plugin programs, private data & models; reset/uninstall via owning tool"),
        "conversations" to ("Conversations & Attachments" to "Conversation DB, attachments & generated files; check references before deletion"),
        "cache" to ("Cache & Install Resources" to "Dependency caches cleanable on demand; install sources, staging & rollback backups kept separately"),
        "app_data" to ("App & Diagnostics" to "Configs, Skills, logs & other data; recommend cleaning expired archive logs"),
    )

    suspend fun inspect(): StorageUsage = withContext(Dispatchers.IO) { mutex.withLock { scan() } }

    private suspend fun scan(): StorageUsage {
        inspected = false
        plans = emptyMap()
        val now = System.currentTimeMillis()
        val coroutine = currentCoroutineContext()
        val rules = buildRules().sortedByDescending { it.root.nameCount }
        val totals = mutableMapOf<Rule, Long>()
        val targets = mutableMapOf<Rule, MutableList<Target>>()
        val warnings = mutableListOf<String>()
        val seenKeys = mutableSetOf<Any>()
        val roots = managedRoots()
        val mountCandidates = children(pathManager.distrosDir).filter { it.isDirectory }
            .flatMap { ProotMountLayout.placeholderCandidates(pathManager.rootfsDir(it.name)) }.toSet()
        val excludedMountPoints = mutableSetOf<Path>()
        fun excludeMountPoint(path: Path): Boolean {
            if (path !in mountCandidates || !safePath(path)) return false
            val stat = runCatching { Os.lstat(path.toString()) }.getOrNull() ?: return false
            val excluded = ProotMountLayout.isRestrictedPlaceholder(
                path, mountCandidates, OsConstants.S_ISDIR(stat.st_mode), stat.st_mode and 0xFFF,
                stat.st_uid, android.os.Process.myUid(),
            )
            if (excluded) excludedMountPoints.add(path)
            return excluded
        }
        for (root in roots) {
            if (!safePath(root) || !Files.exists(root, NOFOLLOW_LINKS)) continue
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    if (excludeMountPoint(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                    // Hard links of same inode counted once, not as reclaimable (may have other links).
                    val key = attrs.fileKey()
                    if (key != null && !seenKeys.add(key)) return FileVisitResult.CONTINUE
                    val rule = rules.firstOrNull { file.startsWith(it.root) } ?: return FileVisitResult.CONTINUE
                    totals[rule] = (totals[rule] ?: 0L) + attrs.size()
                    if (eligible(rule, file, attrs, now) && singleLink(file)) {
                        targets.getOrPut(rule) { mutableListOf() }.add(Target(file, attrs.size(), attrs.lastModifiedTime(), key))
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    if (excludeMountPoint(file)) return FileVisitResult.CONTINUE
                    if (warnings.size < 20) warnings.add(scanFailureDetails(file, exc))
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null && excludeMountPoint(dir)) return FileVisitResult.CONTINUE
                    if (exc != null && warnings.size < 20) warnings.add(scanFailureDetails(dir, exc))
                    return FileVisitResult.CONTINUE
                }
            })
        }
        val entries = totals.map { (rule, bytes) ->
            val reclaimable = targets[rule].orEmpty().sumOf { it.size }
            rule to StorageEntry(
                id = rule.id, name = rule.name, detail = rule.hint, bytes = bytes,
                cleanable = reclaimable > 0, riskLevel = rule.risk, cleanupHint = rule.hint,
                path = rule.root.toString(), reclaimableBytes = reclaimable,
            )
        }
        val categories = categoryInfo.map { (id, info) ->
            val children = entries.filter { it.first.category == id }.map { it.second }.sortedByDescending { it.bytes }
            val cleanable = children.filter { it.cleanable }
            StorageCategory(
                id = id, name = info.first, description = info.second, bytes = children.sumOf { it.bytes },
                cleanable = cleanable.isNotEmpty(),
                riskLevel = cleanable.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: StorageRiskLevel.READONLY,
                cleanupHint = "Only process cleanable items below that existed at scan time and remain unchanged. Deps need re-download, project caches need regeneration; stop active envs first.",
                entries = children, reclaimableBytes = children.sumOf { it.reclaimableBytes },
            )
        }
        plans = targets.mapKeys { it.key.id }.mapValues { (id, files) -> Plan(rules.first { it.id == id }, files.toList()) }
        inspected = true
        return StorageUsage(
            availableBytes = availableBytes(),
            categories = categories, scannedAt = now, scanWarnings = warnings,
            excludedMountPointCount = excludedMountPoints.size,
            safeCleanableBytes = entries.filter { it.second.riskLevel == StorageRiskLevel.SAFE }.sumOf { it.second.reclaimableBytes },
            cautionCleanableBytes = entries.filter { it.second.riskLevel == StorageRiskLevel.CAUTION }.sumOf { it.second.reclaimableBytes },
            projectCleanableBytes = entries.filter { it.first.cleanup == Cleanup.PROJECT_CACHE }.sumOf { it.second.reclaimableBytes },
        )
    }

    private fun buildRules(): List<Rule> = buildList {
        fun addRule(file: File, category: String, name: String, hint: String, cleanup: Cleanup = Cleanup.NONE,
                    risk: StorageRiskLevel = StorageRiskLevel.READONLY) {
            add(Rule(storagePath(file), category, name, hint, cleanup, risk))
        }
        val preserve = "Managed by owning feature; not directly bulk deleted"
        addRule(context.filesDir.parentFile!!, "app_data", "App Private Data", "Contains preferences, WebView, backup configs and other data, managed by owning feature")
        addRule(context.filesDir, "app_data", "App Config & Other Files", preserve)
        addRule(pathManager.baseDir, "app_data", "Runtime Metadata & Other Files", preserve)
        addRule(context.getDatabasePath("taixu.db").parentFile!!, "conversations", "Conversation & App Database", "Delete records via conversation management, keep DB and WAL/SHM files")
        runCatching { context.cacheDir }.getOrNull()?.let {
            addRule(it, "cache", "App Import & System Cache", "May contain files being imported, managed by owning feature")
        }
        runCatching { context.codeCacheDir }.getOrNull()?.let {
            addRule(it, "app_data", "App Code Cache", "Managed by Android")
        }
        addRule(pathManager.cacheDir, "cache", "Download & Offline Install Archives", "Keep offline install & downloading files; install management should confirm completion before deletion")
        addRule(File(context.filesDir, "plugins"), "cache", "Local Plugin Source Packages", "Reinstall and plugin recipes may depend on source packages, handle in plugin management")
        addRule(pathManager.stagingRootfsDir, "cache", "Global Install Staging", "Install transaction data, not part of one-click cleanup")
        addRule(pathManager.tmpDir, "app_data", "Runtime Temp Files", "May be used by PRoot and processes, not part of one-click cleanup")
        val logHint = "Only clean .log.gz / .log.N archive logs older than 7 days and unmodified; keep current logs"
        addRule(pathManager.logsDir, "app_data", "Runtime Logs", logHint, Cleanup.OLD_LOG, StorageRiskLevel.SAFE)
        addRule(pathManager.attachmentsDir, "conversations", "Conversation Attachments & Generated Files", "May be sole copy; verify in conversation or file management before deletion", risk = StorageRiskLevel.DANGEROUS)
        addRule(File(pathManager.attachmentsDir, "skills"), "app_data", "Custom Skills", preserve)
        addRule(pathManager.workspaceDir, "projects", "Workspace Other Files", "Contains exports and unrecognized projects; manage in workspace")
        children(pathManager.workspaceDir).filter { it.isDirectory }.forEach { project ->
            addRule(project, "projects", "Project ${project.name} · Files", "Source, deps, artifacts; no auto-delete by build/dist/out/target names")
            // Only recognize dedicated caches with project markers, do not infer custom build output paths.
            val gradle = File(project, "settings.gradle").isFile || File(project, "settings.gradle.kts").isFile ||
                File(project, "build.gradle").isFile || File(project, "build.gradle.kts").isFile
            if (gradle) addRule(File(project, ".gradle"), "projects", "Project ${project.name} · Gradle Cache",
                "Clean project .gradle cache; next build regenerates. Keep build, dist, out, target and source", Cleanup.PROJECT_CACHE, StorageRiskLevel.CAUTION)
            if (File(project, "pubspec.yaml").isFile) addRule(File(project, ".dart_tool"), "projects", "Project ${project.name} · Dart Cache",
                "Clean .dart_tool; need to re-run pub get and build", Cleanup.PROJECT_CACHE, StorageRiskLevel.CAUTION)
        }
        children(pathManager.distrosDir).filter { it.isDirectory }.forEach { distro ->
            val id = distro.name
            val rfs = pathManager.rootfsDir(id)
            val home = pathManager.homeDir(id)
            val taixu = pathManager.taixuRootDir(id)
            addRule(distro, "environment", "$id · System & Other Env Files", "Contains Linux system and manually installed software, size varies with usage")
            addRule(home, "projects", "$id · Personal Dir /root", "Independent persistent home mount; contains personal files, configs, and unidentified deps")
            addRule(File(rfs, "root"), "environment", "$id · Image Original /root", "This dir is overlaid by independent home mount, preserving original image data")
            addRule(File(rfs, "home"), "projects", "$id · Other User Dirs", preserve)
            addRule(pathManager.rootfsPreviousDir(id), "cache", "$id · Upgrade Rollback Backup", "Deletion loses rollback capability; handled by upgrade transaction", risk = StorageRiskLevel.DANGEROUS)
            addRule(pathManager.stagingRootfsDir(id), "cache", "$id · Install Staging", "May be installing or awaiting restore, not part of one-click cleanup")
            addRule(File(taixu, "imports"), "cache", "$id · Plugin Install Resources", "May be used by recipes or install transactions, not part of one-click cleanup")
            addRule(File(taixu, "flutter-cache-arm64"), "cache", "$id · Flutter Install Resources", "Handled by install management after confirmation")
            addRule(File(rfs, "tmp"), "app_data", "$id · Temp Files", "Process runtime data, not part of one-click cleanup")
            addRule(File(rfs, "var/tmp"), "app_data", "$id · Persistent Temp Files", "May need to persist across reboots, not part of one-click cleanup")
            addRule(File(rfs, "var/log"), "app_data", "$id · System Logs", logHint, Cleanup.OLD_LOG, StorageRiskLevel.SAFE)
            listOf(pathManager.taixuToolsDir(id), pathManager.taixuDataDir(id)).forEach { dir ->
                addRule(dir, "plugins", "$id · ${dir.name}", preserve)
                children(dir).forEach { plugin ->
                    addRule(plugin, "plugins", "$id · ${plugin.name} · ${if (dir.name == "data") "Data & Models" else "Program"}",
                        "Uninstall or reset via plugin feature to maintain reference and registry consistency")
                }
            }
            listOf(File(taixu, "toolchains"), pathManager.taixuRuntimesDir(id), File(taixu, "compat"), File(rfs, "opt")).forEach { dir ->
                children(dir).filter { it.name != "taixu" }.forEach { sdk ->
                    addRule(sdk, "environment", "$id · ${sdk.name}", "Dev toolkit or shared runtime; check plugin and project references before uninstall")
                }
            }
            listOf(".rustup", ".nvm", ".sdkman", ".pyenv").forEach {
                addRule(File(home, it), "environment", "$id · $it", "User-installed language env, managed by corresponding version manager")
            }
            addRule(File(home, ".gradle/wrapper/dists"), "environment", "$id · Gradle Wrapper Distributions",
                "Contains extracted runtime components and install markers; managed by full version, cannot delete only old files")
            addRule(File(home, ".gradle/caches"), "cache", "$id · Gradle Transform & Other Caches",
                "Some caches contain integrity metadata, managed by Gradle; downloaded deps listed separately")
            addRule(File(home, ".cache/yarn"), "cache", "$id · Yarn Cache",
                "May contain extracted packages and integrity markers, manage via Yarn")
            val dependencyHint = "Only delete cache files older than 7 days and unmodified; need network to re-download, offline builds may be affected. Stop terminal, builds, and services first."
            // Only archive/download caches; do not clean pub global activation, npm npx env, Go extracted source, or pnpm store.
            listOf(
                ".gradle/caches/modules-2/files-2.1" to "Gradle Download Deps",
                ".cache/pip" to "Pip Download Cache",
                ".npm/_cacache" to "NPM Download Cache",
                ".cargo/registry/cache" to "Cargo Download Archive",
                "go/pkg/mod/cache/download" to "Go Download Cache",
            ).forEach { (relative, name) ->
                addRule(File(home, relative), "cache", "$id · $name", dependencyHint, Cleanup.DEPENDENY, StorageRiskLevel.CAUTION)
            }
            addRule(File(rfs, "var/cache/apt/archives"), "cache", "$id · APT Downloaded Packages",
                dependencyHint, Cleanup.DEPENDENCY, StorageRiskLevel.CAUTION)
        }
    }

    private fun children(dir: File): List<File> = if (safePath(storagePath(dir))) dir.listFiles().orEmpty().filter {
        !Files.isSymbolicLink(it.toPath())
    } else emptyList()

    /** lstat inspects the entry itself, including dangling links, without following its target. */
    private fun scanFailureDetails(path: Path, error: IOException?): String = buildString {
        append("Incomplete read: ").append(path)
        append("\nError: ").append(error?.javaClass?.simpleName ?: "Unknown read error")
        (error as? FileSystemException)?.reason?.takeIf { it.isNotBlank() }?.let {
            append(" · ").append(it)
        }
        // Mirrors the useful metadata inspection in MTDataFilesProvider, not its SAF export.
        // No chmod: a scan must not alter guest permissions or expose app-private files.
        runCatching {
            val stat = Os.lstat(path.toString())
            append("\nPermissions: ").append(Integer.toOctalString(stat.st_mode and 0xFFF))
            append(" · UID：").append(stat.st_uid).append(" · GID：").append(stat.st_gid)
            append(" · App UID: ").append(android.os.Process.myUid())
            if (OsConstants.S_ISLNK(stat.st_mode)) {
                append("\nLink target: ").append(Os.readlink(path.toString()))
            } else if (OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == android.os.Process.myUid()) {
                val needed = OsConstants.S_IRUSR or OsConstants.S_IXUSR
                if (stat.st_mode and needed != needed) {
                    append("\nDirectory owner lacks read or execute permissions; different from shared storage auth.")
                }
            }
        }.onFailure {
            append("\nMetadata read failed: ").append(it.javaClass.simpleName)
            it.message?.let { message -> append(" · ").append(message) }
        }
    }

    /** Resolve only the trusted Android app root alias, never an arbitrary child symlink. */
    private fun storagePath(file: File): Path {
        val appRoot = context.filesDir.parentFile!!
        val logicalRoot = appRoot.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        return if (path.startsWith(logicalRoot)) appRoot.canonicalFile.toPath().resolve(logicalRoot.relativize(path)) else path
    }

    private fun managedRoots(): List<Path> {
        val paths = listOfNotNull(context.filesDir.parentFile, context.getDatabasePath("taixu.db").parentFile,
            runCatching { context.cacheDir }.getOrNull(), runCatching { context.codeCacheDir }.getOrNull())
            .map(::storagePath).distinct().sortedBy { it.nameCount }
        return paths.filter { path -> paths.none { it != path && path.startsWith(it) } }
    }

    /** Check all ancestors, not just leaf links; PRoot absolute/relative links not traversed. */
    private fun safePath(path: Path): Boolean {
        var cursor: Path? = path.toAbsolutePath().normalize()
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) return false
            cursor = cursor.parent
        }
        return true
    }

    private fun singleLink(path: Path): Boolean = try {
        (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1
    } catch (_: UnsupportedOperationException) {
        // Windows test FS does not provide unix attrs; Android/Linux support nlink.
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    } catch (_: IllegalArgumentException) {
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    } catch (_: IOException) { false }

    private fun eligible(rule: Rule, path: Path, attrs: BasicFileAttributes, now: Long): Boolean {
        if (rule.cleanup == Cleanup.NONE || now - attrs.lastModifiedTime().toMillis() < RETENTION_MS) return false
        val relative = rule.root.relativize(path)
        if (relative.any { it.toString() in setOf("partial", ".git") }) return false
        val name = path.fileName.toString()
        if (name == "lock" || name.endsWith(".lock") || name.endsWith(".part") || name.endsWith(".lck")) return false
        return rule.cleanup != Cleanup.OLD_LOG || name.endsWith(".log.gz") || Regex(".*\\.log\\.[0-9]+(?:\\.gz)?").matches(name)
    }

    suspend fun quickSafeClean(): AppResult<Long> = clean { it.rule.risk == StorageRiskLevel.SAFE }

    /** Legacy API name preserved: only clean recognized project caches, never guess build/dist/out/target purpose. */
    suspend fun cleanProjectBuildArtifacts(projectName: String? = null): AppResult<Long> = clean(requireMatch = projectName != null) { plan ->
        plan.rule.cleanup == Cleanup.PROJECT_CACHE && (projectName == null ||
            plan.rule.root.parent == storagePath(pathManager.workspaceDir).resolve(projectName))
    }

    suspend fun clearCategory(categoryId: String): AppResult<Unit> =
        clean(requireMatch = true) { it.rule.category == categoryId }.map { Unit }

    suspend fun clearEntry(categoryId: String, entryId: String): AppResult<Unit> =
        clean(requireMatch = true) { it.rule.category == categoryId && it.rule.id == entryId }.map { Unit }

    suspend fun clearCache(): AppResult<Unit> = clearCategory("cache")

    private suspend fun clean(requireMatch: Boolean = false, select: (Plan) -> Boolean): AppResult<Long> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    check(inspected) { "Please refresh storage usage and review cleanup scope first" }
                    val selected = plans.values.filter(select)
                    check(!requireMatch || selected.isNotEmpty()) { "This item cannot be cleaned directly or is stale, refresh and retry" }
                    var released = 0L
                    var skipped = 0
                    var failed = 0
                    runtime.value.withStorageCleanup {
                        for (plan in selected) for (target in plan.targets) {
                            currentCoroutineContext().ensureActive()
                            try {
                                check(target.path.startsWith(plan.rule.root) && safePath(target.path)) { "Path has changed" }
                                if (!Files.exists(target.path, NOFOLLOW_LINKS)) { skipped++; continue }
                                val attrs = Files.readAttributes(target.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                                if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != target.size ||
                                    attrs.lastModifiedTime() != target.modified || attrs.fileKey() != target.key ||
                                    !singleLink(target.path) || !eligible(plan.rule, target.path, attrs, System.currentTimeMillis())) {
                                    skipped++
                                    continue
                                }
                                if (Files.deleteIfExists(target.path)) released += target.size
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (_: Exception) { failed++ }
                        }
                    }
                    plans = emptyMap()
                    inspected = false
                    // Partial completion cannot pretend to be success, retain explicit result for UI.
                    if (failed > 0 || skipped > 0) AppResult.Failure(AppError(ErrorCode.IO,
                        "Deleted $released bytes; skipped $skipped changed/missing files, $failed failed. Refresh to see remaining usage"))
                    else AppResult.Success(released)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    AppResult.Failure(AppError(ErrorCode.IO, error.message ?: "Cleanup failed", error))
                }
            }
        }

    fun hasEnoughSpace(requiredBytes: Long): Boolean = availableBytes() >= requiredBytes
    private fun availableBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) { context.filesDir.usableSpace }

    private companion object { const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 }
}
