package dev.nulltogeek.codexremote

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun ChatArtifactCard(artifact: ChatArtifact, load: suspend () -> ByteArray) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(artifact.source) { mutableStateOf<File?>(null) }
    var bitmap by remember(artifact.source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(artifact.source) { mutableStateOf(false) }
    var error by remember(artifact.source) { mutableStateOf<String?>(null) }
    var preview by remember(artifact.source) { mutableStateOf(false) }
    var saved by remember(artifact.source) { mutableStateOf(false) }
    val filename = if (artifact.source.startsWith("data:")) "generated-image.png" else artifactFilename(artifact.source)
    val mime = if (artifact.source.startsWith("data:")) "image/png" else artifactMime(filename)
    suspend fun obtain(): File {
        file?.takeIf { it.exists() }?.let { return it }
        val bytes = load()
        val result = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "chat-files/${java.util.UUID.randomUUID()}").apply { mkdirs() }
            File(directory, filename).also { it.writeBytes(bytes) }
        }
        file = result
        if (artifact.image) bitmap = withContext(Dispatchers.IO) { decodeImagePreview(result.path, 1024) }
        return result
    }
    fun run(action: suspend (File) -> Unit = {}) {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try { action(obtain()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Output unavailable. Tap Retry." }
            finally { loading = false }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) run { source ->
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> source.inputStream().use { it.copyTo(output) } }
            }
            saved = true
        }
    }
    LaunchedEffect(artifact.source) { if (artifact.image) run() }
    Surface(Modifier.fillMaxWidth().testTag("chat-artifact:${artifact.name}"), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (artifact.image) Icons.Outlined.Image else Icons.Outlined.Description, null)
                Text(artifact.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            bitmap?.let { image -> Image(image.asImageBitmap(), "Preview of ${artifact.name}", Modifier.fillMaxWidth().heightIn(max = 260.dp)
                .clickable(onClickLabel = "Preview ${artifact.name}") { preview = true }, contentScale = ContentScale.Fit) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (saved) Text("Saved to your selected location", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) TextButton(onClick = { run() }, enabled = !loading) { Text("Retry") }
                TextButton(onClick = { run { source ->
                    if (bitmap != null) preview = true
                    else try {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".apkprovider", source)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: android.content.ActivityNotFoundException) { error = "No installed app can open this file. Use Save to keep a copy." }
                } }, enabled = !loading) { Text(if (artifact.image) "View image" else "Open file") }
                TextButton(onClick = { save.launch(filename) }, enabled = !loading) { Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
            }
        }
    }
    if (preview) ImagePreviewDialog(artifact.name, bitmap, loading) { preview = false }
}
