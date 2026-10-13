package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

/** Saved history may rematerialize user input with a different item ID. */
internal fun sameUserInput(first: TimelineItem, second: TimelineItem): Boolean {
    fun content(item: TimelineItem) = item.input.map { value ->
        val raw = value as? JsonObject ?: return@map value
        when (raw.text("type")) {
            "text" -> jsonObject("type" to "text", "text" to raw.text("text"))
            "image", "localImage" -> jsonObject("type" to "image", "source" to raw.text("url").ifEmpty { raw.text("path") })
            else -> raw
        }
    }
    return first.type == "userMessage" && second.type == "userMessage" && first.turnId.isNotBlank() &&
        first.turnId == second.turnId && (first.text.isNotEmpty() || first.images.isNotEmpty() || first.input.isNotEmpty()) &&
        first.text == second.text && first.images == second.images && content(first) == content(second)
}

/** Match occurrences one-to-one; equal input in separate turns remains separate. */
internal fun userMessageAliases(history: List<TimelineItem>, observed: List<TimelineItem>,
    reserved: Set<String> = emptySet()): Map<String, String> {
    val shared = observed.map { it.id }.toSet() + reserved
    val savedIds = history.map { it.id }.toSet()
    val available = history.filter { it.type == "userMessage" && it.id !in shared }.toMutableList()
    return buildMap {
        observed.filter { it.type == "userMessage" }.forEach { item ->
            if (item.id in savedIds) return@forEach
            val index = available.indexOfFirst { sameUserInput(it, item) }
            if (index >= 0) put(item.id, available.removeAt(index).id)
        }
    }
}

internal fun canonicalTimelineParams(params: JsonObject, aliases: Map<String, String>): JsonObject {
    val item = params.obj("item")
    val id = item.text("id")
    return if (id !in aliases) params else JsonObject(params + ("item" to JsonObject(item + ("id" to JsonPrimitive(aliases.getValue(id))))))
}
