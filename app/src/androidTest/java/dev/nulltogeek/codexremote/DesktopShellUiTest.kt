package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DesktopShellUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun fixture(theme: String): ScreenState {
        val current = Chat("desktop", "Build a focused workspace", "", "/workspace", "idle", updatedAt = 10, projectId = "p")
        val pinned = Chat("pinned", "Fix authentication", "", "/workspace", "idle", updatedAt = 9)
        return ScreenState(signedIn = true, username = "nulltogeek", endpoint = "https://example.test", connection = "Connected", theme = theme,
            motion = false, projects = listOf(Project("p", "custom-codex", listOf("/workspace"))), selectedChat = current,
            chats = listOf(current, pinned, Chat("review", "Review navigation", "", "/workspace", updatedAt = 8)), favoriteChats = listOf(pinned),
            selectedModel = "sol", selectedEffort = "high", models = listOf(ModelChoice("sol", "6.1 Sol", listOf("low", "high"), "high")),
            timeline = listOf(TimelineItem("question", "userMessage", "Make the Android app feel like this desktop workspace.", turnId = "turn"),
                TimelineItem("answer", "agentMessage", "We can keep the work in one place.\n\n### A clearer workspace\n\n- Keep projects and pinned chats close.\n- Give the conversation room to breathe.\n- Put the controls where you need them.\n\nChoose a task below, or tell me what you want to build.", turnId = "turn", phase = "final_answer")))
    }
    private fun render(theme: String) {
        val state = fixture(theme)
        compose.activityRule.scenario.onActivity { activity ->
            val vm = RemoteViewModel(activity.application)
            activity.setContent { RemoteTheme(theme, motion = false) { RemoteApp(state, vm) } }
        }
    }
    private fun capture(name: String) {
        // Wait for the Android window to present the settled Compose frame.
        compose.waitForIdle()
        Thread.sleep(400)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir(null), "phase1-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
    @Test fun phoneLightNavigationAndConversationActions() {
        render("light")
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.onNodeWithTag("composer-mode").assertIsDisplayed()
        compose.onNodeWithTag("chat-settings").assertIsDisplayed()
        capture("conversation-light")
        compose.onNodeWithTag("conversation-options").onChildren().onFirst().performClick()
        compose.onNodeWithText("Pin chat").assertIsDisplayed()
        compose.onNodeWithText("Continue in new chat").assertIsEnabled()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("navigation-drawer").assertIsDisplayed()
        compose.onNodeWithTag("drawer-group:project:p").performScrollTo().performClick()
        compose.onNodeWithTag("drawer-chat:pinned").assertIsDisplayed()
        capture("sidebar-light")
    }
    @Test fun phoneDarkConversationAndModelSettings() {
        render("dark")
        capture("conversation-dark")
        compose.onNodeWithTag("chat-settings").performClick()
        compose.onNodeWithText("Chat settings").assertIsDisplayed()
        compose.onNodeWithText("Mode").assertIsDisplayed()
        capture("settings-dark")
    }
    @Test fun starterCardsPrepareAnEditablePrompt() {
        var draft = ""
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light", motion = false) { Surface { NewConversationGreeting("codex") { draft = it } } }
        } }
        compose.onNodeWithTag("starter:Build something").performClick()
        compose.runOnIdle { assertEquals("Help me build ", draft) }
        capture("new-chat-light")
    }
    @Test fun tabletSidebarCanCollapseAndReopen() {
        render("dark")
        compose.onNodeWithTag("persistent-sidebar").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        capture("tablet-dark")
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        compose.onNodeWithTag("persistent-sidebar").assertDoesNotExist()
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("persistent-sidebar").assertIsDisplayed()
    }
}
