package de.shakie.tubenext.tabs

import de.shakie.tubenext.browser.YouTubeNavigationPolicy

/**
 * Repair steps for a selected Gecko tab whose frame stays blank in the render health check.
 *
 * Re-attaching the GeckoView repairs a lost surface. A page process that no longer answers after a
 * long background phase keeps the frame black even then, and `session.reload()` stays in that
 * process; only a fresh load (`loadUri`) lets Gecko continue in a working one. See
 * docs/technical-notes/gecko-black-surface-recovery.md.
 */
object BlankTabRecoveryPolicy {
    const val SURFACE_REATTACH_THROTTLE_MS = 12_000L
    const val FRESH_LOAD_MIN_INTERVAL_MS = 60_000L

    enum class Action {
        REATTACH_SURFACE,
        FRESH_LOAD,
        SKIP_THROTTLED,
        SKIP_VIDEO
    }

    /**
     * @param afterSurfaceReattach the blank result comes from the check scheduled by a re-attach.
     * @param msSinceTabReattach time since this tab's last re-attach, null if there was none.
     * @param msSinceAnyReattach time since the last re-attach of any tab, null if there was none.
     * @param msSinceTabFreshLoad time since this tab's last automatic fresh load, null if none.
     * @param videoMayFillFrame a playing or landscape video can legitimately render dark frames.
     */
    fun decide(
        afterSurfaceReattach: Boolean,
        msSinceTabReattach: Long?,
        msSinceAnyReattach: Long?,
        msSinceTabFreshLoad: Long?,
        videoMayFillFrame: Boolean
    ): Action {
        val reattachDidNotHelp = afterSurfaceReattach ||
            msSinceTabReattach.isWithin(SURFACE_REATTACH_THROTTLE_MS)
        if (!reattachDidNotHelp) {
            return if (msSinceAnyReattach.isWithin(SURFACE_REATTACH_THROTTLE_MS)) {
                Action.SKIP_THROTTLED
            } else {
                Action.REATTACH_SURFACE
            }
        }
        if (videoMayFillFrame) return Action.SKIP_VIDEO
        if (msSinceTabFreshLoad.isWithin(FRESH_LOAD_MIN_INTERVAL_MS)) return Action.SKIP_THROTTLED
        return Action.FRESH_LOAD
    }

    /**
     * The URL a fresh load opens: the watch checkpoint for the same video, otherwise the tab URL.
     * Null keeps `session.reload()` for transient account or consent pages without a tab URL of
     * their own, so a reload there does not jump back to YouTube.
     */
    fun freshLoadUrl(tabUrl: String, engineLocationUrl: String, restoreUrl: String?): String? {
        if (CanonicalTabHistory.shouldUseEngineHistory(engineLocationUrl)) return null
        if (restoreUrl != null &&
            YouTubeNavigationPolicy.urlsReferToSameVideo(restoreUrl, engineLocationUrl)
        ) {
            return restoreUrl
        }
        return tabUrl.ifBlank { engineLocationUrl }.ifBlank { null }
    }

    private fun Long?.isWithin(limitMs: Long): Boolean = this != null && this in 0 until limitMs
}
