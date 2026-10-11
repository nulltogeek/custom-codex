package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadHistoryTest {
    private fun raw(text: String) = WireJson.parseToJsonElement(text).jsonObject

    @Test fun legacyHistoryDoesNotCallUnsupportedPagination() = runBlocking {
        val calls = mutableListOf<String>()
        val loaded = readThreadHistory("t") { method, params ->
            calls += method
            assertEquals("thread/read", method)
            if (params["includeTurns"]?.jsonPrimitive?.boolean == false) raw("""{"thread":{"id":"t","historyMode":"legacy"},"_bridgeSequence":3}""")
            else raw("""{"thread":{"id":"t","turns":[{"items":[{"id":"a","type":"agentMessage","text":"saved"}]}]},"_bridgeSequence":4}""")
        }
        assertEquals(listOf("thread/read", "thread/read"), calls)
        assertEquals("saved", parseTimeline(loaded.thread).single().text); assertTrue(loaded.authoritative)
    }

    @Test fun freshChatsWithNoMaterializedTurnsRemainUsable() = runBlocking {
        val loaded = readThreadHistory("t") { _, params ->
            if (params["includeTurns"]?.jsonPrimitive?.boolean == false) raw("""{"thread":{"id":"t","historyMode":"legacy","preview":"","turns":[]}}""")
            else throw RpcException(-32600, "list_turns is not supported yet")
        }
        assertEquals("t", loaded.thread.text("id")); assertFalse(loaded.authoritative); assertNull(loaded.olderCursor)
    }

    @Test fun ephemeralThreadsUseLiveEventsWithoutTryingToHydrateSavedTurns() = runBlocking {
        var calls = 0
        val loaded = readThreadHistory("t") { _, params ->
            calls++; assertFalse(params["includeTurns"]!!.jsonPrimitive.boolean)
            raw("""{"thread":{"id":"t","ephemeral":true},"_bridgeSequence":8}""")
        }
        assertEquals(1, calls); assertFalse(loaded.authoritative)
    }

    @Test fun paginatedThreadsReversePagesAndKeepTheCursor() = runBlocking {
        val loaded = readThreadHistory("t") { method, _ ->
            if (method == "thread/read") raw("""{"thread":{"id":"t","historyMode":"paginated"},"_bridgeSequence":3}""")
            else raw("""{"data":[{"id":"new"},{"id":"old"}],"nextCursor":"older","_bridgeSequence":7}""")
        }
        assertEquals(listOf("old", "new"), loaded.thread.array("turns").map { it.jsonObject.text("id") })
        assertEquals("older", loaded.olderCursor); assertEquals(7L, loaded.sequence)
    }

    @Test fun realConnectionErrorsAreNotTreatedAsHistoryCompatibility() = runBlocking {
        val failure = runCatching { readThreadHistory("t") { _, _ -> throw IOException("Connection lost") } }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test fun deliveryLockOnlyAppliesBeforeAnUnacknowledgedMutation() {
        val historyError = RpcException(-32600, "list_turns is not supported yet")
        assertFalse(uncertainDelivery(true, true, historyError))
        assertFalse(uncertainDelivery(false, false, IOException("Connection lost")))
        assertFalse(uncertainDelivery(true, false, RpcException(-32602, "Rejected input")))
        assertTrue(uncertainDelivery(true, false, IOException("Connection lost")))
        assertFalse(uncertainDelivery(true, true, IOException("History refresh disconnected")))
    }
    @Test fun largeLegacyHistoryStartsWithLatestBatchAndKeepsExpandedHistoryOnRefresh() {
        val all = (1..200).map { TimelineItem("m$it", "agentMessage", "Message $it") }
        val initial = historyWindow(all, emptyList())
        assertEquals(60, initial.size); assertEquals("m141", initial.first().id)
        val expanded = all.take(140).takeLast(60) + initial
        assertEquals(120, expanded.size); assertEquals("m81", expanded.first().id)
        val refreshed = historyWindow(all + TimelineItem("m201", "agentMessage", "Live reply"), expanded)
        assertEquals("m81", refreshed.first().id); assertEquals("m201", refreshed.last().id)
        assertEquals(refreshed.size, refreshed.map { it.id }.distinct().size)
        assertEquals(all, historyWindow(all, all))
    }

    @Test fun attachmentPreviewRoundTripsWithoutBreakingOlderDrafts() {
        val old = raw("""{"id":"f","name":"old.txt","size":1,"mimeType":"text/plain","input":{"type":"text","text":"attached"}}""")
        assertEquals("", parseAttachment(old).previewPath)
        val image = parseAttachment(old).copy(previewPath = "/private/cache/thumb.jpg")
        assertEquals(image, parseAttachment(image.toJson()))
        assertFalse(parseAttachment(image.toJson()).uploading)
    }

}
