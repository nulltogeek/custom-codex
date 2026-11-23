package dev.nulltogeek.codexremote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFilesTest {
    @Test fun fileRequestsUseProjectIdentityAndRevisionWithoutAcceptingHostPaths() {
        val browser = FileBrowserState(Project("project", "Project", listOf("/private/project")),
            document = WorkspaceDocument("src/main.kt", "old", "sha-old", true), draft = "new\nسلام")
        assertTrue(browser.dirty)
        val request = workspaceRequest(browser, "write", "src/main.kt")
        assertEquals("project", request.text("projectId")); assertEquals("sha-old", request.text("revision"))
        assertEquals("new\nسلام", request.text("content")); assertFalse(request.containsKey("root"))
        assertFalse(browser.copy(draft = "old").dirty)
    }
    @Test fun fileRpcUsesAuthenticationAndPreservesDraftOnConflictWithoutFollowingRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val rpc = RpcClient(); rpc.configure("http://127.0.0.1:${server.port}", "s".repeat(43))
            val input = workspaceRequest(FileBrowserState(Project("p", "P", listOf("/root"))), "read", "src/main.kt")
            server.enqueue(MockResponse().setBody("""{"content":"hello","revision":"rev"}"""))
            assertEquals("hello", rpc.files(input).text("content"))
            val sent = server.takeRequest(); assertEquals("/v1/files", sent.path); assertEquals("POST", sent.method)
            assertEquals("Bearer " + "s".repeat(43), sent.getHeader("Authorization"))
            assertEquals("src/main.kt", WireJson.parseToJsonElement(sent.body.readUtf8()).jsonObject.text("path"))
            server.enqueue(MockResponse().setResponseCode(409).setBody("sensitive internal information"))
            val error = runCatching { rpc.files(input) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("changed on the server")); assertFalse(error.message!!.contains("sensitive"))
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/redirected"))
            assertTrue(runCatching { rpc.files(input) }.isFailure); assertEquals("/v1/files", server.takeRequest().path)
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { rpc.files(input) }.exceptionOrNull() is SessionExpiredException)
        }
    }
}
