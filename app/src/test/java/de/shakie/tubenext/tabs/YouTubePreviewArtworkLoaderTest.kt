package de.shakie.tubenext.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePreviewArtworkLoaderTest {
    @Test
    fun `builds only fixed official thumbnail URLs`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc_123-XYZ/mqdefault.jpg",
            YouTubePreviewArtworkLoader.artworkUrlForVideoId("abc_123-XYZ")
        )
        assertNull(YouTubePreviewArtworkLoader.artworkUrlForVideoId("../escape"))
        assertNull(YouTubePreviewArtworkLoader.artworkUrlForVideoId("id?redirect=example.com"))
        assertNull(YouTubePreviewArtworkLoader.artworkUrlForVideoId(""))
    }
}
