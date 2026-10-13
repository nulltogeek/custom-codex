package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LiveChatHistoryTest {
    private fun raw(text: String) = WireJson.parseToJsonElement(text).jsonObject
    private fun cache(maxChats: Int = 8, maxItems: Int = 256) = LiveChatHistory(maxChats, maxItems).apply { activate("account", "epoch") }
    private fun LiveChatHistory.get(id: String = "chat") = snapshot(id, "account", "epoch")
    private fun LiveChatHistory.event(sequence: Long, method: String, params: String, epoch: String = "epoch") = accept(
        buildJsonObject { put("method", method); put("params", raw(params)); put("_bridgeSequence", sequence); put("_bridgeEpoch", epoch) }, "account", "epoch")
    private fun LiveChatHistory.command(sequence: Long, id: String = "cmd", chat: String = "chat", output: String = "prefix") = event(sequence, "item/started",
        """{"threadId":"$chat","turnId":"turn","item":{"id":"$id","type":"commandExecution","command":"run tests","status":"inProgress","aggregatedOutput":"$output"}}""")
    private fun message(id: String, type: String = "agentMessage", status: String = "inProgress", phase: String = "") =
        TimelineItem(id, type, id, turnId = "turn", turnStatus = status, phase = phase)

    @Test fun reopeningRetainsCommandsEvenWhenSavedHistoryHasLaterWatermark() {
        val cache = cache(); cache.command(10)
        val saved = listOf(message("user", "userMessage"))
        repeat(3) {
            assertEquals(listOf("user", "cmd"), cache.get().merge(saved, 100).map { it.id })
        }
        assertEquals("prefix", cache.get().merge(saved, 100).last().text)
    }

    @Test fun outputAndCompletionKeepUpdatingWhileAnotherChatIsOpen() {
        val cache = cache(); cache.command(10); cache.command(11, chat = "other")
        cache.event(12, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":" suffix"}""")
        cache.event(13, "item/completed", """{"threadId":"chat","turnId":"turn","item":{"id":"cmd","type":"commandExecution","status":"completed","command":"run tests","aggregatedOutput":"prefix suffix"}}""")
        cache.event(14, "turn/completed", """{"threadId":"chat","turn":{"id":"turn","status":"completed"}}""")
        val live = cache.get(); val command = live.merge(emptyList(), 100).single()
        assertEquals("prefix suffix", command.text); assertEquals("completed", command.status)
        assertEquals("completed", command.turnStatus); assertEquals("", live.activeTurnId)
        assertEquals("prefix", cache.get("other").timeline.single().text)
    }

    @Test fun replayDoesNotDuplicateCardsOrAppendOutputTwice() {
        val cache = cache(); cache.command(10)
        repeat(2) { cache.event(11, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":" suffix"}""") }
        cache.command(9)
        val merged = cache.get().merge(cache.get().timeline, 0)
        assertEquals(1, merged.size); assertEquals("prefix suffix", merged.single().text)
    }

    @Test fun missingCommandsAreInsertedBetweenUserMessageAndFinalAnswer() {
        val cache = cache(); cache.command(10, "first"); cache.command(11, "second")
        val saved = listOf(message("user", "userMessage"), message("answer", phase = "final_answer"))
        assertEquals(listOf("user", "first", "second", "answer"), cache.get().merge(saved, 100).map { it.id })
    }

    @Test fun savedTerminalTurnWinsOverStaleRunningEvents() {
        val cache = cache(); cache.command(10)
        val saved = listOf(cache.get().timeline.single().copy(turnStatus = "interrupted", status = "completed", text = "saved output"))
        cache.remember("chat", saved, 100, "account", "epoch")
        val snapshot = cache.get()
        assertEquals("", snapshot.activeTurnId)
        assertEquals("interrupted", snapshot.merge(saved, 100).single().turnStatus)
        assertEquals("saved output", snapshot.timeline.single().text)
    }

    @Test fun cachedCompletionWinsOverStaleSavedRunningTurn() {
        val cache = cache(); cache.command(10)
        val saved = cache.get().timeline
        cache.event(11, "turn/completed", """{"threadId":"chat","turn":{"id":"turn","status":"completed"}}""")
        assertEquals("completed", cache.get().merge(saved, 100).single().turnStatus)
    }

    @Test fun newerCommandOutputDoesNotReopenAnAlreadyFinishedSavedTurn() {
        val cache = cache(); cache.command(10)
        cache.event(30, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":" suffix"}""")
        val saved = listOf(cache.get().timeline.single().copy(turnStatus = "completed", text = "prefix"))
        val merged = cache.get().merge(saved, 20).single()
        assertEquals("completed", merged.turnStatus); assertEquals("prefix suffix", merged.text)
    }

    @Test fun idleHistorySettlesMissingActiveCommandWithoutDroppingIt() {
        val cache = cache(); cache.command(10)
        assertEquals("completed", cache.get().merge(emptyList(), 100, running = false).single().turnStatus)
        assertEquals("inProgress", cache.get().merge(emptyList(), 5, running = false).single().turnStatus)
    }

    @Test fun commandOutputPreferenceAndHydrationSurviveMerge() {
        val cache = cache(); cache.command(10)
        val omitted = cache.get().timeline.single().copy(text = "inProgress", commandOutputOmitted = true)
        assertTrue(cache.get().merge(listOf(omitted), 5).single().commandOutputOmitted)
        cache.remember("chat", listOf(omitted), 100, "account", "epoch")
        val hydrated = omitted.copy(text = "full output", commandOutputOmitted = false)
        cache.remember("chat", listOf(hydrated), 110, "account", "epoch")
        assertFalse(cache.get().timeline.single().commandOutputOmitted)
        assertEquals("full output", cache.get().timeline.single().text)
    }

    @Test fun partialSavedCommandCannotEraseAlreadyObservedOutput() {
        val cache = cache(); cache.command(10, output = "complete streamed output")
        val partial = cache.get().timeline.single().copy(text = "partial")
        assertEquals("complete streamed output", cache.get().merge(listOf(partial), 100).single().text)
    }

    @Test fun partialSavedOutputDoesNotDiscardAnEventWaitingInTheActor() {
        val cache = cache(); cache.command(10, output = "complete streamed output")
        val partial = cache.get().timeline.single().copy(text = "partial")
        cache.remember("chat", listOf(partial), 100, "account", "epoch")
        cache.event(20, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":" suffix"}""")
        assertEquals("complete streamed output suffix", cache.get().timeline.single().text)
    }

    @Test fun savedCommandNameHydratesAnEarlyOutputDelta() {
        val cache = cache()
        cache.event(20, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":"output"}""")
        val saved = TimelineItem("cmd", "commandExecution", "", detail = "run tests", turnId = "turn", turnStatus = "inProgress")
        assertEquals("run tests", cache.get().merge(listOf(saved), 10).single().detail)
    }

    @Test fun hydratedCommandPrefixIsKeptForLaterDeltasAndOlderReplayIsIgnored() {
        val cache = cache()
        val saved = TimelineItem("cmd", "commandExecution", "saved prefix", turnId = "turn", turnStatus = "inProgress")
        cache.remember("chat", listOf(saved), 20, "account", "epoch")
        cache.event(19, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":" duplicate"}""")
        cache.event(21, "item/commandExecution/outputDelta", """{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":" suffix"}""")
        assertEquals("saved prefix suffix", cache.get().timeline.single().text)
        // An older replayed command omitted from the read remains eligible.
        cache.command(22, "missing")
        assertEquals(listOf("cmd", "missing"), cache.get().timeline.map { it.id })
    }

    @Test fun terminalStatusWithoutItemsSurvivesUntilHistoryLoads() {
        val cache = cache()
        cache.event(10, "turn/completed", """{"threadId":"chat","turn":{"id":"turn","status":"failed"}}""")
        assertEquals("failed", cache.get().merge(listOf(message("answer")), 100).single().turnStatus)
    }

    @Test fun olderTurnsUseTheirOwnSequenceWhenNewerTurnIsRunning() {
        val cache = cache(); cache.command(10)
        cache.event(30, "turn/started", """{"threadId":"chat","turn":{"id":"new","status":"inProgress"}}""")
        val old = cache.get().timeline.single().copy(turnStatus = "completed")
        assertEquals("completed", cache.get().merge(listOf(old), 20).single().turnStatus)
        assertEquals("new", cache.get().activeTurnId)
    }

    @Test fun sessionAndEpochChangesClearCommandsAndForeignEventsAreRejected() {
        val cache = cache(); cache.command(10); cache.command(11, "second")
        cache.event(12, "turn/completed", """{"threadId":"chat","turn":{"id":"turn","status":"completed"}}""", epoch = "foreign")
        assertEquals("turn", cache.get().activeTurnId)
        assertTrue(cache.snapshot("chat", "other", "epoch").timeline.isEmpty())
        cache.activate("account", "newEpoch"); assertTrue(cache.get().timeline.isEmpty())
        cache.activate("other", "epoch"); cache.command(20)
        assertTrue(cache.snapshot("chat", "other", "epoch").timeline.isEmpty())
    }

    @Test fun deletingOrClearingRemovesCachedCommands() {
        val cache = cache(); cache.command(10); cache.forget("chat"); assertTrue(cache.get().timeline.isEmpty())
        cache.command(11); cache.clear(); assertTrue(cache.get().timeline.isEmpty())
    }

    @Test fun cacheKeepsOnlyBoundedRecentChatsAndItems() {
        val cache = cache(maxChats = 2, maxItems = 2)
        cache.command(1, "one"); cache.command(2, "two"); cache.command(3, "three")
        assertEquals(listOf("two", "three"), cache.get().timeline.map { it.id })
        assertEquals(setOf("two", "three"), cache.get().itemSequences.keys)
        cache.command(4, chat = "second"); cache.command(5, chat = "third")
        assertTrue(cache.get().timeline.isEmpty()); assertEquals(1, cache.get("second").timeline.size)
    }

    @Test fun commandHistoryWindowsCanExpandAfterNavigation() {
        val cache = cache()
        (1..130).forEach { cache.command(it.toLong(), "cmd$it") }
        val all = cache.get().merge(listOf(message("user", "userMessage")), 200)
        val visible = historyWindow(all, emptyList())
        assertEquals(60, visible.size); assertEquals("cmd71", visible.first().id)
        val anchor = all.indexOfFirst { it.id == visible.first().id }
        val expanded = all.take(anchor).takeLast(60) + visible
        assertEquals("cmd11", expanded.first().id); assertEquals(120, expanded.size)
        assertEquals(expanded, historyWindow(cache.get().merge(listOf(message("user", "userMessage")), 300), expanded))
    }
}
