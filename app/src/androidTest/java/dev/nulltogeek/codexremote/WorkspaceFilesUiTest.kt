package dev.nulltogeek.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceFilesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val project = Project("project", "Editor project", listOf("/private/project"))
    @Composable private fun Frame(dark: Boolean = false, content: @Composable () -> Unit) {
        RemoteTheme(if (dark) "dark" else "light", motion = false) {
            Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().systemBarsPadding()) { content() } }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$name.png")).use { it.readBytes() }
    }
    private class TestApplication(base: Context) : Application() {
        private val name = "workspace-ui-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, name).apply { mkdirs() }
        init { attachBaseContext(object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(this@TestApplication.name, mode)
        }) }
        fun cleanup() { getSharedPreferences("codex_remote", MODE_PRIVATE).edit().clear().commit(); directory.deleteRecursively() }
    }
    private class Host : java.io.Closeable {
        val server = MockWebServer()
        val content = AtomicReference("fun greeting() = \"Hello\"\n")
        val rejectSave = AtomicBoolean(false)
        val writes = AtomicReference<JsonObject>()
        val delayRead = AtomicBoolean(false)
        val readStarted = java.util.concurrent.CountDownLatch(1)
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.path) {
                        "/v1/login" -> """{"token":"${"a".repeat(43)}","username":"owner","expiresAt":${System.currentTimeMillis() + 600000}}"""
                        "/v1/host" -> """{"user":{"id":"owner","username":"owner","role":"admin"},"capabilities":{"projectFiles":true}}"""
                        "/v1/files" -> {
                            val input = WireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                            when (input.text("action")) {
                                "read" -> buildJsonObject { put("path", input.text("path")); put("content", content.get()); put("revision", "revision"); put("writable", true) }.toString()
                                "write" -> {
                                    writes.set(input)
                                    if (rejectSave.get()) return MockResponse().setResponseCode(409).setBody("{}")
                                    content.set(input.text("content")); """{"revision":"saved-revision"}"""
                                }
                                "search" -> """{"entries":[{"name":"main.kt","path":"src/main.kt","kind":"file","size":30}]}"""
                                else -> if (input.text("path").isEmpty()) """{"entries":[{"name":"src","path":"src","kind":"directory","size":0}]}"""
                                    else """{"entries":[{"name":"main.kt","path":"src/main.kt","kind":"file","size":30}]}"""
                            }
                        }
                        else -> "{}"
                    }
                    val response = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                    if (request.path == "/v1/files" && body.contains("revision") && delayRead.get()) {
                        readStarted.countDown(); response.setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return response
                }
            }
            server.start()
        }
        override fun close() = server.close()
    }
    private fun runEditor(host: Host, test: (RemoteViewModel) -> Unit) {
        val app = TestApplication(compose.activity); val models = ViewModelStore()
        try {
            val vm = RemoteViewModel(app); models.put("files", vm)
            vm.signIn("http://127.0.0.1:${host.server.port}", "owner", "fixture password")
            compose.waitUntil(10000) { vm.state.value.signedIn && vm.state.value.projectFilesAvailable }
            compose.activityRule.scenario.onActivity { it.setContent {
                val state by vm.state.collectAsState()
                Frame {
                    val browser = state.fileBrowser
                    if (browser != null) WorkspaceFilesScreen(browser, true, vm::closeFiles, vm::browseFiles, vm::readFile, vm::fileQuery, vm::fileDraft, vm::saveFile)
                    else ProjectScreen(state.copy(projects = listOf(project), connection = "Connected"), vm)
                }
            } }
            compose.onNodeWithContentDescription("Folders for Editor project").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.loading == false }
            test(vm)
        } finally { compose.runOnIdle { models.clear() }; app.cleanup() }
    }
    @Test fun projectFolderOpensBrowserAndEditorSavesThroughAuthenticatedHttp() {
        Host().use { host -> runEditor(host) { vm ->
            compose.onNodeWithTag("file:src").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.entries?.any { it.path == "src/main.kt" } == true }
            compose.onNodeWithTag("file:src/main.kt").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.document != null }
            compose.onNodeWithTag("file-editor").performTextReplacement("fun greeting() = \"Edited on Android\"\n")
            compose.onNodeWithTag("save-file").assertIsEnabled().performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.message == "Saved on server" }
            assertEquals("fun greeting() = \"Edited on Android\"\n", host.content.get())
            assertEquals("src/main.kt", host.writes.get().text("path")); assertEquals("revision", host.writes.get().text("revision"))
            compose.onNodeWithTag("save-file").assertIsNotEnabled()
            capture("workspace-files-phone")
            compose.onNodeWithTag("files-back").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.document == null }
            compose.onNodeWithTag("file-search").performTextInput("main")
            compose.onNodeWithContentDescription("Search project files").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.searching == true && vm.state.value.fileBrowser?.loading == false }
            compose.onNodeWithTag("file:src/main.kt").assertIsDisplayed()
        } }
    }
    @Test fun conflictKeepsEditsAndReloadRequiresExplicitDiscard() {
        Host().use { host -> host.rejectSave.set(true); runEditor(host) { vm ->
            vm.readFile("src/main.kt"); compose.waitUntil(10000) { vm.state.value.fileBrowser?.document != null }
            compose.onNodeWithTag("file-editor").performTextReplacement("my unsaved edit")
            compose.onNodeWithTag("save-file").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.error != null }
            compose.onNodeWithTag("file-editor").assertTextContains("my unsaved edit")
            compose.onNodeWithTag("files-error").assertTextContains("changed on the server", substring = true)
            compose.onNodeWithContentDescription("Close project files").performClick()
            compose.onNodeWithText("Keep editing").performClick()
            assertTrue(vm.state.value.fileBrowser!!.dirty)
            host.content.set("new server version")
            compose.onNodeWithContentDescription("Reload file").performClick()
            compose.onNodeWithText("Discard edits").performClick()
            compose.waitUntil(10000) { vm.state.value.fileBrowser?.draft == "new server version" }
            compose.onNodeWithTag("file-editor").assertTextContains("new server version")
        } }
    }
    @Test fun signOutCancelsPendingFileReadAndNextLoginCannotRestoreItsPanel() {
        Host().use { host -> runEditor(host) { vm ->
            host.delayRead.set(true); vm.readFile("src/main.kt")
            assertTrue(host.readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            vm.signOut(); compose.waitUntil(10000) { !vm.state.value.signedIn }
            assertNull(vm.state.value.fileBrowser)
            vm.signIn("http://127.0.0.1:${host.server.port}", "owner", "fixture password")
            compose.waitUntil(10000) { vm.state.value.signedIn }
            assertNull(vm.state.value.fileBrowser)
        } }
    }
    @Test fun unsavedCloseRequiresDiscardAndReadOnlyFilesDisableSaving() {
        val browser = mutableStateOf(FileBrowserState(project.copy(roots = project.roots + "/private/second"),
            document = WorkspaceDocument("readme.md", "original", "r", true), draft = "unsaved"))
        var chosenRoot: Int? = null
        compose.activityRule.scenario.onActivity { it.setContent { Frame(dark = true) {
            WorkspaceFilesScreen(browser.value, true, {}, { _, root, _, _ -> chosenRoot = root }, {}, {}, {}, {})
        } } }
        // On a phone the editor hides the browser; closing must still protect its draft.
        compose.onNodeWithContentDescription("Close project files").performClick()
        compose.onNodeWithText("Discard unsaved edits?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        assertNull(chosenRoot)
        compose.runOnIdle { browser.value = browser.value.copy(document = browser.value.document!!.copy(writable = false), draft = "original") }
        compose.onNodeWithText("Read-only").assertIsDisplayed(); compose.onNodeWithTag("save-file").assertIsNotEnabled()
    }
    @Test fun wideBrowserShowsFilesBesideEditorAndRootChangesRequireDiscard() {
        val browser = FileBrowserState(project.copy(roots = project.roots + "/private/second"),
            entries = listOf(WorkspaceEntry("main.kt", "src/main.kt", "file", 20)),
            document = WorkspaceDocument("src/main.kt", "fun main() = println(\"Hello\")", "r", true), draft = "fun main() = println(\"Edited\")")
        var chosenRoot: Int? = null
        compose.activityRule.scenario.onActivity { it.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f)) { Frame(dark = true) {
                WorkspaceFilesScreen(browser, true, {}, { _, root, _, _ -> chosenRoot = root }, {}, {}, {}, {})
            } }
        } }
        compose.onNodeWithTag("file:src/main.kt").assertIsDisplayed(); compose.onNodeWithTag("file-editor").assertIsDisplayed()
        capture("workspace-files-wide")
        compose.onNodeWithText("second").performClick()
        compose.onNodeWithText("Discard unsaved edits?").assertIsDisplayed(); assertNull(chosenRoot)
        compose.onNodeWithText("Discard edits").performClick(); assertEquals(1, chosenRoot)
    }
}
