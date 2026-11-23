package dev.nulltogeek.codexremote

/** Local pinned snapshots are reconciled with live rows so renamed/archived chats stay current. */
internal fun sidebarChatGroups(state: ScreenState): Pair<List<Chat>, List<Chat>> {
    val live = state.chats.associateBy { it.id }
    val pinned = state.favoriteChats.map { live[it.id] ?: it }.distinctBy { it.id }
        .filter { !it.archived && it.parentId == null && it.source != "subAgent" }
    val pinnedIds = pinned.map { it.id }.toSet()
    fun matches(chat: Chat) = state.searchTerm.isBlank() || chat.title.contains(state.searchTerm, true) || chat.preview.contains(state.searchTerm, true)
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
