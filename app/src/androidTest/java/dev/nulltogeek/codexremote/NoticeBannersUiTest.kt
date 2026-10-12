package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NoticeBannersUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun reconnectRemovesConnectionBannerAndKeepsUnresolvedWarnings() {
        val connection = NoticeKey(NoticeKind.Connection)
        val history = NoticeKey(NoticeKind.History, "chat")
        val state = mutableStateOf(ScreenState(notice = "Check delivery before resending")
            .withInfoNotice(connection, "Connection lost").withInfoNotice(history, "History unavailable"))
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { NoticeBanners(state.value,
                { state.value = state.value.copy(notice = null) }, { state.value = state.value.resolveInfoNotice(it) }) }
        } }
        compose.onNodeWithText("Connection lost").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.withConnectionStatus("Connected") }
        compose.onNodeWithText("Connection lost").assertDoesNotExist()
        compose.onNodeWithText("History unavailable").assertIsDisplayed()
        compose.onNodeWithText("Check delivery before resending").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.resolveInfoNotice(history) }
        compose.onNodeWithText("History unavailable").assertDoesNotExist()
        compose.onNodeWithText("Check delivery before resending").assertIsDisplayed()
    }
    @Test fun dismissingAnInfoBannerDoesNotDismissOtherProblems() {
        val connection = NoticeKey(NoticeKind.Connection); val search = NoticeKey(NoticeKind.Search)
        val state = mutableStateOf(ScreenState().withInfoNotice(connection, "Connection lost").withInfoNotice(search, "Search unavailable"))
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("light", motion = false) { NoticeBanners(state.value, {}, { state.value = state.value.resolveInfoNotice(it) }) }
        } }
        compose.onNodeWithTag("info-notice:Connection:").onChildren().filter(hasContentDescription("Dismiss notice")).onFirst().performClick()
        compose.onNodeWithText("Connection lost").assertDoesNotExist()
        compose.onNodeWithText("Search unavailable").assertIsDisplayed()
    }
}
