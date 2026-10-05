package top.wkbin.taixu.runtime.terminal

import com.termux.terminal.TerminalSession
import top.wkbin.taixu.core.database.TerminalSessionEntity
import top.wkbin.taixu.core.database.TerminalSessionRepository
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.SessionConfig
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Multi-session console manager.
 *
 * Each session is a Termux [TerminalSession] whose subprocess is our PRoot
 * interactive shell (same argv as NativePty). VT rendering / IME / resize are
 * owned by Termux [com.termux.view.TerminalView] in the UI layer.
 */
class TerminalSessionHandle internal constructor(
    val id: String,
    val label: String,
    val workingDirectory: String,
    val distributionId: String = "ubuntu",
    val termuxSession: TerminalSession,
) {
    val isAlive: Boolean get() = termuxSession.isRunning
}

class TerminalSessionManager(
    private val linuxRuntime: LinuxRuntime,
    private val terminalSessionDao: TerminalSessionRepository,
    private val sessionClientRouter: TerminalSessionClientRouter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _handles = MutableStateFlow<List<TerminalSessionHandle>>(emptyList())
    val handles: StateFlow<List<TerminalSessionHandle>> = _handles.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val activeHandle: StateFlow<TerminalSessionHandle?> =
        combine(_handles, _activeId) { handles, id -> handles.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    @Volatile
    private var restoredOnce = false

    suspend fun ensureActive(initialWorkingDirectory: String = DEFAULT_CWD) {
        val aliveHandles = _handles.value.filter { it.isAlive }
        if (aliveHandles.size != _handles.value.size) {
            _handles.value.filterNot { it.isAlive }.forEach { dead ->
                runCatching { dead.termuxSession.finishIfRunning() }
            }
            _handles.value = aliveHandles
            _activeId.value = _activeId.value?.takeIf { id -> aliveHandles.any { it.id == id } }
            if (aliveHandles.isEmpty()) restoredOnce = false
        }
        if (!restoredOnce) {
            restoredOnce = true
            val rows = terminalSessionDao.listAll()
            if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    createSession(
                        id = row.id,
                        label = row.label,
                        workingDirectory = row.workingDirectory,
                        distributionId = row.distributionId,
                    )
                }
                _activeId.value = _handles.value.firstOrNull()?.id ?: _activeId.value
                return
            }
        }
        if (_handles.value.isEmpty()) {
            createSession(workingDirectory = initialWorkingDirectory)
        }
    }

    suspend fun createSession(
        label: String = "Session ${_handles.value.size + 1}",
        workingDirectory: String = DEFAULT_CWD,
        distributionId: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): TerminalSessionHandle {
        val targetDistro = distributionId?.trim()?.takeIf { it.isNotBlank() } ?: linuxRuntime.activeDistroId.value
        val launch = linuxRuntime.buildInteractiveLaunch(
            config = SessionConfig(workingDirectory = workingDirectory, showBanner = true),
            distroId = targetDistro,
        )
        android.util.Log.i(
            "TaiXuTerminal",
            "createSession distro=$targetDistro cwd=${launch.workingDirectory} " +
                "argv0=${launch.executable} argc=${launch.arguments.size} env=${launch.environment.size}",
        )
        val termux = try {
            TerminalSession(
                launch.executable,
                launch.workingDirectory,
                launch.arguments,
                launch.environment,
                TRANSCRIPT_ROWS,
                sessionClientRouter,
            )
        } catch (t: Throwable) {
            android.util.Log.e("TaiXuTerminal", "TerminalSession construct failed", t)
            throw t
        }
        val handle = TerminalSessionHandle(
            id = id,
            label = label,
            workingDirectory = workingDirectory,
            distributionId = targetDistro,
            termuxSession = termux,
        )
        terminalSessionDao.upsert(
            TerminalSessionEntity(
                id = id,
                label = label,
                workingDirectory = workingDirectory,
                distributionId = targetDistro,
                createdAt = System.currentTimeMillis(),
                sortOrder = terminalSessionDao.nextOrder(),
            ),
        )
        _handles.value = _handles.value + handle
        _activeId.value = handle.id
        return handle
    }

    suspend fun openOrSwitchToProject(
        project: String,
        workingDirectory: String,
        distributionId: String? = null,
    ): TerminalSessionHandle {
        ensureActive()
        val existing = _handles.value.firstOrNull { it.workingDirectory == workingDirectory }
        if (existing != null) {
            _activeId.value = existing.id
            return existing
        }
        return createSession(
            label = project.ifBlank { "Workspace" },
            workingDirectory = workingDirectory,
            distributionId = distributionId,
        )
    }

    fun switchTo(id: String) {
        if (_handles.value.any { it.id == id }) {
            _activeId.value = id
        }
    }

    suspend fun closeSession(id: String) {
        val handle = _handles.value.find { it.id == id } ?: return
        runCatching { handle.termuxSession.finishIfRunning() }
        terminalSessionDao.delete(id)
        val remaining = _handles.value.filterNot { it.id == id }
        _handles.value = remaining
        if (remaining.isEmpty()) {
            val fallback = createSession(label = "Main Terminal", workingDirectory = DEFAULT_CWD)
            _activeId.value = fallback.id
        } else if (_activeId.value == id) {
            _activeId.value = remaining.lastOrNull()?.id ?: remaining.firstOrNull()?.id
        }
    }

    suspend fun closeAllSessions() {
        val snapshot = _handles.value.toList()
        snapshot.forEach { handle ->
            runCatching { handle.termuxSession.finishIfRunning() }
        }
        terminalSessionDao.deleteAll()
        _handles.value = emptyList()
        _activeId.value = null
        restoredOnce = false
    }

    fun write(id: String, data: ByteArray) {
        val handle = _handles.value.find { it.id == id } ?: return
        handle.termuxSession.write(data, 0, data.size)
    }

    fun paste(id: String, text: String) {
        write(id, text.toByteArray(Charsets.UTF_8))
    }

    fun interrupt(id: String) {
        write(id, byteArrayOf(3))
    }

    private companion object {
        const val DEFAULT_CWD = "/root"
        const val TRANSCRIPT_ROWS = 2000
    }
}
