package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

data class SentMessageEdit(val chatId: String, val itemId: String, val turnId: String,
    val text: String, val attachments: List<Attachment>, val working: Boolean = false,
    val error: String? = null, val branchId: String? = null, val uncertainFork: Boolean = false)

internal fun sentAttachments(input: JsonArray): List<Attachment> = input.mapIndexedNotNull { index, value ->
    val part = value as? JsonObject ?: return@mapIndexedNotNull null
    if (part.text("type") == "text") return@mapIndexedNotNull null
    val type = part.text("type")
    val name = part.text("name").ifEmpty { part.text("path").substringAfterLast('/') }.ifEmpty {
        when (type) { "image", "localImage" -> "Attached image"; "audio", "localAudio" -> "Attached audio"; else -> "Attachment ${index + 1}" }
    }
    Attachment("sent:$index", name, 0, when (type) {
        "image", "localImage" -> "image/*"; "audio", "localAudio" -> "audio/*"; else -> "application/octet-stream"
    }, part)
}

internal data class SentEditPlan(val earlierTurnIds: List<String>, val earlierInput: JsonArray)

/** Editing is an explicit fork before the containing turn, never a history rewrite. */
internal suspend fun sentEditPlan(edit: SentMessageEdit, call: suspend (String, JsonObject) -> JsonObject): SentEditPlan {
    val history = completeEditHistory(edit.chatId, call)
    check(history.obj("status").text("type") != "active" && history.array("turns").none { it.jsonObject.text("status") == "inProgress" }) {
        "Stop the running task before resending an edit."
    }
    val turns = history.array("turns").map { it.jsonObject }
    val index = turns.indexOfFirst { it.text("id") == edit.turnId }
    check(index >= 0) { "This message is no longer in chat history. Refresh and try again." }
    val items = turns[index].array("items").map { it.jsonObject }
    val itemIndex = items.indexOfFirst { it.text("id") == edit.itemId && it.text("type") == "userMessage" }
    check(itemIndex >= 0) { "This message is no longer in chat history. Refresh and try again." }
    // A follow-up sent during the same turn also needs its earlier user inputs.
    val prefix = JsonArray(items.take(itemIndex).filter { it.text("type") == "userMessage" }.flatMap { it.array("content") })
    return SentEditPlan(turns.take(index).map { it.text("id") }, prefix)
}

internal fun sentEditForkParams(state: ScreenState, edit: SentMessageEdit): JsonObject {
    require(state.selectedChat?.id == edit.chatId)
    return JsonObject(recoveryParams(state) + ("beforeTurnId" to JsonPrimitive(edit.turnId)))
}

internal fun sentEditInput(edit: SentMessageEdit, plan: SentEditPlan): JsonArray {
    require(edit.text.isNotBlank() || edit.attachments.isNotEmpty()) { "Write a message or keep an attachment." }
    return buildJsonArray {
        plan.earlierInput.forEach(::add)
        if (edit.text.isNotBlank()) add(jsonObject("type" to "text", "text" to edit.text))
        edit.attachments.forEach { add(it.input) }
    }
}

/** Older servers may silently ignore beforeTurnId. Verify the actual boundary before sending. */
internal suspend fun verifySentEditBranch(branchId: String, sourceId: String, plan: SentEditPlan,
    call: suspend (String, JsonObject) -> JsonObject) {
    check(branchId.isNotBlank() && branchId != sourceId) { "The server did not create a separate chat." }
    val thread = completeEditHistory(branchId, call)
    check(thread.obj("status").text("type") != "active" && thread.array("turns").none { it.jsonObject.text("status") == "inProgress" }) {
        "The copied chat is already running. Check it before sending again."
    }
    check(thread.array("turns").map { it.jsonObject.text("id") } == plan.earlierTurnIds) {
        "The server could not copy history up to this message. Nothing was resent; update the server before retrying."
    }
}

private suspend fun completeEditHistory(id: String, call: suspend (String, JsonObject) -> JsonObject): JsonObject {
    val loaded = readThreadHistory(id, call)
    check(loaded.authoritative) { "Chat history is unavailable. Refresh before editing a sent message." }
    var turns = loaded.thread.array("turns").toList()
    var cursor = loaded.olderCursor
    val cursors = mutableSetOf<String>()
    while (cursor != null) {
        check(cursors.add(cursor)) { "Chat history pagination did not advance." }
        val page = call("thread/turns/list", buildJsonObject {
            put("threadId", id); put("cursor", cursor); put("limit", 20); put("itemsView", "full"); put("sortDirection", "desc")
        })
        turns = page.array("data").reversed() + turns
        cursor = page.text("nextCursor").ifEmpty { null }
    }
    return JsonObject(loaded.thread + ("turns" to JsonArray(turns.distinctBy { it.jsonObject.text("id") })))
}
