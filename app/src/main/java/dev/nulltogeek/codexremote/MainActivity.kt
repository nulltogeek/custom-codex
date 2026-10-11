package dev.nulltogeek.codexremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val vm: RemoteViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) vm.onForeground()
                    if (event == Lifecycle.Event.ON_STOP) vm.onBackground()
                }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.onForeground()
                onDispose { lifecycle.removeObserver(observer) }
            }
            RemoteTheme(state.theme, state.accent, state.motion) { RemoteApp(state, vm) }
        }
    }
}

private val DarkColors = darkColorScheme(primary = Color(0xFFF0B90B), onPrimary = Color(0xFF181A20),
    primaryContainer = Color(0xFF3A3216), onPrimaryContainer = Color(0xFFF0B90B),
    secondaryContainer = Color(0xFF3A3216), onSecondaryContainer = Color(0xFFF0B90B), surfaceTint = Color(0xFFF0B90B),
    background = Color(0xFF0B0E11), onBackground = Color(0xFFEAECEF), surface = Color(0xFF181A20),
    onSurface = Color(0xFFEAECEF), surfaceVariant = Color(0xFF202630), onSurfaceVariant = Color(0xFF9DA8B8),
    outline = Color(0xFF343B46), secondary = Color(0xFF45D4A0), error = Color(0xFFFF7B85))
private val LightColors = lightColorScheme(primary = Color(0xFF713BF5), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEE9F8), onPrimaryContainer = Color(0xFF713BF5),
    secondaryContainer = Color(0xFFEEE9F8), onSecondaryContainer = Color(0xFF713BF5), surfaceTint = Color(0xFF713BF5),
    background = Color(0xFFF8F7FC), onBackground = Color(0xFF211D32), surface = Color.White,
    onSurface = Color(0xFF211D32), surfaceVariant = Color(0xFFEEE9F8), onSurfaceVariant = Color(0xFF6B617D),
    outline = Color(0xFFD6CEE3), secondary = Color(0xFF078B66), error = Color(0xFFB83249))

