package dev.nulltogeek.codexremote

/** Prolonged outages back off, but a recovered connection starts fresh. */
internal class ReconnectBackoff {
    private var waitMillis = 1000L
    fun connected() { waitMillis = 1000L }
    fun nextDelayMillis(): Long {
        val next = waitMillis
        waitMillis = (waitMillis * 2).coerceAtMost(30000L)
        return next
    }
}
