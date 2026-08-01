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
