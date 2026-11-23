package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ChatComposer(state: ScreenState, vm: RemoteViewModel, onPick: (Array<String>) -> Unit) {
    var attachmentMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val canAttach = state.connection == "Connected" && !state.uploading && !state.sending && state.selectedChat?.archived != true
    val canSend = (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && state.attachments.all { it.readyToSend } && canAttach && !state.uncertainSend
    val running = state.selectedChat?.status == "active" || state.activeTurnId.isNotEmpty()
    val queueMessage = running || state.queuedMessages.any { it.chatId == state.selectedChat?.id }
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp).testTag("chat-composer"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MessageQueueView(state, edit = { message, text, attachments -> vm.editQueued(message.id, text, attachments) },
            move = vm::moveQueued, remove = vm::removeQueued, pause = vm::pauseQueue, resume = vm::resumeQueue, retry = vm::retryQueued)
        if (state.uncertainSend) TextButton(onClick = { vm.refresh() }) { Text("Refresh and check delivery") }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                if (state.attachments.isNotEmpty()) {
                    Text(attachmentSummary(state.attachments), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = if (state.attachments.any { it.uploadError.isNotEmpty() }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("attachment-strip"), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.attachments, key = { it.id }) { file -> AttachmentTile(file, !state.sending) { vm.removeAttachment(file.id) } }
                    }
                }
                MessageDraftInput(state.draft, vm::draft, vm::send, canSend,
                    if (queueMessage) "Add a follow-up…" else if (state.chatMode == "chat") "Ask anything…" else "Describe a task…",
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { attachmentMenu = true }, enabled = canAttach, modifier = Modifier.testTag("composer-attach")) {
                            Icon(Icons.Outlined.Add, "Attach file or image", Modifier.size(23.dp))
                        }
                        DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                            DropdownMenuItem(text = { Text("Photo or image") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { attachmentMenu = false; onPick(arrayOf("image/*")) })
                            DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) }, onClick = { attachmentMenu = false; onPick(arrayOf("*/*")) })
                        }
                    }
                    Box {
                        TextButton(onClick = { modeMenu = true }, enabled = !running && !state.sending, contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.testTag("composer-mode"), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Text(if (state.chatMode == "chat") "Chat" else "Codex", style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(15.dp))
                        }
                        DropdownMenu(modeMenu, { modeMenu = false }) {
                            DropdownMenuItem(text = { Column { Text("Chat"); Text("Ask questions and explore ideas", style = MaterialTheme.typography.labelSmall) } },
                                leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) }, onClick = { vm.chatMode("chat"); modeMenu = false })
                            DropdownMenuItem(text = { Column { Text("Codex"); Text("Work on files and projects", style = MaterialTheme.typography.labelSmall) } },
                                leadingIcon = { Icon(Icons.Outlined.Code, null) }, onClick = { vm.chatMode("codex"); modeMenu = false })
                        }
                    }
                    Box(Modifier.weight(1f)) { ComposerOptions(state, vm, compact = true) }
                    if (running) IconButton(onClick = { vm.interrupt() }, enabled = state.connection == "Connected" && !state.loading,
                        modifier = Modifier.size(44.dp).testTag("stop-work")) { Icon(Icons.Outlined.StopCircle, "Stop", Modifier.size(25.dp)) }
                    Box(Modifier.size(44.dp).testTag("send-message"), contentAlignment = Alignment.Center) {
                        FilledIconButton(onClick = { vm.send() }, enabled = canSend, modifier = Modifier.size(36.dp), shape = CircleShape) {
                            if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.ArrowUpward, if (queueMessage) "Queue message" else "Send", Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun MessageDraftInput(draft: String, onChange: (String) -> Unit, onSend: () -> Unit,
    canSend: Boolean, placeholder: String, modifier: Modifier = Modifier) {
    var field by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    val value = if (field.text == draft) field else field.copy(text = draft, composition = null)
    SideEffect { if (field != value) field = value }
    fun updateField(next: TextFieldValue) { field = next; if (next.text != draft) onChange(next.text) }
    BasicTextField(value, ::updateField, modifier = modifier.heightIn(min = 48.dp, max = 144.dp).padding(vertical = 14.dp)
        .semantics { contentDescription = "Message input" }.onPreviewKeyEvent { event ->
            val enterKey = event.key in listOf(Key.Enter, Key.NumPadEnter) &&
                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
            if (enterKey) {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    if (event.isShiftPressed) updateField(insertDraftLineBreak(value)) else if (canSend) onSend()
                }
                true
            } else false
        }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        decorationBox = { input -> Box {
            if (draft.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            input()
        } })
}

internal fun insertDraftLineBreak(value: TextFieldValue): TextFieldValue {
    val start = value.selection.min
    return value.copy(text = value.text.replaceRange(start, value.selection.max, "\n"),
        selection = TextRange(start + 1), composition = null)
}

@Composable private fun AttachmentTile(file: Attachment, removable: Boolean, remove: () -> Unit) {
    var showPreview by remember(file.id, file.previewPath) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, file.previewPath) {
        if (file.previewPath.isNotEmpty()) value = withContext(Dispatchers.IO) {
            decodeImagePreview(file.previewPath, 256)
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.width(256.dp).height(88.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val preview = bitmap
            if (preview != null) Image(preview.asImageBitmap(), "Preview of ${file.name}",
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Open image ${file.name}") { showPreview = true }, contentScale = ContentScale.Crop)
            else Icon(if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(40.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (file.uploading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    else Icon(if (file.readyToSend) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        null, Modifier.size(14.dp), tint = if (file.readyToSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text(attachmentStatus(file), style = MaterialTheme.typography.labelSmall,
                        color = if (file.uploadError.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (file.uploadError.isNotEmpty()) Text(file.uploadError, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else if (file.uploading && file.uploadProgress in 0..100) LinearProgressIndicator(progress = { file.uploadProgress / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            IconButton(onClick = remove, enabled = removable) { Icon(Icons.Outlined.Close, if (file.uploading) "Cancel upload ${file.name}" else "Remove ${file.name}", Modifier.size(18.dp)) }
        }
    }
    if (showPreview) {
        var loading by remember(file.previewPath) { mutableStateOf(true) }
        val fullPreview by produceState<Bitmap?>(null, file.previewPath) {
            value = withContext(Dispatchers.IO) { decodeImagePreview(file.previewPath, 2048) }
            loading = false
        }
        ImagePreviewDialog(file.name, fullPreview, loading) { showPreview = false }
    }
}
