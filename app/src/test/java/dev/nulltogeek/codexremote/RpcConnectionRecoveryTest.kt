package dev.nulltogeek.codexremote

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Real sockets reproduce a reachable host whose upgrade or RPC reply stalls. */
class RpcConnectionRecoveryTest {
    private class Host : Closeable {
        val server = MockWebServer()
        val methods = CopyOnWriteArrayList<String>()
        val acceptedPrompts = AtomicInteger()
        @Volatile var stall: String? = null
        init { server.start() }
        fun enqueueSocket() {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = WireJson.parseToJsonElement(text).jsonObject
                    val method = request.text("method")
                    methods += method
                    if (method == "turn/start") acceptedPrompts.incrementAndGet()
                    if (method == "initialized" || method == stall) return
                    val result = if (method == "bridge/subscribe") buildJsonObject { put("epoch", "test-runtime"); put("sequence", 0) }
                        else if (method == "bridge/commandOutput") request.obj("params") else buildJsonObject { put("ok", true) }
                    webSocket.send(buildJsonObject { put("id", request.getValue("id")); put("result", result) }.toString())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
        }
        fun client() = RpcClient(connectTimeoutMillis = 350, requestTimeoutMillis = 350).apply {
            configure("http://127.0.0.1:${server.port}", "disposable-session")
        }
        override fun close() { server.shutdown() }
    }

    private fun assertRecoverable(failure: Throwable?, rpc: RpcClient) {
        assertTrue("A transport timeout must be retryable, got $failure", failure is IOException)
        assertFalse(failure is CancellationException)
        assertEquals("Connection lost", rpc.status.value)
    }

    @Test fun upgradeTimeoutIsRetryableAndTheNextConnectionSucceeds() = runBlocking {
        Host().use { host ->
            host.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            host.enqueueSocket()
            val rpc = host.client()
            try {
                assertRecoverable(runCatching { rpc.connect() }.exceptionOrNull(), rpc)
                rpc.connect()
                assertEquals("Connected", rpc.status.value)
                assertEquals(2, host.server.requestCount)
            } finally { rpc.close() }
        }
    }

    @Test fun initializeTimeoutCleansUpAndReconnects() = runBlocking {
        Host().use { host ->
            host.stall = "initialize"; host.enqueueSocket(); host.enqueueSocket()
            val rpc = host.client()
            try {
                assertRecoverable(runCatching { rpc.connect() }.exceptionOrNull(), rpc)
                host.stall = null; rpc.connect()
                assertEquals("Connected", rpc.status.value)
            } finally { rpc.close() }
        }
    }

    @Test fun subscribeTimeoutCannotKillTheAutomaticRetryCoroutine() = runBlocking {
        Host().use { host ->
            host.stall = "bridge/subscribe"; host.enqueueSocket(); host.enqueueSocket()
            val rpc = host.client()
            var attempts = 0
            try {
                withTimeout(5000) {
                    // Same cancellation handling as the app and task monitor.
                    while (attempts < 2) {
                        attempts++
                        try { rpc.connect(); break }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            assertRecoverable(e, rpc)
                            host.stall = null
                        }
                    }
                }
                assertEquals(2, attempts)
                assertEquals("Connected", rpc.status.value)
            } finally { rpc.close() }
        }
    }

    @Test fun liveRequestTimeoutMarksTheSocketLostAndNeverResendsAcceptedPrompts() = runBlocking {
        Host().use { host ->
            host.enqueueSocket(); host.enqueueSocket()
            val rpc = host.client()
            try {
                rpc.connect(); host.stall = "turn/start"
                val failure = runCatching { rpc.call("turn/start", jsonObject("prompt" to "Send once")) }.exceptionOrNull()
                assertRecoverable(failure, rpc)
                assertTrue(failure?.message.orEmpty().contains("check chat history"))
                host.stall = null; rpc.connect(); rpc.call("thread/read")
                assertEquals(1, host.acceptedPrompts.get())
                assertEquals("Connected", rpc.status.value)
            } finally { rpc.close() }
        }
    }

    @Test fun preferenceTimeoutDoesNotSubscribeWithUnwantedCommandOutput() = runBlocking {
        Host().use { host ->
            host.enqueueSocket(); host.stall = "bridge/commandOutput"
            val rpc = host.client()
            try {
                assertRecoverable(runCatching { rpc.connect(loadCommandOutput = false) }.exceptionOrNull(), rpc)
                assertFalse(host.methods.contains("bridge/subscribe"))
            } finally { rpc.close() }
        }
    }

    @Test fun timeoutAlsoFailsOtherPendingRequestsInsteadOfLeavingThemWaiting() = runBlocking {
        Host().use { host ->
            host.enqueueSocket(); val rpc = host.client()
            try {
                rpc.connect(); host.stall = "test/hold"
                val first = async { runCatching { rpc.call("test/hold") }.exceptionOrNull() }
                withTimeout(5000) { while (!host.methods.contains("test/hold")) delay(5) }
                host.stall = "turn/start"
                val second = async { runCatching { rpc.call("turn/start") }.exceptionOrNull() }
                withTimeout(5000) { while (!host.methods.contains("turn/start")) delay(5) }
                assertTrue(first.await() is RpcTimeoutException)
                val failure = second.await()
                assertTrue(failure is IOException)
                assertFalse(failure is RpcTimeoutException)
                assertTrue(failure?.message.orEmpty().contains("check chat history"))
                assertEquals("Connection lost", rpc.status.value)
                assertEquals(1, host.acceptedPrompts.get())
            } finally { rpc.close() }
        }
    }

    @Test fun cancellingAConnectStopsItsSocketAndPreservesCoroutineCancellation() = runBlocking {
        Host().use { host ->
            host.enqueueSocket(); host.stall = "bridge/subscribe"
            val rpc = host.client()
            try {
                val connecting = launch { rpc.connect() }
                withTimeout(5000) { while (!host.methods.contains("bridge/subscribe")) delay(5) }
                connecting.cancelAndJoin()
                assertTrue(connecting.isCancelled)
                assertEquals("Offline", rpc.status.value)
            } finally { rpc.close() }
        }
    }

    @Test fun cancellingOneReadDoesNotDisconnectAHealthySocket() = runBlocking {
        Host().use { host ->
            host.enqueueSocket(); val rpc = host.client()
            try {
                rpc.connect(); host.stall = "thread/read"
                val read = launch { rpc.call("thread/read") }
                withTimeout(5000) { while (!host.methods.contains("thread/read")) delay(5) }
                read.cancelAndJoin()
                assertEquals("Connected", rpc.status.value)
                host.stall = null
                assertEquals("true", rpc.call("test/barrier").text("ok"))
            } finally { rpc.close() }
        }
    }

    @Test fun rejectedUpgradeStillRequiresSignInInsteadOfRetryingAnExpiredSession() = runBlocking {
        Host().use { host ->
            host.server.enqueue(MockResponse().setResponseCode(401))
            val rpc = host.client()
            try {
                assertTrue(runCatching { rpc.connect() }.exceptionOrNull() is SessionExpiredException)
                assertEquals("Session expired", rpc.status.value)
                assertNotNull(host.server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals(1, host.server.requestCount)
            } finally { rpc.close() }
        }
    }
}
