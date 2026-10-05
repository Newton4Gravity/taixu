package top.wkbin.taixu.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.skill.CompatibilityLevel
import top.wkbin.taixu.core.model.skill.PromptTemplateBundle
import top.wkbin.taixu.core.model.skill.SkillManifest
import top.wkbin.taixu.core.model.skill.SkillPackage

class SkillCompatibilityEvaluatorTest {

    private val evaluator = SkillCompatibilityEvaluator(toolRegistry = null)

    @Test
    fun evaluate_compatibleWhenDefaultSandboxCommandsRequired() {
        val manifest = SkillManifest(
            id = "test-compat",
            name = "Compat Test",
            description = "Depends on basic bash and ps",
            compatibleRuntimes = listOf("debian", "proot"),
            requiredTools = listOf("bash", "ps"),
        )
        val pkg = SkillPackage(manifest = manifest, templates = PromptTemplateBundle())

        val result = evaluator.evaluate(pkg)
        assertEquals(CompatibilityLevel.COMPATIBLE, result.level)
        assertTrue(result.isCompatible)
        assertTrue(result.missingTools.isEmpty())
    }

    @Test
    fun evaluate_partiallyCompatibleWhenMissingTool() {
        val manifest = SkillManifest(
            id = "test-missing-tool",
            name = "Missing Tool",
            description = "Depends on rustc and ffmpeg",
            compatibleRuntimes = listOf("debian"),
            requiredTools = listOf("rustc", "ffmpeg"),
        )
        val pkg = SkillPackage(manifest = manifest, templates = PromptTemplateBundle())

        val result = evaluator.evaluate(pkg)
        assertEquals(CompatibilityLevel.PARTIALLY_COMPATIBLE, result.level)
        assertTrue(result.isCompatible)
        assertTrue("rustc" in result.missingTools)
        assertTrue("ffmpeg" in result.missingTools)
    }

    @Test
    fun evaluate_incompatibleWhenRuntimeMismatched() {
        val manifest = SkillManifest(
            id = "test-incompatible",
            name = "Mismatched Env",
            description = "Only supports macOS",
            compatibleRuntimes = listOf("darwin", "macos"),
        )
        val pkg = SkillPackage(manifest = manifest, templates = PromptTemplateBundle())

        val result = evaluator.evaluate(pkg)
        assertEquals(CompatibilityLevel.INCOMPATIBLE, result.level)
        assertFalse(result.isCompatible)
        assertTrue(result.unsupportedRuntimes.contains("darwin"))
    }
}
