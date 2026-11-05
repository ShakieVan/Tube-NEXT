package de.shakie.tubenext.tabs

import de.shakie.tubenext.tabs.BlankTabRecoveryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlankTabRecoveryPolicyTest {
    private val watch = "https://www.youtube.com/watch?v=abcdefghijk"

    private fun decide(
        afterSurfaceReattach: Boolean = false,
        msSinceTabReattach: Long? = null,
        msSinceAnyReattach: Long? = msSinceTabReattach,
        msSinceTabFreshLoad: Long? = null,
        videoMayFillFrame: Boolean = false
    ) = BlankTabRecoveryPolicy.decide(
        afterSurfaceReattach,
        msSinceTabReattach,
        msSinceAnyReattach,
        msSinceTabFreshLoad,
        videoMayFillFrame
    )

    @Test
    fun `first blank result only re-attaches the surface`() {
        assertEquals(Action.REATTACH_SURFACE, decide())
        assertEquals(Action.REATTACH_SURFACE, decide(msSinceTabReattach = 12_000L))
        // Even during video playback the cheap re-attach stays allowed, as before.
        assertEquals(Action.REATTACH_SURFACE, decide(videoMayFillFrame = true))
    }

    @Test
    fun `blank after a re-attach of the same tab escalates to a fresh load`() {
        assertEquals(Action.FRESH_LOAD, decide(afterSurfaceReattach = true, msSinceTabReattach = 1_600L))
        // A second app start shortly after the re-attach is the same evidence.
        assertEquals(Action.FRESH_LOAD, decide(msSinceTabReattach = 5_000L))
    }

    @Test
    fun `a recent re-attach of another tab is only throttled`() {
        assertEquals(
            Action.SKIP_THROTTLED,
            decide(msSinceTabReattach = null, msSinceAnyReattach = 3_000L)
        )
    }

    @Test
    fun `fresh load waits a minute per tab`() {
        assertEquals(
            Action.SKIP_THROTTLED,
            decide(afterSurfaceReattach = true, msSinceTabFreshLoad = 59_999L)
        )
        assertEquals(
            Action.FRESH_LOAD,
            decide(afterSurfaceReattach = true, msSinceTabFreshLoad = 60_000L)
        )
    }

    @Test
    fun `dark video frames never trigger a fresh load`() {
        assertEquals(Action.SKIP_VIDEO, decide(afterSurfaceReattach = true, videoMayFillFrame = true))
        assertEquals(Action.SKIP_VIDEO, decide(msSinceTabReattach = 2_000L, videoMayFillFrame = true))
    }

    @Test
    fun `fresh load keeps the watch position of the same video`() {
        assertEquals(
            "$watch&t=135s",
            BlankTabRecoveryPolicy.freshLoadUrl(watch, watch, "$watch&t=135s")
        )
    }

    @Test
    fun `fresh load ignores a checkpoint of another video`() {
        val other = "https://www.youtube.com/watch?v=lmnopqrstuv"
        assertEquals(watch, BlankTabRecoveryPolicy.freshLoadUrl(watch, watch, "$other&t=10s"))
        assertEquals(
            "https://www.youtube.com/feed/subscriptions",
            BlankTabRecoveryPolicy.freshLoadUrl(
                "https://www.youtube.com/feed/subscriptions",
                "https://m.youtube.com/feed/subscriptions",
                "https://www.youtube.com/feed/subscriptions"
            )
        )
    }

    @Test
    fun `transient account pages keep the plain reload`() {
        assertNull(
            BlankTabRecoveryPolicy.freshLoadUrl(
                watch,
                "https://accounts.google.com/v3/signin/identifier",
                "$watch&t=135s"
            )
        )
    }

    @Test
    fun `falls back to the engine location and then to nothing`() {
        assertEquals(watch, BlankTabRecoveryPolicy.freshLoadUrl("", watch, null))
        assertNull(BlankTabRecoveryPolicy.freshLoadUrl("", "", null))
    }
}
