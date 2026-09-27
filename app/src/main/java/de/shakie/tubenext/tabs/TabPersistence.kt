package de.shakie.tubenext.tabs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TabPersistence(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(sessions: List<TabSession>, selectedTabId: String?) {
        val tabsArray = JSONArray()
        sessions.forEach { session ->
            tabsArray.put(
                JSONObject()
                    .put(KEY_ID, session.id)
                    .put(KEY_URL, session.url)
                    .put(KEY_TITLE, session.title)
                    .put(KEY_RESUME_VIDEO_ID, session.watchProgress?.videoId)
                    .put(KEY_RESUME_SECONDS, session.watchProgress?.positionSeconds)
            )
        }

        preferences.edit()
            .putString(KEY_TABS, tabsArray.toString())
            .putString(KEY_SELECTED_TAB, selectedTabId)
            .apply()
    }

    fun load(): RestoredTabs {
        val serialized = preferences.getString(KEY_TABS, null).orEmpty()
        if (serialized.isBlank()) {
            return RestoredTabs(emptyList(), null)
        }

        val restored = buildList {
            val tabsArray = JSONArray(serialized)
            for (i in 0 until tabsArray.length()) {
                val item = tabsArray.optJSONObject(i) ?: continue
                val id = item.optString(KEY_ID)
                val url = item.optString(KEY_URL)
                if (id.isBlank() || url.isBlank()) continue
                add(
                    TabSession(
                        id = id,
                        url = url,
                        title = item.optString(KEY_TITLE)
                    ).let { session ->
                        val videoId = item.optString(KEY_RESUME_VIDEO_ID)
                        val seconds = item.optLong(KEY_RESUME_SECONDS, -1L)
                        session.withWatchProgress(WatchProgress(videoId, seconds))
                    }
                )
            }
        }

        return RestoredTabs(
            sessions = restored,
            selectedTabId = preferences.getString(KEY_SELECTED_TAB, null)
        )
    }

    companion object {
        private const val PREFS_NAME = "tube_next_tabs"
        private const val KEY_TABS = "tabs"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val KEY_ID = "id"
        private const val KEY_URL = "url"
        private const val KEY_TITLE = "title"
        private const val KEY_RESUME_VIDEO_ID = "resume_video_id"
        private const val KEY_RESUME_SECONDS = "resume_seconds"
    }
}

data class RestoredTabs(
    val sessions: List<TabSession>,
    val selectedTabId: String?
)
