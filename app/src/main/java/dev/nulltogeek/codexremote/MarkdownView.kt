package dev.nulltogeek.codexremote

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun MarkdownView(text: String) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
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

@Composable fun CodeBlock(code: String, language: String = "") {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val foreground = MaterialTheme.colorScheme.onSurface; val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary; val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val highlighted = remember(code, foreground, primary, secondary, muted) {
        buildAnnotatedString {
            append(code.ifEmpty { " " })
            Regex("(?m)(//[^\\n]*|#[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b(?:fun|val|var|class|import|from|def|return|if|else|for|while|const|let|function|public|private|true|false|null|None|async|await)\\b|\\b\\d+(?:\\.\\d+)?\\b)").findAll(code).forEach { token ->
                val color = when { token.value.startsWith("//") || token.value.startsWith('#') -> muted; token.value.startsWith('"') || token.value.startsWith('\'') -> secondary; else -> primary }
                addStyle(SpanStyle(color = color), token.range.first, token.range.last + 1)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(language.ifEmpty { "code" }, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text(if (copied) "Copied" else "Copy code") }
            }
            SelectionContainer { Text(highlighted, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, softWrap = false) }
        }
    }
}
