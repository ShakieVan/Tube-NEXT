package de.shakie.tubenext.browser

import android.content.pm.verify.domain.DomainVerificationUserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinkAssociationTest {
    @Test
    fun `all selected and verified YouTube hosts count as enabled`() {
        val states = YouTubeLinkAssociation.fromHostStates(
            mapOf(
                "youtube.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED,
                "www.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED,
                "m.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED,
                "youtu.be" to DomainVerificationUserState.DOMAIN_STATE_SELECTED
            )
        )

        assertTrue(states.inspectionAvailable)
        assertTrue(states.allHostsEnabled)
        assertEquals(4, states.enabledHostCount)
    }

    @Test
    fun `partial selection remains visible and ignores unrelated hosts`() {
        val states = YouTubeLinkAssociation.fromHostStates(
            mapOf(
                "WWW.YOUTUBE.COM" to DomainVerificationUserState.DOMAIN_STATE_SELECTED,
                "youtu.be" to DomainVerificationUserState.DOMAIN_STATE_NONE,
                "example.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED
            )
        )

        assertFalse(states.allHostsEnabled)
        assertEquals(setOf("www.youtube.com"), states.enabledHosts)
        assertEquals(1, states.enabledHostCount)
        assertEquals(4, states.supportedHostCount)
    }

    @Test
    fun `unavailable inspection never claims full configuration`() {
        val states = YouTubeLinkAssociationState(
            enabledHosts = YouTubeLinkAssociation.supportedHosts,
            inspectionAvailable = false
        )

        assertFalse(states.allHostsEnabled)
    }
}
