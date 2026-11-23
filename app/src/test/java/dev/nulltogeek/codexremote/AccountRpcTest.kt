package dev.nulltogeek.codexremote

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AccountRpcTest {
    @Test fun cancellingOldAccountHttpWorkReleasesTheWaitBeforeTheNextAccountIsConfigured() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val rpc = RpcClient(); val endpoint = "http://127.0.0.1:${server.port}"
            rpc.configure(endpoint, "a".repeat(43))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            var delivered = false
            val old = launch(Dispatchers.IO) { rpc.accountRequest("/v1/admin/users", "GET"); delivered = true }
            val oldRequest = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(oldRequest); assertEquals("Bearer " + "a".repeat(43), oldRequest!!.getHeader("Authorization"))
            withTimeout(2000) { old.cancelAndJoin() }
            assertFalse(delivered)
            rpc.configure(endpoint, "b".repeat(43))
            server.enqueue(MockResponse().setBody("""{"users":[],"signupEnabled":false}"""))
            assertEquals("false", rpc.accountRequest("/v1/admin/users", "GET").text("signupEnabled"))
            assertEquals("Bearer " + "b".repeat(43), server.takeRequest().getHeader("Authorization"))
            rpc.close()
        }
    }

    @Test fun signupUsesPasswordJsonWithoutAnExistingSessionOrRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session = "s".repeat(43)
            server.enqueue(MockResponse().setBody("""{"token":"$session","username":"member","expiresAt":${System.currentTimeMillis() + 60000}}"""))
            val rpc = RpcClient()
            val result = rpc.signup("http://127.0.0.1:${server.port}", "member", "private password  ")
            assertEquals("member", result.username)
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("/v1/signup", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals("private password  ", WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject.text("password"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/other"))
            assertTrue(runCatching { rpc.signup("http://127.0.0.1:${server.port}", "member", "private password") }.exceptionOrNull() is IOException)
            assertEquals("/v1/signup", server.takeRequest().path); assertEquals(2, server.requestCount)
        }
    }

    @Test fun registrationErrorsAreActionableAndNeverDisplayResponseBodies() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val rpc = RpcClient(); val endpoint = "http://127.0.0.1:${server.port}"
            server.enqueue(MockResponse().setResponseCode(403).setBody("sensitive server details"))
            val closed = runCatching { rpc.signup(endpoint, "member", "private password") }.exceptionOrNull()
            assertTrue(closed!!.message!!.contains("registrations are currently closed"))
            assertFalse(closed.message!!.contains("sensitive"))
            server.enqueue(MockResponse().setResponseCode(409))
            assertTrue(runCatching { rpc.signup(endpoint, "member", "private password") }.exceptionOrNull()!!.message!!.contains("already taken"))
        }
    }

    @Test fun accountManagementUsesAuthenticatedBoundedPathsAndPatchWithoutFollowingRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val rpc = RpcClient(); val token = "s".repeat(43)
            rpc.configure("http://127.0.0.1:${server.port}", token)
            server.enqueue(MockResponse().setBody("""{"user":{"id":"u-1"}}"""))
            rpc.accountRequest("/v1/admin/users/u-1", "PATCH", buildJsonObject { put("disabled", true) })
            val request = server.takeRequest()
            assertEquals("PATCH", request.method); assertEquals("/v1/admin/users/u-1", request.path)
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertEquals("true", WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject.text("disabled"))
            assertTrue(runCatching { rpc.accountRequest("/v1/admin/users/../settings", "PATCH") }.exceptionOrNull() is IllegalArgumentException)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/other"))
            assertTrue(runCatching { rpc.accountRequest("/v1/admin/users", "GET") }.exceptionOrNull() is IOException)
            assertEquals("/v1/admin/users", server.takeRequest().path)
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { rpc.accountRequest("/v1/admin/users", "GET") }.exceptionOrNull() is SessionExpiredException)
        }
    }
}
