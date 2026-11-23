package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TurnControlsTest {
    private val message = QueuedMessage("message", "chat", "Hello", model = "chosen", effort = "high")
    private fun metadata(status: String = "idle", archived: Boolean = false) = buildJsonObject {
        put("thread", buildJsonObject { put("id", "chat"); put("status", jsonObject("type" to status)); put("archived", archived) })
    }
    @Test fun manualSendAvoidsHistoryDownloadsAndHalvesWarmChatRoundTrips() = runTest {
        val methods = mutableListOf<String>()
        val call: suspend (String, JsonObject) -> JsonObject = { method, params ->
            methods += method; delay(100)
            when (method) {
                "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("chat") }) }
                "thread/read" -> { if (params["includeTurns"]?.jsonPrimitive?.boolean == true) delay(3000); metadata() }
                else -> { assertEquals("turn/start", method); metadata() }
            }
        }
        val start = testScheduler.currentTime
        submitQueuedMessage(message, call, allowFailedTurn = true) {}
        val oldDuration = testScheduler.currentTime - start
        methods.clear(); val now = testScheduler.currentTime
        submitImmediateMessage(message, call) {}
        assertEquals(listOf("thread/read", "turn/start"), methods)
        assertEquals(3400L, oldDuration); assertEquals(200L, testScheduler.currentTime - now)
    }
    @Test fun unloadedChatResumesWithoutHistoryAndHonorsOwnershipRejection() = runTest {
        for (reject in listOf(false, true)) {
            val methods = mutableListOf<String>(); var attempted = false
            val result = runCatching { submitImmediateMessage(message, { method, params ->
                methods += method
                when (method) {
                    "thread/read" -> metadata("notLoaded")
                    "thread/resume" -> {
                        assertTrue(params["excludeTurns"]!!.jsonPrimitive.boolean)
                        if (reject) throw RpcException(-32600, "already has an active writer")
                        metadata()
                    }
                    else -> { assertEquals("turn/start", method); assertEquals("chosen", params.text("model")); assertEquals("high", params.text("effort")); buildJsonObject {} }
                }
            }) { attempted = true } }
            assertEquals(!reject, result.isSuccess); assertEquals(!reject, attempted)
            assertEquals(if (reject) listOf("thread/read", "thread/resume") else listOf("thread/read", "thread/resume", "turn/start"), methods)
        }
    }
    @Test fun activeAndArchivedChatsCannotReceiveImmediateInput() = runTest {
        for ((status, archived) in listOf("active" to false, "idle" to true)) {
            var mutations = 0
            val result = runCatching { submitImmediateMessage(message, { method, _ -> assertEquals("thread/read", method); metadata(status, archived) }) { mutations++ } }
            assertTrue(result.isFailure); assertEquals(0, mutations)
        }
    }
    @Test fun ambiguousSendIsAttemptedExactlyOnce() = runTest {
        var attempted = false; var mutations = 0
        val error = runCatching { submitImmediateMessage(message, { method, _ ->
            if (method == "thread/read") metadata() else { mutations++; throw IOException("Lost acknowledgement") }
        }) { attempted = true } }.exceptionOrNull()!!
        assertTrue(uncertainDelivery(attempted, false, error as Exception)); assertEquals(1, mutations)
    }
    @Test fun stopUsesOneRequestWhenTurnIdIsKnownEvenDuringLoading() = runTest {
        val state = ScreenState(selectedChat = Chat("chat", "Chat", "", "", "active"), activeTurnId = "turn", loading = true)
        val methods = mutableListOf<String>()
        interruptCurrentTurn(state) { method, params ->
            methods += method; delay(100); assertEquals("turn", params.text("turnId")); buildJsonObject {}
        }
        assertEquals(listOf("turn/interrupt"), methods); assertEquals(100L, testScheduler.currentTime)
    }
    @Test fun stopWithoutLiveIdFetchesOnlyLatestTurnMetadata() = runTest {
        val state = ScreenState(selectedChat = Chat("chat", "Chat", "", "", "active"))
        val methods = mutableListOf<String>()
        interruptCurrentTurn(state) { method, params ->
            methods += method
            if (method == "thread/turns/list") {
                assertEquals(1, params["limit"]!!.jsonPrimitive.int); assertEquals("notLoaded", params.text("itemsView"))
                buildJsonObject { put("data", buildJsonArray { add(jsonObject("id" to "live", "status" to "inProgress")) }) }
            } else { assertEquals("live", params.text("turnId")); buildJsonObject {} }
        }
        assertEquals(listOf("thread/turns/list", "turn/interrupt"), methods)
    }
    @Test fun stopSupportsLegacyHistoryButDoesNotRetryAnAmbiguousInterrupt() = runTest {
        var interrupts = 0
        val state = ScreenState(selectedChat = Chat("chat", "Chat", "", "", "active"))
        val error = runCatching { interruptCurrentTurn(state) { method, _ -> when (method) {
            "thread/turns/list" -> throw RpcException(-32601, "unsupported")
            "thread/read" -> buildJsonObject { put("thread", buildJsonObject { put("turns", buildJsonArray { add(jsonObject("id" to "legacy", "status" to "inProgress")) }) }) }
            else -> { interrupts++; throw IOException("Lost connection") }
        } } }.exceptionOrNull()
        assertTrue(error is IOException); assertEquals(1, interrupts)
    }
}
