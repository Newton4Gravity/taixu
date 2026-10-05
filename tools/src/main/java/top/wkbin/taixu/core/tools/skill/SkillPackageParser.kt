package top.wkbin.taixu.core.tools.skill

import top.wkbin.taixu.core.model.skill.PromptTemplateBundle
import top.wkbin.taixu.core.model.skill.SkillManifest
import top.wkbin.taixu.core.model.skill.SkillPackage
import top.wkbin.taixu.core.model.skill.SkillPermission
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Skill package standard deconstruction & parser (aligned with PalmClaw standard directory & declarative template spec).
 */
class SkillPackageParser {

    /**
     * Parse skill package from local directory.
     * Shares same protection limits (entry count/single file/total) as ZIP path; directory source is equally untrusted,
     * unbounded walkTopDown + readBytes would OOM on huge directories.
     */
    fun parseFromDirectory(dir: File): SkillPackage {
        require(dir.isDirectory) { "Specified path is not a valid directory: ${dir.absolutePath}" }

        val files = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        var fileCount = 0
        dir.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { file ->
            fileCount++
            if (fileCount > MAX_ZIP_ENTRIES) {
                throw SecurityException("File count in skill directory exceeds limit ($MAX_ZIP_ENTRIES)")
            }
            if (file.length() > MAX_SINGLE_ENTRY_BYTES) {
                throw SecurityException("Single file in skill directory exceeds size limit $MAX_SINGLE_ENTRY_BYTES bytes: ${file.name}")
            }
            totalBytes += file.length()
            if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                throw SecurityException("Total skill directory size exceeds limit $MAX_ZIP_TOTAL_BYTES bytes")
            }
            files[file.relativeTo(dir).invariantSeparatorsPath] = file.readBytes()
        }

