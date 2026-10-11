package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val AccessLabels = linkedMapOf("host" to "Host policy", "ask" to "Ask for approvals", "read-only" to "Read only", "full" to "Full access")
private val AccessDetails = mapOf("host" to "Keep this chat’s host policy", "ask" to "Workspace changes; ask for extra access", "read-only" to "Inspect files without changing them", "full" to "File and command access without approval prompts")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ComposerOptions(state: ScreenState, vm: RemoteViewModel) {
    var settings by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val enabled = state.selectedChat?.status != "active" && !state.sending
    val accessLabel = if (state.chatMode == "chat") "Read only" else AccessLabels[state.accessMode] ?: "Host policy"
    TextButton(onClick = { focus.clearFocus(); settings = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-settings").semantics {
        contentDescription = "Chat settings, mode: ${state.chatMode}, model: ${state.selectedModel.ifEmpty { "host default" }}, access: $accessLabel, reasoning: ${state.selectedEffort.ifEmpty { "model default" }}"
    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
        Text(if (state.chatMode == "chat") "Conversation" else "Codex", style = MaterialTheme.typography.labelMedium)
        Text(" · " + (state.models.find { it.id == state.selectedModel }?.name ?: "Host default"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat settings", style = MaterialTheme.typography.titleLarge)
            Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.chatMode == "codex", onClick = { vm.chatMode("codex") }, enabled = enabled, label = { Text("Codex") })
                FilterChip(selected = state.chatMode == "chat", onClick = { vm.chatMode("chat") }, enabled = enabled, label = { Text("Conversation") })
            }
            Text(if (state.chatMode == "chat") "Questions and discussion" else "Work on files and projects", style = MaterialTheme.typography.bodySmall)
            Text("Model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelMenu(state, vm, Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Access", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.chatMode == "chat") Text("Conversation uses read-only access.", style = MaterialTheme.typography.bodySmall)
            if (!enabled) Text("Access and mode can be changed after the current turn finishes.", style = MaterialTheme.typography.bodySmall)
            AccessLabels.forEach { (id, label) ->
                val selected = if (state.chatMode == "chat") id == "read-only" else state.accessMode == id
                Surface(onClick = { vm.accessMode(id) }, enabled = enabled && state.chatMode != "chat", shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null, enabled = enabled && state.chatMode != "chat")
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(AccessDetails.getValue(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            HorizontalDivider()
            Text("Reasoning effort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val efforts = state.models.find { it.id == state.selectedModel }?.efforts.orEmpty()
            if (efforts.isEmpty()) Text("Using the host or model default. Choose a model to see its available reasoning levels.", style = MaterialTheme.typography.bodySmall)
            else efforts.forEach { effort ->
                TextButton(onClick = { vm.effort(effort) }, enabled = !state.sending, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    RadioButton(state.selectedEffort == effort, onClick = null, enabled = !state.sending)
                    Spacer(Modifier.width(8.dp)); Text(effort.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                }
            }
            TextButton(onClick = { settings = false }, Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable private fun ModelMenu(state: ScreenState, vm: RemoteViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val label = state.models.find { it.id == state.selectedModel }?.name ?: "Host default"
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = !state.sending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Model: $label" }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Host default") }, onClick = { vm.model(""); expanded = false })
            state.models.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = { vm.model(model.id); expanded = false }) }
        }
    }
}
