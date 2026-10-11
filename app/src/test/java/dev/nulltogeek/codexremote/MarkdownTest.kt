package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
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
