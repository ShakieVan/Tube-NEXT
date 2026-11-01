package de.shakie.tubenext.browser

import de.shakie.tubenext.browser.YouTubeNavigationPolicy.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeNavigationPolicyTest {

    @Test
    fun `extracts video ids from watch and short URLs`() {
        assertEquals("abc_123-XYZ", YouTubeNavigationPolicy.videoIdForUrl(
            "https://www.youtube.com/watch?v=abc_123-XYZ&list=PL123"
        ))
        assertEquals("abc_123-XYZ", YouTubeNavigationPolicy.videoIdForUrl(
            "https://youtu.be/abc_123-XYZ?t=42"
        ))
    }

    @Test
    fun `matches artwork source only to the same video`() {
        assertTrue(YouTubeNavigationPolicy.urlsReferToSameVideo(
            "https://www.youtube.com/watch?v=sameVideo01",
            "https://m.youtube.com/watch?feature=share&v=sameVideo01"
        ))
        assertFalse(YouTubeNavigationPolicy.urlsReferToSameVideo(
            "https://www.youtube.com/watch?v=firstVideo1",
            "https://www.youtube.com/watch?v=secondVideo"
        ))
        assertFalse(YouTubeNavigationPolicy.urlsReferToSameVideo(
            "https://www.youtube.com/",
            "https://www.youtube.com/watch?v=secondVideo"
        ))
    }

    @Test
    fun `builds privacy share urls containing only the video id`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc_123-XYZ",
            YouTubeNavigationPolicy.privacyShareUrlForUrl(
                "https://m.youtube.com/watch?si=recipient-token&v=abc_123-XYZ&list=PL123&t=42"
            )
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc_123-XYZ",
            YouTubeNavigationPolicy.privacyShareUrlForUrl(
                "https://youtu.be/abc_123-XYZ?si=recipient-token&t=42"
            )
        )
    }

    @Test
    fun `adds only the chosen position to privacy share urls`() {
        val url = "https://m.youtube.com/watch?si=recipient-token&v=abc_123-XYZ&list=PL123&t=42"
        val shared = "https://www.youtube.com/watch?v=abc_123-XYZ"
        assertEquals("$shared&t=3m2s", YouTubeNavigationPolicy.privacyShareUrlAtPosition(url, 182))
        assertEquals("$shared&t=45s", YouTubeNavigationPolicy.privacyShareUrlAtPosition(url, 45))
        assertEquals("$shared&t=3m0s", YouTubeNavigationPolicy.privacyShareUrlAtPosition(url, 180))
        assertEquals("$shared&t=1h0m5s", YouTubeNavigationPolicy.privacyShareUrlAtPosition(url, 3605))
        assertEquals(shared, YouTubeNavigationPolicy.privacyShareUrlAtPosition(url, 0))
        assertEquals(
            null,
            YouTubeNavigationPolicy.privacyShareUrlAtPosition("https://www.youtube.com/", 182)
        )
    }

    @Test
    fun `does not offer privacy sharing outside valid watch urls`() {
        assertEquals(null, YouTubeNavigationPolicy.privacyShareUrlForUrl("https://www.youtube.com/"))
        assertEquals(
            null,
            YouTubeNavigationPolicy.privacyShareUrlForUrl("https://www.youtube.com/watch?list=PL123")
        )
        assertEquals(
            null,
            YouTubeNavigationPolicy.privacyShareUrlForUrl("https://example.org/watch?v=abc_123-XYZ")
        )
    }

    @Test
    fun `uses desktop only for watch and youtu be targets`() {
        assertEquals(RenderMode.MOBILE, YouTubeNavigationPolicy.renderModeForUrl("https://youtube.com/"))
        assertEquals(
            RenderMode.MOBILE,
            YouTubeNavigationPolicy.renderModeForUrl("https://m.youtube.com/feed/subscriptions")
        )
        assertEquals(
            RenderMode.DESKTOP_WATCH,
            YouTubeNavigationPolicy.renderModeForUrl("https://www.youtube.com/watch?v=abc")
        )
        assertEquals(
            RenderMode.DESKTOP_WATCH,
            YouTubeNavigationPolicy.renderModeForUrl("https://youtu.be/abc")
        )
    }

    @Test
    fun `keeps youtube downloads list in desktop mode without treating it as watch`() {
        listOf(
            "https://www.youtube.com/feed/downloads",
            "https://m.youtube.com/feed/downloads",
            "https://www.youtube.com/feed/downloads?app=desktop",
            "https://youtube.com/feed/downloads/"
        ).forEach { url ->
            assertEquals(RenderMode.DESKTOP_DOWNLOADS, YouTubeNavigationPolicy.renderModeForUrl(url))
            assertTrue(YouTubeNavigationPolicy.shouldUseDesktopMode(url))
            assertFalse(YouTubeNavigationPolicy.isWatchUrl(url))
        }
    }

    @Test
    fun `leaves other feeds and lookalike download paths in mobile mode`() {
        listOf(
            "https://m.youtube.com/feed/history",
            "https://m.youtube.com/feed/downloadsfoo",
            "https://m.youtube.com/downloads",
            "https://example.org/feed/downloads"
        ).forEach { url ->
            assertEquals(RenderMode.MOBILE, YouTubeNavigationPolicy.renderModeForUrl(url))
            assertFalse(YouTubeNavigationPolicy.shouldUseDesktopMode(url))
        }
    }

    @Test
    fun `rejects unsupported schemes hosts and lookalikes`() {
        assertFalse(YouTubeNavigationPolicy.isSupportedYouTubeUrl("javascript://www.youtube.com/"))
        assertFalse(YouTubeNavigationPolicy.isSupportedYouTubeUrl("https://youtube.com.example.org/"))
        assertFalse(YouTubeNavigationPolicy.isWatchUrl("https://example.org/watch?v=abc"))
        assertFalse(YouTubeNavigationPolicy.isWatchUrl("not a url"))
    }

    @Test
    fun `keeps transient account urls out of the visible toolbar policy`() {
        assertFalse(
            YouTubeNavigationPolicy.isUserVisibleUrl(
                "https://accounts.youtube.com/RotateCookiesPage?origin=https%3A%2F%2Fyoutube.com"
            )
        )
        assertFalse(YouTubeNavigationPolicy.isUserVisibleUrl("about:blank"))
        assertTrue(YouTubeNavigationPolicy.isUserVisibleUrl("https://www.youtube.com/results?q=kotlin"))
    }
}
