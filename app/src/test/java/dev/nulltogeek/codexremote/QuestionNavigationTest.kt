package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class QuestionNavigationTest {
    @Test fun tallQuestionsRevealTheirBeginningAndShortQuestionsNeedNoOffset() {
        assertEquals(1400, questionScrollOffset(2000, 600))
        assertEquals(0, questionScrollOffset(200, 600))
        assertEquals(0, questionScrollOffset(600, 600))
        assertEquals(1999, questionScrollOffset(2000, 0))
    }

    @Test fun onlyUserMessagesAppearNewestFirstAndRepeatedTextStaysSeparate() {
        val messages = listOf(TimelineItem("first", "userMessage", "Why?"),
            TimelineItem("command", "commandExecution", "Why?"),
            TimelineItem("answer", "agentMessage", "Why?"), TimelineItem("second", "userMessage", "Why?"))
        assertEquals(listOf("second", "first"), questionLinks(messages).map { it.id })
        assertEquals(3, questionScrollIndex(messages, "first"))
        assertEquals(0, questionScrollIndex(messages, "second"))
    }

    @Test fun indexUsesPresentedCommandsAndFinalChangesRatherThanRawHistory() {
        val state = ScreenState(timeline = listOf(TimelineItem("u", "userMessage", "Fix this", turnId = "t"),
            TimelineItem("r", "reasoning", "Hidden", turnId = "t"),
            TimelineItem("c", "commandExecution", "test", turnId = "t"),
            TimelineItem("a", "agentMessage", "Done", turnId = "t", phase = "final_answer"),
            TimelineItem("f", "fileChange", "", turnId = "t", changes = listOf(Change("app.kt", "+fixed")))))
        val rendered = conversationTimeline(state)
        assertEquals(listOf("u", "c", "a", "final-changes:t"), rendered.map { it.id })
        assertEquals(3, questionScrollIndex(rendered, "u"))
    }

    @Test fun newMessagesRecomputeIndexAndEarlierPagesKeepIdentity() {
        val question = TimelineItem("target", "userMessage", "Question")
        val answer = TimelineItem("a", "agentMessage", "Answer")
        assertEquals(1, questionScrollIndex(listOf(question, answer), "target"))
        assertEquals(2, questionScrollIndex(listOf(question, answer, answer.copy(id = "new")), "target"))
        assertEquals(1, questionScrollIndex(listOf(question.copy(id = "older"), question, answer), "target"))
    }

    @Test fun absentAndNonUserTargetsNeverProduceAnInvalidIndex() {
        val messages = listOf(TimelineItem("a", "agentMessage", "Answer"))
        assertNull(questionScrollIndex(messages, "missing"))
        assertNull(questionScrollIndex(messages, "a"))
        assertNull(questionScrollIndex(emptyList(), "a"))
    }

    @Test fun imageOnlyAndOtherAttachmentsHaveUsefulPreviews() {
        val messages = listOf(TimelineItem("image", "userMessage", " ", images = listOf(TimelineImage("/photo", "photo.jpg"))),
            TimelineItem("attachment", "userMessage", ""))
        assertEquals(listOf("Attached message", "photo.jpg"), questionLinks(messages).map { it.preview })
    }

    @Test fun searchIgnoresCaseAndPreviewsNormalizeLineBreaks() {
        val messages = listOf(TimelineItem("u", "userMessage", "  First\n\nquestion\twith spaces  "),
            TimelineItem("other", "userMessage", "Other"))
        assertEquals(listOf(QuestionLink("u", "First question with spaces")), questionLinks(messages, "  QUESTION WITH "))
        assertTrue(questionLinks(messages, "absent").isEmpty())
        assertEquals(2, questionLinks(messages, " ").size)
    }
}
