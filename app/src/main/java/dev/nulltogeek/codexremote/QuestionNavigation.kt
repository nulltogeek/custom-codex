package dev.nulltogeek.codexremote

internal data class QuestionLink(val id: String, val preview: String)

/** Keep repeated questions separate; identity, not text, is the scroll anchor. */
internal fun questionLinks(messages: List<TimelineItem>, query: String = ""): List<QuestionLink> = messages
    .filter { it.type == "userMessage" }
    .map { item ->
        val preview = item.text.trim().replace(Regex("\\s+"), " ").ifBlank {
            if (item.images.isNotEmpty()) item.images.joinToString(", ") { it.name }
            else "Attached message"
        }
        QuestionLink(item.id, preview)
    }
    .asReversed()
    .filter { query.isBlank() || it.preview.contains(query.trim(), ignoreCase = true) }

/** Resolve against the rendered list, which includes commands and final changes. */
internal fun questionScrollIndex(messages: List<TimelineItem>, id: String): Int? {
    val index = messages.indexOfFirst { it.id == id && it.type == "userMessage" }
    return if (index < 0) null else messages.lastIndex - index
}

internal fun questionScrollOffset(itemHeight: Int, viewportHeight: Int): Int =
    (itemHeight - viewportHeight.coerceAtLeast(1)).coerceAtLeast(0)
