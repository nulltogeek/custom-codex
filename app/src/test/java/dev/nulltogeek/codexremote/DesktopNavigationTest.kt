package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class DesktopNavigationTest {
    private fun chat(id: String, title: String = id, archived: Boolean = false, updated: Long = 1) =
        Chat(id, title, "", "/workspace", status = "idle", archived = archived, updatedAt = updated)

    @Test fun liveRenamesAndArchivesOverrideSavedPinsWithoutDuplicatingRecents() {
        val saved = listOf(chat("renamed", "Old name"), chat("archived"), chat("unpaged"))
        val (pinned, recent) = sidebarChatGroups(ScreenState(favoriteChats = saved, chats = listOf(
            chat("renamed", "New name"), chat("archived", archived = true), chat("older", updated = 2), chat("newer", updated = 3))))
        assertEquals(listOf("New name", "unpaged"), pinned.map { it.title })
        assertEquals(listOf("newer", "older"), recent.map { it.id })
    }
    @Test fun searchIncludesSavedPinsButDoesNotLeakChildOrArchivedChats() {
        val (pinned, recent) = sidebarChatGroups(ScreenState(searchTerm = "fix", favoriteChats = listOf(chat("p", "Fix login")),
            chats = listOf(chat("a", "Fix API"), chat("hidden", "Fix hidden", archived = true), chat("agent", "Fix agent").copy(parentId = "parent"))))
        assertEquals(listOf("p"), pinned.map { it.id })
        assertEquals(listOf("a"), recent.map { it.id })
    }
    @Test fun branchActionRefusesRunningUncertainOfflineAndArchivedState() {
        val ready = ScreenState(signedIn = true, connection = "Connected", selectedChat = chat("source"))
        assertTrue(canForkChat(ready))
        listOf(ready.copy(activeTurnId = "running"), ready.copy(selectedChat = chat("source").copy(status = "active")),
            ready.copy(sending = true), ready.copy(uploading = true), ready.copy(uncertainSend = true), ready.copy(loading = true),
            ready.copy(connection = "Offline"), ready.copy(selectedChat = chat("source", archived = true)), ready.copy(signedIn = false))
            .forEach { assertFalse(canForkChat(it)) }
        assertTrue(recoveryParams(ready)["deferGoalContinuation"].toString() == "true")
        assertFalse(recoveryParams(ready).containsKey("input"))
    }
}
