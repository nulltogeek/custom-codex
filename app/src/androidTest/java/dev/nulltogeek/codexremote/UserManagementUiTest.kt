package dev.nulltogeek.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UserManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val memberId = "11111111-1111-1111-1111-111111111111"
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

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
    private inner class Host(private val initialMember: Boolean = true, private val rejectFirstCreate: Boolean = false,
        private val failReadAfterCreate: Boolean = false, holdInitialRead: Boolean = false, holdCreate: Boolean = false) : Closeable {
        val deletes = AtomicInteger()
        val creates = AtomicInteger()
        val reads = AtomicInteger()
        val initialReadStarted = CountDownLatch(1)
        val releaseInitialRead = CountDownLatch(if (holdInitialRead) 1 else 0)
        val createStarted = CountDownLatch(1)
        val releaseCreate = CountDownLatch(if (holdCreate) 1 else 0)
        @Volatile var created = false
        @Volatile var memberName = "alice"
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
                        val input = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                        var offset = 0
                        while (offset < input.size) { val count = reader.read(input, offset, input.size - offset); if (count < 0) break; offset += count }
                        var status = 200
                        val body = when {
                            path == "/v1/login" -> """{"token":"${"a".repeat(43)}","username":"nulltogeek","expiresAt":${System.currentTimeMillis() + 600000}}"""
                            headers["authorization"] != "Bearer " + "a".repeat(43) -> { status = 401; "{}" }
                            path == "/v1/host" -> """{"user":{"id":"owner","username":"nulltogeek","role":"admin"},"capabilities":{"userManagement":true,"userDeletion":true}}"""
                            path == "/v1/admin/users" && method == "GET" -> {
                                val member = if ((initialMember && !removed) || created) """,{"id":"$memberId","username":"$memberName","role":"user","disabled":false}""" else ""
                                val snapshot = """{"users":[{"id":"owner","username":"nulltogeek","role":"admin","disabled":false}$member],"signupEnabled":true}"""
                                if (reads.incrementAndGet() == 1) { initialReadStarted.countDown(); releaseInitialRead.await(10, TimeUnit.SECONDS) }
                                if (failReadAfterCreate && created) { status = 503; "{}" } else snapshot
                            }
                            path == "/v1/admin/users" && method == "POST" -> {
                                if (creates.incrementAndGet() == 1 && rejectFirstCreate) { status = 409; """{"error":"This username is unavailable"}""" }
                                else {
                                    createStarted.countDown(); releaseCreate.await(10, TimeUnit.SECONDS)
                                    memberName = WireJson.parseToJsonElement(String(input)).jsonObject.text("username")
                                    created = true
                                    """{"user":{"id":"$memberId","username":"$memberName","role":"user","disabled":false}}"""
                                }
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
        override fun close() { releaseInitialRead.countDown(); releaseCreate.countDown(); server.close(); workers.shutdownNow(); accept.join(1000) }
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

    @Test fun createKeepsErrorsAndDetailsVisibleThenShowsConfirmedUserEvenWhenRefreshFails() {
        Host(initialMember = false, rejectFirstCreate = true, failReadAfterCreate = true, holdCreate = true).use { host ->
            val app = TestApplication(compose.activity); val viewModels = ViewModelStore()
            try {
                val vm = RemoteViewModel(app); viewModels.put("create", vm)
                vm.signIn(host.endpoint, "nulltogeek", "fixture password")
                compose.waitUntil(10000) { vm.state.value.signedIn && vm.state.value.userManagementAvailable }
                vm.page("users")
                compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                    val state by vm.state.collectAsState()
                    RemoteTheme("dark", motion = false) { RemoteApp(state.copy(connection = "Connected", motion = false), vm) }
                } }
                compose.waitUntil(10000) { vm.state.value.adminUsers.size == 1 && !vm.state.value.usersLoading }
                compose.onNodeWithTag("header-new-chat").assertDoesNotExist()
                compose.onNodeWithTag("create-user").performScrollTo().performClick()
                compose.onNodeWithTag("managed-username").performTextInput("reserved.name")
                compose.onNodeWithTag("managed-password").performScrollTo().performTextInput("long fixture password")
                compose.onNodeWithTag("managed-confirm-password").performScrollTo().performTextInput("long fixture password")
                compose.onNodeWithTag("submit-managed-user").performClick()
                compose.waitUntil(10000) { vm.state.value.userManagementMessage.contains("unavailable") }
                compose.onNodeWithTag("user-password-error").performScrollTo().assertTextContains("deleted account", substring = true)
                capture("user-creation-error")
                compose.onNodeWithTag("managed-username").assertTextContains("reserved.name")
                compose.onNodeWithTag("managed-username").performScrollTo().performTextReplacement("fresh.member")
                compose.onNodeWithTag("submit-managed-user").performClick()
                assertTrue(host.createStarted.await(5, TimeUnit.SECONDS))
                compose.onNodeWithTag("user-password-dialog").assertExists()
                compose.onNodeWithTag("submit-managed-user").assertIsNotEnabled()
                host.releaseCreate.countDown()
                compose.waitUntil(10000) { !vm.state.value.userMutationPending && vm.state.value.adminUsers.size == 2 }
                compose.onNodeWithTag("user-password-dialog").assertDoesNotExist()
                compose.onNodeWithText("fresh.member").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("user-management-message").performScrollTo().assertTextContains("User created.", substring = true)
                capture("created-user-visible")
                val before = host.reads.get()
                compose.onNodeWithTag("header-refresh-users").performClick()
                compose.waitUntil(10000) { host.reads.get() > before && !vm.state.value.usersLoading }
                compose.runOnIdle { assertEquals(2, host.creates.get()) }
            } finally { compose.runOnIdle { viewModels.clear() }; app.cleanup() }
        }
    }

    @Test fun olderUserListCannotOverwriteAnAccountCreatedWhileItWasLoading() {
        Host(initialMember = false, holdInitialRead = true).use { host ->
            val app = TestApplication(compose.activity); val viewModels = ViewModelStore()
            try {
                val vm = RemoteViewModel(app); viewModels.put("stale-list", vm)
                vm.signIn(host.endpoint, "nulltogeek", "fixture password")
                compose.waitUntil(10000) { vm.state.value.signedIn && vm.state.value.userManagementAvailable }
                vm.refreshUsers(); assertTrue(host.initialReadStarted.await(5, TimeUnit.SECONDS))
                vm.createUser("fresh.member", "long fixture password")
                compose.waitUntil(10000) { !vm.state.value.userMutationPending && vm.state.value.adminUsers.size == 2 }
                host.releaseInitialRead.countDown()
                compose.waitUntil(10000) { !vm.state.value.usersLoading }
                compose.runOnIdle { assertEquals(setOf("nulltogeek", "fresh.member"), vm.state.value.adminUsers.map { it.username }.toSet()) }
            } finally { compose.runOnIdle { viewModels.clear() }; app.cleanup() }
        }
    }
}
