package de.shakie.tubenext.browser

object YouTubeLinkOnboardingPolicy {
    const val REMINDER_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L

    fun shouldPrompt(
        association: YouTubeLinkAssociationState,
        permanentlyDisabled: Boolean,
        legacyPromptShown: Boolean,
        lastPromptAt: Long,
        now: Long
    ): Boolean {
        if (permanentlyDisabled || association.allHostsEnabled) return false
        if (!association.inspectionAvailable && legacyPromptShown) return false
        return lastPromptAt <= 0L || now - lastPromptAt >= REMINDER_INTERVAL_MS
    }
}
