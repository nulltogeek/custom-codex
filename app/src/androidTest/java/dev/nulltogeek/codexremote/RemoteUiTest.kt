package dev.nulltogeek.codexremote

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class RemoteUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun sample() = ScreenState(signedIn = true, username = "owner", connection = "Connected", host = "Linux workstation", theme = "dark",
        projects = (1..15).map { Project("p$it", "Project $it", listOf("/workspace/project-$it")) },
        chats = (1..100).map { Chat("c$it", "Build feature $it", "Review code and verify the Android changes", "/workspace/project-1", if (it == 1) "active" else "idle") })
    private fun render(state: androidx.compose.runtime.MutableState<ScreenState>, narrow: Boolean = false, fontScale: Float? = null) {
        compose.activityRule.scenario.onActivity { activity ->
            val vm = RemoteViewModel(activity.application)
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                    Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier) {
                        RemoteTheme(state.value.theme, state.value.accent, motion = false) { RemoteApp(state.value, vm) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        val dir = compose.activity.getExternalFilesDir(null)!!
        val capture = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            capture.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        capture.recycle()
    }
    @Test fun darkAndLightScreensAndProjects() {
        val state = mutableStateOf(sample()); render(state)
        compose.onNodeWithText("Your workspace").assertIsDisplayed()
        screenshot("dark-chats")
        compose.runOnIdle { state.value = state.value.copy(theme = "light", page = "projects") }
        compose.onNodeWithText("Project 1").assertIsDisplayed()
        screenshot("light-projects")
        compose.runOnIdle { state.value = state.value.copy(page = "settings") }
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
    }
    @Test fun commandOutputStartsCollapsedAndFinalDiffIsInspectable() {
        val state = mutableStateOf(sample().copy(selectedChat = Chat("c", "Android client", "", "/workspace", "active"),
            timeline = listOf(TimelineItem("user", "userMessage", "Build an Android client"), TimelineItem("agent", "agentMessage", "The native interface is ready."),
                TimelineItem("terminal", "commandExecution", "All checks passed\n".repeat(1000), "./gradlew test", "completed"))))
        render(state); screenshot("dark-conversation")
        compose.onAllNodesWithText("Show more output").assertCountEquals(0)
        compose.onNodeWithContentDescription("Expand COMMAND").assertIsDisplayed().performClick()
        compose.onNodeWithText("All checks passed", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse COMMAND").performClick()
        compose.onNodeWithText("All checks passed", substring = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(diff = Change("app/MainActivity.kt", (1..1200).joinToString("\n") { "+line $it" })) }
        compose.onNodeWithText("app/MainActivity.kt").assertIsDisplayed()
        screenshot("dark-diff")
        compose.runOnIdle { state.value = state.value.copy(theme = "light") }
        screenshot("light-diff")
    }
    @Test fun codeBlocksComposerControlsAndAttachmentChoicesAreVisible() {
        val state = mutableStateOf(sample().copy(selectedChat = Chat("code", "Formatted chat", "", "/workspace", "idle"),
            timeline = listOf(TimelineItem("a", "agentMessage", "## Example\n```python\nprint('hello')\n```"))))
        render(state)
        compose.onNodeWithText("Copy code").assertIsDisplayed()
        compose.onNodeWithText("python").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand code").performClick()
        compose.onNodeWithText("print('hello')").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse code").performClick()
        compose.onNodeWithText("print('hello')").assertDoesNotExist()
        compose.onNodeWithTag("chat-settings").performClick()
        compose.onNodeWithText("Mode").assertIsDisplayed()
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithContentDescription("Attach file or image").performClick()
        compose.onNodeWithText("Photo or image").assertIsDisplayed()
        compose.onNodeWithText("File").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        screenshot("formatted-code-composer")
    }
    @Test fun appUpdateControlsShowReleaseNotesDownloadProgressAndInstallAction() {
        val update = AppUpdate(BuildConfig.APPLICATION_ID, 5, "0.5.0", "a".repeat(64), 3 * 1024 * 1024, 30, "Update fixture release notes")
        val state = mutableStateOf(sample().copy(page = "settings", appUpdate = update, updateMessage = "Version 0.5.0 is available"))
        render(state)
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download 0.5.0 · 3.0 MB").assertIsDisplayed()
        compose.onNodeWithText("Update fixture release notes").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(updateDownloading = true, updateProgress = 50) }
        compose.onNodeWithText("50% · Keep the app open during download").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(updateDownloading = false, updateApk = "/fixture.apk") }
        compose.onNodeWithText("Install update").assertIsDisplayed()
        screenshot("app-update-settings")
    }
    @Test fun compactComposerKeepsControlsReachableOnNarrowScreens() {
        val state = mutableStateOf(sample().copy(selectedChat = Chat("compact", "Compact conversation", "", "/workspace", "idle"),
            selectedModel = "gpt", selectedEffort = "high", models = listOf(ModelChoice("gpt", "gpt-6.1-sol", listOf("low", "high"), "high")),
            timeline = listOf(TimelineItem("a", "agentMessage", "More room for your conversation."))))
        render(state, narrow = true)
        val density = compose.activity.resources.displayMetrics.density
        val height = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("Empty composer occupies $height dp", height <= 128f)
        compose.onNodeWithText("Codex").assertIsDisplayed()
        compose.onNodeWithTag("chat-settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Mode").assertIsDisplayed()
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Model: gpt-6.1-sol").assertIsDisplayed().performClick()
        compose.onNodeWithText("Host default").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithText("Ask for approvals").assertIsDisplayed()
        compose.onNodeWithText("Full access").assertIsDisplayed()
        compose.onNodeWithText("Reasoning effort").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("High").performScrollTo().assertIsDisplayed()
        screenshot("compact-chat-settings")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Attach file or image").assertIsDisplayed().performClick()
        compose.onNodeWithText("Photo or image").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithContentDescription("Message input").assertIsDisplayed()
        screenshot("compact-conversation")
        compose.onNodeWithContentDescription("Message input").performClick()
        compose.waitUntil(30000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        compose.onNodeWithTag("chat-settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        screenshot("compact-keyboard")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        // A long draft must stay bounded and retain its send control.
        compose.runOnIdle { state.value = state.value.copy(draft = (1..12).joinToString("\n") { "Draft line $it" }) }
        compose.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()
        compose.waitUntil(10000) { compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.height / density <= 220f }
        File(compose.activity.getExternalFilesDir(null), "compact-layout-metrics.json").writeText(buildJsonObject {
            put("screenWidthDp", 320); put("emptyComposerHeightDp", height)
            put("longDraftComposerHeightDp", compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.height / density)
        }.toString())
    }

    @Test fun stopControlSitsBesideSendOnlyWhileWorkIsActive() {
        val state = mutableStateOf(sample().copy(selectedChat = Chat("stop", "Running chat", "", "", "active")))
        render(state, narrow = true)
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed().assertIsEnabled()
        val stop = compose.onNodeWithTag("stop-work").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithTag("send-message").fetchSemanticsNode().boundsInRoot
        assertEquals(stop.bottom, send.bottom, 1f)
        assertEquals(stop.right, send.left, 1f)
        compose.runOnIdle { state.value = state.value.copy(connection = "Offline") }
        compose.onNodeWithContentDescription("Stop").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(selectedChat = state.value.selectedChat?.copy(status = "idle")) }
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
    }
    @Test fun compactProjectRowsKeepAllFolderPathsAvailable() {
        val roots = listOf("/workspace/a/very/long/project/path", "/workspace/second-folder")
        val state = mutableStateOf(sample().copy(page = "projects", projects = listOf(Project("p1", "Project 1", roots))))
        render(state, narrow = true, fontScale = 1.3f)
        compose.onNodeWithText("2 folders", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Folders for Project 1").performClick()
        compose.onAllNodesWithText(roots[0]).onLast().assertIsDisplayed()
        compose.onNodeWithText(roots[1]).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("New chat in Project 1").assertIsDisplayed()
        screenshot("compact-projects-large-font")
        compose.runOnIdle { state.value = state.value.copy(selectedChat = Chat("c", "Conversation", "", "/workspace", "idle"), accessMode = "full") }
        compose.onNodeWithTag("chat-settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Reasoning effort").performScrollTo().assertIsDisplayed()
    }
    @Test fun imagePreviewsAndUploadCancelAreVisibleInComposer() {
        val dir = compose.activity.cacheDir
        val image = File(dir, "preview-fixture.jpg")
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }; bitmap.recycle()
        val state = mutableStateOf(sample().copy(selectedChat = Chat("preview", "Image draft", "", "/workspace", "idle"), uploading = true,
            attachments = listOf(Attachment("a", "photo.jpg", 100, "image/jpeg", jsonObject("type" to "localImage", "path" to "/uploaded/photo.jpg"), image.absolutePath, uploading = true, uploadProgress = 45),
                Attachment("b", "notes.txt", 100, "text/plain", jsonObject("type" to "text", "text" to "attached notes")))))
        render(state, narrow = true)
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Preview of photo.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Preview of photo.jpg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel upload photo.jpg").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Uploading 45%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        screenshot("attachment-preview-upload")
        compose.runOnIdle { state.value = state.value.copy(uploading = false, attachments = state.value.attachments.map { it.copy(uploading = false) }) }
        compose.onNodeWithText("2 uploaded · Ready to send").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove photo.jpg").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun failedAttachmentKeepsItsPreviewAndBlocksSendUntilRemoved() {
        val image = File(compose.activity.cacheDir, "failed-upload-fixture.jpg")
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }; bitmap.recycle()
        val file = Attachment("failed", "photo.jpg", 100, "image/jpeg", buildJsonObject {}, image.absolutePath,
            uploadProgress = 60, uploadError = "Network timed out")
        val state = mutableStateOf(sample().copy(selectedChat = Chat("failed-upload", "Draft", "", "/workspace"), draft = "Look at this image", attachments = listOf(file)))
        render(state, narrow = true)
        compose.onNodeWithTag("attachment-strip").assertIsDisplayed()
        compose.onNodeWithText("Upload failed").assertIsDisplayed()
        compose.onNodeWithText("Network timed out").assertIsDisplayed()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Preview of photo.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Preview of photo.jpg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove photo.jpg").assertIsEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(attachments = emptyList()) }
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun olderHistoryLoadsOnScrollAndPrependingKeepsTheVisibleMessage() {
        val state = mutableStateOf(sample().copy(selectedChat = Chat("scroll", "Scroll history", "", "", "idle"), hiddenOlderCount = 60,
            timeline = (61..100).map { TimelineItem("m$it", "agentMessage", "Message $it") }))
        var calls = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ChatTimeline(state.value, Modifier.fillMaxHeight(), loadOlder = { calls++; state.value = state.value.copy(olderLoading = true) }, showDiff = {}, approval = {}) }
        } }
        compose.onNodeWithText("Message 100").assertIsDisplayed()
        compose.onNodeWithTag("jump-to-latest").assertDoesNotExist()
        assertEquals(0, calls)
        compose.onNodeWithTag("history-list").performScrollToIndex(38)
        compose.waitUntil(10000) { calls == 1 }
        compose.onNodeWithText("Message 62").assertIsDisplayed()
        compose.onNodeWithTag("jump-to-latest").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
        compose.onNodeWithText("Jump to latest").assertDoesNotExist()
        val before = compose.onNodeWithText("Message 62").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state.value = state.value.copy(timeline = (1..60).map { TimelineItem("m$it", "agentMessage", "Message $it") } + state.value.timeline, olderLoading = false, hiddenOlderCount = 0) }
        compose.onNodeWithText("Message 62").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("Message 62").fetchSemanticsNode().boundsInRoot.top, 2f)
        assertEquals(1, calls)
        // Streaming must not jump to the end while reading older history.
        compose.runOnIdle { state.value = state.value.copy(timeline = state.value.timeline.dropLast(1) + state.value.timeline.last().copy(text = "Message 100 streaming")) }
        compose.onNodeWithText("Message 62").assertIsDisplayed()
        screenshot("history-scroll-anchor")
        compose.onNodeWithTag("jump-to-latest").performClick()
        compose.onNodeWithText("Message 100 streaming").assertIsDisplayed()
        compose.onNodeWithTag("jump-to-latest").assertDoesNotExist()
        // Returning to the bottom resumes following new messages.
        compose.runOnIdle { state.value = state.value.copy(timeline = state.value.timeline + TimelineItem("m101", "agentMessage", "Message 101")) }
        compose.onNodeWithText("Message 101").assertIsDisplayed()
        compose.onNodeWithTag("jump-to-latest").assertDoesNotExist()
    }

    @Test fun conversationShowsFinalChangesOnlyAfterWorkFinishes() {
        val state = mutableStateOf(ScreenState(selectedChat = Chat("quiet", "Quiet chat", "", "", "active"), activeTurnId = "t",
            timeline = listOf(
                TimelineItem("r", "reasoning", "Hidden reasoning", turnId = "t", turnStatus = "inProgress"),
                TimelineItem("c", "commandExecution", "Hidden output", turnId = "t", turnStatus = "inProgress"),
                TimelineItem("p", "agentMessage", "Hidden progress", turnId = "t", phase = "commentary", turnStatus = "inProgress"),
                TimelineItem("f", "fileChange", "Intermediate edits", changes = listOf(Change("app.kt", "+fixed")), turnId = "t", turnStatus = "inProgress"),
                TimelineItem("a", "agentMessage", "Finished the update", turnId = "t", phase = "final_answer", turnStatus = "inProgress"))))
        var opened: Change? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ChatTimeline(state.value, Modifier.fillMaxHeight(), {}, { opened = it }, {}) }
        } }
        listOf("Hidden reasoning", "Hidden output", "Hidden progress", "Intermediate edits", "app.kt").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Finished the update").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(activeTurnId = "", selectedChat = state.value.selectedChat?.copy(status = "idle"),
            timeline = state.value.timeline.map { it.copy(turnStatus = "completed") }) }
        compose.onNodeWithText("1 changed file").assertIsDisplayed()
        compose.onNodeWithText("app.kt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand changes").performClick()
        compose.onNodeWithText("app.kt").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Change("app.kt", "+fixed"), opened) }
        compose.onNodeWithContentDescription("Collapse changes").performClick()
        compose.onNodeWithText("app.kt").assertDoesNotExist()
    }

    @Test fun messageCopyButtonsCopyFullOriginalTextIncludingMarkdown() {
        val original = "**Full reply**\n" + "Long reply content. ".repeat(400)
        val state = mutableStateOf(sample().copy(selectedChat = Chat("copy", "Copy messages", "", ""),
            timeline = listOf(TimelineItem("user-copy", "userMessage", "My original message"),
                TimelineItem("codex-copy", "agentMessage", original))))
        render(state)
        compose.onNodeWithTag("history-list").performScrollToIndex(0)
        compose.onNodeWithTag("copy-message:codex-copy").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(original, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("history-list").performScrollToIndex(1)
        compose.onNodeWithTag("copy-message:user-copy").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("My original message", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun heartbeatOpensRunningAndFinishedCommandsWithoutReasoning() {
        val state = mutableStateOf(sample().copy(selectedChat = Chat("pulse", "Activity", "", "", "active"),
            timeline = listOf(TimelineItem("done", "commandExecution", "Completed output", "git status", "completed"),
                TimelineItem("running", "commandExecution", "Live output", "gradle test", "inProgress", turnStatus = "inProgress"),
                TimelineItem("reason", "reasoning", "Private reasoning"))))
        render(state)
        compose.onNodeWithTag("work-heartbeat").assertIsDisplayed().performClick()
        compose.onNodeWithText("Command activity").assertIsDisplayed()
        compose.onNodeWithText("1 running · 1 finished").assertIsDisplayed()
        compose.onNodeWithText("Private reasoning").assertDoesNotExist()
        val sheet = hasAnyAncestor(hasTestTag("activity-sheet"))
        compose.onNode(hasText("gradle test") and sheet).assertIsDisplayed().performClick()
        compose.onNode(hasText("Live output") and sheet).assertIsDisplayed()
        compose.onNode(hasText("git status") and sheet).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close command activity").performClick()
        compose.onNodeWithTag("activity-sheet").assertDoesNotExist()
    }

    @Test fun cataloguePrefetchesAtTheEndAndErrorsWaitForRetry() {
        val state = mutableStateOf(sample().copy(activeCursor = "page2"))
        var calls = 0
        val vm = RemoteViewModel(compose.activity.application)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ChatsScreen(state.value, vm, approval = {}, loadMore = {
                calls++; state.value = state.value.copy(catalogError = "Could not load more chats. Tap to retry.")
            }) }
        } }
        assertEquals(0, calls)
        compose.onNodeWithTag("catalog-list").performScrollToIndex(101)
        compose.waitUntil(10000) { calls == 1 }
        compose.onNodeWithText("Could not load more chats. Tap to retry.").performScrollTo().assertIsDisplayed()
        compose.waitForIdle(); assertEquals(1, calls)
        compose.onNodeWithText("Could not load more chats. Tap to retry.").performClick()
        assertEquals(2, calls)
    }

    @Test fun catalogueAppendsOnePageWithoutJumpingOrEagerlyLoadingAllPages() {
        val state = mutableStateOf(sample().copy(chats = sample().chats.take(20), activeCursor = "page2"))
        var calls = 0
        val vm = RemoteViewModel(compose.activity.application)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ChatsScreen(state.value, vm, approval = {}, loadMore = {
                calls++
                state.value = state.value.copy(catalogLoading = true)
            }) }
        } }
        compose.waitForIdle(); assertEquals(0, calls)
        compose.onNodeWithTag("catalog-list").performScrollToIndex(20)
        compose.waitUntil(10000) { calls == 1 }
        compose.onNodeWithText("Build feature 18").assertIsDisplayed()
        val top = compose.onNodeWithText("Build feature 18").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state.value = state.value.copy(chats = sample().chats.take(40), activeCursor = "page3", catalogLoading = false) }
        compose.waitForIdle(); assertEquals(1, calls)
        assertEquals(top, compose.onNodeWithText("Build feature 18").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithTag("catalog-list").performScrollToIndex(40)
        compose.waitUntil(10000) { calls == 2 }
        compose.runOnIdle { state.value = state.value.copy(chats = sample().chats.take(60), activeCursor = null, catalogLoading = false) }
        compose.waitForIdle(); assertEquals(2, calls)
    }

    @Test fun filteredAgentCatalogueTraversesHiddenPagesAndArchivedAgents() {
        val state = mutableStateOf(sample().copy(chats = sample().chats.take(20), projectFilter = "p2", activeCursor = "page2", archivedCursor = "archive2"))
        val requests = mutableListOf<Boolean>()
        val vm = RemoteViewModel(compose.activity.application)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ChatsScreen(state.value, vm, approval = {}, loadMore = { archived ->
                requests += archived
                state.value = if (!archived) state.value.copy(activeCursor = null) else state.value.copy(archivedCursor = null,
                    chats = state.value.chats + Chat("older-agent", "Older agent result", "", "/workspace/project-2", archived = true, source = "subAgent"))
            }) }
        } }
        compose.waitUntil(10000) { requests == listOf(false) }
        compose.onNodeWithText("Agent chats").performClick()
        compose.waitUntil(10000) { requests == listOf(false, true) }
        compose.onNodeWithText("Older agent result").assertIsDisplayed()
        compose.waitForIdle(); assertEquals(listOf(false, true), requests)
    }

    @Test fun chatsPullToRefreshShowsProgressKeepsFiltersAndBlocksRepeatedPulls() {
        val state = mutableStateOf(sample().copy(projectFilter = "p1", chats = sample().chats.take(20)))
        var calls = 0
        val vm = RemoteViewModel(compose.activity.application)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ChatsScreen(state.value, vm, approval = {}, refresh = {
                calls++; state.value = state.value.copy(refreshing = true, loading = true)
            }) }
        } }
        compose.onNodeWithTag("catalog-list").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 500) }
        compose.waitUntil(10000) { calls == 1 }
        compose.onNodeWithContentDescription("Refreshing list").assertIsDisplayed()
        compose.onNodeWithText("Project 1", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("catalog-list").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 500) }
        compose.waitForIdle(); assertEquals(1, calls)
        compose.runOnIdle { state.value = state.value.copy(refreshing = false, loading = false,
            chats = listOf(state.value.chats.first().copy(title = "Updated after pull")) + state.value.chats.drop(1)) }
        compose.onNodeWithText("Updated after pull").assertIsDisplayed()
        assertEquals("p1", state.value.projectFilter)
        assertEquals(20, state.value.chats.size)
    }

    @Test fun projectsAndEmptyListsSupportPullRefreshButOfflinePullsDoNotSendRequests() {
        val state = mutableStateOf(sample().copy(projects = emptyList(), connection = "Offline"))
        var calls = 0
        val vm = RemoteViewModel(compose.activity.application)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { ProjectScreen(state.value, vm, refresh = {
                calls++; state.value = state.value.copy(refreshing = true)
            }) }
        } }
        compose.onNodeWithTag("project-list").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 500) }
        compose.waitForIdle(); assertEquals(0, calls)
        compose.runOnIdle { state.value = state.value.copy(connection = "Connected") }
        compose.onNodeWithTag("project-list").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 500) }
        compose.waitUntil(10000) { calls == 1 }
        compose.onNodeWithContentDescription("Refreshing list").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(refreshing = false, projects = listOf(Project("new", "New project after pull", listOf("/new")))) }
        compose.onNodeWithText("New project after pull").assertIsDisplayed()
    }

    @Test fun nativeAppUpdateDownloadsAndVerifiesInstalledSigningIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loginFile = File(context.filesDir, "integration-login.json")
        assumeTrue("Requires the temporary update host", BuildConfig.DEBUG && loginFile.exists())
        val credentials = WireJson.parseToJsonElement(loginFile.readText()).jsonObject
        val endpoint = InstrumentationRegistry.getArguments().getString("codexEndpoint", "http://127.0.0.1:8787")!!
        val rpc = RpcClient()
        try {
            rpc.login(endpoint, credentials.text("username"), credentials.text("password"))
            val update = parseAppUpdate(rpc.get("/v1/app-update?channel=debug"), context.packageName, android.os.Build.VERSION.SDK_INT)
            assertEquals(BuildConfig.VERSION_CODE.toLong(), update.versionCode)
            val file = rpc.downloadAppUpdate(update, File(context.cacheDir, "apk-updates")) {}
            verifyUpdateApk(context, file, update)
            val intent = apkInstallerIntent(context, file)
            assertEquals("content", intent.data?.scheme); assertEquals(context.packageName + ".apkprovider", intent.data?.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(intent.data!!)?.use { assertEquals(file.length(), it.available().toLong()) }
            assertTrue(runCatching { verifyUpdateApk(context, file, update.copy(versionCode = update.versionCode + 1)) }.isFailure)
            if (InstrumentationRegistry.getArguments().getString("codexInstallerSmoke") == "true") {
                assertTrue(context.packageManager.canRequestPackageInstalls())
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                fun hasInstallButton(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.text?.toString()?.trim()?.equals("Install", ignoreCase = true) == true && node.isEnabled && node.isClickable) return true
                    return (0 until node.childCount).any { hasInstallButton(node.getChild(it)) }
                }
                try {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { context.startActivity(intent) }
                    withTimeout(120000) { while (true) {
                        val root = automation.rootInActiveWindow
                        if (root?.packageName?.toString()?.contains("packageinstaller") == true && hasInstallButton(root)) break
                        kotlinx.coroutines.delay(100)
                    } }
                } finally {
                    val capture = automation.takeScreenshot()
                    File(context.getExternalFilesDir(null), "android-update-installer.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    capture.recycle()
                    automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }
        } finally { rpc.logout(); rpc.close() }
    }
    @Test fun keystoreSessionAndDraftsSurviveStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocalStore(context); val token = "local-test-token-".repeat(3)
        store.signIn("https://example.test", LoginSession(token, "owner", System.currentTimeMillis() + 60000)); store.draft("test-chat", "unsent draft")
        val reopened = LocalStore(context)
        assertEquals(token, reopened.session()); assertEquals("owner", reopened.username); assertEquals("unsent draft", reopened.draft("test-chat"))
        // A late send acknowledgement must not erase a newer draft, even across store recreation.
        store.draft("test-chat", "next prompt typed while sending")
        assertFalse(reopened.clearSentDraft("test-chat", "unsent draft"))
        assertEquals("next prompt typed while sending", LocalStore(context).draft("test-chat"))
        assertTrue(reopened.clearSentDraft("test-chat", "next prompt typed while sending"))
        assertEquals("", LocalStore(context).draft("test-chat"))
        val file = File(context.applicationInfo.dataDir, "shared_prefs/codex_remote.xml")
        Thread.sleep(100)
        assertFalse(file.readText().contains(token))
        store.signOut(); assertEquals("", LocalStore(context).session())
        store.signIn("https://example.test", LoginSession(token, "owner", System.currentTimeMillis() - 1))
        assertEquals("", LocalStore(context).session()); store.signOut()
    }
    @Test fun releaseRenderingFrameMetrics() {
        val state = mutableStateOf(sample()); render(state)
        val frames = CopyOnWriteArrayList<Long>(); val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        compose.activityRule.scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        repeat(8) { compose.onRoot().performTouchInput { swipeUp(durationMillis = 400) }; compose.waitForIdle() }
        compose.runOnIdle { state.value = state.value.copy(selectedChat = Chat("live", "Live work", "", "/workspace", "active"),
            timeline = (1..20).map { TimelineItem("item$it", "agentMessage", "Message $it\n".repeat(30)) }) }
        repeat(40) {
            compose.runOnIdle { val items = state.value.timeline; state.value = state.value.copy(timeline = items.dropLast(1) + items.last().copy(text = items.last().text + "stream ")) }
            Thread.sleep(50)
        }
        compose.activityRule.scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        thread.quitSafely()
        val refresh = compose.activity.display?.refreshRate ?: 60f
        val budget = (1_000_000_000.0 / refresh).toLong(); val inside = frames.count { it <= budget }
        val report = buildJsonObject { put("buildType", BuildConfig.BUILD_TYPE); put("frames", frames.size); put("refreshHz", refresh)
            put("frameBudgetNanos", budget); put("withinBudgetPercent", if (frames.isEmpty()) 0.0 else 100.0 * inside / frames.size)
            put("targetPercent", 95); put("device", android.os.Build.MODEL) }
        File(compose.activity.getExternalFilesDir(null), "frame-metrics.json").writeText(report.toString())
        assertTrue("No frame metrics collected", frames.isNotEmpty())
    }
    @Test fun nativeNetworkingJoinsTheRealBridge() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loginFile = File(context.filesDir, "integration-login.json")
        assumeTrue("Live local integration is debug-only and requires the test host", BuildConfig.DEBUG && loginFile.exists())
        val credentials = WireJson.parseToJsonElement(loginFile.readText()).jsonObject
        val endpoint = InstrumentationRegistry.getArguments().getString("codexEndpoint", "http://127.0.0.1:8787")!!
        val expectedMode = InstrumentationRegistry.getArguments().getString("codexRuntimeMode")
        val rpc = RpcClient()
        try {
            compose.onNodeWithText("Server URL").performTextReplacement(endpoint)
            compose.onNodeWithText("Username").performTextReplacement(credentials.text("username"))
            compose.onNodeWithText("Password").performTextReplacement(credentials.text("password"))
            compose.onNodeWithText("Sign in").performClick()
            compose.waitUntil(60000) { compose.onAllNodesWithText("15 projects", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Projects").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Saved on", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Chats").performClick()
            compose.onNodeWithText("Your workspace").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Your workspace").assertIsDisplayed()
            // Signing out must preserve the Activity's foreground state for the next login.
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            compose.onNodeWithText("Confirm sign out").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Server URL").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Server URL").performTextReplacement(endpoint)
            compose.onNodeWithText("Username").performTextReplacement(credentials.text("username"))
            compose.onNodeWithText("Password").performTextReplacement(credentials.text("password"))
            compose.onNodeWithText("Sign in").performClick()
            compose.waitUntil(60000) { compose.onAllNodesWithText("15 projects", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rpc.login(endpoint, credentials.text("username"), credentials.text("password"))
            val host = rpc.get("/v1/host"); assertTrue(host.text("backendPid").isNotEmpty())
            if (expectedMode != null) assertEquals(expectedMode, host.text("runtimeMode"))
            val snapshot = rpc.connect(); assertTrue(snapshot["ready"]?.jsonPrimitive?.boolean == true)
            val projects = rpc.get("/v1/projects").array("projects"); assertEquals(15, projects.size)
            val created = rpc.call("thread/start", buildJsonObject { put("ephemeral", true) }).obj("thread")
            val read = rpc.call("thread/read", buildJsonObject { put("threadId", created.text("id")); put("includeTurns", false) }).obj("thread")
            assertEquals(created.text("id"), read.text("id"))
            withTimeout(10000) { while (true) { val event = rpc.events.receive(); if (event.text("method") == "thread/started" && event.obj("params").obj("thread").text("id") == created.text("id")) break } }
        } finally { rpc.close(); LocalStore(context).signOut() }
    }
}
