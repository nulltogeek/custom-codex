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
    init { migrateLegacyStorage() }
    val endpoint get() = prefs.getString("endpoint", "https://codex.geeksun.ir").orEmpty()
    val username get() = prefs.getString("username", "nulltogeek").orEmpty()
    val theme get() = prefs.getString("theme", "system").orEmpty()
    val accent get() = prefs.getString("accent", "exchange").orEmpty()
    val taskNotifications get() = prefs.getBoolean("taskNotifications", false)
    val motion get() = prefs.getBoolean("motion", true)
    val loadCommandOutput get() = prefs.getBoolean("loadCommandOutput", true)
    internal val accountKey get() = accountStorageKey(endpoint, username)
    private fun accountEntry(kind: String, id: String, account: String = accountKey) = "account:$account:$kind:$id"
    // The legacy cache belongs to the account saved before this version was installed.
    // Claim it once before a new login can change that identity.
    @android.annotation.SuppressLint("UseKtx")
    private fun migrateLegacyStorage() = synchronized(prefs) {
        if (prefs.getBoolean("accountStorageMigrated", false)) return@synchronized
        val owner = accountKey
        val old = java.io.File(context.filesDir, "history.json")
        val target = java.io.File(context.filesDir, "history-$owner.json")
        if (old.exists()) {
            if (!target.exists()) check(old.renameTo(target)) { "Could not migrate saved history" }
            else check(old.delete()) { "Could not remove legacy history" }
        }
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (listOf("draft:", "attachments:", "mode:", "access:").any(key::startsWith) && value is String) {
                val scoped = "account:$owner:$key"
                if (!prefs.contains(scoped)) editor.putString(scoped, value)
                editor.remove(key)
            }
        }
        check(editor.putBoolean("accountStorageMigrated", true).commit()) { "Could not migrate saved drafts" }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("codex_remote_pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("codex_remote_pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    internal val sessionIdentity get() = prefs.getString("session", "").orEmpty()
    internal val sessionValid get() = prefs.getLong("sessionExpiresAt", 0) > System.currentTimeMillis()
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
    fun taskNotifications(value: Boolean) { prefs.edit { putBoolean("taskNotifications", value) } }
    fun notifiedTasks(endpoint: String, username: String): List<String> = try {
        WireJson.parseToJsonElement(prefs.getString("notified:" + favoriteKey(endpoint, username), "[]").orEmpty()).jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) { emptyList() }
    @android.annotation.SuppressLint("UseKtx")
    fun notifiedTasks(endpoint: String, username: String, keys: List<String>) {
        check(prefs.edit().putString("notified:" + favoriteKey(endpoint, username), JsonArray(keys.takeLast(256).map(::JsonPrimitive)).toString()).commit())
    }
    fun motion(value: Boolean) { prefs.edit { putBoolean("motion", value) } }
    fun loadCommandOutput(value: Boolean) { prefs.edit { putBoolean("loadCommandOutput", value) } }
    fun queue(endpoint: String, username: String): QueueSnapshot = try {
        parseQueue(WireJson.parseToJsonElement(prefs.getString("queue:" + favoriteKey(endpoint, username), "{}").orEmpty()).jsonObject)
    } catch (_: Exception) { QueueSnapshot() }
    // KTX edit returns Unit; the sender must check the durable commit result.
    @android.annotation.SuppressLint("UseKtx")
    fun queue(endpoint: String, username: String, snapshot: QueueSnapshot) {
        val text = queueJson(snapshot).toString()
        require(text.length <= 8 * 1024 * 1024) { "Queued attachments are too large. Send fewer files per queue." }
        check(prefs.edit().putString("queue:" + favoriteKey(endpoint, username), text).commit()) { "Could not save the message queue" }
    }
    private fun favoriteKey(endpoint: String, username: String): String = "favorites:" +
        java.security.MessageDigest.getInstance("SHA-256").digest((endpoint.trimEnd('/') + "\n" + username).toByteArray())
            .joinToString("") { "%02x".format(it) }
    fun favorites(endpoint: String, username: String): Pair<List<Chat>, Set<String>> = try {
        val raw = WireJson.parseToJsonElement(prefs.getString(favoriteKey(endpoint, username), "{}").orEmpty()).jsonObject
        raw.array("chats").map { parseChat(it.jsonObject, it.jsonObject["archived"]?.jsonPrimitive?.booleanOrNull == true) }
            .map { it.copy(status = "notLoaded") } to raw.array("projects").map { it.jsonPrimitive.content }.toSet()
    } catch (_: Exception) { emptyList<Chat>() to emptySet<String>() }
    fun favorites(endpoint: String, username: String, chats: List<Chat>, projects: Set<String>) {
        prefs.edit { putString(favoriteKey(endpoint, username), buildJsonObject {
            put("chats", JsonArray(chats.map { chat -> buildJsonObject {
                put("id", chat.id); put("name", chat.title); put("preview", chat.preview); put("cwd", chat.cwd)
                put("archived", chat.archived); put("isPinned", chat.pinned); put("source", chat.source)
                chat.parentId?.let { put("parentThreadId", it) }; chat.projectId?.let { put("projectId", it) }; put("updatedAt", chat.updatedAt)
            } })); put("projects", JsonArray(projects.map(::JsonPrimitive)))
        }.toString()) }
    }
    fun chatMode(id: String): String = prefs.getString(accountEntry("mode", id), "codex").orEmpty()
    fun accessMode(id: String): String = prefs.getString(accountEntry("access", id), "host").orEmpty()
    fun chatOptions(id: String, mode: String, access: String) { val account = accountKey; prefs.edit { putString(accountEntry("mode", id, account), mode); putString(accountEntry("access", id, account), access) } }
    fun copyChatDraft(from: String, to: String, mode: String, access: String) = synchronized(prefs) {
        require(from != to)
        val account = accountKey
        prefs.edit {
            putString(accountEntry("draft", to, account), prefs.getString(accountEntry("draft", from, account), ""))
            putString(accountEntry("attachments", to, account), prefs.getString(accountEntry("attachments", from, account), "[]"))
            putString(accountEntry("mode", to, account), mode); putString(accountEntry("access", to, account), access)
        }
    }
    fun attachments(id: String): List<Attachment> = try {
        WireJson.parseToJsonElement(prefs.getString(accountEntry("attachments", id), "[]").orEmpty()).jsonArray.map { parseAttachment(it.jsonObject) }
    } catch (_: Exception) { emptyList() }
    fun attachments(id: String, value: List<Attachment>) { prefs.edit { putString(accountEntry("attachments", id), JsonArray(value.map { it.toJson() }).toString()) } }
    @android.annotation.SuppressLint("UseKtx")
    fun editedDraft(id: String, input: JsonArray, mode: String, access: String) {
        val text = input.mapNotNull { it as? JsonObject }.filter { it.text("type") == "text" }.joinToString("\n") { it.text("text") }
        val account = accountKey
        check(prefs.edit().putString(accountEntry("draft", id, account), text)
            .putString(accountEntry("attachments", id, account), JsonArray(sentAttachments(input).map { it.toJson() }).toString())
            .putString(accountEntry("mode", id, account), mode).putString(accountEntry("access", id, account), access).commit()) { "Could not save the edited message. Try again." }
    }
    fun forgetChat(id: String) { val account = accountKey; prefs.edit { listOf("draft", "attachments", "mode", "access").forEach { remove(accountEntry(it, id, account)) } } }
    fun draft(id: String): String = synchronized(prefs) { prefs.getString(accountEntry("draft", id), "").orEmpty() }
    fun draft(id: String, text: String) { synchronized(prefs) { prefs.edit { putString(accountEntry("draft", id), text) } } }
    fun clearSentDraft(id: String, sentText: String): Boolean = synchronized(prefs) {
        // An acknowledgement may arrive after the user has already edited the next prompt.
        val key = accountEntry("draft", id)
        if (prefs.getString(key, "").orEmpty() != sentText) false
        else { prefs.edit { putString(key, "") }; true }
    }
    @Synchronized fun saveCache(value: JsonObject, account: String = accountKey) {
        val text = value.toString(); if (text.length > 8 * 1024 * 1024) return
        val temp = java.io.File(context.filesDir, "history-$account.tmp"); temp.writeText(text)
        check(temp.renameTo(java.io.File(context.filesDir, "history-$account.json"))) { "Could not save history" }
    }
    @Synchronized fun cache(account: String = accountKey): JsonObject = try { WireJson.parseToJsonElement(java.io.File(context.filesDir, "history-$account.json").readText()).jsonObject }
        catch (_: Exception) { buildJsonObject {} }
}
