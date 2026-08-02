package de.shakie.tubenext.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinkOnboardingPolicyTest {
    private val unconfigured = YouTubeLinkAssociationState(
        enabledHosts = emptySet(),
        inspectionAvailable = true
    )

    @Test
    fun `unconfigured modern device is prompted immediately`() {
        assertTrue(
            YouTubeLinkOnboardingPolicy.shouldPrompt(
                association = unconfigured,
                permanentlyDisabled = false,
                legacyPromptShown = false,
                lastPromptAt = 0L,
                now = 100L
            )
        )
    }

    @Test
    fun `later waits seven days before another prompt`() {
        val lastPrompt = 1_000L
        assertFalse(
            YouTubeLinkOnboardingPolicy.shouldPrompt(
                unconfigured,
                permanentlyDisabled = false,
                legacyPromptShown = false,
                lastPromptAt = lastPrompt,
                now = lastPrompt + YouTubeLinkOnboardingPolicy.REMINDER_INTERVAL_MS - 1L
            )
        )
        assertTrue(
            YouTubeLinkOnboardingPolicy.shouldPrompt(
                unconfigured,
                permanentlyDisabled = false,
                legacyPromptShown = false,
                lastPromptAt = lastPrompt,
                now = lastPrompt + YouTubeLinkOnboardingPolicy.REMINDER_INTERVAL_MS
            )
        )
    }

    @Test
    fun `configured or permanently dismissed device is not prompted`() {
        val configured = YouTubeLinkAssociationState(
            enabledHosts = YouTubeLinkAssociation.supportedHosts,
            inspectionAvailable = true
        )
        assertFalse(
            YouTubeLinkOnboardingPolicy.shouldPrompt(configured, false, false, 0L, 0L)
        )
        assertFalse(
            YouTubeLinkOnboardingPolicy.shouldPrompt(unconfigured, true, false, 0L, 0L)
        )
    }

    @Test
    fun `legacy device receives automatic prompt only once`() {
        val legacy = YouTubeLinkAssociationState(emptySet(), inspectionAvailable = false)
        assertTrue(
            YouTubeLinkOnboardingPolicy.shouldPrompt(legacy, false, false, 0L, 0L)
        )
        assertFalse(
            YouTubeLinkOnboardingPolicy.shouldPrompt(legacy, false, true, 0L, 0L)
        )
    }
}
