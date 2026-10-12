package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RenameUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun chatDialogStartsWithCurrentNameAndSavesTheEditedName() {
        var saved: String? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark") { RenameDialog(RenameTarget(RenameKind.Chat, "c", "Current name"), false, true, null, {}, { saved = it }) }
        } }
        compose.onNodeWithTag("rename-name").assertTextContains("Current name")
        compose.onNodeWithTag("rename-save").assertIsNotEnabled()
        compose.onNodeWithTag("rename-name").performTextReplacement("  New name  ")
        compose.onNodeWithTag("rename-save").performClick()
        compose.runOnIdle { assertEquals("New name", saved) }
    }
    @Test fun projectDialogKeepsFailuresVisibleAndRejectsBlankNames() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light") { RenameDialog(RenameTarget(RenameKind.Project, "p", "Work"), false, true, "Could not rename", {}, {}) }
        } }
        compose.onNodeWithText("Rename project").assertIsDisplayed()
        compose.onNodeWithText("Could not rename").assertIsDisplayed()
        compose.onNodeWithTag("rename-name").performTextReplacement(" ")
        compose.onNodeWithTag("rename-save").assertIsNotEnabled()
    }
}
