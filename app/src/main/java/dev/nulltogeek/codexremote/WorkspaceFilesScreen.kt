package dev.nulltogeek.codexremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun WorkspaceFilesScreen(browser: FileBrowserState, connected: Boolean,
    close: () -> Unit, browse: (String, Int?, Boolean, Boolean) -> Unit, read: (String) -> Unit,
    query: (String) -> Unit, edit: (String) -> Unit, save: () -> Unit) {
    var pending by remember(browser.project.id) { mutableStateOf<(() -> Unit)?>(null) }
    fun navigate(action: () -> Unit) {
        if (browser.saving) return
        if (browser.dirty) pending = action else action()
    }
    fun back() = navigate {
        when {
            browser.document != null -> browse(browser.directory, null, browser.searching, false)
            browser.directory.isNotEmpty() -> browse(browser.directory.substringBeforeLast('/', ""), null, false, false)
            else -> close()
        }
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().imePadding().testTag("workspace-files")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back() }, enabled = !browser.saving, modifier = Modifier.testTag("files-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back from files")
            }
            Column(Modifier.weight(1f)) {
                Text(browser.document?.path?.substringAfterLast('/') ?: "Project files", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(browser.project.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (browser.document != null) {
                IconButton(onClick = { navigate { read(browser.document.path) } }, enabled = connected && !browser.loading && !browser.saving) {
                    Icon(Icons.Outlined.Refresh, "Reload file")
                }
                TextButton(onClick = save, enabled = connected && browser.dirty && !browser.loading && !browser.saving && browser.document.writable,
                    modifier = Modifier.testTag("save-file")) { Text(if (browser.saving) "Saving…" else "Save") }
            }
            IconButton(onClick = { navigate(close) }, enabled = !browser.saving) { Icon(Icons.Outlined.Close, "Close project files") }
        }
        if (browser.loading || browser.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        browser.error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("files-error"))
                if (browser.document == null) TextButton(onClick = { browse(browser.directory, null, browser.searching, false) }, enabled = connected && !browser.loading) { Text("Retry") }
            }
        } }
        browser.message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("files-message"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        if (!connected) Text("Reconnect to browse or save. Your open edits stay here.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                if (wide || browser.document == null) Column(if (wide) Modifier.width(280.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        browser.project.roots.forEachIndexed { index, path -> FilterChip(selected = index == browser.rootIndex,
                            onClick = { navigate { browse("", index, false, false) } }, enabled = connected && !browser.saving,
                            label = { Text(path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }) }) }
                    }
                    Text(browser.project.roots.getOrNull(browser.rootIndex).orEmpty() + if (browser.directory.isEmpty()) "" else "/${browser.directory}",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(browser.query, query, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("file-search"),
                        placeholder = { Text("Search filenames") }, singleLine = true, enabled = !browser.saving,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                            navigate { browse(browser.directory, null, browser.query.isNotBlank(), false) }
                        }), trailingIcon = { IconButton(onClick = { navigate { browse(browser.directory, null, browser.query.isNotBlank(), false) } }, enabled = connected && !browser.loading && !browser.saving) {
                            Icon(Icons.Outlined.Search, "Search project files")
                        } })
                    if (browser.directory.isNotEmpty() || browser.searching) TextButton(onClick = {
                        navigate { browse(if (browser.searching) browser.directory else browser.directory.substringBeforeLast('/', ""), null, false, false) }
                    }, enabled = connected && !browser.saving) { Text(if (browser.searching) "Back to folder" else "Parent folder") }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("file-list")) {
                        items(browser.entries, key = { it.path }) { entry ->
                            val supported = entry.kind in listOf("directory", "file")
                            ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                                supportingContent = { Text(if (browser.searching) entry.path else when(entry.kind) { "directory" -> "Folder"; "link" -> "Link · cannot open"; "file" -> "${entry.size} bytes"; else -> "Unsupported file" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(if (entry.kind == "directory") Icons.Outlined.Folder else if (entry.kind == "link") Icons.Outlined.Link else Icons.Outlined.Description, null) },
                                modifier = Modifier.testTag("file:${entry.path}").clickable(enabled = supported && connected && !browser.loading && !browser.saving) {
                                    navigate { if (entry.kind == "directory") browse(entry.path, null, false, false) else read(entry.path) }
                                })
                        }
                        if (browser.nextOffset != null && !browser.searching) item { TextButton(onClick = { browse(browser.directory, null, false, true) }, enabled = connected && !browser.loading) { Text("Load more files") } }
                        if (browser.entries.isEmpty() && !browser.loading && browser.error == null) item {
                            Text(if (browser.searching) "No matching filenames" else "This folder is empty", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (browser.truncated) item { Text("Search limit reached. Try a more specific name or search inside a folder.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (browser.document == null) TextButton(onClick = { browse(browser.directory, null, browser.searching, false) }, enabled = connected && !browser.loading) { Text("Refresh files") }
                }
                if (wide) VerticalDivider()
                browser.document?.let { document -> Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(document.path, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        Text(if (!document.writable) "Read-only" else if (browser.dirty) "Unsaved" else "Saved", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, color = if (browser.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(browser.draft, edit, Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp).testTag("file-editor"),
                        readOnly = !document.writable || browser.saving || browser.loading,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                } } ?: if (wide) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("Choose a text file to edit", style = MaterialTheme.typography.bodyMedium)
                } else Unit
            }
        }
    }
    pending?.let { action -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("Discard unsaved edits?") },
        text = { Text("Your changes have not been saved on the server.") },
        confirmButton = { TextButton(onClick = { pending = null; action() }) { Text("Discard edits") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep editing") } }) }
}
