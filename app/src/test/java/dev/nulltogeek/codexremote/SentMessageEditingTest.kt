package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SentMessageEditingTest {
    private fun item(id: String, text: String, extra: JsonArray = JsonArray(emptyList())) = buildJsonObject {
        put("id", id); put("type", "userMessage"); put("content", JsonArray(listOf(jsonObject("type" to "text", "text" to text)) + extra))
    }
    private fun turn(id: String, vararg items: JsonObject) = buildJsonObject {
        put("id", id); put("status", "completed"); put("items", JsonArray(items.toList()))
    }
    private fun thread(vararg turns: JsonObject) = buildJsonObject {
        put("id", "source"); put("status", jsonObject("type" to "idle")); put("turns", JsonArray(turns.toList()))
    }
    private val edit = SentMessageEdit("source", "question", "second", "Revised", emptyList())
    private fun response(raw: JsonObject) = buildJsonObject { put("thread", raw) }

    @Test fun forkUsesExplicitBoundaryAndSelectedOptionsWithoutStartingWork() {
        val state = ScreenState(selectedChat = Chat("source", "Original", "", "/work"), chatMode = "chat", accessMode = "ask", selectedModel = "selected-model")
        val params = sentEditForkParams(state, edit)
        assertEquals("source", params.text("threadId")); assertEquals("second", params.text("beforeTurnId"))
        assertEquals("chat", params.text("mobileMode")); assertEquals("ask", params.text("accessMode"))
        assertEquals("selected-model", params.text("model"))
        assertTrue(params["deferGoalContinuation"]!!.jsonPrimitive.boolean)
        assertFalse(params.containsKey("lastTurnId")); assertFalse(params.containsKey("input"))
    }
    @Test fun attachmentsRetainExactInputsAndCanBeRemovedFromTheRevision() {
        val media = buildJsonArray {
            add(jsonObject("type" to "localImage", "path" to "/uploads/photo.jpg"))
            add(jsonObject("type" to "image", "fileId" to "private-image"))
            add(jsonObject("type" to "mention", "name" to "report", "path" to "/work/report.txt"))
        }
        val parsed = parseItem(item("question", "Original", media), "second", "completed")
        assertEquals(4, parsed.input.size)
        val files = sentAttachments(parsed.input)
        assertEquals(media.toList(), files.map { it.input })
        val revised = edit.copy(attachments = files.drop(1))
        val input = sentEditInput(revised, SentEditPlan(emptyList(), JsonArray(emptyList())))
        assertEquals("Revised", input.first().jsonObject.text("text"))
        assertEquals(media.drop(1), input.drop(1)); assertFalse(input.toString().contains("photo.jpg"))
    }
    @Test fun imageOnlyEditsAreAllowedButEmptyEditsAreRejected() {
        val files = sentAttachments(buildJsonArray { add(jsonObject("type" to "localImage", "path" to "/uploaded/photo.png")) })
        val plan = SentEditPlan(emptyList(), JsonArray(emptyList()))
        assertEquals(files.single().input, sentEditInput(edit.copy(text = "", attachments = files), plan).single())
        assertTrue(runCatching { sentEditInput(edit.copy(text = ""), plan) }.isFailure)
    }
    @Test fun firstMessagePlanHasNoEarlierHistoryAndRetainsEarlierInputsForFollowups() = runBlocking {
        val previous = item("initial", "Earlier instruction")
        val raw = thread(turn("second", previous, item("question", "Original")))
        val plan = sentEditPlan(edit) { _, _ -> response(raw) }
        assertTrue(plan.earlierTurnIds.isEmpty())
        assertEquals("Earlier instruction", plan.earlierInput.single().jsonObject.text("text"))
        val input = sentEditInput(edit, plan)
        assertEquals(listOf("Earlier instruction", "Revised"), input.map { it.jsonObject.text("text") })
    }
    @Test fun olderPaginatedMessagesAreLocatedBeforeSelectingTheBoundary() = runBlocking {
        val methods = mutableListOf<String>()
        val plan = sentEditPlan(edit) { method, params ->
            methods += method
            if (method == "thread/read") response(buildJsonObject {
                put("id", "source"); put("historyMode", "paginated"); put("status", jsonObject("type" to "idle"))
            }) else buildJsonObject {
                assertEquals("full", params.text("itemsView"))
                if (params.text("cursor").isEmpty()) {
                    put("data", JsonArray(listOf(turn("third", item("latest", "Latest"))))); put("nextCursor", "older")
                } else put("data", JsonArray(listOf(turn("second", item("question", "Original")), turn("first", item("first", "Earlier")))))
            }
        }
        assertEquals(listOf("first"), plan.earlierTurnIds)
        assertEquals(listOf("thread/read", "thread/turns/list", "thread/turns/list"), methods)
    }
    @Test fun runningMissingAndUnavailableMessagesNeverReachAMutation() = runBlocking {
        val fixtures = listOf(
            thread(turn("second", item("question", "Original"))).let { JsonObject(it + ("status" to jsonObject("type" to "active"))) },
            thread(turn("second", item("different", "Changed"))),
            thread(turn("different", item("question", "Original"))),
            buildJsonObject { put("ephemeral", true) }
        )
        for (raw in fixtures) {
            val methods = mutableListOf<String>()
            assertTrue(runCatching { sentEditPlan(edit) { method, _ -> methods += method; response(raw) } }.isFailure)
            assertTrue(methods.all { it == "thread/read" })
        }
    }
    @Test fun forkBoundaryIsVerifiedAndIgnoredParametersAreRejected() = runBlocking {
        val plan = SentEditPlan(listOf("first"), JsonArray(emptyList()))
        verifySentEditBranch("copy", "source", plan) { _, _ -> response(thread(turn("first"))) }
        for (raw in listOf(thread(turn("first"), turn("second")), thread(), thread(turn("different")))) {
            assertTrue(runCatching { verifySentEditBranch("copy", "source", plan) { _, _ -> response(raw) } }.isFailure)
        }
        assertTrue(runCatching { verifySentEditBranch("source", "source", plan) { _, _ -> error("Must not read source as copy") } }.isFailure)
    }
    @Test fun firstMessageForkVerifiesAnEmptyBoundaryAndRejectsActiveCopies() = runBlocking {
        val plan = SentEditPlan(emptyList(), JsonArray(emptyList()))
        verifySentEditBranch("copy", "source", plan) { _, _ -> response(thread()) }
        val active = JsonObject(thread() + ("status" to jsonObject("type" to "active")))
        assertTrue(runCatching { verifySentEditBranch("copy", "source", plan) { _, _ -> response(active) } }.isFailure)
    }
    @Test fun readFailuresAreNeverRetriedAndRepeatingPaginationStops() = runBlocking {
        var calls = 0
        assertTrue(runCatching { sentEditPlan(edit) { _, _ -> calls++; throw IOException("Disconnected") } }.isFailure)
        assertEquals(1, calls)
        calls = 0
        assertTrue(runCatching { sentEditPlan(edit) { method, _ ->
            calls++
            if (method == "thread/read") response(buildJsonObject { put("historyMode", "paginated") })
            else buildJsonObject { put("data", JsonArray(emptyList())); put("nextCursor", "same") }
        } }.isFailure)
        assertEquals(3, calls)
    }
}
