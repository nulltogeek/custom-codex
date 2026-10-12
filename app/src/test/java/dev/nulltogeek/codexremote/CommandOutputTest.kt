package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandOutputTest {
    private fun raw(text: String) = WireJson.parseToJsonElement(text).jsonObject
    @Test fun omittedHistoryPreservesCommandIdentityAndStatus() {
        val item = parseItem(raw("""{"id":"cmd","type":"commandExecution","command":"run tests","status":"completed","_commandOutputOmitted":true}"""), "turn", "completed")
        assertTrue(item.commandOutputOmitted)
        assertEquals("run tests", item.detail); assertEquals("completed", item.status)
        assertEquals("turn", item.turnId); assertEquals("completed", item.turnStatus)
        assertFalse(parseItem(raw("""{"id":"a","type":"agentMessage","text":"hello","_commandOutputOmitted":true}""")).commandOutputOmitted)
    }
    @Test fun omittedDeltasMarkExistingOrNewCommandsWithoutLosingOtherItems() {
        val command = TimelineItem("cmd", "commandExecution", "earlier output", "run tests", status = "inProgress", turnId = "turn")
        val answer = TimelineItem("a", "agentMessage", "hello")
        val params = raw("""{"threadId":"chat","turnId":"turn","itemId":"cmd","delta":"","_commandOutputOmitted":true}""")
        val items = reduceTimeline(listOf(command, answer), "item/commandExecution/outputDelta", params)
        assertTrue(items[0].commandOutputOmitted); assertEquals("run tests", items[0].detail)
        assertEquals("earlier output", items[0].text); assertSame(answer, items[1])
        val early = reduceTimeline(emptyList(), "item/commandExecution/outputDelta", params).single()
        assertTrue(early.commandOutputOmitted); assertEquals("commandExecution", early.type)
        assertEquals("turn", early.turnId); assertEquals("", early.text)
    }
    @Test fun fullCompletionRestoresOutputAfterPreferenceIsEnabled() {
        val partial = TimelineItem("cmd", "commandExecution", "", commandOutputOmitted = true)
        val completed = reduceTimeline(listOf(partial), "item/completed",
            raw("""{"turnId":"turn","item":{"id":"cmd","type":"commandExecution","command":"run tests","status":"completed","aggregatedOutput":"all output"}}""")).single()
        assertFalse(completed.commandOutputOmitted); assertEquals("all output", completed.text)
        assertEquals("completed", completed.status)
    }
}
