package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MessageQueueTest {
    private val first = QueuedMessage("first", "chat", "First message", chatMode = "chat", accessMode = "ask", model = "model", effort = "high")
    private fun queue(save: (String, String, QueueSnapshot) -> Unit = { _, _, _ -> }) = MessageQueue(save).apply { restore("https://fixture.invalid", "owner", QueueSnapshot()) }
    private fun thread(status: String = "idle", turnStatus: String = "completed", archived: Boolean = false) = buildJsonObject {
        put("thread", buildJsonObject {
            put("id", "chat"); put("status", jsonObject("type" to status)); put("archived", archived)
            put("turns", buildJsonArray { add(jsonObject("id" to "turn", "status" to turnStatus)) })
        })
    }

    @Test fun editsReorderAndRemoveOnlyWithinTheirChatWithoutLosingOtherChats() {
        val queue = queue()
        queue.add(first); queue.add(first.copy(id = "other", chatId = "other")); queue.add(first.copy(id = "last", text = "Last"))
        queue.move("last", -1)
        assertEquals(listOf("last", "other", "first"), queue.state.value.messages.map { it.id })
        assertNull(queue.claim(first))
        queue.edit("last", "Edited", emptyList())
        assertEquals("Edited", queue.state.value.messages.first().text)
        queue.remove("last")
        assertEquals(listOf("other", "first"), queue.state.value.messages.map { it.id })
        assertNotNull(queue.claim(first))
    }
    @Test fun claimingLocksEditsRemovalAndReorderingAndSavesBeforeMutation() {
        val saved = mutableListOf<QueueSnapshot>()
        val queue = queue { _, _, snapshot -> saved += snapshot }
        queue.add(first); queue.add(first.copy(id = "last"))
        val ticket = requireNotNull(queue.claim(first))
        assertEquals("sending", saved.last().messages.first().delivery)
        queue.edit("first", "Changed while sending", emptyList()); queue.remove("first"); queue.move("last", -1)
        assertEquals("First message", queue.state.value.messages.first().text)
        assertEquals("first", queue.state.value.messages.first().id)
        queue.complete(ticket)
        assertEquals(listOf("last"), queue.state.value.messages.map { it.id })
    }
    @Test fun staleEditSnapshotCannotBeClaimedAfterReadinessChecks() {
        val queue = queue(); queue.add(first)
        queue.edit(first.id, "New draft", emptyList())
        assertNull(queue.claim(first))
        assertEquals("queued", queue.state.value.messages.single().delivery)
    }
    @Test fun uncertainDeliveryAndRelaunchRequireExplicitReviewAndResume() {
        val queue = queue(); queue.add(first)
        val savedSending = queue.state.value.copy(messages = listOf(first.copy(delivery = "sending")))
        queue.restore("https://fixture.invalid", "owner", savedSending)
        assertEquals("uncertain", queue.state.value.messages.single().delivery)
        queue.resume("chat")
        assertTrue("chat" in queue.state.value.pausedChats)
        assertNull(queue.claim(queue.state.value.messages.single()))
        queue.retry("first")
        assertTrue("chat" in queue.state.value.pausedChats)
        queue.resume("chat")
        assertNotNull(queue.claim(queue.state.value.messages.single()))
    }
    @Test fun failedSendRetainsItsMessageAndBlocksLaterMessagesUntilResume() {
        val queue = queue(); queue.add(first); queue.add(first.copy(id = "next"))
        val ticket = requireNotNull(queue.claim(first))
        queue.fail(ticket, "RPC rejected", false)
        assertEquals("RPC rejected", queue.state.value.messages.first().error)
        assertNull(queue.claim(queue.state.value.messages.first()))
        queue.resume("chat")
        assertNotNull(queue.claim(queue.state.value.messages.first()))
    }
    @Test fun completionFromOldAccountCannotDeleteMessagesInNewAccount() {
        val scopes = mutableListOf<Pair<String, String>>()
        val queue = queue { endpoint, username, _ -> scopes += endpoint to username }
        queue.add(first); val ticket = requireNotNull(queue.claim(first))
        queue.restore("https://another.invalid", "other", QueueSnapshot(listOf(first)))
        queue.complete(ticket)
        assertEquals(listOf(first), queue.state.value.messages)
        queue.resume("chat")
        assertEquals("https://another.invalid" to "other", scopes.last())
    }
    @Test fun attachmentPayloadAndOptionsSurvivePersistenceAndEditing() {
        val file = Attachment("image", "photo.jpg", 100, "image/jpeg", jsonObject("type" to "localImage", "path" to "/uploads/photo.jpg"), "/cache/photo.jpg")
        val snapshot = QueueSnapshot(listOf(first.copy(attachments = listOf(file))), setOf("chat"))
        assertEquals(snapshot, parseQueue(WireJson.parseToJsonElement(queueJson(snapshot).toString()).jsonObject))
        val queue = queue(); queue.add(first.copy(attachments = listOf(file)))
        queue.edit("first", "Edited text", listOf(file))
        assertEquals(file, queue.state.value.messages.single().attachments.single())
        queue.edit("first", "Edited text", emptyList())
        assertTrue(queue.state.value.messages.single().attachments.isEmpty())
    }
    @Test fun runningTurnNeverReceivesQueuedInputOrSteering() = runBlocking {
        for ((status, turnStatus) in listOf("active" to "inProgress", "idle" to "inProgress")) {
            val calls = mutableListOf<String>(); var attempted = false
            val failure = runCatching { submitQueuedMessage(first, { method, _ ->
                calls += method
                if (method == "thread/loaded/list") buildJsonObject { put("data", buildJsonArray { add("chat") }) }
                else thread(status, turnStatus)
            }) { attempted = true } }.exceptionOrNull()
            assertTrue(failure is QueueWaitingException); assertFalse(attempted)
            assertEquals(listOf("thread/loaded/list", "thread/read"), calls)
        }
    }
    @Test fun queueStartsOneNewTurnWithSavedTextAttachmentsAndOptionsAfterCompletion() = runBlocking {
        val file = Attachment("f", "notes.txt", 20, "text/plain", jsonObject("type" to "text", "text" to "Attached notes"))
        val message = first.copy(attachments = listOf(file))
        var saved = QueueSnapshot()
        val queue = queue { _, _, snapshot -> saved = snapshot }; queue.add(message)
        var mutations = 0
        var ticket: QueueTicket? = null
        submitQueuedMessage(message, { method, params -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("chat") }) }
            "thread/read" -> thread()
            else -> {
                assertEquals("turn/start", method); mutations++
                assertEquals("sending", saved.messages.single().delivery)
                assertEquals("chat", params.text("mobileMode")); assertEquals("ask", params.text("accessMode"))
                assertEquals("model", params.text("model")); assertEquals("high", params.text("effort"))
                assertEquals(listOf("First message", "Attached notes"), params.array("input").map { it.jsonObject.text("text") })
                buildJsonObject {}
            }
        } }) { ticket = queue.claim(message) }
        assertEquals(1, mutations)
        queue.complete(requireNotNull(ticket)); assertTrue(queue.state.value.messages.isEmpty())
    }
    @Test fun ownershipRejectionOrArchivedChatNeverMutatesOrDropsTheQueue() = runBlocking {
        for (archived in listOf(false, true)) {
            val queue = queue(); queue.add(first); var attempted = false
            val failure = runCatching { submitQueuedMessage(first, { method, _ -> when (method) {
                "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray {}) }
                "thread/resume" -> if (!archived) throw RpcException(-32600, "already has an active writer") else buildJsonObject {}
                else -> thread(archived = archived)
            } }) { attempted = true } }.exceptionOrNull()
            assertNotNull(failure); assertFalse(attempted); assertEquals(listOf(first), queue.state.value.messages)
        }
    }
    @Test fun lostAcknowledgementLeavesUncertainMessagePausedWithoutAutomaticRetry() = runBlocking {
        val queue = queue(); queue.add(first)
        var attempted = false; var mutations = 0; var ticket: QueueTicket? = null
        val error = runCatching { submitQueuedMessage(first, { method, _ -> when (method) {
            "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("chat") }) }
            "thread/read" -> thread()
            else -> { mutations++; throw IOException("Disconnected after request") }
        } }) { ticket = queue.claim(first); attempted = true } }.exceptionOrNull()!! as Exception
        queue.fail(requireNotNull(ticket), "Unconfirmed", uncertainDelivery(attempted, false, error))
        assertEquals(1, mutations); assertEquals("uncertain", queue.state.value.messages.single().delivery)
        assertNull(queue.claim(queue.state.value.messages.single()))
    }
    @Test fun stoppedQueueCannotBeClaimedAndStorageFailurePreventsSending() {
        val queue = queue(); queue.add(first); queue.pause("chat")
        assertNull(queue.claim(first)); queue.resume("chat"); assertNotNull(queue.claim(first))
        var failSaving = false
        val broken = queue { _, _, _ -> if (failSaving) throw IOException("Disk full") }
        broken.add(first); failSaving = true
        assertTrue(runCatching { broken.claim(first) }.isFailure)
        assertEquals("queued", broken.state.value.messages.single().delivery)
    }
    @Test fun failedOrInterruptedTurnsBlockSendingBeforeTheirNotificationArrives() = runBlocking {
        for (status in listOf("failed", "interrupted")) {
            var mutations = 0
            val call: suspend (String, JsonObject) -> JsonObject = { method, _ -> when (method) {
                "thread/loaded/list" -> buildJsonObject { put("data", buildJsonArray { add("chat") }) }
                "thread/read" -> thread(turnStatus = status)
                else -> { mutations++; buildJsonObject {} }
            } }
            assertTrue(runCatching { submitQueuedMessage(first, call) {} }.isFailure)
            assertEquals(0, mutations)
            val queue = queue(); queue.add(first); queue.pause("chat"); queue.resume("chat")
            submitQueuedMessage(first, call, allowFailedTurn = "chat" in queue.state.value.resumedChats) { queue.claim(first) }
            assertEquals(1, mutations)
            assertFalse("Resume allows only one new turn", "chat" in queue.state.value.resumedChats)
        }
    }
}
