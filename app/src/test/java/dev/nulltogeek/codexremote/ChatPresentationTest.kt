package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatPresentationTest {
    private fun raw(value: String) = WireJson.parseToJsonElement(value).jsonObject
    private fun history(status: String = "completed") = parseTimeline(raw("""{"turns":[{"id":"t1","status":"$status","items":[
        {"id":"u","type":"userMessage","content":[{"type":"text","text":"Fix the app"}]},
        {"id":"r","type":"reasoning","summary":["Private work"]},
        {"id":"c","type":"commandExecution","command":"test","aggregatedOutput":"passed"},
        {"id":"a","type":"agentMessage","phase":"commentary","text":"Working on it"},
        {"id":"f","type":"fileChange","status":"completed","changes":[{"path":"app.kt","diff":"+fixed"}]},
        {"id":"z","type":"agentMessage","phase":"final_answer","text":"Done"}
    ]}]}"""))
    @Test fun completedTurnShowsOnlyUserFinalAnswerAndOneChangesSummary() {
        val items = conversationTimeline(ScreenState(timeline = history()))
        assertEquals(listOf("u", "z", "final-changes:t1"), items.map { it.id })
        assertEquals("app.kt", items.last().changes.single().path)
    }
    @Test fun activeTurnKeepsReasoningCommentaryCommandsAndChangesHidden() {
        val items = conversationTimeline(ScreenState(timeline = history("inProgress"), activeTurnId = "t1"))
        assertEquals(listOf("u", "z"), items.map { it.id })
        assertFalse(items.any { it.changes.isNotEmpty() })
    }
    @Test fun legacyMessagesWithoutPhaseShowOnlyTheLastAnswerAfterCompletion() {
        val timeline = parseTimeline(raw("""{"turns":[{"id":"old","status":"inProgress","items":[
            {"id":"u","type":"userMessage","content":[{"type":"text","text":"Hello"}]},
            {"id":"a","type":"agentMessage","text":"Let me check"},
            {"id":"b","type":"agentMessage","text":"Finished"}]}]}"""))
        assertEquals(listOf("u"), conversationTimeline(ScreenState(timeline = timeline)).map { it.id })
        val complete = reduceTimeline(timeline, "turn/completed", raw("""{"turn":{"id":"old","status":"completed"}}"""))
        assertEquals(listOf("u", "b"), conversationTimeline(ScreenState(timeline = complete)).map { it.id })
    }
    @Test fun cumulativeDiffSupersedesIntermediatePatchesAndStaysAfterTheAnswer() {
        val diff = "diff --git a/app.kt b/app.kt\n--- a/app.kt\n+++ b/app.kt\n@@ -1 +1 @@\n-old\n+final"
        val updated = reduceTimeline(history("inProgress"), "turn/diff/updated", buildJsonObject { put("turnId", "t1"); put("diff", diff) })
        val complete = reduceTimeline(updated, "turn/completed", raw("""{"turn":{"id":"t1","status":"completed"}}"""))
        val items = conversationTimeline(ScreenState(timeline = complete))
        assertEquals(listOf("u", "z", "final-changes:t1"), items.map { it.id })
        assertEquals(diff, items.last().changes.single().diff)
        assertEquals("app.kt", items.last().changes.single().path)
    }
    @Test fun emptyCumulativeDiffSuppressesChangesThatWereUndone() {
        val updated = reduceTimeline(history(), "turn/diff/updated", raw("""{"turnId":"t1","diff":""}"""))
        val complete = reduceTimeline(updated, "turn/completed", raw("""{"turn":{"id":"t1","status":"completed"}}"""))
        assertEquals(listOf("u", "z"), conversationTimeline(ScreenState(timeline = complete)).map { it.id })
    }
    @Test fun finalDiffSurvivesHistoryReloadAndCachedReopen() {
        val thread = raw("""{"turns":[{"id":"t1","status":"completed","items":[{"id":"a","type":"agentMessage","text":"Done"}]}]}""")
        val live = reduceTimeline(emptyList(), "turn/diff/updated", raw("""{"turnId":"t1","diff":"+final"}"""))
        val saved = retainFinalDiffs(thread, live, null)
        val reopened = retainFinalDiffs(thread, emptyList(), saved)
        assertEquals("+final", conversationTimeline(ScreenState(timeline = parseTimeline(reopened))).last().changes.single().diff)
    }
    @Test fun legacyCacheWithoutCumulativeDiffKeepsItsSavedFileChanges() {
        val thread = raw("""{"turns":[{"id":"old","status":"completed","items":[
            {"id":"f","type":"fileChange","changes":[{"path":"app.kt","diff":"+fixed"}]}]}]}""")
        val reloaded = retainFinalDiffs(thread, emptyList(), thread)
        assertEquals("+fixed", conversationTimeline(ScreenState(timeline = parseTimeline(reloaded))).last().changes.single().diff)
    }
    @Test fun finalChangesRemainWithTheirOwnTurnAndFailuresRemainVisible() {
        val next = parseTimeline(raw("""{"turns":[{"id":"t2","status":"failed","items":[
            {"id":"u2","type":"userMessage","content":[{"type":"text","text":"Next"}]}],"error":{"message":"Unavailable"}}]}"""))
        assertEquals(listOf("u", "z", "final-changes:t1", "u2", "turn-error:t2"),
            conversationTimeline(ScreenState(timeline = history() + next)).map { it.id })
    }
    @Test fun imageInputsKeepTheirSourceInsteadOfBecomingPlaceholderText() {
        val item = parseItem(raw("""{"id":"u","type":"userMessage","content":[
            {"type":"text","text":"Look at this"},{"type":"localImage","path":"/uploads/photo.jpg"},
            {"type":"image","url":"https://example.invalid/image.png"}]}"""))
        assertEquals("Look at this", item.text)
        assertEquals(listOf("/uploads/photo.jpg", "https://example.invalid/image.png"), item.images.map { it.source })
    }
}
