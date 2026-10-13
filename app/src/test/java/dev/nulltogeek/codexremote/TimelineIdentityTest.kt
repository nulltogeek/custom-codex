package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineIdentityTest {
    private fun user(id: String, text: String = "hello", turn: String = "turn", image: String = "") = parseItem(buildJsonObject {
        put("id", id); put("type", "userMessage"); put("content", buildJsonArray {
            add(jsonObject("type" to "text", "text" to text))
            if (image.isNotEmpty()) add(jsonObject("type" to "localImage", "path" to image))
        })
    }, turn, "inProgress")
    private fun snapshot(vararg items: TimelineItem) = LiveChatSnapshot(items.toList(), items.associate { it.id to 10L })
    private fun cache() = LiveChatHistory().apply { activate("account", "epoch") }
    private fun LiveChatHistory.get() = snapshot("chat", "account", "epoch")
    private fun LiveChatHistory.event(seq: Long, item: TimelineItem, method: String = "item/started") {
        accept(buildJsonObject {
            put("method", method); put("_bridgeSequence", seq); put("_bridgeEpoch", "epoch")
            put("params", buildJsonObject {
                put("threadId", "chat"); put("turnId", item.turnId)
                put("item", buildJsonObject { put("id", item.id); put("type", item.type); put("content", item.input) })
            })
        }, "account", "epoch")
    }

    @Test fun liveAndSavedUserIdsRenderOneBubbleWithSavedIdentity() {
        val live = user("live"); val saved = user("saved")
        val merged = snapshot(live).merge(listOf(saved), 20)
        assertEquals(listOf(saved), merged)
        assertEquals("saved", snapshot(live).reconciled(listOf(saved), 20).canonical(live).id)
    }

    @Test fun newestEventAndCommandArePreservedWhenUserIdentityChanges() {
        val live = user("live")
        val command = TimelineItem("cmd", "commandExecution", "output", turnId = "turn")
        val before = LiveChatSnapshot(listOf(live, command), mapOf("live" to 30L, "cmd" to 31L))
        val reconciled = before.reconciled(listOf(user("saved")), 20)
        assertEquals(30L, reconciled.itemSequences["saved"])
        assertEquals(listOf("saved", "cmd"), reconciled.merge(listOf(user("saved")), 20).map { it.id })
    }

    @Test fun repeatedInputInDifferentTurnsIsNeverCollapsed() {
        val first = user("saved", turn = "first")
        val second = user("live", turn = "second")
        assertEquals(listOf(first, second), snapshot(second).merge(listOf(first), 20))
        assertFalse(sameUserInput(first.copy(turnId = ""), second.copy(turnId = "")))
    }

    @Test fun repeatedOccurrencesWithinOneTurnMatchOneToOne() {
        val saved = listOf(user("saved1"), user("saved2"))
        val live = listOf(user("live1"), user("live2"))
        assertEquals(saved, snapshot(*live.toTypedArray()).merge(saved, 20))
        // An already-shared ID must retain its occurrence when another matches.
        assertEquals(saved, snapshot(user("saved2"), user("live1")).merge(saved, 20))
        assertEquals(emptyMap<String, String>(), userMessageAliases(saved, listOf(user("saved1"))))
    }

    @Test fun sameTextWithDifferentAttachmentsOrOtherContentIsKeptSeparate() {
        val saved = user("saved", image = "/one.jpg")
        val other = user("live", image = "/two.jpg")
        assertEquals(2, snapshot(other).merge(listOf(saved), 20).size)
        assertFalse(sameUserInput(saved, user("live")))
        val file = user("live").copy(input = JsonArray(listOf(jsonObject("type" to "file", "path" to "/file.txt"))))
        assertFalse(sameUserInput(user("saved"), file))
    }

    @Test fun harmlessTextMetadataDoesNotDuplicateAUserMessage() {
        val live = user("live")
        val saved = user("saved").copy(input = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "hello"); put("text_elements", buildJsonArray {}) })
        })
        assertEquals(1, snapshot(live).merge(listOf(saved), 20).size)
    }

    @Test fun completionAfterHistoryRefreshUsesTheSavedIdAndReopenStaysSingle() {
        val cache = cache(); cache.event(10, user("live"))
        cache.remember("chat", listOf(user("saved")), 20, "account", "epoch")
        cache.event(21, user("live"), "item/completed")
        assertEquals(listOf("saved"), cache.get().timeline.map { it.id })
        repeat(3) {
            cache.remember("chat", listOf(user("saved")), 30, "account", "epoch")
            assertEquals(1, cache.get().merge(listOf(user("saved")), 0).size)
        }
    }

    @Test fun historyArrivingBeforeActorEventsDoesNotIntroduceAnotherBubble() {
        val cache = cache()
        cache.remember("chat", listOf(user("saved")), 20, "account", "epoch")
        cache.event(10, user("live")); cache.event(21, user("live"), "item/completed")
        assertEquals(listOf("saved"), cache.get().timeline.map { it.id })
        assertEquals("saved", cache.get().itemAliases["live"])
    }

    @Test fun trulyNewUserEventBeyondTheHistoryWatermarkIsNotHidden() {
        val cache = cache()
        cache.remember("chat", listOf(user("saved")), 20, "account", "epoch")
        cache.event(21, user("new-live"))
        assertEquals(listOf("saved", "new-live"), cache.get().timeline.map { it.id })
    }

    @Test fun savedIdsCanChangeAgainWithoutLosingAliasOrNewerEventVersions() {
        val cache = cache(); cache.event(10, user("live"))
        cache.remember("chat", listOf(user("saved1")), 20, "account", "epoch")
        cache.remember("chat", listOf(user("saved2")), 30, "account", "epoch")
        cache.event(31, user("live"), "item/completed")
        assertEquals(listOf("saved2"), cache.get().timeline.map { it.id })
        assertEquals(31L, cache.get().itemSequences["saved2"])
    }

    @Test fun staleDuplicateBesideAnAlreadyCanonicalMessageIsRemoved() {
        val saved = user("saved")
        assertEquals(listOf(saved), snapshot(saved, user("stale-live")).merge(listOf(saved), 20))
        assertEquals(2, snapshot(saved, user("new-live")).copy(itemSequences = mapOf("saved" to 10L, "new-live" to 21L))
            .merge(listOf(saved), 20).size)
    }

    @Test fun selectedTimelineCompletionUsesTheSameCanonicalId() {
        val cache = cache(); cache.event(10, user("live"))
        cache.remember("chat", listOf(user("saved")), 20, "account", "epoch")
        val params = buildJsonObject { put("turnId", "turn"); put("item", buildJsonObject {
            put("id", "live"); put("type", "userMessage"); put("content", user("live").input)
        }) }
        val result = reduceTimeline(listOf(user("saved")), "item/completed", canonicalTimelineParams(params, cache.get().itemAliases))
        assertEquals(listOf("saved"), result.map { it.id })
    }

    @Test fun completionBetweenCacheHydrationAndVisibleStateUpdateStaysSingle() {
        val cache = cache(); cache.event(10, user("live"))
        cache.remember("chat", listOf(user("saved")), 20, "account", "epoch")
        val live = cache.get()
        val params = buildJsonObject { put("turnId", "turn"); put("item", buildJsonObject {
            put("id", "live"); put("type", "userMessage"); put("content", user("live").input)
        }) }
        val visible = listOf(user("live")).map(live::canonical).distinctBy { it.id }
        val result = reduceTimeline(visible, "item/completed", canonicalTimelineParams(params, live.itemAliases))
        assertEquals(listOf("saved"), result.map { it.id })
    }
}
