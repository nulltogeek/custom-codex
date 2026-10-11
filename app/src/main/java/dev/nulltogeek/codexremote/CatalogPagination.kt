package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

internal data class CatalogPageRequest(val archived: Boolean, val cursor: String, val revision: Long, val query: String, val error: String?)

// Agent chats span both catalogues. Favorites are already stored locally.
internal fun nextCatalogPage(state: ScreenState, tab: Int): CatalogPageRequest? {
    val archived = when (tab) {
        3 -> return null
        1 -> true
        2 -> state.activeCursor == null
        else -> false
    }
    return catalogPageRequest(state, archived)
}

internal fun catalogPageRequest(state: ScreenState, archived: Boolean): CatalogPageRequest? =
    (if (archived) state.archivedCursor else state.activeCursor)?.let {
        CatalogPageRequest(archived, it, state.catalogRevision, state.searchTerm, catalogPageError(state, archived))
    }

internal fun CatalogPageRequest.isCurrent(state: ScreenState): Boolean =
    state.connection == "Connected" && !state.catalogLoading && !state.refreshing && this == catalogPageRequest(state, archived)

internal fun catalogPageError(state: ScreenState, archived: Boolean): String? =
    state.catalogError.takeIf { state.catalogErrorArchived == null || state.catalogErrorArchived == archived }

internal fun nearCatalogEnd(totalItems: Int, lastVisibleIndex: Int): Boolean =
    totalItems > 0 && lastVisibleIndex >= 0 && lastVisibleIndex >= totalItems - 4

internal fun catalogRows(page: JsonObject, archived: Boolean): List<JsonObject> = page.array("data").map {
    JsonObject(it.jsonObject + ("cachedArchived" to JsonPrimitive(archived)))
}

internal fun mergeCatalogRows(previous: List<JsonElement>, incoming: List<JsonElement>): JsonArray =
    JsonArray((incoming + previous).distinctBy { it.jsonObject.text("id") })

// A refreshed first page is authoritative for its records, but must retain pages
// the reader already scrolled through. Complete catalogues can discard old rows.
internal fun refreshCatalogRows(previous: List<JsonElement>, active: JsonObject, archived: JsonObject): JsonArray {
    val tail = previous.filter {
        val page = if (it.jsonObject.text("cachedArchived") == "true") archived else active
        page.text("nextCursor").isNotEmpty()
    }
    return mergeCatalogRows(tail, catalogRows(active, false) + catalogRows(archived, true))
}

internal fun mergeCatalogChats(previous: List<Chat>, incoming: List<Chat>): List<Chat> =
    (incoming + previous).distinctBy { it.id }
        .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.id })
