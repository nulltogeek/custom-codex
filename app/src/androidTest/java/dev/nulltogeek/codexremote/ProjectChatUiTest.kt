package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class ProjectChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun fixture() = ScreenState(signedIn = true, connection = "Connected", motion = false, username = "nulltogeek",
        projects = listOf(Project("jira", "JIRA", listOf("/jira")), Project("tennis", "Tennis", listOf("/tennis"))),
        chats = listOf(Chat("jira-chat", "Fix authentication", "", "/jira"), Chat("tennis-chat", "Improve the scoreboard", "", "/tennis"), Chat("orphan", "Explore an idea", "", "")))
    private fun render(state: State<ScreenState>, sidebar: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity ->
            val vm = RemoteViewModel(activity.application)
            activity.setContent { RemoteTheme("dark", motion = false) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (sidebar) RemoteDrawer(state.value, vm, {}, {}, {}, {}, {}, persistent = true)
                else ChatsScreen(state.value, vm, approval = {})
            } } }
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); Thread.sleep(400)
        val capture = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir(null), "project-chats-$name.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        capture.recycle()
    }
    @Test fun homeStartsWithCollapsedProjectsAndSeparateOrphans() {
        val state = mutableStateOf(fixture()); render(state)
        compose.onNodeWithTag("group:project:jira").assertIsDisplayed()
        compose.onNodeWithTag("group:project:tennis").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:jira-chat").assertDoesNotExist()
        compose.onNodeWithTag("chat-row:tennis-chat").assertDoesNotExist()
        compose.onNodeWithTag("chat-row:orphan").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        screenshot("home-dark")
        compose.onNodeWithTag("group:project:jira").performClick()
        compose.onNodeWithTag("chat-row:jira-chat").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:tennis-chat").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        screenshot("expanded-dark")
        compose.onNodeWithTag("group:project:jira").performClick()
        compose.onNodeWithTag("chat-row:jira-chat").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(searchTerm = "scoreboard") }
        compose.waitUntil(10000) { compose.onNodeWithTag("chat-row:tennis-chat").isDisplayed() }
        compose.onNodeWithTag("chat-row:tennis-chat").assertIsDisplayed()
        screenshot("search-dark")
        compose.onNodeWithTag("group:project:jira").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(searchTerm = "") }
        compose.onNodeWithTag("chat-row:tennis-chat").assertDoesNotExist()
    }
    @Test fun selectedProjectDoesNotShowOtherProjectsOrOrphans() {
        val state = mutableStateOf(fixture().copy(projectFilter = "jira")); render(state)
        compose.onNodeWithTag("chat-row:jira-chat").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:tennis-chat").assertDoesNotExist()
        compose.onNodeWithTag("chat-row:orphan").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }
    @Test fun liveProjectMovesUpdateBothGroupsWithoutDuplicateChats() {
        val state = mutableStateOf(fixture()); render(state)
        compose.onNodeWithTag("group:project:jira").performClick()
        compose.onNodeWithTag("chat-row:jira-chat").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(assignments = mapOf("jira-chat" to "tennis")) }
        compose.onNodeWithTag("chat-row:jira-chat").assertDoesNotExist()
        compose.onNodeWithTag("group:project:tennis").performClick()
        compose.onNodeWithTag("chat-row:jira-chat").assertIsDisplayed()
        compose.onAllNodesWithTag("chat-row:jira-chat").assertCountEquals(1)
    }
    @Test fun sidebarKeepsChatsAndPinsInsideTheOwningProject() {
        val base = fixture(); val state = mutableStateOf(base.copy(favoriteChats = listOf(base.chats.first())))
        render(state, sidebar = true)
        compose.onNodeWithTag("drawer-chat:jira-chat").assertDoesNotExist()
        compose.onNodeWithTag("drawer-chat:orphan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drawer-group:project:jira").performScrollTo().performClick()
        compose.onNodeWithTag("drawer-chat:jira-chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pinned chat").assertIsDisplayed()
        compose.onNodeWithTag("drawer-chat:tennis-chat").assertDoesNotExist()
        screenshot("sidebar-dark")
    }
}
