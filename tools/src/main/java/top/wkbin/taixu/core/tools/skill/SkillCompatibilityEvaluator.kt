package top.wkbin.taixu.core.tools.skill

import top.wkbin.taixu.core.model.skill.CompatibilityLevel
import top.wkbin.taixu.core.model.skill.SkillCompatibilityResult
import top.wkbin.taixu.core.model.skill.SkillPackage
import top.wkbin.taixu.core.tools.ToolRegistry

/**
 * Skill compatibility & dependency evaluator (statically assesses skill fit in current mobile PRoot sandbox and TaiXu environment).
 */
class SkillCompatibilityEvaluator(
    private val toolRegistry: ToolRegistry? = null,
) {
    /**
     * Evaluate skill package compatibility with current TaiXu runtime environment.
     */
    fun evaluate(pkg: SkillPackage): SkillCompatibilityResult {
        val notes = mutableListOf<String>()
        val missingTools = mutableListOf<String>()
        val unsupportedRuntimes = mutableListOf<String>()

        // 1. Runtime sandbox compatibility check
        val declaredRuntimes = pkg.manifest.compatibleRuntimes
        if (declaredRuntimes.isNotEmpty()) {
            val supportedInTaiXu = setOf("proot", "debian", "ubuntu", "alpine", "linux", "android", "arm64", "aarch64")
            val hasOverlap = declaredRuntimes.any { it.lowercase() in supportedInTaiXu }
            if (!hasOverlap) {
                unsupportedRuntimes.addAll(declaredRuntimes)
                notes.add("Skill declared target runtimes (${declaredRuntimes.joinToString()}) do not match TaiXu Android PRoot (arm64-v8a)")
            }
        }

        // 2. External command/tool dependency check
        val requiredTools = pkg.manifest.requiredTools
        if (requiredTools.isNotEmpty()) {
            val installedToolIds = toolRegistry?.load()?.map { it.id.lowercase() }?.toSet().orEmpty()
            requiredTools.forEach { tool ->
                val normalized = tool.trim().lowercase()
                val isBuiltinLinuxCommand = normalized in BUILTIN_SANDBOX_COMMANDS
                val isRegisteredInTaiXu = normalized in installedToolIds

                if (!isBuiltinLinuxCommand && !isRegisteredInTaiXu) {
                    missingTools.add(tool)
                    notes.add("Missing prerequisite tool dependency: `$tool` (installable in sandbox via apt or configurable in tool center via Recipe)")
                }
            }
        }

        // 3. Determine overall compatibility level
        val level = when {
            unsupportedRuntimes.isNotEmpty() -> CompatibilityLevel.INCOMPATIBLE
            missingTools.isNotEmpty() -> CompatibilityLevel.PARTIALLY_COMPATIBLE
            else -> CompatibilityLevel.COMPATIBLE
        }

        if (level == CompatibilityLevel.COMPATIBLE) {
            notes.add("Fully compatible with current TaiXu sandbox (Debian arm64-v8a), all prerequisites ready.")
        }

        return SkillCompatibilityResult(
            level = level,
            missingTools = missingTools,
            unsupportedRuntimes = unsupportedRuntimes,
            notes = notes,
        )
    }

    companion object {
        /** TaiXu Debian sandbox preinstalled or basic Linux core command list. */
        private val BUILTIN_SANDBOX_COMMANDS = setOf(
            "sh", "bash", "cat", "echo", "grep", "find", "sed", "awk",
            "ls", "cp", "mv", "rm", "mkdir", "chmod", "ps", "tar", "gzip",
            "taixu-build", "taixu",
        )
    }
}
