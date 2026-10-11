package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadRecoveryTest {
    private val input = buildJsonArray { add(jsonObject("type" to "text", "text" to "Unsent draft")) }
    private fun state(status: String = "idle") = ScreenState(selectedChat = Chat("desktop", "Chat", "", "/workspace", status),
        draft = "Unsent draft", attachments = listOf(Attachment("image", "photo.png", 42, "image/png",
            jsonObject("type" to "localImage", "path" to "/uploaded/photo.png"), "/cache/photo.png")),
        chatMode = "chat", accessMode = "ask", selectedModel = "chosen-model", selectedEffort = "high")

    @Test fun lockedIdleAndActiveChatsNeverStartOrSteerOrSilentlyFork() = runBlocking {
        for (status in listOf("idle", "active")) {
            val original = state(status); val calls = mutableListOf<String>(); var attempted = false
            val failure = runCatching {
                submitThreadInput(original, input, { method, _ ->
                    calls += method
                    if (method == "thread/loaded/list") buildJsonObject { put("data", buildJsonArray {}) }
                    else throw RpcException(-32600, "thread desktop already has an active writer")
                }) { attempted = true }
            }.exceptionOrNull()!!
            assertTrue(writerConflict(failure as Exception))
            assertEquals(listOf("thread/loaded/list", "thread/resume"), calls)
            assertFalse(attempted); assertFalse(uncertainDelivery(attempted, false, failure))
            assertEquals("Unsent draft", original.draft); assertEquals("/cache/photo.png", original.attachments.single().previewPath)
        }
    }

    @Test fun loadedActiveChatSteersExistingTurnOnceWithoutResuming() = runBlocking {
        val calls = mutableListOf<String>(); var mutations = 0
        submitThreadInput(state("active").copy(activeTurnId = "running-turn"), input, { method, params ->
            calls += method
            if (method == "thread/loaded/list") buildJsonObject { put("data", buildJsonArray { add("desktop") }) }
            else {
                assertEquals("turn/steer", method); assertEquals("running-turn", params.text("expectedTurnId"))
                assertEquals(input, params.array("input")); buildJsonObject {}
            }
        }) { mutations++ }
        assertEquals(listOf("thread/loaded/list", "turn/steer"), calls); assertEquals(1, mutations)
    }

    @Test fun releasedWriterResumesAndStartsWithCurrentOptionsOnce() = runBlocking {
        val calls = mutableListOf<String>(); var mutations = 0
        submitThreadInput(state("active"), input, { method, params ->
            calls += method
            when (method) {
                "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray {}) }
                "thread/resume" -> {
                    assertEquals("chat", params.text("mobileMode")); assertEquals("ask", params.text("accessMode"))
                    buildJsonObject { put("thread", buildJsonObject { put("status", jsonObject("type" to "idle")) }) }
                }
                else -> {
                    assertEquals("turn/start", method); assertEquals("chosen-model", params.text("model"))
                    assertEquals("high", params.text("effort")); assertEquals(input, params.array("input")); buildJsonObject {}
                }
            }
        }) { mutations++ }
        assertEquals(listOf("thread/loaded/list", "thread/resume", "turn/start"), calls); assertEquals(1, mutations)
    }

    @Test fun lostTurnAcknowledgementLocksDeliveryAndNeverRetries() = runBlocking {
        var mutations = 0; var attempted = false
        val failure = runCatching {
            submitThreadInput(state(), input, { method, _ ->
                if (method == "thread/loaded/list") buildJsonObject { put("data", buildJsonArray { add("desktop") }) }
                else { mutations++; throw IOException("Disconnected") }
            }) { attempted = true }
        }.exceptionOrNull()!!
        assertEquals(1, mutations); assertTrue(uncertainDelivery(attempted, false, failure as Exception))
        assertFalse(writerConflict(failure))
    }

    @Test fun writerConflictRequiresAnExplicitRpcRejection() {
        assertFalse(writerConflict(IOException("already has an active writer")))
        assertFalse(writerConflict(RpcException(-32602, "Invalid input")))
        assertTrue(writerConflict(RpcException(-32600, "Thread X ALREADY HAS AN ACTIVE WRITER")))
    }

    @Test fun recoveryCopiesHistoryWithSelectedPolicyButNoPromptOrAutomaticGoalWork() {
        val original = state().copy(writerConflict = true)
        val params = recoveryParams(original)
        assertEquals("desktop", params.text("threadId")); assertEquals("chosen-model", params.text("model"))
        assertEquals("chat", params.text("mobileMode")); assertEquals("ask", params.text("accessMode"))
        assertTrue(params["deferGoalContinuation"]!!.jsonPrimitive.boolean)
        assertTrue(params["excludeTurns"]!!.jsonPrimitive.boolean)
        assertFalse(params.containsKey("input")); assertFalse(params.containsKey("historyMode"))
        assertEquals("Unsent draft", original.draft); assertEquals(1, original.attachments.size)
    }
}
