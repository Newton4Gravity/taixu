package top.wkbin.taixu.runtime.webchat

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHttpServerTest {

    @Test
    fun `serves a fixed length response over a socket`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/test") { exchange ->
            val body = "{\"ok\":true}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            server.start()
            val response = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /api/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Length: 11"))
            assertTrue(response.endsWith("{\"ok\":true}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stop closes open ended connections and releases the listening socket`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val streaming = CountDownLatch(1)
        server.createContext("/api/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
            streaming.countDown()
            // Intentionally not closing: simulates WebChat SSE long connection, cleanup responsibility falls on stop().
        }

        val client = Socket()
        try {
            server.start()
            client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write("GET /api/events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            val input = client.getInputStream()
            assertTrue("SSE first byte should be readable", input.read() >= 0)
            assertTrue("handler should have taken over connection", streaming.await(5, TimeUnit.SECONDS))

            server.stop(0)

            // When server closes connection, read EOF or RST; if still hanging, soTimeout throws timeout — that's a leak.
            val outcome = runCatching {
                var value = input.read()
                while (value >= 0) value = input.read()
            }
            assertFalse(
                "stop() must close already accepted long connections",
                outcome.exceptionOrNull() is SocketTimeoutException,
            )

            // Listening socket released: same port can be rebound immediately.
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                probe.bind(InetSocketAddress("127.0.0.1", port))
            }
        } finally {
            runCatching { client.close() }
            server.stop(0)
        }
    }

    @Test
    fun `server can be restarted after stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/ping") { exchange ->
            val body = "pong".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            repeat(2) {
                server.start()
                val response = Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().apply {
                        write("GET /api/ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
                assertTrue("Run ${it + 1} should respond normally", response.endsWith("pong"))
                server.stop(0)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `injected executor is owned by the caller and survives stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val injected = Executors.newSingleThreadExecutor()
        server.executor = injected

        try {
            server.start()
            server.stop(0)
            assertFalse("Externally injected thread pool should not be shut down by stop()", injected.isShutdown)
        } finally {
            injected.shutdownNow()
            server.stop(0)
        }
    }
}
