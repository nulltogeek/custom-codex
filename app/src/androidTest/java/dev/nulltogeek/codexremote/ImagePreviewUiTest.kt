package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImagePreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        return try { ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() } }
        finally { bitmap.recycle() }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { Surface { Box(Modifier.width(320.dp).fillMaxHeight()) { content() } } }
        } }
    }

    @Test fun uploadedThumbnailOpensFullImageAndDismissesWithoutRemovingAttachment() {
        val file = File.createTempFile("image-preview-fixture", ".png", compose.activity.cacheDir)
        file.writeBytes(imageBytes())
        val attachment = Attachment("fixture-image", "photo.png", file.length(), "image/png", buildJsonObject {
            put("type", "localImage"); put("path", "/uploads/photo.png")
        }, previewPath = file.absolutePath)
        val vm = RemoteViewModel(compose.activity.application)
        render { ChatComposer(ScreenState(connection = "Connected", attachments = listOf(attachment)), vm) {} }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Preview of photo.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Preview of photo.png").performClick()
        compose.onNodeWithTag("image-preview-dialog").assertIsDisplayed()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Image photo.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Image photo.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close image preview").performClick()
        compose.onNodeWithTag("image-preview-dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove photo.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview of photo.png").assertIsDisplayed()
    }

    @Test fun historyThumbnailOpensImageAndUnavailableHistoryCanBeDismissed() {
        val bytes = imageBytes()
        render { Row { HistoryImage("/uploads/photo.png", "Sent photo") { bytes }
            HistoryImage("/uploads/missing.png", "Missing photo") { error("Fixture unavailable") } } }
        compose.onNodeWithTag("history-image:/uploads/photo.png").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Image Sent photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Image Sent photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close image preview").performClick()
        compose.onNodeWithTag("history-image:/uploads/missing.png").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Image preview unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Image preview unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close image preview").performClick()
        compose.onNodeWithTag("image-preview-dialog").assertDoesNotExist()
    }

    @Test fun imageDecoderKeepsThumbnailSmallAndDialogImageReadable() {
        val bytes = imageBytes()
        val thumbnail = requireNotNull(decodeImagePreview(bytes, 256))
        val preview = requireNotNull(decodeImagePreview(bytes, 2048))
        try {
            assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 256)
            assertEquals(1600, preview.width); assertEquals(900, preview.height)
        } finally { thumbnail.recycle(); preview.recycle() }
        assertNull(decodeImagePreview(byteArrayOf(1, 2, 3), 2048))
    }
}
