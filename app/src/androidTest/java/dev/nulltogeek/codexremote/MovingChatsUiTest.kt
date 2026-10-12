package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MovingChatsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun chatOptionsExposeRenameAndMoveWithoutOpeningTheChat() {
        var moved = false
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark") { ChatOptionsMenu(Chat("c", "Title", "", ""), true, true, {}, { moved = true }) }
        } }
        compose.onNodeWithContentDescription("Options for chat Title").performClick()
        compose.onNodeWithText("Rename chat").assertIsDisplayed()
        compose.onNodeWithText("Move to project").performClick()
        compose.runOnIdle { assertTrue(moved) }
        compose.onNodeWithText("Move to project").assertDoesNotExist()
    }
    @Test fun moveDialogRequiresAnotherProjectAndPreservesTheFailureForRetry() {
        var destination: String? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light") { MoveChatDialog(Chat("c", "Title", "", "/old", projectId = "old"),
                listOf(Project("old", "Old", emptyList()), Project("new", "New", emptyList())), false, true, "Could not move",
                {}, { destination = it }) }
        } }
        compose.onNodeWithText("Could not move").assertIsDisplayed()
        compose.onNodeWithTag("move-chat-save").assertIsNotEnabled()
        compose.onNodeWithTag("move-project:new").performClick()
        compose.onNodeWithTag("move-chat-save").performClick()
        compose.runOnIdle { assertEquals("new", destination) }
    }
}
