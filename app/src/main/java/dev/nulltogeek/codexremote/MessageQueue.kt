package dev.nulltogeek.codexremote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class QueuedMessage(val id: String, val chatId: String, val text: String,
    val attachments: List<Attachment> = emptyList(), val chatMode: String = "codex", val accessMode: String = "host",
    val model: String = "", val effort: String = "", val delivery: String = "queued", val error: String = "")
data class QueueSnapshot(val messages: List<QueuedMessage> = emptyList(), val pausedChats: Set<String> = emptySet(),
    val resumedChats: Set<String> = emptySet())
internal data class QueueTicket(val generation: Long, val message: QueuedMessage)
internal data class QueueEventCursor(val epoch: String, val sequence: Long)

/** Save before publishing state, especially before a network mutation can begin. */
internal class MessageQueue(private val save: (String, String, QueueSnapshot) -> Unit) {
    private val mutable = MutableStateFlow(QueueSnapshot())
    val state = mutable.asStateFlow()
    private var endpoint = ""
    private var username = ""
    private var generation = 0L
    private val completionCutoffs = mutableMapOf<String, QueueEventCursor>()
    @Synchronized fun restore(endpoint: String, username: String, saved: QueueSnapshot) {
        generation++; this.endpoint = endpoint; this.username = username
        completionCutoffs.clear()
        // Relaunch needs explicit Resume. An interrupted send must never retry automatically.
        mutable.value = saved.copy(messages = saved.messages.map {
            if (it.delivery == "sending") it.copy(delivery = "uncertain", error = "Check history before retrying: delivery could not be confirmed.") else it
        }, pausedChats = saved.pausedChats + saved.messages.map { it.chatId }, resumedChats = emptySet())
    }
    @Synchronized fun detach() { generation++; endpoint = ""; username = ""; completionCutoffs.clear(); mutable.value = QueueSnapshot() }
    private fun write(next: QueueSnapshot) { save(endpoint, username, next); mutable.value = next }
    @Synchronized fun add(message: QueuedMessage, cursor: QueueEventCursor? = null) {
        require(message.text.isNotBlank() || message.attachments.isNotEmpty()) { "Write a message or attach a file" }
        require(message.attachments.all { it.readyToSend }) { "Finish uploading attachments first" }
        require(state.value.messages.count { it.chatId == message.chatId } < 20) { "Queue up to 20 messages per chat" }
        require(state.value.messages.none { it.id == message.id })
        val newQueue = state.value.messages.none { it.chatId == message.chatId }
        write(state.value.copy(messages = state.value.messages + message))
        if (newQueue && cursor != null) completionCutoffs[message.chatId] = cursor
    }
    @Synchronized fun edit(id: String, text: String, attachments: List<Attachment>) {
        val old = state.value.messages.find { it.id == id && it.delivery == "queued" } ?: return
        require(text.isNotBlank() || attachments.isNotEmpty()) { "Write a message or keep an attachment" }
        require(attachments.all { it in old.attachments })
        write(state.value.copy(messages = state.value.messages.map { if (it.id == id) it.copy(text = text, attachments = attachments, error = "") else it }))
    }
    @Synchronized fun move(id: String, offset: Int) {
        require(offset == -1 || offset == 1)
        val messages = state.value.messages.toMutableList()
        val from = messages.indexOfFirst { it.id == id && it.delivery == "queued" }
        if (from < 0) return
        val indices = messages.indices.filter { messages[it].chatId == messages[from].chatId }
        val to = indices.getOrNull(indices.indexOf(from) + offset) ?: return
        if (messages[to].delivery != "queued") return
        val other = messages[to]; messages[to] = messages[from]; messages[from] = other
        write(state.value.copy(messages = messages))
    }
    @Synchronized fun remove(id: String) {
        write(state.value.copy(messages = state.value.messages.filterNot { it.id == id && it.delivery != "sending" }))
    }
    @Synchronized fun pause(chatId: String) { write(state.value.copy(pausedChats = state.value.pausedChats + chatId, resumedChats = state.value.resumedChats - chatId)) }
    @Synchronized fun resume(chatId: String, cursor: QueueEventCursor? = null) {
        if (state.value.messages.any { it.chatId == chatId && it.delivery == "uncertain" }) return
        write(state.value.copy(pausedChats = state.value.pausedChats - chatId, resumedChats = state.value.resumedChats + chatId))
        if (cursor != null) completionCutoffs[chatId] = cursor
    }
    @Synchronized fun pauseForCompletion(event: JsonObject, epoch: String) {
        if (event.text("method") != "turn/completed" || epoch.isEmpty() || event.text("_bridgeEpoch") != epoch) return
        val params = event.obj("params")
        val status = params.obj("turn").text("status")
        if (status !in listOf("failed", "interrupted")) return
        val chatId = params.text("threadId")
        if (state.value.messages.none { it.chatId == chatId }) return
        val sequence = event["_bridgeSequence"]?.jsonPrimitive?.longOrNull ?: return
        val cutoff = completionCutoffs[chatId]
        if (cutoff?.epoch == epoch && sequence <= cutoff.sequence) return
        val error = "Previous work ${if (status == "failed") "failed" else "was stopped"}. Review it, then resume the queue."
        write(state.value.copy(pausedChats = state.value.pausedChats + chatId, resumedChats = state.value.resumedChats - chatId,
            messages = state.value.messages.map { if (it.chatId == chatId && it.delivery == "queued") it.copy(error = error) else it }))
        completionCutoffs[chatId] = QueueEventCursor(epoch, sequence)
    }
    @Synchronized fun retry(id: String) {
        write(state.value.copy(messages = state.value.messages.map { if (it.id == id && it.delivery == "uncertain") it.copy(delivery = "queued", error = "") else it }))
    }
    @Synchronized fun claim(expected: QueuedMessage): QueueTicket? {
        val first = state.value.messages.firstOrNull { it.chatId == expected.chatId }
        if (first != expected || first.delivery != "queued" || first.chatId in state.value.pausedChats) return null
        write(state.value.copy(messages = state.value.messages.map { if (it.id == first.id) it.copy(delivery = "sending") else it },
            resumedChats = state.value.resumedChats - first.chatId))
        return QueueTicket(generation, first)
    }
    @Synchronized fun complete(ticket: QueueTicket) {
        if (ticket.generation != generation) return
        write(state.value.copy(messages = state.value.messages.filterNot { it.id == ticket.message.id }))
    }
    @Synchronized fun fail(ticket: QueueTicket, message: String, uncertain: Boolean) {
        if (ticket.generation != generation) return
        reject(ticket.message, message, uncertain)
    }
    @Synchronized fun reject(expected: QueuedMessage, message: String, uncertain: Boolean = false) {
        if (state.value.messages.none { it.id == expected.id }) return
        write(state.value.copy(messages = state.value.messages.map { if (it.id == expected.id)
            it.copy(delivery = if (uncertain) "uncertain" else "queued", error = message) else it },
            pausedChats = state.value.pausedChats + expected.chatId, resumedChats = state.value.resumedChats - expected.chatId))
    }
}

