package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun ThreadWriterNotice(state: ScreenState, onContinue: () -> Unit) {
    var confirm by remember(state.selectedChat?.id) { mutableStateOf(false) }
    val ready = state.connection == "Connected" && !state.sending && !state.uploading &&
        !state.uncertainSend && state.selectedChat?.archived != true
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("This chat is open in another Codex runtime", style = MaterialTheme.typography.labelLarge)
            Text("Your message has not been sent. Continue with a copy of the saved history, or close this chat in the other runtime and try Send again.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirm = true }, enabled = ready) { Text("Continue in a new chat") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Copy this chat?") },
        text = { Text("Saved history, your draft, attachments and chat settings will be copied. Work still running in the other runtime may be missing. The original chat and draft stay available. Review the copy and tap Send when ready.") },
        confirmButton = { TextButton(onClick = { confirm = false; onContinue() }, enabled = ready) { Text("Create copy") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
