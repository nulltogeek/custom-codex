package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

/** Explicit sends need live ownership/status, not a download of old conversation items. */
internal suspend fun submitImmediateMessage(message: QueuedMessage,
    call: suspend (String, JsonObject) -> JsonObject, beforeMutation: () -> Unit): JsonObject {
    val options = jsonObject("mobileMode" to message.chatMode, "accessMode" to message.accessMode)
    var thread = call("thread/read", buildJsonObject { put("threadId", message.chatId); put("includeTurns", false) }).obj("thread")
    check(thread["archived"]?.jsonPrimitive?.booleanOrNull != true) { "Restore this chat before sending." }
    val status = thread.obj("status").text("type")
    if (status !in listOf("idle", "active")) {
        // notLoaded is authoritative. Older backends with no status need a loaded-list check.
        val loaded = status != "notLoaded" && call("thread/loaded/list", buildJsonObject {}).array("data").any { (it as? JsonPrimitive)?.contentOrNull == message.chatId }
        if (!loaded) thread = call("thread/resume", buildJsonObject {
            put("threadId", message.chatId); put("excludeTurns", true); put("deferGoalContinuation", true)
            options.forEach { (key, value) -> put(key, value) }
        }).obj("thread")
        if (thread.obj("status").text("type").isBlank()) thread = call("thread/read", buildJsonObject {
            put("threadId", message.chatId); put("includeTurns", false)
        }).obj("thread")
    }
    if (thread.obj("status").text("type") == "active" || thread.array("turns").any { it.jsonObject.text("status") == "inProgress" }) throw QueueWaitingException()
    check(thread["archived"]?.jsonPrimitive?.booleanOrNull != true) { "Restore this chat before sending." }
    val input = buildJsonArray {
        if (message.text.isNotBlank()) add(jsonObject("type" to "text", "text" to message.text))
        message.attachments.forEach { add(it.input) }
    }
    beforeMutation()
    return call("turn/start", buildJsonObject {
        put("threadId", message.chatId); put("input", input); options.forEach { (key, value) -> put(key, value) }
        if (message.model.isNotEmpty()) put("model", message.model)
        if (message.effort.isNotEmpty()) put("effort", message.effort)
    })
}

/** The active ID from live events makes Stop a single request, independent of history refresh. */
internal suspend fun interruptCurrentTurn(state: ScreenState, call: suspend (String, JsonObject) -> JsonObject) {
    val id = requireNotNull(state.selectedChat).id
    var turnId = state.activeTurnId.ifEmpty {
        state.timeline.lastOrNull { it.turnStatus == "inProgress" }?.turnId.orEmpty()
    }
    if (turnId.isEmpty()) {
        val turns = try {
            call("thread/turns/list", buildJsonObject {
                put("threadId", id); put("limit", 1); put("itemsView", "notLoaded"); put("sortDirection", "desc")
            }).array("data")
        } catch (error: RpcException) {
            if (error.code != -32601 && !listOf("unsupported", "not supported", "paginated", "history mode", "not materialized", "ephemeral").any { error.message.orEmpty().contains(it, true) }) throw error
            call("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", true) }).obj("thread").array("turns")
        }
        turnId = turns.mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }?.text("id").orEmpty()
    }
    check(turnId.isNotEmpty()) { "No active turn was found. Refresh to check whether it already stopped." }
    call("turn/interrupt", jsonObject("threadId" to id, "turnId" to turnId))
}