internal fun queueJson(snapshot: QueueSnapshot): JsonObject = buildJsonObject {
    put("pausedChats", JsonArray(snapshot.pausedChats.map(::JsonPrimitive)))
    put("resumedChats", JsonArray(snapshot.resumedChats.map(::JsonPrimitive)))
    put("messages", JsonArray(snapshot.messages.map { item -> buildJsonObject {
        put("id", item.id); put("chatId", item.chatId); put("text", item.text)
        put("attachments", JsonArray(item.attachments.map { it.toJson() }))
        put("chatMode", item.chatMode); put("accessMode", item.accessMode)
        put("model", item.model); put("effort", item.effort); put("delivery", item.delivery); put("error", item.error)
    } }))
}
internal fun parseQueue(raw: JsonObject) = QueueSnapshot(raw.array("messages").map { value ->
    val item = value.jsonObject
    QueuedMessage(item.text("id"), item.text("chatId"), item.text("text"), item.array("attachments").map { parseAttachment(it.jsonObject) },
        item.text("chatMode", "codex"), item.text("accessMode", "host"), item.text("model"), item.text("effort"), item.text("delivery", "queued"), item.text("error"))
}, raw.array("pausedChats").map { it.jsonPrimitive.content }.toSet(), raw.array("resumedChats").map { it.jsonPrimitive.content }.toSet())

