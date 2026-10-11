package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun TaskNotificationSettings(enabled: Boolean, message: String, change: (Boolean) -> Unit, openSettings: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Task completion notifications")
                Text("Get an alert when Codex finishes a task", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, change, Modifier.testTag("task-notifications"))
        }
        Text("Tap an alert to open its chat. A quiet monitoring notification keeps alerts available while the app is in the background. Android may pause monitoring; reopening the app resumes it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(openSettings) { Text("Android notification settings") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
