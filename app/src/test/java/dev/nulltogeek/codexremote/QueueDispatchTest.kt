package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QueueDispatchTest {
    private val first = QueuedMessage("first", "original", "Queued in first project", model = "saved-model")
    private fun queue() = MessageQueue { _, _, _ -> }.apply { restore("https://fixture.invalid", "owner", QueueSnapshot()) }
    private fun thread(id: String, status: String = "idle", last: String = "completed") = buildJsonObject {
        put("thread", buildJsonObject { put("id", id); put("status", jsonObject("type" to status)); put("turns", buildJsonArray {
            add(jsonObject("id" to "turn-$id", "status" to last))
        }) })
    }
    private fun completion(id: String = "original", at: Long = 10, epoch: String = "current", status: String = "interrupted") = buildJsonObject {
        put("method", "turn/completed"); put("_bridgeEpoch", epoch); put("_bridgeSequence", at)
        put("params", buildJsonObject { put("threadId", id); put("turn", jsonObject("id" to "turn-$id", "status" to status)) })
    }

    @Test fun switchingProjectsDuringChecksStillSendsToOriginalChatWithoutBlockingTheDisplayedChat() = runBlocking {
        val queue = queue(); queue.add(first)
        val screen = MutableStateFlow(ScreenState(signedIn = true, connection = "Connected", projectFilter = "first-project",
            selectedChat = Chat("original", "Original", "", "")))
        val starts = mutableListOf<String>()
        dispatchMessageQueue(queue, { method, params -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("original") }) }
            "thread/read" -> {
                // Navigation and live response updates can arrive while checks are pending.
                screen.value = screen.value.copy(projectFilter = "other-project", selectedChat = Chat("other", "Other", "", ""),
                    timeline = listOf(TimelineItem("reply", "agentMessage", "another project's live reply")))
                thread(params.text("threadId"))
            }
            else -> {
                assertEquals("turn/start", method); starts += params.text("threadId")
                assertEquals("saved-model", params.text("model"))
                assertEquals("sending", queue.state.value.messages.single().delivery)
                assertEquals("other", screen.value.selectedChat?.id); assertFalse(screen.value.sending)
                buildJsonObject {}
            }
        } }, canDispatch = { screen.value.connection == "Connected" && !screen.value.sending }, sameAccount = { true })
        assertEquals(listOf("original"), starts); assertTrue(queue.state.value.messages.isEmpty())
        assertEquals("other-project", screen.value.projectFilter); assertFalse(screen.value.sending)
    }

    @Test fun busyOrPausedProjectsCannotPreventAnotherProjectsQueueFromSending() = runBlocking {
        val queue = queue(); queue.add(first); queue.add(first.copy(id = "paused", chatId = "paused")); queue.pause("paused")
        queue.add(first.copy(id = "other", chatId = "other"))
        val starts = mutableListOf<String>()
        var originalRunning = true
        val call: suspend (String, JsonObject) -> JsonObject = { method, params -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("original"); add("paused"); add("other") }) }
            "thread/read" -> if (params.text("threadId") == "original" && originalRunning) thread("original", "active", "inProgress") else thread(params.text("threadId"))
            else -> { assertEquals("turn/start", method); starts += params.text("threadId"); buildJsonObject {} }
        } }
        dispatchMessageQueue(queue, call, { true }, { true })
        assertEquals(listOf("other"), starts)
        assertEquals(listOf("first", "paused"), queue.state.value.messages.map { it.id })
        originalRunning = false
        dispatchMessageQueue(queue, call, { true }, { true })
        assertEquals(listOf("other", "original"), starts)
        assertEquals(listOf("paused"), queue.state.value.messages.map { it.id }); assertTrue("paused" in queue.state.value.pausedChats)
    }

    @Test fun accountOrConnectivityChangesBeforeClaimNeverSend() = runBlocking {
        for (connected in listOf(true, false)) {
            val queue = queue(); queue.add(first); var allowed = true
            var starts = 0
            dispatchMessageQueue(queue, { method, _ -> when (method) {
                "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("original") }) }
                "thread/read" -> { allowed = false; thread("original") }
                else -> { starts++; buildJsonObject {} }
            } }, canDispatch = { allowed }, sameAccount = { connected })
            assertEquals(0, starts); assertEquals(listOf(first), queue.state.value.messages)
        }
    }

    @Test fun unconfirmedDeliveryPausesOnlyThatChatAndDoesNotRetryOnNextDispatch() = runBlocking {
        val queue = queue(); queue.add(first); queue.add(first.copy(id = "next", chatId = "other"))
        val starts = mutableListOf<String>()
        val call: suspend (String, JsonObject) -> JsonObject = { method, params -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("original"); add("other") }) }
            "thread/read" -> thread(params.text("threadId"))
            else -> { starts += params.text("threadId"); if (params.text("threadId") == "original") throw IOException("Lost acknowledgement") else buildJsonObject {} }
        } }
        dispatchMessageQueue(queue, call, { true }, { true })
        dispatchMessageQueue(queue, call, { true }, { true })
        assertEquals(listOf("original", "other"), starts)
        assertEquals("uncertain", queue.state.value.messages.single().delivery)
        assertEquals(setOf("original"), queue.state.value.pausedChats)
    }

    @Test fun delayedFailureCannotPauseANewOrExplicitlyResumedQueue() {
        val queue = queue()
        // Old completion notifications must not create a pause for a future queue.
        queue.pauseForCompletion(completion(), "current")
        assertTrue(queue.state.value.pausedChats.isEmpty())
        queue.add(first, QueueEventCursor("current", 10))
        queue.pauseForCompletion(completion(at = 10), "current")
        assertTrue(queue.state.value.pausedChats.isEmpty())
        queue.pauseForCompletion(completion(at = 11), "current")
        assertTrue("original" in queue.state.value.pausedChats)
        assertTrue(queue.state.value.messages.single().error.contains("was stopped"))
        queue.resume("original", QueueEventCursor("current", 12))
        queue.pauseForCompletion(completion(at = 11), "current")
        queue.pauseForCompletion(completion(at = 12), "current")
        assertFalse("original" in queue.state.value.pausedChats)
        queue.pauseForCompletion(completion(at = 13, status = "failed"), "current")
        assertTrue("original" in queue.state.value.pausedChats)
        assertTrue(queue.state.value.messages.single().error.contains("failed"))
    }

    @Test fun disconnectedStatusChecksWaitWithoutPausingThenSendAfterReconnect() = runBlocking {
        val queue = queue(); queue.add(first)
        var disconnected = true; var starts = 0; var rejections = 0
        val call: suspend (String, JsonObject) -> JsonObject = { method, params -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("original") }) }
            "thread/read" -> if (disconnected) throw IOException("Connection lost during status read") else thread("original")
            else -> { assertEquals("original", params.text("threadId")); starts++; buildJsonObject {} }
        } }
        dispatchMessageQueue(queue, call, { true }, { true }, onRejected = { _, _, _ -> rejections++ })
        assertEquals(listOf(first), queue.state.value.messages); assertTrue(queue.state.value.pausedChats.isEmpty())
        assertEquals(0, starts); assertEquals(0, rejections)
        disconnected = false
        dispatchMessageQueue(queue, call, { true }, { true })
        assertEquals(1, starts); assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test fun queueStorageErrorsCannotBeMistakenForTemporaryConnectionLoss() = runBlocking {
        var failSaving = false
        val queue = MessageQueue { _, _, _ -> if (failSaving) throw IOException("Disk full") }
        queue.restore("https://fixture.invalid", "owner", QueueSnapshot()); queue.add(first)
        failSaving = true; var starts = 0
        val failure = runCatching { dispatchMessageQueue(queue, { method, _ -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("original") }) }
            "thread/read" -> thread("original")
            else -> { starts++; buildJsonObject {} }
        } }, { true }, { true }) }.exceptionOrNull()
        assertTrue(failure is IOException); assertEquals("Disk full", failure?.message)
        assertEquals(0, starts); assertEquals(listOf(first), queue.state.value.messages)
    }

    @Test fun staleEpochAndOtherProjectsFailuresLeaveThisQueueAlone() {
        val queue = queue(); queue.add(first, QueueEventCursor("current", 2))
        queue.pauseForCompletion(completion(epoch = "old", at = 100), "current")
        queue.pauseForCompletion(completion(id = "other", at = 100), "current")
        queue.pauseForCompletion(completion(at = 3, status = "completed"), "current")
        assertTrue(queue.state.value.pausedChats.isEmpty()); assertEquals(listOf(first), queue.state.value.messages)
    }
}
