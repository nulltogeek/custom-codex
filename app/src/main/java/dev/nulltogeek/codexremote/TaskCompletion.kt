package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

internal data class TaskCompletion(val threadId: String, val turnId: String) {
    fun notificationKey(endpoint: String, username: String): String = buildJsonArray { add(endpoint); add(username); add(key) }.toString()
    val key: String get() = buildJsonArray { add(threadId); add(turnId) }.toString()
}

/** Only successful terminal events alert; remembered identities survive reconnects. */
internal fun taskCompletion(event: JsonObject): TaskCompletion? {
    if (event.text("method") != "turn/completed") return null
    val params = event.obj("params"); val turn = params.obj("turn")
    if (turn.text("status") != "completed" || turn.obj("error").isNotEmpty()) return null
    val threadId = params.text("threadId"); val turnId = turn.text("id")
    return if (threadId.isBlank() || turnId.isBlank()) null else TaskCompletion(threadId, turnId)
}

internal class CompletionTracker(saved: List<String> = emptyList(), private val save: (List<String>) -> Unit = {}) {
    private val seen = saved.takeLast(256).toMutableList()
    fun accept(event: JsonObject): TaskCompletion? {
        val completion = taskCompletion(event) ?: return null
        if (completion.key in seen) return null
        val next = (seen + completion.key).takeLast(256)
        save(next) // If persistence fails, don't publish or mark the event delivered.
        seen.clear(); seen.addAll(next)
        return completion
    }
}

internal fun notificationAccountMatches(state: ScreenState, endpoint: String, username: String): Boolean =
    state.signedIn && state.connection == "Connected" && state.endpoint == endpoint && state.username == username
