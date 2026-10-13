package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

/** Observed events can be newer/more complete than a running turn's saved history. */
internal data class LiveChatSnapshot(
    val timeline: List<TimelineItem> = emptyList(), val itemSequences: Map<String, Long> = emptyMap(),
    val sequence: Long = 0, val turnSequence: Long = 0, val activeTurnId: String = "",
    val turnStatuses: Map<String, String> = emptyMap(),
    val turnSequences: Map<String, Long> = emptyMap(),
) {
    fun merge(history: List<TimelineItem>, cutoff: Long, running: Boolean? = null): List<TimelineItem> {
        val historyStatuses = history.associate { it.turnId to it.turnStatus }
        val turns = (history.map { it.turnId } + timeline.map { it.turnId }).distinct()
        return turns.flatMap { turn ->
            val local = timeline.filter { it.turnId == turn }
            val merged = history.filter { it.turnId == turn }.toMutableList()
            local.forEachIndexed { index, observed ->
                val existing = merged.indexOfFirst { it.id == observed.id }
                if (existing >= 0) {
                    val saved = merged[existing]
                    val newer = (itemSequences[observed.id] ?: 0) > cutoff
                    // A snapshot watermark does not guarantee that active command output was saved.
                    val fullerCommand = saved.type == "commandExecution" && saved.turnStatus == "inProgress" &&
                        !saved.commandOutputOmitted && !observed.commandOutputOmitted &&
                        (observed.text.length > saved.text.length ||
                            (saved.status in listOf("inProgress", "running") && observed.status in listOf("completed", "failed", "declined")))
                    if (newer || fullerCommand) merged[existing] = observed.copy(
                        detail = observed.detail.ifEmpty { saved.detail },
                        commandOutputOmitted = observed.commandOutputOmitted || saved.commandOutputOmitted)
                } else {
                    val next = local.drop(index + 1).firstNotNullOfOrNull { next ->
                        merged.indexOfFirst { it.id == next.id }.takeIf { it >= 0 }
                    }
                    val previous = local.take(index).asReversed().firstNotNullOfOrNull { previous ->
                        merged.indexOfFirst { it.id == previous.id }.takeIf { it >= 0 }?.plus(1)
                    }
                    val finalAnswer = merged.indexOfFirst { it.type == "agentMessage" && it.phase == "final_answer" }.takeIf { it >= 0 }
                    merged.add(next ?: previous ?: finalAnswer ?: merged.size, observed)
                }
            }
            val savedStatus = historyStatuses[turn]
            val localStatus = turnStatuses[turn]
            val turnVersion = turnSequences[turn] ?: turnSequence
            val status = when {
                turnVersion <= cutoff && savedStatus in terminalTurnStatuses -> savedStatus
                localStatus in terminalTurnStatuses -> localStatus
                running == false && turn == activeTurnId && turnVersion <= cutoff -> "completed"
                else -> localStatus ?: savedStatus
            }
            merged.map { if (status == null) it else it.copy(turnStatus = status) }
        }
    }
}

private val terminalTurnStatuses = setOf("completed", "failed", "interrupted")

/** Small session-only cache; no extra RPCs or command-output downloads. */
internal class LiveChatHistory(private val maxChats: Int = 8, private val maxItems: Int = 256) {
    private var session = ""
    private var epoch = ""
    private val chats = linkedMapOf<String, LiveChatSnapshot>()
    @Synchronized fun activate(session: String, epoch: String) {
        if (this.session != session || this.epoch != epoch) chats.clear()
        this.session = session; this.epoch = epoch
    }
    @Synchronized fun clear() { chats.clear(); session = ""; epoch = "" }
    @Synchronized fun forget(id: String) { chats.remove(id) }
    @Synchronized fun snapshot(id: String, session: String, epoch: String): LiveChatSnapshot =
        if (this.session == session && this.epoch == epoch) chats[id] ?: LiveChatSnapshot() else LiveChatSnapshot()

