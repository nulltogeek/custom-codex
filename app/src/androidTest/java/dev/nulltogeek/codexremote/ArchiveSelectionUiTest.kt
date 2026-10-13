package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArchiveSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val first = Chat("one", "First archived chat", "", "", archived = true)
    private val second = first.copy(id = "two", title = "Second archived chat")
    private fun render(state: State<ScreenState>, deleted: (List<Chat>) -> Unit = {}, swipeDelete: (Chat) -> Unit = {}): RemoteViewModel {
        val vm = RemoteViewModel(compose.activity.application)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { Surface {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    ChatsScreen(state.value, vm, approval = {}, archivedOnly = true, deleteSelected = deleted, delete = swipeDelete)
                }
            } }
        } }
        compose.waitForIdle(); return vm
    }
    private fun state() = mutableStateOf(ScreenState(connection = "Connected", chats = listOf(first, second)))
    private fun selectFirst() { compose.onNodeWithTag("chat-row:one").performTouchInput { longClick() } }

    @Test fun longPressAndTapsSelectMultipleWithoutOpeningAChat() {
        val state = state(); val vm = render(state)
        selectFirst()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:one").assertIsSelected().assertIsOn()
        compose.onNodeWithTag("chat-row:two").performClick().assertIsSelected()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Options for chat First archived chat").assertDoesNotExist()
        compose.onNodeWithTag("chat-row:one").performClick().assertIsNotSelected()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.runOnIdle { assertNull(vm.state.value.selectedChat) }
    }

    @Test fun bulkDeletionNeedsConfirmationAndOnlyIncludesSelectedChats() {
        val state = state(); var deleted = emptyList<Chat>()
        render(state, deleted = { targets -> deleted = targets; state.value = state.value.copy(chats = state.value.chats.filterNot { it in targets }) })
        selectFirst(); compose.onNodeWithTag("chat-row:two").performClick()
        compose.onNodeWithTag("archive-delete-selected").performClick()
        compose.onNodeWithText("Delete 2 chats permanently?").assertIsDisplayed()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:two").performClick()
        compose.onNodeWithTag("archive-delete-selected").performClick()
        compose.onNodeWithText("Delete this chat permanently?").assertIsDisplayed()
        compose.onNodeWithTag("archive-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(listOf(first), deleted) }
        compose.onNodeWithText("First archived chat").assertDoesNotExist()
        compose.onNodeWithText("Second archived chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
    }

    @Test fun selectionDisablesSwipeAndSupportsAccessibleLongPressAndBack() {
        val state = state(); var swipes = 0
        render(state, swipeDelete = { swipes++ })
        compose.onNodeWithTag("chat-row:one").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("delete-swipe:two").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, swipes) }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithText("1 selected").assertDoesNotExist()
    }

    @Test fun disconnectedRunningQueuedAndBusySelectionsCannotBeDeleted() {
        val state = state(); render(state); selectFirst()
        compose.onNodeWithTag("archive-delete-selected").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(connection = "Offline") }
        compose.onNodeWithTag("archive-delete-selected").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(connection = "Connected", loading = true) }
        compose.onNodeWithTag("archive-delete-selected").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false, chats = listOf(first.copy(status = "active"), second)) }
        compose.onNodeWithTag("archive-delete-selected").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(chats = listOf(first, second), queuedMessages = listOf(QueuedMessage("q", "one", "waiting"))) }
        compose.onNodeWithTag("archive-delete-selected").assertIsNotEnabled()
    }

    @Test fun restoredOrRemovedChatCannotBeConfirmedFromAnOldDialog() {
        val state = state(); var deletes = 0; render(state, deleted = { deletes++ })
        selectFirst(); compose.onNodeWithTag("archive-delete-selected").performClick()
        compose.runOnIdle { state.value = state.value.copy(chats = listOf(first.copy(archived = false), second)) }
        compose.onNodeWithTag("archive-confirm-delete").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deletes) }
    }

    @Test fun filterChangeClearsThePreviousSelection() {
        val state = state(); render(state); selectFirst()
        compose.runOnIdle { state.value = state.value.copy(searchTerm = "Second") }
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithText("Second archived chat").assertIsDisplayed()
        compose.onNodeWithText("First archived chat").assertDoesNotExist()
    }

    @Test fun selectAllCanBeClearedAndDeletionStillRequiresConfirmation() {
        val state = state(); var deleted = emptyList<Chat>()
        render(state, deleted = { deleted = it })
        selectFirst()
        compose.onNodeWithText("Select all").assertIsDisplayed().performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:one").assertIsSelected()
        compose.onNodeWithTag("chat-row:two").assertIsSelected()
        compose.onNodeWithText("Deselect all").performClick()
        compose.onNodeWithContentDescription("Cancel selection").assertDoesNotExist()
        selectFirst(); compose.onNodeWithTag("archive-select-all").performClick()
        compose.onNodeWithTag("archive-delete-selected").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithTag("archive-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(listOf(first, second), deleted) }
    }

    @Test fun selectAllOnlyIncludesLoadedChatsInTheCurrentArchiveFilter() {
        val state = state()
        val other = first.copy(id = "other", title = "Other project", cwd = "/other")
        val active = first.copy(id = "active", title = "Active chat", archived = false)
        state.value = state.value.copy(chats = listOf(first, second, other, active), projectFilter = "p",
            projects = listOf(Project("p", "Selected project", listOf("/work"))), assignments = mapOf("one" to "p", "two" to "p"))
        var deleted = emptyList<Chat>(); render(state, deleted = { deleted = it })
        selectFirst(); compose.onNodeWithTag("archive-select-all").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("Loaded chats").assertIsDisplayed()
        compose.onNodeWithTag("archive-delete-selected").performClick()
        compose.onNodeWithTag("archive-confirm-delete").performClick()
        compose.runOnIdle { assertEquals(listOf(first, second), deleted) }
    }

    @Test fun newlyLoadedChatsAreOnlySelectedAfterSelectAllIsTappedAgain() {
        val state = state(); render(state)
        selectFirst(); compose.onNodeWithTag("archive-select-all").performClick()
        val third = first.copy(id = "three", title = "Third archived chat")
        compose.runOnIdle { state.value = state.value.copy(chats = state.value.chats + third) }
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:three").assertIsNotSelected()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        compose.onNodeWithTag("chat-row:three").assertIsSelected()
    }
}
