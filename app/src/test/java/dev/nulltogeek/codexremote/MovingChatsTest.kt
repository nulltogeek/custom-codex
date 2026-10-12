package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class MovingChatsTest {
    @Test fun moveUpdatesEveryChatViewAndKeepsIdentityHistoryDraftQueueAndWorkingFolder() {
        val chat = Chat("c", "Title", "Preview", "/old", archived = true, projectId = "old")
        val other = chat.copy(id = "other")
        val state = ScreenState(chats = listOf(chat, other), selectedChat = chat, favoriteChats = listOf(chat),
            assignments = mapOf("c" to "old", "other" to "old"), timeline = listOf(TimelineItem("i", "userMessage", "History")),
            draft = "Draft", queuedMessages = listOf(QueuedMessage("q", "c", "Queued")))
        val moved = state.withMovedChat("c", "new")
        assertEquals(chat.copy(projectId = "new"), moved.selectedChat)
        assertEquals(moved.selectedChat, moved.chats.first()); assertEquals(moved.selectedChat, moved.favoriteChats.first())
        assertEquals("new", moved.assignments["c"]); assertEquals("old", moved.assignments["other"])
        assertEquals(other, moved.chats.last()); assertEquals(state.timeline, moved.timeline)
        assertEquals(state.draft, moved.draft); assertEquals(state.queuedMessages, moved.queuedMessages)
    }
    @Test fun explicitAssignmentWinsOverOldMetadataAndFolderInference() {
        val chat = Chat("c", "Title", "", "/old", projectId = "old")
        val old = Project("old", "Old", listOf("/old")); val next = Project("next", "Next", listOf("/different"))
        val assignments = mapOf("c" to "next")
        assertFalse(chatBelongsToProject(chat, old, assignments))
        assertTrue(chatBelongsToProject(chat, next, assignments))
        assertFalse(chatBelongsToProject(chat.copy(projectId = "next"), old, emptyMap()))
        assertTrue(chatBelongsToProject(chat.copy(projectId = null), old, emptyMap()))
    }
    @Test fun projectRemovalEventsClearAllCopiesWithoutChangingOtherChats() {
        val chat = Chat("c", "Title", "", "/old", projectId = "old")
        val state = ScreenState(chats = listOf(chat), selectedChat = chat, favoriteChats = listOf(chat), assignments = mapOf("c" to "old", "other" to "old"))
        val cleared = state.withMovedChat("c", null)
        assertNull(cleared.chats.first().projectId); assertNull(cleared.selectedChat?.projectId)
        assertNull(cleared.favoriteChats.first().projectId); assertFalse(cleared.assignments.containsKey("c"))
        assertEquals("old", cleared.assignments["other"])
    }
    @Test fun cacheUpdatesKeepHistoryAndOtherProjectAssignments() {
        val raw = jsonObject("id" to "c", "name" to "Title", "cwd" to "/old", "projectId" to "old")
        val moved = cachedChatWithProject(raw, "new")
        assertEquals("new", parseChat(moved).projectId)
        assertEquals(raw.filterKeys { it != "projectId" }, moved.filterKeys { it != "projectId" })
        val inventory = inventoryWithChatProject(inventoryWithChatProject(jsonObject(), "other", "old"), "c", "new")
        assertEquals("new", inventory.obj("assignments").text("c")); assertEquals("old", inventory.obj("assignments").text("other"))
    }
    @Test fun moveRequestOnlyChangesTheChatProject() {
        assertEquals(jsonObject("threadId" to "c", "projectId" to "p"), chatMoveParams("c", "p"))
    }
    @Test fun moveRequiresConnectionAndPreventsConcurrentMetadataOperations() {
        val state = ScreenState(signedIn = true, connection = "Connected", sending = true)
        assertTrue(canMoveChat(state))
        assertFalse(canMoveChat(state.copy(connection = "Offline")))
        assertFalse(canMoveChat(state.copy(movingChat = true)))
        assertFalse(canMoveChat(state.copy(creatingProject = true)))
        assertFalse(canMoveChat(state.copy(renaming = true)))
    }
}
