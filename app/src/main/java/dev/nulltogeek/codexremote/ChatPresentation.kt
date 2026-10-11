package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

/** Keep work events in the model for reconciliation, but present only the conversation. */
fun conversationTimeline(state: ScreenState): List<TimelineItem> = state.timeline
    .groupBy { it.turnId }
    .flatMap { (turnId, items) ->
        val running = items.any { it.turnStatus == "inProgress" } ||
            (turnId.isNotEmpty() && state.activeTurnId == turnId) ||
            (items.all { it.turnStatus.isEmpty() } && state.selectedChat?.status == "active" && turnId == state.timeline.lastOrNull()?.turnId)
        val final = items.lastOrNull { it.type == "agentMessage" && it.phase == "final_answer" }
            ?: if (running) null else items.lastOrNull { it.type == "agentMessage" && it.phase.isEmpty() }
        val messages = items.filter { item ->
            item.type in listOf("userMessage", "error") ||
                (item.type == "agentMessage" && (item.id == final?.id ||
                    (turnId.isEmpty() && !running && item.phase.isEmpty())))
        }
        if (running) messages else {
            val aggregate = items.lastOrNull { it.type == "turnDiff" }
            val changes = if (aggregate != null) splitFinalDiff(aggregate.changes.firstOrNull()?.diff.orEmpty())
                else items.filter { it.type == "fileChange" && it.status != "failed" }.flatMap { it.changes }
                    .groupBy { it.path }.map { (path, edits) -> Change(path, edits.map { it.diff }.distinct().joinToString("\n")) }
            messages + if (changes.isEmpty()) emptyList() else listOf(TimelineItem(
                "final-changes:$turnId", "finalChanges", "${changes.size} changed file${if (changes.size == 1) "" else "s"}",
                changes = changes, turnId = turnId, turnStatus = "completed"))
        }
    }

/** The latest turn diff is cumulative; prefer it over individual apply-patch events. */
internal fun splitFinalDiff(diff: String): List<Change> {
    if (diff.isBlank()) return emptyList()
    val starts = Regex("(?m)^diff --git ").findAll(diff).map { it.range.first }.toList()
    if (starts.isEmpty()) return listOf(Change("All changes", diff))
    return starts.mapIndexed { index, start ->
        val patch = diff.substring(start, starts.getOrNull(index + 1) ?: diff.length).trimEnd()
        val path = Regex("(?m)^\\+\\+\\+ b/(.+)$").find(patch)?.groupValues?.get(1)
            ?: Regex("(?m)^--- a/(.+)$").find(patch)?.groupValues?.get(1)
            ?: "All changes"
        Change(path.trimEnd('\r'), patch)
    }
}

/** Retain the cumulative diff through the authoritative history reload and local cache. */
fun retainFinalDiffs(thread: JsonObject, timeline: List<TimelineItem>, previous: JsonObject?): JsonObject {
    val saved = previous?.array("turns").orEmpty().mapNotNull { it as? JsonObject }.filter { it.containsKey("mobileFinalDiff") }
        .associate { it.text("id") to it.text("mobileFinalDiff") }
    val live = timeline.filter { it.type == "turnDiff" }.associate { it.turnId to it.changes.firstOrNull()?.diff.orEmpty() }
    return JsonObject(thread + ("turns" to JsonArray(thread.array("turns").map { value ->
        val turn = value as? JsonObject ?: return@map value
        val diff = live[turn.text("id")] ?: saved[turn.text("id")] ?: turn["mobileFinalDiff"]?.jsonPrimitive?.contentOrNull
        if (diff == null) turn else JsonObject(turn + ("mobileFinalDiff" to JsonPrimitive(diff)))
    })))
}
