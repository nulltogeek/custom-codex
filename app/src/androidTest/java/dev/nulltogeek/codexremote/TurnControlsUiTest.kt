package dev.nulltogeek.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TurnControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private class App(base: Context) : Application() {
        private val name = "turn-controls-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, name).apply { mkdirs() }
        init { attachBaseContext(object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(this@App.name, mode)
        }) }
        fun cleanup() { getSharedPreferences("codex_remote", MODE_PRIVATE).edit().clear().commit(); directory.deleteRecursively() }
    }
    private class Host : java.io.Closeable {
        val server = MockWebServer(); val starts = AtomicInteger(); val stops = AtomicInteger()
        val blockedHistory = CountDownLatch(1); val blockedQueue = CountDownLatch(1)
        @Volatile var active = false
        @Volatile var stopReply: (() -> Unit)? = null
        @Volatile var socket: WebSocket? = null
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/rpc") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val request = WireJson.parseToJsonElement(text).jsonObject
                            val id = request["id"] ?: return
                            val method = request.text("method"); val p = request.obj("params")
                            fun reply(result: JsonObject) { webSocket.send(buildJsonObject { put("id", id); put("result", result); put("bridgeSequence", 0) }.toString()) }
                            val result = when(method) {
                                "bridge/subscribe" -> buildJsonObject { put("epoch", "fixture"); put("sequence", 0); put("pendingRequests", JsonArray(emptyList())) }
                                "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("chat"); add("other") }) }
                                "thread/read" -> {
                                    if (p.text("threadId") == "other") { blockedQueue.countDown(); return }
                                    if (p["includeTurns"]?.jsonPrimitive?.boolean == true) { blockedHistory.countDown(); return }
                                    buildJsonObject { put("thread", buildJsonObject { put("id", "chat"); put("status", jsonObject("type" to if (active) "active" else "idle")); put("cwd", "/fixture") }) }
                                }
                                "turn/start" -> { starts.incrementAndGet(); active = true; buildJsonObject { put("turn", jsonObject("id" to "live", "status" to "inProgress")) } }
                                "turn/interrupt" -> { stops.incrementAndGet(); stopReply = { reply(buildJsonObject {}); active = false }; return }
                                else -> buildJsonObject { put("data", JsonArray(emptyList())) }
                            }
                            reply(result)
                            if (method == "turn/start") webSocket.send("""{"type":"event","epoch":"fixture","sequence":1,"message":{"method":"turn/started","params":{"threadId":"chat","turn":{"id":"live","status":"inProgress"}}}}""")
                        }
                    })
                    val body = when (request.path?.substringBefore('?')) {
                        "/v1/login" -> """{"token":"${"a".repeat(43)}","username":"owner","expiresAt":${System.currentTimeMillis()+600000}}"""
                        "/v1/host" -> """{"user":{"id":"owner","username":"owner","role":"admin"}}"""
                        "/v1/snapshot" -> """{"epoch":"fixture","sequence":0,"pendingRequests":[]}"""
                        "/v1/projects" -> """{"projects":[],"assignments":{}}"""
                        else -> """{"data":[]}"""
                    }
                    return MockResponse().setBody(body)
                }
            }; server.start()
        }
        override fun close() { socket?.close(1000, "Fixture complete"); server.close() }
    }
    @Test fun sendBypassesBlockedHistoryAndQueueAndStopRespondsDuringOtherLoading() {
        Host().use { host ->
            val app = App(compose.activity); val models = ViewModelStore()
            try {
                val endpoint = "http://127.0.0.1:${host.server.port}"
                LocalStore(app).queue(endpoint, "owner", QueueSnapshot(listOf(QueuedMessage("queued", "other", "Other task"))))
                val vm = RemoteViewModel(app); models.put("vm", vm)
                vm.signIn(endpoint, "owner", "fixture password"); compose.waitUntil(10000) { vm.state.value.signedIn }
                vm.onForeground(); compose.waitUntil(10000) { vm.state.value.connection == "Connected" }
                vm.resumeQueue("other"); assertTrue(host.blockedQueue.await(5, TimeUnit.SECONDS))
                vm.open(Chat("chat", "Chat", "", "/fixture", "idle"))
                assertTrue(host.blockedHistory.await(5, TimeUnit.SECONDS))
                compose.activityRule.scenario.onActivity { it.setContent {
                    val state by vm.state.collectAsState()
                    RemoteTheme("light", motion = false) { Surface { ChatComposer(state.copy(loading = true), vm) {} } }
                } }
                vm.draft("Send without downloading old history")
                compose.onNodeWithContentDescription("Send").performClick()
                compose.waitUntil(2000) { host.starts.get() == 1 && !vm.state.value.sending }
                compose.waitUntil(2000) { vm.state.value.activeTurnId == "live" }
                compose.onNodeWithTag("stop-work").assertIsEnabled().performClick()
                compose.waitUntil(2000) { host.stops.get() == 1 }
                compose.onNodeWithContentDescription("Stopping").assertIsDisplayed()
                compose.onNodeWithTag("stop-work").assertIsNotEnabled()
                vm.interrupt(); assertEquals(1, host.stops.get())
                host.stopReply?.invoke()
                compose.waitUntil(2000) { vm.state.value.stoppingChats.isEmpty() }
                assertTrue("Stop leaves follow-ups paused", "chat" in vm.state.value.pausedQueues)
                assertEquals(1, host.starts.get())
            } finally { compose.runOnIdle { models.clear() }; app.cleanup() }
        }
    }
}
