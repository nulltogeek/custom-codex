package dev.nulltogeek.codexremote

data class MarkdownBlock(val kind: String, val text: String, val language: String = "", val level: Int = 0,
    val marker: String = "", val checked: Boolean? = null)
data class InlinePart(val text: String, val style: String = "", val url: String = "")

/** Keeps whitespace inside fences intact, including unfinished fences during streaming. */
fun markdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.replace("\r\n", "\n").split('\n'); val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>(); var index = 0
    fun flush() { if (paragraph.isNotEmpty()) { result += MarkdownBlock("paragraph", paragraph.joinToString("\n")); paragraph.clear() } }
    while (index < lines.size) {
        val line = lines[index]
        val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
        if (fence != null) {
            flush(); val marker = fence.groupValues[1]; val language = fence.groupValues[2].trim().take(40)
            val code = mutableListOf<String>(); index++
            while (index < lines.size && !Regex("^ {0,3}" + Regex.escape(marker.first().toString()) + "{" + marker.length + ",}\\s*$").matches(lines[index])) code += lines[index++]
            result += MarkdownBlock("code", code.joinToString("\n"), language)
        } else {
            val heading = Regex("^ {0,3}(#{1,6})\\s+(.+?)(?:\\s+#+)?$").find(line)
            val list = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.+)$").find(line)
            when {
                line.isBlank() -> flush()
                heading != null -> { flush(); result += MarkdownBlock("heading", heading.groupValues[2], level = heading.groupValues[1].length) }
                Regex("^\\s*(?:---+|\\*\\*\\*+|___+)\\s*$").matches(line) -> { flush(); result += MarkdownBlock("rule", "") }
                line.trimStart().startsWith('>') -> {
                    flush(); val quote = mutableListOf<String>()
                    while (index < lines.size && lines[index].trimStart().startsWith('>')) quote += lines[index++].trimStart().removePrefix(">").trimStart()
                    index--; result += MarkdownBlock("quote", quote.joinToString("\n"))
                }
                list != null -> {
                    flush()
                    val indent = list.groupValues[1].replace("\t", "    ").length
                    val content = mutableListOf(list.groupValues[3]); val marker = list.groupValues[2]
                    while (index + 1 < lines.size && lines[index + 1].isNotBlank() &&
                        lines[index + 1].takeWhile(Char::isWhitespace).replace("\t", "    ").length > indent &&
                        !Regex("^\\s*(?:[-+*]|\\d+[.)])\\s+").containsMatchIn(lines[index + 1]) &&
                        !lines[index + 1].trimStart().startsWith("```") && !lines[index + 1].trimStart().startsWith("~~~")) content += lines[++index].trimStart()
                    val task = Regex("^\\[([ xX])]\\s+(.+)$").find(content.first())
                    if (task != null) content[0] = task.groupValues[2]
                    result += MarkdownBlock("list", content.joinToString("\n"), level = indent / 2,
                        marker = if (marker.first().isDigit()) marker else "•", checked = task?.groupValues?.get(1)?.let { it != " " })
                }
                line.contains('|') && index + 1 < lines.size && Regex("^[ |:-]+$").matches(lines[index + 1]) && lines[index + 1].contains('-') -> {
                    flush(); val table = mutableListOf(line); index += 2
                    while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) table += lines[index++]
                    index--; result += MarkdownBlock("table", table.joinToString("\n"))
                }
                else -> paragraph += line
            }
        }
        index++
    }
    flush(); return result
}

fun inlineParts(text: String): List<InlinePart> {
    val pattern = Regex("(`[^`\\n]+`|\\*\\*[^*]+\\*\\*|__[^_]+__|~~[^~\\n]+~~|\\[[^]\\n]+]\\(https?://[^\\s)]+\\)|\\*[^*\\n]+\\*)")
    val parts = mutableListOf<InlinePart>(); var offset = 0
    for (match in pattern.findAll(text)) {
        if (match.range.first > offset) parts += InlinePart(text.substring(offset, match.range.first))
        val raw = match.value
        parts += when {
            raw.startsWith('`') -> InlinePart(raw.drop(1).dropLast(1), "code")
            raw.startsWith("**") || raw.startsWith("__") -> InlinePart(raw.drop(2).dropLast(2), "bold")
            raw.startsWith("~~") -> InlinePart(raw.drop(2).dropLast(2), "strike")
            raw.startsWith('[') -> InlinePart(raw.substring(1, raw.indexOf(']')), "link", raw.substring(raw.indexOf('(') + 1, raw.length - 1))
            else -> InlinePart(raw.drop(1).dropLast(1), "italic")
        }
        offset = match.range.last + 1
    }
    if (offset < text.length) parts += InlinePart(text.substring(offset))
    return parts
}

/** Keep escaped pipes and pipes inside inline code in their table cell. */
fun markdownTableRows(text: String): List<List<String>> = text.lines().map { line ->
    val cells = mutableListOf<String>(); val cell = StringBuilder(); var code = false; var escaped = false
    for (character in line.trim()) {
        when {
            escaped -> { if (character != '|') cell.append('\\'); cell.append(character); escaped = false }
            character == '\\' -> escaped = true
            character == '`' -> { code = !code; cell.append(character) }
            character == '|' && !code -> { cells += cell.toString().trim(); cell.clear() }
            else -> cell.append(character)
        }
    }
    if (escaped) cell.append('\\')
    cells += cell.toString().trim()
    if (line.trimStart().startsWith('|')) cells.removeAt(0)
    if (line.trimEnd().endsWith('|') && cells.lastOrNull().isNullOrEmpty()) cells.removeAt(cells.lastIndex)
    cells.toList()
}
