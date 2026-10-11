package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

val WireJson = Json { ignoreUnknownKeys = true }
fun JsonObject.text(key: String, fallback: String = ""): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: buildJsonObject {}
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
fun jsonObject(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

fun subscriptionCursor(epoch: String, sequence: Long, snapshot: JsonObject): Pair<String, Long> {
    val nextEpoch = snapshot.text("epoch")
    val snapshotSequence = (snapshot["sequence"] as? JsonPrimitive)?.longOrNull ?: 0
    val gap = (snapshot["replayGap"] as? JsonPrimitive)?.booleanOrNull == true
    return nextEpoch to when {
        epoch != nextEpoch -> snapshotSequence
        gap -> maxOf(sequence, snapshotSequence)
        else -> sequence
    }
}

data class Project(val id: String, val name: String, val roots: List<String>)
data class Chat(val id: String, val title: String, val preview: String, val cwd: String,
    val status: String = "notLoaded", val archived: Boolean = false, val pinned: Boolean = false,
    val parentId: String? = null, val source: String = "", val updatedAt: Long = 0, val projectId: String? = null)
data class TimelineItem(val id: String, val type: String, val text: String, val detail: String = "",
    val status: String = "", val changes: List<Change> = emptyList())
data class Change(val path: String, val diff: String)
data class PendingInput(val id: String, val method: String, val params: JsonObject)
data class ModelChoice(val id: String, val name: String, val efforts: List<String>, val defaultEffort: String)
data class Attachment(val id: String, val name: String, val size: Long, val mimeType: String, val input: JsonObject, val previewPath: String = "", val uploading: Boolean = false)
fun parseAttachment(raw: JsonObject) = Attachment(raw.text("id"), raw.text("name"), raw["size"]?.jsonPrimitive?.longOrNull ?: 0, raw.text("mimeType"), raw.obj("input"), raw.text("previewPath"))
fun Attachment.toJson() = buildJsonObject { put("id", id); put("name", name); put("size", size); put("mimeType", mimeType); put("input", input); put("previewPath", previewPath) }
data class ScreenState(
    val endpoint: String = "", val username: String = "", val signedIn: Boolean = false, val connection: String = "Offline",
    val host: String = "Your computer", val projects: List<Project> = emptyList(),
    val assignments: Map<String, String> = emptyMap(), val chats: List<Chat> = emptyList(),
    val activeCursor: String? = null, val archivedCursor: String? = null,
    val catalogLoading: Boolean = false, val catalogError: String? = null, val catalogRevision: Long = 0,
    val selectedChat: Chat? = null, val timeline: List<TimelineItem> = emptyList(),
    val pending: List<PendingInput> = emptyList(), val models: List<ModelChoice> = emptyList(),
    val selectedModel: String = "", val selectedEffort: String = "", val draft: String = "",
    val loading: Boolean = false, val sending: Boolean = false, val notice: String? = null,
    val theme: String = "system", val diff: Change? = null, val page: String = "chats",
    val projectFilter: String? = null, val uncertainSend: Boolean = false,
    val snapshotSequence: Long = 0, val itemSequences: Map<String, Long> = emptyMap(), val threadSequence: Long = 0,
    val searchTerm: String = "", val olderCursor: String? = null,
    val historyRevision: Long = 0, val hiddenOlderCount: Int = 0, val olderLoading: Boolean = false, val olderError: String? = null,
    val lastTurnStatus: String = "",
    val statusSequence: Long = 0, val titleSequence: Long = 0, val pendingSequence: Long = 0,
    val attachments: List<Attachment> = emptyList(), val uploading: Boolean = false,
    val chatMode: String = "codex", val accessMode: String = "host", val historyAvailable: Boolean = true,
    val accent: String = "exchange", val motion: Boolean = true,
    val activeTurnId: String = "",
    val appUpdate: AppUpdate? = null, val updateChecking: Boolean = false,
    val updateDownloading: Boolean = false, val updateProgress: Int = 0,
    val updateApk: String? = null, val updateMessage: String = "",
)

fun reconcileChatMetadata(state: ScreenState, snapshot: Chat, sequence: Long): ScreenState {
    val current = state.selectedChat ?: return state
    return state.copy(selectedChat = snapshot.copy(
        status = if (state.statusSequence > sequence) current.status else snapshot.status,
        title = if (state.titleSequence > sequence) current.title else snapshot.title),
        statusSequence = maxOf(state.statusSequence, sequence), titleSequence = maxOf(state.titleSequence, sequence))
}

fun reconcilePendingSnapshot(state: ScreenState, snapshot: JsonObject): ScreenState {
    val sequence = (snapshot["sequence"] as? JsonPrimitive)?.longOrNull ?: 0
    if (sequence < state.pendingSequence) return state
    return state.copy(pendingSequence = sequence, pending = snapshot.array("pendingRequests").mapNotNull { r ->
        (r as? JsonObject)?.let { PendingInput(it.text("requestId"), it.text("method"), it.obj("params")) }
    })
}

fun parseChat(raw: JsonObject, archived: Boolean = false): Chat {
    val source = raw["source"]
    val sourceObj = source as? JsonObject
    val sub = if (sourceObj?.containsKey("subAgent") == true) sourceObj.obj("subAgent") else sourceObj?.obj("subagent")
    val spawn = sub?.obj("thread_spawn") ?: buildJsonObject {}
    val parent = raw.text("parentThreadId").ifEmpty { spawn.text("parent_thread_id") }.ifEmpty { null }
    return Chat(raw.text("id"), raw.text("name").ifEmpty { raw.text("preview").lineSequence().firstOrNull().orEmpty().take(100) }.ifEmpty { "Untitled chat" },
        raw.text("preview"), raw.text("cwd"), raw.obj("status").text("type", "notLoaded"), archived,
        (raw["isPinned"] as? JsonPrimitive)?.booleanOrNull == true, parent,
        (source as? JsonPrimitive)?.contentOrNull ?: if (sub?.isNotEmpty() == true) "subAgent" else "",
        (raw["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0, raw.text("projectId").ifEmpty { null })
}

fun parseItem(raw: JsonObject): TimelineItem {
    val type = raw.text("type"); var text = raw.text("text")
    if (type == "userMessage") text = raw.array("content").mapNotNull { it as? JsonObject }.joinToString("\n") {
        when (it.text("type")) { "text" -> it.text("text"); "image", "localImage" -> "[Image]"; else -> it.text("text") }
    }
    if (type == "commandExecution") text = raw.text("aggregatedOutput").ifEmpty { raw.text("output") }
    if (type == "reasoning") text = raw.array("summary").joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("text").orEmpty() }
    val changes = raw.array("changes").mapNotNull { it as? JsonObject }.map { Change(it.text("path"), it.text("diff")) }
    if (type == "fileChange") text = "${changes.size} changed file${if (changes.size == 1) "" else "s"}"
    if (type == "mcpToolCall") text = listOf(raw.text("tool"), readableContent(raw["result"]), readableContent(raw["error"])).filter { it.isNotBlank() }.joinToString("\n")
    if (type == "dynamicToolCall") text = listOf(raw.text("tool"), readableContent(raw["contentItems"])).filter { it.isNotBlank() }.joinToString("\n")
    if (type == "functionCallOutput") text = readableContent(raw["output"])
    if (text.isEmpty() && type !in listOf("agentMessage", "userMessage")) text = raw.text("status").ifEmpty { type }
    return TimelineItem(raw.text("id"), type, text, raw.text("command").ifEmpty { raw.text("server").ifEmpty { raw.text("namespace").ifEmpty { raw.text("name") } } }, raw.text("status"), changes)
}

// Decode text content blocks without exposing base64 media as pages of JSON.
private fun readableContent(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.joinToString("\n") { readableContent(it) }
    is JsonObject -> when {
        value["text"] is JsonPrimitive -> value.text("text")
        value.text("type") in listOf("image", "inputImage", "audio", "inputAudio") -> "[${value.text("type")} attachment]"
        value["content"] is JsonArray -> readableContent(value["content"]).ifBlank { readableContent(value["structuredContent"]) }
        value["resource"] is JsonObject -> readableContent(value["resource"])
        value["message"] is JsonPrimitive -> value.text("message")
        else -> value.toString()
    }
}

fun parseTimeline(thread: JsonObject): List<TimelineItem> = thread.array("turns")
    .mapNotNull { it as? JsonObject }.flatMap { turn ->
        val items = turn.array("items").mapNotNull { it as? JsonObject }.map(::parseItem)
        val error = turn.obj("error").text("message")
        if (error.isEmpty()) items else items + TimelineItem("turn-error:${turn.text("id")}", "error", error, status = "failed")
    }

fun reduceTimeline(items: List<TimelineItem>, method: String, params: JsonObject): List<TimelineItem> {
    if (method == "item/started" || method == "item/completed") {
        val item = parseItem(params.obj("item")); if (item.id.isEmpty()) return items
        return if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it } else items + item
    }
    val id = params.text("itemId"); if (id.isEmpty()) return items
    val delta = params.text("delta")
    if (method !in listOf("item/agentMessage/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta")) return items
    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return items + TimelineItem(id, if (method.contains("commandExecution")) "commandExecution" else if (method.contains("reasoning")) "reasoning" else "agentMessage", delta)
    return items.mapIndexed { i, item -> if (i == index) item.copy(text = item.text + delta) else item }
}
