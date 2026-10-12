package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class NoticesTest {
    private val connection = NoticeKey(NoticeKind.Connection)
    private val search = NoticeKey(NoticeKind.Search)
    @Test fun connectionRecoveryClearsOnlyItsProblemAndPreservesUnconfirmedDelivery() {
        val warning = "Delivery could not be confirmed. Check history before resending."
        val state = ScreenState(connection = "Offline", notice = warning, uncertainSend = true)
            .withInfoNotice(connection, "Connection lost")
            .withInfoNotice(search, "Search unavailable")
        val recovered = state.withConnectionStatus("Connected")
        assertFalse(recovered.infoNotices.containsKey(connection))
        assertEquals("Search unavailable", recovered.infoNotices[search])
        assertEquals(warning, recovered.notice); assertTrue(recovered.uncertainSend)
    }
    @Test fun connectingAndAnotherFailureKeepTheWarningUntilRecovery() {
        var state = ScreenState().withInfoNotice(connection, "Connection lost")
        for (status in listOf("Connecting", "Offline", "Connection lost", "Session expired")) {
            state = state.withConnectionStatus(status)
            assertEquals("Connection lost", state.infoNotices[connection])
        }
        state = state.withConnectionStatus("Connected").withInfoNotice(connection, "Cannot reach host")
        assertEquals("Cannot reach host", state.infoNotices[connection])
        assertTrue(state.withConnectionStatus("Connected").infoNotices.isEmpty())
    }
    @Test fun historyRecoveryIsScopedToTheChatThatSucceeded() {
        val first = NoticeKey(NoticeKind.History, "first"); val second = NoticeKey(NoticeKind.History, "second")
        val state = ScreenState().withInfoNotice(first, "History unavailable").withInfoNotice(second, "Other history unavailable")
        val resolved = state.resolveInfoNotice(first)
        assertEquals(mapOf(second to "Other history unavailable"), resolved.infoNotices)
    }
    @Test fun updatingAndDismissingOneInfoNoticePreservesTheOthers() {
        val state = ScreenState(notice = "An action failed")
            .withInfoNotice(connection, "Old connection problem").withInfoNotice(search, "Search unavailable")
            .withInfoNotice(connection, "New connection problem")
        assertEquals(2, state.infoNotices.size)
        assertEquals("New connection problem", state.infoNotices[connection])
        val dismissed = state.resolveInfoNotice(connection)
        assertEquals("Search unavailable", dismissed.infoNotices[search]); assertEquals("An action failed", dismissed.notice)
    }
    @Test fun queueWarningClearsAfterEditRetryOrRemovalButNotWhileDeliveryIsUncertain() {
        val key = NoticeKey(NoticeKind.QueueDelivery, "chat")
        val state = ScreenState().withInfoNotice(key, "Check queued delivery")
        for (message in listOf(QueuedMessage("m", "chat", "Prompt", error = "Rejected"),
            QueuedMessage("m", "chat", "Prompt", delivery = "uncertain"))) {
            assertTrue(state.resolveQueueNotices(QueueSnapshot(listOf(message))).infoNotices.containsKey(key))
        }
        assertFalse(state.resolveQueueNotices(QueueSnapshot(listOf(QueuedMessage("m", "chat", "Revised")))).infoNotices.containsKey(key))
        assertFalse(state.resolveQueueNotices(QueueSnapshot()).infoNotices.containsKey(key))
    }
    @Test fun anotherChatsQueueErrorAndStorageProblemAreNotClearedByRecovery() {
        val key = NoticeKey(NoticeKind.QueueDelivery, "other")
        val storage = NoticeKey(NoticeKind.QueueStorage)
        val state = ScreenState().withInfoNotice(key, "Other queue problem").withInfoNotice(storage, "Could not save queue")
        val snapshot = QueueSnapshot(listOf(QueuedMessage("m", "other", "Prompt", error = "Still rejected")))
        assertEquals(state.infoNotices, state.resolveQueueNotices(snapshot).infoNotices)
        assertEquals(mapOf(key to "Other queue problem"), state.resolveInfoNotice(storage).infoNotices)
    }
}
