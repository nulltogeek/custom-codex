package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun MessageQueueView(state: ScreenState, edit: (QueuedMessage, String, List<Attachment>) -> Boolean,
    move: (String, Int) -> Unit, remove: (String) -> Unit, pause: (String) -> Boolean, resume: (String) -> Unit,
    retry: (String) -> Unit) {
    val chatId = state.selectedChat?.id ?: return
    val messages = state.queuedMessages.filter { it.chatId == chatId }
    if (messages.isEmpty()) return
    var expanded by rememberSaveable(chatId) { mutableStateOf(false) }
    var editingId by remember(chatId) { mutableStateOf<String?>(null) }
    var retrying by remember(chatId) { mutableStateOf<QueuedMessage?>(null) }
    val paused = chatId in state.pausedQueues || state.queueBlocked
    val uncertain = messages.any { it.delivery == "uncertain" }
    Column(Modifier.fillMaxWidth().testTag("message-queue")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                Text("${messages.size} queued${if (paused) " · paused" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Collapse message queue" else "Expand message queue")
            }
            TextButton(onClick = {
                if (paused) resume(chatId) else if (pause(chatId)) {
                    expanded = true
                    editingId = messages.firstOrNull { it.delivery == "queued" }?.id
                }
            }, enabled = !uncertain) {
                Text(if (paused) "Resume queue" else "Pause queue", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sent in order while this app is open and connected.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            messages.forEachIndexed { index, message -> key(message.id) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("queue:${message.id}")) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("${index + 1}. ${message.text.ifBlank { "Attached files" }}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        if (message.attachments.isNotEmpty()) Text(message.attachments.joinToString { it.name }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        if (message.delivery == "sending") Text("Sending…", style = MaterialTheme.typography.labelSmall)
                        if (message.error.isNotEmpty()) Text(message.error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { move(message.id, -1) }, enabled = index > 0 && message.delivery == "queued" && messages[index - 1].delivery == "queued") {
                                Icon(Icons.Outlined.ArrowUpward, "Move queued message ${index + 1} up", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { move(message.id, 1) }, enabled = index < messages.lastIndex && message.delivery == "queued" && messages[index + 1].delivery == "queued") {
                                Icon(Icons.Outlined.ArrowDownward, "Move queued message ${index + 1} down", Modifier.size(18.dp))
                            }
                            if (message.delivery == "uncertain") IconButton(onClick = { retrying = message }) {
                                Icon(Icons.Outlined.Refresh, "Review retry for queued message ${index + 1}", Modifier.size(18.dp))
                            } else IconButton(onClick = { if (pause(chatId)) editingId = message.id }, enabled = message.delivery == "queued") {
                                Icon(Icons.Outlined.Edit, "Edit queued message ${index + 1}", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { remove(message.id) }, enabled = message.delivery != "sending") {
                                Icon(Icons.Outlined.Close, "Remove queued message ${index + 1}", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            } }
        }
    }
    val editing = messages.find { it.id == editingId && it.delivery == "queued" }
    LaunchedEffect(editingId, editing?.id) { if (editingId != null && editing == null) editingId = null }
    editing?.let { message ->
        var text by remember(message.id) { mutableStateOf(message.text) }
        var attachments by remember(message.id) { mutableStateOf(message.attachments) }
        AlertDialog(onDismissRequest = { editingId = null }, title = { Text("Edit queued message") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(text, { text = it }, label = { Text("Queued message") }, modifier = Modifier.fillMaxWidth(), maxLines = 6)
                attachments.forEach { file -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { attachments = attachments.filterNot { it.id == file.id } }) { Icon(Icons.Outlined.Close, "Remove queued attachment ${file.name}") }
                } }
                Text("Queue stays paused until you tap Resume queue.", style = MaterialTheme.typography.bodySmall)
                if (state.queueBlocked) Text(state.infoNotices[NoticeKey(NoticeKind.QueueStorage)] ?: state.notice ?: "Could not save the queue. Try saving again.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { if (edit(message, text, attachments)) editingId = null },
                enabled = text.isNotBlank() || attachments.isNotEmpty()) { Text("Save message") } },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("Cancel") } })
    }
    retrying?.let { message -> AlertDialog(onDismissRequest = { retrying = null }, title = { Text("Retry this queued message?") },
        text = { Text("Check chat history first. If the message was already delivered, remove it from the queue. Retrying could send it twice. The queue will stay paused until you resume it.") },
        confirmButton = { TextButton(onClick = { retry(message.id); retrying = null }) { Text("Retry message") } },
        dismissButton = { TextButton(onClick = { retrying = null }) { Text("Cancel") } }) }
}
