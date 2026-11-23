package dev.nulltogeek.codexremote

import org.junit.Assert.*
import org.junit.Test

class ProjectChatGroupsTest {
    private val tennis = Project("tennis", "Tennis", listOf("/tennis"))
    private val jira = Project("jira", "JIRA", listOf("/jira"))
    private fun chat(id: String, cwd: String = "", project: String? = null) = Chat(id, id, "", cwd, projectId = project)

    @Test fun projectGroupsContainOnlyTheirChatsAndKeepOrphansSeparate() {
        val moved = chat("moved", "/tennis", "tennis")
        val rows = listOf(chat("tennis", "/tennis"), moved, chat("orphan"), chat("missing", "/tennis", "removed"))
        val state = ScreenState(projects = listOf(tennis, jira), assignments = mapOf("moved" to "jira"))
        val groups = projectChatSections(state, rows + rows, true)
        assertEquals(listOf("tennis"), groups.first { it.project == tennis }.chats.map { it.id })
        assertEquals(listOf("moved"), groups.first { it.project == jira }.chats.map { it.id })
        assertEquals(listOf("orphan"), groups.first { it.key == "orphans" }.chats.map { it.id })
        assertEquals(listOf("missing"), groups.first { it.key == "project:removed" }.chats.map { it.id })
        assertEquals(rows.size, groups.sumOf { it.chats.size })
    }
    @Test fun pinsStayInsideTheirProjectAndEmptyProjectsRemainReachable() {
        val older = chat("pinned", "/tennis").copy(updatedAt = 1)
        val recent = chat("recent", "/tennis").copy(updatedAt = 10)
        val state = ScreenState(projects = listOf(tennis, jira), favoriteChats = listOf(older), favoriteProjectIds = setOf("jira"))
        val groups = projectChatSections(state, listOf(recent, older), true)
        assertEquals(jira, groups.first().project)
        assertTrue(groups.first().chats.isEmpty())
        assertEquals(listOf("pinned", "recent"), groups.last().chats.map { it.id })
        assertEquals(1, projectChatSections(state, listOf(recent, older), false).size)
    }
    @Test fun searchMatchesProjectNamesAndDoesNotExposeUnrelatedEmptyProjects() {
        val state = ScreenState(projects = listOf(tennis, jira), searchTerm = "JIRA")
        val rows = listOf(chat("fix login", "/jira"), chat("serve", "/tennis"))
        val matches = rows.filter { chatMatchesSearch(state, it) }
        assertEquals(listOf("fix login"), matches.map { it.id })
        assertEquals(listOf(jira), projectChatSections(state, matches, true).map { it.project })
    }
}
