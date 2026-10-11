package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class LibraryControlsTest {
    @Test fun archiveIsUnavailableWhileOfflineRunningSendingOrAwaitingInput() {
        val chat = Chat("c", "Title", "", "", status = "idle")
        val connected = ScreenState(connection = "Connected")
        assertTrue(canArchive(connected, chat))
        assertTrue(canArchive(connected, chat.copy(archived = true)))
        assertFalse(canArchive(connected.copy(connection = "Offline"), chat))
        assertFalse(canArchive(connected.copy(loading = true), chat))
        assertFalse(canArchive(connected.copy(sending = true), chat))
        assertFalse(canArchive(connected, chat.copy(status = "active")))
        assertFalse(canArchive(connected.copy(pending = listOf(PendingInput("r", "request", jsonObject("threadId" to "c")))), chat))
    }
    @Test fun passwordValidationChecksMatchAndUtf8LimitWithoutTrimmingSpaces() {
        assertNotNull(passwordValidation("", "long-new-password", "long-new-password"))
        assertNotNull(passwordValidation("old-password", "short", "short"))
        assertNotNull(passwordValidation("old-password", "long-new-password", "different"))
        assertNotNull(passwordValidation("same-password", "same-password", "same-password"))
        assertNotNull(passwordValidation("old-password", "é".repeat(600), "é".repeat(600)))
        assertNull(passwordValidation("old-password", "  new password  ", "  new password  "))
        assertNull(passwordValidation("old-password", "é".repeat(512), "é".repeat(512)))
    }
}