    /** Seed saved prefixes so later output deltas append to the complete command. */
    @Synchronized fun remember(id: String, history: List<TimelineItem>, cutoff: Long, session: String, epoch: String) {
        if (this.session != session || this.epoch != epoch) return
        val before = chats[id] ?: LiveChatSnapshot()
        val merged = before.merge(history, cutoff).takeLast(maxItems)
        val savedItems = history.associateBy { it.id }
        val versions = merged.associate { item ->
            val saved = savedItems[item.id]
            // Do not give incomplete saved output a watermark it did not represent.
            val represented = saved != null && item.text == saved.text && item.status == saved.status &&
                item.commandOutputOmitted == saved.commandOutputOmitted
            item.id to maxOf(before.itemSequences[item.id] ?: 0, if (represented) cutoff else 0)
        }
        val statuses = before.turnStatuses + merged.associate { it.turnId to it.turnStatus }
        val savedTurns = history.map { it.turnId }.toSet()
        val turnVersions = before.turnSequences + savedTurns.associateWith { maxOf(cutoff, before.turnSequences[it] ?: 0) }
        val active = before.activeTurnId.takeIf { statuses[it] == "inProgress" }
            ?: merged.lastOrNull { it.turnStatus == "inProgress" }?.turnId.orEmpty()
        retain(id, before.copy(timeline = merged, itemSequences = versions, activeTurnId = active,
            turnStatuses = statuses, turnSequences = turnVersions))
    }

    @Synchronized fun accept(event: JsonObject, session: String, epoch: String) {
        if (this.session != session || this.epoch != epoch ||
            (event.text("_bridgeEpoch").isNotEmpty() && event.text("_bridgeEpoch") != epoch)) return
        val method = event.text("method")
        if (method !in listOf("turn/started", "turn/completed", "turn/diff/updated", "item/started", "item/completed",
            "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta")) return
        val params = event.obj("params")
        val id = params.text("threadId")
        if (id.isBlank()) return
        val before = chats[id] ?: LiveChatSnapshot()
        val sequence = event["_bridgeSequence"]?.jsonPrimitive?.longOrNull ?: 0
        if (sequence <= before.sequence) return
        val turn = params.text("turnId").ifEmpty { params.obj("turn").text("id") }.ifEmpty { before.activeTurnId }
        var active = before.activeTurnId
        var statuses = before.turnStatuses
        var turnSequence = before.turnSequence
        var turnVersions = before.turnSequences
        if (turn.isNotEmpty() && sequence > (before.turnSequences[turn] ?: 0) &&
            (method == "turn/started" || method == "turn/completed" || turn !in statuses)) {
            val status = if (method == "turn/completed") params.obj("turn").text("status", "completed") else "inProgress"
            statuses = statuses + (turn to status)
            active = if (status == "inProgress") turn else if (active == turn) "" else active
            turnSequence = sequence
            turnVersions = turnVersions + (turn to sequence)
        }
        val item = params.text("itemId").ifEmpty { params.obj("item").text("id") }
        val alreadySaved = item.isNotEmpty() && sequence <= (before.itemSequences[item] ?: 0)
        val items = (if (alreadySaved) before.timeline else reduceTimeline(before.timeline, method, params, before.activeTurnId)).takeLast(maxItems)
            .map { item -> statuses[item.turnId]?.let { item.copy(turnStatus = it) } ?: item }
        val versions = if (item.isEmpty() || alreadySaved) before.itemSequences else before.itemSequences + (item to sequence)
        retain(id, LiveChatSnapshot(items, versions, sequence, turnSequence, active,
            statuses, turnVersions))
    }

    private fun retain(id: String, snapshot: LiveChatSnapshot) {
        val ids = snapshot.timeline.map { it.id }.toSet()
        val retainedTurns = snapshot.timeline.map { it.turnId }.toSet() + snapshot.activeTurnId + snapshot.turnStatuses.keys.lastOrNull().orEmpty()
        chats.remove(id)
        chats[id] = snapshot.copy(itemSequences = snapshot.itemSequences.filterKeys { it in ids },
            turnStatuses = snapshot.turnStatuses.filterKeys { it in retainedTurns },
            turnSequences = snapshot.turnSequences.filterKeys { it in retainedTurns })
        while (chats.size > maxChats) chats.remove(chats.keys.first())
    }
}
