package top.wkbin.taixu.runtime.doctor

import top.wkbin.taixu.core.model.RepairProgress
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class EnvironmentRepairer(
    private val linuxRuntime: LinuxRuntime,
    private val environmentDoctor: EnvironmentDoctor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<RepairProgress?>(null)
    val progress: StateFlow<RepairProgress?> = _progress.asStateFlow()
    private val _isRepairing = MutableStateFlow(false)
    val isRepairing: StateFlow<Boolean> = _isRepairing.asStateFlow()
    private var currentRepairJob: Job? = null

    fun startRepair(): Job {
        val existing = currentRepairJob
        if (existing?.isActive == true) return existing
        val job = scope.launch {
            _isRepairing.value = true
            try {
                repair().collect { p ->
                    _progress.value = p
                }
            } finally {
                _isRepairing.value = false
            }
        }
        currentRepairJob = job
        return job
    }

    fun cancelRepair() {
        currentRepairJob?.cancel()
        _isRepairing.value = false
        _progress.value = null
    }

    fun repair(): Flow<RepairProgress> = flow {
        val totalSteps = 5
        val logs = mutableListOf<String>()

        fun addLog(message: String) {
            logs.add(message)
            if (logs.size > 200) {
                logs.removeAt(0)
            }
        }

        if (linuxRuntime.state.value !is RuntimeState.Ready) {
            emit(
                RepairProgress(
                    stepTitle = "Linux sandbox not ready",
                    stepIndex = 0,
                    totalSteps = totalSteps,
                    progress = 0.0f,
                    logs = listOf("Error: Linux sandbox not initialized or in error state, cannot run self-heal repair"),
                    isCompleted = false,
                    isFailed = true,
                    errorMessage = "Linux sandbox not ready, please initialize sandbox first",
                ),
            )
            return@flow
        }

        try {
            // ==========================================
            // Step 1: Fix DNS & certificates
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "Configuring highly available DNS & SSL root certificates...",
                    stepIndex = 1,
                    totalSteps = totalSteps,
                    progress = 0.15f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 1/5] Writing public DNS (114.114.114.114, 223.5.5.5, 8.8.8.8)")
            val dnsCmd = "mkdir -p /etc && printf 'nameserver 114.114.114.114\\nnameserver 223.5.5.5\\nnameserver 8.8.8.8\\n' > /etc/resolv.conf"
            val dnsRes = executeCommand(dnsCmd, logs)
            if (!dnsRes.isSuccess) {
                addLog("Warning: Failed to write /etc/resolv.conf: ${dnsRes.stderr}")
            }

            // ==========================================
            // Step 2: Clean leftover locks & switch to domestic mirror sources
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "Configuring Tsinghua domestic mirror acceleration source...",
                    stepIndex = 2,
                    totalSteps = totalSteps,
                    progress = 0.35f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 2/5] Cleaning dpkg/apt transaction locks and replacing with domestic sources")
            val mirrorScript = """
                rm -rf /var/lib/dpkg/updates/* /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock* 2>/dev/null || true
                DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true
                mkdir -p /etc/apt/sources.list.d
                # Disable all built-in official sources and old sources to avoid conflicts or 404s with new sources
                for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
                    if [ -f "${'$'}f" ] && [ "${'$'}(basename "${'$'}f")" != "taixu-mirrors.list" ]; then
                        mv "${'$'}f" "${'$'}f.taixu-disabled" 2>/dev/null || true
                    fi
                done
                if [ -f /etc/os-release ]; then
                    . /etc/os-release
                    if [ "${'$'}ID" = "ubuntu" ]; then
                        CN="${'$'}{VERSION_CODENAME:-noble}"
                        printf "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports %s main restricted universe multiverse\ndeb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports %s-updates main restricted universe multiverse\ndeb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports %s-security main restricted universe multiverse\ndeb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports %s-backports main restricted universe multiverse\n" "${'$'}CN" "${'$'}CN" "${'$'}CN" "${'$'}CN" > /etc/apt/sources.list.d/taixu-mirrors.list
                    elif [ "${'$'}ID" = "debian" ] || [ "${'$'}ID_LIKE" = "debian" ]; then
                        CN="${'$'}{VERSION_CODENAME:-bookworm}"
                        printf "deb https://mirrors.tuna.tsinghua.edu.cn/debian %s main contrib non-free non-free-firmware\ndeb https://mirrors.tuna.tsinghua.edu.cn/debian %s-updates main contrib non-free non-free-firmware\ndeb https://mirrors.tuna.tsinghua.edu.cn/debian-security %s-security main contrib non-free non-free-firmware\n" "${'$'}CN" "${'$'}CN" "${'$'}CN" > /etc/apt/sources.list.d/taixu-mirrors.list
                    elif [ "${'$'}ID" = "kali" ]; then
                        printf "deb https://mirrors.tuna.tsinghua.edu.cn/kali kali-rolling main contrib non-free\n" > /etc/apt/sources.list.d/taixu-mirrors.list
                    fi
                fi
                # Clean old apt lists cache to ensure fresh index pulled from mirror
                rm -rf /var/lib/apt/lists/* 2>/dev/null || true
            """.trimIndent()
            executeCommand(mirrorScript, logs)

            // ==========================================
            // Step 3: Update APT package index
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "Updating APT package index...",
                    stepIndex = 3,
                    totalSteps = totalSteps,
                    progress = 0.55f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 3/5] Running apt-get update to refresh index")
            val updateRes = executeCommand("rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive apt-get update -y", logs, timeoutMs = 120_000L)
            if (!updateRes.isSuccess) {
                addLog("Note: apt-get update produced some non-fatal warnings")
            }

            // ==========================================
            // Step 4: Install core toolchain (curl, git, tar, xz-utils)
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "Installing core toolchain (Git / Curl / Tar / XZ)...",
                    stepIndex = 4,
                    totalSteps = totalSteps,
                    progress = 0.75f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 4/5] Installing ca-certificates, curl, git, tar, xz-utils, procps")
            val installToolsCmd = "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl git tar xz-utils procps"
            val installToolsRes = executeCommand(installToolsCmd, logs, timeoutMs = 180_000L)
            if (!installToolsRes.isSuccess) {
                addLog("Warning: Core toolchain install anomaly: ${installToolsRes.stderr.ifBlank { installToolsRes.stdout }}")
            }

            // ==========================================
            // Step 5: Configure & prepare/upgrade Node.js & domestic package mirrors
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "Preparing/Upgrading Node.js runtime & NPM/PIP domestic mirrors...",
                    stepIndex = 5,
                    totalSteps = totalSteps,
                    progress = 0.90f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 5/5] Checking and auto-upgrading Node.js (>= v20 LTS) & configuring mirror acceleration")
            val setupNodeAndMirrors = """
                NODE_VER=${'$'}(node -v 2>/dev/null | tr -d 'v' | cut -d. -f1)
                if [ -z "${'$'}NODE_VER" ] || [ "${'$'}NODE_VER" -lt 20 ]; then
                    echo "Detected missing or low Node.js version (current: ${'$'}{NODE_VER:-not installed}), downloading and installing Node.js v22 (LTS ARM64)..."
                    mkdir -p /tmp/node_setup
                    curl -fsSL --connect-timeout 10 --max-time 180 https://npmmirror.com/mirrors/node/v22.14.0/node-v22.14.0-linux-arm64.tar.xz -o /tmp/node_setup/node.tar.xz || \
                    curl -fsSL --connect-timeout 10 --max-time 180 https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-arm64.tar.xz -o /tmp/node_setup/node.tar.xz || true
                    if [ -f /tmp/node_setup/node.tar.xz ]; then
                        tar -xJf /tmp/node_setup/node.tar.xz -C /usr/local --strip-components=1
                        rm -rf /tmp/node_setup
                        echo "Node.js v22 upgrade complete: ${'$'}(node -v 2>/dev/null)"
                    else
                        echo "Prebuilt package fetch restricted, attempting base Node via system package manager..."
                        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nodejs npm || true
                    fi
                fi
                which npm >/dev/null 2>&1 && npm config set registry https://registry.npmmirror.com || true
                mkdir -p ${'$'}HOME/.pip
                printf '[global]\nindex-url = https://pypi.tuna.tsinghua.edu.cn/simple\n' > ${'$'}HOME/.pip/pip.conf 2>/dev/null || true
                echo "Runtime ready: Node ${'$'}(node -v 2>/dev/null || echo 'not ready'), NPM ${'$'}(npm -v 2>/dev/null || echo 'not ready')"
                if [ -d /opt/android-sdk ] || [ -d /opt/taixu/toolchains/android ]; then
                    if [ ! -f /etc/profile.d/taixu-android.sh ]; then
                        mkdir -p /etc/profile.d
                        cat << 'EOF' > /etc/profile.d/taixu-android.sh
# TaiXu Android development environment (self-healed by EnvironmentRepairer)
export JAVA_HOME="${'$'}{JAVA_HOME:-/opt/taixu/toolchains/android/jdk}"
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="/opt/android-sdk"
export GRADLE_HOME="/opt/gradle-8.14.2"
export TAIXU_AAPT2_PATH="/opt/android-sdk/build-tools/35.0.0/aapt2"
export TAIXU_NDK_PATH="/opt/taixu/toolchains/android/ndk"
export TAIXU_NDK_VERSION="r29"
export ANDROID_NDK_HOME="/opt/taixu/toolchains/android/ndk"
export ANDROID_NDK_ROOT="/opt/taixu/toolchains/android/ndk"
export TAIXU_CMAKE_HOME="/opt/taixu/tools/android-suite-offline/cmake"
export TAIXU_NINJA_HOME="/opt/taixu/tools/android-suite-offline/bin"
export PATH="/opt/taixu/bin:/opt/taixu/tools/android-suite-offline/bin:/opt/taixu/tools/android-suite-offline/cmake/bin:${'$'}JAVA_HOME/bin:${'$'}GRADLE_HOME/bin:/opt/flutter/bin:${'$'}PATH"
export _JAVA_OPTIONS="-Djava.security.egd=file:/dev/urandom"
EOF
                        chmod 644 /etc/profile.d/taixu-android.sh 2>/dev/null || true
                    fi
                    if [ -f /root/.bashrc ] && ! grep -q "taixu-android" /root/.bashrc 2>/dev/null; then
                        echo '. /etc/profile.d/taixu-android.sh 2>/dev/null || true' >> /root/.bashrc
                    fi
                fi
            """.trimIndent()
            executeCommand(setupNodeAndMirrors, logs, timeoutMs = 240_000L)

            // Trigger re-diagnosis
            addLog("Self-heal repair completed, refreshing environment diagnosis report...")
            environmentDoctor.check()

            emit(
                RepairProgress(
                    stepTitle = "Environment self-heal & acceleration config completed!",
                    stepIndex = 5,
                    totalSteps = totalSteps,
                    progress = 1.0f,
                    logs = logs.toList(),
                    isCompleted = true,
                    isFailed = false,
                ),
            )
        } catch (cancellation: CancellationException) {
            addLog("Repair process cancelled by user")
            emit(
                RepairProgress(
                    stepTitle = "Repair cancelled",
                    stepIndex = 0,
                    totalSteps = totalSteps,
                    progress = 0.0f,
                    logs = logs.toList(),
                    isCompleted = false,
                    isFailed = true,
                    errorMessage = "Repair task manually cancelled",
                ),
            )
            throw cancellation
        } catch (throwable: Throwable) {
            addLog("Repair exception: ${throwable.message}")
            emit(
                RepairProgress(
                    stepTitle = "Self-heal repair failed",
                    stepIndex = 0,
                    totalSteps = totalSteps,
                    progress = 0.0f,
                    logs = logs.toList(),
                    isCompleted = false,
                    isFailed = true,
                    errorMessage = throwable.message ?: "Unknown exception during environment self-heal repair",
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeCommand(
        command: String,
        logs: MutableList<String>,
        timeoutMs: Long = 60_000L,
    ): CommandResult {
        val result = runCatching {
            linuxRuntime.execute(ShellCommand(commandLine = command, timeoutMs = timeoutMs))
        }.getOrElse {
            CommandResult(
                exitCode = 1,
                stdout = "",
                stderr = it.message ?: "Command execution error",
                durationMs = 0L,
            )
        }

        result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(50)
            .forEach { logs.add(it) }

        if (!result.isSuccess) {
            result.stderr.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(30)
                .forEach { logs.add("ERR: $it") }
        }

        return result
    }
}
