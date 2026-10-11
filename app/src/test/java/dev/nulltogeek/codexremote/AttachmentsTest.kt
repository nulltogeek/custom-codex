package dev.nulltogeek.codexremote

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class AttachmentsTest {
    @Test fun documentStreamsAreBoundedWithoutTrustingProviderMetadata() {
        assertArrayEquals("content".toByteArray(), readAttachmentBytes(ByteArrayInputStream("content".toByteArray()), 7))
        assertTrue(runCatching { readAttachmentBytes(ByteArrayInputStream(ByteArray(8)), 7) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { readAttachmentBytes(ByteArrayInputStream(ByteArray(0))) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun pendingAttachmentRoundTripsWithoutChangingItsNativeInput() {
        val input = jsonObject("type" to "localImage", "path" to "/private/upload/photo.jpg")
        val file = Attachment("id", "photo.jpg", 123, "image/jpeg", input)
        assertEquals(file, parseAttachment(file.toJson()))
    }

    @Test fun pickerReturnAcceptsFilesBeforeTheChatSocketReconnects() {
        val selected = ScreenState(signedIn = true, selectedChat = Chat("c", "Draft", "", "/workspace"))
        for (status in listOf("Offline", "Connecting", "Connection lost", "Connected"))
            assertTrue(canAcceptPickedAttachments(selected.copy(connection = status)))
        assertFalse(canAcceptPickedAttachments(selected.copy(sending = true)))
        assertFalse(canAcceptPickedAttachments(selected.copy(signedIn = false)))
        assertFalse(canAcceptPickedAttachments(selected.copy(selectedChat = null)))
        assertFalse(canAcceptPickedAttachments(selected.copy(selectedChat = selected.selectedChat!!.copy(archived = true))))
    }

    @Test fun transmissionReaching100PercentIsNotYetAConfirmedUpload() {
        val pending = Attachment("a", "photo.jpg", 100, "image/jpeg", jsonObject(), uploading = true)
        assertEquals("Preparing…", attachmentStatus(pending))
        assertEquals("Uploading 45%", attachmentStatus(pending.copy(uploadProgress = 45)))
        assertEquals("Confirming upload…", attachmentStatus(pending.copy(uploadProgress = 100)))
        assertFalse(pending.copy(uploadProgress = 100).readyToSend)
        val uploaded = pending.copy(uploading = false, input = jsonObject("type" to "localImage", "path" to "/uploaded/photo.jpg"))
        assertTrue(uploaded.readyToSend); assertEquals("Uploaded", attachmentStatus(uploaded))
        assertEquals("1 uploaded · Ready to send", attachmentSummary(listOf(uploaded)))
    }

    @Test fun failuresRemainVisibleAcrossDraftReloadAndCannotBeSent() {
        val failed = Attachment("a", "photo.jpg", 100, "image/jpeg", jsonObject(), "/cache/photo.jpg", uploadProgress = 73, uploadError = "Network timed out")
        assertEquals(failed, parseAttachment(failed.toJson()))
        assertEquals("Upload failed", attachmentStatus(failed)); assertFalse(failed.readyToSend)
        assertEquals("0 uploaded · 1 failed · remove and choose again", attachmentSummary(listOf(failed)))
        assertFalse(failed.copy(uploadError = "").readyToSend)
        assertFalse(failed.copy(input = jsonObject("type" to "text", "text" to "partial")).readyToSend)
    }
}
