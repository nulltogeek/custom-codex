package dev.nulltogeek.codexremote

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun NavigationGlyph() {
    Icon(Icons.Outlined.ViewSidebar, "Open navigation", Modifier.size(23.dp))
}

@Composable internal fun RemoteDrawer(state: ScreenState, vm: RemoteViewModel,
    navigate: (String) -> Unit, newChat: () -> Unit, openChat: (Chat) -> Unit,
    openProject: (Project) -> Unit, close: () -> Unit, persistent: Boolean = false,
    archive: (Chat) -> Unit = {}) {
    val (pinned, recent) = remember(state.chats, state.favoriteChats, state.searchTerm) { sidebarChatGroups(state) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxHeight().then(if (persistent) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Code, null, Modifier.size(25.dp))
                Text("Codex", Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                IconButton(onClick = close) { Icon(Icons.Outlined.ViewSidebar, if (persistent) "Hide sidebar" else "Close navigation", Modifier.size(21.dp)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { DrawerAction("New chat", Icons.Outlined.Edit, onClick = newChat,
                    enabled = state.connection == "Connected" && !state.loading, tag = "drawer-new-chat") }
                item {
                    OutlinedTextField(state.searchTerm, vm::search, Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("drawer-search"),
                        placeholder = { Text("Search chats", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = MaterialTheme.colorScheme.outline))
                }
                item { DrawerAction("Chats", Icons.AutoMirrored.Outlined.Chat, { navigate("chats") }, selected = state.page == "chats" && state.selectedChat == null, tag = "nav-chats") }
                item { DrawerAction("Archived", Icons.Outlined.Archive, { navigate("archived") }, selected = state.page == "archived" && state.selectedChat == null, tag = "nav-archived") }
                if (pinned.isNotEmpty()) {
                    item { SidebarHeading("Pinned") }
                    items(pinned, key = { "pinned:${it.id}" }) { chat -> SidebarChat(chat, state, vm, openChat, archive) }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Projects", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { if (!persistent) close(); vm.beginNewProject() }, enabled = canCreateProject(state), modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Add, "Create project from navigation", Modifier.size(18.dp))
                        }
                    }
                }
                items(state.projects.sortedByDescending { it.id in state.favoriteProjectIds }.take(8), key = { "drawer-project:${it.id}" }) { project ->
                    DrawerAction(project.name, Icons.Outlined.Folder, { openProject(project) },
                        selected = state.projectFilter == project.id && state.selectedChat == null, tag = "drawer-project:${project.id}")
                }
                item { DrawerAction("All projects", Icons.Outlined.FolderOpen, { navigate("projects") }, selected = state.page == "projects" && state.selectedChat == null, tag = "nav-projects") }
                item { SidebarHeading(if (state.searchTerm.isBlank()) "Recents" else "Search results") }
                items(recent, key = { "recent:${it.id}" }) { chat -> SidebarChat(chat, state, vm, openChat, archive) }
                if (recent.isEmpty() && pinned.isEmpty()) item { Text(if (state.searchTerm.isBlank()) "Your conversations appear here" else "No matching chats", Modifier.padding(12.dp),
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text(state.username.take(2).uppercase().ifBlank { "C" }, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.username, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        Text(if (state.userManagementAvailable) "Administrator" else "Settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Settings, "Account settings", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (persistent) Surface(Modifier.width(272.dp).fillMaxHeight().testTag("persistent-sidebar"), color = MaterialTheme.colorScheme.surfaceContainerLowest) { content() }
    else ModalDrawerSheet(modifier = Modifier.fillMaxHeight().widthIn(max = 330.dp).fillMaxWidth(.88f).testTag("navigation-drawer"),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, drawerShape = RoundedCornerShape(0.dp)) { content() }
}

@Composable private fun SidebarHeading(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun SidebarChat(chat: Chat, state: ScreenState, vm: RemoteViewModel, open: (Chat) -> Unit, archive: (Chat) -> Unit) {
    Surface(onClick = { open(chat) }, color = if (state.selectedChat?.id == chat.id) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().testTag("drawer-chat:${chat.id}").semantics { selected = state.selectedChat?.id == chat.id }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(chat.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chat.status == "active") Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
            ChatOptionsMenu(chat, canRename(state), canMoveChat(state), { vm.beginRename(chat) }, { vm.beginMoveChat(chat) },
                canArchive(state, chat), { archive(chat) }, pinned = state.favoriteChats.any { it.id == chat.id }, pin = { vm.favorite(chat) })
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

@Composable internal fun NewConversationGreeting(mode: String, choose: (String) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (mode == "chat") Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Code, null, Modifier.size(32.dp))
        Text(if (mode == "chat") "What can I help with?" else "What should we build?", textAlign = TextAlign.Center,
            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
        Text(if (mode == "chat") "Ask a question or explore an idea." else "Build, explore, and solve problems with Codex.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        starterPrompts(mode).chunked(2).forEach { prompts ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                prompts.forEach { prompt ->
                    Surface(onClick = { choose(prompt.prompt) }, shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        color = Color.Transparent, modifier = Modifier.weight(1f).testTag("starter:${prompt.title}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(prompt.title, style = MaterialTheme.typography.titleSmall)
                            Text(prompt.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
