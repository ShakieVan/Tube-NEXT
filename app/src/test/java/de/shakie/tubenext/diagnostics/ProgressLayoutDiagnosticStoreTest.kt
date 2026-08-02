package de.shakie.tubenext.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProgressLayoutDiagnosticStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retainsOnlyNewestEntriesAcrossStoreInstances() {
        val directory = temporaryFolder.newFolder("diagnostics")
        val store = ProgressLayoutDiagnosticStore(
            directory = directory,
            appVersionName = "1.4.1-diagnostic",
            appVersionCode = 17,
            maxEntries = 3,
            nowEpochMs = { 1234L }
        )

        repeat(5) { index ->
            assertTrue(store.append("tab-$index", "{\"sequence\":$index}"))
        }

        val reopened = ProgressLayoutDiagnosticStore(
            directory = directory,
            appVersionName = "1.4.1-diagnostic",
            appVersionCode = 17,
            maxEntries = 3
        )
        assertEquals(3, reopened.count())
        assertEquals(
            listOf(2, 3, 4),
            reopened.entries().map { line ->
                Regex("\\\"sequence\\\":(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
            }
        )
    }

    @Test
    fun rejectsInvalidOrOversizedPayloads() {
        val store = ProgressLayoutDiagnosticStore(
            directory = temporaryFolder.newFolder("invalid"),
            appVersionName = "test",
            appVersionCode = 1
        )

        assertFalse(store.append("tab", "not-json"))
        assertFalse(store.append("tab", "{\"value\":\"${"x".repeat(48_000)}\"}"))
        assertEquals(0, store.count())
    }

    @Test
    fun clearRemovesShareableLog() {
        val store = ProgressLayoutDiagnosticStore(
            directory = temporaryFolder.newFolder("clear"),
            appVersionName = "test",
            appVersionCode = 1
        )

        assertTrue(store.append("tab", "{\"reason\":\"wrapped\"}"))
        assertTrue(store.fileForSharing()?.isFile == true)
        assertTrue(store.clear())
        assertEquals(0, store.count())
        assertEquals(null, store.fileForSharing())
    }
}
