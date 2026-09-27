package de.shakie.tubenext.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchProgressTest {
    private val watch = "https://www.youtube.com/watch?v=abcdefghijk"
    private val progress = WatchProgress("abcdefghijk", 135)

    @Test
    fun `restores position without modifying the live tab url`() {
        val tab = TabSession("one", watch, "Video").withWatchProgress(progress)
        assertEquals(watch, tab.url)
        assertEquals("$watch&t=135s", tab.restoreUrl())
        assertEquals(tab.watchProgress, tab.withPage(watch, "New title").watchProgress)
    }

    @Test
    fun `replaces all conflicting times and retains encoded playlist context`() {
        assertEquals(
            "$watch&list=PL%2Bxyz&index=2&t=135s",
            progress.restoreUrl("$watch&t=2m&t=3&start=90&time_continue=12&list=PL%2Bxyz&index=2#t=10s")
        )
        assertEquals("$watch&t=135s#comments", progress.restoreUrl("$watch&%74=90#comments"))
        assertEquals("https://youtu.be/abcdefghijk?t=135s", progress.restoreUrl("https://youtu.be/abcdefghijk#1m"))
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk&t=135s", progress.restoreUrl("https://m.youtube.com/watch?v=abcdefghijk"))
    }

    @Test
    fun `old tabs and newly opened timestamp links retain their original target`() {
        val link = "$watch&t=2m15s"
        assertEquals(link, TabSession("new", link, "").restoreUrl())
        assertEquals(watch, WatchProgress("abcdefghijk", -1).restoreUrl(watch))
    }

    @Test
    fun `different videos and tabs cannot inherit a checkpoint`() {
        val tab = TabSession("one", watch, "").withWatchProgress(progress)
        val otherUrl = "https://www.youtube.com/watch?v=other_video"
        assertEquals(otherUrl, progress.restoreUrl(otherUrl))
        assertNull(tab.withPage(otherUrl, "Other").watchProgress)
        assertNull(tab.withPage("https://www.youtube.com/", "Home").watchProgress)
        assertNull(TabSession("two", otherUrl, "").withWatchProgress(progress).watchProgress)
        assertEquals(progress, tab.withPage("https://youtu.be/abcdefghijk", "").watchProgress)
    }

    @Test
    fun `invalid missing live and non-watch samples are ignored`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 301.0).forEach {
            assertNull(WatchProgress.fromSample(watch, it, 300.0, false))
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach {
            assertNull(WatchProgress.fromSample(watch, 135.0, it, false))
        }
        listOf("https://example.com/watch?v=abcdefghijk", "https://www.youtube.com/shorts/abcdefghijk", "about:blank").forEach {
            assertNull(WatchProgress.fromSample(it, 135.0, 300.0, false))
        }
    }

    @Test
    fun `finished videos restart and seeking backwards saves the new position`() {
        assertEquals(progress, WatchProgress.fromSample(watch, 135.9, 300.0, false))
        val ended = WatchProgress.fromSample(watch, 300.0, 300.0, true)!!
        assertEquals("$watch&t=0s", ended.restoreUrl("$watch&t=150s"))
        val tab = TabSession("one", watch, "", progress)
        assertEquals("$watch&t=12s", tab.withWatchProgress(WatchProgress("abcdefghijk", 12)).restoreUrl())
    }
}
