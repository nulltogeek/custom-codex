package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

internal fun canMoveChat(state: ScreenState) = canRename(state) && !state.creatingProject && !state.movingChat
internal fun chatBelongsToProject(chat: Chat, project: Project, assignments: Map<String, String>): Boolean {
    val assigned = assignments[chat.id] ?: chat.projectId
    return if (assigned != null) assigned == project.id else chat.cwd in project.roots
}
internal fun ScreenState.withMovedChat(id: String, projectId: String?): ScreenState = copy(
    assignments = if (projectId == null) assignments - id else assignments + (id to projectId),
    chats = chats.map { if (it.id == id) it.copy(projectId = projectId) else it },
    favoriteChats = favoriteChats.map { if (it.id == id) it.copy(projectId = projectId) else it },
    selectedChat = selectedChat?.let { if (it.id == id) it.copy(projectId = projectId) else it })
internal fun chatMoveParams(chatId: String, projectId: String) = jsonObject("threadId" to chatId, "projectId" to projectId)
internal fun cachedChatWithProject(raw: JsonObject, projectId: String) = JsonObject(raw + ("projectId" to JsonPrimitive(projectId)))
internal fun inventoryWithChatProject(raw: JsonObject, chatId: String, projectId: String) = JsonObject(raw +
    ("assignments" to JsonObject(raw.obj("assignments") + (chatId to JsonPrimitive(projectId)))))

@Composable internal fun ChatOptionsMenu(chat: Chat, renameEnabled: Boolean, moveEnabled: Boolean, rename: () -> Unit, move: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, "Options for chat ${chat.title}", Modifier.size(20.dp)) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename chat") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { expanded = false; rename() }, enabled = renameEnabled)
            DropdownMenuItem(text = { Text("Move to project") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                onClick = { expanded = false; move() }, enabled = moveEnabled)
        }
    }
}

@Composable internal fun MoveChatDialog(chat: Chat, projects: List<Project>, saving: Boolean, connected: Boolean,
    error: String?, dismiss: () -> Unit, move: (String) -> Unit) {
    var selected by rememberSaveable(chat.id) { mutableStateOf(chat.projectId) }
    val valid = projects.any { it.id == selected } && selected != chat.projectId
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("Move to project") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chat.title, style = MaterialTheme.typography.titleSmall)
            Text("Choose a project for this chat. Its history and working folder stay the same.", style = MaterialTheme.typography.bodySmall)
            if (projects.isEmpty()) Text("Create a project in Projects first.")
            projects.forEach { project ->
                Row(Modifier.fillMaxWidth().testTag("move-project:${project.id}").selectable(selected == project.id, enabled = !saving,
                    role = Role.RadioButton, onClick = { selected = project.id }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == project.id, onClick = null, enabled = !saving)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(project.name); if (project.id == chat.projectId) Text("Current project", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (!connected) Text("Reconnect to move this chat.")
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { selected?.let(move) }, enabled = connected && !saving && valid,
        modifier = Modifier.testTag("move-chat-save")) { Text(if (saving) "Moving…" else "Move") } },
        dismissButton = { TextButton(dismiss, enabled = !saving) { Text("Cancel") } })
}
