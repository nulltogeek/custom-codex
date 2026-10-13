package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun QuestionNavigator(
    messages: List<TimelineItem>, hasOlder: Boolean, loading: Boolean, error: String?,
    canLoadOlder: Boolean, loadOlder: () -> Unit, select: (String) -> Unit, close: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val questions = remember(messages, query) { questionLinks(messages, query) }
    AlertDialog(onDismissRequest = close, title = { Text("Your questions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Find a message") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("question-search"))
                if (questions.isEmpty()) Text(if (query.isBlank()) "No questions loaded yet." else "No matching messages loaded.")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("question-list")) {
                    items(questions, key = { it.id }) { question ->
                        TextButton(onClick = { select(question.id) }, modifier = Modifier.fillMaxWidth().testTag("question-link:${question.id}")) {
                            Text(question.preview, Modifier.fillMaxWidth(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider()
                    }
                }
                if (hasOlder) {
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = loadOlder, enabled = canLoadOlder && !loading,
                        modifier = Modifier.fillMaxWidth().testTag("question-load-earlier")) {
                        Text(if (loading) "Loading earlier messages…" else if (error != null) "Retry earlier messages" else "Load earlier messages")
                    }
                    if (!canLoadOlder) Text("Connect to load earlier messages.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
