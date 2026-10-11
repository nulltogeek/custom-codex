package dev.nulltogeek.codexremote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun ComposerOptions(state: ScreenState, vm: RemoteViewModel) {
    var modeMenu by remember { mutableStateOf(false) }; var accessMenu by remember { mutableStateOf(false) }
    val enabled = state.selectedChat?.status != "active" && !state.sending
    val accessLabels = mapOf("host" to "Host policy", "ask" to "Ask for approvals", "read-only" to "Read only", "full" to "Full access")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { modeMenu = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(if (state.chatMode == "chat") Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Code, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(if (state.chatMode == "chat") "Conversation" else "Codex"); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            DropdownMenu(modeMenu, { modeMenu = false }) {
                DropdownMenuItem(text = { Column { Text("Codex"); Text("Work on files and projects", style = MaterialTheme.typography.bodySmall) } }, onClick = { vm.chatMode("codex"); modeMenu = false })
                DropdownMenuItem(text = { Column { Text("Conversation"); Text("Questions and discussion", style = MaterialTheme.typography.bodySmall) } }, onClick = { vm.chatMode("chat"); modeMenu = false })
            }
        }
        Box {
            OutlinedButton(onClick = { accessMenu = true }, enabled = enabled && state.chatMode == "codex", contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Outlined.Security, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(if (state.chatMode == "chat") "Read-only conversation" else accessLabels[state.accessMode] ?: "Host policy"); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
            }
            DropdownMenu(accessMenu, { accessMenu = false }) {
                val details = mapOf("host" to "Keep this chat’s host policy", "ask" to "Workspace changes; ask for extra access", "read-only" to "Inspect files without changing them", "full" to "File and command access without approval prompts")
                accessLabels.forEach { (id, label) -> DropdownMenuItem(text = { Column { Text(label); Text(details.getValue(id), style = MaterialTheme.typography.bodySmall) } }, onClick = { vm.accessMode(id); accessMenu = false }) }
            }
        }
    }
}
