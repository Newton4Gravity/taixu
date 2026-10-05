package top.wkbin.taixu.runtime

import java.io.File
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.runtime.bridge.HostBridge
import top.wkbin.taixu.runtime.scripts.RuntimeAssetSynchronizer

/**
 * Distro configurator: writes sandbox configuration after RootFS install / import / reset / update.
 *
 * Covers TUNA apt/pip mirrors, dpkg no-doc and statoverride, setuid stripping
 * (setuid ineffective under PRoot, retaining it causes dpkg upgrade hangs), perl hardlink fix script,
 * DNS, environment variables, HostBridge sandbox scripts and keys.
 *
 * Extracted from LinuxRuntimeImpl (original configureRootfs series), logic preserved verbatim;
 * only behavioral change: [configureRootfs] changed from synchronous runBlocking to suspend,
 * no longer blocks calling thread and can be cooperatively cancelled.
 */
class DistroConfigurator(
    private val pathManager: RuntimePathManager,
    private val hostBridge: HostBridge,
    private val assetSynchronizer: RuntimeAssetSynchronizer,
    private val logger: AppLogger,
) {

    /** Complete configuration pipeline after RootFS is in place (shared by install / import / reset / update). */
    suspend fun configureRootfs(distroId: String = "ubuntu") {
        val rootfs = pathManager.rootfsDir(distroId)
        val etcDir = File(rootfs, "etc")
        etcDir.mkdirs()
        File(etcDir, "taixu-runtime").writeText("taixu-runtime=0.3.0\n")
        File(rootfs, "opt/taixu").mkdirs()
        pathManager.ensureDistroDirectories(distroId)
        stripSetuidBits(distroId)
        configureDpkgStatoverride(distroId)
        configureDpkgNoDoc(distroId)
        configureAptSettings(distroId)
        configureChinaMirrors(distroId)
        configurePipMirror(distroId)
        installAptStripSetuidHook(distroId)
        installPerlFixScript(distroId)
        runCatching {
            assetSynchronizer.syncAssetsToDistro(distroId)
        }
    }

    /**
     * Idempotent asset sync when restoring installed env: APK upgrades may add or replace built-in MCP/build scripts,
     * existing rootfs won't re-run [configureRootfs], must sync once on restore to avoid stale sandbox files.
     */
    suspend fun syncAssets(distroId: String) {
        assetSynchronizer.syncAssetsToDistro(distroId)
    }

    /**
     * Exclude docs/man pages/locale packages from unpacking in PRoot sandbox:
     * 1. Reduces disk I/O and unpack time by 50-60%, greatly avoids 300s install timeout;
     * 2. Avoids man page hardlink (e.g. perlthanks.1.gz) chown errors under PRoot.
     */
    private fun configureDpkgNoDoc(distroId: String = "ubuntu") {
        val dpkgCfgDir = File(pathManager.rootfsDir(distroId), "etc/dpkg/dpkg.cfg.d")
        dpkgCfgDir.mkdirs()
        File(dpkgCfgDir, "01_taixu_nodoc").writeText(
            """
            # PRoot performance & stability: skip docs/man pages to reduce I/O and avoid hardlink unpack errors
            path-exclude /usr/share/doc/*
            path-exclude /usr/share/man/*
            path-exclude /usr/share/groff/*
            path-exclude /usr/share/info/*
            path-exclude /usr/share/locale/*
            path-exclude /usr/share/lintian/*
            path-exclude /usr/share/linda/*
            """.trimIndent() + "\n",
        )
    }

    /**
     * Configure apt timeout retries and non-interactive defaults to prevent background install tasks from hanging.
     */
    private fun configureAptSettings(distroId: String = "ubuntu") {
        val hookDir = File(pathManager.rootfsDir(distroId), "etc/apt/apt.conf.d")
        hookDir.mkdirs()
        File(hookDir, "99taixu-apt-config").writeText(
            """
            // Improve install robustness in flaky networks and default to non-interactive config
            Acquire::Retries "3";
            Acquire::http::Timeout "60";
            Acquire::https::Timeout "60";
            DPkg::Options {
               "--force-confdef";
               "--force-confold";
            };
            """.trimIndent() + "\n",
        )
    }

    /**
     * Switch sandbox software sources to TUNA mirror to accelerate apt/pip installs in China.
     * Only applies to apt-based distros (debian/ubuntu/kali); others keep official sources.
     */
    fun configureChinaMirrors(distroId: String = "ubuntu") {
        val osRelease = readOsRelease(distroId)
        if (osRelease.isEmpty()) {
            logger.i("China mirrors skipped: os-release not found for $distroId")
            return
        }
        val id = osRelease["ID"] ?: osRelease["ID_LIKE"]
        val codename = osRelease["VERSION_CODENAME"]
        val sources = when (id) {
            "debian" -> codename?.let(::tunaDebianSources)
            "ubuntu" -> codename?.let(::tunaUbuntuSources)
            "kali" -> tunaKaliSources()
            else -> null
        }
        if (sources == null) {
            logger.i("China mirrors skipped for distro id=$id codename=$codename")
            return
        }
        val sourcesListDir = File(pathManager.rootfsDir(distroId), "etc/apt/sources.list.d")
        sourcesListDir.mkdirs()
        disableStockAptSources(sourcesListDir, distroId)
        File(sourcesListDir, "taixu-mirrors.list").writeText(sources + "\n")
        logger.i("China mirrors applied: TUNA apt sources for $id ($codename) in $distroId")
    }

    private fun disableStockAptSources(sourcesListDir: File, distroId: String = "ubuntu") {
        File(pathManager.rootfsDir(distroId), "etc/apt/sources.list").takeIf { it.isFile }
            ?.let { stock -> renameToDisabled(stock) }
        sourcesListDir.listFiles()?.forEach { entry ->
            val name = entry.name
            if (entry.isFile && (name.endsWith(".list") || name.endsWith(".sources")) &&
                name != "taixu-mirrors.list"
            ) {
                renameToDisabled(entry)
            }
        }
    }

    private fun renameToDisabled(file: File) {
        val disabled = File(file.parentFile, "${file.name}.taixu-disabled")
        if (!file.renameTo(disabled)) {
            logger.w("Failed to disable stock apt source: ${file.path}")
        }
    }

    private fun tunaDebianSources(codename: String): String = """
        # TaiXu: TUNA Mirror (auto-switched from official source)
        deb https://mirrors.tuna.tsinghua.edu.cn/debian $codename main contrib non-free non-free-firmware
        deb https://mirrors.tuna.tsinghua.edu.cn/debian $codename-updates main contrib non-free non-free-firmware
        deb https://mirrors.tuna.tsinghua.edu.cn/debian-security $codename-security main contrib non-free non-free-firmware
    """.trimIndent()

    private fun tunaUbuntuSources(codename: String): String = """
        # TaiXu: TUNA Mirror (auto-switched from official source)
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename-updates main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename-security main restricted universe multiverse
        deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports $codename-backports main restricted universe multiverse
    """.trimIndent()

    private fun tunaKaliSources(): String = """
        # TaiXu: TUNA Mirror (auto-switched from official source)
        deb https://mirrors.tuna.tsinghua.edu.cn/kali kali-rolling main contrib non-free
    """.trimIndent()

    private fun configurePipMirror(distroId: String = "ubuntu") {
        val config = File(pathManager.rootfsDir(distroId), "etc/pip.conf")
        config.parentFile?.mkdirs()
        config.writeText(
            """
            # TaiXu: TUNA PyPI Mirror
            [global]
            index-url = https://pypi.tuna.tsinghua.edu.cn/simple
            """.trimIndent() + "\n",
        )
    }

    private fun readOsRelease(distroId: String = "ubuntu"): Map<String, String> {
        val rootfs = pathManager.rootfsDir(distroId)
        val file = File(rootfs, "etc/os-release")
            .takeIf { it.isFile }
            ?: File(rootfs, "usr/lib/os-release").takeIf { it.isFile }
            ?: return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim().trim('"', '\'')
                key to value
            }.toMap()
        }.onFailure { logger.w("Failed to parse os-release for $distroId", it) }
            .getOrDefault(emptyMap())
    }

    private fun installPerlFixScript(distroId: String = "ubuntu") {
        val binDir = File(pathManager.rootfsDir(distroId), "usr/local/sbin")
        binDir.mkdirs()
        val script = File(binDir, "taixu-fix-perl")
        script.writeText(
            """
            #!/bin/sh
            set -e
            echo "[TaiXu] Scanning for perl deb packages to patch hardlinks..."
            TMPDIR="${'$'}(mktemp -d /tmp/taixu-perl-fix.XXXXXX)"
            trap 'rm -rf "${'$'}TMPDIR"' EXIT INT TERM

            # Prefer finding perl deb from apt cache, otherwise attempt download
            PERL_DEB="${'$'}(ls -1 /var/cache/apt/archives/perl_*.deb 2>/dev/null | head -n 1 || true)"
            if [ -z "${'$'}PERL_DEB" ]; then
                echo "[TaiXu] Downloading perl package..."
                cd "${'$'}TMPDIR" && apt-get download perl || true
                PERL_DEB="${'$'}(ls -1 "${'$'}TMPDIR"/perl_*.deb 2>/dev/null | head -n 1 || true)"
            fi

            if [ -z "${'$'}PERL_DEB" ] || [ ! -f "${'$'}PERL_DEB" ]; then
                echo "[TaiXu] Perl deb package not found, attempting apt --fix-broken install..."
                apt-get --fix-broken install -y || true
                exit 0
            fi

            echo "[TaiXu] Patching ${'$'}PERL_DEB..."
            WORKDIR="${'$'}TMPDIR/repack"
            mkdir -p "${'$'}WORKDIR/DEBIAN"
            dpkg-deb -e "${'$'}PERL_DEB" "${'$'}WORKDIR/DEBIAN"
            dpkg-deb --fsys-tarfile "${'$'}PERL_DEB" | tar -C "${'$'}WORKDIR" -xf -

            # Convert usr/bin/perlthanks and other hardlinks to symlinks
            if [ -f "${'$'}WORKDIR/usr/bin/perlthanks" ]; then
                rm -f "${'$'}WORKDIR/usr/bin/perlthanks"
                ln -s perlbug "${'$'}WORKDIR/usr/bin/perlthanks"
                echo "[TaiXu] Converted /usr/bin/perlthanks to symlink"
            fi

            dpkg-deb -b "${'$'}WORKDIR" "${'$'}TMPDIR/perl-patched.deb"
            dpkg -i --force-overwrite "${'$'}TMPDIR/perl-patched.deb"
            echo "[TaiXu] Perl patch installed successfully."
            """.trimIndent() + "\n",
        )
        runCatching {
            android.system.Os.chmod(script.absolutePath, 0x1ED) // 0755
        }
    }

    private fun stripSetuidBits(distroId: String = "ubuntu") {
        var stripped = 0
        var failed = 0
        pathManager.rootfsDir(distroId).walkTopDown()
            .forEach { file ->
                if (file.name.endsWith(".dpkg-tmp")) {
                    file.delete()
                } else if (file.isFile) {
                    runCatching {
                        val mode = android.system.Os.lstat(file.absolutePath).st_mode
                        if (mode and 0xC00 != 0) {
                            android.system.Os.chmod(file.absolutePath, mode and 0x3FF)
                            stripped++
                        }
                    }.onFailure { failed++ }
                }
            }
        logger.i("stripSetuidBits ($distroId): cleared $stripped setuid/setgid bits, $failed failures")
    }

    private fun configureDpkgStatoverride(distroId: String = "ubuntu") {
        val dpkgDir = File(pathManager.rootfsDir(distroId), "var/lib/dpkg")
        dpkgDir.mkdirs()
        val statoverrideFile = File(dpkgDir, "statoverride")
        val overrides = listOf(
            "/usr/bin/su",
            "/usr/bin/mount",
            "/usr/bin/umount",
            "/usr/bin/newgrp",
            "/usr/bin/gpasswd",
            "/usr/bin/passwd",
            "/usr/bin/chfn",
            "/usr/bin/chsh",
            "/usr/bin/expiry",
        )
        val existingLines = if (statoverrideFile.exists()) {
            statoverrideFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else {
            mutableSetOf()
        }
        overrides.forEach { path ->
            val entry = "root root 0755 $path"
            val pathSuffix = " $path"
            if (existingLines.none { it.endsWith(pathSuffix) }) {
                existingLines.add(entry)
            }
        }
        statoverrideFile.writeText(existingLines.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun installAptStripSetuidHook(distroId: String = "ubuntu") {
        val hookDir = File(pathManager.rootfsDir(distroId), "etc/apt/apt.conf.d")
        hookDir.mkdirs()
        val findCommand = "find /usr /bin /sbin -xdev -type f -perm /6000 -exec chmod ug-s {} +"
        val cleanupTmp = "rm -f /bin/*.dpkg-tmp /usr/bin/*.dpkg-tmp /usr/sbin/*.dpkg-tmp /sbin/*.dpkg-tmp 2>/dev/null || true"
        File(hookDir, "99taixu-strip-setuid").writeText(
            "// PRoot sandbox: setuid ineffective, retaining causes dpkg upgrade hangs (see taixu-runtime docs).\n" +
                "DPkg::Pre-Invoke { \"$cleanupTmp\"; };\n" +
                "DPkg::Post-Invoke { \"$findCommand\"; };\n",
        )
    }

    fun configureDns(distroId: String = "ubuntu") {
        val etcDir = File(pathManager.rootfsDir(distroId), "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        // Ubuntu/Debian OCI images have /etc/resolv.conf as symlink to /run/systemd/resolve/...,
        // PRoot sandbox lacks systemd causing dangling link; direct write throws FileNotFoundException: ENOENT,
        // must remove link first (handles both regular file and dangling link) then write.
        try {
            val resolvPath = resolvConf.toPath()
            if (java.nio.file.Files.isSymbolicLink(resolvPath) || resolvConf.exists()) {
                resolvConf.delete()
            }
        } catch (_: Exception) {
            // Delete failure doesn't block subsequent write attempt (writeText will give final error)
        }
        resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    fun configureEnvironment(distroId: String = "ubuntu") {
        val profileDir = File(pathManager.rootfsDir(distroId), "etc/profile.d")
        profileDir.mkdirs()
        File(profileDir, "taixu-env.sh").writeText(
            """
            export LANG=C.UTF-8
            export PATH=/root/.local/bin:/opt/taixu/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export HOME=/root
            # HostBridge — sandbox triggers host operations via localhost HTTP bridge
            export TAIXU_BRIDGE_URL="http://127.0.0.1:7980"
            export TAIXU_BRIDGE_PORT=7980
            # Android binary reference paths (executed via taixu-android-exec wrapper)
            export ANDROID_BIN_PATH="/system/bin:/system/xbin"
            export ANDROID_LIB_PATH="/system/lib64:/system/lib:/vendor/lib64:/vendor/lib"
            """.trimIndent() + "\n",
        )
        val home = pathManager.homeDir(distroId)
        home.mkdirs()
        File(home, ".gitconfig").writeText(
            """
            [safe]
            	directory = *
            [http]
            	version = HTTP/1.1
            """.trimIndent() + "\n",
        )
        // Install HostBridge sandbox scripts and keys
        installHostBridgeScripts(distroId)
    }

    /**
     * Install HostBridge sandbox scripts and API key.
     *
     * Writes three files to /opt/taixu/ (bind-mounted to sandbox):
     * - bin/taixu-host        — bridge CLI (install-apk / shell / health)
     * - bin/taixu-android-exec — Android binary execution wrapper (sets correct linker env)
     * - .bridge-key           — API auth key
     */
    private fun installHostBridgeScripts(distroId: String) {
        val binDir = pathManager.taixuBinDir(distroId)
        binDir.mkdirs()
        val rootDir = pathManager.taixuRootDir(distroId)

        // taixu-host — host bridge CLI
        val hostScript = File(binDir, "taixu-host")
        hostScript.writeText(TAIXU_HOST_SCRIPT)
        runCatching { android.system.Os.chmod(hostScript.absolutePath, 0x1ED) } // 0755

        // taixu-android-exec — Android binary execution wrapper
        val androidExecScript = File(binDir, "taixu-android-exec")
        androidExecScript.writeText(TAIXU_ANDROID_EXEC_SCRIPT)
        runCatching { android.system.Os.chmod(androidExecScript.absolutePath, 0x1ED) }

        // API key — refreshed on each config (ensures key sync after app restart)
        File(rootDir, ".bridge-key").writeText(hostBridge.bridgeKey)

        logger.i("HostBridge scripts installed for distro: $distroId")
    }

    companion object {
        val TAIXU_HOST_SCRIPT = listOf(
            "#!/bin/sh",
            "# TaiXu Host Bridge CLI",
            "# Usage: taixu-host install-apk <path> | taixu-host shell <cmd> | taixu-host health",
            "",
            "BRIDGE_URL=\"http://127.0.0.1:7980\"",
            "KEY_FILE=\"/opt/taixu/.bridge-key\"",
            "",
            "if [ -f \"\${KEY_FILE}\" ]; then",
            "  BRIDGE_KEY=\$(cat \"\${KEY_FILE}\" 2>/dev/null | tr -d '[:space:]')",
            "else",
            "  BRIDGE_KEY=\"\"",
            "fi",
            "",
            "if ! command -v curl >/dev/null 2>&1; then",
            "  echo '{\"success\":false,\"error\":\"curl not installed\"}' >&2; exit 1",
            "fi",
            "",
            "AUTH=\"\"",
            "if [ -n \"\${BRIDGE_KEY}\" ]; then AUTH=\"Authorization: Bearer \${BRIDGE_KEY}\"; fi",
            "",
            "case \"\$1\" in",
            "  install-apk)",
            "    [ -z \"\$2\" ] && { echo 'Usage: taixu-host install-apk <path>' >&2; exit 1; }",
            "    TARGET=\"\$2\"",
            "    if [ ! -f \"\${TARGET}\" ]; then",
            "      STRIPPED=\"\${TARGET#/}\"",
            "      if [ -f \"\${STRIPPED}\" ]; then",
            "        TARGET=\"\${STRIPPED}\"",
            "      elif [ -f \"./\$STRIPPED\" ]; then",
            "        TARGET=\"./\$STRIPPED\"",
            "      elif [ -f \"build/\$STRIPPED\" ]; then",
            "        TARGET=\"build/\$STRIPPED\"",
            "      else",
            "        BN=\"\$(basename \"\$TARGET\")\"",
            "        FOUND=\"\$(find . -maxdepth 6 -name \"\$BN\" -type f 2>/dev/null | head -n 1)\"",
            "        if [ -z \"\$FOUND\" ] && [ -d /workspace ]; then",
            "          FOUND=\"\$(find /workspace -maxdepth 7 -name \"\$BN\" -type f 2>/dev/null | head -n 1)\"",
            "        fi",
            "        if [ -z \"\$FOUND\" ]; then",
            "          FOUND=\"\$(find . -maxdepth 6 -name \"*.apk\" -type f 2>/dev/null | head -n 1)\"",
            "        fi",
            "        if [ -n \"\$FOUND\" ] && [ -f \"\$FOUND\" ]; then",
            "          TARGET=\"\$FOUND\"",
            "        fi",
            "      fi",
            "    fi",
            "    [ ! -f \"\${TARGET}\" ] && { echo \"{\\\"success\\\":false,\\\"error\\\":\\\"Not found: \$2\\\"}\" >&2; exit 1; }",
            "    if command -v realpath >/dev/null 2>&1; then",
            "      ABS_TARGET=\"\$(realpath \"\$TARGET\")\"",
            "    elif command -v readlink >/dev/null 2>&1; then",
            "      ABS_TARGET=\"\$(readlink -f \"\$TARGET\" 2>/dev/null || echo \"\$TARGET\")\"",
            "    else",
            "      ABS_TARGET=\"\$TARGET\"",
            "    fi",
            "    if [ -n \"\${AUTH}\" ]; then",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/install-apk\" -H 'Content-Type: application/json' -H \"\${AUTH}\" -d \"{\\\"path\\\":\\\"\${ABS_TARGET}\\\"}\"",
            "    else",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/install-apk\" -H 'Content-Type: application/json' -d \"{\\\"path\\\":\\\"\${ABS_TARGET}\\\"}\"",
            "    fi",
            "    echo \"\"",
            "    ;;",
            "  shell)",
            "    [ -z \"\$2\" ] && { echo 'Usage: taixu-host shell <command>' >&2; exit 1; }",
            "    shift; CMD=\"\$*\"",
            "    if command -v jq >/dev/null 2>&1; then",
            "      BODY=\$(jq -nc --arg c \"\${CMD}\" '{command:\$c}')",
            "    else",
            "      ESC=\$(printf '%s' \"\${CMD}\" | sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g' | tr '\\n' ' ')",
            "      BODY=\"{\\\"command\\\":\\\"\${ESC}\\\"}\"",
            "    fi",
            "    if [ -n \"\${AUTH}\" ]; then",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/shell\" -H 'Content-Type: application/json' -H \"\${AUTH}\" -d \"\${BODY}\"",
            "    else",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/shell\" -H 'Content-Type: application/json' -d \"\${BODY}\"",
            "    fi",
            "    echo \"\"",
            "    ;;",
            "  logcat)",
            "    shift",
            "    PKG=\"\${1:-}\"",
            "    TAG=\"\${2:-}\"",
            "    PRIO=\"\${3:-V}\"",
            "    KW=\"\${4:-}\"",
            "    LINES=\"\${5:-200}\"",
            "    if command -v jq >/dev/null 2>&1; then",
            "      BODY=\$(jq -nc --arg p \"\$PKG\" --arg t \"\$TAG\" --arg pr \"\$PRIO\" --arg k \"\$KW\" --arg l \"\$LINES\" '{package:\$p, tag:\$t, priority:\$pr, keyword:\$k, lines:(\$l|tonumber)}')",
            "    else",
            "      BODY=\"{\\\"package\\\":\\\"\$PKG\\\",\\\"tag\\\":\\\"\$TAG\\\",\\\"priority\\\":\\\"\$PRIO\\\",\\\"keyword\\\":\\\"\$KW\\\",\\\"lines\\\":\$LINES}\"",
            "    fi",
            "    if [ -n \"\${AUTH}\" ]; then",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/logcat\" -H 'Content-Type: application/json' -H \"\${AUTH}\" -d \"\${BODY}\"",
            "    else",
            "      curl -s -X POST \"\${BRIDGE_URL}/api/logcat\" -H 'Content-Type: application/json' -d \"\${BODY}\"",
            "    fi",
            "    echo \"\"",
            "    ;;",
            "  health|status)",
            "    if [ -n \"\${AUTH}\" ]; then curl -s \"\${BRIDGE_URL}/api/health\" -H \"\${AUTH}\"; else curl -s \"\${BRIDGE_URL}/api/health\"; fi",
            "    echo \"\"",
            "    ;;",
            "  *)",
            "    echo \"TaiXu Host Bridge CLI\"",
            "    echo \"  taixu-host install-apk <path>   Install APK on host (wireless ADB or system dialog)\"",
            "    echo \"  taixu-host shell <command>      Run host shell (wireless ADB or Shizuku/root)\"",
            "    echo \"  taixu-host logcat [pkg] [tag] [prio] [kw] [lines] Capture logcat via wireless ADB\"",
            "    echo \"  taixu-host health               Bridge health and ADB status\"",
            "    exit 1;;",
            "esac",
        ).joinToString("\n") + "\n"

        val TAIXU_ANDROID_EXEC_SCRIPT = listOf(
            "#!/bin/sh",
            "# TaiXu Android Binary Executor",
            "# Sets up correct linker env for Android system binaries in PRoot sandbox.",
            "# Usage: taixu-android-exec /system/bin/settings put global captive_portal_http_url ''",
            "",
            "[ -z \"\$1\" ] && { echo 'Usage: taixu-android-exec <binary> [args...]' >&2; exit 1; }",
            "export LD_LIBRARY_PATH=/system/lib64:/system/lib:/vendor/lib64:/vendor/lib",
            "export ANDROID_DATA=/data",
            "export ANDROID_ROOT=/system",
            "unset LD_PRELOAD",
            "exec \"\$@\"",
        ).joinToString("\n") + "\n"
    }
}
