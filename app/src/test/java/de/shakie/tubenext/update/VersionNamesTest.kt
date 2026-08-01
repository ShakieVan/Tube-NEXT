package de.shakie.tubenext.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNamesTest {
    @Test
    fun `normalizes tags release prefixes and build suffixes`() {
        assertEquals("1.3.8", VersionNames.normalize(" refs/tags/v1.3.8 "))
        assertEquals("1.4.0", VersionNames.normalize("release/v1.4.0-debug"))
        assertEquals("2.0", VersionNames.normalize("v2.0-local"))
    }

    @Test
    fun `compares numeric components and treats missing parts as zero`() {
        assertTrue(VersionNames.compare("v1.4.0", "1.3.9") > 0)
        assertTrue(VersionNames.compare("1.3.7", "1.3.8") < 0)
        assertEquals(0, VersionNames.compare("1.3", "1.3.0"))
        assertEquals(0, VersionNames.compare("1.3.8-debug", "refs/tags/v1.3.8"))
    }
}
