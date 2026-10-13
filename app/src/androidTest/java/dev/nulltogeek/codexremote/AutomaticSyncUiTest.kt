package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class AutomaticSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun render(state: ScreenState) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { RemoteTheme("dark", motion = false) {
                ChatTimeline(state, Modifier.height(400.dp), {}, {}, {})
            } }
        }
    }
    @Test fun emptyChatShowsUpdatingUntilItsHistoryArrives() {
        val state = ScreenState(chatSyncing = true, selectedChat = Chat("chat", "Title", "", ""))
        render(state)
        compose.onNodeWithTag("chat-syncing").assertIsDisplayed()
        compose.onNodeWithText("Ready to build").assertDoesNotExist()
        render(state.copy(chatSyncing = false, timeline = listOf(TimelineItem("reply", "agentMessage", "Latest response"))))
        compose.onNodeWithTag("chat-syncing").assertDoesNotExist()
        compose.onNodeWithText("Latest response").assertIsDisplayed()
    }
    @Test fun cachedMessagesRemainVisibleWhileUpdating() {
        render(ScreenState(chatSyncing = true, selectedChat = Chat("chat", "Title", "", ""),
            timeline = listOf(TimelineItem("reply", "agentMessage", "Cached response"))))
        compose.onNodeWithText("Cached response").assertIsDisplayed()
        compose.onNodeWithTag("chat-syncing").assertDoesNotExist()
    }
}
