package dev.nulltogeek.codexremote

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun ChatComposer(state: ScreenState, vm: RemoteViewModel, onPick: (Array<String>) -> Unit) {
    var attachmentMenu by remember { mutableStateOf(false) }
    val canAttach = state.connection == "Connected" && !state.uploading && !state.sending && state.selectedChat?.archived != true
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 4.dp).testTag("chat-composer"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ComposerOptions(state, vm)
            if (state.attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.attachments, key = { it.id }) { file ->
                    AttachmentTile(file, !state.sending) { vm.removeAttachment(file.id) }
                }
            }
            if (state.uploading) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Uploading attachment…", style = MaterialTheme.typography.labelSmall)
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
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        FilledIconButton(onClick = { vm.send() },
                            enabled = (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && canAttach && !state.uncertainSend,
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
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.previewPath) {
        if (file.previewPath.isNotEmpty()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(file.previewPath)
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.width(204.dp).height(64.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val preview = bitmap
            if (preview != null) androidx.compose.foundation.Image(preview.asImageBitmap(), "Preview of ${file.name}", Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Icon(if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null, Modifier.size(40.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Text(if (file.uploading) "Uploading…" else "Ready", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = remove, enabled = removable) { Icon(Icons.Outlined.Close, if (file.uploading) "Cancel upload ${file.name}" else "Remove ${file.name}", Modifier.size(18.dp)) }
        }
    }
}
