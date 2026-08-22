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
        assertInternal("https", "gds.google.com")
        assertInternal("HTTP", "WWW.YOUTUBE.COM")
    }

    @Test
    fun `rejects lookalike hosts outside trusted domain boundaries`() {
        assertExternal("https", "accounts.google.example.org")
        assertExternal("https", "consent.google.example.org")
        assertExternal("https", "gds.google.example.org")
        assertExternal("https", "myaccount.google.example.org")
        assertExternal("https", "accounts.google.com.example.org")
        assertExternal("https", "gds.google.com.example.org")
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
    fun `allows Google sorry challenge only when it returns to YouTube`() {
        assertInternalUrl(
            "https://www.google.com/sorry/index?" +
                "continue=https://m.youtube.com/watch%3Fv%3DQpEXO_XOHhI&q=challenge"
        )
        assertInternalUrl(
            "https://google.com/sorry/?" +
                "continue=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabc"
        )
        assertInternalUrl("https://www.google.com/sorry/index")
    }

    @Test
    fun `rejects unrelated or unsafe Google sorry urls`() {
        assertExternalUrl("https://www.google.com/search?q=youtube")
        assertExternalUrl(
            "https://www.google.com/sorry/index?continue=https%3A%2F%2Fexample.org%2F"
        )
        assertExternalUrl(
            "https://www.google.com/sorry/index?" +
                "continue=https%3A%2F%2Fyoutube.com.example.org%2Fwatch%3Fv%3Dabc"
        )
        assertExternalUrl(
            "http://www.google.com/sorry/index?" +
                "continue=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabc"
        )
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

    private fun assertInternalUrl(url: String) {
        assertTrue("$url should remain inside Gecko", LinkInterceptor.isInternalHttpNavigation(url))
    }

    private fun assertExternalUrl(url: String) {
        assertFalse("$url should leave Gecko", LinkInterceptor.isInternalHttpNavigation(url))
    }
}
