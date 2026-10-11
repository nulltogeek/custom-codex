package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskCompletionTest {
    private fun event(thread: String = "chat", turn: String = "turn", status: String = "completed", method: String = "turn/completed", error: Boolean = false) = buildJsonObject {
        put("method", method); put("params", buildJsonObject {
            put("threadId", thread); put("turn", buildJsonObject {
                put("id", turn); put("status", status)
                if (error) put("error", jsonObject("message" to "failed"))
            })
        })
    }
    @Test fun onlySuccessfulCompletedTasksAlert() {
        assertEquals(TaskCompletion("chat", "turn"), taskCompletion(event()))
        for (status in listOf("inProgress", "failed", "interrupted", "")) assertNull(taskCompletion(event(status = status)))
        assertNull(taskCompletion(event(method = "item/completed")))
        assertNull(taskCompletion(event(thread = ""))); assertNull(taskCompletion(event(turn = "")))
        assertNull(taskCompletion(event(error = true)))
    }
    @Test fun duplicateEventsAndRelaunchDoNotAlertAgainButOtherChatsAndTurnsDo() {
        var saved = emptyList<String>()
        val tracker = CompletionTracker { saved = it }
        assertNotNull(tracker.accept(event())); assertNull(tracker.accept(event()))
        assertNull(CompletionTracker(saved).accept(event()))
        assertNotNull(tracker.accept(event(turn = "next")))
        assertNotNull(tracker.accept(event(thread = "other")))
        assertEquals(3, saved.size)
    }
    @Test fun identitiesCannotCollideAndFailedEventsDoNotConsumeASuccess() {
        assertNotEquals(TaskCompletion("a", "b\nc").key, TaskCompletion("a\nb", "c").key)
        val tracker = CompletionTracker()
        assertNull(tracker.accept(event(status = "failed")))
        assertNotNull(tracker.accept(event()))
    }
    @Test fun notificationLinksAndIdentitiesAreScopedToTheCurrentConnectedAccount() {
        val state = ScreenState(signedIn = true, connection = "Connected", endpoint = "https://host", username = "owner")
        assertTrue(notificationAccountMatches(state, "https://host", "owner"))
        assertFalse(notificationAccountMatches(state.copy(signedIn = false), "https://host", "owner"))
        assertFalse(notificationAccountMatches(state.copy(connection = "Offline"), "https://host", "owner"))
        assertFalse(notificationAccountMatches(state, "https://other", "owner"))
        assertFalse(notificationAccountMatches(state, "https://host", "other"))
        val completion = TaskCompletion("chat", "turn")
        assertNotEquals(completion.notificationKey("https://host", "owner"), completion.notificationKey("https://other", "owner"))
        assertNotEquals(completion.notificationKey("https://host", "owner"), completion.notificationKey("https://host", "other"))
    }
    @Test fun storageFailureDoesNotMarkDeliveredAndHistoryIsBounded() {
        var fail = true; var saved = emptyList<String>()
        val tracker = CompletionTracker { if (fail) throw IllegalStateException("storage full"); saved = it }
        try { tracker.accept(event()); fail("Expected storage failure") } catch (_: IllegalStateException) { }
        fail = false; assertNotNull(tracker.accept(event()))
        repeat(300) { tracker.accept(event(turn = "turn-$it")) }
        assertEquals(256, saved.size); assertNull(CompletionTracker(saved).accept(event(turn = "turn-299")))
    }
}
