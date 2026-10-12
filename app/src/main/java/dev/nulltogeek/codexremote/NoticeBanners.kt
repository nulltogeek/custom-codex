package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun NoticeBanners(state: ScreenState, dismiss: () -> Unit, dismissInfo: (NoticeKey) -> Unit) {
    state.notice?.let { NoticeBanner(it, "notice", dismiss) }
    state.infoNotices.forEach { (key, message) ->
        NoticeBanner(message, "info-notice:${key.kind}:${key.chatId}") { dismissInfo(key) }
    }
}

@Composable private fun NoticeBanner(message: String, tag: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.testTag(tag)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss notice") }
        }
    }
}
