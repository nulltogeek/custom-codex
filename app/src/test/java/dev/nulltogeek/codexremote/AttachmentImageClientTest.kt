package dev.nulltogeek.codexremote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class AttachmentImageClientTest {
    @Test fun embeddedImageHistoryDecodesWithoutMakingANetworkRequest() = runBlocking {
        val rpc = RpcClient()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertArrayEquals(bytes, rpc.downloadAttachmentImage("data:image/png;base64,$encoded"))
        assertTrue(runCatching { rpc.downloadAttachmentImage("data:text/html;base64,$encoded") }.isFailure)
        assertTrue(runCatching { rpc.downloadAttachmentImage("data:image/png;base64,") }.isFailure)
    }
    @Test fun imageDownloadKeepsSessionOnConfiguredHostAndEncodesUploadPath() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(bytes)))
            val rpc = RpcClient().apply { configure("http://127.0.0.1:${server.port}", "fixture-session") }
            val path = "/private uploads/photo #1.png"
            assertArrayEquals(bytes, rpc.downloadAttachmentImage(path))
            val request = server.takeRequest()
            assertEquals("/v1/attachments/image", request.requestUrl?.encodedPath)
            assertEquals(path, request.requestUrl?.queryParameter("path"))
            assertEquals("Bearer fixture-session", request.getHeader("Authorization"))
            assertEquals("GET", request.method)
        }
    }

    @Test fun imageDownloadRejectsRedirectsWithoutContactingDestination() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { destination ->
            server.start(); destination.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/photo.png")))
            val rpc = RpcClient().apply { configure("http://127.0.0.1:${server.port}", "fixture-session") }
            assertTrue(runCatching { rpc.downloadAttachmentImage("/uploads/photo.png") }.exceptionOrNull() is IOException)
            assertEquals(0, destination.requestCount)
            assertEquals(1, server.requestCount)
        } }
    }

    @Test fun invalidImageResponsesAndExpiredSessionsAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val rpc = RpcClient().apply { configure("http://127.0.0.1:${server.port}", "fixture-session") }
            server.enqueue(MockResponse().setResponseCode(401).setBody("private error body"))
            assertTrue(runCatching { rpc.downloadAttachmentImage("/uploads/photo.png") }.exceptionOrNull() is SessionExpiredException)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("private file"))
            assertEquals("Image preview unavailable", runCatching { rpc.downloadAttachmentImage("/uploads/photo.png") }.exceptionOrNull()?.message)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setHeader("Content-Length", 21 * 1024 * 1024))
            assertEquals("Image preview unavailable", runCatching { rpc.downloadAttachmentImage("/uploads/photo.png") }.exceptionOrNull()?.message)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(okio.Buffer().write(ByteArray(20 * 1024 * 1024 + 1)), 65536))
            assertTrue(runCatching { rpc.downloadAttachmentImage("/uploads/photo.png") }.isFailure)
            assertTrue(runCatching { rpc.downloadAttachmentImage("https://another-host/photo.png") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun dismissingPreviewCanCancelPendingDownload() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val rpc = RpcClient().apply { configure("http://127.0.0.1:${server.port}", "fixture-session") }
            val download = launch(Dispatchers.IO) { rpc.downloadAttachmentImage("/uploads/photo.png") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { download.cancelAndJoin() }
            assertTrue(download.isCancelled)
        }
    }
}
