package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

fun writerConflict(error: Exception): Boolean = error is RpcException &&
    error.message.orEmpty().contains("already has an active writer", ignoreCase = true)

fun recoveryParams(state: ScreenState) = buildJsonObject {
    put("threadId", requireNotNull(state.selectedChat).id)
    put("mobileMode", state.chatMode); put("accessMode", state.accessMode)
    put("excludeTurns", true); put("deferGoalContinuation", true)
    if (state.selectedModel.isNotEmpty()) put("model", state.selectedModel)
}

/** Resolve runtime ownership before steering or starting any message mutation. */
suspend fun submitThreadInput(state: ScreenState, input: JsonArray,
    call: suspend (String, JsonObject) -> JsonObject, beforeMutation: () -> Unit) {
    val chat = requireNotNull(state.selectedChat)
    val options = buildJsonObject { put("mobileMode", state.chatMode); put("accessMode", state.accessMode) }
    val loaded = call("thread/loaded/list", buildJsonObject {}).array("data")
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val resumed = if (chat.id !in loaded) call("thread/resume", buildJsonObject {
        put("threadId", chat.id); put("excludeTurns", true)
        options.forEach { (key, value) -> put(key, value) }
    }) else null
    val status = resumed?.obj("thread")?.obj("status")?.text("type")?.ifEmpty { null } ?: chat.status
    if (status == "active") {
        val turnId = state.activeTurnId.ifEmpty {
            call("thread/read", buildJsonObject { put("threadId", chat.id); put("includeTurns", true) })
                .obj("thread").array("turns").mapNotNull { it as? JsonObject }
                .lastOrNull { it.text("status") == "inProgress" }?.text("id")
                ?: throw IllegalStateException("Refresh to load the active turn before sending")
        }
        beforeMutation()
        call("turn/steer", buildJsonObject {
            put("threadId", chat.id); put("expectedTurnId", turnId); put("input", input)
        })
    } else {
        beforeMutation()
        call("turn/start", buildJsonObject {
            put("threadId", chat.id); put("input", input)
            options.forEach { (key, value) -> put(key, value) }
            if (state.selectedModel.isNotEmpty()) put("model", state.selectedModel)
            if (state.selectedEffort.isNotEmpty()) put("effort", state.selectedEffort)
        })
    }
}
