package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.nio.file.Paths
import kotlinx.serialization.json.*

data class ProjectCreateRequest(val name: String, val roots: List<String>)
internal fun projectFolders(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
    .map { Paths.get(it).normalize().toString() }.distinct().toList()
internal fun projectCreationValidation(name: String, folders: String): String? {
    renameValidation(name)?.let { return it }
    val lines = folders.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.size > 8) return "Use at most 8 folders."
    if (lines.any { !it.startsWith('/') || it.length > 4096 || it.any { c -> c.code < 32 || c.code == 127 } })
        return "Use full folder paths on your computer, one per line, starting with /."
    return null
}
internal fun projectCreateParams(key: String, request: ProjectCreateRequest) = buildJsonObject {
    put("idempotencyKey", key); put("name", request.name)
    put("roots", buildJsonArray { request.roots.forEach { add(jsonObject("path" to it)) } })
}
internal fun createdProject(raw: JsonObject): Project {
    require(raw.text("id").isNotEmpty() && raw.text("name").isNotEmpty()) { "Project creation returned an incomplete result. Retry to check it." }
    return Project(raw.text("id"), raw.text("name"), raw.array("roots").map { it.jsonObject.text("path") })
}
internal fun inventoryWithProject(inventory: JsonObject, project: Project): JsonObject = JsonObject(inventory + ("projects" to
    JsonArray(inventory.array("projects").filterNot { it.jsonObject.text("id") == project.id } + buildJsonObject {
        put("id", project.id); put("name", project.name); put("rootPaths", JsonArray(project.roots.map(::JsonPrimitive)))
    })))
internal fun canCreateProject(state: ScreenState) = canRename(state) && !state.creatingProject

@Composable internal fun NewProjectDialog(key: String, request: ProjectCreateRequest?, saving: Boolean, connected: Boolean,
    error: String?, dismiss: () -> Unit, create: (String, String) -> Unit) {
    var name by rememberSaveable(key) { mutableStateOf(request?.name.orEmpty()) }
    var folders by rememberSaveable(key) { mutableStateOf(request?.roots?.joinToString("\n").orEmpty()) }
    val validation = projectCreationValidation(name, folders)
    val locked = saving || request != null
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("New project") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("project-name"), label = { Text("Project name") }, singleLine = true, enabled = !locked)
            OutlinedTextField(folders, { folders = it }, Modifier.fillMaxWidth().testTag("project-folders"),
                label = { Text("Folders on your computer (optional)") }, minLines = 2, maxLines = 5, enabled = !locked)
            Text("Use full paths to existing folders, one per line. You can leave this empty for a project without folders.", style = MaterialTheme.typography.bodySmall)
            if (name.isNotEmpty() && validation != null) Text(validation, color = MaterialTheme.colorScheme.error)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            if (!connected) Text("Reconnect to create your project.")
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { create(name, folders) }, enabled = connected && !saving && validation == null,
        modifier = Modifier.testTag("project-create")) { Text(if (saving) "Creating…" else if (request != null) "Retry creation" else "Create") } },
        dismissButton = { TextButton(dismiss, enabled = !saving) { Text(if (request != null) "Close" else "Cancel") } })
}
