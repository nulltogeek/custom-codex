package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProjectCreationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun createAllowsEmptyFoldersAndBlocksInvalidPaths() {
        var saved: Pair<String, String>? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark") { NewProjectDialog("key", null, false, true, null, {}, { name, roots -> saved = name to roots }) }
        } }
        compose.onNodeWithTag("project-create").assertIsNotEnabled()
        compose.onNodeWithTag("project-name").performTextInput("Work")
        compose.onNodeWithTag("project-folders").performTextInput("relative/folder")
        compose.onNodeWithTag("project-create").assertIsNotEnabled()
        compose.onNodeWithTag("project-folders").performTextClearance()
        compose.onNodeWithTag("project-create").performClick()
        compose.runOnIdle { assertEquals("Work" to "", saved) }
    }
    @Test fun retryKeepsUnconfirmedCreationDetailsAndShowsTheProblem() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light") { NewProjectDialog("key", ProjectCreateRequest("Work", listOf("/work")), false, true,
                "Creation could not be confirmed", {}, { _, _ -> }) }
        } }
        compose.onNodeWithText("Creation could not be confirmed").assertIsDisplayed()
        compose.onNodeWithTag("project-name").assertIsNotEnabled()
        compose.onNodeWithTag("project-folders").assertIsNotEnabled()
        compose.onNodeWithText("Retry creation").assertIsEnabled()
    }
}
