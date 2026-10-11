package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
