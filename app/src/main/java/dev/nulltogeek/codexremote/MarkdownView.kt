package dev.nulltogeek.codexremote

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun MarkdownView(text: String) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index, block.kind) {
            when (block.kind) {
                "code" -> CodeBlock(block.text, block.language)
                "rule" -> HorizontalDivider()
                "table" -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        block.text.lines().forEachIndexed { index, row ->
                            Text(row.trim().trim('|').split('|').joinToString("  │  ") { it.trim() }, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                else -> SelectionContainer {
                    val style = if (block.kind == "heading") when (block.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium } else MaterialTheme.typography.bodyMedium
                    Text(markdownInline(block.text), Modifier.then(if (block.kind == "quote") Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp) else Modifier),
                        style = style, fontWeight = if (block.kind == "heading") FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            }
        }
    }
}

@Composable private fun markdownInline(text: String): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary; val background = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        inlineParts(text).forEach { part ->
            when (part.style) {
                "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) }
                "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part.text) }
                "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = background, color = primary)) { append(part.text) }
                "link" -> withLink(LinkAnnotation.Url(part.url, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)))) { append(part.text) }
                else -> append(part.text)
            }
        }
    }
}

@Composable fun CodeBlock(code: String, language: String = "", collapsible: Boolean = true) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(6000) }
    val shown = if (!collapsible || expanded) code.take(limit) else ""
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val palette = mapOf(
        SyntaxKind.Keyword to if (dark) Color(0xFFC4B5FD) else Color(0xFF6D28D9),
        SyntaxKind.String to if (dark) Color(0xFF86EFAC) else Color(0xFF176C3D),
        SyntaxKind.Number to if (dark) Color(0xFFFCD34D) else Color(0xFF925400),
        SyntaxKind.Function to if (dark) Color(0xFF7DD3FC) else Color(0xFF075985),
        SyntaxKind.Type to if (dark) Color(0xFFFDBA74) else Color(0xFF9A3412),
        SyntaxKind.Property to if (dark) Color(0xFF67E8F9) else Color(0xFF0E6675),
        SyntaxKind.Variable to if (dark) Color(0xFFF9A8D4) else Color(0xFF9D174D),
        SyntaxKind.Tag to if (dark) Color(0xFFF9A8D4) else Color(0xFF9D174D),
        SyntaxKind.Comment to colors.onSurfaceVariant,
        SyntaxKind.Operator to colors.onSurface,
        SyntaxKind.Added to colors.secondary,
        SyntaxKind.Removed to colors.error,
        SyntaxKind.Heading to colors.primary
    )
    val highlighted = remember(shown, language, palette) {
        buildAnnotatedString {
            append(shown.ifEmpty { " " })
            syntaxTokens(shown, language).forEach { token ->
                addStyle(SpanStyle(color = palette.getValue(token.kind),
                    fontWeight = if (token.kind == SyntaxKind.Keyword || token.kind == SyntaxKind.Heading) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (token.kind == SyntaxKind.Comment) FontStyle.Italic else FontStyle.Normal), token.start, token.end)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (collapsible) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(language.ifEmpty { "code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse code" else "Expand code")
                } else Text(language.ifEmpty { "code" }, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if (copied) "Copied" else "Copy code") }
            }
            if (!collapsible || expanded) {
                SelectionContainer { Text(highlighted, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp, softWrap = false) }
                if (code.length > limit) TextButton(onClick = { limit += 6000 }) { Text("Show more output") }
            }

        }
    }
}
