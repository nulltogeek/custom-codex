package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal fun QuestionDialog(requestKey: String, questions: List<InputQuestion>,
    connected: Boolean, submitting: Boolean, error: String?, onDismiss: () -> Unit,
    onSubmit: (Map<String, String>) -> Unit, blockedReason: String? = null) {
    val answerSaver = remember(questions) { mapSaver<Map<String, String>>(
        save = { values -> values.filterKeys { id -> questions.none { it.id == id && it.secret } } },
        restore = { values -> values.mapValues { it.value as String } }) }
    var answers by rememberSaveable(requestKey, stateSaver = answerSaver) { mutableStateOf(emptyMap<String, String>()) }
    var custom by rememberSaveable(requestKey) { mutableStateOf(emptyList<String>()) }
    AlertDialog(onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Answer Codex") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                questions.forEach { question -> key(question.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (question.header.isNotBlank()) Text(question.header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(question.title, fontWeight = FontWeight.SemiBold)
                        Column(Modifier.selectableGroup()) {
                            question.options.forEach { option ->
                                val selected = question.id !in custom && answers[question.id] == option.label
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = !submitting, role = Role.RadioButton, onClick = {
                                    custom = custom - question.id; answers = answers + (question.id to option.label)
                                }).testTag("question-option:${question.id}:${option.label}"), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected, onClick = null, enabled = !submitting)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(option.label)
                                        if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (question.options.isNotEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(question.id in custom,
                                enabled = !submitting, role = Role.RadioButton, onClick = {
                                    if (question.id !in custom) { custom = custom + question.id; answers = answers - question.id }
                                }).testTag("question-custom:${question.id}"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(question.id in custom, onClick = null, enabled = !submitting)
                                Spacer(Modifier.width(8.dp)); Text("Write your own answer")
                            }
                        }
                        if (question.options.isEmpty() || question.id in custom) OutlinedTextField(answers[question.id].orEmpty(),
                            { answers = answers + (question.id to it) }, Modifier.fillMaxWidth().testTag("question-answer:${question.id}"),
                            enabled = !submitting, label = { Text("Your answer") }, minLines = if (question.secret) 1 else 2,
                            visualTransformation = if (question.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (question.secret) KeyboardType.Password else KeyboardType.Text))
                    }
                } }
                if (!connected) Text("Reconnect to submit. Your answers stay here.", color = MaterialTheme.colorScheme.error)
                if (blockedReason != null) Text(blockedReason, color = MaterialTheme.colorScheme.error)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("question-error"))
                if (submitting) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Sending answer…")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(answers) },
            enabled = connected && !submitting && blockedReason == null && validQuestionAnswers(questions, answers), modifier = Modifier.testTag("submit-question")) { Text("Submit answer") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Later") } })
}
