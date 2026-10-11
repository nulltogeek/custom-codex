package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogPaginationTest {
    private fun page(cursor: String?, vararg rows: Pair<String, Long>) = buildJsonObject {
        put("data", JsonArray(rows.map { (id, updated) -> buildJsonObject {
            put("id", id); put("title", id); put("updatedAt", updated)
        } }))
        put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun state() = ScreenState(connection = "Connected", activeCursor = "active2", archivedCursor = "archive2", catalogRevision = 4)

    @Test fun recentArchiveAndFavoritesChooseTheCorrectCatalogue() {
        assertFalse(nextCatalogPage(state(), 0)!!.archived)
        assertTrue(nextCatalogPage(state(), 1)!!.archived)
        assertNull(nextCatalogPage(state(), 3))
        assertNull(nextCatalogPage(state().copy(activeCursor = null), 0))
    }

    @Test fun agentsContinueThroughArchivedPagesAfterActivePagesFinish() {
        assertFalse(nextCatalogPage(state(), 2)!!.archived)
        assertTrue(nextCatalogPage(state().copy(activeCursor = null), 2)!!.archived)
        assertNull(nextCatalogPage(state().copy(activeCursor = null, archivedCursor = null), 2))
    }

    @Test fun duplicateCallbacksAndSearchChangesCannotReuseAPageRequest() {
        val original = state()
        val request = nextCatalogPage(original, 0)!!
        assertTrue(request.isCurrent(original))
        assertFalse(request.isCurrent(original.copy(activeCursor = "active3")))
        assertFalse(request.isCurrent(original.copy(catalogRevision = 5)))
        assertFalse(request.isCurrent(original.copy(searchTerm = "different")))
        assertFalse(request.isCurrent(original.copy(catalogLoading = true)))
        assertFalse(request.isCurrent(original.copy(refreshing = true)))
        assertFalse(request.isCurrent(original.copy(connection = "Offline")))
    }

    @Test fun failedAutomaticRequestWaitsForAnExplicitRetry() {
        val original = state()
        val request = nextCatalogPage(original, 0)!!
        val failed = original.copy(catalogError = "Retry", catalogErrorArchived = false)
        assertFalse(request.isCurrent(failed))
        assertTrue(nextCatalogPage(failed, 0)!!.isCurrent(failed))
        assertEquals("Retry", catalogPageError(failed, false))
        assertNull(catalogPageError(failed, true))
    }

    @Test fun refreshRequiresALiveSessionAndCannotOverlapAnotherRefreshOrAction() {
        val ready = state().copy(signedIn = true)
        assertTrue(canRefreshHistory(ready))
        assertFalse(canRefreshHistory(ready.copy(signedIn = false)))
        assertFalse(canRefreshHistory(ready.copy(connection = "Offline")))
        assertFalse(canRefreshHistory(ready.copy(connection = "Connecting")))
        assertFalse(canRefreshHistory(ready.copy(loading = true)))
        assertFalse(canRefreshHistory(ready.copy(refreshing = true)))
        // A page fetch may be in progress; refresh invalidates its revision and
        // waits on the catalogue mutex instead of silently ignoring the gesture.
        assertTrue(canRefreshHistory(ready.copy(catalogLoading = true)))
    }

    @Test fun prefetchStartsNearTheBottomAndWaitsForMeasuredRows() {
        assertFalse(nearCatalogEnd(0, -1))
        assertFalse(nearCatalogEnd(4, -1))
        assertFalse(nearCatalogEnd(104, 20))
        assertFalse(nearCatalogEnd(104, 99))
        assertTrue(nearCatalogEnd(104, 100))
        // Empty/short filtered lists must keep searching through hidden pages.
        assertTrue(nearCatalogEnd(5, 4))
    }

    @Test fun refreshedFirstPagesKeepScrolledPagesAndReplaceDuplicateMetadata() {
        val previous = catalogRows(page(null, "new" to 10, "older" to 1), false) +
            catalogRows(page(null, "archivedOlder" to 0), true)
        val merged = refreshCatalogRows(previous, page("active2", "new" to 20), page("archive2", "archivedNew" to 5))
        assertEquals(listOf("new", "archivedNew", "older", "archivedOlder"), merged.map { it.jsonObject.text("id") })
        assertEquals("20", merged.first().jsonObject.text("updatedAt"))
        assertEquals("true", merged.last().jsonObject.text("cachedArchived"))
    }

    @Test fun completeRefreshRemovesStaleRowsAndArchiveMovesNeverDuplicateChats() {
        val previous = catalogRows(page(null, "deleted" to 1, "moved" to 2), false) +
            catalogRows(page(null, "oldArchive" to 0), true)
        val merged = refreshCatalogRows(previous, page(null, "current" to 3), page("archive2", "moved" to 4))
        assertEquals(listOf("current", "moved", "oldArchive"), merged.map { it.jsonObject.text("id") })
        assertEquals("true", merged[1].jsonObject.text("cachedArchived"))
    }

    @Test fun overlappingOffsetPagesUpdateAndSortWithoutDuplicateChatKeys() {
        val old = listOf(Chat("a", "Old title", "", "", updatedAt = 30), Chat("b", "B", "", "", updatedAt = 10))
        val merged = mergeCatalogChats(old, listOf(old[0].copy(title = "New title"), Chat("c", "C", "", "", updatedAt = 20)))
        assertEquals(listOf("a", "c", "b"), merged.map { it.id })
        assertEquals("New title", merged.first().title)
        val raw = mergeCatalogRows(catalogRows(page(null, "a" to 1), false), catalogRows(page(null, "a" to 2, "b" to 0), false))
        assertEquals(2, raw.size)
        assertEquals("2", raw.first().jsonObject.text("updatedAt"))
    }
}
