package dev.nulltogeek.codexremote

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AccountStorageIsolationTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val name = "account-test-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, name).apply { mkdirs() }
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(this.name, mode)
        fun cleanup() { getSharedPreferences("", MODE_PRIVATE).edit().clear().commit(); directory.deleteRecursively() }
    }

    @Test fun legacyHistoryAndDraftsAreClaimedOnlyByThePreviouslySavedAccount() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val prefs = context.getSharedPreferences("codex_remote", Context.MODE_PRIVATE)
            prefs.edit().putString("endpoint", "https://owner.example").putString("username", "nulltogeek")
                .putString("draft:same-chat-id", "owner's private draft").putString("mode:same-chat-id", "chat")
                .putString("attachments:same-chat-id", "[]").commit()
            File(context.filesDir, "history.json").writeText("""{"ownerOnly":"private history"}""")
            val store = LocalStore(context)
            assertEquals("private history", store.cache().text("ownerOnly"))
            assertEquals("owner's private draft", store.draft("same-chat-id"))
            assertFalse(File(context.filesDir, "history.json").exists())
            assertFalse(prefs.contains("draft:same-chat-id"))
            prefs.edit().putString("username", "new-user").commit()
            val other = LocalStore(context)
            assertTrue(other.cache().isEmpty()); assertEquals("", other.draft("same-chat-id"))
            assertEquals("codex", other.chatMode("same-chat-id"))
            other.draft("same-chat-id", "other draft")
            other.saveCache(buildJsonObject { put("otherOnly", "other history") })
            prefs.edit().putString("username", "nulltogeek").commit()
            assertEquals("owner's private draft", store.draft("same-chat-id"))
            assertEquals("chat", store.chatMode("same-chat-id"))
            assertEquals("private history", store.cache().text("ownerOnly"))
            assertFalse(store.cache().containsKey("otherOnly"))
            prefs.edit().putString("endpoint", "https://another.example").commit()
            assertTrue(store.cache().isEmpty()); assertEquals("", store.draft("same-chat-id"))
        } finally { context.cleanup() }
    }

    @Test fun capturedCacheIdentityCannotWriteIntoTheNextAccount() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val prefs = context.getSharedPreferences("codex_remote", Context.MODE_PRIVATE)
            prefs.edit().putString("endpoint", "https://owner.example").putString("username", "owner").commit()
            val store = LocalStore(context)
            val oldAccount = store.accountKey
            prefs.edit().putString("username", "other").commit()
            store.saveCache(buildJsonObject { put("lateResult", "owner data") }, oldAccount)
            assertTrue(store.cache().isEmpty())
            assertEquals("owner data", store.cache(oldAccount).text("lateResult"))
        } finally { context.cleanup() }
    }
}
