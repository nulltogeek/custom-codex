package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

internal fun canArchive(state: ScreenState, chat: Chat): Boolean = state.connection == "Connected" && !state.loading &&
    !state.sending && chat.status != "active" && state.pending.none { it.params.text("threadId") == chat.id }

internal fun canDeleteChat(state: ScreenState, chat: Chat): Boolean = chat.archived && canArchive(state, chat) &&
    state.queuedMessages.none { it.chatId == chat.id }

internal fun withoutDeletedChat(state: ScreenState, id: String): ScreenState {
    val selected = state.selectedChat?.id == id
    return state.copy(chats = state.chats.filterNot { it.id == id }, favoriteChats = state.favoriteChats.filterNot { it.id == id },
        assignments = state.assignments - id, catalogRevision = state.catalogRevision + 1,
        activeCursor = null, archivedCursor = null, catalogError = null, catalogErrorArchived = null, pending = state.pending.filterNot { it.params.text("threadId") == id },
        selectedChat = if (selected) null else state.selectedChat, timeline = if (selected) emptyList() else state.timeline,
        diff = if (selected) null else state.diff, draft = if (selected) "" else state.draft,
        attachments = if (selected) emptyList() else state.attachments,
        page = if (selected) "archived" else state.page, historyRevision = state.historyRevision + if (selected) 1 else 0)
}

@Composable internal fun FavoriteButton(favorite: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            if (favorite) "Remove $label from favorites" else "Add $label to favorites",
            Modifier.size(20.dp), tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SwipeArchiveRow(chat: Chat, enabled: Boolean, archive: () -> Unit,
    deleteEnabled: Boolean = false, delete: () -> Unit = {}, content: @Composable () -> Unit) {
    val latestArchive by rememberUpdatedState(archive)
    val latestDelete by rememberUpdatedState(delete)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestDeleteEnabled by rememberUpdatedState(deleteEnabled)
    // These are physical right/left gestures in either layout direction.
    val rightSwipe = if (LocalLayoutDirection.current == LayoutDirection.Ltr) SwipeToDismissBoxValue.StartToEnd else SwipeToDismissBoxValue.EndToStart
    val leftSwipe = if (rightSwipe == SwipeToDismissBoxValue.StartToEnd) SwipeToDismissBoxValue.EndToStart else SwipeToDismissBoxValue.StartToEnd
    val latestRightSwipe by rememberUpdatedState(rightSwipe)
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { target ->
        if (target != SwipeToDismissBoxValue.Settled) {
            if (target == latestRightSwipe && latestEnabled) latestArchive()
            else if (target != latestRightSwipe && latestDeleteEnabled) latestDelete()
        }
        // Confirmation owns the action; the row returns to its resting position.
        false
    }, positionalThreshold = { it * 0.35f })
    SwipeToDismissBox(swipe, modifier = Modifier.testTag("archive-swipe:${chat.id}").semantics {
        customActions = buildList {
            if (enabled) add(CustomAccessibilityAction(if (chat.archived) "Restore chat" else "Archive chat") { latestArchive(); true })
            if (deleteEnabled) add(CustomAccessibilityAction("Delete chat") { latestDelete(); true })
        }
    }, enableDismissFromStartToEnd = if (rightSwipe == SwipeToDismissBoxValue.StartToEnd) enabled else deleteEnabled,
        enableDismissFromEndToStart = if (rightSwipe == SwipeToDismissBoxValue.EndToStart) enabled else deleteEnabled,
        gesturesEnabled = enabled || deleteEnabled,
        backgroundContent = {
            val deleting = swipe.dismissDirection == leftSwipe
            val colors = MaterialTheme.colorScheme
            Surface(color = if (deleting) colors.errorContainer else colors.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if ((if (deleting) leftSwipe else rightSwipe) == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End) {
                    Icon(if (deleting) Icons.Outlined.DeleteOutline else if (chat.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null)
                    Spacer(Modifier.width(8.dp)); Text(if (deleting) "Delete" else if (chat.archived) "Restore" else "Archive")
                }
            }
        }) { content() }
}

internal fun passwordValidation(current: String, next: String, confirmation: String): String? = when {
    current.isEmpty() -> "Enter your current password."
    next.length < 12 || next.toByteArray(Charsets.UTF_8).size > 1024 -> "Use at least 12 characters and at most 1024 bytes."
    next == current -> "Choose a different password."
    next != confirmation -> "New passwords do not match."
    else -> null
}

@Composable internal fun ChangePasswordDialog(changing: Boolean, message: String, changed: Boolean, connected: Boolean,
    onDismiss: () -> Unit, changePassword: (String, String) -> Unit) {
    // Credentials never enter saved state, preferences, logs, or the history cache.
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    LaunchedEffect(changed) { if (changed) { current = ""; next = ""; confirmation = "" } }
    val validation = passwordValidation(current, next, confirmation)
    AlertDialog(onDismissRequest = { if (!changing) onDismiss() }, title = { Text("Change password") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!changed) {
                Text("Other sessions will be signed out. This phone stays signed in and running work continues.", style = MaterialTheme.typography.bodySmall)
                PasswordField("Current password", current, !changing) { current = it }
                PasswordField("New password", next, !changing) { next = it }
                PasswordField("Confirm new password", confirmation, !changing) { confirmation = it }
                Text(if (next.isNotEmpty() && validation != null) validation else "Use at least 12 characters. Password spaces are preserved.", style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message, color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (changing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        if (changed) TextButton(onDismiss) { Text("Close") }
        else TextButton(onClick = { changePassword(current, next) }, enabled = connected && !changing && validation == null) { Text(if (changing) "Saving…" else "Save password") }
    }, dismissButton = { if (!changed) TextButton(onDismiss, enabled = !changing) { Text("Cancel") } })
}

@Composable private fun PasswordField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
}
