package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class LibraryControlsTest {
    @Test fun archiveIsUnavailableWhileOfflineRunningSendingOrAwaitingInput() {
        val chat = Chat("c", "Title", "", "", status = "idle")
        val connected = ScreenState(connection = "Connected")
        assertTrue(canArchive(connected, chat))
        assertTrue(canArchive(connected, chat.copy(archived = true)))
        assertFalse(canArchive(connected.copy(connection = "Offline"), chat))
        assertFalse(canArchive(connected.copy(loading = true), chat))
        assertFalse(canArchive(connected.copy(sending = true), chat))
        assertFalse(canArchive(connected, chat.copy(status = "active")))
        assertFalse(canArchive(connected.copy(pending = listOf(PendingInput("r", "request", jsonObject("threadId" to "c")))), chat))
    }
    @Test fun deleteRequiresAnIdleArchivedChatAndAnEmptyQueue() {
        val chat = Chat("c", "Title", "", "", archived = true)
        val state = ScreenState(connection = "Connected")
        assertTrue(canDeleteChat(state, chat))
        assertFalse(canDeleteChat(state, chat.copy(archived = false)))
        assertFalse(canDeleteChat(state, chat.copy(status = "active")))
        assertFalse(canDeleteChat(state.copy(connection = "Offline"), chat))
        assertFalse(canDeleteChat(state.copy(queuedMessages = listOf(QueuedMessage("q", "c", "next"))), chat))
        assertTrue(canDeleteChat(state.copy(queuedMessages = listOf(QueuedMessage("q", "other", "next"))), chat))
    }
    @Test fun deletionClearsTheSelectedChatAndItsFavoritesButKeepsOtherConversations() {
        val chat = Chat("c", "Title", "", "", archived = true)
        val other = chat.copy(id = "other")
        val state = ScreenState(chats = listOf(chat, other), favoriteChats = listOf(chat, other), selectedChat = chat,
            timeline = listOf(TimelineItem("i", "agentMessage", "answer")), draft = "draft", diff = Change("a", "diff"),
            assignments = mapOf("c" to "p", "other" to "p"), historyRevision = 4, catalogRevision = 7, activeCursor = "50", archivedCursor = "100")
        val deleted = withoutDeletedChat(state, "c")
        assertEquals(listOf(other), deleted.chats); assertEquals(listOf(other), deleted.favoriteChats)
        assertNull(deleted.selectedChat); assertNull(deleted.diff); assertTrue(deleted.timeline.isEmpty())
        assertEquals("", deleted.draft); assertEquals("archived", deleted.page); assertEquals(5, deleted.historyRevision)
        assertEquals(mapOf("other" to "p"), deleted.assignments)
        assertEquals(8, deleted.catalogRevision); assertNull(deleted.activeCursor); assertNull(deleted.archivedCursor)
        assertEquals(state.timeline, withoutDeletedChat(state, "other").timeline)
    }
    @Test fun passwordValidationChecksMatchAndUtf8LimitWithoutTrimmingSpaces() {
        assertNotNull(passwordValidation("", "long-new-password", "long-new-password"))
        assertNotNull(passwordValidation("old-password", "short", "short"))
        assertNotNull(passwordValidation("old-password", "long-new-password", "different"))
        assertNotNull(passwordValidation("same-password", "same-password", "same-password"))
        assertNotNull(passwordValidation("old-password", "é".repeat(600), "é".repeat(600)))
        assertNull(passwordValidation("old-password", "  new password  ", "  new password  "))
        assertNull(passwordValidation("old-password", "é".repeat(512), "é".repeat(512)))
    }
}
