package top.wkbin.taixu.runtime.webchat

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.QuickPhraseRepository
import top.wkbin.taixu.core.database.WorkspaceRepository
import top.wkbin.taixu.runtime.WorkspaceFileService
import top.wkbin.taixu.runtime.WorkspaceManager

const val DEFAULT_WEBCHAT_PORT = 8899

data class WebChatServerStatus(
    val isRunning: Boolean = false,
    val port: Int = DEFAULT_WEBCHAT_PORT,
    val localIp: String = "127.0.0.1",
    val pinCode: String = "",
    val activeConnections: Int = 0,
) {
    val accessUrl: String get() = "http://$localIp:$port"
}

/** LAN bridge for TaiXu's own Harness sessions and registered Linux workspaces. */
class WebChatBridgeServer(
    private val context: Context,
    private val sessions: HarnessSessionRepository,
    private val models: AiModelRepository,
    private val quickPhrases: QuickPhraseRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
    private val workspaceFiles: WorkspaceFileService,
    private val agentGateway: WebChatAgentGateway,
    private val logger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: AndroidHttpServer? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val _status = MutableStateFlow(WebChatServerStatus())
    val status: StateFlow<WebChatServerStatus> = _status.asStateFlow()

    private val sseEmitters = ConcurrentHashMap.newKeySet<AndroidHttpExchange>()
    private val taskSessions = ConcurrentHashMap<String, String>()
    private val sessionObservers = ConcurrentHashMap<String, Job>()
    private var heartbeatJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    fun start(port: Int = DEFAULT_WEBCHAT_PORT, pin: String? = null): Boolean {
        if (_status.value.isRunning) return true
        return try {
            val generatedPin = pin ?: generatePin()
            // Worker thread pool owned by AndroidHttpServer: reclaimed with service on stop(),
            // no longer letting this class hold a never-shutdown fixedThreadPool.
            val server = AndroidHttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/webchat/api/session/bootstrap", SessionBootstrapHandler())
            server.createContext("/webchat/api/bootstrap", BootstrapHandler())
            server.createContext("/webchat/api/conversations", ConversationsHandler())
            server.createContext("/webchat/api/tasks", TasksHandler())
            server.createContext("/webchat/api/events", SseEventsHandler())
            server.createContext("/webchat/api/workspaces", WorkspacesHandler())
            server.createContext("/", StaticAssetHandler())
            server.start()
            httpServer = server

            val localIp = resolveLocalIp()
            _status.value = WebChatServerStatus(true, port, localIp, generatedPin)
            acquireLocks()
            showNotification("http://$localIp:$port", generatedPin)
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(25_000)
                    if (sseEmitters.isNotEmpty()) {
                        broadcastEvent("ping", "{}")
                    }
                }
            }
            logger.i("TaiXu Web Collaboration Service started: http://$localIp:$port")
            true
        } catch (exception: Exception) {
            logger.e("TaiXu Web Collaboration Service failed to start", exception)
            false
        }
    }

    fun stop() {
        runCatching {
            heartbeatJob?.cancel()
            heartbeatJob = null
            releaseLocks()
            hideNotification()
            sessionObservers.values.forEach(Job::cancel)
            sessionObservers.clear()
            taskSessions.clear()
            sseEmitters.forEach { runCatching { it.close() } }
            sseEmitters.clear()
            httpServer?.stop(0)
            httpServer = null
            _status.value = _status.value.copy(isRunning = false, activeConnections = 0)
        }.onFailure { logger.e("TaiXu Web Collaboration Service stop error", it) }
    }

    fun broadcastEvent(eventName: String, dataJson: String) {
        val payload = "event: $eventName\ndata: $dataJson\n\n".toByteArray(Charsets.UTF_8)
        val iterator = sseEmitters.iterator()
        while (iterator.hasNext()) {
            val exchange = iterator.next()
            try {
                exchange.responseBody.write(payload)
                exchange.responseBody.flush()
            } catch (_: Exception) {
                runCatching { exchange.close() }
                iterator.remove()
            }
        }
        _status.value = _status.value.copy(activeConnections = sseEmitters.size)
    }

    /**
     * Unified error handling for request coroutines. Handlers run async in [scope]; thrown exceptions dont return to
     * AndroidHttpServer accept loop, socket would hang with its worker thread:
     * this ensures error path also returns 500 and closes connection (AndroidHttpExchange.close is idempotent).
     *
     * Returns Unit not Job: call site is `override fun handle(...) = launchRequest(...) { }`,
     * returning Job would force each handler to end with `.let { }` to satisfy Unit override.
     */
    private fun launchRequest(
        exchange: AndroidHttpExchange,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                logger.e("TaiXu Web request failed: ${exchange.requestURI.path}", throwable)
                if (!exchange.isResponseStarted) {
                    runCatching { sendJson(exchange, 500, errorJson(throwable.message ?: "Request handling failed")) }
                }
            } finally {
                runCatching { exchange.close() }
            }
        }
    }

    private inner class StaticAssetHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/").trimStart('/')
            val assetPath = if (path.isBlank() || !hasFileExtension(path)) "webchat/index.html" else "webchat/$path"
            val stream: InputStream = runCatching { context.assets.open(assetPath) }.getOrElse {
                if (hasFileExtension(path)) {
                    sendText(exchange, 404, "Resource not found")
                    return
                }
                context.assets.open("webchat/index.html")
            }
            stream.use { sendResponse(exchange, 200, getMimeType(assetPath), it.readBytes()) }
        }
    }

    private inner class SessionBootstrapHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            val token = requestJson(exchange)["token"]?.jsonPrimitive?.content.orEmpty()
            if (token != _status.value.pinCode) sendJson(exchange, 401, errorJson("Invalid pairing code"))
            else sendJson(exchange, 200, buildJsonObject { put("authenticated", true) })
        }
    }

    private inner class BootstrapHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            val configuredModels = models.observeAll().firstValue()
            # Like ChatViewModel, seed built-in phrases first so chat page never shows empty list
            runCatching { quickPhrases.ensureInitialized() }
            val enabledPhrases = runCatching {
                quickPhrases.getAll()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
            }.getOrDefault(emptyList())
            sendJson(exchange, 200, buildJsonObject {
                put("authenticated", true)
                put("appName", "TaiXu")
                put("version", "1.0")
                putJsonArray("models") {
                    configuredModels.forEach { model ->
                        add(buildJsonObject {
                            put("id", model.id)
                            put("name", model.name)
                            put("model", model.model)
                            put("active", model.isActive)
                        })
                    }
                }
                putJsonArray("quickPhrases") {
                    enabledPhrases.forEach { phrase ->
                        add(buildJsonObject {
                            put("id", phrase.id)
                            put("title", phrase.title)
                            put("content", phrase.content)
                            if (phrase.description.isNotBlank()) put("description", phrase.description)
                        })
                    }
                }
                put("workspace", buildJsonObject {
                    put("workspace", buildJsonObject { put("rootPath", "/workspace") })
                    put("root", buildJsonObject { put("path", "/workspace") })
                })
            })
        }
    }

    private inner class ConversationsHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            try {
                val suffix = exchange.requestURI.path.substringAfter("/conversations", "").trim('/')
                val parts = suffix.split('/').filter(String::isNotBlank)
                when {
                    parts.isEmpty() && exchange.requestMethod == "GET" -> {
                        sendJson(exchange, 200, buildJsonArray {
                            sessions.listAll().sortedByDescending(HarnessSessionEntity::updatedAt).forEach { add(conversationJson(it)) }
                        })
                    }
                    parts.isEmpty() && exchange.requestMethod == "POST" -> {
                        val body = requestJson(exchange)
                        val workspace = body["workspace"]?.jsonPrimitive?.content.orEmpty()
                        val id = agentGateway.createSession(
                            body["title"]?.jsonPrimitive?.content.orEmpty(),
                            workspace.takeIf { isRegisteredWorkspacePath(it) }.orEmpty(),
                        )
                        val session = requireNotNull(sessions.findById(id))
                        sendJson(exchange, 200, conversationJson(session))
                        broadcastEvent("conversation_created", buildJsonObject { put("conversationId", id) }.toString())
                    }
                    parts.size == 1 && exchange.requestMethod == "DELETE" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "Session not found" }
                        agentGateway.deleteSession(id)
                        sendJson(exchange, 200, buildJsonObject { put("deleted", true) })
                        broadcastEvent("conversation_deleted", buildJsonObject { put("conversationId", id) }.toString())
                    }
                    parts.size == 2 && parts[1] == "messages" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "Session not found" }
                        sendJson(exchange, 200, messageArray(agentGateway.messages(id)))
                    }
                    parts.size == 2 && parts[1] == "approvals" && exchange.requestMethod == "GET" -> {
                        val id = parts[0]
                        requireNotNull(sessions.findById(id)) { "Session not found" }
                        sendJson(exchange, 200, approvalArray(agentGateway.pendingApprovals(id)))
                    }
                    parts.size == 3 && parts[1] == "approvals" && exchange.requestMethod == "POST" -> {
                        val sessionId = parts[0]
                        requireNotNull(sessions.findById(sessionId)) { "Session not found" }
                        val body = requestJson(exchange)
                        val approved = body["approved"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                            ?: throw IllegalArgumentException("Missing approval decision")
                        val accepted = agentGateway.resolveApproval(sessionId, parts[2], approved)
                        require(accepted) { "Approval request not found, already processed, or not in this session" }
                        val taskId = taskSessions.entries.firstOrNull { it.value == sessionId }?.key
                        sendJson(exchange, 200, buildJsonObject {
                            put("accepted", true)
                            taskId?.let { put("taskId", it) }
                        })
                    }
                    parts.size == 2 && parts[1] == "runs" && exchange.requestMethod == "POST" -> {
                        startRun(exchange, parts[0])
                    }
                    else -> sendText(exchange, 404, "Endpoint not found")
                }
            } catch (throwable: Throwable) {
                sendJson(exchange, 400, errorJson(throwable.message ?: "Session operation failed"))
            }
        }
    }

    private suspend fun startRun(exchange: AndroidHttpExchange, sessionId: String) {
        requireNotNull(sessions.findById(sessionId)) { "Session not found" }
        val body = requestJson(exchange)
        val text = body["userMessage"]?.jsonPrimitive?.content.orEmpty()
        val imageUrls = body["attachments"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["dataUrl"]?.jsonPrimitive?.content?.takeIf { it.startsWith("data:image/") }
        }
        require(text.isNotBlank() || imageUrls.isNotEmpty()) { "Message cannot be empty" }
        val taskId = body["taskId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: "web-${System.currentTimeMillis()}"
        taskSessions[taskId] = sessionId
        agentGateway.send(sessionId, text, imageUrls)
        observeRun(sessionId, taskId)
        sendJson(exchange, 200, buildJsonObject {
            put("taskId", taskId)
            put("conversationMode", "normal")
            put("conversation", conversationJson(requireNotNull(sessions.findById(sessionId))))
        })
    }

    private fun observeRun(sessionId: String, taskId: String) {
        sessionObservers.remove(sessionId)?.cancel()
        sessionObservers[sessionId] = scope.launch {
            var observedRunning = false
            var waitingEventSent = false
            agentGateway.observeSession(sessionId).collect { snapshot ->
                if (snapshot.running) {
                    observedRunning = true
                    waitingEventSent = false
                }
                broadcastEvent("messages_replaced", buildJsonObject {
                    put("conversationId", sessionId)
                    put("conversationMode", "normal")
                    put("messages", messageArray(snapshot.messages))
                }.toString())
                if (snapshot.waitingApproval) {
                    if (!waitingEventSent) {
                        broadcastEvent("chat_task_event", taskEvent(taskId, "waiting_approval", sessionId, snapshot.approvals))
                        waitingEventSent = true
                    }
                } else if (observedRunning && !snapshot.running) {
                    broadcastEvent("chat_task_event", taskEvent(taskId, if (snapshot.error == null) "completed" else "error", sessionId))
                    taskSessions.remove(taskId)
                    cancel()
                }
            }
        }
    }

    private inner class TasksHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            val suffix = exchange.requestURI.path.substringAfter("/tasks", "").trim('/')
            val parts = suffix.split('/').filter(String::isNotBlank)
            if (parts.size == 2 && parts[1] == "cancel" && exchange.requestMethod == "POST") {
                val taskId = parts[0]
                val sessionId = taskSessions.remove(taskId)
                if (sessionId != null) agentGateway.cancel(sessionId)
                sendJson(exchange, 200, buildJsonObject { put("cancelled", sessionId != null) })
            } else {
                sendText(exchange, 404, "Task endpoint not found")
            }
        }
    }

    private inner class SseEventsHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) {
            if (handlePreflight(exchange)) return
            if (!isAuthenticated(exchange)) {
                sendJson(exchange, 401, errorJson("Pair first"))
                return
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0)
            sseEmitters.add(exchange)
            _status.value = _status.value.copy(activeConnections = sseEmitters.size)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
        }
    }

    private inner class WorkspacesHandler : AndroidHttpHandler {
        override fun handle(exchange: AndroidHttpExchange) = launchRequest(exchange) {
            if (handlePreflight(exchange)) return@launchRequest
            if (!requireAuthenticated(exchange)) return@launchRequest
            try {
                val suffix = exchange.requestURI.path.substringAfter("/workspaces", "").trim('/')
                when {
                    suffix.isEmpty() && exchange.requestMethod == "GET" -> listWorkspace(exchange)
                    suffix == "file" && exchange.requestMethod == "GET" -> readWorkspaceFile(exchange)
                    suffix == "file" && exchange.requestMethod == "PUT" -> writeWorkspaceFile(exchange)
                    suffix == "download" && exchange.requestMethod == "GET" -> downloadWorkspaceFile(exchange)
                    else -> sendText(exchange, 404, "Workspace endpoint not found")
                }
            } catch (throwable: Throwable) {
                sendJson(exchange, 400, errorJson(throwable.message ?: "Workspace operation failed"))
            }
        }
    }

    private suspend fun listWorkspace(exchange: AndroidHttpExchange) {
        val path = getQueryParam(exchange, "path").orEmpty().ifBlank { "/workspace" }
        if (path.trimEnd('/') == "/workspace") {
            val projects = workspaceManager.listProjects()
            sendJson(exchange, 200, buildJsonObject {
                put("path", "/workspace")
                putJsonArray("items") {
                    projects.forEach { project ->
                        add(buildJsonObject {
                            put("name", project.name)
                            put("path", project.linuxPath)
                            put("isDirectory", true)
                            put("size", project.sizeBytes)
                        })
                    }
                }
            })
            return
        }
        val (project, relative) = parseWorkspacePath(path)
        val items = workspaceFiles.listFiles(project, relative).orThrow()
        sendJson(exchange, 200, buildJsonObject {
            put("path", workspacePath(project, relative))
            putJsonArray("items") {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", workspacePath(project, item.relativePath))
                        put("isDirectory", item.isDirectory)
                        put("size", item.sizeBytes)
                    })
                }
            }
        })
    }

    private suspend fun readWorkspaceFile(exchange: AndroidHttpExchange) {
        val (project, relative) = parseWorkspacePath(requireNotNull(getQueryParam(exchange, "path")))
        sendJson(exchange, 200, buildJsonObject {
            put("content", workspaceFiles.readFile(project, relative).orThrow())
        })
    }

    private suspend fun writeWorkspaceFile(exchange: AndroidHttpExchange) {
        val body = requestJson(exchange)
        val (project, relative) = parseWorkspacePath(body["path"]?.jsonPrimitive?.content.orEmpty())
        workspaceFiles.writeFile(project, relative, body["content"]?.jsonPrimitive?.content.orEmpty()).orThrow()
        sendJson(exchange, 200, buildJsonObject { put("saved", true) })
        broadcastEvent("workspace_changed", buildJsonObject { put("path", workspacePath(project, relative)) }.toString())
    }

    private suspend fun downloadWorkspaceFile(exchange: AndroidHttpExchange) {
        val (project, relative) = parseWorkspacePath(requireNotNull(getQueryParam(exchange, "path")))
        val entity = requireNotNull(workspaces.findByName(project)) { "Workspace not found" }
        val root = File(entity.path).canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.isFile && (file == root || file.path.startsWith(root.path + File.separator))) { "Invalid file path" }
        exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"${file.name.replace("\"", "")}\"")
        sendResponse(exchange, 200, "application/octet-stream", file.readBytes())
    }

    private fun conversationJson(session: HarnessSessionEntity) = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("mode", "normal")
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("workspace", session.workspace)
    }

    private fun messageArray(messages: List<WebChatMessage>) = buildJsonArray {
        messages.forEach { add(json.encodeToJsonElement(WebChatMessage.serializer(), it)) }
    }

    private fun approvalArray(approvals: List<WebChatApproval>) = buildJsonArray {
        approvals.forEach { add(json.encodeToJsonElement(WebChatApproval.serializer(), it)) }
    }

    private fun taskEvent(
        taskId: String,
        kind: String,
        sessionId: String,
        approvals: List<WebChatApproval> = emptyList(),
    ) = buildJsonObject {
        put("taskId", taskId)
        put("kind", kind)
        put("conversationId", sessionId)
        if (approvals.isNotEmpty()) put("approvals", approvalArray(approvals))
    }.toString()

    private fun requestJson(exchange: AndroidHttpExchange): JsonObject {
        val raw = exchange.requestBody.bufferedReader().readText()
        return if (raw.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw).jsonObject
    }

    private fun handlePreflight(exchange: AndroidHttpExchange): Boolean {
        if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendResponse(exchange, 204, "text/plain", ByteArray(0))
            return true
        }
        return false
    }

    private fun isAuthenticated(exchange: AndroidHttpExchange): Boolean {
        val token = getQueryParam(exchange, "token")
            ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        return token != null && token == _status.value.pinCode
    }

    private fun requireAuthenticated(exchange: AndroidHttpExchange): Boolean {
        if (isAuthenticated(exchange)) return true
        sendJson(exchange, 401, errorJson("Please pair with pairing code first"))
        return false
    }

    private fun parseWorkspacePath(path: String): Pair<String, String> {
        val normalized = path.replace('\\', '/').trim().removeSuffix("/")
        require(normalized.startsWith("/workspace/")) { "Only registered /workspace projects allowed" }
        val tail = normalized.removePrefix("/workspace/")
        val project = tail.substringBefore('/')
        val relative = tail.substringAfter('/', "")
        require(project.isNotBlank() && relative.split('/').none { it == ".." }) { "Invalid workspace path" }
        return project to relative
    }

    private suspend fun isRegisteredWorkspacePath(path: String): Boolean = runCatching {
        val (project, _) = parseWorkspacePath(path)
        workspaces.findByName(project) != null
    }.getOrDefault(false)

    private fun workspacePath(project: String, relative: String): String =
        "/workspace/$project" + relative.trim('/').takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()

    private fun <T> AppResult<T>.orThrow(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw IllegalArgumentException(error.message)
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }

    private fun sendJson(exchange: AndroidHttpExchange, code: Int, payload: kotlinx.serialization.json.JsonElement) =
        sendResponse(exchange, code, "application/json; charset=utf-8", payload.toString().toByteArray())

    private fun sendText(exchange: AndroidHttpExchange, code: Int, text: String) =
        sendResponse(exchange, code, "text/plain; charset=utf-8", text.toByteArray())

    private fun sendResponse(exchange: AndroidHttpExchange, code: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        exchange.close()
    }

    private fun getQueryParam(exchange: AndroidHttpExchange, key: String): String? {
        val raw = exchange.requestURI.query.orEmpty().split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "") ?: return null
        return URLDecoder.decode(raw, Charsets.UTF_8.name())
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

    private fun acquireLocks() {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        wakeLock = power?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "taixu:webchat_bridge_wake")?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        wifiLock = wifi?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "taixu:webchat_bridge_wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun showNotification(url: String, pin: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "TaiXu Web Collaboration",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "TaiXu LAN collaboration service"; setShowBadge(false) },
            )
        }
        manager.notify(
            NOTIFICATION_ID,
            androidx.core.app.NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("TaiXu Web Collaboration Running")
                .setContentText("$url (pairing: $pin)")
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    private fun hideNotification() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(NOTIFICATION_ID)
    }

    private fun getMimeType(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    private fun hasFileExtension(path: String): Boolean = path.substringAfterLast('/', "").contains('.')
    private fun generatePin(): String = (100000..999999).random().toString()

    private fun resolveLocalIp(): String = runCatching {
        var fallback = "127.0.0.1"
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (network.isLoopback || !network.isUp) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress.orEmpty()
                    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return@runCatching host
                    fallback = host
                }
            }
        }
        fallback
    }.getOrDefault("127.0.0.1")

    companion object {
        const val DEFAULT_PORT = DEFAULT_WEBCHAT_PORT
        const val NOTIFICATION_CHANNEL_ID = "taixu_webchat_bridge"
        const val NOTIFICATION_ID = 8899
    }
}
