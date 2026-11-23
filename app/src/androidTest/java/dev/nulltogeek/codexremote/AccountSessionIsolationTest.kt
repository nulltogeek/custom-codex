package dev.nulltogeek.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** A 401 while another account operation is waiting must not deadlock the next login. */
class AccountSessionIsolationTest {
    private class TestApplication(base: Context) : Application() {
        private val prefsName = "session-isolation-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, prefsName).apply { mkdirs() }
        init { attachBaseContext(object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefsName, mode)
        }) }
        fun cleanup() { getSharedPreferences("codex_remote", MODE_PRIVATE).edit().clear().commit(); directory.deleteRecursively() }
    }

    private class Host : Closeable {
        val blockedMutation = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        private val workers = Executors.newCachedThreadPool()
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val endpoint get() = "http://127.0.0.1:${server.localPort}"
        private val accept = thread(isDaemon = true, name = "account-session-fixture") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                workers.execute {
                    try { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val line = reader.readLine().orEmpty().split(' ')
                        val method = line.firstOrNull().orEmpty(); val path = line.getOrNull(1).orEmpty()
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
                            path == "/v1/login" -> {
                                val username = WireJson.parseToJsonElement(String(input)).jsonObject.text("username")
                                val token = (if (username == "owner") "a" else "b").repeat(43)
                                """{"token":"$token","username":"$username","expiresAt":${System.currentTimeMillis() + 60000}}"""
                            }
                            path == "/v1/host" -> {
                                val owner = headers["authorization"] == "Bearer " + "a".repeat(43)
                                """{"name":"Fixture","user":{"id":"${if (owner) "owner" else "member-id"}","username":"${if (owner) "owner" else "member"}","role":"${if (owner) "admin" else "user"}"},"capabilities":{"userManagement":$owner,"registration":true}}"""
                            }
                            path == "/v1/admin/users" && method == "GET" -> {
                                check(blockedMutation.await(5, TimeUnit.SECONDS))
                                status = 401; """{"error":"expired"}"""
                            }
                            path == "/v1/admin/users" && method == "POST" -> {
                                blockedMutation.countDown(); releaseMutation.await(10, TimeUnit.SECONDS)
                                """{"user":{"id":"late-user","username":"late","role":"user","disabled":false}}"""
                            }
                            else -> "{}"
                        }.toByteArray()
                        val output = it.getOutputStream()
                        output.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Unauthorized"}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body); output.flush()
                    } } catch (_: Exception) { }
                }
            }
        }
        override fun close() { releaseMutation.countDown(); server.close(); workers.shutdownNow(); accept.join(1000) }
    }

    private fun until(predicate: () -> Boolean) {
        val limit = System.currentTimeMillis() + 5000
        while (!predicate()) { if (System.currentTimeMillis() > limit) fail("Account operation did not complete"); Thread.sleep(20) }
    }

    @Test fun expiredAdminSessionCancelsInFlightWorkAndNextLoginHasNoPreviousAccountData() {
        val app = TestApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val viewModels = ViewModelStore()
        Host().use { host ->
            try {
                app.getSharedPreferences("codex_remote", Context.MODE_PRIVATE).edit().putString("endpoint", host.endpoint).putString("username", "owner").commit()
                val store = LocalStore(app)
                store.saveCache(buildJsonObject {
                    put("chats", WireJson.parseToJsonElement("""[{"id":"private-chat","name":"Owner private chat"}]"""))
                })
                val vm = RemoteViewModel(app)
                viewModels.put("account-test", vm)
                vm.signIn(host.endpoint, "owner", "owner test password")
                until { vm.state.value.signedIn && vm.state.value.userManagementAvailable && vm.state.value.chats.any { it.id == "private-chat" } }
                assertTrue(vm.state.value.chats.any { it.id == "private-chat" })
                vm.refreshUsers()
                vm.createUser("late", "late test password")
                assertTrue(host.blockedMutation.await(5, TimeUnit.SECONDS))
                until { !vm.state.value.signedIn }
                assertTrue(vm.state.value.chats.isEmpty()); assertTrue(vm.state.value.adminUsers.isEmpty())
                vm.signIn(host.endpoint, "member", "member test password")
                until { vm.state.value.signedIn && vm.state.value.username == "member" }
                host.releaseMutation.countDown()
                assertFalse(vm.state.value.userManagementAvailable)
                assertTrue(vm.state.value.chats.isEmpty()); assertTrue(vm.state.value.adminUsers.isEmpty())
                assertFalse(vm.state.value.userMutationPending); assertFalse(vm.state.value.usersLoading)
                assertTrue(LocalStore(app).cache().isEmpty())
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
                app.cleanup()
            }
        }
    }
}