        return parseFromFiles(files, fallbackId = dir.name)
    }

    /**
     * Parse skill package from ZIP byte array (includes Zip Slip path traversal defense).
     */
    fun parseFromZip(zipBytes: ByteArray, fallbackId: String = "skill_" + UUID.randomUUID().toString().take(8)): SkillPackage {
        return parseFromZipStream(ByteArrayInputStream(zipBytes), fallbackId)
    }

    /**
     * Parse skill package from ZIP input stream.
     */
    fun parseFromZipStream(inputStream: InputStream, fallbackId: String = "skill_" + UUID.randomUUID().toString().take(8)): SkillPackage {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            var totalBytes = 0L
            var entryCount = 0

            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw SecurityException("Entry count in skill archive exceeds limit ($MAX_ZIP_ENTRIES)")
                }

                val name = entry.name.replace('\\', '/')
                // Defend against Zip Slip path traversal
                if (name.contains("../") || name.startsWith("/") || name.contains("/../")) {
                    throw SecurityException("Detected illegal Zip Slip path traversal attack: $name")
                }

                if (!entry.isDirectory) {
                    val entryBos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytes = 0L

                    while (true) {
                        val remainingTotal = MAX_ZIP_TOTAL_BYTES - totalBytes
                        val remainingEntry = MAX_SINGLE_ENTRY_BYTES - entryBytes
                        if (remainingTotal <= 0L || remainingEntry <= 0L) {
                            throw SecurityException("Skill decompression exceeds size limit (single file limit $MAX_SINGLE_ENTRY_BYTES bytes, total limit $MAX_ZIP_TOTAL_BYTES bytes)")
                        }
                        val toRead = minOf(buffer.size.toLong(), remainingTotal, remainingEntry).toInt()
                        val read = zip.read(buffer, 0, toRead)
                        if (read < 0) break

                        entryBytes += read
                        totalBytes += read
                        entryBos.write(buffer, 0, read)

                        if (entryBytes >= MAX_SINGLE_ENTRY_BYTES || totalBytes >= MAX_ZIP_TOTAL_BYTES) {
                            if (zip.read() != -1) {
                                throw SecurityException("Skill decompression exceeds size limit (single file limit $MAX_SINGLE_ENTRY_BYTES bytes, total limit $MAX_ZIP_TOTAL_BYTES bytes)")
                            }
                            break
                        }
                    }
                    files[name] = entryBos.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // If all files wrapped in single root dir (e.g., `my-skill/...`), strip that prefix
        val normalizedFiles = stripCommonRootDirectory(files)
        return parseFromFiles(normalizedFiles, fallbackId)
    }

    /**
     * Parse canonical skill package structure from flattened path map.
     */
    fun parseFromFiles(files: Map<String, ByteArray>, fallbackId: String): SkillPackage {
        // Find SKILL.md or prompt.md
        val skillEntry = files.entries.firstOrNull { (path, _) ->
            path.equals("SKILL.md", ignoreCase = true) || path.equals("prompt.md", ignoreCase = true)
        } ?: files.entries.firstOrNull { (path, _) ->
            path.endsWith("/SKILL.md", ignoreCase = true) || path.endsWith("/prompt.md", ignoreCase = true)
        }

        val rawSkillMarkdown = skillEntry?.value?.toString(StandardCharsets.UTF_8).orEmpty()
        val frontmatter = parseFrontmatter(rawSkillMarkdown)

        // Extract deconstruction templates
        val agentMd = findTextContent(files, "AGENT.md")
        val soulMd = findTextContent(files, "SOUL.md")
        val toolsMd = findTextContent(files, "TOOLS.md")
        val userMd = findTextContent(files, "USER.md")
        val memoryMd = findTextContent(files, "MEMORY.md")

        val scripts = files.keys.filter { it.startsWith("scripts/", ignoreCase = true) }
        val references = files.keys.filter { it.startsWith("references/", ignoreCase = true) }

        // id used to construct install dir name, must sanitize to safe charset to prevent "../", "." path injection
        val id = frontmatter["id"]
            ?.takeIf { it.isNotBlank() }
            ?.let(::sanitizeSkillId)
            ?: run {
                // fallback (dir name/caller provided) sanitize failure does not block parse, falls back to generated stable id
                runCatching { sanitizeSkillId(fallbackId) }.getOrElse {
                    "skill_" + UUID.randomUUID().toString().take(8)
                }
            }

        val name = frontmatter["name"]
            ?: extractFirstHeading(rawSkillMarkdown)
            ?: agentMd?.let(::extractFirstHeading)
            ?: fallbackId

        val description = frontmatter["description"]
            ?: "TaiXu Declarative Agent Skill Package"

        val version = frontmatter["version"] ?: "1.0.0"
        val author = frontmatter["author"] ?: "Community"
        val license = frontmatter["license"]
        val icon = frontmatter["icon"] ?: "Sparkles"
        val category = frontmatter["category"] ?: "General"
        val triggerCommand = frontmatter["trigger_command"] ?: frontmatter["triggercommand"]

        val tags = (frontmatter["tags"] ?: "")
            .split(',', ';', ' ')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }

        val requiredTools = (frontmatter["required_tools"] ?: frontmatter["requiredtools"] ?: "")
            .removePrefix("[").removeSuffix("]")
            .split(',', ';', ' ')
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }

        val permissions = parsePermissions(frontmatter["permissions"])

        val manifest = SkillManifest(
            id = id,
            name = name,
            version = version,
            description = description,
            author = author,
            license = license,
            icon = icon,
            category = category,
            tags = tags,
            permissions = permissions,
            requiredTools = requiredTools,
            triggerCommand = triggerCommand,
        )

        val bundle = PromptTemplateBundle(
            skillMd = stripFrontmatter(rawSkillMarkdown).takeIf { it.isNotBlank() },
            agentMd = agentMd,
            soulMd = soulMd,
            toolsMd = toolsMd,
            userMd = userMd,
            memoryMd = memoryMd,
            extraFiles = files.filterKeys { k ->
                !k.equals("SKILL.md", ignoreCase = true) &&
                    !k.equals("AGENT.md", ignoreCase = true) &&
                    !k.equals("SOUL.md", ignoreCase = true) &&
                    !k.equals("TOOLS.md", ignoreCase = true) &&
                    !k.equals("USER.md", ignoreCase = true) &&
                    !k.equals("MEMORY.md", ignoreCase = true)
            }.mapValues { it.value.toString(StandardCharsets.UTF_8) },
        )

        return SkillPackage(
            manifest = manifest,
            templates = bundle,
            scripts = scripts,
            references = references,
            rawFiles = files,
        )
    }

    private fun sanitizeSkillId(raw: String): String {
        val sanitized = raw.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        if (sanitized.isBlank() || sanitized.all { it == '_' }) {
            throw SecurityException("Skill package declared id is invalid, parsing rejected: $raw")
        }
        return sanitized
    }

    private fun findTextContent(files: Map<String, ByteArray>, fileName: String): String? {
        val entry = files.entries.firstOrNull { (k, _) ->
            k.equals(fileName, ignoreCase = true) || k.endsWith("/$fileName", ignoreCase = true)
        } ?: return null
        return entry.value.toString(StandardCharsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }

    private fun parsePermissions(raw: String?): List<SkillPermission> {
        if (raw.isNullOrBlank()) return emptyList()
        val tokens = raw.removePrefix("[").removeSuffix("]")
            .split(',', ';', ' ')
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }

        return tokens.mapNotNull { SkillPermission.fromIdOrNull(it) }
    }

    private fun extractFirstHeading(markdown: String): String? =
        markdown.lineSequence()
            .firstOrNull { it.trim().startsWith("# ") }
            ?.substringAfter("# ")
            ?.trim()

    /**
     * Parse YAML Frontmatter.
     */
    fun parseFrontmatter(markdown: String): Map<String, String> {
        var text = markdown
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        if (!text.startsWith("---")) return emptyMap()

        val lines = text.lineSequence().drop(1)
            .takeWhile { it.trim() != "---" && it.trim() != "..." }
            .toList()

        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(':')) {
                index++
                continue
            }
            val key = trimmed.substringBefore(':').trim().lowercase()
            var value = trimmed.substringAfter(':').trim()
            index++

            // Folded block scalar indicators (>-, | etc.)
            if (value.startsWith(">") || value.startsWith("|")) {
                val block = StringBuilder()
                while (index < lines.size && (lines[index].startsWith(" ") || lines[index].startsWith("\t") || lines[index].isBlank())) {
                    block.append(' ').append(lines[index].trim())
                    index++
                }
                value = block.toString().trim()
            } else {
                if (value.startsWith("\"") && value.contains("\" #")) {
                    val endQuote = value.indexOf('"', 1)
                    if (endQuote > 0) value = value.substring(1, endQuote)
                } else if (value.startsWith("'") && value.contains("' #")) {
                    val endQuote = value.indexOf('\'', 1)
                    if (endQuote > 0) value = value.substring(1, endQuote)
                } else {
                    val comment = value.indexOf(" #")
                    if (comment >= 0) value = value.substring(0, comment).trim()
                    value = value.trim('"', '\'')
                }
            }
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    /**
     * Strip YAML Frontmatter header from Markdown.
     */
    fun stripFrontmatter(markdown: String): String {
        var text = markdown
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        if (!text.startsWith("---")) return text

        val lines = text.lines()
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
        return if (endIdx != -1) {
            lines.drop(endIdx + 2).joinToString("\n").trim()
        } else {
            text
        }
    }

    private fun stripCommonRootDirectory(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val rootDirs = files.keys.mapNotNull { path ->
            val slash = path.indexOf('/')
            if (slash > 0) path.substring(0, slash) else null
        }.toSet()

        if (rootDirs.size == 1 && files.keys.all { it.contains('/') }) {
            val root = rootDirs.first() + "/"
            return files.mapKeys { (k, _) -> k.removePrefix(root) }
        }
        return files
    }

    companion object {
        const val MAX_ZIP_ENTRIES = 500
        const val MAX_SINGLE_ENTRY_BYTES = 10L * 1024 * 1024 // 10MB
        const val MAX_ZIP_TOTAL_BYTES = 50L * 1024 * 1024 // 50MB
    }
}
