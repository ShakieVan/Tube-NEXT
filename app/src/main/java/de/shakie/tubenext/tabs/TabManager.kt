package de.shakie.tubenext.tabs

import java.util.UUID

class TabManager(
    private val persistence: TabPersistence
) {
    private val sessions = mutableListOf<TabSession>()
    private var selectedId: String? = null

    fun restore(): List<TabSession> {
        val restoredTabs = persistence.load()
        sessions.clear()
        sessions.addAll(restoredTabs.sessions)
        selectedId = restoredTabs.selectedTabId?.takeIf { savedId ->
            sessions.any { it.id == savedId }
        } ?: sessions.firstOrNull()?.id
        return sessions.toList()
    }

    fun all(): List<TabSession> = sessions.toList()

    fun selectedTabId(): String? = selectedId

    fun create(url: String, title: String = ""): TabSession {
        val session = TabSession(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title
        )
        sessions.add(session)
        selectedId = session.id
        persist()
        return session
    }

    fun update(tabId: String, url: String, title: String) {
        val index = sessions.indexOfFirst { it.id == tabId }
        if (index < 0) return
        sessions[index] = sessions[index].copy(
            url = url,
            title = title
        )
        persist()
    }

    fun select(tabId: String) {
        if (sessions.none { it.id == tabId }) return
        selectedId = tabId
        persist()
    }

    fun close(tabId: String): String? {
        val index = sessions.indexOfFirst { it.id == tabId }
        if (index < 0) return selectedId
        sessions.removeAt(index)
        if (sessions.isEmpty()) {
            selectedId = null
            persist()
            return null
        }
        selectedId = when {
            selectedId != tabId -> selectedId
            index > 0 -> sessions[index - 1].id
            else -> sessions.first().id
        }
        persist()
        return selectedId
    }

    fun duplicate(tabId: String): TabSession? {
        val original = sessions.firstOrNull { it.id == tabId } ?: return null
        return create(
            url = original.url,
            title = original.title
        )
    }

    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex == toIndex) return false
        if (fromIndex !in sessions.indices || toIndex !in sessions.indices) return false
        val moved = sessions.removeAt(fromIndex)
        sessions.add(toIndex, moved)
        persist()
        return true
    }

    fun persist() {
        persistence.save(sessions = sessions, selectedTabId = selectedId)
    }
}
