package dev.nulltogeek.codexremote

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notificationIntent by mutableStateOf<Intent?>(null)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); notificationIntent = intent }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        notificationIntent = intent
        setContent {
            val vm: RemoteViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(notificationIntent, state.signedIn, state.connection) {
                val tap = notificationIntent
                if (state.signedIn && state.connection == "Connected" && tap != null) {
                    notificationIntent = null
                    vm.openNotificationChat(tap.getStringExtra(TaskNotificationService.CHAT_ID).orEmpty(),
                        tap.getStringExtra(TaskNotificationService.HOST).orEmpty(), tap.getStringExtra(TaskNotificationService.USER).orEmpty())
                }
            }
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

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF3F3F3), onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFECECEC),
    secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFECECEC),
    background = Color(0xFF212121), onBackground = Color(0xFFECECEC),
    surface = Color(0xFF212121), onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFB4B4B4),
    surfaceContainerLowest = Color(0xFF171717), surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF303030), surfaceContainerHigh = Color(0xFF383838),
    surfaceContainerHighest = Color(0xFF424242), surfaceTint = Color.Transparent,
    outline = Color(0xFF5A5A5A), outlineVariant = Color(0xFF3A3A3A),
    secondary = Color(0xFF81C7A7), error = Color(0xFFFF8A8A))
