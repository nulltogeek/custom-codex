package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

data class ThreadHistory(val thread: JsonObject, val sequence: Long, val metadataSequence: Long,
    val olderCursor: String? = null, val authoritative: Boolean = true)

/** Legacy and newly created chats must never be forced through experimental pagination. */
suspend fun readThreadHistory(id: String, call: suspend (String, JsonObject) -> JsonObject): ThreadHistory {
    val metadata = call("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", false) })
    val raw = metadata.obj("thread")
    val metadataSequence = metadata["_bridgeSequence"]?.jsonPrimitive?.longOrNull ?: 0
    if (raw["ephemeral"]?.jsonPrimitive?.booleanOrNull == true) return ThreadHistory(raw, metadataSequence, metadataSequence, authoritative = false)
    val paginated = raw.text("historyMode") == "paginated"
    if (paginated) {
        try {
            val page = call("thread/turns/list", buildJsonObject { put("threadId", id); put("limit", 20); put("itemsView", "full"); put("sortDirection", "desc") })
            return ThreadHistory(JsonObject(raw + ("turns" to JsonArray(page.array("data").reversed()))),
                page["_bridgeSequence"]?.jsonPrimitive?.longOrNull ?: metadataSequence, metadataSequence,
                page.text("nextCursor").ifEmpty { null })
        } catch (e: RpcException) { if (!unsupportedHistory(e)) throw e }
    }
    try {
        val response = call("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", true) })
        return ThreadHistory(response.obj("thread"), response["_bridgeSequence"]?.jsonPrimitive?.longOrNull ?: metadataSequence, metadataSequence)
    } catch (e: RpcException) {
        val empty = raw.text("preview").isEmpty() && raw.array("turns").isEmpty()
        if (unsupportedHistory(e) || (empty && e.message.orEmpty().contains("no rollout", true)))
            return ThreadHistory(raw, metadataSequence, metadataSequence, authoritative = false)
        throw e
    }
}

private fun unsupportedHistory(e: RpcException): Boolean = e.code == -32601 || listOf("not supported", "unsupported", "not materialized", "ephemeral threads").any { e.message.orEmpty().contains(it, true) }

fun uncertainDelivery(mutationAttempted: Boolean, acknowledged: Boolean, error: Exception): Boolean =
    mutationAttempted && !acknowledged && error !is RpcException && error !is SessionExpiredException
