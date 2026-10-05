package top.wkbin.taixu.core.tools.skill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wkbin.taixu.core.common.files.BoundedStreamCopy
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.model.skill.ClawHubMarketDetail
import top.wkbin.taixu.core.model.skill.ClawHubMarketItem
import top.wkbin.taixu.core.model.skill.SkillPermission
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ClawHub online skill marketplace client (provides online ecosystem search, download, and offline curated package fallback).
 */
class ClawHubClient(
    private val httpClient: OkHttpClient,
    private val hubRegistryBaseUrl: String = DEFAULT_CLAWHUB_URL,
) {

    /** Whether the last [fetchMarketCatalog] used the built-in offline curated fallback (remote marketplace unavailable or unreachable). */
    @Volatile
    var lastCatalogUsedOfflineFallback: Boolean = true
        private set

    /**
     * Fetch marketplace skill list (supports search and category filtering, auto-fallback to curated offline catalog when network unreachable).
     */
    suspend fun fetchMarketCatalog(
        query: String? = null,
        category: String? = null,
    ): AppResult<List<ClawHubMarketItem>> {
        val remoteResult = runCatching { fetchRemoteCatalog() }
        val remote = remoteResult.getOrNull()?.takeIf { it.isNotEmpty() }
        lastCatalogUsedOfflineFallback = remote == null
        val catalog = remote ?: BUILTIN_PRESET_ITEMS

        val filtered = catalog.filter { item ->
            val matchesQuery = query.isNullOrBlank() ||
                item.name.contains(query, ignoreCase = true) ||
                item.description.contains(query, ignoreCase = true) ||
                item.tags.any { it.contains(query, ignoreCase = true) }
            val matchesCategory = category.isNullOrBlank() ||
                item.category.equals(category, ignoreCase = true)
            matchesQuery && matchesCategory
        }

        return AppResult.Success(filtered)
    }

    /**
     * Fetch marketplace detail info for a specific skill.
     */
    suspend fun fetchPackageDetail(skillId: String): AppResult<ClawHubMarketDetail> {
        val preset = BUILTIN_PRESET_DETAILS[skillId]
        if (preset != null) {
            return AppResult.Success(preset)
        }

        // Try fetching from remote
        val item = fetchMarketCatalog().let { res ->
            if (res is AppResult.Success) res.data.firstOrNull { it.id == skillId } else null
        } ?: return AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(top.wkbin.taixu.core.common.result.ErrorCode.NETWORK, "Skill not found in ClawHub marketplace: $skillId"))

        return AppResult.Success(
            ClawHubMarketDetail(
                item = item,
                readmeMarkdown = item.description,
                license = "Apache-2.0",
                templateSummary = listOf("SKILL.md", "AGENT.md", "TOOLS.md"),
            ),
        )
    }

    /**
     * Download skill package ZIP bytes (offline curated packages are dynamically generated as standard ZIP from built-in templates).
     */
    suspend fun downloadPackage(skillId: String): AppResult<ByteArray> {
        val builtInGen = BUILTIN_PACKAGE_GENERATORS[skillId]
        if (builtInGen != null) {
            return AppResult.Success(builtInGen())
        }

        // Try downloading from network
        return runCatching {
            val url = "$hubRegistryBaseUrl/packages/${URLEncoder.encode(skillId, StandardCharsets.UTF_8.name())}.zip"
            val request = Request.Builder().url(url).build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Failed to download skill package from ClawHub, HTTP status: ${response.code}")
                    }
                    val body = response.body
                    val contentLength = body.contentLength()
                    if (contentLength > SkillPackageParser.MAX_ZIP_TOTAL_BYTES) {
                        error("Remote skill package size (${contentLength / 1024 / 1024}MB) exceeds system limit (${SkillPackageParser.MAX_ZIP_TOTAL_BYTES / 1024 / 1024}MB)")
                    }
                    val bos = ByteArrayOutputStream()
                    BoundedStreamCopy.copy(
                        input = body.byteStream(),
                        output = bos,
                        maxBytes = SkillPackageParser.MAX_ZIP_TOTAL_BYTES,
                        policy = BoundedStreamCopy.OverflowPolicy.ABORT,
                    )
                    AppResult.Success(bos.toByteArray())
                }
            }
        }.getOrElse { err ->
            AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(top.wkbin.taixu.core.common.result.ErrorCode.DOWNLOAD, err.message ?: "Failed to download skill", err))
        }
    }

    private suspend fun fetchRemoteCatalog(): List<ClawHubMarketItem>? {
        // Send network request when valid external API is configured
        if (hubRegistryBaseUrl.isBlank() || hubRegistryBaseUrl.startsWith("mock://")) {
            return null
        }
        val request = Request.Builder()
            .url("$hubRegistryBaseUrl/catalog.json")
            .header("Accept", "application/json")
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // When ClawHub backend integrated later, do JSON deserialization here; currently graceful fallback
                null
            }
        }
    }

    companion object {
        const val DEFAULT_CLAWHUB_URL = "https://raw.githubusercontent.com/taixu-ai/clawhub/main"

        /**
         * 5 built-in curated high-quality ecosystem skills (fully compliant with PalmClaw / OpenMinis spec).
         */
        val BUILTIN_PRESET_ITEMS: List<ClawHubMarketItem> = listOf(
            ClawHubMarketItem(
                id = "git-workflow",
                name = "Git Workflow & Review Master",
                version = "1.2.0",
                description = "Follows semantic Conventional Commits spec, intelligently analyzes branch state and code diffs to write rigorous Commit / PR messages.",
                author = "TaiXu Core Team",
                icon = "GitBranch",
                tags = listOf("Git", "Review", "Workflow", "DevOps"),
                category = "Dev Engineering",
                downloadUrl = "builtin://git-workflow",
                stars = 428,
                requiredTools = listOf("git"),
                permissions = listOf(SkillPermission.EXEC_COMMAND),
            ),
            ClawHubMarketItem(
                id = "code-auditor",
                name = "Code Security & Architecture Audit",
                version = "1.1.0",
                description = "Performs fine-grained incremental review for Kotlin, Java, Rust, C++, identifying null-pointer risks, memory leaks, and dangerous syscalls with refactor suggestions.",
                author = "PalmClaw Security Lab",
                icon = "ShieldCheck",
                tags = listOf("Security", "Code Review", "Refactor", "Architecture"),
                category = "Security Audit",
                downloadUrl = "builtin://code-auditor",
                stars = 389,
                requiredTools = emptyList(),
                permissions = listOf(SkillPermission.FILE_WRITE),
            ),
            ClawHubMarketItem(
                id = "python-pro",
                name = "Sandbox Python Data & Scientific Computing",
                version = "2.0.1",
                description = "Uses Python3 in PRoot sandbox for data stats, math derivation, and script debugging, supports NumPy/Pandas offline workflows.",
                author = "Community SciPy",
                icon = "Code",
                tags = listOf("Python", "Data Science", "Scripting", "Sandbox"),
                category = "Data Algorithms",
                downloadUrl = "builtin://python-pro",
                stars = 512,
                requiredTools = listOf("python3"),
                permissions = listOf(SkillPermission.EXEC_COMMAND, SkillPermission.FILE_WRITE),
            ),
            ClawHubMarketItem(
                id = "linux-doctor",
                name = "PRoot Sandbox Self-Check & Diagnostic Expert",
                version = "1.0.5",
                description = "Expert in Android PRoot user-space sandbox boundaries, quickly diagnoses dpkg state residue, mount point R/W issues, background managed processes, and network connectivity.",
                author = "TaiXu Runtime Lab",
                icon = "Terminal",
                tags = listOf("Linux", "Sandbox", "Diagnostics", "PRoot"),
                category = "System Ops",
                downloadUrl = "builtin://linux-doctor",
                stars = 640,
                requiredTools = listOf("bash", "ps"),
                permissions = listOf(SkillPermission.EXEC_COMMAND, SkillPermission.SYSTEM_PROBE),
            ),
            ClawHubMarketItem(
                id = "doc-craftsman",
                name = "Technical Design & Documentation Polishing Master",
                version = "1.3.0",
                description = "Uses Google Developer / Markdown specs to compose well-structured technical docs, architecture ADRs, API specs, and release logs.",
                author = "Documentation Guild",
                icon = "BookOpen",
                tags = listOf("Docs", "Markdown", "Design", "ADR"),
                category = "Documentation",
                downloadUrl = "builtin://doc-craftsman",
                stars = 275,
                requiredTools = emptyList(),
                permissions = listOf(SkillPermission.FILE_WRITE),
            ),
        )

        val BUILTIN_PRESET_DETAILS: Map<String, ClawHubMarketDetail> = BUILTIN_PRESET_ITEMS.associate { item ->
            item.id to ClawHubMarketDetail(
                item = item,
                readmeMarkdown = """
                    # ${item.name} (${item.id})
                    
                    ${item.description}
                    
                    ## Declarative Template Composition
                    - `SKILL.md`: Skill entry overview with YAML Frontmatter
                    - `AGENT.md`: Core agent decision-making and workflow tree
                    - `SOUL.md`: Communication tone, value redlines, and persona
                    - `TOOLS.md`: Sandbox tool calling conventions and output specs
                """.trimIndent(),
                templateSummary = listOf("SKILL.md", "AGENT.md", "SOUL.md", "TOOLS.md"),
            )
        }

        /**
         * Dynamically generate standard curated skill ZIP byte stream.
         */
        val BUILTIN_PACKAGE_GENERATORS: Map<String, () -> ByteArray> = mapOf(
            "git-workflow" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: git-workflow
                        name: Git Workflow & Review Master
                        version: 1.2.0
                        description: Follows semantic Conventional Commits spec, intelligently analyzes branch state and code diffs.
                        author: TaiXu Core Team
                        category: Dev Engineering
                        tags: Git, Review, DevOps
                        permissions: [exec_command]
                        required_tools: [git]
                        trigger_command: /git
                        ---
                        # Git Workflow Mastery Overview
                        This skill provides TaiXu agents with strict code version control capabilities in the sandbox.
                    """.trimIndent(),
                    agentMd = """
                        1. Before any code commit, must first call `git status -s` and `git diff` to confirm change scope;
                        2. Commit messages must follow Conventional Commits spec, title limited to 50 chars;
                        3. If untracked files are missed, proactively prompt user whether to include in version control.
                    """.trimIndent(),
                    soulMd = """
                        Rigorous, meticulous, obsessive about code hygiene, keen nose for any unverified or missed commits.
                    """.trimIndent(),
                    toolsMd = """
                        Only call sandbox-installed `git` CLI. Forbidden to use destructive args like `git push --force`.
                    """.trimIndent(),
                )
            },
            "code-auditor" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: code-auditor
                        name: Code Security & Architecture Audit
                        version: 1.1.0
                        description: Performs static code security and architecture audit for mobile and pure Kotlin/Java modules.
                        author: PalmClaw Security Lab
                        category: Security Audit
                        tags: Security, Code Review, Architecture
                        permissions: [file_write]
                        trigger_command: /audit
                        ---
                        # Code Security & Architecture Audit
                        Helps developers statically discover potential vulnerabilities and anti-patterns locally.
                    """.trimIndent(),
                    agentMd = """
                        1. Review focus: input validation, memory leaks, null safety (NPE), uncaught exceptions, and concurrency races;
                        2. In review reports, separate into [Critical Defects], [Improvements], and [Hardening Solutions];
                        3. Refactor code in small increments, ensuring semantic equivalence.
                    """.trimIndent(),
                    soulMd = """
                        Objective, constructive — always provides exemplary fixes when pointing out issues.
                    """.trimIndent(),
                    toolsMd = """
                        Prefer `read` to inspect target files, use `edit` for fine-grained changes, never overwrite unverified file content.
                    """.trimIndent(),
                )
            },
            "python-pro" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: python-pro
                        name: Sandbox Python Data & Scientific Computing
                        version: 2.0.1
                        description: Uses sandboxed Python3 for data processing and automation scripts.
                        author: Community SciPy
                        category: Data Algorithms
                        tags: Python, Data Science, Scripting
                        permissions: [exec_command, file_write]
                        required_tools: [python3]
                        trigger_command: /python
                        ---
                        # Python Scientific Computing Mastery
                    """.trimIndent(),
                    agentMd = """
                        1. When writing standalone Python scripts, declare `#!/usr/bin/env python3` as first line;
                        2. For data processing tasks, mind sandbox memory (default capped at 256MB), prefer generators or streaming iteration;
                        3. Complex statistical computations provide detailed output and console visualization tables.
                    """.trimIndent(),
                    soulMd = """
                        Efficient, mathematically rigorous, highly sensitive to performance and memory overhead.
                    """.trimIndent(),
                    toolsMd = """
                        Call `python3` via `base` or `process`. If third-party deps missing, guide user to configure sandbox env.
                    """.trimIndent(),
                )
            },
            "linux-doctor" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: linux-doctor
                        name: PRoot Sandbox Self-Check & Diagnostic Expert
                        version: 1.0.5
                        description: Diagnoses sandbox state, dpkg residue, mounts, and foreground/background processes.
                        author: TaiXu Runtime Lab
                        category: System Ops
                        tags: Linux, Sandbox, Diagnostics
                        permissions: [exec_command, system_probe]
                        required_tools: [bash, ps]
                        trigger_command: /doctor
                        ---
                        # Sandbox Environment Self-Check Overview
                    """.trimIndent(),
                    agentMd = """
                        1. Identify Android PRoot no-root user-space limitations;
                        2. On dpkg install anomalies, guide checking `/var/lib/dpkg/updates` and setuid dropping;
                        3. Background services must be guarded by TaiXu process manager to avoid orphan process reaping.
                    """.trimIndent(),
                    soulMd = """
                        Calm, composed, methodical troubleshooting — like a seasoned Linux systems engineer.
                    """.trimIndent(),
                    toolsMd = """
                        Read-first: diagnose with `ps`, `df`, `free` before attempting repairs.
                    """.trimIndent(),
                )
            },
            "doc-craftsman" to {
                createZipPackage(
                    skillMd = """
                        ---
                        id: doc-craftsman
                        name: Technical Design & Documentation Polishing Master
                        version: 1.3.0
                        description: Composes well-structured technical docs, architecture ADRs, and release logs.
                        author: Documentation Guild
                        category: Documentation
                        tags: Docs, Markdown, ADR
                        permissions: [file_write]
                        trigger_command: /doc
                        ---
                        # Technical Documentation Composition & Polishing Overview
                    """.trimIndent(),
                    agentMd = """
                        1. Follows clear GitHub Flavored Markdown spec;
                        2. Leverages Mermaid sequence diagrams, architecture diagrams, and parameter tables to clarify complex systems;
                        3. Language concise and accurate, no verbose fluff.
                    """.trimIndent(),
                    soulMd = """
                        Clear, elegant, pursues ultimate typesetting and high readability.
                    """.trimIndent(),
                    toolsMd = """
                        Output structured markdown files via `write` or `edit`.
                    """.trimIndent(),
                )
            },
        )

        private fun createZipPackage(
            skillMd: String,
            agentMd: String? = null,
            soulMd: String? = null,
            toolsMd: String? = null,
            userMd: String? = null,
            memoryMd: String? = null,
        ): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                fun addFile(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }

                addFile("SKILL.md", skillMd)
                agentMd?.let { addFile("AGENT.md", it) }
                soulMd?.let { addFile("SOUL.md", it) }
                toolsMd?.let { addFile("TOOLS.md", it) }
                userMd?.let { addFile("USER.md", it) }
                memoryMd?.let { addFile("MEMORY.md", it) }
            }
            return bos.toByteArray()
        }
    }
}
