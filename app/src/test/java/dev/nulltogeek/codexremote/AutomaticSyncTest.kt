package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticSyncTest {
    @Test fun navigationStartsImmediatelyAndBurstsProduceOneRead() = runTest {
        var reads = 0
        val sync = AutomaticSync(backgroundScope) { reads++ }
        repeat(10) { sync.request() }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(0L, currentTime)
    }

    @Test fun liveEventBurstsAreCoalescedBeforeFetching() = runTest {
        var reads = 0
        val sync = AutomaticSync(backgroundScope) { reads++ }
        sync.request(false); runCurrent()
        advanceTimeBy(100)
        repeat(10) { sync.request(false) }
        advanceTimeBy(99); runCurrent()
        assertEquals(0, reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, reads)
    }

    @Test fun navigationDuringIoKeepsTheReadAndSchedulesOneFreshFollowUp() = runTest {
        val release = CompletableDeferred<Unit>()
        var reads = 0; var inFlight = 0; var peak = 0
        val sync = AutomaticSync(backgroundScope) {
            reads++; inFlight++; peak = maxOf(peak, inFlight)
            if (reads == 1) release.await()
            inFlight--
        }
        sync.request(); runCurrent()
        repeat(20) { sync.request() }
        runCurrent()
        assertEquals(1, reads)
        release.complete(Unit); runCurrent()
        assertEquals(2, reads); assertEquals(1, peak); assertEquals(0, inFlight)
    }

    @Test fun handledTransientFailureDoesNotPreventNextAutomaticRequest() = runTest {
        var attempts = 0; var successful = false
        val sync = AutomaticSync(backgroundScope) {
            try {
                if (++attempts == 1) throw IOException("temporary disconnect")
                successful = true
            } catch (_: IOException) { /* The ViewModel retains cache and schedules a retry. */ }
        }
        sync.request(); runCurrent()
        assertFalse(successful)
        sync.request(); runCurrent()
        assertTrue(successful); assertEquals(2, attempts)
    }

    @Test fun libraryReadsAreConcurrentAndOnlyRequestSummariesAndProjects() = runTest {
        val release = CompletableDeferred<Unit>()
        val paths = mutableListOf<String>(); val methods = mutableListOf<String>()
        val read = async {
            readLibrarySnapshot("space & فارسی", listOf("cli", "appServer"), get = { path ->
                paths += path; release.await(); jsonObject("path" to path)
            }, call = { method, params ->
                methods += method
                assertEquals("true", params.text("useStateDbOnly"))
                assertEquals(listOf("cli", "appServer"), params.array("sourceKinds").map { it.jsonPrimitive.content })
                release.await(); jsonObject("kind" to "live")
            })
        }
        runCurrent()
        val query = java.net.URLEncoder.encode("space & فارسی", "UTF-8")
        assertEquals(setOf("/v1/projects", "/v1/history?archived=false&searchTerm=$query",
            "/v1/history?archived=true&searchTerm=$query"), paths.toSet())
        assertEquals(listOf("thread/list"), methods)
        assertFalse(read.isCompleted)
        release.complete(Unit)
        assertEquals("live", read.await().live.text("kind"))
    }

    @Test fun failedSnapshotCancelsOutstandingSummaryRequests() = runTest {
        val fail = CompletableDeferred<Unit>()
        var cancelled = 0
        supervisorScope {
            val read = async {
                readLibrarySnapshot("", emptyList(), get = { path ->
                    if (path == "/v1/projects") { fail.await(); throw IOException("offline") }
                    try { awaitCancellation() } finally { cancelled++ }
                }, call = { _, _ -> try { awaitCancellation() } finally { cancelled++ } })
            }
            runCurrent(); fail.complete(Unit); runCurrent()
            try { read.await(); fail("Expected failure") } catch (_: IOException) { }
        }
        assertEquals(3, cancelled)
    }

    @Test fun onlyVisibleConnectedLibrariesAutoFetchWhileSelectedChatUsesItsOwnSync() {
        val state = ScreenState(signedIn = true, connection = "Connected")
        for (page in listOf("chats", "archived", "projects")) {
            assertTrue(needsLibrarySync(state.copy(page = page, projectFilter = "project")))
            assertFalse(needsLibrarySync(state.copy(page = page, selectedChat = Chat("id", "Title", "", ""))))
        }
        assertFalse(needsLibrarySync(state.copy(page = "settings")))
        assertFalse(needsLibrarySync(state.copy(connection = "Offline")))
        assertFalse(needsLibrarySync(state.copy(signedIn = false)))
    }

    @Test fun commandAndTextDeltasDoNotDownloadTheLibraryAgain() {
        for (method in listOf("thread/started", "thread/status/changed", "thread/name/updated", "thread/project/updated",
            "thread/archived", "thread/unarchived", "thread/deleted", "turn/started", "turn/completed", "project/changed"))
            assertTrue(method, libraryChanged(method))
        for (method in listOf("item/commandExecution/outputDelta", "item/agentMessage/delta", "item/started", "item/completed"))
            assertFalse(method, libraryChanged(method))
    }

    @Test fun lateReadsCannotReplaceNewerChatAccountOrBackendResults() {
        val reads = SyncReads()
        val old = reads.start("chat", "account-one", "epoch-one")
        val other = reads.start("other-chat", "account-one", "epoch-one")
        assertTrue(reads.isCurrent(old, "account-one", "epoch-one"))
        val latest = reads.start("chat", "account-one", "epoch-one")
        assertFalse(reads.isCurrent(old, "account-one", "epoch-one"))
        assertTrue(reads.isCurrent(latest, "account-one", "epoch-one"))
        assertTrue(reads.isCurrent(other, "account-one", "epoch-one"))
        assertFalse(reads.isCurrent(latest, "account-two", "epoch-one"))
        assertFalse(reads.isCurrent(latest, "account-one", "epoch-two"))
    }
}
