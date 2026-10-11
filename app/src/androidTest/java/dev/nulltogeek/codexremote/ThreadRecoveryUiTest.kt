package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ThreadRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun render(state: ScreenState, onContinue: () -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { RemoteTheme("dark", motion = false) { ThreadWriterNotice(state, onContinue) } }
        }
    }
    private fun state() = ScreenState(selectedChat = Chat("source", "Desktop chat", "", "/workspace"),
        connection = "Connected", writerConflict = true, draft = "My draft")

    @Test fun copyingRequiresConfirmationAndCancelDoesNotCreateOrSend() {
        var copies = 0
        render(state()) { copies++ }
        compose.onNodeWithText("Continue in a new chat").performClick()
        compose.onNodeWithText("Copy this chat?").assertIsDisplayed()
        assertEquals(0, copies)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, copies)
        compose.onNodeWithText("Continue in a new chat").performClick()
        compose.onNodeWithText("Create copy").performClick()
        compose.waitForIdle(); assertEquals(1, copies)
    }

    @Test fun copyingUnavailableOfflineDuringUploadAndWithUnknownDelivery() {
        for (state in listOf(state().copy(connection = "Offline"), state().copy(uploading = true),
            state().copy(sending = true), state().copy(uncertainSend = true),
            state().copy(selectedChat = state().selectedChat!!.copy(archived = true)))) {
            render(state) { fail("Unavailable copy must not run") }
            compose.onNodeWithText("Continue in a new chat").assertIsNotEnabled()
        }
    }

    @Test fun copyingDraftIsDurableAndKeepsOriginalAndAttachmentPreview() {
        val store = LocalStore(compose.activity.application)
        val original = "source-${UUID.randomUUID()}"
        val copied = "copy-${UUID.randomUUID()}"
        val photo = Attachment("photo", "photo.png", 42, "image/png",
            jsonObject("type" to "localImage", "path" to "/uploaded/photo.png"), "/cache/photo.png")
        store.draft(original, "Latest unsent edit"); store.attachments(original, listOf(photo))
        store.chatOptions(original, "codex", "full")
        store.copyChatDraft(original, copied, "chat", "ask")
        val reloaded = LocalStore(compose.activity.application)
        assertEquals("Latest unsent edit", reloaded.draft(copied))
        assertEquals(listOf(photo), reloaded.attachments(copied))
        assertEquals("chat", reloaded.chatMode(copied)); assertEquals("ask", reloaded.accessMode(copied))
        assertEquals("Latest unsent edit", reloaded.draft(original))
        assertEquals(listOf(photo), reloaded.attachments(original))
        assertEquals("full", reloaded.accessMode(original))
    }
}