private val LightColors = lightColorScheme(
    primary = Color(0xFF171717), onPrimary = Color.White,
    primaryContainer = Color(0xFFECECEC), onPrimaryContainer = Color(0xFF171717),
    secondaryContainer = Color(0xFFF1F1F1), onSecondaryContainer = Color(0xFF171717),
    background = Color.White, onBackground = Color(0xFF171717),
    surface = Color.White, onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE5E5E5), surfaceTint = Color.Transparent,
    outline = Color(0xFFABABAB), outlineVariant = Color(0xFFE5E5E5),
    secondary = Color(0xFF16845B), error = Color(0xFFBB3030))

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
    val container = if (accent == "exchange" || accent == "neutral") base.primaryContainer else lerp(base.surface, primary, 0.10f)
    MaterialTheme(colorScheme = base.copy(primary = primary, primaryContainer = container,
        onPrimaryContainer = primary, secondaryContainer = container, onSecondaryContainer = primary, surfaceTint = Color.Transparent),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RemoteApp(state: ScreenState, vm: RemoteViewModel) {
    var approvalId by rememberSaveable(state.endpoint, state.username, state.signedIn) { mutableStateOf<String?>(null) }
    var asyncQuestion by rememberSaveable(state.endpoint, state.username, state.signedIn) { mutableStateOf<String?>(null) }
    var confirmArchive by remember(state.endpoint, state.username, state.signedIn) { mutableStateOf<Chat?>(null) }
    var confirmDelete by remember(state.endpoint, state.username, state.signedIn) { mutableStateOf<Chat?>(null) }
    val pending = state.pending.find { it.id == approvalId }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun closeDrawer(action: () -> Unit = {}) {
        dismissKeyboard()
        action()
        scope.launch { drawer.close() }
    }
    BackHandler(drawer.isOpen || state.diff != null || state.selectedChat != null) {
        when { drawer.isOpen -> closeDrawer(); state.diff != null -> vm.showDiff(null); else -> vm.page(state.page) }
    }
    LaunchedEffect(state.signedIn, state.username) { drawer.close() }
    LaunchedEffect(drawer.currentValue) {
        if (state.signedIn && drawer.currentValue == DrawerValue.Closed) dismissKeyboard()
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = state.signedIn,
        drawerContent = {
            if (state.signedIn) RemoteDrawer(state, vm,
                navigate = { page -> closeDrawer { vm.page(page) } },
                newChat = { closeDrawer { vm.newChat(state.projects.find { it.id == state.projectFilter }) } },
                openChat = { chat -> closeDrawer { vm.open(chat) } },
                openProject = { project -> closeDrawer { vm.filterProject(project.id) } },
                close = { closeDrawer() })
        }) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.signedIn) TopAppBar(expandedHeight = 56.dp, title = {
                if (state.selectedChat != null && state.diff == null) ComposerOptions(state, vm, compact = true)
                else Text(when {
                    state.diff != null -> "Changed code"
                    state.page == "projects" -> "Projects"
                    state.page == "archived" -> "Archived chats"
                    state.page == "settings" -> "Settings"
                    state.page == "users" -> "Manage users"
                    else -> "Codex"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }, navigationIcon = {
                IconButton(onClick = { if (state.diff != null) vm.showDiff(null) else { dismissKeyboard(); scope.launch { drawer.open() } } },
                    modifier = Modifier.testTag("open-navigation")) {
                    if (state.diff != null) Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(24.dp))
                    else Box(Modifier.semantics { contentDescription = "Open navigation" }) { NavigationGlyph() }
                }
            }, actions = {
                IconButton(onClick = { vm.newChat(state.projects.find { it.id == state.projectFilter }) },
                    enabled = state.connection == "Connected" && !state.loading,
                    modifier = Modifier.testTag("header-new-chat")) { Icon(Icons.Outlined.Edit, "Start new chat", Modifier.size(23.dp)) }
                state.selectedChat?.let { chat ->
                    ChatOptionsMenu(chat, canRename(state), canMoveChat(state), { vm.beginRename(chat) }, { vm.beginMoveChat(chat) },
                        canArchive(state, chat), { confirmArchive = chat })
                    if (chat.archived) IconButton(onClick = { confirmDelete = chat }, enabled = canDeleteChat(state, chat)) { Icon(Icons.Outlined.DeleteOutline, "Delete chat") }
                }
                if (state.selectedChat == null) IconButton(onClick = { vm.refresh() }, enabled = canRefreshHistory(state)) { Icon(Icons.Outlined.Refresh, "Refresh history") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading && !(state.refreshing && state.selectedChat == null && state.page in listOf("chats", "archived", "projects"))) LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            if (state.signedIn && state.connection != "Connected") Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text("${state.connection} · saved history available", style = MaterialTheme.typography.labelMedium)
                }
            }
            NoticeBanners(state, vm::noticeDismiss, vm::infoNoticeDismiss)
            if (state.writerConflict && state.selectedChat != null) ThreadWriterNotice(state, vm::continueInNewChat)
            val screenKey = when { !state.signedIn -> "login"; state.diff != null -> "diff"; state.selectedChat != null -> "chat:${state.selectedChat.id}"; else -> state.page }
            Crossfade(screenKey, Modifier.weight(1f), animationSpec = tween(if (state.motion) 160 else 0), label = "Screen transition") { screen -> when {
                screen == "login" -> AccountSignIn(state, vm)
                screen == "diff" && state.diff != null -> DiffScreen(state.diff)
                screen.startsWith("chat:") && state.selectedChat != null -> key(state.selectedChat.id) { Conversation(state, vm, approval = { approvalId = it.id }, answerQuestion = { asyncQuestion = it.id }) }
                screen == "projects" -> ProjectScreen(state, vm)
                screen == "settings" -> SettingsScreen(state, vm)
                screen == "users" && state.userManagementAvailable -> ManageUsersScreen(state, vm)
                screen == "archived" -> ChatsScreen(state, vm, approval = { approvalId = it.id }, archive = { confirmArchive = it }, delete = { confirmDelete = it }, archivedOnly = true)
                else -> ChatsScreen(state, vm, approval = { approvalId = it.id }, archive = { confirmArchive = it }, delete = { confirmDelete = it })
            } }
        }
    }
    }
    if (pending?.method == "item/tool/requestUserInput") QuestionDialog(pending.id, pending.questions(),
        state.connection == "Connected", pending.id in state.answeringInputs, state.inputErrors[pending.id],
        onDismiss = { approvalId = null }, onSubmit = { vm.answer(pending, questionResult(pending.questions(), it)) })
    else if (pending != null) ApprovalDialog(pending, pending.id in state.answeringInputs || state.connection != "Connected",
        onDismiss = { approvalId = null }, onAnswer = { result -> vm.answer(pending, result) })
    val questionItem = state.timeline.find { it.id == asyncQuestion && it.questions.isNotEmpty() && it.id !in state.answeredInputs && !questionAnswered(it, state.timeline) }
    if (questionItem != null) QuestionDialog(questionItem.id, questionItem.questions,
        state.connection == "Connected",
        questionItem.id in state.answeringInputs, state.inputErrors[questionItem.id], onDismiss = { asyncQuestion = null },
        onSubmit = { vm.answerAsyncQuestion(questionItem, it) }, blockedReason = when {
            state.uncertainSend -> "Refresh and check chat history before submitting again."
            state.selectedChat?.archived == true -> "Restore this chat before answering."
            state.sending && questionItem.id !in state.answeringInputs -> "Wait for the current message to finish sending."
            else -> null
        })
    confirmDelete?.let { chat -> AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete this chat permanently?") },
        text = { Text("Delete “${chat.title}” from your computer and this phone. This cannot be undone. Remove queued messages before deleting.") },
        confirmButton = { TextButton(onClick = { vm.deleteChat(chat); confirmDelete = null }, enabled = canDeleteChat(state, state.chats.find { it.id == chat.id } ?: chat)) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }) }
    confirmArchive?.let { chat -> AlertDialog(onDismissRequest = { confirmArchive = null }, title = { Text(if (chat.archived) "Restore this chat?" else "Archive this chat?") },
        text = { Text(if (chat.archived) "Return this conversation to your active chats on both devices." else "Move this conversation to archived history on both devices. Codex may also archive its child-agent chats.") },
        confirmButton = { TextButton(onClick = { vm.archive(chat); confirmArchive = null }) { Text(if (chat.archived) "Restore" else "Archive") } },
        dismissButton = { TextButton(onClick = { confirmArchive = null }) { Text("Cancel") } }) }
    state.renameTarget?.let { target -> RenameDialog(target, state.renaming, state.connection == "Connected", state.renameError,
        vm::dismissRename, vm::rename) }
    state.newProjectKey?.let { key -> NewProjectDialog(key, state.projectCreateRequest, state.creatingProject,
        state.connection == "Connected", state.projectCreateError, vm::dismissNewProject, vm::createProject,
        initialFolders = defaultProjectFolders(state)) }
    state.moveChat?.let { chat -> MoveChatDialog(chat, state.projects, state.movingChat, state.connection == "Connected",
        state.moveChatError, vm::dismissMoveChat, vm::moveChatToProject) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun ChatsScreen(state: ScreenState, vm: RemoteViewModel, approval: (PendingInput) -> Unit, loadMore: (Boolean) -> Unit = { vm.loadMore(it) },
    archive: (Chat) -> Unit = {}, favorite: (Chat) -> Unit = { vm.favorite(it) }, refresh: () -> Unit = { vm.refresh() }, archivedOnly: Boolean = false, delete: (Chat) -> Unit = {},
    rename: (Chat) -> Unit = { vm.beginRename(it) }, move: (Chat) -> Unit = { vm.beginMoveChat(it) },
    deleteSelected: (List<Chat>) -> Unit = { vm.deleteChats(it) }) {
    val query = state.searchTerm; var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tab = if (archivedOnly) 1 else selectedTab
    val project = state.projects.find { it.id == state.projectFilter }
    val list = remember(state.chats, state.favoriteChats, query, tab, state.projectFilter, state.assignments, state.projects) {
        val favorites = state.favoriteChats.map { it.id }.toSet()
        val source = if (tab == 3) (state.chats + state.favoriteChats).distinctBy { it.id } else state.chats
        source.filter { chat ->
            val agent = chat.source == "subAgent" || chat.parentId != null
            val category = when (tab) { 1 -> chat.archived; 2 -> agent; 3 -> chat.id in favorites; else -> !chat.archived && !agent }
            val projectMatch = project == null || chatBelongsToProject(chat, project, state.assignments)
            category && projectMatch && (chat.title.contains(query, true) || chat.preview.contains(query, true))
        }
    }
    var selectedIds by remember(archivedOnly, state.endpoint, state.username, state.signedIn, state.page, query, state.projectFilter) { mutableStateOf(emptySet<String>()) }
    var confirmIds by remember(archivedOnly, state.endpoint, state.username, state.signedIn, state.page, query, state.projectFilter) { mutableStateOf<Set<String>?>(null) }
    val selectedChats = if (archivedOnly) list.filter { it.id in selectedIds } else emptyList()
    val selecting = selectedChats.isNotEmpty()
    LaunchedEffect(list, archivedOnly) {
        selectedIds = selectedIds.intersect(list.filter { archivedOnly && it.archived }.map { it.id }.toSet())
    }
    BackHandler(selecting && confirmIds == null) { selectedIds = emptySet() }
    fun toggle(chat: Chat) { selectedIds = if (chat.id in selectedIds) selectedIds - chat.id else selectedIds + chat.id }
    val listState = key(tab, query, state.projectFilter) { rememberLazyListState() }
    val page = nextCatalogPage(state, tab)
    val error = page?.let { catalogPageError(state, it.archived) }
    val expectedItems = (if (archivedOnly) 2 else 3) + (if (state.pending.isEmpty()) 0 else 1) + maxOf(1, list.size) + (if (page == null) 0 else 1)
    val latestLoadMore by rememberUpdatedState(loadMore)
    LaunchedEffect(page, listState, expectedItems, error, state.connection, state.catalogLoading, state.refreshing) {
        if (page != null && error == null && !state.catalogLoading && !state.refreshing && state.connection == "Connected")
            snapshotFlow { listState.layoutInfo.totalItemsCount == expectedItems && nearCatalogEnd(listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
                .collect { nearEnd -> if (nearEnd) latestLoadMore(page.archived) }
    }
    RefreshableLibrary(state, refresh) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) ArchiveSelectionBar(selectedChats.size, canDeleteChats(state, selectedChats),
                allSelected = selectedChats.size == list.size,
                cancel = { selectedIds = emptySet() }, delete = { confirmIds = selectedChats.map { it.id }.toSet() },
                selectAll = { selectedIds = if (selectedChats.size == list.size) emptySet() else list.map { it.id }.toSet() })
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("catalog-list"), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (archivedOnly) "Archived chats" else project?.name ?: "Your chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (project != null) IconButton(onClick = { vm.filterProject(null) }) { Icon(Icons.Outlined.Close, "All projects") }
                        if (!archivedOnly) FilledTonalIconButton(onClick = { vm.newChat(project) }, enabled = state.connection == "Connected" && !state.loading) { Icon(Icons.Outlined.Add, "New chat") }
                    }
                }
                items(state.pending, key = { "input:${it.id}" }) { request ->
                    Surface(onClick = { approval(request) }, color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.NotificationsActive, null); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text("Your input is needed", fontWeight = FontWeight.SemiBold); Text(request.questions().firstOrNull()?.title ?: "Review requested action", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                item { CompactSearch(query, vm::search, if (archivedOnly) "Search archived chats" else "Search all chats") }
                if (!archivedOnly) item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Recent", 3 to "Favorites", 2 to "Agent chats").forEach { (i, label) -> FilterChip(selected = tab == i, onClick = { selectedTab = i }, label = { Text(label) }) } } }
                if (list.isEmpty()) item { EmptyState(if (page != null && error == null) "Looking for chats…" else if (query.isNotEmpty()) "No matching chats" else if (archivedOnly) "No archived chats" else if (tab == 3) "No favorite chats yet" else "No chats here yet", if (page != null) "Checking more saved chats for this view." else if (archivedOnly) "Chats you archive appear here. Open one to read or restore it." else if (tab == 3) "Tap a chat's star to keep it here." else "Choose a project or start a conversation.") }
                items(list, key = { it.id }, contentType = { "chat" }) { chat ->
                    val p = state.projects.find { chatBelongsToProject(chat, it, state.assignments) }
                    SwipeDeleteRow(chat, !selecting && canDeleteChat(state, chat), delete = { delete(chat) }) {
                        ChatRow(chat, p?.name ?: "No project", state.favoriteChats.any { it.id == chat.id }, onFavorite = { favorite(chat) }, onClick = { if (selecting) toggle(chat) else vm.open(chat) },
                            renameEnabled = canRename(state), onRename = { rename(chat) }, moveEnabled = canMoveChat(state), onMove = { move(chat) },
                            archiveEnabled = canArchive(state, chat), onArchive = { archive(chat) },
                            selecting = selecting, selected = chat.id in selectedIds, onLongClick = if (archivedOnly) ({ toggle(chat) }) else null)
                    }
                }
                if (page != null) item(key = "catalog-footer") {
                    PageLoader(state.catalogLoading, error, "More chats load as you scroll", state.connection == "Connected") { loadMore(page.archived) }
                }
            }
        }
    }
    confirmIds?.let { ids ->
        val targets = list.filter { it.id in ids }
        ArchiveDeleteDialog(ids.size, targets.size == ids.size && canDeleteChats(state, targets),
            dismiss = { confirmIds = null }, delete = { deleteSelected(targets); confirmIds = null })
    }
}

