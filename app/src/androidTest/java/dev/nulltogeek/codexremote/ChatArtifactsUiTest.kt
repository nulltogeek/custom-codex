package dev.nulltogeek.codexremote

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatArtifactsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun assistantImageDownloadsWithChatIdentityAndOpensPreview() {
        MockWebServer().use { server ->
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes))); server.start()
            val rpc = RpcClient().apply { configure(server.url("/").toString().trimEnd('/'), "private-session") }
            val item = TimelineItem("reply", "agentMessage", "![Chart](/workspace/chart.png)", turnId = "turn")
            compose.activityRule.scenario.onActivity { it.setContent { RemoteTheme("light", motion = false) { Surface {
                MessageItem(item, {}, { error("Not an upload") }, loadArtifact = { message, artifact -> rpc.downloadChatArtifact("chat", message, artifact) })
            } } } }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Preview of Chart").fetchSemanticsNodes().isNotEmpty() }
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/chat-file", request.path); assertEquals("Bearer private-session", request.getHeader("Authorization"))
            val input = WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("chat", input.text("threadId")); assertEquals("reply", input.text("itemId")); assertEquals("turn", input.text("turnId"))
            compose.onNodeWithText("Save").assertIsEnabled()
            compose.onNodeWithContentDescription("Preview of Chart").performClick()
            compose.onNodeWithTag("image-preview-dialog").assertIsDisplayed()
            compose.onNodeWithContentDescription("Image Chart").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close image preview").performClick()
        }
    }

    @Test fun nativeImageAndFileCardsRemainVisibleWithRetryForMissingFiles() {
        val native = parseItem(buildJsonObject { put("type", "imageGeneration"); put("id", "native"); put("status", "completed"); put("savedPath", "/tmp/generated.png") })
        val state = ScreenState(timeline = listOf(native, TimelineItem("report", "agentMessage", "[Report](/tmp/report.pdf)")))
        compose.activityRule.scenario.onActivity { it.setContent { RemoteTheme("light", motion = false) { Surface {
            ChatTimeline(state, loadOlder = {}, showDiff = {}, approval = {}, loadArtifact = { _, _ -> error("Fixture output missing") })
        } } } }
        compose.onNodeWithTag("chat-artifact:Generated image").performScrollTo().assertIsDisplayed()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-artifact:Report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open file").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Fixture output missing").fetchSemanticsNodes().size == 2 }
    }

    @Test fun artifactDownloadRejectsRedirectsAndOversizeResponses() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val rpc = RpcClient().apply { configure(server.url("/").toString().trimEnd('/'), "secret") }
            val item = TimelineItem("reply", "agentMessage", "", turnId = "turn")
            val artifact = ChatArtifact("File", "/workspace/file.pdf", false)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/leak")))
            assertTrue(runCatching { rpc.downloadChatArtifact("chat", item, artifact) }.isFailure)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", 9 * 1024 * 1024))
            assertTrue(runCatching { rpc.downloadChatArtifact("chat", item, artifact) }.isFailure)
            assertTrue(runCatching { rpc.downloadChatArtifact("chat", item, ChatArtifact("Image", "http://example.com/a.png", true)) }.isFailure)
        }
    }
}
