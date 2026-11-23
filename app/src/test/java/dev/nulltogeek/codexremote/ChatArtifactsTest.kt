package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatArtifactsTest {
    @Test fun localMarkdownImagesAndFilesBecomeCardsIncludingSpacesAndParentheses() {
        val files = chatArtifacts("![Preview](sandbox:/mnt/data/image.png)\n[Report](</tmp/My report.pdf>)\n[Code](src/main(test).kt:12)\n[Site](https://example.com)")
        assertEquals(3, files.size); assertTrue(files[0].image); assertFalse(files[1].image)
        assertEquals("/tmp/My report.pdf", files[1].source); assertEquals("src/main(test).kt:12", files[2].source)
        assertEquals("My report.pdf", artifactFilename("sandbox:/tmp/My%20report.pdf"))
    }
    @Test fun fencedExamplesAndUnsafeSchemesDoNotBecomeDownloads() {
        assertTrue(chatArtifacts("```md\n[Secret](/private/file.txt)\n```\n[Bad](javascript:bad)\n[Intent](intent:bad)").isEmpty())
        assertEquals(1, chatArtifacts("![Image](https://example.com/a.png)").size)
        assertEquals("Dockerfile", chatArtifacts("[Build file](Dockerfile)").single().source)
    }
    @Test fun nativeGeneratedImageSurvivesTimelineFilteringAndKeepsItsSavedPath() {
        val item = parseItem(buildJsonObject { put("type", "imageGeneration"); put("id", "image"); put("status", "completed"); put("savedPath", "/workspace/generated.png") }, "turn", "completed")
        assertEquals("/workspace/generated.png", item.artifacts.single().source)
        assertEquals(listOf(item), conversationTimeline(ScreenState(timeline = listOf(item))))
    }
}
