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
}