internal class QueueWaitingException : Exception()

/** Dispatch belongs to the account's queues, independently of the displayed chat/project. */
internal suspend fun dispatchMessageQueue(queue: MessageQueue, call: suspend (String, JsonObject) -> JsonObject,
    canDispatch: () -> Boolean, sameAccount: () -> Boolean,
    onAccepted: suspend (QueuedMessage) -> Unit = {},
    onRejected: (QueuedMessage, String, Exception) -> Unit = { _, _, _ -> }) {
    if (!canDispatch()) return
    val candidates = queue.state.value.messages.distinctBy { it.chatId }
        .filter { it.delivery == "queued" && it.chatId !in queue.state.value.pausedChats }
    for (candidate in candidates) {
        if (!canDispatch()) return
        var ticket: QueueTicket? = null
        var claimStarted = false
        var attempted = false
        var acknowledged = false
        var completed = false
        try {
            submitQueuedMessage(candidate, call, allowFailedTurn = candidate.chatId in queue.state.value.resumedChats) {
                // Only account/connectivity and durable queue state guard a send.
                // Navigation and live timeline updates cannot invalidate the claim.
                if (!canDispatch()) throw QueueWaitingException()
                claimStarted = true
                ticket = queue.claim(candidate) ?: throw QueueWaitingException()
                attempted = true
            }
            acknowledged = true
            queue.complete(requireNotNull(ticket))
            completed = true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            if (e is QueueWaitingException) continue
            // A disconnected or timed-out status read has not sent the message.
            // Keep it queued for the next connected check rather than silently pausing it.
            if (!claimStarted && ((e is java.io.IOException && e !is RpcException && e !is SessionExpiredException) ||
                e is kotlinx.coroutines.TimeoutCancellationException)) return
            if (sameAccount()) {
                val uncertain = acknowledged || uncertainDelivery(attempted, acknowledged, e)
                val error = if (uncertain) "Check chat history before retrying: delivery could not be confirmed." else e.message ?: "Queued send failed"
                ticket?.let { queue.fail(it, error, uncertain) } ?: queue.reject(candidate, error)
                onRejected(candidate, error, e)
            }
            if (e is SessionExpiredException) return
        }
        if (completed) {
            if (sameAccount()) onAccepted(candidate)
            return
        }
    }
}

/** Read live status and start a new turn; queued input can never steer running work. */
internal suspend fun submitQueuedMessage(message: QueuedMessage, call: suspend (String, JsonObject) -> JsonObject,
    allowFailedTurn: Boolean = false, beforeMutation: () -> Unit): JsonObject {
    val options = jsonObject("mobileMode" to message.chatMode, "accessMode" to message.accessMode)
    val loaded = call("thread/loaded/list", buildJsonObject {}).array("data").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    if (message.chatId !in loaded) call("thread/resume", buildJsonObject {
        put("threadId", message.chatId); put("excludeTurns", true); options.forEach { (key, value) -> put(key, value) }
    })
    val metadata = call("thread/read", buildJsonObject { put("threadId", message.chatId); put("includeTurns", false) })
    val summary = metadata.obj("thread")
    if (summary.obj("status").text("type") == "active" || summary.array("turns").any { it.jsonObject.text("status") == "inProgress" }) throw QueueWaitingException()
    // Reuse legacy/new/ephemeral/paginated history compatibility, with the live
    // metadata already fetched. Running queues never download full history.
    val thread = readThreadHistory(message.chatId) { method, params ->
        if (method == "thread/read" && params["includeTurns"]?.jsonPrimitive?.booleanOrNull == false) metadata else call(method, params)
    }.thread
    if (thread.obj("status").text("type") == "active" || thread.array("turns").any { it.jsonObject.text("status") == "inProgress" }) throw QueueWaitingException()
    val lastStatus = (thread.array("turns").lastOrNull() as? JsonObject)?.text("status")
    check(allowFailedTurn || lastStatus !in listOf("failed", "interrupted")) { "Previous work ${if (lastStatus == "failed") "failed" else "was stopped"}. Review it, then resume the queue." }
    check(thread["archived"]?.jsonPrimitive?.booleanOrNull != true) { "Restore this chat before sending queued messages" }
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
