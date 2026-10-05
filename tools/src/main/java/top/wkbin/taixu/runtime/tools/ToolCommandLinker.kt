package top.wkbin.taixu.runtime.tools

import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand

/** Creates stable `/opt/taixu/bin` shims without exposing arbitrary commands. */
class ToolCommandLinker(
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun link(
        command: String,
        target: String,
        environment: Map<String, String> = emptyMap(),
    ): CommandResult {
        require(SAFE_NAME.matches(command)) { "Invalid tool command name" }
        require(SAFE_TARGET.matches(target)) { "Invalid tool command target path" }
        val link = ToolLayout.commandPath(command)
        // Self-ref guard: shim exec target must never be shim itself (direct equality or symlink resolving back to self). Script exec-ing itself in PRoot creates infinite zero-output ptrace loop.
        require(target != link) { "Tool command target cannot point to self: $link" }
        val scriptLine = shellQuote("exec $target \"\$@\"")
        val quotedLink = shellQuote(link)
        val quotedTarget = shellQuote(target)
        val quotedBin = shellQuote(ToolLayout.BIN)
        return linuxRuntime.execute(
            ShellCommand(
                // ⚠️ Must rm -f before redirect: shell `>` follows symlinks.
                // Offline suite install scripts create /opt/taixu/bin/java as symlink
                // (→ TOOL_DIR/bin/java → real JDK ELF). If `> $link` directly,
                // redirect follows entire symlink chain, writing exec wrapper into
                // real JDK java ELF — wrapper and symlink pointing back exec each
                // other, forming infinite zero-output, CPU-saturating loop in PRoot.
                // rm -f unlinks first, so printf writes a fresh regular file.
                commandLine = "mkdir -p $quotedBin && " +
                    "if [ \"\$(readlink -f $quotedTarget 2>/dev/null || echo $quotedTarget)\" = $quotedLink ]; then " +
                    "echo 'refusing self-referential tool command link: $link -> $target' >&2; exit 1; fi && " +
                    "rm -f $quotedLink && " +
                    "printf '%s\\n' '#!/bin/sh' $scriptLine > $quotedLink && " +
                    "chmod 700 $quotedLink",
                environment = environment,
            ),
        )
    }

    suspend fun remove(command: String, environment: Map<String, String> = emptyMap()): CommandResult {
        require(SAFE_NAME.matches(command)) { "Invalid tool command name" }
        return linuxRuntime.execute(
            ShellCommand(
                commandLine = "rm -f ${shellQuote(ToolLayout.commandPath(command))}",
                environment = environment,
            ),
        )
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

    private companion object {
        val SAFE_NAME = Regex("[a-z0-9][a-z0-9._+-]{0,63}")
        val SAFE_TARGET = Regex("/[A-Za-z0-9._/@+:-]+")
    }
}
