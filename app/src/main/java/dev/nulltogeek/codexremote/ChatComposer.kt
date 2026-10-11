package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ChatComposer(state: ScreenState, vm: RemoteViewModel, onPick: (Array<String>) -> Unit) {
    var attachmentMenu by remember { mutableStateOf(false) }
    val canAttach = state.connection == "Connected" && !state.uploading && !state.sending && state.selectedChat?.archived != true
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 4.dp).testTag("chat-composer"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ComposerOptions(state, vm)
            if (state.attachments.isNotEmpty()) {
                Text(attachmentSummary(state.attachments), style = MaterialTheme.typography.labelSmall,
                    color = if (state.attachments.any { it.uploadError.isNotEmpty() }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                LazyRow(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("attachment-strip"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.attachments, key = { it.id }) { file ->
                        AttachmentTile(file, !state.sending) { vm.removeAttachment(file.id) }
                    }
                }
            }
            if (state.uncertainSend) TextButton(onClick = { vm.refresh() }) { Text("Refresh and check delivery") }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Box {
                        IconButton(onClick = { attachmentMenu = true }, enabled = canAttach) { Icon(Icons.Outlined.AttachFile, "Attach file or image", Modifier.size(20.dp)) }
                        DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                            DropdownMenuItem(text = { Text("Photo or image") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { attachmentMenu = false; onPick(arrayOf("image/*")) })
                            DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Outlined.InsertDriveFile, null) }, onClick = { attachmentMenu = false; onPick(arrayOf("*/*")) })
                        }
                    }
                    BasicTextField(state.draft, vm::draft, modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 144.dp).padding(vertical = 14.dp).semantics { contentDescription = "Message input" },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5,
                        decorationBox = { input ->
                            Box {
                                if (state.draft.isEmpty()) Text(if (state.selectedChat?.status == "active") "Guide the current work…" else if (state.chatMode == "chat") "Ask a question…" else "Message Codex…",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                input()
                            }
                        })
                    if (state.selectedChat?.status == "active" || state.activeTurnId.isNotEmpty()) IconButton(
                        onClick = { vm.interrupt() }, enabled = state.connection == "Connected" && !state.loading,
                        modifier = Modifier.size(48.dp).testTag("stop-work")
                    ) { Icon(Icons.Outlined.Stop, "Stop", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error) }
                    Box(Modifier.size(48.dp).testTag("send-message"), contentAlignment = Alignment.Center) {
                        FilledIconButton(onClick = { vm.send() },
                            enabled = (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && state.attachments.all { it.readyToSend } && canAttach && !state.uncertainSend,
                            modifier = Modifier.size(40.dp)) {
                            if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
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
            else Icon(if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(40.dp))
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
