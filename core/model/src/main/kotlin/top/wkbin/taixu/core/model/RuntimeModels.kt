package top.wkbin.taixu.core.model

import kotlinx.serialization.Serializable

/**
 * CPU architecture enumeration for compatibility checks.
 */
enum class CpuArch {
    ARM64,
    X86_64,
    UNKNOWN;

    companion object {
        fun fromBuildAbi(abi: String): CpuArch = when (abi.lowercase()) {
            "arm64-v8a", "aarch64" -> ARM64
            "x86_64" -> X86_64
            else -> UNKNOWN
        }
    }
}

/**
 * Linux runtime state machine.
 */
sealed interface RuntimeState {
    data object NotInitialized : RuntimeState
    data class Initializing(
        val step: String,
        val progress: Float,
        val detail: String? = null
    ) : RuntimeState
    data object Ready : RuntimeState
    data class Error(val cause: Throwable) : RuntimeState
}

/**
 * Installed Linux distribution metadata.
 */
@Serializable
data class InstalledDistro(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val installedAt: Long,
    val isActive: Boolean,
    val packageManager: String,
    val statusText: String
)

/**
 * Runtime requirement specification for shared dependencies.
 */
@Serializable
data class RuntimeRequirement(
    val name: RuntimeName,
    val constraint: String? = null  // e.g., ">=18.0.0", "=3.11"
)

/**
 * Installed shared runtime information.
 */
@Serializable
data class InstalledRuntime(
    val id: String,
    val name: RuntimeName,
    val version: String?,
    val executablePath: String,
    val referenceCount: Int
)

/**
 * Supported shared runtime names.
 */
enum class RuntimeName {
    NODE,
    PYTHON,
    GIT,
    CA_CERTIFICATES,
    CURL
}

/**
 * Download progress tracking.
 */
@Serializable
data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val fraction: Float?,
    val currentFile: String? = null
) {
    val downloadedMegabytes: Float get() = downloadedBytes / (1024f * 1024f)
    val totalMegabytes: Float? get() = totalBytes?.let { it / (1024f * 1024f) }
}

/**
 * RootFS update information.
 */
@Serializable
data class RootfsUpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String?,
    val latestVersion: String?,
    val releaseNotes: String? = null
)