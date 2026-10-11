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
import kotlinx.serialization.json.*

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val rpc = RpcClient()
    private val mutable = MutableStateFlow(ScreenState(endpoint = store.endpoint, username = store.username, theme = store.theme, accent = store.accent, motion = store.motion))
    val state: StateFlow<ScreenState> = mutable.asStateFlow()
    private var reconnect: Job? = null
    private var searchJob: Job? = null
    private var updateJob: Job? = null
    private var lastUpdateCheck = 0L
    @Volatile private var foreground = false
    @Volatile private var lastEpoch = ""
    private var cachedThreads = buildJsonObject {}
    private var cachedList = JsonArray(emptyList())
    private var cachedInventory = buildJsonObject {}
    private val uncertainChats = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val sourceKinds = listOf("cli", "vscode", "exec", "appServer", "subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther", "unknown")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cache = store.cache(); cachedThreads = cache.obj("threads"); cachedList = cache.array("chats"); cachedInventory = cache.obj("inventory")
            val token = store.session()
            mutable.update { it.copy(signedIn = token.isNotEmpty(), chats = cachedList.mapNotNull { r -> (r as? JsonObject)?.let { o -> parseChat(o, o.text("cachedArchived") == "true") } }) }
            applyInventory(cachedInventory)
            connectIfForeground()
        }
        viewModelScope.launch {
            rpc.status.collect { status -> mutable.update { it.copy(connection = status) } }
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
                    mutable.update { it.copy(connection = "Offline", notice = e.message ?: "Cannot connect to host") }
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
        mutable.update { it.copy(endpoint = cleaned, username = session.username, signedIn = true, host = host.text("name"), notice = null) }
        connectIfForeground()
    }
    private fun clearSession(notice: String? = null) {
        updateJob?.cancel()
        rpc.close(); store.signOut()
        mutable.update { ScreenState(endpoint = store.endpoint, username = store.username, theme = it.theme, accent = it.accent, motion = it.motion, notice = notice) }
    }
    fun signOut() = action {
        disconnect()
        var notice: String? = null
        try { rpc.configure(store.endpoint, store.session()); rpc.logout() }
        catch (e: Exception) { if (e is CancellationException) throw e; notice = "Signed out on this phone. Server session could not be revoked while offline." }
        finally { clearSession(notice) }
    }
    fun theme(value: String) { store.theme(value); mutable.update { it.copy(theme = value) } }
    fun accent(value: String) { store.accent(value); mutable.update { it.copy(accent = value) } }
    fun motion(value: Boolean) { store.motion(value); mutable.update { it.copy(motion = value) } }
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
    fun filterProject(id: String?) { mutable.update { it.copy(page = "chats", projectFilter = id, selectedChat = null) } }
    fun search(value: String) {
        mutable.update { it.copy(searchTerm = value) }; searchJob?.cancel()
        if (rpc.status.value != "Connected") return
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            try {
                val active = listPage(false, null, value); val archived = listPage(true, null, value)
                if (mutable.value.searchTerm != value) return@launch
                mutable.update { it.copy(chats = active.array("data").map { r -> parseChat(r.jsonObject) } + archived.array("data").map { r -> parseChat(r.jsonObject, true) },
                    activeCursor = active.text("nextCursor").ifEmpty { null }, archivedCursor = archived.text("nextCursor").ifEmpty { null }) }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(notice = "Search unavailable: ${e.message}") } }
        }
    }
    fun draft(text: String) {
        mutable.update { it.copy(draft = text) }
        store.draft(mutable.value.selectedChat?.id ?: "new", text)
    }
    fun model(id: String) { mutable.update { it.copy(selectedModel = id, selectedEffort = it.models.find { m -> m.id == id }?.defaultEffort.orEmpty()) } }
    fun effort(value: String) { mutable.update { it.copy(selectedEffort = value) } }
    fun noticeDismiss() { mutable.update { it.copy(notice = null) } }
    fun showDiff(change: Change?) { mutable.update { it.copy(diff = change) } }
    fun refresh() = action { refreshAll(); mutable.update { if (it.historyAvailable) { it.selectedChat?.id?.let(uncertainChats::remove); it.copy(uncertainSend = false) } else it } }

    private suspend fun refreshAll() {
        val host = rpc.get("/v1/host"); mutable.update { it.copy(host = host.text("name", "Your computer")) }
        val inventory = rpc.get("/v1/projects"); cachedInventory = inventory; applyInventory(inventory)
        val active = listPage(false, null); val archived = listPage(true, null)
        cachedList = JsonArray(active.array("data") + archived.array("data").map { r -> buildJsonObject { (r as? JsonObject)?.forEach { (k, v) -> put(k, v) }; put("cachedArchived", true) } })
        mutable.update { it.copy(chats = cachedList.map { r -> parseChat(r.jsonObject, r.jsonObject.text("cachedArchived") == "true") }.sortedWith(compareByDescending<Chat> { c -> c.pinned }.thenByDescending { c -> c.updatedAt }),
            activeCursor = active.text("nextCursor").ifEmpty { null }, archivedCursor = archived.text("nextCursor").ifEmpty { null }) }
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
    private suspend fun listPage(archived: Boolean, cursor: String?, query: String = mutable.value.searchTerm): JsonObject = rpc.get("/v1/history?archived=$archived" + (cursor?.let { "&cursor=$it" } ?: "") + "&searchTerm=" + java.net.URLEncoder.encode(query, "UTF-8"))
    fun loadMore(archived: Boolean) = action {
        val cursor = if (archived) mutable.value.archivedCursor else mutable.value.activeCursor
        if (cursor != null) {
            val page = listPage(archived, cursor)
            val raw = page.array("data").map { r -> buildJsonObject { r.jsonObject.forEach { (k, v) -> put(k, v) }; put("cachedArchived", archived) } }
            cachedList = JsonArray((cachedList + raw).distinctBy { it.jsonObject.text("id") })
            mutable.update { it.copy(chats = (it.chats + raw.map { r -> parseChat(r, archived) }).distinctBy { c -> c.id },
                activeCursor = if (!archived) page.text("nextCursor").ifEmpty { null } else it.activeCursor,
                archivedCursor = if (archived) page.text("nextCursor").ifEmpty { null } else it.archivedCursor) }
            saveCache()
        }
    }
    fun open(chat: Chat) {
        mutable.update { it.copy(selectedChat = chat, diff = null, timeline = cachedThreads[chat.id]?.let { r -> parseTimeline(r.jsonObject) } ?: emptyList(), draft = store.draft(chat.id), attachments = store.attachments(chat.id), chatMode = store.chatMode(chat.id), accessMode = store.accessMode(chat.id), selectedModel = "", selectedEffort = "", uncertainSend = chat.id in uncertainChats, historyAvailable = true, activeTurnId = "", snapshotSequence = 0, itemSequences = emptyMap(), threadSequence = 0, olderCursor = null, lastTurnStatus = "", statusSequence = 0, titleSequence = 0) }
        if (rpc.status.value == "Connected") action { loadThread(chat.id) }
    }
    private suspend fun loadThread(id: String) {
        val loaded = readThreadHistory(id, rpc::call)
        val raw = loaded.thread
        if (loaded.authoritative || !cachedThreads.containsKey(id)) cachedThreads = buildJsonObject {
            cachedThreads.entries.toList().takeLast(7).forEach { (k, v) -> put(k, v) }; put(id, raw)
        }
        val cutoff = loaded.sequence
        mutable.update { current ->
            if (current.selectedChat?.id != id) current else {
                val newer = current.timeline.filter { (current.itemSequences[it.id] ?: 0) > cutoff }.associateBy { it.id }
                val history = parseTimeline(raw)
                val merged = if (!loaded.authoritative) current.timeline else history.map { newer[it.id] ?: it } + newer.values.filter { n -> history.none { it.id == n.id } }
                val summary = parseChat(raw, current.selectedChat.archived)
                reconcileChatMetadata(current, summary, loaded.metadataSequence).copy(timeline = merged, olderCursor = loaded.olderCursor, historyAvailable = loaded.authoritative,
                    selectedModel = current.selectedModel.ifEmpty { raw.text("model") }, selectedEffort = current.selectedEffort.ifEmpty { raw.text("reasoningEffort") },
                    lastTurnStatus = if (!loaded.authoritative || current.threadSequence > cutoff) current.lastTurnStatus else (raw.array("turns").lastOrNull() as? JsonObject)?.text("status").orEmpty(),
                    activeTurnId = if (!loaded.authoritative || current.threadSequence > cutoff) current.activeTurnId else raw.array("turns").mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }?.text("id").orEmpty(),
                    snapshotSequence = if (loaded.authoritative) cutoff else current.snapshotSequence, itemSequences = if (loaded.authoritative) current.itemSequences.filterValues { it > cutoff } else current.itemSequences)
            }
        }
        saveCache()
    }
    fun loadOlder() = action {
        val current = mutable.value; val id = current.selectedChat?.id ?: return@action; val cursor = current.olderCursor ?: return@action
        val page = rpc.call("thread/turns/list", buildJsonObject { put("threadId", id); put("cursor", cursor); put("limit", 20); put("itemsView", "full"); put("sortDirection", "desc") })
        val turns = JsonArray(page.array("data").reversed()); val older = parseTimeline(buildJsonObject { put("turns", turns) })
        mutable.update { if (it.selectedChat?.id != id) it else it.copy(timeline = (older + it.timeline).distinctBy { item -> item.id }, olderCursor = page.text("nextCursor").ifEmpty { null }) }
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
    fun send() {
        val state = mutable.value; val chat = state.selectedChat ?: return
        if ((state.draft.isBlank() && state.attachments.isEmpty()) || state.uploading || state.sending || state.uncertainSend || state.connection != "Connected" || chat.archived) return
        val text = state.draft
        mutable.update { it.copy(sending = true, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            var mutationAttempted = false
            var acknowledged = false
            try {
                val input = buildJsonArray { if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) }); state.attachments.forEach { add(it.input) } }
                if (chat.status == "active") {
                    val turnId = state.activeTurnId.ifEmpty {
                        rpc.call("thread/read", buildJsonObject { put("threadId", chat.id); put("includeTurns", true) }).obj("thread").array("turns")
                            .mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }?.text("id")
                            ?: throw IllegalStateException("Refresh to load the active turn before sending")
                    }
                    mutationAttempted = true
                    rpc.call("turn/steer", buildJsonObject { put("threadId", chat.id); put("expectedTurnId", turnId); put("input", input) })
                } else {
                    val loaded = rpc.call("thread/loaded/list").array("data").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    if (chat.id !in loaded) rpc.call("thread/resume", buildJsonObject { put("threadId", chat.id); put("excludeTurns", true); mobileParams(state).forEach { (k, v) -> put(k, v) } })
                    mutationAttempted = true
                    rpc.call("turn/start", buildJsonObject { put("threadId", chat.id); put("input", input)
                        mobileParams(state).forEach { (k, v) -> put(k, v) }
                        if (state.selectedModel.isNotEmpty()) put("model", state.selectedModel)
                        if (state.selectedEffort.isNotEmpty()) put("effort", state.selectedEffort) })
                }
                acknowledged = true
                uncertainChats.remove(chat.id)
                val cleared = store.clearSentDraft(chat.id, text)
                val sentIds = state.attachments.map { it.id }.toSet()
                store.attachments(chat.id, store.attachments(chat.id).filter { it.id !in sentIds })
                mutable.update { if (it.selectedChat?.id == chat.id) it.copy(draft = if (cleared && it.draft == text) "" else it.draft, attachments = it.attachments.filter { a -> a.id !in sentIds }, sending = false) else it.copy(sending = false) }
                loadThread(chat.id)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val uncertain = uncertainDelivery(mutationAttempted, acknowledged, e)
                if (uncertain) uncertainChats.add(chat.id)
                mutable.update { if (it.selectedChat?.id != chat.id) it.copy(sending = false) else it.copy(sending = false, uncertainSend = uncertain,
                    notice = if (acknowledged) "Message accepted. History refresh is temporarily unavailable."
                    else (e.message ?: "Send failed") + if (uncertain) ". Refresh and check history before sending again." else "") }
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
            }
        }
    }
    fun removeAttachment(id: String) {
        if (mutable.value.sending) return
        mutable.update { it.copy(attachments = it.attachments.filter { a -> a.id != id }) }
        store.attachments(mutable.value.selectedChat?.id ?: "new", mutable.value.attachments)
    }
    fun attach(uris: List<Uri>) {
        val initial = mutable.value; val chatId = initial.selectedChat?.id ?: return
        if (initial.uploading || initial.sending || initial.connection != "Connected") return
        if (initial.attachments.size + uris.size > 8) { mutable.update { it.copy(notice = "Attach up to 8 files per message") }; return }
        mutable.update { it.copy(uploading = true, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                for (uri in uris) {
                    var name = "attachment"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    var mime = resolver.getType(uri) ?: "application/octet-stream"
                    var bytes = resolver.openInputStream(uri)?.use { readAttachmentBytes(it) } ?: error("Cannot read the selected file")
                    require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "Select a non-empty file up to 20 MB" }
                    if (mime.startsWith("image/")) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be opened" }
                        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Cannot decode the selected image")
                        try { bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray() } }
                        finally { bitmap.recycle() }
                        mime = "image/jpeg"; name = name.substringBeforeLast('.', name) + ".jpg"
                    }
                    val attachment = rpc.upload(name, mime, bytes)
                    val saved = store.attachments(chatId) + attachment; store.attachments(chatId, saved)
                    mutable.update { if (it.selectedChat?.id == chatId) it.copy(attachments = saved) else it }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
                else mutable.update { it.copy(notice = e.message ?: "Attachment upload failed") }
            } finally { mutable.update { it.copy(uploading = false) } }
        }
    }
    fun interrupt() = action {
        val id = mutable.value.selectedChat?.id ?: return@action
        val turnId = mutable.value.activeTurnId.ifEmpty {
            rpc.call("thread/read", buildJsonObject { put("threadId", id); put("includeTurns", true) }).obj("thread").array("turns")
                .mapNotNull { it as? JsonObject }.lastOrNull { it.text("status") == "inProgress" }?.text("id")
                ?: throw IllegalStateException("Refresh to load the active turn before stopping it")
        }
        rpc.call("turn/interrupt", jsonObject("threadId" to id, "turnId" to turnId))
    }
    fun answer(request: PendingInput, result: JsonObject) = action {
        rpc.call("bridge/respond", buildJsonObject { put("requestId", request.id); put("result", result) })
        mutable.update { it.copy(pending = it.pending.filter { r -> r.id != request.id }) }
    }
    fun archive(chat: Chat) = action {
        rpc.call(if (chat.archived) "thread/unarchive" else "thread/archive", jsonObject("threadId" to chat.id)); refreshAll()
    }
    private fun applyEvents(events: List<JsonObject>) {
        var completedChat: String? = null
        mutable.update { before ->
            var state = before
            for (event in events) {
                if (lastEpoch.isNotEmpty() && event.text("_bridgeEpoch").isNotEmpty() && event.text("_bridgeEpoch") != lastEpoch) continue
                val method = event.text("method"); val p = event.obj("params"); val threadId = p.text("threadId")
                val sequence = (event["_bridgeSequence"] as? JsonPrimitive)?.longOrNull ?: 0
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
                    "thread/archived", "thread/unarchived" -> state = state.copy(chats = state.chats.map { if (it.id == threadId) it.copy(archived = method == "thread/archived") else it }, selectedChat = state.selectedChat?.let { if (it.id == threadId) it.copy(archived = method == "thread/archived") else it })
                    "turn/started", "turn/completed" -> {
                        if (method == "turn/completed") completedChat = threadId
                        if (state.selectedChat?.id == threadId && sequence > state.statusSequence)
                            state = state.copy(selectedChat = state.selectedChat?.copy(status = if (method == "turn/started") "active" else "idle"), statusSequence = sequence)
                    }
                }
                if (state.selectedChat?.id == threadId && sequence > state.snapshotSequence) {
                    val itemId = p.text("itemId").ifEmpty { p.obj("item").text("id") }
                    state = state.copy(timeline = reduceTimeline(state.timeline, method, p), threadSequence = sequence,
                        itemSequences = if (itemId.isNotEmpty()) state.itemSequences + (itemId to sequence) else state.itemSequences)
                    if (method == "turn/started") state = state.copy(lastTurnStatus = "inProgress")
                    if (method == "turn/started") state = state.copy(activeTurnId = p.obj("turn").text("id"))
                    if (method == "turn/completed") state = state.copy(activeTurnId = "")
                    if (method == "turn/completed") state = state.copy(lastTurnStatus = p.obj("turn").text("status"),
                        notice = p.obj("turn").obj("error").text("message").ifEmpty { state.notice })
                    if (method == "error") state = state.copy(notice = p.obj("error").text("message", "The host reported an error"))
                    if (method == "turn/diff/updated") {
                        val diff = p.text("diff")
                        if (diff.isNotEmpty()) state = state.copy(timeline = state.timeline.filter { it.id != "turn-diff" } + TimelineItem("turn-diff", "fileChange", "Turn diff", changes = listOf(Change("All changes", diff))))
                    }
                }
            }
            state
        }
        completedChat?.takeIf { it == mutable.value.selectedChat?.id }?.let { id -> action { loadThread(id) } }
        if (events.any { it.text("method") == "project/changed" }) action { delay(100); val inventory = rpc.get("/v1/projects"); cachedInventory = inventory; applyInventory(inventory) }
    }
    private fun saveCache() { store.saveCache(buildJsonObject { put("threads", cachedThreads); put("chats", cachedList); put("inventory", cachedInventory) }) }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        mutable.update { it.copy(loading = true) }
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e
            if (e is SessionExpiredException) { disconnect(); clearSession(e.message) }
            else mutable.update { it.copy(notice = e.message ?: "Action failed") } }
        finally { mutable.update { it.copy(loading = false) } }
    }
    override fun onCleared() { rpc.close(); super.onCleared() }
}
