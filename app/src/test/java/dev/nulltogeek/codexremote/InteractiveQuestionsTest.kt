package dev.nulltogeek.codexremote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InteractiveQuestionsTest {
    private fun raw(text: String) = WireJson.parseToJsonElement(text).jsonObject
    private val question = raw("""{"id":"ask","type":"agentMessage","phase":"commentary","delivery":"async","text":"Choose a format","questions":[{"title":"Which format?","options":["PNG","JPEG"]},{"title":"What size?"}]}""")
    private fun item() = parseItem(question, "t", "inProgress")
    private fun history(status: String = "inProgress", turnId: String = "t", reply: String? = null): JsonObject = buildJsonObject {
        put("thread", buildJsonObject {
            put("status", jsonObject("type" to if (status == "inProgress") "active" else "idle"))
            put("turns", buildJsonArray { add(buildJsonObject {
                put("id", turnId); put("status", status); put("items", buildJsonArray {
                    add(question)
                    if (reply != null) add(buildJsonObject {
                        put("id", "reply"); put("type", "userMessage")
                        put("content", buildJsonArray { add(jsonObject("type" to "text", "text" to reply)) })
                    })
                })
            }) })
        })
    }
    private val state = ScreenState(selectedChat = Chat("chat", "Chat", "", "", "active"), activeTurnId = "stale")
    private val answers = mapOf("0" to "PNG", "1" to "1024 px")
    @Test fun asyncQuestionSurvivesLiveAndHistoryPresentationWithoutShowingProgress() {
        val live = reduceTimeline(emptyList(), "item/completed", buildJsonObject { put("turnId", "t"); put("item", question) })
        val progress = TimelineItem("progress", "agentMessage", "Working", phase = "commentary", turnId = "t", turnStatus = "inProgress")
        assertEquals(listOf("ask"), conversationTimeline(state.copy(timeline = live + progress)).map { it.id })
        val reopened = parseTimeline(history("completed").obj("thread"))
        assertEquals(item().questions, reopened.single().questions)
        assertEquals(listOf("ask"), conversationTimeline(state.copy(timeline = reopened, activeTurnId = "")).map { it.id })
        assertEquals(listOf("PNG", "JPEG"), live.single().questions.first().options.map { it.label })
        assertTrue(live.single().questions.last().options.isEmpty())
    }
    @Test fun explicitAsyncMessagesWithoutChoicesStayVisibleDuringWork() {
        val async = parseItem(raw("""{"id":"ask","type":"agentMessage","delivery":"async","phase":"commentary","text":"Could you clarify?"}"""), "t", "inProgress")
        assertEquals(listOf(async), conversationTimeline(state.copy(timeline = listOf(async))))
    }
    @Test fun synchronousQuestionsKeepIdsDescriptionsAndSecretFlag() {
        val request = PendingInput("request", "item/tool/requestUserInput", raw("""{"questions":[{"id":"format","header":"Format","question":"Which?","options":[{"label":"PNG","description":"Transparent"}]},{"id":"token","question":"Token?","isSecret":true}]}"""))
        val questions = request.questions()
        assertEquals("Transparent", questions.first().options.single().description)
        assertTrue(questions.last().secret)
        assertFalse(validQuestionAnswers(questions, mapOf("format" to "PNG")))
        assertFalse(validQuestionAnswers(questions, mapOf("format" to "PNG", "token" to " ")))
        val result = questionResult(questions, mapOf("format" to "PNG", "token" to "typed answer"))
        assertEquals(raw("""{"answers":{"format":{"answers":["PNG"]},"token":{"answers":["typed answer"]}}}"""), result)
        assertFalse(validQuestionAnswers(emptyList(), emptyMap()))
    }
    @Test fun asyncRepliesIdentifyEveryQuestionAndAllowCustomText() {
        assertEquals("Which format?\nSVG\n\nWhat size?\n1024 px", questionReply(item().questions, answers + ("0" to "SVG")))
    }
    @Test fun activeAnswerSteersLiveTurnImmediatelyWithoutStartingOrQueuing() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var mutations = 0
        submitAsyncAnswer(state, item(), questionReply(item().questions, answers), { method, params ->
            calls += method to params
            when (method) { "thread/loaded/list" -> raw("""{"data":["chat"]}"""); "thread/read" -> history(); else -> buildJsonObject {} }
        }) { mutations++ }
        assertEquals(1, mutations)
        assertEquals("turn/steer", calls.last().first)
        assertEquals("t", calls.last().second.text("expectedTurnId"))
        assertEquals("Which format?\nPNG\n\nWhat size?\n1024 px", calls.last().second.array("input").single().jsonObject.text("text"))
        assertFalse(calls.any { it.first == "turn/start" || it.first == "thread/resume" })
    }
    @Test fun completedQuestionStartsNextTurnAndResumesOnlyWhenNotLoaded() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        submitAsyncAnswer(state.copy(chatMode = "chat", accessMode = "read-only", selectedModel = "gpt"), item(), "reply", { method, params ->
            calls += method to params
            when (method) { "thread/loaded/list" -> raw("""{"data":[]}"""); "thread/read" -> history("completed"); else -> buildJsonObject {} }
        }) {}
        assertEquals(1, calls.count { it.first == "thread/resume" })
        assertEquals("turn/start", calls.last().first)
        assertEquals("chat", calls.last().second.text("mobileMode"))
        assertEquals("read-only", calls.last().second.text("accessMode"))
        assertEquals("gpt", calls.last().second.text("model"))
    }
    @Test fun laterRunningTurnRejectsAnEarlierQuestionWithoutMutation() = runBlocking {
        var mutated = false
        try {
            submitAsyncAnswer(state, item(), "reply", { method, _ ->
                if (method == "thread/loaded/list") raw("""{"data":["chat"]}""") else history(turnId = "next")
            }) { mutated = true }
            fail("Must not steer another turn")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("Another task")) }
        assertFalse(mutated)
    }
    @Test fun alreadyAnsweredQuestionCannotBeSentAgainAfterReopenOrUncertainDelivery() = runBlocking {
        val reply = questionReply(item().questions, answers)
        val timeline = parseTimeline(history("completed", reply = reply).obj("thread"))
        assertTrue(questionAnswered(item(), timeline))
        var mutated = false
        try {
            submitAsyncAnswer(state, item(), reply, { method, _ ->
                if (method == "thread/loaded/list") raw("""{"data":["chat"]}""") else history("completed", reply = reply)
            }) { mutated = true }
            fail("Must not resend")
        } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("already been answered")) }
        assertFalse(mutated)
    }
    @Test fun staleQuestionAndArchivedChatRejectBeforeSending() = runBlocking {
        for (archived in listOf(false, true)) {
            var mutated = false
            try {
                submitAsyncAnswer(state.copy(selectedChat = state.selectedChat!!.copy(archived = archived)), item(), "reply", { method, _ ->
                    if (method == "thread/loaded/list") raw("""{"data":["chat"]}""") else raw("""{"thread":{"turns":[]}}""")
                }) { mutated = true }
                fail("Must not send")
            } catch (_: IllegalStateException) { }
            assertFalse(mutated)
        }
    }
    @Test fun steeringFailureIsNotRetriedOrConvertedToANewTurn() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            submitAsyncAnswer(state, item(), "reply", { method, _ ->
                calls += method
                when (method) {
                    "thread/loaded/list" -> raw("""{"data":["chat"]}""")
                    "thread/read" -> history()
                    else -> throw RpcException(-32602, "Turn completed before answer arrived")
                }
            }) {}
            fail("Must report the failure")
        } catch (_: RpcException) { }
        assertEquals(1, calls.count { it == "turn/steer" })
        assertFalse(calls.contains("turn/start"))
    }
}
