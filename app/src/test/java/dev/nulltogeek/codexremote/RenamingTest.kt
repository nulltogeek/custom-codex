package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class RenamingTest {
    @Test fun validationAcceptsUnicodeAndRejectsBlankLongOrMultilineNames() {
        assertNull(renameValidation("  پروژه شخصی  "))
        assertNull(renameValidation("x".repeat(200)))
        for (name in listOf("   ", "x".repeat(201), "First\nSecond", "Bad\u0000name")) assertNotNull(renameValidation(name))
    }
    @Test fun renameRequiresAnOnlineSessionButAllowsMetadataChangesDuringWork() {
        val state = ScreenState(signedIn = true, connection = "Connected", sending = true)
        assertTrue(canRename(state))
        assertFalse(canRename(state.copy(signedIn = false)))
        assertFalse(canRename(state.copy(connection = "Offline")))
        assertFalse(canRename(state.copy(loading = true)))
        assertFalse(canRename(state.copy(renaming = true)))
    }
    @Test fun renamingUpdatesSelectedAndFavoriteChatsWithoutChangingHistoryOrIdentity() {
        val chat = Chat("c", "Old", "Preview", "/work", archived = true, projectId = "p")
        val other = chat.copy(id = "other")
        val timeline = listOf(TimelineItem("i", "userMessage", "Original message"))
        val state = ScreenState(chats = listOf(chat, other), favoriteChats = listOf(chat, other), selectedChat = chat,
            timeline = timeline, draft = "Unsent", assignments = mapOf("c" to "p"))
        val renamed = state.withRenamedChat("c", "New")
        assertEquals(chat.copy(title = "New"), renamed.selectedChat)
        assertEquals("New", renamed.chats.first().title)
        assertEquals("New", renamed.favoriteChats.first().title)
        assertEquals(other, renamed.chats.last())
        assertEquals(timeline, renamed.timeline); assertEquals(state.draft, renamed.draft)
        assertEquals(state.assignments, renamed.assignments)
    }
    @Test fun cachedNameSurvivesParsingAndKeepsTheRemainingMetadata() {
        val raw = jsonObject("id" to "c", "name" to "Old", "preview" to "Content", "cwd" to "/work", "cachedArchived" to "true")
        val renamed = renameCachedChat(raw, "New")
        assertEquals("New", parseChat(renamed, true).title)
        assertEquals(raw.filterKeys { it != "name" }, renamed.filterKeys { it != "name" })
    }
}
