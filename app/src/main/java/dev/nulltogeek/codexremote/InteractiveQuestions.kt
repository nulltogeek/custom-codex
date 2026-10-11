package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

data class InputOption(val label: String, val description: String = "")
data class InputQuestion(val id: String, val title: String, val header: String = "",
    val options: List<InputOption> = emptyList(), val secret: Boolean = false)

fun parseAsyncQuestions(raw: JsonObject): List<InputQuestion> = raw.array("questions").mapIndexedNotNull { index, value ->
    val question = value as? JsonObject ?: return@mapIndexedNotNull null
    val title = question.text("title").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
    InputQuestion(index.toString(), title, options = question.array("options").mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(::InputOption)
    })
}

fun PendingInput.questions(): List<InputQuestion> = params.array("questions").mapNotNull { value ->
    val question = value as? JsonObject ?: return@mapNotNull null
    val id = question.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
    InputQuestion(id, question.text("question"), question.text("header"), question.array("options").mapNotNull {
        val option = it as? JsonObject ?: return@mapNotNull null
        option.text("label").takeIf(String::isNotBlank)?.let { label -> InputOption(label, option.text("description")) }
    }, question["isSecret"]?.jsonPrimitive?.booleanOrNull == true)
}

fun validQuestionAnswers(questions: List<InputQuestion>, answers: Map<String, String>): Boolean =
    questions.isNotEmpty() && questions.all { answers[it.id]?.isNotBlank() == true }

fun questionResult(questions: List<InputQuestion>, answers: Map<String, String>): JsonObject {
    require(validQuestionAnswers(questions, answers)) { "Answer each question before submitting." }
    return buildJsonObject { put("answers", buildJsonObject {
        questions.forEach { q -> put(q.id, buildJsonObject { put("answers", buildJsonArray { add(answers.getValue(q.id)) }) }) }
    }) }
}

fun questionReply(questions: List<InputQuestion>, answers: Map<String, String>): String {
    require(validQuestionAnswers(questions, answers)) { "Answer each question before submitting." }
    return questions.joinToString("\n\n") { "${it.title}\n${answers.getValue(it.id)}" }
}

fun questionAnswered(item: TimelineItem, timeline: List<TimelineItem>): Boolean {
    if (item.questions.isEmpty()) return false
    return timeline.dropWhile { it.id != item.id }.drop(1).any { reply ->
        reply.type == "userMessage" && reply.text.startsWith(item.questions.first().title + "\n") &&
            item.questions.all { reply.text.contains(it.title + "\n") }
    }
}

/** Async answers are normal user input. Read current work before steering or starting,
 * and never send them to the message queue or retry an ambiguous mutation. */
internal suspend fun submitAsyncAnswer(state: ScreenState, item: TimelineItem, text: String,
    call: suspend (String, JsonObject) -> JsonObject, beforeMutation: () -> Unit) {
    val chat = requireNotNull(state.selectedChat)
    check(!chat.archived) { "Restore this chat before answering." }
    val options = jsonObject("mobileMode" to state.chatMode, "accessMode" to state.accessMode)
    val loaded = call("thread/loaded/list", buildJsonObject {}).array("data").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    if (chat.id !in loaded) call("thread/resume", buildJsonObject {
        put("threadId", chat.id); put("excludeTurns", true); options.forEach { (k, v) -> put(k, v) }
    })
    val history = readThreadHistory(chat.id, call)
    check(history.authoritative) { "Refresh to load the conversation before answering." }
    val items = parseTimeline(history.thread)
    val liveQuestion = items.find { it.id == item.id }
    check(liveQuestion?.questions == item.questions) { "This question changed. Refresh before answering." }
    check(!questionAnswered(item, items)) { "This question has already been answered. Refresh to see the answer." }
    check(history.thread["archived"]?.jsonPrimitive?.booleanOrNull != true) { "Restore this chat before answering." }
    val active = history.thread.array("turns").mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }
    val input = buildJsonArray { add(jsonObject("type" to "text", "text" to text)) }
    if (active != null) {
        check(active.text("id") == item.turnId) { "Another task is running. Wait for it to finish before answering this earlier question." }
        beforeMutation()
        call("turn/steer", buildJsonObject { put("threadId", chat.id); put("expectedTurnId", active.text("id")); put("input", input) })
    } else {
        check(history.thread.obj("status").text("type") != "active") { "Refresh to load the running task before answering." }
        beforeMutation()
        call("turn/start", buildJsonObject {
            put("threadId", chat.id); put("input", input); options.forEach { (k, v) -> put(k, v) }
            if (state.selectedModel.isNotEmpty()) put("model", state.selectedModel)
            if (state.selectedEffort.isNotEmpty()) put("effort", state.selectedEffort)
        })
    }
}
