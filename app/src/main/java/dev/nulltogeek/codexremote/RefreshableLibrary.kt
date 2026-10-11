package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

internal fun canRefreshHistory(state: ScreenState): Boolean =
    state.signedIn && state.connection == "Connected" && !state.loading && !state.refreshing

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RefreshableLibrary(state: ScreenState, refresh: () -> Unit, content: @Composable () -> Unit) {
    val pullState = rememberPullToRefreshState()
    val latestRefresh by rememberUpdatedState(refresh)
    val enabled = canRefreshHistory(state)
    Box(Modifier.fillMaxSize().pullToRefresh(
        isRefreshing = state.refreshing, state = pullState, enabled = enabled,
        onRefresh = { if (enabled) latestRefresh() }
    ).semantics {
        if (enabled) customActions = listOf(CustomAccessibilityAction("Refresh list") { latestRefresh(); true })
    }) {
        content()
        PullToRefreshDefaults.Indicator(
            state = pullState, isRefreshing = state.refreshing,
            modifier = Modifier.align(Alignment.TopCenter).testTag("pull-refresh-indicator").semantics {
                contentDescription = if (state.refreshing) "Refreshing list" else "Pull down to refresh"
            }
        )
    }
}
