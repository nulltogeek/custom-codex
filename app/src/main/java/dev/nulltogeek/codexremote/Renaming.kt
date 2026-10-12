package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

enum class RenameKind { Chat, Project }
data class RenameTarget(val kind: RenameKind, val id: String, val name: String)

internal fun canRename(state: ScreenState) = state.signedIn && state.connection == "Connected" && !state.loading && !state.renaming
internal fun renameValidation(name: String): String? = when {
    name.trim().isEmpty() -> "Enter a name."
    name.trim().length > 200 -> "Use at most 200 characters."
    name.any { it.code < 32 || it.code == 127 } -> "Use a single line without control characters."
    else -> null
}

internal fun ScreenState.withRenamedChat(id: String, name: String): ScreenState = copy(
    chats = chats.map { if (it.id == id) it.copy(title = name) else it },
    favoriteChats = favoriteChats.map { if (it.id == id) it.copy(title = name) else it },
    selectedChat = selectedChat?.let { if (it.id == id) it.copy(title = name) else it })

internal fun renameCachedChat(raw: JsonObject, name: String) = JsonObject(raw + ("name" to JsonPrimitive(name)))

@Composable internal fun RenameDialog(target: RenameTarget, saving: Boolean, connected: Boolean, error: String?,
    dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable(target.kind, target.id) { mutableStateOf(target.name) }
    val validation = renameValidation(name)
    val changed = name.trim() != target.name
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(if (target.kind == RenameKind.Chat) "Rename chat" else "Rename project") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("rename-name"),
                label = { Text("Name") }, singleLine = true, enabled = !saving, isError = validation != null)
            if (validation != null) Text(validation, color = MaterialTheme.colorScheme.error)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (!connected) Text("Reconnect to save this name.")
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = connected && !saving && changed && validation == null,
            modifier = Modifier.testTag("rename-save")) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text("Cancel") } })
}
