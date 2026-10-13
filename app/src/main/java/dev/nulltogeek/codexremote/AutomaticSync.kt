package dev.nulltogeek.codexremote

import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** One request at a time, with at most one follow-up for changes arriving during IO. */
internal class AutomaticSync(scope: CoroutineScope, private val sync: suspend () -> Unit) {
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    init {
        scope.launch {
            for (immediate in requests) {
                if (!immediate) delay(200)
                while (requests.tryReceive().isSuccess) { }
                sync()
            }
        }
    }
    fun request(immediate: Boolean = true) { requests.trySend(immediate) }
}

internal fun needsLibrarySync(state: ScreenState): Boolean = state.signedIn && state.connection == "Connected" &&
    state.selectedChat == null && state.page in listOf("chats", "archived", "projects")

internal fun libraryChanged(method: String): Boolean = method in setOf(
    "thread/started", "thread/status/changed", "thread/name/updated", "thread/project/updated",
    "thread/archived", "thread/unarchived", "thread/deleted", "turn/started", "turn/completed", "project/changed")

internal data class LibrarySnapshot(val inventory: JsonObject, val active: JsonObject,
    val archived: JsonObject, val live: JsonObject)

/** Navigation fetches only project metadata and chat summaries, never command/history bodies. */
internal suspend fun readLibrarySnapshot(query: String, sourceKinds: List<String>,
    get: suspend (String) -> JsonObject, call: suspend (String, JsonObject) -> JsonObject): LibrarySnapshot = coroutineScope {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val inventory = async { get("/v1/projects") }
    val active = async { get("/v1/history?archived=false&searchTerm=$encoded") }
    val archived = async { get("/v1/history?archived=true&searchTerm=$encoded") }
    val live = async { call("thread/list", buildJsonObject {
        put("limit", 50); put("sortKey", "updated_at"); put("useStateDbOnly", true)
        put("modelProviders", buildJsonArray {}); put("sourceKinds", JsonArray(sourceKinds.map(::JsonPrimitive)))
    }) }
    LibrarySnapshot(inventory.await(), active.await(), archived.await(), live.await())
}

/** Rejects history reads superseded by a newer read, account or backend. */
internal data class SyncRead(val id: String, val version: Long, val session: String, val epoch: String)
internal class SyncReads {
    private val versions = mutableMapOf<String, Long>()
    @Synchronized fun start(id: String, session: String, epoch: String): SyncRead {
        val version = (versions[id] ?: 0) + 1
        versions[id] = version
        return SyncRead(id, version, session, epoch)
    }
    @Synchronized fun isCurrent(read: SyncRead, session: String, epoch: String): Boolean =
        versions[read.id] == read.version && read.session == session && read.epoch == epoch
}
