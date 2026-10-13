package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun canDeleteChats(state: ScreenState, chats: List<Chat>) = chats.isNotEmpty() &&
    chats.all { it.id.isNotBlank() && canDeleteChat(state, it) }

internal data class ArchiveDeletionResult(val deleted: Int, val error: String? = null)

/** Sequential, acknowledged deletions; stop on failure and retain the remaining chats. */
internal suspend fun deleteArchivedChats(chats: List<Chat>, eligible: (Chat) -> Boolean,
    call: suspend (String, JsonObject) -> JsonObject, removed: (Chat) -> Unit): ArchiveDeletionResult {
    val targets = chats.distinctBy { it.id }
    var deleted = 0
    try {
        check(targets.isNotEmpty() && targets.all { it.id.isNotBlank() && eligible(it) }) {
            "Finish work and remove queued messages before deleting these archived chats."
        }
        for (chat in targets) {
            check(eligible(chat)) { "This chat is no longer available for deletion. Refresh archived history." }
            val metadata = call("thread/read", buildJsonObject { put("threadId", chat.id); put("includeTurns", false) })
            check(metadata.obj("thread").obj("status").text("type") != "active") {
                "Finish the running task before deleting this chat."
            }
            check(eligible(chat)) { "This chat is no longer available for deletion. Refresh archived history." }
            try { call("thread/delete", jsonObject("threadId" to chat.id)) }
            catch (e: Exception) {
                if (e is CancellationException || e is SessionExpiredException) throw e
                if (e is RpcException) throw IllegalStateException("Chat was not deleted: ${e.message}. Your host must support chat deletion.")
                throw IllegalStateException("Deletion could not be confirmed. Refresh archived history before trying again.")
            }
            removed(chat)
            deleted++
        }
        return ArchiveDeletionResult(deleted)
    } catch (e: Exception) {
        if (e is CancellationException || e is SessionExpiredException) throw e
        return ArchiveDeletionResult(deleted, e.message ?: "Could not delete this chat.")
    }
}

@Composable internal fun ArchiveSelectionBar(count: Int, deleteEnabled: Boolean, cancel: () -> Unit, delete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(cancel) { Icon(Icons.Outlined.Close, "Cancel selection") }
            Text("$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(delete, enabled = deleteEnabled, modifier = Modifier.testTag("archive-delete-selected")) {
                Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("Delete")
            }
        }
    }
}

@Composable internal fun ArchiveDeleteDialog(count: Int, enabled: Boolean, dismiss: () -> Unit, delete: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (count == 1) "Delete this chat permanently?" else "Delete $count chats permanently?") },
        text = { Text(if (count == 1) "Delete the selected chat from your computer and this phone. This cannot be undone."
            else "Delete these $count selected chats from your computer and this phone. This cannot be undone.") },
        confirmButton = { TextButton(delete, enabled = enabled, modifier = Modifier.testTag("archive-confirm-delete")) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
