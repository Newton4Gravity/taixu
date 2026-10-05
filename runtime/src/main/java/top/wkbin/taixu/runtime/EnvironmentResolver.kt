package top.wkbin.taixu.runtime


/** Single source of truth for the environment visible inside Debian. */
class EnvironmentResolver() {
    fun runtimePath(): String = listOf(
        "/root/.local/bin",
        "/opt/taixu/bin",
        "/usr/local/sbin",
        "/usr/local/bin",
        "/usr/sbin",
        "/usr/bin",
        "/sbin",
        "/bin",
    ).joinToString(":")

    fun baseEnvironment(interactive: Boolean): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        put("PATH", runtimePath())
        // HostBridge — sandbox triggers host operations via localhost HTTP bridge (APK install, shell exec)
        put("TAIXU_BRIDGE_URL", "http://127.0.0.1:7980")
        put("TAIXU_BRIDGE_PORT", "7980")
        // Android binary reference paths (not in main PATH to avoid conflicts with Debian tools)
        // Use taixu-android-exec wrapper or taixu-host shell to execute Android commands
        put("ANDROID_BIN_PATH", "/system/bin:/system/xbin")
        put("ANDROID_LIB_PATH", "/system/lib64:/system/lib:/vendor/lib64:/vendor/lib")
        if (interactive) {
            put("TERM", "xterm-256color")
        } else {
            put("TERM", "dumb")
            put("DEBIAN_FRONTEND", "noninteractive")
            put("CI", "true")
            put("NONINTERACTIVE", "1")
        }
    }

    fun merge(
        manifest: Map<String, String> = emptyMap(),
        provider: Map<String, String> = emptyMap(),
        interactive: Boolean = false,
    ): Map<String, String> = buildMap {
        putAll(baseEnvironment(interactive))
        putAll(manifest)
        putAll(provider)
    }
}
