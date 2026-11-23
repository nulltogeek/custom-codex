package dev.nulltogeek.codexremote

import java.security.MessageDigest

/** Stable, opaque storage namespace. Passwords and session tokens are never part of file names. */
internal fun accountStorageKey(endpoint: String, username: String): String = MessageDigest.getInstance("SHA-256")
    .digest((endpoint.trim().trimEnd('/') + "\n" + username).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun validSignup(username: String, password: String, confirmation: String): Boolean =
    Regex("^[A-Za-z0-9._-]{1,64}$").matches(username.trim()) && password.length >= 12 &&
        password.toByteArray(Charsets.UTF_8).size <= 1024 && password == confirmation

data class ManagedUser(val id: String, val username: String, val role: String, val disabled: Boolean,
    val createdAt: Long = 0)

internal fun parseManagedUser(raw: kotlinx.serialization.json.JsonObject) = ManagedUser(
    raw.text("id"), raw.text("username"), raw.text("role", "user"), raw.text("disabled") == "true",
    raw.text("createdAt").toLongOrNull() ?: 0)

/** The server remains authoritative; these defaults keep the UI inside its private workspace. */
internal fun defaultProjectFolders(state: ScreenState): String = if (state.accountRole == "admin") "" else state.workspaceRoot

internal fun newChatDirectory(state: ScreenState, project: Project?): String? =
    project?.roots?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: state.workspaceRoot.takeIf { state.accountRole != "admin" && it.isNotBlank() }

