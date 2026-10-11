package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class ModelsTest {
    private fun raw(text: String) = WireJson.parseToJsonElement(text).jsonObject
    @Test fun mixedHistoryPreservesCommandsAndFileDiffs() {
        val timeline = parseTimeline(raw("""{"turns":[{"items":[
            {"id":"u","type":"userMessage","content":[{"type":"text","text":"Fix this"}]},
            {"id":"a","type":"agentMessage","text":"Done"},
            {"id":"c","type":"commandExecution","command":"test","aggregatedOutput":"passed","status":"completed"},
            {"id":"f","type":"fileChange","changes":[{"path":"main.kt","diff":"+fixed"}]}]}]}"""))
        assertEquals(listOf("u", "a", "c", "f"), timeline.map { it.id })
        assertEquals("Fix this", timeline[0].text)
        assertEquals("passed", timeline[2].text)
        assertEquals("+fixed", timeline[3].changes.single().diff)
    }
    @Test fun deltasOnlyChangeTheirTargetAndCompletionIsAuthoritative() {
        val first = TimelineItem("a", "agentMessage", "hello")
        val other = TimelineItem("b", "userMessage", "keep")
        val updated = reduceTimeline(listOf(first, other), "item/agentMessage/delta", raw("""{"itemId":"a","delta":" world"}"""))
        assertEquals("hello world", updated[0].text); assertSame(other, updated[1])
        val completed = reduceTimeline(updated, "item/completed", raw("""{"item":{"id":"a","type":"agentMessage","text":"final"}}"""))
        assertEquals("final", completed[0].text); assertEquals(2, completed.size)
    }
    @Test fun deltaBeforeItemStartStillAppears() {
        val updated = reduceTimeline(emptyList(), "item/agentMessage/delta", raw("""{"itemId":"a","delta":"early"}"""))
        assertEquals("early", updated.single().text)
    }
    @Test fun legacyChildChatsRetainParentAndArchiveState() {
        val chat = parseChat(raw("""{"id":"child","source":{"subagent":{"thread_spawn":{"parent_thread_id":"parent"}}},"preview":"Review files"}"""), true)
        assertEquals("parent", chat.parentId); assertEquals("subAgent", chat.source); assertTrue(chat.archived)
    }
    @Test fun unknownItemsRemainInspectableInsteadOfCrashing() {
        val item = parseItem(raw("""{"id":"future","type":"newTool","status":"completed"}"""))
        assertEquals("completed", item.text)
    }
    @Test fun desktopToolResultsDecodeMultilineContentAndHideMediaPayloads() {
        val dynamic = parseItem(raw("""{"id":"d","type":"dynamicToolCall","tool":"read_file","contentItems":[{"type":"inputText","text":"first\nsecond"},{"type":"inputImage","imageUrl":"data:secret"}]}"""))
        assertEquals("read_file\nfirst\nsecond\n[inputImage attachment]", dynamic.text)
        val mcp = parseItem(raw("""{"id":"m","type":"mcpToolCall","tool":"lookup","result":{"content":[{"type":"text","text":"line 1\nline 2"}]},"error":null}"""))
        assertEquals("lookup\nline 1\nline 2", mcp.text)
        val output = parseItem(raw("""{"id":"f","type":"functionCallOutput","name":"helper","output":"passed\nready"}"""))
        assertEquals("passed\nready", output.text)
    }
    @Test fun failedTurnKeepsBackendErrorInHistory() {
        val items = parseTimeline(raw("""{"turns":[{"id":"t","status":"failed","items":[],"error":{"message":"Model unavailable"}}]}"""))
        assertEquals("turn-error:t", items.single().id)
        assertEquals("Model unavailable", items.single().text)
    }
    @Test fun restartedBackendResetsOldStreamCounter() {
        val cursor = subscriptionCursor("old", 800, raw("""{"epoch":"new","sequence":2,"replayGap":true}"""))
        assertEquals("new" to 2L, cursor)
    }
    @Test fun sameBackendKeepsEventsReceivedDuringSubscribe() {
        val cursor = subscriptionCursor("same", 15, raw("""{"epoch":"same","sequence":12,"replayGap":true}"""))
        assertEquals("same" to 15L, cursor)
    }
    @Test fun laterHistoryPageCannotOverwriteNewerMetadataEvents() {
        val current = ScreenState(selectedChat = Chat("c", "New title", "", "", "idle"), statusSequence = 4, titleSequence = 6)
        val metadata = Chat("c", "Old title", "", "", "active")
        // Metadata was read at 3; a separate history page returned at 5.
        val merged = reconcileChatMetadata(current, metadata, 3)
        assertEquals("idle", merged.selectedChat?.status); assertEquals("New title", merged.selectedChat?.title)
        assertEquals(4L, merged.statusSequence)
    }
    @Test fun resolvedApprovalCannotReturnFromAnOlderHttpSnapshot() {
        val resolved = ScreenState(pendingSequence = 8)
        val stale = raw("""{"sequence":7,"pendingRequests":[{"requestId":"old","method":"approval","params":{}}]}""")
        assertTrue(reconcilePendingSnapshot(resolved, stale).pending.isEmpty())
    }
    @Test fun newerSnapshotRestoresAuthoritativePendingRequests() {
        val snapshot = raw("""{"sequence":9,"pendingRequests":[{"requestId":"new","method":"approval","params":{}}]}""")
        val refreshed = reconcilePendingSnapshot(ScreenState(pendingSequence = 8), snapshot)
        assertEquals("new", refreshed.pending.single().id); assertEquals(9L, refreshed.pendingSequence)
    }
}
