package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ArchiveSelectionTest {
    private val first = Chat("one", "First", "", "", archived = true)
    private val second = first.copy(id = "two", title = "Second")
    private val third = first.copy(id = "three", title = "Third")
    private fun metadata(id: String, status: String = "idle") = buildJsonObject {
        put("thread", buildJsonObject { put("id", id); put("status", jsonObject("type" to status)) })
    }

    @Test fun bulkDeleteRequiresEverySelectedChatToBeEligible() {
        val state = ScreenState(connection = "Connected")
        assertTrue(canDeleteChats(state, listOf(first, second)))
        assertFalse(canDeleteChats(state, emptyList()))
        assertFalse(canDeleteChats(state, listOf(first, second.copy(archived = false))))
        assertFalse(canDeleteChats(state, listOf(first, second.copy(status = "active"))))
        assertFalse(canDeleteChats(state.copy(connection = "Offline"), listOf(first)))
        assertFalse(canDeleteChats(state.copy(loading = true), listOf(first)))
        assertFalse(canDeleteChats(state.copy(sending = true), listOf(first)))
        assertFalse(canDeleteChats(state.copy(pending = listOf(PendingInput("r", "approval", jsonObject("threadId" to "two")))), listOf(first, second)))
        assertFalse(canDeleteChats(state.copy(queuedMessages = listOf(QueuedMessage("q", "two", "waiting"))), listOf(first, second)))
        assertTrue(canDeleteChats(state.copy(queuedMessages = listOf(QueuedMessage("q", "other", "waiting"))), listOf(first, second)))
    }

    @Test fun deletesUniqueChatsSequentiallyWithLightweightReadsAndAcknowledgedCleanup() = runBlocking {
        val calls = mutableListOf<String>(); val removed = mutableListOf<Chat>()
        val result = deleteArchivedChats(listOf(first, second, first), { true }, { method, params ->
            val id = params.text("threadId"); calls += "$method:$id"
            if (method == "thread/read") {
                assertFalse(params["includeTurns"]!!.jsonPrimitive.boolean)
                metadata(id)
            } else {
                assertEquals(setOf("threadId"), params.keys)
                assertEquals(if (id == "one") emptyList<Chat>() else listOf(first), removed)
                jsonObject()
            }
        }, { removed += it })
        assertEquals(listOf("thread/read:one", "thread/delete:one", "thread/read:two", "thread/delete:two"), calls)
        assertEquals(listOf(first, second), removed); assertEquals(ArchiveDeletionResult(2), result)
    }

    @Test fun unsafeInitialSelectionCausesNoRemoteCallsOrDeletes() = runBlocking {
        val result = deleteArchivedChats(listOf(first, second), { it != second }, { _, _ -> error("Must not call server") }, { error("Must not remove") })
        assertEquals(0, result.deleted); assertNotNull(result.error)
    }

    @Test fun taskBecomingActiveOnHostPreventsDeletion() = runBlocking {
        val calls = mutableListOf<String>()
        val result = deleteArchivedChats(listOf(first, second), { true }, { method, _ ->
            calls += method; metadata("one", "active")
        }, { error("Must not remove") })
        assertEquals(listOf("thread/read"), calls)
        assertEquals(0, result.deleted); assertTrue(result.error!!.contains("running task"))
    }

    @Test fun queueOrRestoreDuringMetadataReadPreventsMutation() = runBlocking {
        var eligible = true
        val result = deleteArchivedChats(listOf(first), { eligible }, { method, _ ->
            assertEquals("thread/read", method); eligible = false; metadata("one")
        }, { error("Must not remove") })
        assertEquals(0, result.deleted); assertNotNull(result.error)
    }

    @Test fun partialFailureKeepsUnacknowledgedAndRemainingChats() = runBlocking {
        val calls = mutableListOf<String>(); val removed = mutableListOf<Chat>()
        val result = deleteArchivedChats(listOf(first, second, third), { true }, { method, params ->
            val id = params.text("threadId"); calls += "$method:$id"
            if (method == "thread/read") metadata(id)
            else if (id == "two") throw RpcException(-32601, "unsupported") else jsonObject()
        }, { removed += it })
        assertEquals(listOf(first), removed); assertEquals(1, result.deleted)
        assertTrue(result.error!!.contains("not deleted")); assertFalse(calls.any { it.endsWith(":three") })
    }

    @Test fun lostDeleteAcknowledgementIsNeverRetriedOrLocallyRemoved() = runBlocking {
        var deletes = 0
        val result = deleteArchivedChats(listOf(first, second), { true }, { method, _ ->
            if (method == "thread/read") metadata("one") else { deletes++; throw IOException("Connection lost") }
        }, { error("Must not remove") })
        assertEquals(1, deletes); assertEquals(0, result.deleted)
        assertTrue(result.error!!.contains("could not be confirmed"))
    }

    @Test fun metadataReadFailureStopsBeforeAnyDeletion() = runBlocking {
        val result = deleteArchivedChats(listOf(first), { true }, { method, _ ->
            assertEquals("thread/read", method); throw IOException("Connection lost")
        }, { error("Must not remove") })
        assertEquals(0, result.deleted); assertEquals("Connection lost", result.error)
    }

    @Test fun sessionExpirationPropagatesForNormalSignInRecovery() = runBlocking {
        val failure = runCatching { deleteArchivedChats(listOf(first, second), { true }, { method, _ ->
            if (method == "thread/read") metadata("one") else throw SessionExpiredException()
        }, { error("Must not remove") }) }.exceptionOrNull()
        assertTrue(failure is SessionExpiredException)
    }

    @Test fun cancellationStopsTheBatchWithoutFurtherMutations() = runBlocking {
        val failure = runCatching { deleteArchivedChats(listOf(first), { true }, { _, _ -> throw CancellationException() },
            { error("Must not remove") }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun contextChangeAfterOneDeleteStopsTheRemainingChats() = runBlocking {
        var valid = true; val removed = mutableListOf<Chat>()
        val result = deleteArchivedChats(listOf(first, second), { valid }, { method, _ ->
            if (method == "thread/read") metadata("one") else jsonObject()
        }, { removed += it; valid = false })
        assertEquals(listOf(first), removed); assertEquals(1, result.deleted); assertNotNull(result.error)
    }
}