@Composable fun RemoteTheme(mode: String, accent: String = "exchange", motion: Boolean = true, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    DisposableEffect(dark) {
        (view.context as? android.app.Activity)?.let { activity -> WindowCompat.getInsetsController(activity.window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
        onDispose { }
    }
    val base = if (dark) DarkColors else LightColors
    val requested = when (accent) {
        "ocean" -> if (dark) Color(0xFF66C5FF) else Color(0xFF176AC0)
        "mint" -> if (dark) Color(0xFF45D4A0) else Color(0xFF087F5B)
        "rose" -> if (dark) Color(0xFFFF8FA3) else Color(0xFFB83565)
        "violet" -> if (dark) Color(0xFFB79BFF) else Color(0xFF713BF5)
        else -> base.primary
    }
    val primary by animateColorAsState(requested, tween(if (motion) 180 else 0), label = "Theme accent")
    val container = lerp(base.surface, primary, 0.14f)
    MaterialTheme(colorScheme = base.copy(primary = primary, primaryContainer = container,
        onPrimaryContainer = primary, secondaryContainer = container, onSecondaryContainer = primary, surfaceTint = primary),
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RemoteApp(state: ScreenState, vm: RemoteViewModel) {
    var approval by remember { mutableStateOf<PendingInput?>(null) }
    var confirmArchive by remember { mutableStateOf<Chat?>(null) }
    val pending = approval?.let { candidate -> state.pending.find { it.id == candidate.id } }
    BackHandler(state.diff != null || state.selectedChat != null) {
        if (state.diff != null) vm.showDiff(null) else vm.page("chats")
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(expandedHeight = 56.dp, title = {
                Column {
                    Text(if (state.diff != null) "Changed code" else state.selectedChat?.title ?: "Codex Remote", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    if (state.signedIn) Text(state.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = {
                if (state.diff != null || state.selectedChat != null) IconButton(onClick = { if (state.diff != null) vm.showDiff(null) else vm.page("chats") }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
            }, actions = {
                if (state.signedIn) {
                    IconButton(onClick = { vm.refresh() }, enabled = state.connection == "Connected" && !state.loading) { Icon(Icons.Outlined.Refresh, "Refresh history") }
                    state.selectedChat?.let { chat ->
                        FavoriteButton(state.favoriteChats.any { it.id == chat.id }, "chat ${chat.title}") { vm.favorite(chat) }
                        IconButton(onClick = { confirmArchive = chat }, enabled = canArchive(state, chat)) { Icon(if (chat.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (chat.archived) "Restore chat" else "Archive chat") }
                    }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }, bottomBar = {
            if (state.signedIn && state.selectedChat == null && state.diff == null) Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Triple("chats", "Chats", Icons.AutoMirrored.Outlined.Chat), Triple("projects", "Projects", Icons.Outlined.FolderOpen), Triple("settings", "Settings", Icons.Outlined.Settings)).forEach { (page, label, icon) ->
                        TextButton(onClick = { vm.page(page) }, modifier = Modifier.weight(1f).height(48.dp).semantics { selected = state.page == page },
                            colors = ButtonDefaults.textButtonColors(containerColor = if (state.page == page) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (state.page == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
                            contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.signedIn && state.connection != "Connected") Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text("${state.connection} · saved history available", style = MaterialTheme.typography.labelMedium)
                }
            }
            state.notice?.let { message -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = vm::noticeDismiss) { Icon(Icons.Outlined.Close, "Dismiss notice") }
                }
            } }
            val screenKey = when { !state.signedIn -> "login"; state.diff != null -> "diff"; state.selectedChat != null -> "chat:${state.selectedChat.id}"; else -> state.page }
            Crossfade(screenKey, Modifier.weight(1f), animationSpec = tween(if (state.motion) 160 else 0), label = "Screen transition") { screen -> when {
                screen == "login" -> ConnectionScreen(state.endpoint, state.username, state.loading, vm::signIn)
                screen == "diff" && state.diff != null -> DiffScreen(state.diff)
                screen.startsWith("chat:") && state.selectedChat != null -> key(state.selectedChat.id) { Conversation(state, vm) { approval = it } }
                screen == "projects" -> ProjectScreen(state, vm)
                screen == "settings" -> SettingsScreen(state, vm)
                else -> ChatsScreen(state, vm, approval = { approval = it }, archive = { confirmArchive = it })
            } }
        }
    }
    if (pending != null) ApprovalDialog(pending, state.loading, onDismiss = { approval = null }, onAnswer = { result -> vm.answer(pending, result) })
    confirmArchive?.let { chat -> AlertDialog(onDismissRequest = { confirmArchive = null }, title = { Text(if (chat.archived) "Restore this chat?" else "Archive this chat?") },
        text = { Text(if (chat.archived) "Return this conversation to your active chats on both devices." else "Move this conversation to archived history on both devices. Codex may also archive its child-agent chats.") },
        confirmButton = { TextButton(onClick = { vm.archive(chat); confirmArchive = null }) { Text(if (chat.archived) "Restore" else "Archive") } },
        dismissButton = { TextButton(onClick = { confirmArchive = null }) { Text("Cancel") } }) }
}

@Composable private fun ConnectionScreen(initialEndpoint: String, initialUsername: String, loading: Boolean, signIn: (String, String, String) -> Unit) {
    var endpoint by rememberSaveable { mutableStateOf(initialEndpoint) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // Password is intentionally excluded from saved instance state and local storage.
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp)) {
            Text("</>", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 28.sp)
        }
        Text("Your Codex.\nWithin reach.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Connect to your computer to follow work, send instructions and approve actions from anywhere on your private network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, placeholder = { Text("https://codex.geeksun.ir") }, singleLine = true, enabled = !loading)
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, enabled = !loading)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !loading)
        Button(onClick = { signIn(endpoint, username, password) }, Modifier.fillMaxWidth().height(52.dp), enabled = !loading && endpoint.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) { Icon(Icons.Outlined.Login, null); Spacer(Modifier.width(8.dp)); Text("Sign in") }
        Text("Your projects, tools and credentials stay on your computer. This app connects to Codex app-server on that computer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ChatsScreen(state: ScreenState, vm: RemoteViewModel, approval: (PendingInput) -> Unit, loadMore: (Boolean) -> Unit = { vm.loadMore(it) },
    archive: (Chat) -> Unit = {}, favorite: (Chat) -> Unit = { vm.favorite(it) }) {
    val query = state.searchTerm; var tab by rememberSaveable { mutableIntStateOf(0) }
    val project = state.projects.find { it.id == state.projectFilter }
    val list = remember(state.chats, state.favoriteChats, query, tab, state.projectFilter, state.assignments, state.projects) {
        val favorites = state.favoriteChats.map { it.id }.toSet()
        val source = if (tab == 3) (state.chats + state.favoriteChats).distinctBy { it.id } else state.chats
        source.filter { chat ->
            val agent = chat.source == "subAgent" || chat.parentId != null
            val category = when (tab) { 1 -> chat.archived; 2 -> agent; 3 -> chat.id in favorites; else -> !chat.archived && !agent }
            val projectMatch = project == null || state.assignments[chat.id] == project.id || chat.projectId == project.id || (state.assignments[chat.id] == null && project.roots.any { it == chat.cwd })
            category && projectMatch && (chat.title.contains(query, true) || chat.preview.contains(query, true))
        }
    }
    val listState = rememberLazyListState()
    val cursor = if (tab == 3) null else if (tab == 1) state.archivedCursor else state.activeCursor
    LaunchedEffect(cursor, tab, query, state.projectFilter, state.catalogError, state.connection, state.catalogLoading) {
        if (cursor != null && state.catalogError == null && !state.catalogLoading && state.connection == "Connected")
            snapshotFlow { listState.layoutInfo.totalItemsCount > 0 && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= listState.layoutInfo.totalItemsCount - 4 }
                .collect { nearEnd -> if (nearEnd) loadMore(tab == 1) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("catalog-list"), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(project?.name ?: "Your workspace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(state.connection == "Connected"); Spacer(Modifier.width(6.dp))
                        Text("${state.projects.size} projects · ${state.chats.size} loaded chats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (project != null) IconButton(onClick = { vm.filterProject(null) }) { Icon(Icons.Outlined.Close, "All projects") }
                FilledTonalIconButton(onClick = { vm.newChat(project) }, enabled = state.connection == "Connected" && !state.loading) { Icon(Icons.Outlined.Add, "New chat") }
            }
        }
        if (state.pending.isNotEmpty()) item {
            Surface(onClick = { approval(state.pending.first()) }, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NotificationsActive, null); Spacer(Modifier.width(12.dp))
                    Column { Text("Your input is needed", fontWeight = FontWeight.SemiBold); Text("${state.pending.size} pending request(s)", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { CompactSearch(query, vm::search) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Recent", 3 to "Favorites", 1 to "Archived", 2 to "Agent chats").forEach { (i, label) -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) }) } } }
        if (list.isEmpty()) item { EmptyState(if (query.isNotEmpty()) "No matching chats" else if (tab == 3) "No favorite chats yet" else "No chats here yet", if (tab == 3) "Tap a chat's star to keep it here." else "Choose a project or start a conversation.") }
        items(list, key = { it.id }, contentType = { "chat" }) { chat ->
            val p = state.projects.find { it.id == (state.assignments[chat.id] ?: chat.projectId) } ?: state.projects.find { chat.cwd in it.roots }
            SwipeArchiveRow(chat, canArchive(state, chat), archive = { archive(chat) }) {
                ChatRow(chat, p?.name ?: "No project", state.favoriteChats.any { it.id == chat.id }, onFavorite = { favorite(chat) }, onClick = { vm.open(chat) })
            }
        }
        if (cursor != null) item(key = "catalog-footer") {
            PageLoader(state.catalogLoading, state.catalogError, "More chats load as you scroll", state.connection == "Connected") { loadMore(tab == 1) }
        }

    }
}

@Composable private fun CompactSearch(query: String, onQuery: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(query, onQuery, modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = "Search all chats" },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { input ->
                    Box { if (query.isEmpty()) Text("Search all chats", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); input() }
                })
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) }
            else Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable private fun ChatRow(chat: Chat, project: String, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(if (chat.parentId != null || chat.source == "subAgent") Icons.Outlined.AccountTree else Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (chat.pinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (chat.preview.isNotBlank() && chat.preview != chat.title) Text(chat.preview, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (chat.archived) Text("Archived", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (chat.status == "active") { StatusDot(true); Spacer(Modifier.width(4.dp)); Text("Running", style = MaterialTheme.typography.labelSmall) }
                }
            }
            FavoriteButton(favorite, "chat ${chat.title}", onFavorite)
        }
    }
}

@Composable internal fun ProjectScreen(state: ScreenState, vm: RemoteViewModel, favorite: (Project) -> Unit = { vm.favorite(it) }) {
    var folders by remember { mutableStateOf<Project?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val projects = if (favoritesOnly) state.projects.filter { it.id in state.favoriteProjectIds } else state.projects
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Saved on ${state.host}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${state.projects.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!favoritesOnly, { favoritesOnly = false }, label = { Text("All projects") })
            FilterChip(favoritesOnly, { favoritesOnly = true }, label = { Text("Favorites") })
        } }
        items(projects, key = { it.id }) { project ->
            val count = state.chats.count { state.assignments[it.id] == project.id || it.projectId == project.id || it.cwd in project.roots }
            Surface(onClick = { vm.filterProject(project.id) }, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                        IconButton(onClick = { folders = project }) { Icon(Icons.Outlined.FolderOpen, "Folders for ${project.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(project.roots.firstOrNull() ?: "No folder", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$count loaded chats" + if (project.roots.size > 1) " · ${project.roots.size} folders" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    FavoriteButton(project.id in state.favoriteProjectIds, "project ${project.name}") { favorite(project) }
                    IconButton(onClick = { vm.newChat(project) }, enabled = state.connection == "Connected" && !state.loading) { Icon(Icons.Outlined.Add, "New chat in ${project.name}", Modifier.size(20.dp)) }
                }
            }
        }
        if (projects.isEmpty()) item { EmptyState(if (favoritesOnly) "No favorite projects yet" else "No projects loaded", if (favoritesOnly) "Tap a project's star to keep it here." else "Connect to your computer and refresh its project inventory.") }
    }
    folders?.let { project -> AlertDialog(onDismissRequest = { folders = null }, title = { Text(project.name) },
        text = { SelectionContainer { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            project.roots.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (project.roots.isEmpty()) Text("No folders saved for this project.")
        } } }, confirmButton = { TextButton(onClick = { folders = null }) { Text("Close") } }) }
}

@Composable private fun Conversation(state: ScreenState, vm: RemoteViewModel, approval: (PendingInput) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.attach(uris) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val active = state.selectedChat?.status == "active"
            StatusDot(active); Spacer(Modifier.width(6.dp)); Text(when {
                state.pending.any { it.params.text("threadId") == state.selectedChat?.id } -> "Waiting for input"
                active -> "Running on your computer"
                state.selectedChat?.archived == true -> "Archived conversation"
                state.lastTurnStatus == "failed" -> "Error · see details below"
                state.lastTurnStatus == "completed" -> "Completed"
                state.lastTurnStatus == "interrupted" -> "Stopped"
                else -> "Ready"
            }, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f)); if (active) TextButton(onClick = { vm.interrupt() }, enabled = state.connection == "Connected") { Icon(Icons.Outlined.Stop, null, Modifier.size(16.dp)); Text("Stop") }
        }
        ChatTimeline(state, Modifier.weight(1f), vm::loadOlder, vm::showDiff, approval)
        ChatComposer(state, vm, onPick = { picker.launch(it) })
    }
}

@Composable internal fun ChatTimeline(state: ScreenState, modifier: Modifier = Modifier, loadOlder: () -> Unit, showDiff: (Change) -> Unit, approval: (PendingInput) -> Unit) {
    val listState = remember(state.selectedChat?.id) { LazyListState() }
    var followTail by remember(state.selectedChat?.id) { mutableStateOf(true) }
    val hasOlder = state.olderCursor != null || state.hiddenOlderCount > 0
    LaunchedEffect(listState) { snapshotFlow { listState.canScrollBackward }.collect { canScroll -> followTail = !canScroll } }
    val last = state.timeline.lastOrNull()
    LaunchedEffect(last?.id, last?.text?.length) { if (followTail && state.timeline.isNotEmpty()) listState.scrollToItem(0) }
    LaunchedEffect(listState, state.olderCursor, state.hiddenOlderCount, state.olderError, state.olderLoading, state.connection) {
        if (hasOlder && state.olderError == null && !state.olderLoading && (state.hiddenOlderCount > 0 || state.connection == "Connected"))
            snapshotFlow { listState.layoutInfo.totalItemsCount > 0 && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= listState.layoutInfo.totalItemsCount - 3 }
                .collect { nearStart -> if (nearStart) loadOlder() }
    }
    LazyColumn(modifier.fillMaxWidth().testTag("history-list"), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.pending.filter { it.params.text("threadId") == state.selectedChat?.id }, key = { "pending-${it.id}" }) { request ->
            Button(onClick = { approval(request) }, Modifier.fillMaxWidth()) { Text(if (request.method.contains("Approval")) "Review requested action" else "Answer Codex") }
        }
        items(state.timeline.asReversed(), key = { it.id }, contentType = { it.type }) { item -> MessageItem(item, showDiff) }
        if (state.timeline.isEmpty() && !state.historyAvailable && state.selectedChat?.preview?.isNotBlank() == true) item { EmptyState("Earlier messages unavailable", "New messages will appear here. You can continue this conversation.") }
        else if (state.timeline.isEmpty()) item { EmptyState(if (state.chatMode == "chat") "A new conversation" else "Ready to build", if (state.chatMode == "chat") "Ask a question, share an image, or discuss an idea." else "Describe the work, attach a file, and choose how Codex can access your computer.") }
        if (hasOlder) item(key = "history-footer") {
            PageLoader(state.olderLoading, state.olderError, "Earlier messages load as you scroll", state.hiddenOlderCount > 0 || state.connection == "Connected") { loadOlder() }
        }

    }
}

@Composable private fun PageLoader(loading: Boolean, error: String?, label: String, enabled: Boolean, retry: () -> Unit) {
    if (error != null) TextButton(onClick = retry, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(error) }
    else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (loading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (loading) "Loading…" else label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun MessageItem(item: TimelineItem, showDiff: (Change) -> Unit) {
    var visibleLimit by rememberSaveable(item.id) { mutableIntStateOf(6000) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val user = item.type == "userMessage"; val tool = item.type !in listOf("userMessage", "agentMessage")
    val label = if (user) "YOU" else if (!tool) "CODEX" else when (item.type) { "commandExecution" -> "TERMINAL"; "fileChange" -> "CHANGES"; "reasoning" -> "WORKING"; else -> item.type.uppercase() }
    Surface(color = if (user) MaterialTheme.colorScheme.surfaceVariant else if (tool) MaterialTheme.colorScheme.surface else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(if (tool || user) 10.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tool) Surface(onClick = { expanded = !expanded }, color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(item.detail.ifBlank { item.text }.lineSequence().firstOrNull().orEmpty().take(160), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (item.status.isNotEmpty()) Text(item.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse $label" else "Expand $label")
                }
            } else Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (!tool || expanded) {
                Column(if (tool) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()) else Modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.detail.isNotBlank()) SelectionContainer { Text(item.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                val visible = item.text.take(visibleLimit)
                if (visible.isNotBlank()) {
                    if (!tool && !user) MarkdownView(visible)
                    else if (item.type == "commandExecution") CodeBlock(item.text, "terminal", collapsible = false)
                    else SelectionContainer { Text(visible, style = if (tool) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default) }
                }
                if (item.text.length > visibleLimit && item.type != "commandExecution") TextButton(onClick = { visibleLimit += 6000 }) { Text("Show more output") }
                item.changes.forEach { change -> OutlinedButton(onClick = { showDiff(change) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Difference, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(change.path, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            }
            }
        }
    }
}

@Composable private fun DiffScreen(change: Change) {
    val lines = remember(change.diff) { change.diff.lines() }
    var visibleCount by remember(change.diff) { mutableIntStateOf(300) }
    Column(Modifier.fillMaxSize()) {
        Text(change.path, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lines.take(visibleCount).size, key = { it }) { index ->
                    val line = lines[index]
                    val color = when { line.startsWith("+") && !line.startsWith("+++") -> MaterialTheme.colorScheme.secondary; line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }
                    Text(line.ifEmpty { " " }, Modifier.fillMaxWidth().background(color.copy(alpha = if (line.startsWith("+") || line.startsWith("-")) 0.08f else 0f)).padding(horizontal = 12.dp, vertical = 2.dp), color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                if (visibleCount < lines.size) item { TextButton(onClick = { visibleCount += 300 }, Modifier.fillMaxWidth()) { Text("Show next 300 lines (${lines.size - visibleCount} remaining)") } }
            }
        }
    }
}

@Composable private fun SettingsScreen(state: ScreenState, vm: RemoteViewModel) {
    var signOut by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "dark", "light").forEach { mode -> FilterChip(selected = state.theme == mode, onClick = { vm.theme(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) }) } }
        Text("ACCENT STYLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("exchange", "ocean", "mint", "rose", "violet").forEach { accent -> FilterChip(selected = state.accent == accent, onClick = { vm.accent(accent) }, label = { Text(accent.replaceFirstChar { it.uppercase() }) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Animations"); Text("Smooth screen and color transitions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = state.motion, onCheckedChange = vm::motion) }
        HorizontalDivider()
        AppUpdateSection(state, vm)
        HorizontalDivider()
        Text("CONNECTED COMPUTER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.host, fontWeight = FontWeight.SemiBold); Text(state.endpoint, style = MaterialTheme.typography.bodySmall); Text(state.connection, color = MaterialTheme.colorScheme.secondary)
        Text("Signed in as ${state.username}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.passwordFormOpened(); password = true }, enabled = !state.passwordChanging && state.connection == "Connected" && state.passwordChangeAvailable) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Change password")
        }
        if (!state.passwordChangeAvailable) Text("Update the computer's server to enable password changes.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { signOut = true }, enabled = !state.loading && !state.updateDownloading && !state.passwordChanging) { Text("Sign out") }
        HorizontalDivider()
        Text("Codex Remote · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        Text("A personal Android client. Work runs on your computer using its permissions and existing Codex account. Keep the Codex server running and your computer awake.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (password) ChangePasswordDialog(state.passwordChanging, state.passwordMessage, state.passwordChanged,
        state.connection == "Connected", onDismiss = { password = false }, changePassword = vm::changePassword)
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text("Sign out of this phone?") }, text = { Text("End this phone's session. Your conversations remain on your computer.") },
        confirmButton = { TextButton(onClick = { vm.signOut(); signOut = false }) { Text("Confirm sign out") } }, dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } })
}

@Composable private fun ApprovalDialog(request: PendingInput, loading: Boolean, onDismiss: () -> Unit, onAnswer: (JsonObject) -> Unit) {
    val params = request.params
    var answers by remember(request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val questions = params.array("questions").mapNotNull { it as? JsonObject }
    val isQuestion = request.method == "item/tool/requestUserInput"
    val isForm = request.method == "mcpServer/elicitation/request"
    val isPermissions = request.method == "item/permissions/requestApproval"
    val properties = params.obj("requestedSchema").obj("properties")
    val required = params.obj("requestedSchema").array("required").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val formValid = required.all { answers[it]?.isNotBlank() == true }
    val availableDecisions = params.array("availableDecisions")
    val approveAllowed = availableDecisions.isEmpty() || availableDecisions.any { (it as? JsonPrimitive)?.contentOrNull == "accept" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isQuestion) "Codex needs your input" else if (isForm) "Tool needs your input" else "Review requested action") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(params.text("reason").ifEmpty { params.text("message").ifEmpty { request.method } }, style = MaterialTheme.typography.bodyMedium)
                if (isQuestion) questions.forEach { q ->
                    Text(q.text("question"), fontWeight = FontWeight.SemiBold)
                    q.array("options").mapNotNull { it as? JsonObject }.forEach { option ->
                        val label = option.text("label")
                        Row(Modifier.fillMaxWidth().clickable { answers = answers + (q.text("id") to label) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(answers[q.text("id")] == label, onClick = { answers = answers + (q.text("id") to label) })
                            Column { Text(label); if (option.text("description").isNotEmpty()) Text(option.text("description"), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    OutlinedTextField(answers[q.text("id")].orEmpty(), { answers = answers + (q.text("id") to it) }, Modifier.fillMaxWidth(), label = { Text("Your answer") })
                } else if (isForm && properties.isNotEmpty()) properties.forEach { (id, value) ->
                    val spec = value.jsonObject
                    if (spec.text("type") == "boolean") Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(answers[id] == "true", { answers = answers + (id to it.toString()) }); Text(spec.text("title", id))
                    } else OutlinedTextField(answers[id].orEmpty(), { answers = answers + (id to it) }, Modifier.fillMaxWidth(), label = { Text(spec.text("title", id)) }, supportingText = { Text(spec.text("description").ifEmpty { spec.array("enum").joinToString() }) })
                } else SelectionContainer { Text(params.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text("An answer here resolves this request for all clients connected to this server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            TextButton(enabled = !loading && (!isQuestion || questions.all { answers[it.text("id")]?.isNotBlank() == true }) && (!isForm || formValid) && (isQuestion || isForm || isPermissions || approveAllowed), onClick = {
                val result = when {
                    isQuestion -> buildJsonObject { put("answers", buildJsonObject { questions.forEach { q -> put(q.text("id"), buildJsonObject { put("answers", buildJsonArray { add(answers[q.text("id")].orEmpty()) }) }) } }) }
                    isForm -> buildJsonObject { put("action", "accept"); put("content", buildJsonObject { answers.forEach { (id, answer) ->
                        val type = (properties[id] as? JsonObject)?.text("type")
                        put(id, when (type) { "boolean" -> JsonPrimitive(answer == "true"); "integer" -> answer.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(answer); "number" -> answer.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(answer); else -> JsonPrimitive(answer) })
                    } }) }
                    isPermissions -> buildJsonObject { put("permissions", params.obj("permissions")); put("scope", "turn") }
                    request.method in listOf("applyPatchApproval", "execCommandApproval") -> jsonObject("decision" to "approved")
                    else -> jsonObject("decision" to "accept")
                }
                onAnswer(result)
            }) { Text(if (isQuestion || isForm) "Submit answer" else if (isPermissions) "Allow for this turn" else "Approve once") }
        }, dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Later") }
                if (!isQuestion) TextButton(enabled = !loading, onClick = { onAnswer(when {
                    isForm -> jsonObject("action" to "decline")
                    isPermissions -> buildJsonObject { put("permissions", buildJsonObject {}); put("scope", "turn") }
                    request.method in listOf("applyPatchApproval", "execCommandApproval") -> jsonObject("decision" to "denied")
                    else -> jsonObject("decision" to "decline")
                }) }) { Text("Decline") }
            }
        })
}

@Composable private fun StatusDot(active: Boolean) { Box(Modifier.size(7.dp).background(if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50))) }
@Composable private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
