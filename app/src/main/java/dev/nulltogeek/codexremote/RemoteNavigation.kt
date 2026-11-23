package dev.nulltogeek.codexremote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun NavigationGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(30.dp)) {
        drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.2.dp.toPx()))
        drawLine(color, Offset(size.width * .29f, size.height * .4f), Offset(size.width * .71f, size.height * .4f), 1.6.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * .29f, size.height * .6f), Offset(size.width * .57f, size.height * .6f), 1.6.dp.toPx(), StrokeCap.Round)
    }
}

@Composable internal fun RemoteDrawer(state: ScreenState, vm: RemoteViewModel,
    navigate: (String) -> Unit, newChat: () -> Unit, openChat: (Chat) -> Unit,
    openProject: (Project) -> Unit, close: () -> Unit) {
    val recent = remember(state.chats, state.searchTerm) {
        state.chats.filter { !it.archived && it.parentId == null && it.source != "subAgent" &&
            (state.searchTerm.isBlank() || it.title.contains(state.searchTerm, true) || it.preview.contains(state.searchTerm, true)) }
            .sortedByDescending { it.updatedAt }
    }
    ModalDrawerSheet(modifier = Modifier.fillMaxHeight().widthIn(max = 330.dp).fillMaxWidth(.88f).testTag("navigation-drawer"),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface, drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)) {
        Column(Modifier.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Codex Remote", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close navigation", Modifier.size(22.dp)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    DrawerAction("New chat", Icons.Outlined.Edit, onClick = newChat,
                        enabled = state.connection == "Connected" && !state.loading, tag = "drawer-new-chat")
                }
                item {
                    OutlinedTextField(state.searchTerm, vm::search, Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("drawer-search"),
                        placeholder = { Text("Search chats", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) }, singleLine = true,
                        shape = RoundedCornerShape(50), textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = MaterialTheme.colorScheme.outline))
                }
                item { DrawerAction("Chats", Icons.AutoMirrored.Outlined.Chat, { navigate("chats") }, selected = state.page == "chats" && state.selectedChat == null, tag = "nav-chats") }
                item { DrawerAction("Archived", Icons.Outlined.Archive, { navigate("archived") }, selected = state.page == "archived" && state.selectedChat == null, tag = "nav-archived") }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Projects", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { close(); vm.beginNewProject() }, enabled = canCreateProject(state), modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Add, "Create project from navigation", Modifier.size(20.dp))
                        }
                    }
                }
                item { DrawerAction("All projects", Icons.Outlined.FolderOpen, { navigate("projects") }, selected = state.page == "projects" && state.selectedChat == null, tag = "nav-projects") }
                items(state.projects.take(8), key = { "drawer-project:${it.id}" }) { project ->
                    DrawerAction(project.name, Icons.Outlined.Folder, { openProject(project) },
                        selected = state.projectFilter == project.id && state.selectedChat == null, tag = "drawer-project:${project.id}")
                }
                item { Text(if (state.searchTerm.isBlank()) "Recent chats" else "Search results", Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(recent, key = { "drawer-chat:${it.id}" }) { chat ->
                    Surface(onClick = { openChat(chat) }, color = if (state.selectedChat?.id == chat.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("drawer-chat:${chat.id}")) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(chat.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (chat.status == "active") Box(Modifier.padding(start = 6.dp).size(6.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                        }
                    }
                }
                if (recent.isEmpty()) item { Text(if (state.searchTerm.isBlank()) "Your conversations appear here" else "No matching chats", Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.activeCursor != null) item { TextButton(onClick = { vm.loadMore(false) }, enabled = !state.catalogLoading && state.connection == "Connected", modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.catalogLoading) "Loading…" else "Load more chats", style = MaterialTheme.typography.labelMedium)
                } }
                if (state.userManagementAvailable) item {
                    Spacer(Modifier.height(12.dp))
                    DrawerAction("Manage users", Icons.Outlined.ManageAccounts, { navigate("users") }, selected = state.page == "users", tag = "nav-users")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(onClick = { navigate("settings") }, color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().testTag("nav-settings").semantics { selected = state.page == "settings" }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text(state.username.take(2).uppercase().ifBlank { "C" }, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.username, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text(if (state.userManagementAvailable) "Administrator" else "Settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Settings, "Account settings", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun DrawerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit,
    selected: Boolean = false, enabled: Boolean = true, tag: String) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { this.selected = selected }) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(21.dp)); Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun NewConversationGreeting(mode: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What can I help with?", textAlign = TextAlign.Center, fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
        Text(if (mode == "chat") "Ask a question or explore an idea." else "Build something, solve a problem, or explore an idea.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