@Composable private fun CompactSearch(query: String, onQuery: (String) -> Unit, label: String = "Search all chats") {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(query, onQuery, modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = label },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { input ->
                    Box { if (query.isEmpty()) Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); input() }
                })
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search", Modifier.size(18.dp)) }
            else Spacer(Modifier.width(12.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ChatRow(chat: Chat, project: String, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit,
    renameEnabled: Boolean, onRename: () -> Unit, moveEnabled: Boolean, onMove: () -> Unit, archiveEnabled: Boolean, onArchive: () -> Unit,
    selecting: Boolean = false, selected: Boolean = false, onLongClick: (() -> Unit)? = null) {
    Surface(modifier = Modifier.testTag("chat-row:${chat.id}").combinedClickable(onClick = onClick, onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) "Select chat" else null, role = if (selecting) Role.Checkbox else Role.Button)
        .semantics { if (selecting) { this.selected = selected; toggleableState = if (selected) ToggleableState.On else ToggleableState.Off } },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { Checkbox(selected, onCheckedChange = null, modifier = Modifier.size(40.dp)); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.parentId != null || chat.source == "subAgent") { Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (chat.pinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (project != "No project" || chat.status == "active" || chat.archived) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (project != "No project") Text(project, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (chat.archived) Text("Archived", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (chat.status == "active") { StatusDot(true); Text("Running", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (!selecting) {
                FavoriteButton(favorite, "chat ${chat.title}", onFavorite)
                ChatOptionsMenu(chat, renameEnabled, moveEnabled, onRename, onMove, archiveEnabled, onArchive)
            }
        }
    }
}

@Composable internal fun ProjectScreen(state: ScreenState, vm: RemoteViewModel, favorite: (Project) -> Unit = { vm.favorite(it) }, refresh: () -> Unit = { vm.refresh() },
    rename: (Project) -> Unit = { vm.beginRename(it) }, newProject: () -> Unit = { vm.beginNewProject() }) {
    var folders by remember { mutableStateOf<Project?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val projects = if (favoritesOnly) state.projects.filter { it.id in state.favoriteProjectIds } else state.projects
    RefreshableLibrary(state, refresh) {
        LazyColumn(Modifier.fillMaxSize().testTag("project-list"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Keep related chats together", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${state.projects.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = newProject, enabled = canCreateProject(state), modifier = Modifier.testTag("new-project")) { Icon(Icons.Outlined.Add, "New project") }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!favoritesOnly, { favoritesOnly = false }, label = { Text("All projects") })
                FilterChip(favoritesOnly, { favoritesOnly = true }, label = { Text("Favorites") })
            } }
            items(projects, key = { it.id }) { project ->
                val count = state.chats.count { chatBelongsToProject(it, project, state.assignments) }
                Surface(onClick = { vm.filterProject(project.id) }, color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                            IconButton(onClick = { folders = project }) { Icon(Icons.Outlined.FolderOpen, "Folders for ${project.name}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(project.roots.firstOrNull() ?: "No folder", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$count chats" + if (project.roots.size > 1) " · ${project.roots.size} folders" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        FavoriteButton(project.id in state.favoriteProjectIds, "project ${project.name}") { favorite(project) }
                        IconButton(onClick = { rename(project) }, enabled = canRename(state)) { Icon(Icons.Outlined.Edit, "Rename project ${project.name}", Modifier.size(20.dp)) }
                        IconButton(onClick = { vm.newChat(project) }, enabled = state.connection == "Connected" && !state.loading) { Icon(Icons.Outlined.Add, "New chat in ${project.name}", Modifier.size(20.dp)) }
                    }
                }
            }
            if (projects.isEmpty()) item { EmptyState(if (favoritesOnly) "No favorite projects yet" else "No projects loaded", if (favoritesOnly) "Tap a project's star to keep it here." else "Connect to your computer and refresh its project inventory.") }
        }
    }
    folders?.let { project -> AlertDialog(onDismissRequest = { folders = null }, title = { Text(project.name) },
        text = { SelectionContainer { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            project.roots.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (project.roots.isEmpty()) Text("No folders saved for this project.")
        } } }, confirmButton = { TextButton(onClick = { folders = null }) { Text("Close") } }) }
}

@Composable private fun Conversation(state: ScreenState, vm: RemoteViewModel, approval: (PendingInput) -> Unit, answerQuestion: (TimelineItem) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.attach(uris) }
    Column(Modifier.fillMaxSize()) {
        if (state.selectedChat?.status == "active" || state.selectedChat?.archived == true || state.pending.any { it.params.text("threadId") == state.selectedChat?.id } || state.lastTurnStatus in listOf("failed", "interrupted")) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val active = state.selectedChat?.status == "active"
            CommandActivity(state)
            Text(when {
                state.pending.any { it.params.text("threadId") == state.selectedChat?.id } -> "Waiting for input"
                active -> "Running on your computer"
                state.selectedChat?.archived == true -> "Archived conversation"
                state.lastTurnStatus == "failed" -> "Error · see details below"
                state.lastTurnStatus == "completed" -> "Completed"
                state.lastTurnStatus == "interrupted" -> "Stopped"
                else -> "Ready"
            }, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            state.pending.filter { it.params.text("threadId") == state.selectedChat?.id }.forEach { request ->
                OutlinedButton(onClick = { approval(request) }, Modifier.fillMaxWidth().testTag("pending-input:${request.id}")) {
                    Text(request.questions().firstOrNull()?.title ?: "Review requested action", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        ChatTimeline(state, Modifier.weight(1f), vm::loadOlder, vm::showDiff, approval, loadImage = vm::attachmentImage, answerQuestion = answerQuestion, editMessage = vm::editSentMessage)
        ChatComposer(state, vm, onPick = { picker.launch(it) })
    }
    state.sentMessageEdit?.let { edit -> SentMessageEditDialog(edit.copy(error = edit.error ?: state.notice), state.connection == "Connected",
        state.selectedChat?.status == "active" || state.activeTurnId.isNotEmpty(), vm::sentEditText,
        vm::removeSentEditAttachment, vm::resendEditedMessage, vm::interrupt, vm::cancelSentEdit, stopPending = state.loading) }
}

@Composable internal fun ChatTimeline(state: ScreenState, modifier: Modifier = Modifier, loadOlder: () -> Unit, showDiff: (Change) -> Unit, approval: (PendingInput) -> Unit,
    loadImage: suspend (String) -> ByteArray = { error("Image unavailable") }, answerQuestion: (TimelineItem) -> Unit = {},
    editMessage: ((TimelineItem) -> Unit)? = null) {
    val listState = remember(state.selectedChat?.id) { LazyListState() }
    val followTail by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    val scope = rememberCoroutineScope()
    val hasOlder = state.olderCursor != null || state.hiddenOlderCount > 0
    val messages = remember(state.timeline, state.activeTurnId, state.selectedChat?.status) { conversationTimeline(state) }
    val currentMessages by rememberUpdatedState(messages)
    var showQuestions by remember(state.selectedChat?.id) { mutableStateOf(false) }
    var selectedQuestion by remember(state.selectedChat?.id) { mutableStateOf<String?>(null) }
    var jumpingToQuestion by remember(state.selectedChat?.id) { mutableStateOf(false) }
    val last = messages.lastOrNull()
    LaunchedEffect(last?.id, last?.text?.length) { if (followTail && !jumpingToQuestion && messages.isNotEmpty()) listState.scrollToItem(0) }
    LaunchedEffect(listState, state.olderCursor, state.hiddenOlderCount, state.olderError, state.olderLoading, state.connection) {
        if (hasOlder && state.olderError == null && !state.olderLoading && (state.hiddenOlderCount > 0 || state.connection == "Connected"))
            snapshotFlow { listState.layoutInfo.totalItemsCount > 0 && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= listState.layoutInfo.totalItemsCount - 3 }
                .collect { nearStart -> if (nearStart) loadOlder() }
    }
    Column(modifier.fillMaxWidth()) {
        if (messages.any { it.type == "userMessage" } || hasOlder) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showQuestions = true }, modifier = Modifier.testTag("your-questions"),
                enabled = messages.any { it.type == "userMessage" } || hasOlder) {
                Icon(Icons.Outlined.FormatListBulleted, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Your questions")
            }
        }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().testTag("history-list"), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(messages.asReversed(), key = { it.id }, contentType = { it.type }) { item ->
                MessageItem(item, showDiff, loadImage, answerQuestion, item.id in state.answeredInputs || questionAnswered(item, state.timeline),
                    editMessage = editMessage, canEdit = !state.sending && !state.loading && !state.uploading && !state.uncertainSend && state.selectedChat?.archived != true,
                    highlighted = item.id == selectedQuestion)
            }
            if (state.timeline.isEmpty() && state.chatSyncing) item {
                Row(Modifier.fillMaxWidth().padding(12.dp).testTag("chat-syncing"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Updating chat…", style = MaterialTheme.typography.labelSmall)
                }
            }
            else if (state.timeline.isEmpty() && !state.historyAvailable && state.selectedChat?.preview?.isNotBlank() == true) item { EmptyState("Earlier messages unavailable", "New messages will appear here. You can continue this conversation.") }
            else if (state.timeline.isEmpty()) item { NewConversationGreeting(state.chatMode) }
            if (hasOlder) item(key = "history-footer") {
                PageLoader(state.olderLoading, state.olderError, "Earlier messages load as you scroll", state.hiddenOlderCount > 0 || state.connection == "Connected") { loadOlder() }
            }
        }
        if (!followTail) FilledTonalIconButton(
            onClick = { scope.launch {
                selectedQuestion = null
                if (state.motion) listState.animateScrollToItem(0) else listState.scrollToItem(0)
            } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp).testTag("jump-to-latest")
        ) {
            Icon(Icons.Outlined.ArrowDownward, contentDescription = "Jump to latest", modifier = Modifier.size(24.dp))
        }
    }
    }
    if (showQuestions) QuestionNavigator(messages, hasOlder, state.olderLoading, state.olderError,
        state.hiddenOlderCount > 0 || state.connection == "Connected", loadOlder,
        select = { id ->
            showQuestions = false
            selectedQuestion = id
            jumpingToQuestion = true
            scope.launch {
                try {
                    questionScrollIndex(currentMessages, id)?.let { listState.scrollToItem(it) }
                    val layout = listState.layoutInfo
                    val target = layout.visibleItemsInfo.firstOrNull { it.key == id }
                    if (target != null) {
                        val viewport = layout.viewportSize.height - layout.beforeContentPadding - layout.afterContentPadding
                        val offset = questionScrollOffset(target.size, viewport)
                        // In reverse layout, a positive offset reveals the top of a tall bubble.
                        if (offset > 0) questionScrollIndex(currentMessages, id)?.let { listState.scrollToItem(it, offset) }
                    }
                }
                finally { jumpingToQuestion = false }
            }
        }, close = { showQuestions = false })
}

@Composable private fun PageLoader(loading: Boolean, error: String?, label: String, enabled: Boolean, retry: () -> Unit) {
    if (error != null) TextButton(onClick = retry, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(error) }
    else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (loading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (loading) "Loading…" else label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun MessageItem(item: TimelineItem, showDiff: (Change) -> Unit, loadImage: suspend (String) -> ByteArray,
    answerQuestion: (TimelineItem) -> Unit = {}, answered: Boolean = false,
    editMessage: ((TimelineItem) -> Unit)? = null, canEdit: Boolean = true, highlighted: Boolean = false) {
    var visibleLimit by rememberSaveable(item.id) { mutableIntStateOf(6000) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var copied by remember(item.id, item.text) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    val user = item.type == "userMessage"; val tool = item.type !in listOf("userMessage", "agentMessage", "finalChanges", "error")
    val finalChanges = item.type == "finalChanges"
    val label = if (user) "YOU" else if (item.type == "finalChanges") "CHANGES" else if (item.type == "error") "ERROR" else if (!tool) "CODEX" else if (item.type == "commandExecution") "COMMAND" else item.type.uppercase()
    Box(Modifier.fillMaxWidth().testTag("message:${item.id}")) {
    Column(modifier = Modifier.fillMaxWidth(if (user) 0.88f else 1f)
        .align(if (user) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)) {
    Surface(modifier = Modifier.fillMaxWidth(),
        color = if (user) MaterialTheme.colorScheme.surfaceVariant else if (tool) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, shape = RoundedCornerShape(if (user) 24.dp else 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (tool || user) 16.dp else 0.dp, vertical = if (tool || user) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tool || finalChanges) Surface(onClick = { expanded = !expanded }, modifier = Modifier.testTag("${if (finalChanges) "changes" else "command"}:${item.id}"), color = Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(item.detail.ifBlank { item.text }.lineSequence().firstOrNull().orEmpty().take(160), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (tool) Text(commandStatus(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (finalChanges) { if (expanded) "Collapse changes" else "Expand changes" } else if (expanded) "Collapse $label" else "Expand $label")
                }
            }
            if ((!tool && !finalChanges) || expanded) {
                Column(if (tool) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()) else Modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.type == "error") Text("Something went wrong", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                if (item.detail.isNotBlank()) SelectionContainer { Text(item.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                val visible = item.text.take(visibleLimit)
                if (item.commandOutputOmitted) Text("Command output was not downloaded. Enable Load command output in Settings to fetch it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("command-output-omitted:${item.id}"))
                if (visible.isNotBlank() && !finalChanges && !item.commandOutputOmitted) {
                    if (!tool && !user) MarkdownView(visible)
                    else if (item.type == "commandExecution") CodeBlock(item.text, "terminal", collapsible = false)
                    else SelectionContainer { Text(visible, style = if (tool) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default) }
                }
                if (item.text.length > visibleLimit && item.type != "commandExecution") TextButton(onClick = { visibleLimit += 6000 }) { Text("Show more output") }
                if (item.questions.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (answered) "Answer sent" else "Your answer is requested", fontWeight = FontWeight.SemiBold)
                        item.questions.forEach { Text(it.title) }
                        if (!answered) Button(onClick = { answerQuestion(item) }, modifier = Modifier.testTag("answer-question:${item.id}")) { Text("Answer questions") }
                    }
                }
                item.images.forEachIndexed { index, image -> HistoryImage(image.source, "${image.name} ${index + 1}", loadImage) }
                item.changes.forEach { change -> OutlinedButton(onClick = { showDiff(change) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Difference, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(change.path, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            }
            }
        }
    }
    if (!tool && !finalChanges && item.type != "error") Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (item.text.isNotBlank()) IconButton(onClick = { clipboard.setText(AnnotatedString(item.text)); copied = true },
            modifier = Modifier.size(40.dp).testTag("copy-message:${item.id}")) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                if (copied) "Message copied" else if (user) "Copy your message" else "Copy Codex message", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (user && editMessage != null && item.turnId.isNotBlank()) IconButton(onClick = { editMessage(item) }, enabled = canEdit,
            modifier = Modifier.size(40.dp).testTag("edit-message:${item.id}")) { Icon(Icons.Outlined.Edit, "Edit your message", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        vm.taskNotifications(allowed)
        if (!allowed) vm.notificationMessage("Notifications were not allowed. You can enable them in Android Settings.")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "dark", "light").forEach { mode -> FilterChip(selected = state.theme == mode, onClick = { vm.theme(mode) }, label = { Text(mode.replaceFirstChar { it.uppercase() }) }) } }
        Text("ACCENT STYLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("exchange", "ocean", "mint", "rose", "violet").forEach { accent -> FilterChip(selected = state.accent == accent, onClick = { vm.accent(accent) }, label = { Text(if (accent == "exchange") "Default" else accent.replaceFirstChar { it.uppercase() }) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Animations"); Text("Smooth screen and color transitions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = state.motion, onCheckedChange = vm::motion) }
        HorizontalDivider()
        CommandOutputSettings(state.loadCommandOutput, state.commandOutputChanging, state.commandOutputMessage, vm::loadCommandOutput)
        HorizontalDivider()
        TaskNotificationSettings(state.taskNotifications, state.notificationMessage, change = { enabled ->
            when {
                !enabled -> vm.taskNotifications(false)
                Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                TaskNotificationService.canNotify(context) -> vm.taskNotifications(true)
                else -> {
                    vm.taskNotifications(true)
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
        }, openSettings = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) })
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
        Text("An Android client for your private Codex server. Each account has its own chats and projects. Keep the server running and the computer awake.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (password) ChangePasswordDialog(state.passwordChanging, state.passwordMessage, state.passwordChanged,
        state.connection == "Connected", onDismiss = { password = false }, changePassword = vm::changePassword)
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text("Sign out of this phone?") }, text = { Text("End this phone's session. Your conversations remain on your computer.") },
        confirmButton = { TextButton(onClick = { vm.signOut(); signOut = false }) { Text("Confirm sign out") } }, dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } })
}

@Composable private fun ApprovalDialog(request: PendingInput, loading: Boolean, onDismiss: () -> Unit, onAnswer: (JsonObject) -> Unit) {
    val params = request.params
    var answers by remember(request.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val isForm = request.method == "mcpServer/elicitation/request"
    val isPermissions = request.method == "item/permissions/requestApproval"
    val properties = params.obj("requestedSchema").obj("properties")
    val required = params.obj("requestedSchema").array("required").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val formValid = required.all { answers[it]?.isNotBlank() == true }
    val availableDecisions = params.array("availableDecisions")
    val approveAllowed = availableDecisions.isEmpty() || availableDecisions.any { (it as? JsonPrimitive)?.contentOrNull == "accept" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isForm) "Tool needs your input" else "Review requested action") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(params.text("reason").ifEmpty { params.text("message").ifEmpty { request.method } }, style = MaterialTheme.typography.bodyMedium)
                if (isForm && properties.isNotEmpty()) properties.forEach { (id, value) ->
                    val spec = value.jsonObject
                    if (spec.text("type") == "boolean") Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(answers[id] == "true", { answers = answers + (id to it.toString()) }); Text(spec.text("title", id))
                    } else OutlinedTextField(answers[id].orEmpty(), { answers = answers + (id to it) }, Modifier.fillMaxWidth(), label = { Text(spec.text("title", id)) }, supportingText = { Text(spec.text("description").ifEmpty { spec.array("enum").joinToString() }) })
                } else SelectionContainer { Text(params.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text("An answer here resolves this request for all clients connected to this server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            TextButton(enabled = !loading && (!isForm || formValid) && (isForm || isPermissions || approveAllowed), onClick = {
                val result = when {
                    isForm -> buildJsonObject { put("action", "accept"); put("content", buildJsonObject { answers.forEach { (id, answer) ->
                        val type = (properties[id] as? JsonObject)?.text("type")
                        put(id, when (type) { "boolean" -> JsonPrimitive(answer == "true"); "integer" -> answer.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(answer); "number" -> answer.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(answer); else -> JsonPrimitive(answer) })
                    } }) }
                    isPermissions -> buildJsonObject { put("permissions", params.obj("permissions")); put("scope", "turn") }
                    request.method in listOf("applyPatchApproval", "execCommandApproval") -> jsonObject("decision" to "approved")
                    else -> jsonObject("decision" to "accept")
                }
                onAnswer(result)
            }) { Text(if (isForm) "Submit answer" else if (isPermissions) "Allow for this turn" else "Approve once") }
        }, dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Later") }
                TextButton(enabled = !loading, onClick = { onAnswer(when {
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
