package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ComposerInputTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun enterSendsOnceAndShiftEnterKeepsAMultilineDraft() {
        val draft = mutableStateOf("Hello"); var sends = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { MessageDraftInput(draft.value, { draft.value = it }, { sends++ }, true, "Message", Modifier.fillMaxWidth()) }
        } }
        val input = compose.onNodeWithContentDescription("Message input")
        input.performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends); assertEquals("Hello", draft.value) }
        input.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        compose.runOnIdle { assertEquals(1, sends); assertTrue(draft.value.contains('\n')) }
    }
    @Test fun softwareKeyboardSendAndNumpadEnterUseTheSameSendAction() {
        var sends = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light", motion = false) { MessageDraftInput("Message", {}, { sends++ }, true, "Message", Modifier.fillMaxWidth()) }
        } }
        val input = compose.onNodeWithContentDescription("Message input")
        input.performClick().performImeAction()
        compose.runOnIdle { assertEquals(1, sends) }
        input.performKeyInput { pressKey(Key.NumPadEnter) }
        compose.runOnIdle { assertEquals(2, sends) }
    }
    @Test fun blockedSendCannotBypassConnectionUploadOrDeliveryGuards() {
        val draft = mutableStateOf("Ready"); var sends = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { MessageDraftInput(draft.value, { draft.value = it }, { sends++ }, false, "Message", Modifier.fillMaxWidth()) }
        } }
        val input = compose.onNodeWithContentDescription("Message input")
        input.performClick().performKeyInput { pressKey(Key.Enter) }
        input.performImeAction()
        compose.runOnIdle { assertEquals(0, sends); assertEquals("Ready", draft.value) }
    }
    @Test fun pastedMultilineTextStaysInTheDraftUntilExplicitlySent() {
        val draft = mutableStateOf(""); var sends = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light", motion = false) { MessageDraftInput(draft.value, { draft.value = it }, { sends++ }, true, "Message", Modifier.fillMaxWidth()) }
        } }
        compose.onNodeWithContentDescription("Message input").performTextInput("First line\nSecond line")
        compose.runOnIdle { assertEquals(0, sends); assertEquals("First line\nSecond line", draft.value) }
    }
}
