package dev.nulltogeek.codexremote

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val rpc = RpcClient()
    private val mutable = MutableStateFlow(ScreenState(endpoint = store.endpoint, username = store.username, theme = store.theme, accent = store.accent, motion = store.motion))
    val state: StateFlow<ScreenState> = mutable.asStateFlow()
    private var reconnect: Job? = null
    private var searchJob: Job? = null
    private var updateJob: Job? = null
    private var olderJob: Job? = null
    private val catalogMutex = kotlinx.coroutines.sync.Mutex()
    private val inputMutex = kotlinx.coroutines.sync.Mutex()
    private val messageQueue = MessageQueue { endpoint, username, snapshot -> store.queue(endpoint, username, snapshot) }
    @Volatile private var queueStorageBlocked = false
    private val uploadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var lastUpdateCheck = 0L
    @Volatile private var foreground = false
    @Volatile private var lastEpoch = ""
    private var cachedThreads = buildJsonObject {}
    private var cachedList = JsonArray(emptyList())
    private var cachedInventory = buildJsonObject {}
    private val deletedChats = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val uncertainChats = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val sourceKinds = listOf("cli", "vscode", "exec", "appServer", "subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cache = store.cache(); cachedThreads = cache.obj("threads"); cachedList = cache.array("chats"); cachedInventory = cache.obj("inventory")
            val token = store.session()
            mutable.update { it.copy(signedIn = token.isNotEmpty(), chats = cachedList.mapNotNull { r -> (r as? JsonObject)?.let { o -> parseChat(o, o.text("cachedArchived") == "true") } }) }
            applyInventory(cachedInventory)
            loadFavorites()
            if (mutable.value.signedIn) loadQueue()
            if (foreground) withContext(Dispatchers.Main) { syncTaskNotifications() }
            connectIfForeground()
        }
        viewModelScope.launch {
            rpc.status.collect { status -> mutable.update { it.withConnectionStatus(status) } }
        }
        viewModelScope.launch {
            messageQueue.state.collect { queue -> mutable.update { it.copy(queuedMessages = queue.messages, pausedQueues = queue.pausedChats).resolveQueueNotices(queue) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                try { dispatchQueuedMessages() }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    queueStorageBlocked = true
                    mutable.update { it.copy(queueBlocked = true).withInfoNotice(NoticeKey(NoticeKind.QueueStorage), "Queue paused: ${e.message}. Tap Resume queue after fixing the problem.") }
                }
            }
        }
        // Actor processes notifications off the main thread in short batches.
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val first = rpc.events.receive(); val batch = mutableListOf(first)
                delay(50)
                repeat(1023) { rpc.events.tryReceive().getOrNull()?.let(batch::add) }
                applyEvents(batch)
            }
        }
    }
    fun onForeground() {
        foreground = true
        syncTaskNotifications()
        connectIfForeground()
    }
    private fun connectIfForeground() = viewModelScope.launch(Dispatchers.Main.immediate) {
        if (!foreground || !mutable.value.signedIn || reconnect?.isActive == true) return@launch
        reconnect = viewModelScope.launch(Dispatchers.IO) {
            var delayMs = 1000L
            while (foreground && isActive && mutable.value.signedIn) {
                try {
                    val session = store.session()
                    if (session.isEmpty()) throw SessionExpiredException()
                    rpc.configure(store.endpoint, session)
                    val snapshot = rpc.connect()
                    if (lastEpoch != snapshot.text("epoch")) mutable.update { it.copy(snapshotSequence = 0, itemSequences = emptyMap(), threadSequence = 0, statusSequence = 0, titleSequence = 0, pendingSequence = 0) }
                    lastEpoch = snapshot.text("epoch"); applySnapshot(snapshot)
                    checkAppUpdate(automatic = true)
                    refreshAll()
                    while (foreground && rpc.status.value == "Connected") delay(500)
                    if (rpc.status.value == "Session expired") throw SessionExpiredException()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is SessionExpiredException) { clearSession(e.message); break }
                    mutable.update { it.copy(connection = "Offline").withInfoNotice(NoticeKey(NoticeKind.Connection), e.message ?: "Cannot connect to host") }
                }
                if (foreground) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30000) }
            }
        }
    }
    fun onBackground() { foreground = false; disconnect() }
    private fun disconnect() { reconnect?.cancel(); reconnect = null; rpc.close() }
    fun signIn(endpoint: String, username: String, password: String) = action {
        val cleaned = endpoint.trim().trimEnd('/')
        val session = rpc.login(cleaned, username.trim(), password)
        // Verify the session before saving it; the password is never persisted.
        val host = rpc.get("/v1/host")
        store.signIn(cleaned, session)
        mutable.update { it.copy(endpoint = cleaned, username = session.username, signedIn = true, host = host.text("name"),
            passwordChangeAvailable = host.obj("capabilities")["passwordChange"]?.jsonPrimitive?.booleanOrNull == true, notice = null) }
        loadFavorites()
        loadQueue()
        syncTaskNotifications()
        connectIfForeground()
    }
    private fun clearSession(notice: String? = null) {
        updateJob?.cancel(); olderJob?.cancel()
        synchronized(uploadJobs) { uploadJobs.values.forEach { it.cancel() }; uploadJobs.clear() }
        TaskNotificationService.stop(getApplication())
        messageQueue.detach(); rpc.close(); store.signOut()
        mutable.update { ScreenState(endpoint = store.endpoint, username = store.username, theme = it.theme, accent = it.accent, motion = it.motion, notice = notice) }
    }
    fun signOut() = action {
        disconnect()
        var notice: String? = null
        try { rpc.configure(store.endpoint, store.session()); rpc.logout() }
        catch (e: Exception) { if (e is CancellationException) throw e; notice = "Signed out on this phone. Server session could not be revoked while offline." }
        finally { clearSession(notice) }
    }
    fun notificationMessage(message: String) { mutable.update { it.copy(notificationMessage = message) } }
    fun taskNotifications(enabled: Boolean) {
        store.taskNotifications(enabled)
        if (!enabled) TaskNotificationService.stop(getApplication())
        syncTaskNotifications()
    }
    private fun syncTaskNotifications() {
        val app = getApplication<Application>()
        val allowed = TaskNotificationService.canNotify(app)
        val enabled = store.taskNotifications
        val started = if (enabled && allowed && foreground && mutable.value.signedIn) TaskNotificationService.start(app) else false
        if (!allowed) TaskNotificationService.stop(app)
        mutable.update { it.copy(taskNotifications = enabled && allowed, notificationMessage = when {
            enabled && !allowed -> "Allow completed-task notifications in Android Settings."
            enabled && foreground && it.signedIn && !started -> "Monitoring could not start. Reopen the app to retry."
            else -> ""
        }) }
    }
    fun openNotificationChat(id: String, endpoint: String, username: String) {
        val current = mutable.value
        if (!notificationAccountMatches(current, endpoint, username) || id.isBlank()) return
        action {
            val raw = rpc.call("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", false) }).obj("thread")
            if (!notificationAccountMatches(mutable.value, endpoint, username)) return@action
            val chat = mutable.value.chats.find { it.id == id } ?: parseChat(raw, raw["archived"]?.jsonPrimitive?.booleanOrNull == true)
            page(if (chat.archived) "archived" else "chats"); open(chat)
        }
    }
    fun theme(value: String) { store.theme(value); mutable.update { it.copy(theme = value) } }
    fun accent(value: String) { store.accent(value); mutable.update { it.copy(accent = value) } }
    fun motion(value: Boolean) { store.motion(value); mutable.update { it.copy(motion = value) } }
    private fun loadFavorites() {
        val current = mutable.value
        val (chats, projects) = store.favorites(current.endpoint, current.username)
        mutable.update { it.copy(favoriteChats = chats, favoriteProjectIds = projects) }
    }
    private fun saveFavorites() {
        val current = mutable.value
        store.favorites(current.endpoint, current.username, current.favoriteChats, current.favoriteProjectIds)
    }
    fun favorite(chat: Chat) {
        mutable.update { it.copy(favoriteChats = if (it.favoriteChats.any { c -> c.id == chat.id }) it.favoriteChats.filter { c -> c.id != chat.id } else it.favoriteChats + chat.copy(projectId = it.assignments[chat.id] ?: chat.projectId)) }
        saveFavorites()
    }
    fun favorite(project: Project) {
        mutable.update { it.copy(favoriteProjectIds = if (project.id in it.favoriteProjectIds) it.favoriteProjectIds - project.id else it.favoriteProjectIds + project.id) }
        saveFavorites()
    }
    fun passwordFormOpened() { mutable.update { it.copy(passwordMessage = "", passwordChanged = false) } }
    fun changePassword(currentPassword: String, newPassword: String) {
        val initial = mutable.value
        if (!initial.signedIn || initial.connection != "Connected" || initial.passwordChanging) return
        mutable.update { it.copy(passwordChanging = true, passwordChanged = false, passwordMessage = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rpc.changePassword(currentPassword, newPassword)
                mutable.update { it.copy(passwordChanged = true, passwordMessage = "Password changed. Other sessions have been signed out.") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                else mutable.update { it.copy(passwordMessage = e.message ?: "Password change failed.") }
            } finally { mutable.update { it.copy(passwordChanging = false) } }
        }
    }
    fun updateMessage(message: String) { mutable.update { it.copy(updateMessage = message) } }
    fun checkAppUpdate(automatic: Boolean = false) {
        val initial = mutable.value
        if (!initial.signedIn || initial.updateChecking || initial.updateDownloading) return
        if (automatic && System.currentTimeMillis() - lastUpdateCheck < 6 * 60 * 60 * 1000L) return
        lastUpdateCheck = System.currentTimeMillis()
        val session = store.session()
        mutable.update { it.copy(updateChecking = true, updateMessage = if (automatic) it.updateMessage else "Checking for updates…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val update = parseAppUpdate(rpc.get("/v1/app-update?channel=$appUpdateChannel"), BuildConfig.APPLICATION_ID, android.os.Build.VERSION.SDK_INT)
                val available = update.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                val cached = available?.let { java.io.File(getApplication<Application>().cacheDir, "apk-updates/${it.sha256}.apk") }
                    ?.takeIf { it.isFile && runCatching { verifyUpdateApk(getApplication(), it, update) }.isSuccess }
                if (store.session() == session) mutable.update { it.copy(appUpdate = available, updateApk = cached?.absolutePath,
                    notice = if (automatic && available != null && available.versionCode != it.appUpdate?.versionCode && it.notice == null) "Version ${available.versionName} is available. Open Settings to update." else it.notice,
                    updateMessage = if (available == null) "You’re up to date · ${BuildConfig.VERSION_NAME}" else if (cached != null) "Downloaded and verified. Ready to install." else "Version ${available.versionName} is available") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (store.session() == session) {
                    if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                    else if (!automatic) mutable.update { it.copy(updateMessage = when {
                        e is HostHttpException && e.statusCode in listOf(404, 503) -> "No app update is available on this server yet."
                        e is IllegalArgumentException -> e.message ?: "Invalid app update information"
                        else -> "Could not check for updates. Check your WireGuard connection and try again."
                    }) }
                }
            } finally { if (store.session() == session) mutable.update { it.copy(updateChecking = false) } }
        }
    }
    fun downloadAppUpdate() {
        val initial = mutable.value; val update = initial.appUpdate ?: return
        if (initial.updateDownloading || initial.updateChecking || update.versionCode <= BuildConfig.VERSION_CODE || !initial.signedIn) return
        val session = store.session()
        mutable.update { it.copy(updateDownloading = true, updateProgress = 0, updateApk = null, updateMessage = "Downloading update…") }
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = rpc.downloadAppUpdate(update, java.io.File(getApplication<Application>().cacheDir, "apk-updates")) { percent ->
                    if (store.session() == session) mutable.update { it.copy(updateProgress = percent) }
                }
                verifyUpdateApk(getApplication(), file, update)
                if (store.session() == session) mutable.update { it.copy(updateApk = file.absolutePath, updateMessage = "Downloaded and verified. Ready to install.") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (store.session() == session) {
                    if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                    else mutable.update { it.copy(updateMessage = e.message ?: "Update download failed. Try again.") }
                }
            } finally { if (store.session() == session) mutable.update { it.copy(updateDownloading = false) } }
        }
    }
    fun chatMode(value: String) {
        if (mutable.value.selectedChat?.status == "active" || value !in listOf("codex", "chat")) return
        mutable.update { it.copy(chatMode = value) }; saveChatOptions()
    }
    fun accessMode(value: String) {
        if (mutable.value.selectedChat?.status == "active" || value !in listOf("host", "ask", "read-only", "full")) return
        mutable.update { it.copy(accessMode = value) }; saveChatOptions()
    }
    private fun saveChatOptions() { val state = mutable.value; store.chatOptions(state.selectedChat?.id ?: "new", state.chatMode, state.accessMode) }
    private fun mobileParams(state: ScreenState) = buildJsonObject { put("mobileMode", state.chatMode); put("accessMode", state.accessMode) }
    fun page(value: String) { mutable.update { it.copy(page = value, selectedChat = null, diff = null) } }
    fun filterProject(id: String?) { mutable.update { it.copy(page = if (id == null && it.page == "archived") "archived" else "chats", projectFilter = id, selectedChat = null) } }
    fun search(value: String) {
        mutable.update { it.copy(searchTerm = value, catalogRevision = it.catalogRevision + 1, catalogError = null, catalogErrorArchived = null, activeCursor = null, archivedCursor = null) }; searchJob?.cancel()
        val revision = mutable.value.catalogRevision
        if (rpc.status.value != "Connected") return
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            try {
                catalogMutex.withLock {
                    if (mutable.value.catalogRevision != revision) return@withLock
                    val active = listPage(false, null, value); val archived = listPage(true, null, value)
                    if (mutable.value.catalogRevision != revision) return@withLock
                    cachedList = JsonArray(catalogRows(active, false) + catalogRows(archived, true))
                    mutable.update { if (it.catalogRevision != revision) it else it.copy(chats = mergeCatalogChats(emptyList(), cachedList.map { r -> parseChat(r.jsonObject, r.jsonObject.text("cachedArchived") == "true") }),
                        activeCursor = active.text("nextCursor").ifEmpty { null }, archivedCursor = archived.text("nextCursor").ifEmpty { null }).resolveInfoNotice(NoticeKey(NoticeKind.Search)) }
                    saveCache()
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { if (it.catalogRevision != revision) it else it.withInfoNotice(NoticeKey(NoticeKind.Search), "Search unavailable: ${e.message}") } }
        }
    }
    fun draft(text: String) {
        mutable.update { it.copy(draft = text) }
        store.draft(mutable.value.selectedChat?.id ?: "new", text)
    }
    fun model(id: String) { mutable.update { it.copy(selectedModel = id, selectedEffort = it.models.find { m -> m.id == id }?.defaultEffort.orEmpty()) } }
    fun effort(value: String) { mutable.update { it.copy(selectedEffort = value) } }
    fun noticeDismiss() { mutable.update { it.copy(notice = null) } }
    fun beginRename(chat: Chat) {
        mutable.update { if (!canRename(it)) it else it.copy(renameTarget = RenameTarget(RenameKind.Chat, chat.id, chat.title), renameError = null) }
    }
    fun beginRename(project: Project) {
        mutable.update { if (!canRename(it)) it else it.copy(renameTarget = RenameTarget(RenameKind.Project, project.id, project.name), renameError = null) }
    }
    fun dismissRename() { mutable.update { if (it.renaming) it else it.copy(renameTarget = null, renameError = null) } }
    fun rename(name: String) {
        val initial = mutable.value
        val target = initial.renameTarget ?: return
        val validation = renameValidation(name)
        if (validation != null) { mutable.update { it.copy(renameError = validation) }; return }
        if (!canRename(initial) || name.trim() == target.name) return
        if (!mutable.compareAndSet(initial, initial.copy(renaming = true, renameError = null))) return
        viewModelScope.launch(Dispatchers.IO) {
            var accepted = false
            try {
                val result = rpc.call(if (target.kind == RenameKind.Chat) "thread/name/set" else "project/update",
                    jsonObject((if (target.kind == RenameKind.Chat) "threadId" else "projectId") to target.id, "name" to name.trim()))
                accepted = true
                if (mutable.value.endpoint != initial.endpoint || mutable.value.username != initial.username || !mutable.value.signedIn) return@launch
                val savedName = if (target.kind == RenameKind.Project) result.obj("project").text("name", name.trim()) else name.trim()
                if (target.kind == RenameKind.Chat) {
                    val sequence = result["_bridgeSequence"]?.jsonPrimitive?.longOrNull ?: 0
                    mutable.update { it.withRenamedChat(target.id, savedName).let { next ->
                        if (next.selectedChat?.id == target.id) next.copy(titleSequence = maxOf(next.titleSequence, sequence)) else next } }
                    cachedList = JsonArray(cachedList.map { if (it.jsonObject.text("id") == target.id) renameCachedChat(it.jsonObject, savedName) else it })
                    cachedThreads[target.id]?.let { cachedThreads = JsonObject(cachedThreads + (target.id to renameCachedChat(it.jsonObject, savedName))) }
                    saveFavorites()
                } else {
                    mutable.update { it.copy(projects = it.projects.map { p -> if (p.id == target.id) p.copy(name = savedName) else p }) }
                    cachedInventory = JsonObject(cachedInventory + ("projects" to JsonArray(cachedInventory.array("projects").map {
                        if (it.jsonObject.text("id") == target.id) JsonObject(it.jsonObject + ("name" to JsonPrimitive(savedName))) else it
                    })))
                }
                saveCache()
                mutable.update { if (it.renameTarget != target) it else it.copy(renameTarget = null, renameError = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                else mutable.update { if (it.renameTarget != target) it else it.copy(renameError =
                    if (accepted) "Name saved on your computer. Local cache could not update; refresh history."
                    else e.message ?: "Could not rename. Try again.") }
            } finally { mutable.update { it.copy(renaming = false) } }
        }
    }
    fun infoNoticeDismiss(key: NoticeKey) { mutable.update { it.resolveInfoNotice(key) } }
    fun showDiff(change: Change?) { mutable.update { it.copy(diff = change) } }
    suspend fun attachmentImage(source: String): ByteArray = rpc.downloadAttachmentImage(source)
    fun refresh() {
        // Claim the refresh before launching IO so a second gesture/button tap
        // cannot start another request while the first coroutine is queued.
        while (true) {
            val current = mutable.value
            if (!canRefreshHistory(current)) return
            if (mutable.compareAndSet(current, current.copy(refreshing = true))) break
        }
        action(NoticeKey(NoticeKind.Catalog)) {
            try {
                refreshAll()
                mutable.update { if (it.historyAvailable) { it.selectedChat?.id?.let(uncertainChats::remove); it.copy(uncertainSend = false) } else it }
            } finally { mutable.update { it.copy(refreshing = false) } }
        }
    }

    private suspend fun refreshAll() {
        mutable.update { it.copy(catalogRevision = it.catalogRevision + 1, catalogError = null, catalogErrorArchived = null, activeCursor = null, archivedCursor = null) }
        val revision = mutable.value.catalogRevision
        val query = mutable.value.searchTerm
        val host = rpc.get("/v1/host"); mutable.update { it.copy(host = host.text("name", "Your computer"),
            passwordChangeAvailable = host.obj("capabilities")["passwordChange"]?.jsonPrimitive?.booleanOrNull == true) }
        val inventory = rpc.get("/v1/projects"); cachedInventory = inventory; applyInventory(inventory)
        catalogMutex.withLock {
            if (mutable.value.catalogRevision != revision) return@withLock
            val active = listPage(false, null, query); val archived = listPage(true, null, query)
            if (mutable.value.catalogRevision != revision) return@withLock
            cachedList = JsonArray(refreshCatalogRows(cachedList, active, archived).filterNot { it.jsonObject.text("id") in deletedChats })
            mutable.update { if (it.catalogRevision != revision) it else it.copy(chats = mergeCatalogChats(emptyList(), cachedList.map { r -> parseChat(r.jsonObject, r.jsonObject.text("cachedArchived") == "true") }),
                activeCursor = active.text("nextCursor").ifEmpty { null }, archivedCursor = archived.text("nextCursor").ifEmpty { null }).resolveInfoNotice(NoticeKey(NoticeKind.Search)) }
        }
        if (mutable.value.catalogRevision != revision) return
        // Enrich the read-only full catalogue with live status from the shared runtime.
        val live = rpc.call("thread/list", buildJsonObject { put("limit", 50); put("sortKey", "updated_at"); put("useStateDbOnly", true)
            put("modelProviders", buildJsonArray {}); put("sourceKinds", JsonArray(sourceKinds.map(::JsonPrimitive))) })
        val liveById = live.array("data").mapNotNull { r -> (r as? JsonObject)?.let { parseChat(it) } }.associateBy { it.id }
        mutable.update { it.copy(chats = it.chats.map { c -> liveById[c.id]?.copy(archived = c.archived) ?: c }) }
        val models = rpc.call("model/list", buildJsonObject {})
        mutable.update { it.copy(models = models.array("data").mapNotNull { r -> r as? JsonObject }.map { m -> ModelChoice(m.text("id"), m.text("displayName").ifEmpty { m.text("id") },
            m.array("supportedReasoningEfforts").mapNotNull { r -> (r as? JsonObject)?.text("reasoningEffort") }, m.text("defaultReasoningEffort")) }) }
        applySnapshot(rpc.get("/v1/snapshot"))
        mutable.value.selectedChat?.id?.let { loadThread(it) }
        saveCache()
        mutable.update { if (it.catalogRevision != revision) it else it.resolveInfoNotice(NoticeKey(NoticeKind.Catalog)) }
    }
    private fun applyInventory(raw: JsonObject) {
        val assignments = raw.obj("assignments").mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val projects = raw.array("projects").mapNotNull { it as? JsonObject }.map { Project(it.text("id"), it.text("name"), it.array("rootPaths").mapNotNull { r -> (r as? JsonPrimitive)?.contentOrNull }) }
        mutable.update { it.copy(projects = projects, assignments = assignments) }
    }
    private fun applySnapshot(raw: JsonObject) {
        if (raw.text("epoch") != lastEpoch) return
        mutable.update { reconcilePendingSnapshot(it, raw) }
    }
    private suspend fun listPage(archived: Boolean, cursor: String?, query: String = mutable.value.searchTerm): JsonObject = rpc.get("/v1/history?archived=$archived" + (cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "") + "&searchTerm=" + java.net.URLEncoder.encode(query, "UTF-8"))
    fun loadMore(archived: Boolean): Job {
        val request = catalogPageRequest(mutable.value, archived)
        return viewModelScope.launch(Dispatchers.IO) {
            if (request == null) return@launch
            catalogMutex.withLock {
                // Queued callbacks for the same page or an earlier search become no-ops.
                if (!request.isCurrent(mutable.value)) return@withLock
                mutable.update { it.copy(catalogLoading = true, catalogError = null, catalogErrorArchived = null) }
                try {
                    val page = listPage(archived, request.cursor, request.query)
                    if (mutable.value.catalogRevision != request.revision) return@withLock
                    val raw = catalogRows(page, archived).filterNot { it.text("id") in deletedChats }
                    cachedList = mergeCatalogRows(cachedList, raw)
                    mutable.update { if (it.catalogRevision != request.revision) it else it.copy(chats = mergeCatalogChats(it.chats, raw.map { r -> parseChat(r, archived) }),
                        activeCursor = if (!archived) page.text("nextCursor").takeIf { next -> next.isNotEmpty() && next != request.cursor } else it.activeCursor,
                        archivedCursor = if (archived) page.text("nextCursor").takeIf { next -> next.isNotEmpty() && next != request.cursor } else it.archivedCursor) }
                    saveCache()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                    else mutable.update { if (it.catalogRevision != request.revision) it else it.copy(catalogError = "Could not load more chats. Tap to retry.", catalogErrorArchived = archived) }
                } finally { mutable.update { it.copy(catalogLoading = false) } }
            }
        }
    }
    fun open(chat: Chat) {
        if (chat.id in deletedChats) return
        olderJob?.cancel()
        synchronized(uploadJobs) { uploadJobs.values.forEach { it.cancel() }; uploadJobs.clear() }
        val history = cachedThreads[chat.id]?.let { parseTimeline(it.jsonObject) }.orEmpty()
        val window = historyWindow(history, emptyList())
        mutable.update { it.copy(selectedChat = chat, sentMessageEdit = null, writerConflict = false, notice = null, historyRevision = it.historyRevision + 1, diff = null, timeline = window, hiddenOlderCount = history.size - window.size, olderLoading = false, olderError = null, draft = store.draft(chat.id), attachments = store.attachments(chat.id), uploading = false, chatMode = store.chatMode(chat.id), accessMode = store.accessMode(chat.id), selectedModel = "", selectedEffort = "", uncertainSend = chat.id in uncertainChats, historyAvailable = true, activeTurnId = "", snapshotSequence = 0, itemSequences = emptyMap(), threadSequence = 0, olderCursor = null, lastTurnStatus = "", statusSequence = 0, titleSequence = 0) }
        if (rpc.status.value == "Connected") action(NoticeKey(NoticeKind.History, chat.id)) { loadThread(chat.id) }
    }
    private suspend fun loadThread(id: String) {
        val revision = mutable.value.historyRevision
        val loaded = readThreadHistory(id, rpc::call)
        val previous = cachedThreads[id]?.jsonObject
        val mergedThread = if (loaded.olderCursor != null && previous != null) JsonObject(loaded.thread + ("turns" to
            JsonArray((previous.array("turns") + loaded.thread.array("turns")).distinctBy { it.jsonObject.text("id") }.map { turn ->
                loaded.thread.array("turns").find { it.jsonObject.text("id") == turn.jsonObject.text("id") } ?: turn
            }))) else loaded.thread
        val raw = retainFinalDiffs(mergedThread, mutable.value.timeline.takeIf { mutable.value.selectedChat?.id == id }.orEmpty(), previous)
        if (id in deletedChats) return
        if (loaded.authoritative || !cachedThreads.containsKey(id)) cachedThreads = buildJsonObject {
            cachedThreads.entries.toList().takeLast(7).forEach { (k, v) -> put(k, v) }; put(id, raw)
        }
        val cutoff = loaded.sequence
        mutable.update { current ->
            if (current.selectedChat?.id != id || current.historyRevision != revision) current else {
                val newer = current.timeline.filter { (current.itemSequences[it.id] ?: 0) > cutoff }.associateBy { it.id }
                val fullHistory = if (loaded.authoritative) parseTimeline(raw) else cachedThreads[id]?.let { parseTimeline(it.jsonObject) }.orEmpty()
                val history = historyWindow(fullHistory, current.timeline)
                val merged = if (!loaded.authoritative) current.timeline else history.map { newer[it.id] ?: it } + newer.values.filter { n -> history.none { it.id == n.id } }
                val summary = parseChat(raw, current.selectedChat.archived)
                reconcileChatMetadata(current, summary, loaded.metadataSequence).copy(timeline = merged, hiddenOlderCount = fullHistory.size - history.size, olderCursor = loaded.olderCursor, historyAvailable = loaded.authoritative,
                    selectedModel = current.selectedModel.ifEmpty { raw.text("model") }, selectedEffort = current.selectedEffort.ifEmpty { raw.text("reasoningEffort") },
                    lastTurnStatus = if (!loaded.authoritative || current.threadSequence > cutoff) current.lastTurnStatus else (raw.array("turns").lastOrNull() as? JsonObject)?.text("status").orEmpty(),
                    activeTurnId = if (!loaded.authoritative || current.threadSequence > cutoff) current.activeTurnId else raw.array("turns").mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }?.text("id").orEmpty(),
                    snapshotSequence = if (loaded.authoritative) cutoff else current.snapshotSequence, itemSequences = if (loaded.authoritative) current.itemSequences.filterValues { it > cutoff } else current.itemSequences)
            }
        }
        saveCache()
        if (loaded.authoritative) mutable.update { it.resolveInfoNotice(NoticeKey(NoticeKind.History, id)) }
    }
    fun loadOlder() {
        val initial = mutable.value
        val id = initial.selectedChat?.id ?: return
        if (initial.olderLoading || olderJob?.isActive == true || (initial.hiddenOlderCount == 0 && initial.connection != "Connected")) return
        if (initial.hiddenOlderCount == 0 && initial.olderCursor == null) return
        mutable.update { it.copy(olderLoading = true, olderError = null) }
        olderJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (initial.hiddenOlderCount > 0) {
                    val all = cachedThreads[id]?.let { parseTimeline(it.jsonObject) }.orEmpty()
                    val older = all.take(initial.hiddenOlderCount).takeLast(60)
                    mutable.update { if (it.selectedChat?.id != id || it.historyRevision != initial.historyRevision) it else it.copy(
                        timeline = (older + it.timeline).distinctBy { item -> item.id }, hiddenOlderCount = (initial.hiddenOlderCount - older.size).coerceAtLeast(0)) }
                } else {
                    val page = rpc.call("thread/turns/list", buildJsonObject { put("threadId", id); put("cursor", initial.olderCursor!!); put("limit", 20); put("itemsView", "full"); put("sortDirection", "desc") })
                    val turns = JsonArray(page.array("data").reversed())
                    val older = parseTimeline(buildJsonObject { put("turns", turns) })
                    mutable.update { if (it.selectedChat?.id != id || it.historyRevision != initial.historyRevision || it.olderCursor != initial.olderCursor) it else it.copy(
                        timeline = (older + it.timeline).distinctBy { item -> item.id }, olderCursor = page.text("nextCursor").takeIf { next -> next.isNotEmpty() && next != initial.olderCursor }) }
                    val cached = cachedThreads[id]?.jsonObject ?: buildJsonObject {}
                    cachedThreads = JsonObject(cachedThreads + (id to JsonObject(cached + ("turns" to JsonArray((turns + cached.array("turns")).distinctBy { it.jsonObject.text("id") })))))
                    saveCache()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                else mutable.update { if (it.selectedChat?.id != id || it.historyRevision != initial.historyRevision) it else it.copy(olderError = "Could not load earlier messages. Tap to retry.") }
            } finally { mutable.update { if (it.selectedChat?.id != id || it.historyRevision != initial.historyRevision) it else it.copy(olderLoading = false) } }
        }
    }
    fun newChat(project: Project? = null) {
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true) }
        action {
        val options = mutable.value
        val result = rpc.call("thread/start", buildJsonObject { mobileParams(options).forEach { (k, v) -> put(k, v) }; put("historyMode", "legacy"); project?.roots?.firstOrNull()?.let { put("cwd", it) } })
        val chat = parseChat(result.obj("thread")); mutable.update { it.copy(chats = listOf(chat) + it.chats.filter { c -> c.id != chat.id }) }
        store.chatOptions(chat.id, options.chatMode, options.accessMode)
        open(chat)
        mutable.update { it.copy(selectedModel = result.text("model"), selectedEffort = result.text("reasoningEffort")) }
        if (project != null) {
            rpc.call("thread/metadata/update", jsonObject("threadId" to chat.id, "projectId" to project.id))
            mutable.update { it.copy(assignments = it.assignments + (chat.id to project.id)) }
        }
    }
    }
    fun editSentMessage(item: TimelineItem) {
        val source = mutable.value
        val chat = source.selectedChat ?: return
        if (item.type != "userMessage" || item.turnId.isBlank() || source.sending || source.loading || source.uploading ||
            source.uncertainSend || chat.archived || source.sentMessageEdit?.working == true || source.timeline.none { it.id == item.id }) return
        if (messageQueue.state.value.messages.any { it.chatId == chat.id } && !pauseQueue(chat.id)) return
        mutable.update { if (it.selectedChat?.id != chat.id) it else it.copy(sentMessageEdit =
            SentMessageEdit(chat.id, item.id, item.turnId, item.text, sentAttachments(item.input)), notice = null) }
    }
    fun sentEditText(text: String) {
        mutable.update { state -> state.copy(sentMessageEdit = state.sentMessageEdit?.let { if (it.working) it else it.copy(text = text, error = null) }) }
    }
    fun removeSentEditAttachment(id: String) {
        mutable.update { state -> state.copy(sentMessageEdit = state.sentMessageEdit?.let { if (it.working) it else it.copy(attachments = it.attachments.filterNot { a -> a.id == id }, error = null) }) }
    }
    fun cancelSentEdit() {
        mutable.update { if (it.sentMessageEdit?.working == true) it else it.copy(sentMessageEdit = null) }
    }
    fun resendEditedMessage() {
        val source = mutable.value
        val edit = source.sentMessageEdit ?: return
        val original = source.selectedChat ?: return
        if (edit.chatId != original.id || edit.working || edit.uncertainFork || source.connection != "Connected" || source.sending ||
            source.loading || source.uploading || source.uncertainSend || original.archived || original.status == "active" || source.activeTurnId.isNotEmpty() ||
            (edit.text.isBlank() && edit.attachments.isEmpty())) return
        if (!mutable.compareAndSet(source, source.copy(sending = true, sentMessageEdit = edit.copy(working = true, error = null)))) return
        val session = store.session()
        viewModelScope.launch(Dispatchers.IO) {
            inputMutex.withLock {
                var branchId = edit.branchId
                var forkAttempted = false
                fun sameAccount() = mutable.value.signedIn && mutable.value.endpoint == source.endpoint && mutable.value.username == source.username && store.session() == session
                try {
                    val plan = sentEditPlan(edit, rpc::call)
                    if (branchId == null) {
                        forkAttempted = true
                        val result = rpc.call("thread/fork", sentEditForkParams(source, edit))
                        val id = result.obj("thread").text("id")
                        check(id.isNotBlank() && id != original.id) { "The server did not create a separate chat." }
                        branchId = id
                        check(sameAccount()) { "The account changed. Check Chats for the copied conversation." }
                        mutable.update { it.copy(sentMessageEdit = it.sentMessageEdit?.takeIf { e -> e.chatId == edit.chatId && e.itemId == edit.itemId }?.copy(branchId = id)) }
                    }
                    val id = requireNotNull(branchId)
                    verifySentEditBranch(id, original.id, plan, rpc::call)
                    check(sameAccount()) { "The account changed. Check Chats for the copied conversation." }
                    store.editedDraft(id, sentEditInput(edit, plan), source.chatMode, source.accessMode)
                    val title = original.title.take(85) + " (edited)"
                    rpc.call("thread/name/set", jsonObject("threadId" to id, "name" to title))
                    val projectId = source.assignments[original.id] ?: original.projectId
                    if (projectId != null) rpc.call("thread/metadata/update", jsonObject("threadId" to id, "projectId" to projectId))
                    check(sameAccount()) { "The account changed. Your copied conversation remains on the server." }
                    val branch = Chat(id, title, edit.text.take(100), original.cwd, status = "idle", projectId = projectId)
                    val openEdited = mutable.value.selectedChat?.id == original.id && mutable.value.sentMessageEdit?.itemId == edit.itemId
                    mutable.update { it.copy(chats = listOf(branch) + it.chats.filter { c -> c.id != id },
                        assignments = if (projectId == null) it.assignments else it.assignments + (id to projectId)) }
                    if (openEdited) {
                        open(branch)
                        mutable.update { it.copy(selectedModel = source.selectedModel, selectedEffort = source.selectedEffort,
                            sending = false, notice = "Edited message continues here. Your original chat is saved.") }
                        send()
                    } else mutable.update { it.copy(sending = false, notice = "Edited chat saved in Chats. Open it and tap Send to continue.") }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    val uncertain = forkAttempted && branchId == null && e !is RpcException && e !is SessionExpiredException
                    if (sameAccount()) mutable.update { state -> state.copy(sending = false,
                        sentMessageEdit = state.sentMessageEdit?.takeIf { it.chatId == edit.chatId && it.itemId == edit.itemId }?.copy(
                            working = false, branchId = branchId, uncertainFork = uncertain,
                            error = if (uncertain) "Could not confirm the new chat. Check Chats before trying again; your edit was not resent."
                                else e.message ?: "Could not resend your edit."),
                        notice = if (state.sentMessageEdit == null) e.message else state.notice) }
                    if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                }
            }
        }
    }
    fun send() {
        val state = mutable.value; val chat = state.selectedChat ?: return
        if ((state.draft.isBlank() && state.attachments.isEmpty()) || state.attachments.any { !it.readyToSend } || state.uploading || state.sending || state.sentMessageEdit != null || state.uncertainSend || state.connection != "Connected" || chat.archived) return
        val text = state.draft
        if (chat.status == "active" || state.activeTurnId.isNotEmpty() || messageQueue.state.value.messages.any { it.chatId == chat.id }) {
            try { enqueueDraft(state) } catch (e: Exception) { mutable.update { it.copy(notice = e.message) } }
            return
        }
        if (!mutable.compareAndSet(state, state.copy(sending = true, notice = null, writerConflict = false))) return
        viewModelScope.launch(Dispatchers.IO) {
            inputMutex.withLock {
            var mutationAttempted = false
            var acknowledged = false
            try {
                submitQueuedMessage(queuedDraft(state), rpc::call, allowFailedTurn = true) { mutationAttempted = true }
                acknowledged = true
                uncertainChats.remove(chat.id)
                val cleared = store.clearSentDraft(chat.id, text)
                val sentIds = state.attachments.map { it.id }.toSet()
                store.attachments(chat.id, store.attachments(chat.id).filter { it.id !in sentIds })
                mutable.update { if (it.selectedChat?.id == chat.id) it.copy(draft = if (cleared && it.draft == text) "" else it.draft, attachments = it.attachments.filter { a -> a.id !in sentIds }, sending = false) else it.copy(sending = false) }
                loadThread(chat.id)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (e is QueueWaitingException) {
                    try { enqueueDraft(state) } catch (saveError: Exception) { mutable.update { it.copy(notice = saveError.message) } }
                    mutable.update { it.copy(sending = false) }
                    return@withLock
                }
                val uncertain = uncertainDelivery(mutationAttempted, acknowledged, e)
                if (uncertain) uncertainChats.add(chat.id)
                mutable.update { if (it.selectedChat?.id != chat.id) it.copy(sending = false) else it.copy(sending = false, uncertainSend = uncertain, writerConflict = !acknowledged && writerConflict(e),
                    notice = if (!acknowledged && writerConflict(e)) null else if (acknowledged) it.notice
                    else (e.message ?: "Send failed") + if (uncertain) ". Refresh and check history before sending again." else "")
                    .let { next -> if (acknowledged) next.withInfoNotice(NoticeKey(NoticeKind.History, chat.id),
                        "Message accepted. History refresh is temporarily unavailable.") else next } }
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
            }
            }
        }
    }
    private fun queuedDraft(state: ScreenState) = QueuedMessage(java.util.UUID.randomUUID().toString(), requireNotNull(state.selectedChat).id,
        state.draft, state.attachments, state.chatMode, state.accessMode, state.selectedModel, state.selectedEffort)
    private fun enqueueDraft(snapshot: ScreenState) {
        val chatId = requireNotNull(snapshot.selectedChat).id
        messageQueue.add(queuedDraft(snapshot))
        val cleared = store.clearSentDraft(chatId, snapshot.draft)
        val attachmentIds = snapshot.attachments.map { it.id }.toSet()
        store.attachments(chatId, store.attachments(chatId).filter { it.id !in attachmentIds })
        mutable.update { if (it.selectedChat?.id != chatId) it else it.copy(draft = if (cleared && it.draft == snapshot.draft) "" else it.draft,
            attachments = it.attachments.filter { a -> a.id !in attachmentIds }, notice = null) }
    }
    private fun loadQueue() {
        val current = mutable.value
        messageQueue.restore(current.endpoint, current.username, store.queue(current.endpoint, current.username))
        queueStorageBlocked = false
        mutable.update { it.copy(queueBlocked = false).resolveInfoNotice(NoticeKey(NoticeKind.QueueStorage)) }
    }
    private fun queueAction(block: () -> Unit): Boolean {
        return try { block(); true } catch (e: Exception) {
            queueStorageBlocked = true
            mutable.update { it.copy(queueBlocked = true).withInfoNotice(NoticeKey(NoticeKind.QueueStorage), e.message ?: "Could not update the queue") }
            false
        }
    }
    fun editQueued(id: String, text: String, attachments: List<Attachment>) = queueAction { messageQueue.edit(id, text, attachments) }
    fun moveQueued(id: String, offset: Int) { queueAction { messageQueue.move(id, offset) } }
    fun removeQueued(id: String) { queueAction { messageQueue.remove(id) } }
    fun retryQueued(id: String) { queueAction { messageQueue.retry(id) } }
    fun pauseQueue(chatId: String) = queueAction { messageQueue.pause(chatId) }
    fun resumeQueue(chatId: String) {
        if (messageQueue.state.value.messages.any { it.chatId == chatId && it.delivery == "uncertain" }) return
        queueAction { messageQueue.resume(chatId); queueStorageBlocked = false; mutable.update { it.copy(queueBlocked = false).resolveInfoNotice(NoticeKey(NoticeKind.QueueStorage)) } }
    }
    private suspend fun dispatchQueuedMessages() = inputMutex.withLock {
        val initial = mutable.value
        if (!foreground || queueStorageBlocked || !initial.signedIn || initial.connection != "Connected" || initial.sending) return@withLock
        val candidates = messageQueue.state.value.messages.distinctBy { it.chatId }
            .filter { it.delivery == "queued" && it.chatId !in messageQueue.state.value.pausedChats }
        for (candidate in candidates) {
            var ticket: QueueTicket? = null
            var sendClaimed = false
            var attempted = false
            var acknowledged = false
            try {
                submitQueuedMessage(candidate, rpc::call, allowFailedTurn = candidate.chatId in messageQueue.state.value.resumedChats) {
                    val current = mutable.value
                    if (!foreground || !current.signedIn || current.connection != "Connected" || current.sending ||
                        current.endpoint != initial.endpoint || current.username != initial.username ||
                        !mutable.compareAndSet(current, current.copy(sending = true))) throw QueueWaitingException()
                    sendClaimed = true
                    ticket = messageQueue.claim(candidate) ?: throw QueueWaitingException()
                    attempted = true
                }
                acknowledged = true
                messageQueue.complete(requireNotNull(ticket))
                if (mutable.value.selectedChat?.id == candidate.chatId) loadThread(candidate.chatId)
                break
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (e is QueueWaitingException) continue
                if (initial.endpoint == mutable.value.endpoint && initial.username == mutable.value.username) {
                    if (acknowledged && messageQueue.state.value.messages.none { it.id == candidate.id }) {
                        mutable.update { it.withInfoNotice(NoticeKey(NoticeKind.History, candidate.chatId),
                            "Queued message accepted. History refresh is temporarily unavailable.") }
                    } else {
                        val uncertain = acknowledged || uncertainDelivery(attempted, acknowledged, e)
                        val error = if (uncertain) "Check chat history before retrying: delivery could not be confirmed." else e.message ?: "Queued send failed"
                        ticket?.let { messageQueue.fail(it, error, uncertain) } ?: messageQueue.reject(candidate, error)
                        mutable.update { it.withInfoNotice(NoticeKey(NoticeKind.QueueDelivery, candidate.chatId), "Queued message paused: $error") }
                    }
                }
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message); break }
            } finally { if (sendClaimed) mutable.update { it.copy(sending = false) } }
        }
    }
    fun continueInNewChat() {
        val source = mutable.value
        val original = source.selectedChat ?: return
        if (!source.writerConflict || source.sending || source.uploading || source.uncertainSend ||
            source.connection != "Connected" || original.archived) return
        mutable.update { it.copy(sending = true, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            var copiedId: String? = null
            try {
                val result = rpc.call("thread/fork", recoveryParams(source))
                val copied = parseChat(result.obj("thread"))
                require(copied.id.isNotEmpty() && copied.id != original.id) { "The server did not create a separate chat" }
                copiedId = copied.id
                // Keep the source as a backup. Capture edits made while the fork was pending.
                store.copyChatDraft(original.id, copied.id, source.chatMode, source.accessMode)
                mutable.update { it.copy(chats = listOf(copied) + it.chats.filter { c -> c.id != copied.id }) }
                if (mutable.value.selectedChat?.id == original.id) {
                    open(copied)
                    mutable.update { it.copy(selectedModel = source.selectedModel.ifEmpty { result.text("model") },
                        selectedEffort = source.selectedEffort.ifEmpty { result.text("reasoningEffort") },
                        notice = "History and draft copied. Review your message, then tap Send.") }
                } else mutable.update { it.copy(notice = "Your copied chat is ready in Chats. Its draft is saved.") }
                val title = original.title.take(90) + " (copy)"
                rpc.call("thread/name/set", jsonObject("threadId" to copied.id, "name" to title))
                mutable.update { it.copy(chats = it.chats.map { c -> if (c.id == copied.id) c.copy(title = title) else c },
                    selectedChat = it.selectedChat?.let { c -> if (c.id == copied.id) c.copy(title = title) else c }) }
                val projectId = source.assignments[original.id] ?: original.projectId
                if (projectId != null && copied.projectId != projectId) {
                    rpc.call("thread/metadata/update", jsonObject("threadId" to copied.id, "projectId" to projectId))
                    mutable.update { it.copy(assignments = it.assignments + (copied.id to projectId)) }
                }
                refreshAll()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                else mutable.update { it.copy(notice = if (copiedId != null)
                    "Chat copied and draft saved. Some details could not refresh: ${e.message}"
                    else if (e !is RpcException)
                        "Could not confirm the copy. Check Chats before trying again. Your original draft is saved."
                    else "Could not copy this chat: ${e.message}. Your draft is saved.") }
            } finally { mutable.update { it.copy(sending = false) } }
        }
    }
    fun removeAttachment(id: String) {
        if (mutable.value.sending) return
        synchronized(uploadJobs) {
            uploadJobs.remove(id)?.cancel()
            mutable.update { it.copy(attachments = it.attachments.filter { a -> a.id != id }, uploading = it.attachments.any { a -> a.id != id && a.uploading }) }
            store.attachments(mutable.value.selectedChat?.id ?: "new", mutable.value.attachments.filter { !it.uploading })
        }
    }
    fun attach(uris: List<Uri>) {
        val initial = mutable.value; val chatId = initial.selectedChat?.id ?: return
        if (!canAcceptPickedAttachments(initial)) return
        if (initial.attachments.size + uris.size > 8) { mutable.update { it.copy(notice = "Attach up to 8 files per message") }; return }
        for (uri in uris) {
            val id = java.util.UUID.randomUUID().toString()
            val pending = Attachment(id, "Preparing attachment…", 0, "", buildJsonObject {}, uploading = true)
            mutable.update { it.copy(attachments = it.attachments + pending, uploading = true, notice = null) }
            val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    var name = "attachment"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    var mime = resolver.getType(uri) ?: "application/octet-stream"
                    mutable.update { if (it.selectedChat?.id != chatId) it else it.copy(attachments = it.attachments.map { a -> if (a.id == id) a.copy(name = name, mimeType = mime) else a }) }
                    var bytes = resolver.openInputStream(uri)?.use { readAttachmentBytes(it) } ?: error("Cannot read the selected file")
                    var previewPath = ""
                    if (mime.startsWith("image/")) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be opened" }
                        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Cannot decode the selected image")
                        try {
                            bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray() }
                            val dir = java.io.File(getApplication<Application>().cacheDir, "attachment-previews").apply { mkdirs() }
                            val file = java.io.File(dir, "$id.jpg")
                            file.outputStream().use { it.write(bytes) }
                            previewPath = file.absolutePath
                        } finally { bitmap.recycle() }
                        mime = "image/jpeg"; name = name.substringBeforeLast('.', name) + ".jpg"
                    }
                    ensureActive()
                    mutable.update { if (it.selectedChat?.id != chatId) it else it.copy(attachments = it.attachments.map { a -> if (a.id == id) a.copy(name = name, size = bytes.size.toLong(), mimeType = mime, previewPath = previewPath, uploadProgress = 0) else a }) }
                    val attachment = rpc.upload(name, mime, bytes) { percent ->
                        mutable.update { if (it.selectedChat?.id != chatId) it else it.copy(attachments = it.attachments.map { a -> if (a.id == id) a.copy(uploadProgress = percent) else a }) }
                    }.copy(previewPath = previewPath, uploadProgress = 100)
                    ensureActive()
                    synchronized(uploadJobs) {
                        // Cancel/remove wins even if the server accepted the bytes just before cancellation.
                        if (uploadJobs.containsKey(id)) {
                            store.attachments(chatId, store.attachments(chatId) + attachment)
                            mutable.update { if (it.selectedChat?.id != chatId) it else it.copy(attachments = it.attachments.map { a -> if (a.id == id) attachment else a }) }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                    else synchronized(uploadJobs) {
                        if (uploadJobs.containsKey(id)) {
                            val message = e.message ?: "Attachment upload failed"
                            mutable.update { if (it.selectedChat?.id != chatId) it else it.copy(notice = message,
                                attachments = it.attachments.map { a -> if (a.id == id) a.copy(uploading = false, uploadError = message) else a }) }
                            if (mutable.value.selectedChat?.id == chatId)
                                store.attachments(chatId, mutable.value.attachments.filter { !it.uploading })
                        }
                    }
                } finally {
                    uploadJobs.remove(id)
                    mutable.update { it.copy(uploading = it.attachments.any { a -> a.uploading }) }
                }
            }
            uploadJobs[id] = job; job.start()
        }
    }
    fun interrupt() {
        val initial = mutable.value
        val id = initial.selectedChat?.id ?: return
        pauseQueue(id)
        action {
        val turnId = initial.activeTurnId.ifEmpty {
            rpc.call("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", true) }).obj("thread").array("turns")
                .mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }?.text("id")
                ?: throw IllegalStateException("Refresh to load the active turn before stopping it")
        }
        rpc.call("turn/interrupt", jsonObject("threadId" to id, "turnId" to turnId))
        }
    }
    fun answer(request: PendingInput, result: JsonObject) {
        val initial = mutable.value
        if (initial.connection != "Connected" || initial.pending.none { it.id == request.id } || request.id in initial.answeringInputs) return
        if (!mutable.compareAndSet(initial, initial.copy(answeringInputs = initial.answeringInputs + request.id,
                inputErrors = initial.inputErrors - request.id))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rpc.call("bridge/respond", buildJsonObject { put("requestId", request.id); put("result", result) })
                mutable.update { it.copy(pending = it.pending.filter { r -> r.id != request.id }) }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                else mutable.update { it.copy(inputErrors = it.inputErrors + (request.id to (e.message ?: "Answer could not be sent. Try again when connected.")),
                    notice = if (request.method == "item/tool/requestUserInput") it.notice else e.message ?: "Answer could not be sent.") }
            } finally { mutable.update { it.copy(answeringInputs = it.answeringInputs - request.id) } }
        }
    }
    fun answerAsyncQuestion(item: TimelineItem, answers: Map<String, String>) {
        val initial = mutable.value
        val chat = initial.selectedChat ?: return
        if (initial.connection != "Connected" || initial.sending || initial.uncertainSend || chat.archived ||
            item.id in initial.answeredInputs || initial.timeline.none { it.id == item.id && it.questions == item.questions } ||
            !validQuestionAnswers(item.questions, answers)) return
        val reply = questionReply(item.questions, answers)
        if (!mutable.compareAndSet(initial, initial.copy(sending = true, answeringInputs = initial.answeringInputs + item.id,
                inputErrors = initial.inputErrors - item.id))) return
        viewModelScope.launch(Dispatchers.IO) {
            inputMutex.withLock {
                var attempted = false
                var acknowledged = false
                try {
                    val current = mutable.value
                    check(current.signedIn && current.endpoint == initial.endpoint && current.username == initial.username) { "Reconnect before answering." }
                    submitAsyncAnswer(initial, item, reply, rpc::call) { attempted = true }
                    acknowledged = true
                    mutable.update { it.copy(answeredInputs = it.answeredInputs + item.id, inputErrors = it.inputErrors - item.id) }
                    if (mutable.value.selectedChat?.id == chat.id) loadThread(chat.id)
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                    else {
                        val uncertain = uncertainDelivery(attempted, acknowledged, e)
                        if (uncertain) uncertainChats.add(chat.id)
                        mutable.update { it.copy(uncertainSend = if (it.selectedChat?.id == chat.id) it.uncertainSend || uncertain else it.uncertainSend,
                            inputErrors = if (acknowledged) it.inputErrors - item.id else it.inputErrors + (item.id to
                                if (uncertain) "Delivery could not be confirmed. Refresh and check chat history before trying again." else e.message ?: "Answer could not be sent."))
                            .let { next -> if (acknowledged) next.withInfoNotice(NoticeKey(NoticeKind.History, chat.id),
                                "Answer accepted. History refresh is temporarily unavailable.") else next } }
                    }
                } finally { mutable.update { it.copy(sending = false, answeringInputs = it.answeringInputs - item.id) } }
            }
        }
    }
    fun archive(chat: Chat) = action {
        val current = mutable.value
        val live = current.chats.find { it.id == chat.id } ?: current.selectedChat?.takeIf { it.id == chat.id } ?: chat
        check(current.connection == "Connected") { "Connect to your computer to archive or restore a chat." }
        check(live.status != "active" && current.pending.none { it.params.text("threadId") == chat.id }) { "Finish the running turn and pending requests before archiving this chat." }
        rpc.call(if (live.archived) "thread/unarchive" else "thread/archive", jsonObject("threadId" to chat.id))
        mutable.update { it.copy(favoriteChats = it.favoriteChats.map { c -> if (c.id == chat.id) c.copy(archived = !live.archived) else c }) }
        saveFavorites(); refreshAll()
    }
    fun deleteChat(chat: Chat) {
        if (!canDeleteChat(mutable.value, chat)) return
        action {
            val current = mutable.value
            val live = current.chats.find { it.id == chat.id } ?: current.selectedChat?.takeIf { it.id == chat.id } ?: chat
            check(live.archived && current.connection == "Connected" && !current.sending &&
                current.queuedMessages.none { it.chatId == chat.id } && current.pending.none { it.params.text("threadId") == chat.id }) {
                "Finish work and remove queued messages before deleting this archived chat."
            }
            val metadata = rpc.call("thread/read", buildJsonObject { put("threadId", chat.id); put("includeTurns", false) }).obj("thread")
            check(metadata.obj("status").text("type") != "active") { "Finish the running task before deleting this chat." }
            try { rpc.call("thread/delete", jsonObject("threadId" to chat.id)) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is RpcException) throw IllegalStateException("Chat was not deleted: ${e.message}. Your host must support chat deletion.")
                throw IllegalStateException("Deletion could not be confirmed. Refresh archived history before trying again.")
            }
            forgetDeletedChat(chat.id)
            try { refreshAll() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SessionExpiredException) throw e
                mutable.update { it.copy(notice = "Chat deleted. Refresh history to continue loading chats.") }
            }
        }
    }
    private fun forgetDeletedChat(id: String) {
        if (id.isBlank() || !deletedChats.add(id)) return
        mutable.update { withoutDeletedChat(it, id) }
        uncertainChats.remove(id); store.forgetChat(id)
        cachedThreads = JsonObject(cachedThreads.filterKeys { it != id })
        cachedList = JsonArray(cachedList.filterNot { it.jsonObject.text("id") == id })
        saveFavorites(); saveCache()
    }
    private fun applyEvents(events: List<JsonObject>) {
        events.filter { it.text("method") == "thread/deleted" && (it.text("_bridgeEpoch").isEmpty() || it.text("_bridgeEpoch") == lastEpoch) }.forEach { forgetDeletedChat(it.obj("params").text("threadId")) }
        var completedChat: String? = null
        mutable.update { before ->
            var state = before
            for (event in events) {
                if (lastEpoch.isNotEmpty() && event.text("_bridgeEpoch").isNotEmpty() && event.text("_bridgeEpoch") != lastEpoch) continue
                val method = event.text("method"); val p = event.obj("params"); val threadId = p.text("threadId").ifEmpty { p.obj("thread").text("id") }
                if (threadId in deletedChats) continue
                val sequence = (event["_bridgeSequence"] as? JsonPrimitive)?.longOrNull ?: 0
                state = state.copy(favoriteChats = state.favoriteChats.map { chat -> if (chat.id != threadId) chat else when (method) {
                    "thread/name/updated" -> chat.copy(title = p.text("threadName").ifEmpty { p.text("name").ifEmpty { chat.title } })
                    "thread/status/changed" -> chat.copy(status = p.obj("status").text("type", "idle"))
                    "turn/started", "turn/completed" -> chat.copy(status = if (method == "turn/started") "active" else "idle")
                    "thread/project/updated" -> chat.copy(projectId = p.text("projectId").ifEmpty { null })
                    else -> chat
                } })
                when (method) {
                    "bridge/requestPending" -> if (sequence > state.pendingSequence) state = state.copy(pending = (state.pending + PendingInput(p.text("requestId"), p.text("method"), p.obj("params"))).distinctBy { it.id }, pendingSequence = sequence)
                    "bridge/requestResolved" -> if (sequence > state.pendingSequence) state = state.copy(pending = state.pending.filter { it.id != p.text("requestId") }, pendingSequence = sequence)
                    "thread/started" -> { val chat = parseChat(p.obj("thread")); state = state.copy(chats = listOf(chat) + state.chats.filter { it.id != chat.id }) }
                    "thread/status/changed" -> {
                        val status = p.obj("status").text("type", "idle")
                        val selected = state.selectedChat?.id == threadId && sequence > state.statusSequence
                        state = state.copy(chats = state.chats.map { if (it.id == threadId) it.copy(status = status) else it },
                            selectedChat = if (selected) state.selectedChat?.copy(status = status) else state.selectedChat,
                            statusSequence = if (selected) sequence else state.statusSequence)
                    }
                    "thread/name/updated" -> {
                        val title = p.text("threadName").ifEmpty { p.text("name") }
                        val selected = state.selectedChat?.id == threadId && sequence > state.titleSequence
                        if (title.isNotEmpty()) state = state.copy(chats = state.chats.map { if (it.id == threadId) it.copy(title = title) else it },
                            selectedChat = if (selected) state.selectedChat?.copy(title = title) else state.selectedChat,
                            titleSequence = if (selected) sequence else state.titleSequence)
                    }
                    "thread/project/updated" -> state = state.copy(assignments = if (p.text("projectId").isEmpty()) state.assignments - threadId else state.assignments + (threadId to p.text("projectId")))
                    "thread/archived", "thread/unarchived" -> state = state.copy(chats = state.chats.map { if (it.id == threadId) it.copy(archived = method == "thread/archived") else it },
                        favoriteChats = state.favoriteChats.map { if (it.id == threadId) it.copy(archived = method == "thread/archived") else it },
                        selectedChat = state.selectedChat?.let { if (it.id == threadId) it.copy(archived = method == "thread/archived") else it })
                    "turn/started", "turn/completed" -> {
                        state = state.copy(chats = state.chats.map { if (it.id == threadId) it.copy(status = if (method == "turn/started") "active" else "idle") else it })
                        if (method == "turn/completed") completedChat = threadId
                        if (state.selectedChat?.id == threadId && sequence > state.statusSequence)
                            state = state.copy(selectedChat = state.selectedChat?.copy(status = if (method == "turn/started") "active" else "idle"), statusSequence = sequence)
                    }
                }
                if (state.selectedChat?.id == threadId && sequence > state.snapshotSequence) {
                    val itemId = p.text("itemId").ifEmpty { p.obj("item").text("id") }
                    state = state.copy(timeline = reduceTimeline(state.timeline, method, p, state.activeTurnId), threadSequence = sequence,
                        itemSequences = if (itemId.isNotEmpty()) state.itemSequences + (itemId to sequence) else state.itemSequences)
                    if (method == "turn/started") state = state.copy(lastTurnStatus = "inProgress")
                    if (method == "turn/started") state = state.copy(activeTurnId = p.obj("turn").text("id"))
                    if (method == "turn/completed") state = state.copy(activeTurnId = "")
                    if (method == "turn/completed") state = state.copy(lastTurnStatus = p.obj("turn").text("status"),
                        notice = p.obj("turn").obj("error").text("message").ifEmpty { state.notice })
                    if (method == "error") state = state.copy(notice = p.obj("error").text("message", "The host reported an error"))
                }
            }
            state
        }
        completedChat?.takeIf { it == mutable.value.selectedChat?.id }?.let { id -> action { loadThread(id) } }
        events.filter { it.text("method") == "turn/completed" && it.obj("params").obj("turn").text("status") in listOf("failed", "interrupted") }
            .forEach { event -> pauseQueue(event.obj("params").text("threadId")) }
        if (events.any { it.text("method") == "thread/deleted" && it.text("_bridgeEpoch") == lastEpoch } && canRefreshHistory(mutable.value)) refresh()
        if (events.any { it.text("method").startsWith("thread/") }) refreshFavoriteSummaries()
        if (events.any { it.text("method") == "project/changed" }) action { delay(100); val inventory = rpc.get("/v1/projects"); cachedInventory = inventory; applyInventory(inventory) }
    }
    private fun refreshFavoriteSummaries() {
        mutable.update { state ->
            val current = state.chats.associateBy { it.id }
            state.copy(favoriteChats = state.favoriteChats.map { current[it.id] ?: it })
        }
        saveFavorites()
    }
    private fun saveCache() {
        cachedList = JsonArray(cachedList.filterNot { it.jsonObject.text("id") in deletedChats })
        cachedThreads = JsonObject(cachedThreads.filterKeys { it !in deletedChats })
        refreshFavoriteSummaries(); store.saveCache(buildJsonObject { put("threads", cachedThreads); put("chats", cachedList); put("inventory", cachedInventory) }) }
    private fun action(recoveryKey: NoticeKey? = null, block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        mutable.update { it.copy(loading = true) }
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e
            if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
            else mutable.update { if (recoveryKey == null) it.copy(notice = e.message ?: "Action failed") else it.withInfoNotice(recoveryKey, e.message ?: "Action failed") } }
        finally { mutable.update { it.copy(loading = false) } }
    }
    override fun onCleared() { rpc.close(); super.onCleared() }
}
