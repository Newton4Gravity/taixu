package top.wkbin.taixu.runtime.webchat

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small HTTP/1.1 server backed only by Android-supported java.net APIs.
 *
 * Android does not ship the desktop-JDK `com.sun.net.httpserver` module. This
 * adapter intentionally exposes only the subset WebChat needs: prefix routes,
 * fixed-length responses, and an open-ended response body for SSE.
 *
 * Resource ownership: ServerSocket, default thread pool, and all accepted connections
 * are held by this class; [stop] must release all of them — services on mobile are
 * repeatedly started/stopped, and any residue accumulates into resident threads or
 * dangling file descriptors. Externally injected thread pools belong to the injector
 * and are not closed here.
 */
internal class AndroidHttpServer private constructor(
    private val address: InetSocketAddress,
    private val backlog: Int,
) {
    private val contexts = ConcurrentHashMap<String, AndroidHttpHandler>()

    /** Accepted and not-yet-closed connections, used by [stop] to reclaim long-lived connections like SSE. */
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private var externalExecutor: Executor? = null
    private var ownedExecutor: ExecutorService? = null

    /**
     * Worker thread pool. Default is lazily created by this class and reclaimed in
     * [stop]; once externally injected, shutdown responsibility transfers to the
     * injector (this class only releases the one it created).
     */
    var executor: Executor
        get() = currentExecutor()
        set(value) = synchronized(this) {
            ownedExecutor?.shutdownNow()
            ownedExecutor = null
            externalExecutor = value
        }

    fun createContext(path: String, handler: AndroidHttpHandler) {
        require(path.startsWith('/')) { "HTTP context path must start with /" }
        contexts[path] = handler
    }

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(address, backlog.coerceAtLeast(DEFAULT_BACKLOG))
        }
        serverSocket = socket
        running = true
        Thread({ acceptConnections(socket) }, "taixu-webchat-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop(delaySeconds: Int) {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Accepted connections are not closed with the listening socket: SSE streams
        // can hang worker threads and fds.
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        ownedExecutor?.shutdownNow()
        ownedExecutor = null
        if (delaySeconds > 0) {
            // The WebChat caller always requests an immediate stop. The argument
            // is kept to mirror the old API and make that intent explicit.
        }
    }

    private fun currentExecutor(): Executor = synchronized(this) {
        externalExecutor
            ?: ownedExecutor?.takeIf { !it.isShutdown }
            ?: createOwnedExecutor().also { ownedExecutor = it }
    }

    /**
     * Default thread pool: concurrency cap matches old `newFixedThreadPool(8)`, but
     * core threads can timeout and all are daemon — no resident threads after service
     * stop, and process exit is not blocked.
     */
    private fun createOwnedExecutor(): ExecutorService = ThreadPoolExecutor(
        MAX_WORKER_THREADS,
        MAX_WORKER_THREADS,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable -> Thread(runnable, "taixu-webchat-worker").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    private fun acceptConnections(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                if (!running) {
                    runCatching { client.close() }
                    break
                }
                activeSockets.add(client)
                try {
                    currentExecutor().execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    activeSockets.remove(client)
                    runCatching { client.close() }
                }
            } catch (_: SocketException) {
                if (running) continue
                break
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readHttpLine(input) ?: run {
                socket.close()
                return
            }
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size < 2) {
                writeSimpleError(socket, 400, "Bad Request")
                return
            }

            val headers = AndroidHttpHeaders()
            var headerBytes = requestLine.length
            while (true) {
                val line = readHttpLine(input) ?: break
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES) {
                    writeSimpleError(socket, 431, "Request Header Fields Too Large")
                    return
                }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers.add(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
                }
            }

            val contentLength = headers.getFirst("Content-Length")?.toIntOrNull() ?: 0
            if (contentLength !in 0..MAX_BODY_BYTES) {
                writeSimpleError(socket, 413, "Payload Too Large")
                return
            }
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                if (count < 0) break
                offset += count
            }

            val target = requestParts[1]
            val path = target.substringBefore('?').ifBlank { "/" }
            val query = target.substringAfter('?', "").ifBlank { null }
            val handler = contexts.entries
                .asSequence()
                .filter { path.startsWith(it.key) }
                .maxByOrNull { it.key.length }
                ?.value

            if (handler == null) {
                writeSimpleError(socket, 404, "Not Found")
                return
            }

            val exchange = AndroidHttpExchange(
                socket = socket,
                requestMethod = requestParts[0],
                requestURI = AndroidHttpUri(path, query),
                requestHeaders = headers,
                requestBody = ByteArrayInputStream(body, 0, offset),
                // Async handler closes connection after this method returns via callback, not polling.
                onClose = { activeSockets.remove(socket) },
            )
            handler.handle(exchange)
        } catch (_: Exception) {
            runCatching { socket.close() }
        } finally {
            // Covers early-return paths that never reach exchange (400/404/431/413 and read errors).
            if (socket.isClosed) activeSockets.remove(socket)
        }
    }

    private fun writeSimpleError(socket: Socket, code: Int, message: String) {
        runCatching {
            val body = message.toByteArray(Charsets.UTF_8)
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write("HTTP/1.1 $code $message\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(Charsets.US_ASCII))
            output.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
        runCatching { socket.close() }
    }

    /**
     * Read one line byte by byte. Uses [ByteArrayOutputStream] instead of
     * `ArrayList<Byte>`: the latter allocates a boxed reference slot for each
     * request header byte — 8 KB line header means 8K references.
     */
    private fun readHttpLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(INITIAL_LINE_BYTES)
        while (buffer.size() <= MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        if (buffer.size() > MAX_LINE_BYTES) throw IllegalArgumentException("HTTP line too long")
        return buffer.toString(Charsets.US_ASCII.name())
    }

    companion object {
        private const val DEFAULT_BACKLOG = 16
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val INITIAL_LINE_BYTES = 128
        private const val MAX_WORKER_THREADS = 8
        private const val WORKER_KEEP_ALIVE_SECONDS = 30L

        fun create(address: InetSocketAddress, backlog: Int): AndroidHttpServer =
            AndroidHttpServer(address, backlog)
    }
}

internal fun interface AndroidHttpHandler {
    fun handle(exchange: AndroidHttpExchange)
}

internal data class AndroidHttpUri(
    val path: String,
    val query: String?,
)

internal class AndroidHttpHeaders {
    private val values = LinkedHashMap<String, MutableList<String>>()

    @Synchronized
    fun add(name: String, value: String) {
        val existingName = values.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        values.getOrPut(existingName) { mutableListOf() }.add(value)
    }

    @Synchronized
    fun getFirst(name: String): String? =
        values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    @Synchronized
    internal fun entries(): List<Pair<String, String>> =
        values.flatMap { (name, entries) -> entries.map { name to it } }
}

internal class AndroidHttpExchange(
    private val socket: Socket,
    val requestMethod: String,
    val requestURI: AndroidHttpUri,
    val requestHeaders: AndroidHttpHeaders,
    val requestBody: InputStream,
    private val onClose: () -> Unit = {},
) {
    val responseHeaders = AndroidHttpHeaders()
    private val output = BufferedOutputStream(socket.getOutputStream())
    val responseBody: OutputStream = output

    @Volatile
    private var responseStarted = false
    private val closed = AtomicBoolean(false)

    /** Whether response has started: exception fallback uses this to decide if another
     * error response can still be written. */
    val isResponseStarted: Boolean
        get() = responseStarted

    @Synchronized
    fun sendResponseHeaders(code: Int, responseLength: Long) {
        check(!responseStarted) { "Response headers already sent" }
        responseStarted = true
        output.write("HTTP/1.1 $code ${statusText(code)}\r\n".toByteArray(Charsets.US_ASCII))
        responseHeaders.entries().forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (responseLength > 0 && responseHeaders.getFirst("Content-Length") == null) {
            output.write("Content-Length: $responseLength\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (responseLength > 0 && responseHeaders.getFirst("Connection") == null) {
            output.write("Connection: close\r\n".toByteArray(Charsets.US_ASCII))
        }
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Idempotent: repeated calls only trigger [onClose] once; SSE broadcast failures
     * and coroutine fallbacks can safely call it multiple times. */
    fun close() {
        runCatching { output.close() }
        runCatching { socket.close() }
        if (closed.compareAndSet(false, true)) runCatching { onClose() }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "HTTP Response"
    }
}
