package top.wkbin.taixu.core.tools.skill

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.skill.AuditLevel
import top.wkbin.taixu.core.model.skill.SecurityAuditReport
import top.wkbin.taixu.core.model.skill.SkillCompatibilityResult
import top.wkbin.taixu.core.model.skill.SkillPackage
import java.io.File
import java.io.InputStream

/**
 * Skill installation inspection context (contains deconstructed skill package, client-side static security audit report, and compatibility evaluation result).
 */
data class SkillInstallInspection(
    val packageId: String,
    val pkg: SkillPackage,
    val auditReport: SecurityAuditReport,
    val compatibilityResult: SkillCompatibilityResult,
) {
    val isBlocked: Boolean get() = auditReport.isBlocked
    val canProceed: Boolean get() = !isBlocked
}

class SkillSecurityBlockedException(
    val report: SecurityAuditReport,
    message: String = "Skill package failed client-side static security audit, installation blocked by system",
) : SecurityException(message)

/**
 * Skill security audit & installation transaction manager (connects ClawHub marketplace, static audit engine, and TaiXu persistence repository).
 */
class SkillInstallationManager(
    private val packageParser: SkillPackageParser,
    private val inspector: SkillPackageInspector,
    private val compatibilityEvaluator: SkillCompatibilityEvaluator,
    private val clawHubClient: ClawHubClient,
    private val agentSkillRepository: AgentSkillRepository,
) {

    /**
     * Prepare and audit skill package from ClawHub marketplace.
     */
    suspend fun prepareMarketSkill(skillId: String): AppResult<SkillInstallInspection> {
        val downloadRes = clawHubClient.downloadPackage(skillId)
        if (downloadRes !is AppResult.Success) {
            return AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(top.wkbin.taixu.core.common.result.ErrorCode.DOWNLOAD, "Failed to download marketplace skill package"))
        }

        return runCatching {
            val inspection = inspectZipBytes(downloadRes.data, fallbackId = skillId)
            AppResult.Success(inspection)
        }.getOrElse { err ->
            AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(top.wkbin.taixu.core.common.result.ErrorCode.SECURITY, err.message ?: "Audit failed", err))
        }
    }

    /**
     * Perform deconstruction and client-side static security audit on ZIP byte stream.
     */
    fun inspectZipBytes(zipBytes: ByteArray, fallbackId: String = "custom_skill"): SkillInstallInspection {
        val pkg = packageParser.parseFromZip(zipBytes, fallbackId)
        return inspectPackage(pkg)
    }

    /**
     * Perform deconstruction and client-side static security audit on local skill directory.
     */
    fun inspectDirectory(dir: File): SkillInstallInspection {
        val pkg = packageParser.parseFromDirectory(dir)
        return inspectPackage(pkg)
    }

    /**
     * Perform complete security and compatibility evaluation on deconstructed skill package.
     */
    fun inspectPackage(pkg: SkillPackage): SkillInstallInspection {
        val auditReport = inspector.inspect(pkg)
        val compatibility = compatibilityEvaluator.evaluate(pkg)

        return SkillInstallInspection(
            packageId = pkg.manifest.id,
            pkg = pkg,
            auditReport = auditReport,
            compatibilityResult = compatibility,
        )
    }

    /**
     * Commit installation: safely unzip skill package that passed audit to disk, and register to AgentSkillRepository.
     *
     * @param inspection Inspection context
     * @param targetSkillsDir Host skill installation root dir (usually attachments/skills)
     * @param guestPrefix Sandbox mount path prefix (usually /attachments/skills)
     */
    suspend fun commitInstallation(
        inspection: SkillInstallInspection,
        targetSkillsDir: File,
        guestPrefix: String = "/attachments/skills",
    ): AgentSkill {
        if (inspection.isBlocked) {
            throw SkillSecurityBlockedException(inspection.auditReport)
        }

        // Re-audit in-memory package before commit to prevent caller passing tampered audit result
        val pkg = inspection.pkg
        val reReport = inspector.inspect(pkg)
        if (reReport.isBlocked) {
            throw SkillSecurityBlockedException(reReport)
        }

        val skillId = pkg.manifest.id
        if (!SKILL_ID_PATTERN.matches(skillId)) {
            throw SecurityException("Skill id contains illegal chars, installation rejected: $skillId")
        }

        // First write to isolated staging dir, then atomic rename on success: rollback only deletes staging dir, never touches other skills
        val stagingDir = File(targetSkillsDir, "$skillId.staging_${java.util.UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        var renamed = false
        val targetDir = File(targetSkillsDir, skillId)
        val canonicalTarget = stagingDir.canonicalPath

        try {
            pkg.rawFiles.forEach { (relPath, bytes) ->
                val safePath = relPath.trimStart('/')
                val destFile = File(stagingDir, safePath)
                val canonicalDest = destFile.canonicalPath
                if (!canonicalDest.startsWith(canonicalTarget + File.separator)) {
                    throw SecurityException("Detected illegal file write escape: $relPath")
                }
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
            }

            if (targetDir.exists()) targetDir.deleteRecursively()
            if (!stagingDir.renameTo(targetDir)) {
                throw java.io.IOException("Skill dir staging rename failed: ${stagingDir.absolutePath} -> ${targetDir.absolutePath}")
            }
            renamed = true

            val guestPath = guestPrefix.trimEnd('/') + "/$skillId"
            val composedPrompt = pkg.templates.composeSystemPrompt(resourceGuestPath = guestPath)

            val agentSkill = AgentSkill(
                id = "custom_$skillId",
                name = pkg.manifest.name,
                description = pkg.manifest.description,
                systemPrompt = composedPrompt,
                triggerCommand = pkg.manifest.triggerCommand,
                iconName = pkg.manifest.icon,
                isEnabled = true,
                isBuiltin = false,
                isImmutable = false,
                category = pkg.manifest.category,
                resourcePath = targetDir.absolutePath,
            )

            agentSkillRepository.addCustom(agentSkill)
            return agentSkill
        } catch (e: Throwable) {
            if (renamed) {
                targetDir.deleteRecursively()
            } else {
                stagingDir.deleteRecursively()
            }
            throw e
        }
    }

    companion object {
        // Consistent with SkillPackageParser.sanitizeSkillId output charset (sanitized result may start with _)
        // Explicit full anchor: even if caller mistakenly uses containsMatchIn/find semantics, will not allow path traversal like "../../etc"
        private val SKILL_ID_PATTERN = Regex("^[a-z0-9_-]+$")
    }
}
