package dev.nulltogeek.codexremote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AccountStorageTest {
    @Test fun storageKeysAreStableButSeparateServersAndUsers() {
        val owner = accountStorageKey("https://server.example", "nulltogeek")
        assertEquals(owner, accountStorageKey(" https://server.example/ ", "nulltogeek"))
        assertTrue(Regex("[a-f0-9]{64}").matches(owner))
        assertNotEquals(owner, accountStorageKey("https://server.example", "another-user"))
        assertNotEquals(owner, accountStorageKey("https://other.example", "nulltogeek"))
        assertNotEquals(owner, accountStorageKey("https://server.example", "Nulltogeek"))
    }

    @Test fun signupRequiresAValidUsernameMatchingPasswordsAndUtf8ByteLimit() {
        assertTrue(validSignup("new.user-2", "a long password  ", "a long password  "))
        assertFalse(validSignup("invalid user", "a long password", "a long password"))
        assertFalse(validSignup("../invalid", "a long password", "a long password"))
        assertFalse(validSignup("u", "short", "short"))
        assertFalse(validSignup("u", "a long password ", "a long password"))
        assertFalse(validSignup("u", "🔒".repeat(257), "🔒".repeat(257)))
        assertTrue(validSignup("u", "a".repeat(1024), "a".repeat(1024)))
    }

    @Test fun managedUserParsesDisabledStateAndDefaultsToAnUnprivilegedRole() {
        val user = parseManagedUser(buildJsonObject { put("id", "u-1"); put("username", "member"); put("disabled", true); put("createdAt", 1700000000000) })
        assertEquals("user", user.role); assertTrue(user.disabled)
        assertEquals(1700000000000, user.createdAt)
    }

    @Test fun regularUserProjectsAndChatsDefaultToTheirPrivateWorkspace() {
        val member = ScreenState(accountRole = "user", workspaceRoot = "/private/workspace")
        assertEquals("/private/workspace", defaultProjectFolders(member))
        assertEquals(listOf("/private/workspace"), projectFolders(defaultProjectFolders(member)))
        assertEquals("/private/workspace", newChatDirectory(member, null))
        assertEquals("/private/workspace", newChatDirectory(member, Project("empty", "No folders", emptyList())))
        assertEquals("/private/workspace/project", newChatDirectory(member, Project("p", "Project", listOf("/private/workspace/project"))))
        val owner = member.copy(accountRole = "admin")
        assertEquals("", defaultProjectFolders(owner)); assertTrue(projectFolders("").isEmpty())
        assertNull(newChatDirectory(owner, null))
        assertEquals("/existing/project", newChatDirectory(owner, Project("owner", "Owner", listOf("/existing/project"))))
    }
}
