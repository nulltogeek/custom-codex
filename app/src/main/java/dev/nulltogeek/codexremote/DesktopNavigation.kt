package dev.nulltogeek.codexremote

/** Local pinned snapshots are reconciled with live rows so renamed/archived chats stay current. */
internal fun sidebarChatGroups(state: ScreenState): Pair<List<Chat>, List<Chat>> {
    val live = state.chats.associateBy { it.id }
    val pinned = state.favoriteChats.map { live[it.id] ?: it }.distinctBy { it.id }
        .filter { !it.archived && it.parentId == null && it.source != "subAgent" }
    val pinnedIds = pinned.map { it.id }.toSet()
    fun matches(chat: Chat) = chatMatchesSearch(state, chat)
    val recent = state.chats.distinctBy { it.id }.filter {
        !it.archived && it.parentId == null && it.source != "subAgent" && it.id !in pinnedIds && matches(it)
    }.sortedByDescending { it.updatedAt }
    return pinned.filter(::matches) to recent
}

internal fun canForkChat(state: ScreenState) = state.signedIn && state.connection == "Connected" &&
    state.selectedChat != null && state.selectedChat.archived.not() && state.selectedChat.status != "active" &&
    state.activeTurnId.isEmpty() && !state.loading && !state.sending && !state.uploading && !state.uncertainSend && state.sentMessageEdit == null

internal data class StarterPrompt(val title: String, val description: String, val prompt: String)
internal fun starterPrompts(mode: String) = if (mode == "chat") listOf(
    StarterPrompt("Explore an idea", "Think it through together", "Help me think through an idea: "),
    StarterPrompt("Explain something", "Make a complex topic clear", "Explain this step by step: "),
    StarterPrompt("Make a plan", "Turn a goal into next steps", "Help me make a plan for "),
    StarterPrompt("Write something", "Find the right words", "Help me write ")
) else listOf(
    StarterPrompt("Build something", "Turn an idea into working code", "Help me build "),
    StarterPrompt("Fix a problem", "Investigate and resolve a bug", "Help me investigate and fix "),
    StarterPrompt("Review code", "Find issues and improvements", "Review this project for bugs and explain the most important findings."),
    StarterPrompt("Explore a project", "Understand how the pieces fit", "Explore this project and explain its structure and how to run it.")
)

internal data class ProjectChatSection(val key: String, val title: String, val project: Project?, val chats: List<Chat>)

/** Explicit project assignments win over folder inference, including missing projects. */
internal fun chatProjectId(state: ScreenState, chat: Chat): String? =
    state.assignments[chat.id] ?: chat.projectId ?: state.projects.firstOrNull {
        chatBelongsToProject(chat, it, emptyMap())
    }?.id

internal fun chatMatchesSearch(state: ScreenState, chat: Chat): Boolean {
    val query = state.searchTerm
    val project = state.projects.find { it.id == chatProjectId(state, chat) }
    return query.isBlank() || chat.title.contains(query, true) || chat.preview.contains(query, true) ||
        project?.name?.contains(query, true) == true
}

internal fun projectChatSections(state: ScreenState, chats: List<Chat>, includeEmptyProjects: Boolean): List<ProjectChatSection> {
    val pins = state.favoriteChats.map { it.id }.toSet()
    val grouped = chats.distinctBy { it.id }.groupBy { chatProjectId(state, it) }
    fun ordered(rows: List<Chat>) = rows.sortedWith(compareByDescending<Chat> { it.id in pins }.thenByDescending { it.updatedAt })
    val known = state.projects.sortedByDescending { it.id in state.favoriteProjectIds }.mapNotNull { project ->
        val rows = grouped[project.id].orEmpty()
        if (rows.isEmpty() && (!includeEmptyProjects || (state.searchTerm.isNotBlank() && !project.name.contains(state.searchTerm, true)))) null
        else ProjectChatSection("project:${project.id}", project.name, project, ordered(rows))
    }
    val unknown = grouped.keys.filterNotNull().filter { id -> state.projects.none { it.id == id } }.map { id ->
        ProjectChatSection("project:$id", "Unavailable project", null, ordered(grouped[id].orEmpty()))
    }
    val orphans = grouped[null].orEmpty().takeIf { it.isNotEmpty() }?.let {
        listOf(ProjectChatSection("orphans", "No project", null, ordered(it)))
    }.orEmpty()
    return known + unknown + orphans
}
