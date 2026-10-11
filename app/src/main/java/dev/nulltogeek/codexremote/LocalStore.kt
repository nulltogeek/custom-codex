package dev.nulltogeek.codexremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

class LocalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("codex_remote", Context.MODE_PRIVATE)
    val endpoint get() = prefs.getString("endpoint", "https://codex.geeksun.ir").orEmpty()
    val username get() = prefs.getString("username", "nulltogeek").orEmpty()
    val theme get() = prefs.getString("theme", "system").orEmpty()
    val accent get() = prefs.getString("accent", "exchange").orEmpty()
    val motion get() = prefs.getBoolean("motion", true)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("codex_remote_pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("codex_remote_pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun session(): String { return try {
        if (prefs.getLong("sessionExpiresAt", 0) <= System.currentTimeMillis()) return ""
        val stored = prefs.getString("session", null) ?: return ""
        val parts = stored.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { "" } }
    fun signIn(endpoint: String, session: LoginSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(session.token.toByteArray()), Base64.NO_WRAP)
        prefs.edit { putString("endpoint", endpoint); putString("username", session.username)
            putString("session", encrypted); putLong("sessionExpiresAt", session.expiresAt); remove("token") }
    }
    fun signOut() { prefs.edit { remove("token"); remove("session"); remove("sessionExpiresAt") } }
    fun theme(value: String) { prefs.edit { putString("theme", value) } }
    fun accent(value: String) { prefs.edit { putString("accent", value) } }
    fun motion(value: Boolean) { prefs.edit { putBoolean("motion", value) } }
    fun chatMode(id: String): String = prefs.getString("mode:$id", "codex").orEmpty()
    fun accessMode(id: String): String = prefs.getString("access:$id", "host").orEmpty()
    fun chatOptions(id: String, mode: String, access: String) { prefs.edit { putString("mode:$id", mode); putString("access:$id", access) } }
    fun attachments(id: String): List<Attachment> = try {
        WireJson.parseToJsonElement(prefs.getString("attachments:$id", "[]").orEmpty()).jsonArray.map { parseAttachment(it.jsonObject) }
    } catch (_: Exception) { emptyList() }
    fun attachments(id: String, value: List<Attachment>) { prefs.edit { putString("attachments:$id", JsonArray(value.map { it.toJson() }).toString()) } }
    fun draft(id: String): String = synchronized(prefs) { prefs.getString("draft:$id", "").orEmpty() }
    fun draft(id: String, text: String) { synchronized(prefs) { prefs.edit { putString("draft:$id", text) } } }
    fun clearSentDraft(id: String, sentText: String): Boolean = synchronized(prefs) {
        // An acknowledgement may arrive after the user has already edited the next prompt.
        if (prefs.getString("draft:$id", "").orEmpty() != sentText) false
        else { prefs.edit { putString("draft:$id", "") }; true }
    }
    @Synchronized fun saveCache(value: JsonObject) {
        val text = value.toString(); if (text.length > 8 * 1024 * 1024) return
        val temp = java.io.File(context.filesDir, "history.tmp"); temp.writeText(text)
        temp.renameTo(java.io.File(context.filesDir, "history.json"))
    }
    @Synchronized fun cache(): JsonObject = try { WireJson.parseToJsonElement(java.io.File(context.filesDir, "history.json").readText()).jsonObject }
        catch (_: Exception) { buildJsonObject {} }
}
