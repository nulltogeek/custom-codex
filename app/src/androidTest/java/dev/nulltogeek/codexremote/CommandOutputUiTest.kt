package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommandOutputUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun switchCanDisableDownloadsAndBlocksRepeatedChangesWhileApplying() {
        val enabled = mutableStateOf(true); val changing = mutableStateOf(false)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light") { CommandOutputSettings(enabled.value, changing.value, "", { enabled.value = it }) }
        } }
        compose.onNodeWithTag("load-command-output").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(enabled.value); changing.value = true }
        compose.onNodeWithTag("load-command-output").assertIsNotEnabled()
    }
    @Test fun downloadPreferenceSurvivesAStoreRecreation() {
        val app = compose.activity.application
        val original = LocalStore(app).loadCommandOutput
        try {
            LocalStore(app).loadCommandOutput(false); assertFalse(LocalStore(app).loadCommandOutput)
            LocalStore(app).loadCommandOutput(true); assertTrue(LocalStore(app).loadCommandOutput)
        } finally { LocalStore(app).loadCommandOutput(original) }
    }
    @Test fun expandedCommandExplainsOmittedOutputAndKeepsItsName() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark") { MessageItem(TimelineItem("cmd", "commandExecution", "private downloaded output", "run tests",
                status = "completed", commandOutputOmitted = true), {}, { ByteArray(0) }, {}) }
        } }
        compose.onNodeWithTag("command:cmd").performClick()
        compose.onNodeWithTag("command-output-omitted:cmd").assertIsDisplayed()
        compose.onAllNodesWithText("run tests").assertCountEquals(2)
        compose.onNodeWithText("private downloaded output").assertDoesNotExist()
    }
}
