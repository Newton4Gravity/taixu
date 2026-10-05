package top.wkbin.taixu.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import top.wkbin.taixu.core.model.ToolState

/**
 * Tool entity for Room database.
 */
@Entity(tableName = "tools")
data class ToolEntity(
    @PrimaryKey val id: String,
    val distroId: String,
    val name: String,
    val description: String,
    val dependencies: String = "",
    val launchType: String = "command",
    val state: String = ToolState.AVAILABLE.name,
    val manifestVersion: String = "",
    val installedVersion: String? = null,
    val publisher: String = "TaiXu",
    val category: String = "utility",
    val permissions: String = "",
    val homepage: String = "",
    val updateStrategy: String = "manual",
    val latestVersion: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Install log entity for tracking installation history.
 */
@Entity(tableName = "install_logs")
data class InstallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val distroId: String,
    val toolId: String,
    val event: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Install task entity for tracking ongoing/failed installations.
 */
@Entity(tableName = "install_tasks")
data class InstallTaskEntity(
    @PrimaryKey val toolId: String,
    val distroId: String,
    val operation: String,  // INSTALL, UPDATE, UNINSTALL
    val state: String,      // RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED
    val message: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Runtime entity for shared runtime tracking.
 */
@Entity(tableName = "runtimes")
data class RuntimeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String?,
    val executablePath: String,
    val state: String = "INSTALLED",
    val referenceCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Runtime reference entity for tracking which tools use which runtimes.
 */
@Entity(tableName = "runtime_references", primaryKeys = ["runtimeId", "toolId"])
data class RuntimeReferenceEntity(
    val runtimeId: String,
    val toolId: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Storage mount binding entity.
 */
@Entity(tableName = "storage_mount_bindings")
data class StorageMountBindingEntity(
    @PrimaryKey val id: String,
    val distroId: String,
    val name: String,
    val hostPath: String,
    val guestPath: String,
    val enabled: Boolean = true,
    val readOnly: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Workspace entity.
 */
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val templateId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Build script entity.
 */
@Entity(tableName = "build_scripts")
data class BuildScriptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val scriptContent: String,
    val category: String = "build",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Terminal session entity.
 */
@Entity(tableName = "terminal_sessions")
data class TerminalSessionEntity(
    @PrimaryKey val id: String,
    val distroId: String,
    val name: String,
    val workingDirectory: String = "/root",
    val commandLine: String = "/bin/bash -i",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Tool settings entity for per-tool configuration.
 */
@Entity(tableName = "tool_settings")
data class ToolSettingsEntity(
    @PrimaryKey val toolId: String,
    val distroId: String,
    val settingsJson: String = "{}",
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Plugin entity for local plugin management.
 */
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val manifestJson: String,
    val archivePath: String? = null,
    val installedAt: Long = System.currentTimeMillis()
)

/**
 * Skill entity for skill management.
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestJson: String,
    val installedAt: Long = System.currentTimeMillis()
)