package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun repeatedFailuresWaitLongerButNeverMoreThanThirtySeconds() {
        val backoff = ReconnectBackoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (1..7).map { backoff.nextDelayMillis() })
    }
    @Test fun successfulConnectionResetsTheNextDisconnectToOneSecond() {
        val backoff = ReconnectBackoff()
        repeat(8) { backoff.nextDelayMillis() }
        backoff.connected()
        assertEquals(1000L, backoff.nextDelayMillis())
        backoff.connected()
        assertEquals(1000L, backoff.nextDelayMillis())
        assertEquals(2000L, backoff.nextDelayMillis())
    }
}
