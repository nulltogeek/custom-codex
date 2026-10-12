package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SentMessageEditUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun sentUserMessageHasAnEditButtonBesideCopyAt320Dp() {
        val item = TimelineItem("sent", "userMessage", "Original message", turnId = "turn")
        var selected: TimelineItem? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { Surface { Box(Modifier.width(320.dp)) {
                MessageItem(item, {}, { error("No image") }, editMessage = { selected = it })
            } } }
        } }
        compose.onNodeWithTag("copy-message:sent").assertIsDisplayed()
        compose.onNodeWithTag("edit-message:sent").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(item, selected) }
    }
    @Test fun revisionKeepsOriginalMediaUntilRemovedAndCancelNeverResends() {
        val file = Attachment("image", "photo.png", 0, "image/png", jsonObject("type" to "localImage", "path" to "/fixture/photo.png"))
        val original = SentMessageEdit("chat", "item", "turn", "Original", listOf(file))
        val edit = mutableStateOf(original); var saves = 0; var cancels = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light", motion = false) { SentMessageEditDialog(edit.value, true, false,
                { edit.value = edit.value.copy(text = it) }, { id -> edit.value = edit.value.copy(attachments = edit.value.attachments.filterNot { it.id == id }) },
                { saves++ }, {}, { cancels++ }) }
        } }
        compose.onNodeWithTag("sent-message-edit-input").performTextReplacement("Revised")
        compose.onNodeWithContentDescription("Remove original attachment photo.png").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals("Revised", edit.value.text); assertTrue(edit.value.attachments.isEmpty())
            assertEquals("Original", original.text); assertEquals(listOf(file), original.attachments)
            assertEquals(0, saves); assertEquals(1, cancels)
        }
        compose.onNodeWithTag("save-sent-message").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
    @Test fun runningDisconnectedBusyAndUnconfirmedForksCannotResend() {
        val edit = mutableStateOf(SentMessageEdit("chat", "item", "turn", "Revised", emptyList()))
        val connected = mutableStateOf(true); val running = mutableStateOf(true)
        var saves = 0; var stops = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { SentMessageEditDialog(edit.value, connected.value, running.value,
                {}, {}, { saves++ }, { stops++ }, {}) }
        } }
        compose.onNodeWithTag("save-sent-message").assertIsNotEnabled()
        compose.onNodeWithText("Stop work").performClick()
        compose.runOnIdle { assertEquals(1, stops); running.value = false; connected.value = false }
        compose.onNodeWithTag("save-sent-message").assertIsNotEnabled()
        compose.runOnIdle { connected.value = true; edit.value = edit.value.copy(working = true) }
        compose.onNodeWithTag("save-sent-message").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { edit.value = edit.value.copy(working = false, uncertainFork = true, error = "Check Chats") }
        compose.onNodeWithTag("save-sent-message").assertIsNotEnabled()
        compose.onNodeWithText("Check Chats").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
