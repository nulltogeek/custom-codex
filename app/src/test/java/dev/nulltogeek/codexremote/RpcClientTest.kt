package dev.nulltogeek.codexremote

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production transport through real loopback WebSocket frames. */
class RpcClientTest {
    @Test fun appUpdateDownloadUsesPrivateSessionAndVerifiesItsBytes() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val bytes = "apk fixture".toByteArray()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val rpc = RpcClient(); rpc.configure("http://127.0.0.1:${server.port}", "s".repeat(43))
            val update = AppUpdate(BuildConfig.APPLICATION_ID, 4, "0.4.0", hash, bytes.size.toLong(), 30, "Update")
            val file = rpc.downloadAppUpdate(update, java.nio.file.Files.createTempDirectory("rpc-update").toFile()) {}
            assertArrayEquals(bytes, file.readBytes())
            val request = server.takeRequest(); assertEquals(update.downloadPath, request.path)
            assertEquals("Bearer " + "s".repeat(43), request.getHeader("Authorization")); rpc.close()
        }
    }
    @Test fun updateDownloadRejectsRedirectsAndExpiredSessions() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { destination ->
            server.start(); destination.start()
            val rpc = RpcClient(); rpc.configure("http://127.0.0.1:${server.port}", "s".repeat(43))
            val update = AppUpdate(BuildConfig.APPLICATION_ID, 4, "0.4.0", "a".repeat(64), 10, 30, "Update")
            val directory = java.nio.file.Files.createTempDirectory("rpc-bad-update").toFile()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/other.apk")))
            assertTrue(runCatching { rpc.downloadAppUpdate(update, directory) {} }.isFailure)
            assertEquals(0, destination.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { rpc.downloadAppUpdate(update, directory) {} }.exceptionOrNull() is SessionExpiredException)
            assertTrue(directory.listFiles().orEmpty().isEmpty()); rpc.close()
        } }
    }
    @Test fun attachmentUploadSendsBytesAndSessionWithoutPuttingSecretsInUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"f","name":"notes file.txt","size":7,"mimeType":"text/plain","input":{"type":"text","text":"attached content"}}"""))
            val rpc = RpcClient(); rpc.configure("http://127.0.0.1:${server.port}", "s".repeat(43))
            val attachment = rpc.upload("notes file.txt", "text/plain", "content".toByteArray())
            val request = server.takeRequest()
            assertEquals("/v1/attachments", request.path); assertEquals("POST", request.method)
            assertEquals("Bearer " + "s".repeat(43), request.getHeader("Authorization"))
            assertEquals("notes%20file.txt", request.getHeader("X-File-Name")); assertEquals("content", request.body.readUtf8())
            assertEquals("text", attachment.input.text("type"))
        }
    }

    @Test fun cancellingUploadStopsWaitingForTheServerAndNeverReturnsAnAttachment() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val rpc = RpcClient(); rpc.configure("http://127.0.0.1:${server.port}", "s".repeat(43))
            var delivered = false
            val upload = launch(Dispatchers.IO) { rpc.upload("cancel.txt", "text/plain", "content".toByteArray()); delivered = true }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { upload.cancelAndJoin() }
            assertFalse(delivered); assertTrue(upload.isCancelled)
            rpc.close()
        }
    }

    @Test fun usernamePasswordLoginUsesJsonAndSessionForLaterRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = "s".repeat(43)
            server.enqueue(MockResponse().setBody("""{"token":"$session","username":"owner","expiresAt":${System.currentTimeMillis() + 60000}}"""))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            val rpc = RpcClient()
            val result = rpc.login("http://127.0.0.1:${server.port}", "owner", "password with spaces ")
            assertEquals("owner", result.username)
            val login = server.takeRequest()
            assertEquals("POST", login.method); assertEquals("/v1/login", login.path)
            assertNull(login.getHeader("Authorization"))
            val input = WireJson.parseToJsonElement(login.body.readUtf8()).jsonObject
            assertEquals("password with spaces ", input.text("password"))
            rpc.get("/v1/host")
            assertEquals("Bearer $session", server.takeRequest().getHeader("Authorization"))
            rpc.logout()
            val logout = server.takeRequest()
            assertEquals("POST", logout.method); assertEquals("/v1/logout", logout.path)
            assertEquals("Bearer $session", logout.getHeader("Authorization"))
        }
    }

    @Test fun passwordChangePreservesSpacesAuthenticatesAndRejectsRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val rpc = RpcClient(); val session = "s".repeat(43)
            rpc.configure("http://127.0.0.1:${server.port}", session)
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            rpc.changePassword("current password  ", " new password  ")
            val request = server.takeRequest()
            assertEquals("/v1/password", request.path); assertEquals("POST", request.method)
            assertEquals("Bearer $session", request.getHeader("Authorization"))
            val body = WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("current password  ", body.text("currentPassword")); assertEquals(" new password  ", body.text("newPassword"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/redirected"))
            assertTrue(runCatching { rpc.changePassword("old", "new-long-password") }.exceptionOrNull() is IOException)
            assertEquals("/v1/password", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun rejectedCurrentPasswordDoesNotExpireSessionAndResponseBodiesStayPrivate() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val rpc = RpcClient(); rpc.configure("http://127.0.0.1:${server.port}", "s".repeat(43))
            server.enqueue(MockResponse().setResponseCode(403).setBody("private account data"))
            val failure = runCatching { rpc.changePassword("wrong", "new-long-password") }.exceptionOrNull()
            assertEquals("Current password was rejected.", failure?.message)
            assertFalse(failure is SessionExpiredException)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { rpc.changePassword("old", "new-long-password") }.exceptionOrNull() is SessionExpiredException)
        }
    }
    @Test fun rejectedLoginAndExpiredSessionHaveActionableErrorsWithoutResponseBodies() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val endpoint = "http://127.0.0.1:${server.port}"
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive provider detail"))
            server.enqueue(MockResponse().setResponseCode(401))
            val rpc = RpcClient()
            val rejected = runCatching { rpc.login(endpoint, "owner", "wrong") }.exceptionOrNull()
            assertEquals("Username or password was rejected", rejected?.message)
            rpc.configure(endpoint, "s".repeat(43))
            assertTrue(runCatching { rpc.get("/v1/host") }.exceptionOrNull() is SessionExpiredException)
        }
    }

    @Test fun releaseCredentialsCannotBeSentToAnHttpOrCredentialBearingUrl() = runBlocking {
        val rpc = RpcClient()
        assertTrue(runCatching { rpc.login("http://example.com", "owner", "password") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { rpc.login("https://owner:password@example.com", "owner", "password") }.exceptionOrNull() is IllegalArgumentException)
    }

    private class Host : Closeable {
        val server = MockWebServer()
        val subscriptions = CopyOnWriteArrayList<JsonObject>()
        @Volatile lateinit var socket: WebSocket
        @Volatile var epoch = "runtime-one"
        @Volatile var sequence = 0L
        var handle: (WebSocket, JsonObject) -> Unit = { ws, request -> reply(ws, request) }
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = WireJson.parseToJsonElement(text).jsonObject
                        when (request.text("method")) {
                            "initialize" -> reply(webSocket, request)
                            "initialized" -> Unit
                            "bridge/subscribe" -> {
                                subscriptions.add(request.obj("params"))
                                reply(webSocket, request, buildJsonObject {
                                    put("epoch", epoch); put("sequence", sequence); put("ready", true)
                                    put("pendingRequests", buildJsonArray {})
                                })
                            }
                            else -> handle(webSocket, request)
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                })
            }
            server.start()
        }
        fun client() = RpcClient().apply { configure("http://127.0.0.1:${server.port}", "disposable-test-credential".repeat(2)) }
        fun reply(ws: WebSocket, request: JsonObject, result: JsonObject = buildJsonObject {}) {
            ws.send(buildJsonObject { put("id", request.getValue("id")); put("result", result); put("bridgeSequence", sequence) }.toString())
        }
        fun event(at: Long, text: String) {
            socket.send(buildJsonObject {
                put("type", "event"); put("epoch", epoch); put("sequence", at)
                put("message", buildJsonObject { put("method", "item/agentMessage/delta"); put("params", buildJsonObject { put("itemId", "a"); put("delta", text) }) })
            }.toString())
        }
        override fun close() { server.shutdown() }
    }

    @Test fun concurrentCallsReceiveTheirOwnOutOfOrderResponses() = runBlocking {
        Host().use { host ->
            val requests = LinkedBlockingQueue<Pair<WebSocket, JsonObject>>()
            host.handle = { ws, request -> requests.add(ws to request) }
            val rpc = host.client()
            try {
                rpc.connect()
                val first = async(Dispatchers.IO) { rpc.call("test/echo", jsonObject("value" to "first")) }
                val second = async(Dispatchers.IO) { rpc.call("test/echo", jsonObject("value" to "second")) }
                val a = requireNotNull(requests.poll(10, TimeUnit.SECONDS))
                val b = requireNotNull(requests.poll(10, TimeUnit.SECONDS))
                host.reply(b.first, b.second, b.second.obj("params"))
                host.reply(a.first, a.second, a.second.obj("params"))
                assertEquals("first", first.await().text("value"))
                assertEquals("second", second.await().text("value"))
            } finally { rpc.close() }
        }
    }

    @Test fun duplicatedAndOlderNotificationsDoNotRepeatOutput() = runBlocking {
        Host().use { host ->
            val rpc = host.client()
            try {
                rpc.connect()
                host.event(2, "accepted"); host.event(2, "duplicate"); host.event(1, "older")
                // The response is a wire-order barrier behind all three notifications.
                rpc.call("test/barrier")
                assertEquals("accepted", withTimeout(5000) { rpc.events.receive() }.obj("params").text("delta"))
                assertTrue(rpc.events.tryReceive().isFailure)
                assertEquals(2L, rpc.sequence)
            } finally { rpc.close() }
        }
    }

    @Test fun reconnectResumesCursorAndBackendRestartAcceptsNewSequence() = runBlocking {
        Host().use { host ->
            val rpc = host.client()
            try {
                rpc.connect(); host.event(7, "before disconnect")
                withTimeout(5000) { rpc.events.receive() }
                host.sequence = 7
                rpc.close(); rpc.connect()
                assertEquals("runtime-one", host.subscriptions.last().text("epoch"))
                assertEquals("7", host.subscriptions.last().text("sequence"))
                host.event(7, "replayed duplicate"); rpc.call("test/barrier")
                assertTrue(rpc.events.tryReceive().isFailure)
                rpc.close(); host.epoch = "runtime-two"; host.sequence = 0
                rpc.connect(); host.event(1, "after restart")
                val event = withTimeout(5000) { rpc.events.receive() }
                assertEquals("after restart", event.obj("params").text("delta"))
                assertEquals("runtime-two", rpc.epoch); assertEquals(1L, rpc.sequence)
            } finally { rpc.close() }
        }
    }

    @Test fun disconnectAfterAcceptanceNeverResendsPromptOnReconnect() = runBlocking {
        Host().use { host ->
            val accepted = AtomicInteger()
            host.handle = { ws, request ->
                if (request.text("method") == "turn/start") { accepted.incrementAndGet(); ws.cancel() }
                else host.reply(ws, request)
            }
            val rpc = host.client()
            try {
                rpc.connect()
                val failure = runCatching { withTimeout(10000) { rpc.call("turn/start", jsonObject("prompt" to "execute once")) } }.exceptionOrNull()
                assertTrue("Expected uncertain delivery after connection loss", failure is IOException)
                assertTrue(failure!!.message.orEmpty().contains("check chat history"))
                rpc.connect(); rpc.call("test/barrier")
                assertEquals(1, accepted.get())
            } finally { rpc.close() }
        }
    }
}
