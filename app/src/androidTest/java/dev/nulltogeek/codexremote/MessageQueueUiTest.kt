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
            var blocked by remember { mutableStateOf(false) }
            RemoteTheme("dark", motion = false) { Surface { Box(Modifier.width(320.dp).fillMaxHeight()) {
                MessageQueueView(ScreenState(selectedChat = Chat("chat", "Queue fixture", "", ""),
                    queuedMessages = snapshot.messages, pausedQueues = snapshot.pausedChats, queueBlocked = blocked),
                    edit = { message, text, files -> runCatching { queue.edit(message.id, text, files) }.onFailure { blocked = true }.isSuccess }, move = queue::move,
                    remove = queue::remove, pause = { runCatching { queue.pause(it) }.onFailure { blocked = true }.isSuccess }, resume = queue::resume, retry = queue::retry)
            } } }
        } }
        compose.waitForIdle()
    }
    @Test fun failedPauseDoesNotOpenAnEditorAndFailedSaveKeepsTheDraft() {
        var failStorage = false
        val queue = MessageQueue { _, _, _ -> check(!failStorage) { "Storage unavailable" } }
        queue.restore("https://fixture.invalid", "owner", QueueSnapshot())
        queue.add(QueuedMessage("first", "chat", "First"))
        render(queue)
        compose.runOnIdle { failStorage = true }
        compose.onNodeWithText("Pause queue").performClick()
        compose.onNodeWithText("Edit queued message").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("chat" in queue.state.value.pausedChats)
            failStorage = false
        }
        compose.onNodeWithContentDescription("Expand message queue").performClick()
        compose.onNodeWithContentDescription("Edit queued message 1").performScrollTo().performClick()
        compose.onNodeWithText("Queued message").performTextReplacement("Keep this draft")
        compose.runOnIdle { failStorage = true }
        compose.onNodeWithText("Save message").performClick()
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
        compose.onNodeWithText("Edit queued message").assertIsDisplayed()
        compose.runOnIdle { assertEquals("First", queue.state.value.messages.first().text); failStorage = false }
        compose.onNodeWithText("Save message").performClick()
        compose.runOnIdle { assertEquals("Keep this draft", queue.state.value.messages.first().text) }
    }
    @Test fun pauseSkipsAnAlreadySendingMessage() {
        val queue = MessageQueue { _, _, _ -> }
        queue.restore("https://fixture.invalid", "owner", QueueSnapshot())
        queue.add(QueuedMessage("first", "chat", "Already sending"))
        queue.add(QueuedMessage("second", "chat", "Waiting"))
        queue.claim(queue.state.value.messages.first())
        render(queue)
        compose.onNodeWithText("Pause queue").performClick()
        compose.onNodeWithText("Queued message").assertTextContains("Waiting")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Edit queued message 1").performScrollTo().assertIsNotEnabled()
    }
    @Test fun pauseOpensEditorAndSavingRequiresExplicitResume() {
        val queue = MessageQueue { _, _, _ -> }
        queue.restore("https://fixture.invalid", "owner", QueueSnapshot())
        queue.add(QueuedMessage("first", "chat", "First"))
        queue.add(QueuedMessage("second", "chat", "Second"))
        render(queue)
        compose.onNodeWithText("1. First").assertDoesNotExist()
        compose.onNodeWithText("Pause queue").performClick()
        compose.onNodeWithText("Edit queued message").assertIsDisplayed()
        compose.onNodeWithText("Queued message").performTextReplacement("Revised first")
        compose.onNodeWithText("Save message").performClick()
        compose.onNodeWithText("1. Revised first").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Revised first", queue.state.value.messages.first().text)
            assertTrue("chat" in queue.state.value.pausedChats)
            assertNull(queue.claim(queue.state.value.messages.first()))
        }
        compose.onNodeWithContentDescription("Edit queued message 2").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue("chat" in queue.state.value.pausedChats) }
        compose.onNodeWithText("Resume queue").performClick()
        compose.runOnIdle { assertNotNull(queue.claim(queue.state.value.messages.first())) }
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
