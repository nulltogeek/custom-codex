package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Sampling bounds the memory needed for both camera photos and image attachments.
internal fun decodeImagePreview(path: String, maxDimension: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    imageSampleOptions(bounds, maxDimension)?.let { BitmapFactory.decodeFile(path, it) }
}.getOrNull()

internal fun decodeImagePreview(bytes: ByteArray, maxDimension: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    imageSampleOptions(bounds, maxDimension)?.let { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
}.getOrNull()

private fun imageSampleOptions(bounds: BitmapFactory.Options, maxDimension: Int): BitmapFactory.Options? {
    require(maxDimension in 1..4096)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.Options().apply {
        inSampleSize = 1
        while ((bounds.outWidth.toLong() + inSampleSize - 1) / inSampleSize > maxDimension ||
            (bounds.outHeight.toLong() + inSampleSize - 1) / inSampleSize > maxDimension) inSampleSize *= 2
    }
}

@Composable fun HistoryImage(source: String, name: String = "Image attachment", loadImage: suspend (String) -> ByteArray) {
    var showPreview by remember(source) { mutableStateOf(false) }
    val thumbnail by produceState<Bitmap?>(null, source) {
        value = null
        value = loadHistoryBitmap(source, 256, loadImage)
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).testTag("history-image:$source")
            .clickable(onClickLabel = "Open image $name") { showPreview = true }, contentAlignment = Alignment.Center) {
            val image = thumbnail
            if (image != null) Image(image.asImageBitmap(), "Preview of $name", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.Outlined.Image, null)
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (showPreview) {
        var loading by remember(source) { mutableStateOf(true) }
        val fullPreview by produceState<Bitmap?>(null, source) {
            value = loadHistoryBitmap(source, 2048, loadImage)
            loading = false
        }
        ImagePreviewDialog(name, fullPreview, loading) { showPreview = false }
    }
}

private suspend fun loadHistoryBitmap(source: String, maxDimension: Int, loadImage: suspend (String) -> ByteArray): Bitmap? = try {
    withContext(Dispatchers.IO) { decodeImagePreview(loadImage(source), maxDimension) }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable fun ImagePreviewDialog(name: String, bitmap: Bitmap?, loading: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("image-preview-dialog"), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close image preview") }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(bitmap.asImageBitmap(), "Image $name", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else if (loading) CircularProgressIndicator(color = Color.White)
                    else Text("Image preview unavailable")
                }
            }
        }
    }
}
