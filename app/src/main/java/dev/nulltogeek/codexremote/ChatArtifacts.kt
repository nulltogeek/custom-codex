package dev.nulltogeek.codexremote

data class ChatArtifact(val name: String, val source: String, val image: Boolean)

fun chatArtifacts(text: String): List<ChatArtifact> {
    val results = mutableListOf<ChatArtifact>()
    // Fenced examples are not output attachments.
    markdownBlocks(text).filter { it.kind != "code" }.forEach { block ->
        val pattern = Regex("(!?)\\[([^]\\n]*)]\\(")
        var offset = 0
        while (offset < block.text.length) {
            val match = pattern.find(block.text, offset) ?: break
            var index = match.range.last + 1; val start = index; var depth = 1
            val source: String
            if (block.text.getOrNull(index) == '<') {
                val end = block.text.indexOf('>', index + 1)
                if (end < 0 || block.text.getOrNull(end + 1) != ')') { offset = index; continue }
                source = block.text.substring(index + 1, end); index = end + 2
            } else {
                while (index < block.text.length && depth > 0) {
                    when (block.text[index]) { '\\' -> index++; '(' -> depth++; ')' -> depth-- }
                    index++
                }
                if (depth != 0) break
                source = block.text.substring(start, index - 1)
            }
            offset = index
            val raw = source.trim()
            val remote = raw.startsWith("https://") || raw.startsWith("http://")
            val path = raw.replace(Regex(":\\d+(?::\\d+)?$"), "")
            val local = raw.startsWith('/') || raw.startsWith("sandbox:/") || raw.startsWith("file:///") ||
                (path.isNotBlank() && !path.contains(':') && !raw.startsWith('#'))
            val image = match.groupValues[1] == "!" || Regex("(?i)\\.(png|jpe?g|gif|webp)(?:[:#?].*)?$").containsMatchIn(raw)
            if ((local || remote && image || raw.startsWith("data:image/")) && raw.length <= 4096) {
                val name = match.groupValues[2].ifBlank { raw.substringBefore('#').substringAfterLast('/').take(160) }.take(160)
                results += ChatArtifact(name.ifBlank { "Codex output" }, raw, image)
            }
        }
    }
    return results.distinctBy { it.source }.take(20)
}

fun artifactMime(source: String): String = when (source.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()) {
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "pdf" -> "application/pdf"; "txt", "md", "py", "kt", "js", "ts", "csv", "log" -> "text/plain"
    "json" -> "application/json"; "html" -> "text/html"; "svg" -> "image/svg+xml"; "zip" -> "application/zip"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    else -> "application/octet-stream"
}
fun artifactFilename(source: String): String = runCatching {
    java.net.URLDecoder.decode(source.replace("+", "%2B"), "UTF-8")
}.getOrDefault(source).substringBefore('#').substringBefore('?').replace(Regex(":\\d+(?::\\d+)?$"), "")
    .substringAfterLast('/').replace(Regex("[\\x00-\\x1f\\x7f/\\\\]"), "_").takeLast(160).let { if (it.isBlank() || it == "." || it == "..") "codex-output" else it }
