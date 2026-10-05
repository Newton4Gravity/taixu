package top.wkbin.taixu.core.tools.skill

import top.wkbin.taixu.core.model.skill.AuditFinding
import top.wkbin.taixu.core.model.skill.AuditLevel
import top.wkbin.taixu.core.model.skill.SecurityAuditReport
import top.wkbin.taixu.core.model.skill.SkillPackage
import top.wkbin.taixu.core.model.skill.SkillPermission
import java.nio.charset.StandardCharsets

/**
 * Skill client-side static security auditor (aligned with PalmClaw client-side permission & security review system).
 *
 * Review dimensions:
 * 1. Structure & packaging defense: Zip Slip path traversal, zip bombs, anomalous ELF native binaries;
 * 2. Prompt injection & jailbreak review: System Override, DAN mode, covert instructions, prompt theft probes for LLMs;
 * 3. Linux destructive commands & malicious privilege escalation: rm -rf /, mkfs, dd disk overwrite, malicious reverse shell, curl | bash;
 * 4. Secret sniffing probes: detecting id_rsa keys, /etc/shadow, env var API_KEY;
 * 5. Permission boundary consistency inference: checks if declared permissions cover actual scripts and behaviors.
 */
class SkillPackageInspector {

    /**
     * Performs full offline static security audit on the given skill package.
     */
    fun inspect(pkg: SkillPackage): SecurityAuditReport {
        val findings = mutableListOf<AuditFinding>()

        // 1. Structure & packaging security check
        inspectArchiveStructure(pkg, findings)

        // 2. Native binary executable check
        inspectBinaryPayloads(pkg, findings)

        // 3. Static content review of prompts & scripts
        inspectTextContents(pkg, findings)

        // 4. Permission boundary inference & consistency audit
        val declaredPermissions = pkg.manifest.permissions.toSet()
        val detectedPermissions = inferRequiredPermissions(pkg)
        val undeclaredPermissions = detectedPermissions - declaredPermissions

        if (undeclaredPermissions.isNotEmpty()) {
            undeclaredPermissions.forEach { missing ->
                val level = if (missing.isHighRisk) AuditLevel.WARNING else AuditLevel.INFO
                findings.add(
                    AuditFinding(
                        level = level,
                        ruleId = "PERM-001",
                        title = "Undeclared sensitive permission: ${missing.label}",
                        detail = "Skill package contains permission-sensitive operations (${missing.description}), but manifest.permissions does not declare this permission",
                    ),
                )
            }
        }

        // Compute highest risk level
        val overallLevel = when {
            findings.any { it.level == AuditLevel.BLOCKED } -> AuditLevel.BLOCKED
            findings.any { it.level == AuditLevel.DANGER } -> AuditLevel.DANGER
            findings.any { it.level == AuditLevel.WARNING } -> AuditLevel.WARNING
            findings.any { it.level == AuditLevel.INFO } -> AuditLevel.INFO
            else -> AuditLevel.SAFE
        }

        return SecurityAuditReport(
            level = overallLevel,
            findings = findings.sortedByDescending { it.level.severity },
            declaredPermissions = declaredPermissions,
            detectedPermissions = detectedPermissions,
            undeclaredPermissions = undeclaredPermissions,
        )
    }

    private fun inspectArchiveStructure(pkg: SkillPackage, findings: MutableList<AuditFinding>) {
        pkg.rawFiles.keys.forEach { path ->
            if (path.contains("../") || path.startsWith("/") || path.contains("..\\")) {
                findings.add(
                    AuditFinding(
                        level = AuditLevel.BLOCKED,
                        ruleId = "SEC-001",
                        title = "Detected Zip Slip path traversal",
                        detail = "File path attempts to escape target directory: $path",
                        targetFile = path,
                    ),
                )
            }
        }

        if (pkg.rawFiles.size > SkillPackageParser.MAX_ZIP_ENTRIES) {
            findings.add(
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "SEC-002",
                    title = "Archive entry count exceeded",
                    detail = "Skill package file count (${pkg.rawFiles.size}) exceeds safety threshold (${SkillPackageParser.MAX_ZIP_ENTRIES})",
                ),
            )
        }

