package top.wkbin.taixu.runtime.terminal

/**
 * Terminal login banner. Written by [top.wkbin.taixu.runtime.LinuxRuntimeImpl] to
 * distro `/opt/taixu/motd` before terminal session starts, printed by login shell via `cat`
 * to bypass command-string escaping issues.
 */
internal fun terminalBanner(): String =
    "TaiXu · TaiXu Linux AI Runtime\n"
