package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun SentMessageEditDialog(edit: SentMessageEdit, connected: Boolean, running: Boolean,
    onText: (String) -> Unit, onRemove: (String) -> Unit, onSave: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit,
    stopPending: Boolean = false) {
    AlertDialog(onDismissRequest = { if (!edit.working) onDismiss() }, title = { Text("Edit your message") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(edit.text, onText, Modifier.fillMaxWidth().testTag("sent-message-edit-input"),
                enabled = !edit.working, label = { Text("Your message") }, minLines = 2, maxLines = 8)
            edit.attachments.forEach { file -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { onRemove(file.id) }, enabled = !edit.working) {
                    Icon(Icons.Outlined.Close, "Remove original attachment ${file.name}")
                }
            } }
            Text("Resends your edit in a new chat from before this exchange. Your original chat stays available. Existing file changes stay on your computer.",
                style = MaterialTheme.typography.bodySmall)
            if (running) {
                Text("Stop the running task before resending.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onStop, enabled = connected && !edit.working && !stopPending) { Text(if (stopPending) "Stopping…" else "Stop work") }
            }
            if (!connected) Text("Reconnect to resend. Your edit stays here.", color = MaterialTheme.colorScheme.error)
            edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sent-message-edit-error")) }
            if (edit.working) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Preparing edited chat…")
            }
        } },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.testTag("save-sent-message"),
            enabled = connected && !running && !stopPending && !edit.working && !edit.uncertainFork && (edit.text.isNotBlank() || edit.attachments.isNotEmpty())) { Text("Save & resend") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !edit.working) { Text("Cancel") } })
}
