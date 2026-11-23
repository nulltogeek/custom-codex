package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*

data class WorkspaceEntry(val name: String, val path: String, val kind: String, val size: Long)
data class WorkspaceDocument(val path: String, val content: String, val revision: String, val writable: Boolean)
data class FileBrowserState(
    val project: Project, val rootIndex: Int = 0, val directory: String = "",
    val entries: List<WorkspaceEntry> = emptyList(), val nextOffset: Int? = null,
    val query: String = "", val searching: Boolean = false, val truncated: Boolean = false,
    val document: WorkspaceDocument? = null, val draft: String = "",
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null, val message: String? = null,
) {
    val dirty get() = document != null && draft != document.content
}
fun workspaceRequest(browser: FileBrowserState, action: String, path: String, offset: Int = 0): JsonObject = buildJsonObject {
    put("projectId", browser.project.id); put("rootIndex", browser.rootIndex); put("action", action); put("path", path)
    if (action == "list") put("offset", offset)
    if (action == "search") put("query", browser.query)
    if (action == "write") { put("content", browser.draft); put("revision", browser.document!!.revision) }
}
fun parseWorkspaceEntries(result: JsonObject) = result.array("entries").map { raw ->
    val entry = raw.jsonObject
    WorkspaceEntry(entry.text("name"), entry.text("path"), entry.text("kind"), entry["size"]?.jsonPrimitive?.longOrNull ?: 0)
}
