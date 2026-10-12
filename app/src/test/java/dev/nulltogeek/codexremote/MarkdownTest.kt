package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun listMarkersIndentationTasksAndContinuationTextStayTogether() {
        val blocks = markdownBlocks("1. First item\n   Continue the same item.\n   - Nested **detail**\n- [x] Completed\n- [ ] Pending")
        assertEquals(listOf("list", "list", "list", "list"), blocks.map { it.kind })
        assertEquals("1.", blocks.first().marker)
        assertEquals("First item\nContinue the same item.", blocks.first().text)
        assertEquals(1, blocks[1].level)
        assertEquals("•", blocks[1].marker)
        assertEquals(true, blocks[2].checked)
        assertEquals("Completed", blocks[2].text)
        assertEquals(false, blocks[3].checked)
    }
    @Test fun quotesFormOneReadableBlockAndTablesRespectInlineCodeAndEscapedPipes() {
        assertEquals("First line\nSecond line", markdownBlocks("> First line\n> Second line").single().text)
        val rows = markdownTableRows("| Name | Value |\n| **A** | `a|b` |\n| B | left\\|right |")
        assertEquals(listOf("Name", "Value"), rows.first())
        assertEquals(listOf("**A**", "`a|b`"), rows[1])
        assertEquals(listOf("B", "left|right"), rows[2])
        assertEquals(listOf("A", "", "C"), markdownTableRows("A || C").single())
        assertEquals(listOf("A", ""), markdownTableRows("| A | | ").single())
    }
    @Test fun deletedTextAndInlineCodeRemainDistinct() {
        val parts = inlineParts("Use ~~old~~ and `a|b` instead.")
        assertEquals("old", parts.first { it.style == "strike" }.text)
        assertEquals("a|b", parts.first { it.style == "code" }.text)
    }
    @Test fun fencedCodePreservesWhitespaceAndSeparatesExplanatoryText() {
        val blocks = markdownBlocks("Before\n\n```python\ndef greet():\n    return 'hi'\n```\n\nAfter")
        assertEquals(listOf("paragraph", "code", "paragraph"), blocks.map { it.kind })
        assertEquals("python", blocks[1].language); assertEquals("def greet():\n    return 'hi'", blocks[1].text)
    }
    @Test fun streamingOpenFencesAndLongerClosingFencesAreSupported() {
        assertEquals("  pending\n", markdownBlocks("```kt\n  pending\n").single().text)
        assertEquals("code", markdownBlocks("~~~sh\ncode\n~~~~").single().text)
    }
    @Test fun headingsListsQuotesTablesAndInlineCodeKeepTheirMeaning() {
        val blocks = markdownBlocks("## Title\n- **One**\n> Quote\n\n| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(listOf("heading", "list", "quote", "table"), blocks.map { it.kind })
        val parts = inlineParts("Use `x*y` and **bold**, *italic*, [docs](https://example.com).")
        assertEquals(listOf("code", "bold", "italic", "link"), parts.filter { it.style.isNotEmpty() }.map { it.style })
        assertEquals("x*y", parts.first { it.style == "code" }.text)
        assertEquals("https://example.com", parts.first { it.style == "link" }.url)
        assertTrue(inlineParts("[unsafe](file:///etc/passwd)").none { it.style == "link" })
    }
}