        val totalSize = pkg.rawFiles.values.sumOf { it.size.toLong() }
        if (totalSize > SkillPackageParser.MAX_ZIP_TOTAL_BYTES) {
            findings.add(
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "SEC-003",
                    title = "Decompressed size too large (suspected Zip Bomb)",
                    detail = "Decompressed size reached $totalSize bytes, exceeding safety threshold ${SkillPackageParser.MAX_ZIP_TOTAL_BYTES} bytes",
                ),
            )
        }
    }

    private fun inspectBinaryPayloads(pkg: SkillPackage, findings: MutableList<AuditFinding>) {
        pkg.rawFiles.forEach { (path, bytes) ->
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in DANGEROUS_EXTENSIONS) {
                findings.add(
                    AuditFinding(
                        level = AuditLevel.DANGER,
                        ruleId = "BIN-001",
                        title = "Contains potentially dangerous executable or library file",
                        detail = "Detected extension with native execution risk: .$ext",
                        targetFile = path,
                    ),
                )
            }

            // Check ELF magic 0x7F 'E' 'L' 'F'
            if (bytes.size >= 4 &&
                bytes[0] == 0x7F.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
            ) {
                findings.add(
                    AuditFinding(
                        level = AuditLevel.DANGER,
                        ruleId = "BIN-002",
                        title = "Contains unsigned Linux ELF binary",
                        detail = "Skill packages should primarily consist of prompts and generic scripts; embedded ELF binaries cannot be safely guaranteed across Android/PRoot architectures",
                        targetFile = path,
                    ),
                )
            }
        }
    }

    private fun inspectTextContents(pkg: SkillPackage, findings: MutableList<AuditFinding>) {
        val textsToCheck = buildMap {
            pkg.templates.skillMd?.let { put("SKILL.md", it) }
            pkg.templates.agentMd?.let { put("AGENT.md", it) }
            pkg.templates.soulMd?.let { put("SOUL.md", it) }
            pkg.templates.toolsMd?.let { put("TOOLS.md", it) }
            pkg.templates.userMd?.let { put("USER.md", it) }
            pkg.templates.memoryMd?.let { put("MEMORY.md", it) }

            pkg.rawFiles.forEach { (path, bytes) ->
                if (path.startsWith("scripts/", ignoreCase = true) || path.endsWith(".sh") || path.endsWith(".py") || path.endsWith(".js")) {
                    runCatching { put(path, bytes.toString(StandardCharsets.UTF_8)) }
                }
            }
        }

        textsToCheck.forEach { (filename, text) ->
            // 1. Jailbreak & Prompt Injection
            checkPromptInjections(filename, text, findings)

            // 2. Covert zero-width characters
            checkZeroWidthChars(filename, text, findings)

            // 3. Destructive Linux commands
            checkDestructiveCommands(filename, text, findings)

            // 4. Reverse shell & external code execution pipes
            checkReverseShellAndPipes(filename, text, findings)

            // 5. Sensitive privacy probing
            checkSensitiveFileProbes(filename, text, findings)
        }
    }

    private fun checkPromptInjections(filename: String, text: String, findings: MutableList<AuditFinding>) {
        PROMPT_INJECTION_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "INJ-001",
                    title = "Detected malicious Prompt Injection or jailbreak instruction",
                    detail = "Matched high-risk injection pattern: \"${match.value.take(40)}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }

        PROMPT_LEAK_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.DANGER,
                    ruleId = "INJ-002",
                    title = "Suspicious system prompt leak probe",
                    detail = "Detected inducement statement attempting to extract model System Prompt or hidden instructions",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun checkZeroWidthChars(filename: String, text: String, findings: MutableList<AuditFinding>) {
        val zeroWidthCount = text.count { it in ZERO_WIDTH_CHARS }
        if (zeroWidthCount > 10) {
            findings.add(
                AuditFinding(
                    level = AuditLevel.WARNING,
                    ruleId = "INJ-003",
                    title = "Detected anomalous zero-width invisible chars ($zeroWidthCount occurrences)",
                    detail = "Text contains large amount of zero-width spaces / hidden Unicode chars, possibly used to bypass static filters or conduct steganographic attacks",
                    targetFile = filename,
                ),
            )
        }
    }

    private fun checkDestructiveCommands(filename: String, text: String, findings: MutableList<AuditFinding>) {
        DESTRUCTIVE_COMMAND_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "CMD-001",
                    title = "Destructive sandbox or system command",
                    detail = "Detected dangerous instruction that could damage system/sandbox: \"${match.value.trim()}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }

        // Static obfuscation samples not directly blocked, but must prominently prompt manual review
        OBFUSCATION_HINT_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.WARNING,
                    ruleId = "CMD-004",
                    title = "Detected suspicious command obfuscation or encoding variant",
                    detail = "Text contains common obfuscation execution patterns (base64 decode pipe / eval nesting / ${IFS} concat / hex encoding / quote splitting), static audit cannot determine true intent: \"${match.value.trim().take(60)}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun checkReverseShellAndPipes(filename: String, text: String, findings: MutableList<AuditFinding>) {
        REVERSE_SHELL_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "CMD-002",
                    title = "Malicious reverse shell or external script silent pipe",
                    detail = "Detected high-risk network remote execution instruction: \"${match.value.trim()}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun checkSensitiveFileProbes(filename: String, text: String, findings: MutableList<AuditFinding>) {
        SENSITIVE_PROBE_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.DANGER,
                    ruleId = "CMD-003",
                    title = "Host/sandbox sensitive private file probing",
                    detail = "Detected instruction attempting to read SSH private keys, credentials, or system sensitive password files",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun reportMatches(
        filename: String,
        text: String,
        pattern: Regex,
        findings: MutableList<AuditFinding>,
        maxReportsPerPattern: Int = 3,
        build: (MatchResult) -> AuditFinding,
    ) {
        pattern.findAll(text).take(maxReportsPerPattern).forEach { match ->
            findings.add(build(match))
        }
    }

    private fun inferRequiredPermissions(pkg: SkillPackage): Set<SkillPermission> {
        val permissions = mutableSetOf<SkillPermission>()

        // Whether contains scripts or foreground command lines
        if (pkg.scripts.isNotEmpty() || pkg.rawFiles.keys.any { it.endsWith(".sh") || it.endsWith(".py") }) {
            permissions.add(SkillPermission.EXEC_COMMAND)
        }

        val allText = buildString {
            pkg.templates.skillMd?.let(::append)
            pkg.templates.agentMd?.let(::append)
            pkg.templates.soulMd?.let(::append)
            pkg.templates.toolsMd?.let(::append)
            pkg.templates.userMd?.let(::append)
            pkg.templates.memoryMd?.let(::append)
            pkg.rawFiles.forEach { (path, bytes) ->
                val lower = path.lowercase()
                val isTextFile = lower.endsWith(".sh") || lower.endsWith(".py") || lower.endsWith(".js") ||
                    lower.endsWith(".ts") || lower.endsWith(".txt") || lower.endsWith(".json") ||
                    lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".bash")
                if (isTextFile) {
                    runCatching { append(bytes.toString(StandardCharsets.UTF_8)) }
                }
            }
        }

        if (NETWORK_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.NETWORK)
        }
        if (FILE_WRITE_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.FILE_WRITE)
        }
        if (BROWSER_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.BROWSER_AUTOMATION)
        }
        if (PROCESS_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.BACKGROUND_SERVICE)
        }

        return permissions
    }

    private fun extractSnippet(text: String, charIndex: Int): String {
        val start = (charIndex - 30).coerceAtLeast(0)
        val end = (charIndex + 50).coerceAtMost(text.length)
        return "..." + text.substring(start, end).replace('\n', ' ').trim() + "..."
    }

    companion object {
        private val DANGEROUS_EXTENSIONS = setOf(
            "exe", "dll", "so", "dylib", "msi", "bat", "vbs", "cmd", "scr", "pif",
        )

        private val ZERO_WIDTH_CHARS = setOf(
            '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\uFEFF',
        )

        private val PROMPT_INJECTION_PATTERNS = listOf(
            Regex("""(?i)\b(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above)\s+(instructions|prompts|rules)"""),
            Regex("""(?i)\b(you are now|pretend to be|act as)\s+(an unregulated|jailbroken|DAN\b|developer mode|chaosgpt)"""),
            Regex("""(?i)\b(system prompt override|new instructions begin now|admin override mode)"""),
            Regex("""(?i)\b(bypass|disable)\s+(all\s+)?(safety|ethical|content)\s+(filters|guidelines|restrictions)"""),
            Regex("""(?i)do anything now\b"""),
        )

        private val PROMPT_LEAK_PATTERNS = listOf(
            Regex("""(?i)\b(reveal|output|display|print|leak)\s+(your|the)\s+(system prompt|initial prompt|hidden instructions)"""),
            Regex("""(?i)what are (your|the) exact (instructions|prompts) given to you above"""),
        )

        private val DESTRUCTIVE_COMMAND_PATTERNS = listOf(
            Regex("""(?i)\brm\s+(-[a-z0-9_-]*[rf][a-z0-9_-]*\s+|--recursive\s+|--force\s+)*(--no-preserve-root\s+)?(/|/\*|~|~/|~/\*|\$\{?HOME\}?|\$\{?HOME\}?/(|\*))(?=\s*($|[;\n&|)]|\s))"""),
            Regex("""\bmkfs(\.[a-z0-9]+)?\s+"""),
            Regex("""\bdd\s+.*\bof=/dev/(sd|hd|nvme|mmcblk|disk|zero)"""),
            Regex("""(?i)\bchmod\s+(-[a-z0-9_-]*[R][a-z0-9_-]*\s+)?777\s+(/|/etc|/bin|/usr|/var|/root)(/)?(?=\s*($|[;\n&|)]|\s))"""),
            Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""), // Fork Bomb
            Regex("""(?i)(bash|sh|zsh)\s+<\(\s*(curl|wget)\b"""), // Process substitution to execute remote script
        )

        private val REVERSE_SHELL_PATTERNS = listOf(
            Regex("""bash\s+-i\s+>&\s*/dev/tcp/"""),
            Regex("""nc(\.traditional)?\s+.*-e\s+(/bin/)?(bash|sh)"""),
            Regex("""(?i)\bcurl\s+.*\|\s*(sudo\s+)?(bash|sh)\b"""),
            Regex("""(?i)\bwget\s+.*\|\s*(sudo\s+)?(bash|sh)\b"""),
            Regex("""python[0-9.]*\s+-c\s+.*import\s+socket,subprocess"""),
        )

        /**
         * Common static obfuscation execution variants: blacklist cannot be exhaustive, only WARNING-level manual review hints.
         */
        private val OBFUSCATION_HINT_PATTERNS = listOf(
            Regex("""(?i)\bbase64\s+(-[a-z]+\s+)*-d.*\|\s*(sudo\s+)?(ba|z|da)?sh\b"""),
            Regex("""(?i)\beval\s+["']?\$\("""),
            Regex("""\$\{?IFS\}?"""),
            Regex("""(?i)printf\s+['"](\\x[0-9a-f]{2}){4,}"""),
            Regex("""\b[a-z]{1,6}(['"])\s*\1[a-z]{1,6}\b"""), // Adjacent quote fragments split command name: r""m → rm
        )

        private val SENSITIVE_PROBE_PATTERNS = listOf(
            Regex("""(?i)\b(cat|head|tail|less|grep)\s+.*(id_rsa|id_ed25519|\.ssh/|/etc/shadow|\.bash_history|\.env\b)"""),
            Regex("""(?i)\becho\s+${'$'}(API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|PASSWORD|TOKEN)\b"""),
        )

        private val NETWORK_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(curl|wget|httpclient|fetch|requests\.(get|post)|urllib)\b"""),
            Regex("""https?://[a-zA-Z0-9.-]+"""),
        )

        private val FILE_WRITE_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(write|edit|mkdir|touch|tee\b|>>?)\b"""),
            Regex("""open\([^)]+,\s*['"][wa]"""),
        )

        private val BROWSER_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(browser|cdp|playwright|puppeteer|page\.goto|dom_query)\b"""),
        )

        private val PROCESS_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(daemon|nohup|supervisord|systemctl|process\.start)\b"""),
        )
    }
}
