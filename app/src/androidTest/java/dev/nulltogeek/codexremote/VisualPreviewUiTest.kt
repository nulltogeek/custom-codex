package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Native screenshots of the complete navigation shell and account screens. */
class VisualPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private fun conversation(theme: String) {
        val chat = Chat("visual-conversation", "Plan a better workspace", "", "/workspace", status = "idle")
        val state = ScreenState(endpoint = "https://example.test", username = "nulltogeek", signedIn = true,
            connection = "Connected", theme = theme, motion = false, accountId = "owner", accountRole = "admin",
            userManagementAvailable = true, selectedChat = chat, chats = listOf(chat),
            projects = listOf(Project("project-preview", "My workspace", listOf("/workspace"))),
            timeline = listOf(TimelineItem("visual-user", "userMessage", "Help me plan a focused workspace for my next project.", turnId = "visual-turn"),
                TimelineItem("visual-answer", "agentMessage", "Start with a clear goal, then give each part of the work a place.\n\n- Keep your active project easy to find.\n- Break the work into small, useful steps.\n\nWhat would you like to build first?", turnId = "visual-turn", phase = "final_answer")))
        compose.activityRule.scenario.onActivity { activity ->
            val vm = RemoteViewModel(activity.application)
            activity.setContent { RemoteTheme(theme, motion = false) { RemoteApp(state, vm) } }
        }
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.onNodeWithTag("message:visual-user").assertIsDisplayed()
        capture("codex-0.8.0-conversation-$theme")
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("navigation-drawer").assertIsDisplayed()
        compose.onNodeWithTag("nav-users").assertIsDisplayed()
        capture("codex-0.8.0-drawer-$theme")
    }

    @Test fun lightConversationAndDrawer() = conversation("light")
    @Test fun darkConversationAndDrawer() = conversation("dark")

    @Test fun signupScreen() {
        compose.activityRule.scenario.onActivity { activity ->
            val vm = RemoteViewModel(activity.application)
            activity.setContent { RemoteTheme("light", motion = false) {
                Surface { AccountSignIn(ScreenState(endpoint = "https://example.test", username = "", motion = false), vm) }
            } }
        }
        compose.onNodeWithTag("account-toggle-signup").performScrollTo().performClick()
        compose.onNodeWithTag("account-confirm-password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Create your account").performScrollTo().assertIsDisplayed()
        capture("codex-0.8.0-signup-light")
    }

    @Test fun administratorUserList() {
        compose.activityRule.scenario.onActivity { activity ->
            val vm = RemoteViewModel(activity.application)
            val state = ScreenState(connection = "Connected", username = "nulltogeek", accountId = "owner",
                accountRole = "admin", userManagementAvailable = true, motion = false,
                adminUsers = listOf(ManagedUser("owner", "nulltogeek", "admin", false),
                    ManagedUser("alice", "alice", "user", false), ManagedUser("bob", "bob", "user", true)))
            activity.setContent { RemoteTheme("light", motion = false) { Surface { ManageUsersScreen(state, vm) } } }
        }
        compose.onNodeWithTag("allow-registration").assertIsDisplayed()
        compose.onNodeWithTag("managed-user:owner").assertIsDisplayed()
        compose.onNodeWithTag("managed-user:bob").assertIsDisplayed()
        capture("codex-0.8.0-admin-light")
    }
}
