package top.wkbin.taixu.core.tools

data class ToolVerification(
    val toolId: String,
    val healthy: Boolean,
    val version: String? = null,
    val detail: String,
)
