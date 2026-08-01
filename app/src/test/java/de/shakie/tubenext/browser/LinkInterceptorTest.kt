package de.shakie.tubenext.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkInterceptorTest {
    @Test
    fun `allows supported YouTube and required login flow hosts`() {
        assertInternal("https", "youtube.com")
        assertInternal("https", "www.youtube.com")
        assertInternal("https", "m.youtube.com")
        assertInternal("https", "youtu.be")
        assertInternal("https", "accounts.youtube.com")
        assertInternal("https", "consent.youtube.com")
        assertInternal("https", "accounts.google.com")
        assertInternal("https", "consent.google.com")
        assertInternal("HTTP", "WWW.YOUTUBE.COM")
    }

    @Test
    fun `rejects lookalike hosts outside trusted domain boundaries`() {
        assertExternal("https", "accounts.google.example.org")
        assertExternal("https", "consent.google.example.org")
        assertExternal("https", "myaccount.google.example.org")
        assertExternal("https", "accounts.google.com.example.org")
        assertExternal("https", "youtube.com.example.org")
        assertExternal("https", "notyoutube.com")
        assertExternal("https", "evil-youtube.com")
    }

    @Test
    fun `rejects unrelated Google and normal external hosts`() {
        assertExternal("https", "google.com")
        assertExternal("https", "www.google.com")
        assertExternal("https", "mail.google.com")
        assertExternal("https", "myaccount.google.com")
        assertExternal("https", "accounts.google.de")
        assertExternal("https", "example.com")
        assertExternal("https", "github.com")
    }

    @Test
    fun `rejects non web schemes even for trusted hosts`() {
        assertExternal("javascript", "www.youtube.com")
        assertExternal("file", "accounts.google.com")
        assertExternal(null, "www.youtube.com")
    }

    @Test
    fun `recognizes only http YouTube navigation as YouTube links`() {
        assertTrue(LinkInterceptor.isYouTubeHttpNavigation("https", "music.youtube.com"))
        assertTrue(LinkInterceptor.isYouTubeHttpNavigation("http", "youtu.be"))
        assertFalse(LinkInterceptor.isYouTubeHttpNavigation("javascript", "www.youtube.com"))
        assertFalse(LinkInterceptor.isYouTubeHttpNavigation("https", "youtube.com.example.org"))
    }

    private fun assertInternal(scheme: String?, host: String?) {
        assertTrue(
            "$scheme://$host should remain inside Gecko",
            LinkInterceptor.isInternalHttpNavigation(scheme, host)
        )
    }

    private fun assertExternal(scheme: String?, host: String?) {
        assertFalse(
            "$scheme://$host should leave Gecko",
            LinkInterceptor.isInternalHttpNavigation(scheme, host)
        )
    }
}
