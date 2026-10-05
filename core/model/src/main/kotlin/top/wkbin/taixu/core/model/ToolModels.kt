package top.wkbin.taixu.core.model

import kotlinx.serialization.Serializable

/**
 * Tool manifest definition from registry or local plugin.
 */
@Serializable
data class ToolManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val latestVersion: String? = null,
    val publisher: String = "TaiXu",
    val category: String = "utility",
    val dependencies: List<String> = emptyList(),
    val launchType: String = "command",
    val commandLinks: List<String> = emptyList(),
    val servicePort: Int? = null,
    val servicePath: String = "/",
    val permissions: List<String> = emptyList(),
    val architectures: List<String> = listOf("ARM64"),
    val offlineOnly: Boolean = false,
    val installScript: String = "",
    val installMethod: String = "",
    val updateStrategy: String = "manual",
    val enabled: Boolean = true,
    val homepage: String = "",
    val minRuntimeVersion: String? = null
)

/**
 * Tool state in the local database.
 */
enum class ToolState {
    AVAILABLE,
    INSTALLING,
    INSTALLED,
    UPDATE_AVAILABLE,
    FAILED,
    DISABLED
}

/**
 * Tool verification result.
 */
@Serializable
data class ToolVerification(
    val toolId: String,
    val healthy: Boolean,
    val version: String? = null,
    val detail: String = ""
)

/**
 * Installation event types for progress tracking.
 */
sealed interface InstallEvent {
    data class Started(val toolId: String) : InstallEvent
    data class Progress(
        val toolId: String,
        val message: String,
        val progress: Float? = null,
        val phase: InstallPhase = InstallPhase.INSTALLING
    ) : InstallEvent
    data class Output(val toolId: String, val line: String) : InstallEvent
    data class Completed(
        val toolId: String,
        val version: String? = null
    ) : InstallEvent
    data class Failed(val toolId: String, val message: String) : InstallEvent
    data class RolledBack(val toolId: String) : InstallEvent
    data class Cancelled(val toolId: String) : InstallEvent
}

enum class InstallPhase {
    DOWNLOADING,
    EXTRACTING,
    INSTALLING,
    CONFIGURING,
    VERIFYING
}

/**
 * Tool action result for unified handling.
 */
sealed interface ToolActionResult {
    data class Success(
        val message: String = "Success",
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int = 0
    ) : ToolActionResult
    data class Failure(
        val message: String,
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int = -1
    ) : ToolActionResult
}