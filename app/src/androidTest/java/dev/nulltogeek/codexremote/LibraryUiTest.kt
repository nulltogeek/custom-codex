package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun render(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { Surface { Box(Modifier.width(320.dp).fillMaxHeight()) { content() } } }
        } }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        val capture = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            capture.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        capture.recycle()
    }

    @Test fun rightSwipeRequestsArchiveLeftSwipeAndDisabledRowsDoNot() {
        var requests = 0
        val chat = Chat("swipe", "Swipe me", "", "", status = "idle")
        val enabled = mutableStateOf(true)
        render { Column {
            SwipeArchiveRow(chat, enabled.value, { requests++ }) {
                Surface { Text("Swipe me", Modifier.fillMaxWidth().padding(24.dp)) }
            }
        } }
        compose.onNodeWithTag("archive-swipe:swipe").performTouchInput { swipeLeft() }
        compose.waitForIdle(); assertEquals(0, requests)
        compose.onNodeWithTag("archive-swipe:swipe").performTouchInput { swipeRight() }
        compose.waitUntil(10000) { requests > 0 }
        assertEquals(1, requests)
        compose.onNodeWithText("Swipe me").assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("archive-swipe:swipe").performTouchInput { swipeRight() }
        compose.waitForIdle(); assertEquals(1, requests)
    }

    @Test fun chatFavoritesShowUnpagedSavedChatsAndAllowRemoval() {
        val cached = Chat("favorite", "Saved older chat", "Favorite preview", "/workspace", archived = true)
        val state = mutableStateOf(ScreenState(connection = "Connected", favoriteChats = listOf(cached),
            chats = listOf(Chat("recent", "Recent chat", "", ""))))
        val vm = RemoteViewModel(compose.activity.application)
        render { ChatsScreen(state.value, vm, approval = {}, favorite = { chat ->
            state.value = state.value.copy(favoriteChats = state.value.favoriteChats.filter { it.id != chat.id })
        }) }
        compose.onNodeWithText("Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Saved older chat").assertIsDisplayed()
        compose.onNodeWithText("Recent chat").assertDoesNotExist()
        screenshot("v0.7.0-chat-favorites")
        compose.onNodeWithContentDescription("Remove chat Saved older chat from favorites").performClick()
        compose.onNodeWithText("No favorite chats yet").assertIsDisplayed()
    }

    @Test fun archivedSectionFiltersProjectsAndIncludesArchivedAgents() {
        val archived = Chat("archive", "Archived conversation", "", "/workspace", archived = true)
        val state = mutableStateOf(ScreenState(connection = "Connected", projectFilter = "p", projects = listOf(Project("p", "Project", listOf("/workspace"))),
            chats = listOf(archived, archived.copy(id = "agent", title = "Archived agent", source = "subAgent"),
                archived.copy(id = "other", title = "Other project archive", cwd = "/other"),
                archived.copy(id = "active", title = "Active conversation", archived = false))))
        val vm = RemoteViewModel(compose.activity.application)
        var restore: Chat? = null
        var delete: Chat? = null
        render { ChatsScreen(state.value, vm, approval = {}, archivedOnly = true, archive = { restore = it }, delete = { delete = it }) }
        compose.onNodeWithText("Archived chats").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search archived chats").assertIsDisplayed()
        compose.onNodeWithText("Recent").assertDoesNotExist()
        compose.onNodeWithText("Archived conversation").assertIsDisplayed()
        compose.onNodeWithText("Archived agent").assertIsDisplayed()
        compose.onNodeWithText("Active conversation").assertDoesNotExist()
        compose.onNodeWithText("Other project archive").assertDoesNotExist()
        compose.onAllNodesWithText("Delete")[0].performClick()
        compose.runOnIdle { assertEquals(archived, delete) }
        compose.onNodeWithTag("archive-swipe:archive").performTouchInput { swipeRight() }
        compose.waitUntil(10000) { restore != null }
        assertEquals(archived, restore)
        compose.runOnIdle { state.value = state.value.copy(chats = emptyList()) }
        compose.onNodeWithText("No archived chats").assertIsDisplayed()
    }

    @Test fun emptyArchivedSectionLoadsArchivedPagesAndRetainsSearch() {
        val state = mutableStateOf(ScreenState(connection = "Connected", searchTerm = "needle", activeCursor = "active-page",
            archivedCursor = "archive-page", pending = listOf(PendingInput("pending", "approval", buildJsonObject {})),
            chats = listOf(Chat("active", "Active needle", "", ""))))
        val requests = mutableListOf<Boolean>()
        val vm = RemoteViewModel(compose.activity.application)
        render { ChatsScreen(state.value, vm, approval = {}, archivedOnly = true, loadMore = { archived ->
            requests += archived
            state.value = state.value.copy(archivedCursor = null, chats = state.value.chats +
                Chat("archived", "Archived needle", "", "", archived = true))
        }) }
        compose.waitUntil(10000) { requests == listOf(true) }
        compose.onNodeWithText("Archived needle").assertIsDisplayed()
        compose.onNodeWithText("Active needle").assertDoesNotExist()
        assertEquals("needle", state.value.searchTerm)
        assertEquals("active-page", state.value.activeCursor)
        compose.waitForIdle(); assertEquals(listOf(true), requests)
    }

    @Test fun archivedNavigationReturnsToArchiveAndOffersRestore() {
        val chat = Chat("archive-nav", "Saved conversation", "", "/workspace", archived = true)
        val vm = RemoteViewModel(compose.activity.application)
        vm.filterProject("p")
        render {
            val state by vm.state.collectAsState()
            RemoteApp(state.copy(signedIn = true, connection = "Connected", motion = false, chats = listOf(chat),
                projects = listOf(Project("p", "Project", listOf("/workspace")))), vm)
        }
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("nav-archived").assertIsDisplayed().performClick()
        assertArchiveDestination()
        compose.onNodeWithText("Saved conversation").performClick()
        compose.onNodeWithContentDescription("Delete chat").performClick()
        compose.onNodeWithText("Delete this chat permanently?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Restore chat").performClick()
        compose.onNodeWithText("Restore this chat?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Archived chats").assertIsDisplayed()
        assertArchiveDestination()
        compose.onNodeWithText("Saved conversation").performClick()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        assertArchiveDestination()
        compose.onNodeWithContentDescription("All projects").performClick()
        assertArchiveDestination()
        compose.runOnIdle { assertNull(vm.state.value.projectFilter) }
    }

    private fun assertArchiveDestination() {
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("nav-archived").assertIsSelected()
        compose.onNodeWithTag("nav-archived").performClick()
    }

    @Test fun sidebarNavigatesAndBackClosesItWithoutChangingPage() {
        val vm = RemoteViewModel(compose.activity.application)
        render {
            val state by vm.state.collectAsState()
            RemoteApp(state.copy(signedIn = true, connection = "Connected", motion = false), vm)
        }
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("nav-projects").performClick()
        compose.onNodeWithText("Projects").assertIsDisplayed()
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("nav-projects").assertIsSelected()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("open-sidebar").assertIsDisplayed()
        compose.runOnIdle { assertEquals("projects", vm.state.value.page) }
        compose.onNodeWithTag("open-sidebar").performClick()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun projectFavoritesFilterAndToggleAt320Dp() {
        val project = Project("p", "Favorite project", listOf("/workspace/long/project/path"))
        val state = mutableStateOf(ScreenState(connection = "Connected", projects = listOf(project, Project("other", "Other project", emptyList()))))
        val vm = RemoteViewModel(compose.activity.application)
        render { ProjectScreen(state.value, vm, favorite = { selected ->
            state.value = state.value.copy(favoriteProjectIds = if (selected.id in state.value.favoriteProjectIds) emptySet() else setOf(selected.id))
        }) }
        compose.onNodeWithContentDescription("Add project Favorite project to favorites").performClick()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Favorite project").assertIsDisplayed()
        compose.onNodeWithText("Other project").assertDoesNotExist()
        screenshot("v0.7.0-project-favorites")
        compose.onNodeWithContentDescription("Remove project Favorite project from favorites").performClick()
        compose.onNodeWithText("No favorite projects yet").assertIsDisplayed()
    }

    @Test fun favoritesPersistAcrossStoreInstancesAndAreIsolatedByServerAndAccount() {
        val store = LocalStore(compose.activity)
        val chat = Chat("persisted", "Persisted favorite", "Preview", "/workspace", archived = true, parentId = "parent", source = "subAgent", projectId = "p")
        val endpoint = "https://favorite-fixture.invalid"
        val username = "fixture-owner"
        store.favorites(endpoint, username, listOf(chat), setOf("p"))
        val restored = LocalStore(compose.activity).favorites(endpoint + "/", username)
        assertEquals(listOf(chat), restored.first); assertEquals(setOf("p"), restored.second)
        assertTrue(store.favorites("https://another-fixture.invalid", username).first.isEmpty())
        assertTrue(store.favorites(endpoint, "another-fixture-owner").second.isEmpty())
        store.favorites(endpoint, username, emptyList(), emptySet())
        assertTrue(LocalStore(compose.activity).favorites(endpoint, username).first.isEmpty())
    }

    @Test fun taskNotificationsCanBeToggledAndPermissionFeedbackIsVisible() {
        val enabled = mutableStateOf(false)
        var settingsOpened = false
        render { TaskNotificationSettings(enabled.value, "Allow notifications in Android Settings.", change = { enabled.value = it }, openSettings = { settingsOpened = true }) }
        compose.onNodeWithTag("task-notifications").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Allow notifications in Android Settings.").assertIsDisplayed()
        compose.onNodeWithText("Android notification settings").performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
        compose.onNodeWithTag("task-notifications").performClick().assertIsOff()
    }

    @Test fun completedTaskIdentityStorageIsIsolatedByServerAndAccount() {
        val store = LocalStore(compose.activity)
        val endpoint = "https://notification-fixture.invalid"
        store.notifiedTasks(endpoint, "owner", listOf("completed-turn"))
        assertEquals(listOf("completed-turn"), LocalStore(compose.activity).notifiedTasks(endpoint, "owner"))
        assertTrue(store.notifiedTasks(endpoint, "other").isEmpty())
        assertTrue(store.notifiedTasks("https://another-fixture.invalid", "owner").isEmpty())
        store.notifiedTasks(endpoint, "owner", emptyList())
    }

    @Test fun passwordFormRequiresMatchingValuesAndClearsFieldsOnSuccess() {
        val changed = mutableStateOf(false)
        var submitted: Pair<String, String>? = null
        render { ChangePasswordDialog(false, if (changed.value) "Password changed." else "", changed.value, true, {}, { old, next ->
            submitted = old to next; changed.value = true
        }) }
        compose.onNodeWithText("Save password").assertIsNotEnabled()
        screenshot("v0.7.0-password-form")
        compose.onNodeWithText("Current password").performTextInput("old fixture password  ")
        compose.onNodeWithText("New password").performTextInput("new fixture password  ")
        compose.onNodeWithText("Confirm new password").performTextInput("different fixture password")
        compose.onNodeWithText("Save password").assertIsNotEnabled()
        compose.onNodeWithText("Confirm new password").performTextReplacement("new fixture password  ")
        compose.onNodeWithText("Save password").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("old fixture password  " to "new fixture password  ", submitted)
        compose.onNodeWithText("Password changed.").assertIsDisplayed()
        compose.onNodeWithText("Current password").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
