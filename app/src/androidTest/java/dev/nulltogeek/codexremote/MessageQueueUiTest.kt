package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessageQueueUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun render(queue: MessageQueue) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val snapshot by queue.state.collectAsState()
            RemoteTheme("dark", motion = false) { Surface { Box(Modifier.width(320.dp).fillMaxHeight()) {
                MessageQueueView(ScreenState(selectedChat = Chat("chat", "Queue fixture", "", ""),
                    queuedMessages = snapshot.messages, pausedQueues = snapshot.pausedChats),
                    edit = { message, text, files -> queue.edit(message.id, text, files) }, move = queue::move,
                    remove = queue::remove, pause = queue::pause, resume = queue::resume, retry = queue::retry)
            } } }
        } }
        compose.waitForIdle()
    }
    @Test fun queuedMessagesCanBeEditedReorderedAndRemovedAt320Dp() {
        val queue = MessageQueue { _, _, _ -> }
        queue.restore("https://fixture.invalid", "owner", QueueSnapshot())
        val file = Attachment("f", "notes.txt", 10, "text/plain", jsonObject("type" to "text", "text" to "notes"))
        queue.add(QueuedMessage("first", "chat", "First", attachments = listOf(file)))
        queue.add(QueuedMessage("second", "chat", "Second"))
        render(queue)
        compose.onNodeWithText("1. First").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand message queue").performClick()
        compose.onNodeWithContentDescription("Edit queued message 1").performScrollTo().performClick()
        compose.onNodeWithText("Queued message").performTextReplacement("Edited first")
        compose.onNodeWithContentDescription("Remove queued attachment notes.txt").performClick()
        compose.onNodeWithText("Save message").performClick()
        compose.runOnIdle {
            assertEquals("Edited first", queue.state.value.messages.first().text)
            assertTrue(queue.state.value.messages.first().attachments.isEmpty())
            assertTrue("chat" in queue.state.value.pausedChats)
        }
        compose.onNodeWithContentDescription("Move queued message 2 up").performScrollTo().performClick()
        compose.onNodeWithText("1. Second").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove queued message 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("first"), queue.state.value.messages.map { it.id }) }
        compose.onNodeWithText("Resume queue").performClick()
        compose.runOnIdle { assertFalse("chat" in queue.state.value.pausedChats) }
        compose.onNodeWithContentDescription("Collapse message queue").performClick()
        compose.onNodeWithText("1. Edited first").assertDoesNotExist()
    }
    @Test fun uncertainMessageRequiresRetryConfirmationAndExplicitResume() {
        val queue = MessageQueue { _, _, _ -> }
        queue.restore("https://fixture.invalid", "owner", QueueSnapshot(listOf(QueuedMessage("uncertain", "chat", "Check delivery",
            delivery = "uncertain", error = "Delivery unconfirmed"))))
        render(queue)
        compose.onNodeWithText("Resume queue").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Expand message queue").performClick()
        compose.onNodeWithContentDescription("Review retry for queued message 1").performScrollTo().performClick()
        compose.onNodeWithText("Retry this queued message?").assertIsDisplayed()
        compose.onNodeWithText("Retry message").performClick()
        compose.onNodeWithText("Resume queue").assertIsEnabled()
        compose.runOnIdle { assertTrue("chat" in queue.state.value.pausedChats) }
        compose.onNodeWithText("Resume queue").performClick()
        compose.runOnIdle { assertFalse("chat" in queue.state.value.pausedChats) }
    }
    @Test fun queuePersistsAndIsIsolatedByServerAndAccount() {
        val endpoint = "https://queue-storage-fixture.invalid"
        val snapshot = QueueSnapshot(listOf(QueuedMessage("stored", "chat", "Saved queue", model = "fixture-model")), setOf("chat"))
        LocalStore(compose.activity).queue(endpoint, "owner", snapshot)
        val reopened = LocalStore(compose.activity)
        assertEquals(snapshot, reopened.queue(endpoint + "/", "owner"))
        assertTrue(reopened.queue(endpoint, "other").messages.isEmpty())
        assertTrue(reopened.queue("https://another-queue-fixture.invalid", "owner").messages.isEmpty())
        reopened.queue(endpoint, "owner", QueueSnapshot())
    }
}
