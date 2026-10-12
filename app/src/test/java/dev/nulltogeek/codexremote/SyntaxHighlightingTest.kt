package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class SyntaxHighlightingTest {
    private fun tokens(code: String, language: String) = syntaxTokens(code, language).map { code.substring(it.start, it.end) to it.kind }
    @Test fun stringsAndCommentsProtectUrlsAndKeywordsFromOtherStyles() {
        val code = "const url = \"https://example.com/#if\"; // return 42\nfetch(url)"
        val tokens = tokens(code, "js")
        assertTrue(tokens.contains("const" to SyntaxKind.Keyword))
        assertTrue(tokens.contains("\"https://example.com/#if\"" to SyntaxKind.String))
        assertTrue(tokens.contains("// return 42" to SyntaxKind.Comment))
        assertTrue(tokens.contains("fetch" to SyntaxKind.Function))
        assertFalse(tokens.any { it.first == "return" || it.first == "42" })
    }
    @Test fun pythonTripleStringsAndUnfinishedCommentsKeepTheirBoundaries() {
        val code = "def greet():\n    text = \"\"\"if True\n# still a string\"\"\"\n    return 3.5e-2 # seconds"
        val tokens = tokens(code, "python")
        assertTrue(tokens.contains("\"\"\"if True\n# still a string\"\"\"" to SyntaxKind.String))
        assertTrue(tokens.contains("3.5e-2" to SyntaxKind.Number))
        assertEquals(listOf("# seconds"), tokens.filter { it.second == SyntaxKind.Comment }.map { it.first })
        assertEquals(listOf("/* return\nunfinished" to SyntaxKind.Comment), tokens("/* return\nunfinished", "kt"))
    }
    @Test fun jsonKeysAndCssColorsDoNotBecomeComments() {
        val json = tokens("{\"url\":\"https://example.com\",\"enabled\":true,\"count\":12}", "json")
        assertTrue(json.contains("\"url\"" to SyntaxKind.Property))
        assertTrue(json.contains("true" to SyntaxKind.Keyword))
        assertTrue(json.none { it.second == SyntaxKind.Comment })
        val css = tokens(".card { background-color: #08f; margin: 12px; /* spacing */ }", "css")
        assertTrue(css.contains("background-color" to SyntaxKind.Property))
        assertTrue(css.contains("#08f" to SyntaxKind.Number))
        assertEquals(listOf("/* spacing */"), css.filter { it.second == SyntaxKind.Comment }.map { it.first })
    }
    @Test fun shellVariablesSqlKeywordsAndHtmlAttributesUseTheirLanguageRules() {
        val shell = tokens("echo \$HOME file#part # comment", "bash")
        assertTrue(shell.contains("\$HOME" to SyntaxKind.Variable))
        assertEquals(listOf("# comment"), shell.filter { it.second == SyntaxKind.Comment }.map { it.first })
        val sql = tokens("select count(*) from users -- query", "sql")
        assertTrue(sql.contains("select" to SyntaxKind.Keyword))
        assertTrue(sql.contains("count" to SyntaxKind.Function))
        assertTrue(sql.contains("-- query" to SyntaxKind.Comment))
        val html = tokens("<div class=\"card\">Hello</div><!-- note -->", "html")
        assertTrue(html.contains("<div" to SyntaxKind.Tag))
        assertTrue(html.contains("class" to SyntaxKind.Property))
        assertTrue(html.contains("<!-- note -->" to SyntaxKind.Comment))
    }
    @Test fun diffsDistinguishHeadersAdditionsAndRemovals() {
        val tokens = tokens("--- a/app.kt\n+++ b/app.kt\n@@ -1 +1 @@\n-old\n+new\n unchanged", "diff")
        assertEquals(listOf(SyntaxKind.Heading, SyntaxKind.Heading, SyntaxKind.Heading, SyntaxKind.Removed, SyntaxKind.Added), tokens.map { it.second })
    }
    @Test fun languageAliasesAndUnknownLanguagesPreserveSourceAndOrderedRanges() {
        for (language in listOf("kotlin", "kt", "kts")) {
            val code = "val answer: Widget = create(0xFF)"
            assertTrue(tokens(code, language).contains("Widget" to SyntaxKind.Type))
            assertTrue(tokens(code, language).contains("0xFF" to SyntaxKind.Number))
            val spans = syntaxTokens(code, language)
            assertTrue(spans.zipWithNext().all { (first, second) -> first.end <= second.start })
            assertTrue(spans.all { it.start >= 0 && it.end <= code.length && it.start < it.end })
        }
        assertTrue(syntaxTokens("# not necessarily a comment\nreturn 1", "unknown-language").isEmpty())
        assertTrue(syntaxTokens("plain text", "").isEmpty())
    }
}
