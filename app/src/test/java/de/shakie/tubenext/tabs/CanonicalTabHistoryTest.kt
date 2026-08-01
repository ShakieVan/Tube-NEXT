package de.shakie.tubenext.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalTabHistoryTest {
    @Test
    fun `moves backward and forward through canonical targets`() {
        val history = CanonicalTabHistory("https://www.youtube.com/")
        history.record("https://www.youtube.com/feed/subscriptions")
        history.record("https://www.youtube.com/watch?v=abc&t=20")

        assertTrue(history.canGoBack("https://www.youtube.com/watch?v=abc&t=20"))
        assertFalse(history.canGoForward("https://www.youtube.com/watch?v=abc&t=20"))
        assertEquals(
            "https://www.youtube.com/feed/subscriptions",
            history.backTarget("https://www.youtube.com/watch?v=abc&t=20")
        )
        assertNull(history.backTarget("https://www.youtube.com/watch?v=abc&t=20"))
        history.recordNavigationStart("https://www.youtube.com/feed/subscriptions")
        assertTrue(history.isNavigationPending)

        history.record("https://www.youtube.com/feed/subscriptions")
        assertTrue(history.canGoForward("https://www.youtube.com/feed/subscriptions"))
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            history.forwardTarget("https://www.youtube.com/feed/subscriptions")
        )
    }

    @Test
    fun `new navigation after back discards forward entries`() {
        val history = CanonicalTabHistory("https://www.youtube.com/")
        history.record("https://www.youtube.com/feed/subscriptions")
        history.record("https://www.youtube.com/watch?v=abc")
        history.backTarget("https://www.youtube.com/watch?v=abc")
        history.record("https://www.youtube.com/feed/subscriptions")

        history.record("https://www.youtube.com/results?search_query=kotlin")

        assertFalse(history.canGoForward("https://www.youtube.com/results?search_query=kotlin"))
        assertEquals(
            "https://www.youtube.com/feed/subscriptions",
            history.backTarget("https://www.youtube.com/results?search_query=kotlin")
        )
    }

    @Test
    fun `canonicalizes watch variants and ignores transient or external urls`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            CanonicalTabHistory.canonicalize("https://youtu.be/abc?t=30")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            CanonicalTabHistory.canonicalize("https://m.youtube.com/watch?v=abc&list=123#fragment")
        )
        assertEquals(
            "https://www.youtube.com/feed/subscriptions?flow=2",
            CanonicalTabHistory.canonicalize(
                "https://www.youtube.com/feed/subscriptions?flow=2#menu"
            )
        )
        assertEquals("", CanonicalTabHistory.canonicalize("https://accounts.google.com/ServiceLogin"))
        assertEquals("", CanonicalTabHistory.canonicalize("https://example.com/"))

        val history = CanonicalTabHistory("https://www.youtube.com/")
        history.record("https://www.youtube.com/watch?v=abc&t=10")
        history.record("https://m.youtube.com/watch?v=abc&t=20")
        assertEquals(
            "https://www.youtube.com/",
            history.backTarget("https://www.youtube.com/watch?v=abc&t=20")
        )
    }

    @Test
    fun `restores history without preserving an in flight navigation`() {
        val history = CanonicalTabHistory("https://www.youtube.com/")
        history.record("https://www.youtube.com/feed/subscriptions")
        history.backTarget("https://www.youtube.com/feed/subscriptions")

        val restored = CanonicalTabHistory()
        restored.restore(history.snapshot())

        assertTrue(restored.canGoForward("https://www.youtube.com/"))
    }
}
