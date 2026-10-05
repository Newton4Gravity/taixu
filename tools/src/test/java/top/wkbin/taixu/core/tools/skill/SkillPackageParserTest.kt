package top.wkbin.taixu.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.wkbin.taixu.core.model.skill.SkillPermission
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillPackageParserTest {

    private val parser = SkillPackageParser()

    @Test
    fun parseFromZip_deconstructsTemplatesCorrectly() {
        val zipBytes = createTestZip(
            "SKILL.md" to """
                ---
                id: test-skill
                name: Test Deconstruction Skill
                version: 1.0.0
                description: Deconstruction skill package for unit tests
                author: TestAuthor
                category: Dev Engineering
                tags: test, kotlin
                permissions: [exec_command, file_write]
                required_tools: [git, python3]
                trigger_command: /test
                ---
                # Test Entry Overview
                This is the main entry description for the test skill.
            """.trimIndent(),
            "AGENT.md" to "You are a code review agent, strictly enforcing Lint rules.",
            "SOUL.md" to "Rigorous, meticulous, remain polite.",
            "TOOLS.md" to "Prefer read tool to inspect code.",
            "USER.md" to "Assume user is an experienced Android architect.",
            "MEMORY.md" to "Project uses Kotlin 2.4 with Jetpack Compose.",
            "scripts/run.sh" to "#!/bin/bash\necho hello",
        )

        val pkg = parser.parseFromZip(zipBytes)

        assertEquals("test-skill", pkg.manifest.id)
        assertEquals("Test Deconstruction Skill", pkg.manifest.name)
        assertEquals("1.0.0", pkg.manifest.version)
        assertEquals("TestAuthor", pkg.manifest.author)
        assertEquals("/test", pkg.manifest.triggerCommand)
        assertEquals(2, pkg.manifest.permissions.size)
        assertTrue(SkillPermission.EXEC_COMMAND in pkg.manifest.permissions)
        assertTrue(SkillPermission.FILE_WRITE in pkg.manifest.permissions)
        assertEquals(listOf("git", "python3"), pkg.manifest.requiredTools)

        // Verify template deconstruction
        assertNotNull(pkg.templates.skillMd)
        assertEquals("You are a code review agent, strictly enforcing Lint rules.", pkg.templates.agentMd)
        assertEquals("Rigorous, meticulous, remain polite.", pkg.templates.soulMd)
        assertEquals("Prefer read tool to inspect code.", pkg.templates.toolsMd)
        assertEquals("Assume user is an experienced Android architect.", pkg.templates.userMd)
        assertEquals("Project uses Kotlin 2.4 with Jetpack Compose.", pkg.templates.memoryMd)

        // Verify system prompt composition
        val systemPrompt = pkg.templates.composeSystemPrompt("/attachments/skills/test-skill")
        assertTrue(systemPrompt.contains("### 【Core Role & Instructions (AGENT)】"))
        assertTrue(systemPrompt.contains("### 【Persona Boundaries & Style (SOUL)】"))
        assertTrue(systemPrompt.contains("### 【Tool Usage Spec (TOOLS)】"))
        assertTrue(systemPrompt.contains("### 【User Interaction Contract (USER)】"))
        assertTrue(systemPrompt.contains("### 【Initialized Fact Memory (MEMORY)】"))
        assertTrue(systemPrompt.contains("Resource Physical Path: `/attachments/skills/test-skill`"))
    }

    @Test
    fun parseFromZip_blocksZipSlipAttack() {
        val evilZip = createTestZip(
            "../../etc/cron.d/evil" to "malicious script",
            "SKILL.md" to "# Normal Skill",
        )

        try {
            parser.parseFromZip(evilZip)
            fail("Should catch Zip Slip attack and throw exception")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Zip Slip") == true)
        }
    }

    @Test
    fun parseFromZip_rejectsMaliciousManifestId() {
        val evilZip = createTestZip(
            "SKILL.md" to """
                ---
                id: .
                name: Malicious Skill
                ---
                # Malicious Entry
            """.trimIndent(),
        )

        try {
            parser.parseFromZip(evilZip)
            fail("Should reject illegal frontmatter id")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("id") == true)
        }
    }

    @Test
    fun parseFromZip_sanitizesPathTraversalIdIntoSafeDirectoryComponent() {
        val traversalZip = createTestZip(
            "SKILL.md" to """
                ---
                id: ../evil
                name: Traversal Skill
                ---
                # Entry
            """.trimIndent(),
        )

        val pkg = parser.parseFromZip(traversalZip)
        assertTrue(pkg.manifest.id.matches(Regex("[a-z0-9_-]+")))
        assertTrue(!pkg.manifest.id.contains('/') && !pkg.manifest.id.contains('.'))
    }

    @Test
    fun parseFrontmatter_handlesComplexYamlScalars() {
        val markdown = """
            ---
            Name: "quoted-name"
            Description: >-
              Multi-line folded long description text
              Spans multiple lines merged into single line
            Author: 'Single Quoted' # This is an inline comment
            Category: System Ops
            ---
            Body content
        """.trimIndent()

        val meta = parser.parseFrontmatter(markdown)
        assertEquals("quoted-name", meta["name"])
        assertEquals("Multi-line folded long description text Spans multiple lines merged into single line", meta["description"])
        assertEquals("Single Quoted", meta["author"])
        assertEquals("System Ops", meta["category"])
    }

    private fun createTestZip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
