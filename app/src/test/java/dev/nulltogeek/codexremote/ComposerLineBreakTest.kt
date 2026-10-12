package dev.nulltogeek.codexremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ComposerLineBreakTest {
    @Test fun newlineAtCursorKeepsSurroundingTextAndMovesTheCaret() {
        val result = insertDraftLineBreak(TextFieldValue("FirstSecond", TextRange(5)))
        assertEquals("First\nSecond", result.text)
        assertEquals(TextRange(6), result.selection)
    }
    @Test fun newlineReplacesEitherDirectionOfSelectionAndEndsComposition() {
        for (selection in listOf(TextRange(4, 8), TextRange(8, 4))) {
            val result = insertDraftLineBreak(TextFieldValue("KeepDROPtail", selection, composition = TextRange(4, 8)))
            assertEquals("Keep\ntail", result.text)
            assertEquals(TextRange(5), result.selection)
            assertNull(result.composition)
        }
    }
    @Test fun emptyAndMultilineUnicodeDraftsKeepTheirContent() {
        val empty = insertDraftLineBreak(TextFieldValue(""))
        assertEquals("\n", empty.text); assertEquals(TextRange(1), empty.selection)
        val text = "سلام 👋\nSecond"
        val result = insertDraftLineBreak(TextFieldValue(text, TextRange(text.length)))
        assertEquals(text + "\n", result.text); assertEquals(TextRange(text.length + 1), result.selection)
    }
}
