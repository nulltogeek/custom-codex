package dev.nulltogeek.codexremote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectCreationTest {
    @Test fun projectNameIsRequiredButFoldersAreOptional() {
        assertNull(projectCreationValidation("پروژه", ""))
        assertNotNull(projectCreationValidation("  ", ""))
        assertNotNull(projectCreationValidation("x".repeat(201), ""))
        assertEquals(emptyList<String>(), projectFolders(" \n "))
    }
    @Test fun foldersMustBeAbsoluteAndAreNormalizedWithoutFilesystemChanges() {
        assertNull(projectCreationValidation("Work", "/home/me/work\n/tmp/a/../b\n/home/me/work"))
        assertEquals(listOf("/home/me/work", "/tmp/b"), projectFolders(" /home/me/work \n/tmp/a/../b\n/home/me/work"))
        for (folders in listOf("relative/path", "~/work", "/bad\u0000path", (1..9).joinToString("\n") { "/work/$it" }))
            assertNotNull(projectCreationValidation("Work", folders))
    }
    @Test fun retriesUseTheSameCreationKeyAndPayload() {
        val request = ProjectCreateRequest("Work", listOf("/work"))
        val first = projectCreateParams("same-key", request)
        assertEquals(first, projectCreateParams("same-key", request))
        assertEquals("same-key", first.text("idempotencyKey"))
        assertEquals(listOf("idempotencyKey", "name", "roots"), first.keys.toList())
        assertEquals("/work", first.array("roots").first().jsonObject.text("path"))
    }
    @Test fun hostCreatedProjectIsCachedOnceAndKeepsOtherProjectsAndAssignments() {
        val existing = buildJsonObject {
            put("projects", buildJsonArray { add(jsonObject("id" to "other", "name" to "Other")) })
            put("assignments", jsonObject("chat" to "other"))
        }
        val project = createdProject(buildJsonObject {
            put("id", "new"); put("name", "Work"); put("roots", buildJsonArray { add(jsonObject("path" to "/work")) })
        })
        assertEquals(Project("new", "Work", listOf("/work")), project)
        val inventory = inventoryWithProject(inventoryWithProject(existing, project), project)
        assertEquals(2, inventory.array("projects").size)
        assertEquals(existing.obj("assignments"), inventory.obj("assignments"))
        assertEquals("/work", inventory.array("projects").last().jsonObject.array("rootPaths").first().jsonPrimitive.content)
    }
    @Test fun incompleteCreationResultCannotBecomeAProject() {
        assertThrows(IllegalArgumentException::class.java) { createdProject(jsonObject("name" to "Missing ID")) }
    }
    @Test fun creationRequiresConnectionAndPreventsConcurrentSubmissions() {
        val state = ScreenState(signedIn = true, connection = "Connected")
        assertTrue(canCreateProject(state))
        assertFalse(canCreateProject(state.copy(creatingProject = true)))
        assertFalse(canCreateProject(state.copy(connection = "Offline")))
        assertFalse(canCreateProject(state.copy(signedIn = false)))
    }
}
