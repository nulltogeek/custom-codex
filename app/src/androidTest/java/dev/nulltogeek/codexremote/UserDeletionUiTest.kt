package dev.nulltogeek.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UserDeletionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val memberId = "11111111-1111-1111-1111-111111111111"

    private class TestApplication(base: Context) : Application() {
        private val key = "delete-user-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, key).apply { mkdirs() }
        init { attachBaseContext(object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(key, mode)
        }) }
        fun cleanup() { getSharedPreferences("codex_remote", MODE_PRIVATE).edit().clear().commit(); directory.deleteRecursively() }
    }

    /** Disposable account fixture: the first delete fails, the second succeeds. */
    private inner class Host : Closeable {
        val deletes = AtomicInteger()
        @Volatile var removed = false
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val endpoint get() = "http://127.0.0.1:${server.localPort}"
        private val accept = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                workers.execute {
                    try { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty().split(' ')
                        val method = request.firstOrNull(); val path = request.getOrNull(1)
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = reader.readLine().orEmpty()
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        repeat(headers["content-length"]?.toIntOrNull() ?: 0) { reader.read() }
                        var status = 200
                        val body = when {
                            path == "/v1/login" -> """{"token":"${"a".repeat(43)}","username":"nulltogeek","expiresAt":${System.currentTimeMillis() + 600000}}"""
                            headers["authorization"] != "Bearer " + "a".repeat(43) -> { status = 401; "{}" }
                            path == "/v1/host" -> """{"user":{"id":"owner","username":"nulltogeek","role":"admin"},"capabilities":{"userManagement":true,"userDeletion":true}}"""
                            path == "/v1/admin/users" && method == "GET" -> {
                                val member = if (removed) "" else """,{"id":"$memberId","username":"alice","role":"user","disabled":false}"""
                                """{"users":[{"id":"owner","username":"nulltogeek","role":"admin","disabled":false}$member],"signupEnabled":true}"""
                            }
                            path == "/v1/admin/users/$memberId" && method == "DELETE" -> {
                                if (deletes.incrementAndGet() == 1) { status = 503; "{}" }
                                else { removed = true; """{"ok":true}""" }
                            }
                            else -> { status = 404; "{}" }
                        }.toByteArray()
                        val output = it.getOutputStream()
                        output.write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body); output.flush()
                    } } catch (_: Exception) { }
                }
            }
        }
        override fun close() { server.close(); workers.shutdownNow(); accept.join(1000) }
    }

    @Test fun deletionRequiresConfirmationProtectsOwnerAndRecoversFromFailure() {
        Host().use { host ->
            val app = TestApplication(compose.activity)
            val viewModels = ViewModelStore()
            try {
                val vm = RemoteViewModel(app); viewModels.put("delete", vm)
                vm.signIn(host.endpoint, "nulltogeek", "fixture password")
                compose.waitUntil(10000) { vm.state.value.signedIn && vm.state.value.userDeletionAvailable }
                compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                    val state by vm.state.collectAsState()
                    RemoteTheme("light", motion = false) { Surface { ManageUsersScreen(state.copy(connection = "Connected"), vm) } }
                } }
                compose.waitUntil(10000) { vm.state.value.adminUsers.size == 2 }
                compose.onNodeWithContentDescription("Manage nulltogeek").performClick()
                compose.onNodeWithText("Delete user").assertDoesNotExist()
                compose.onNodeWithText("Change your password in Settings").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.onNodeWithContentDescription("Manage alice").performScrollTo().performClick()
                compose.onNodeWithTag("delete-user:$memberId").performClick()
                compose.onNodeWithText("Delete alice?").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                compose.runOnIdle { assertEquals(0, host.deletes.get()) }
                compose.onNodeWithContentDescription("Manage alice").performScrollTo().performClick()
                compose.onNodeWithTag("delete-user:$memberId").performClick()
                compose.onNodeWithTag("confirm-delete-user").performClick()
                compose.waitUntil(10000) { !vm.state.value.userMutationPending && vm.state.value.userManagementMessage.contains("503") }
                compose.onNodeWithTag("managed-user:$memberId").assertExists()
                compose.onNodeWithContentDescription("Manage alice").performScrollTo().performClick()
                compose.onNodeWithTag("delete-user:$memberId").performClick()
                compose.onNodeWithTag("confirm-delete-user").performClick()
                compose.waitUntil(10000) { !vm.state.value.userMutationPending && vm.state.value.adminUsers.size == 1 }
                compose.onNodeWithTag("managed-user:$memberId").assertDoesNotExist()
                compose.runOnIdle { assertEquals(2, host.deletes.get()); assertTrue(host.removed) }
            } finally {
                compose.runOnIdle { viewModels.clear() }; app.cleanup()
            }
        }
    }
}
