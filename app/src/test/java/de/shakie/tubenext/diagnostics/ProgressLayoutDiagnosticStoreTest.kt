package de.shakie.tubenext.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class ProgressLayoutDiagnosticStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retainsOnlyNewestEntriesAcrossStoreInstances() {
        val directory = temporaryFolder.newFolder("diagnostics")
        var eventIndex = 0
        val store = ProgressLayoutDiagnosticStore(
            directory = directory,
            appVersionName = "1.4.2-diagnostic",
            appVersionCode = 18,
            maxEntries = 3,
            nowEpochMs = { 1234L },
            newEventId = { "event-${eventIndex++}" }
        )

        repeat(5) { index ->
            assertTrue(store.append("tab-$index", "{\"sequence\":$index}"))
        }

        val reopened = ProgressLayoutDiagnosticStore(
            directory = directory,
            appVersionName = "1.4.2-diagnostic",
            appVersionCode = 18,
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
    fun addsStableIdReadableTimestampAndScreenshotName() {
        val store = ProgressLayoutDiagnosticStore(
            directory = temporaryFolder.newFolder("metadata"),
            appVersionName = "1.4.2-diagnostic",
            appVersionCode = 18,
            nowEpochMs = { 1_786_118_955_123L },
            newEventId = { "fixed-id" }
        )

        val storedEntry = store.appendWithScreenshot("tab", "{\"reason\":\"wrapped\"}")
        assertNotNull(storedEntry)
        val entry = storedEntry!!
        val line = store.entries().single()
        assertEquals("progress-1786118955123-fixed-id", entry.eventId)
        assertEquals("progress-1786118955123-fixed-id.jpg", entry.screenshotFileName)
        assertTrue(line.contains("\"eventId\":\"${entry.eventId}\""))
        assertTrue(line.contains("\"storedAtEpochMs\":1786118955123"))
        assertTrue(line.contains("\"storedAtIso8601\":\"2026-08-07T16:09:15.123Z\""))
        assertTrue(line.contains("\"screenshotFileName\":\"${entry.screenshotFileName}\""))
    }

    @Test
    fun exportedLegacyEntryReceivesReadableTimestamp() {
        val directory = temporaryFolder.newFolder("legacy")
        java.io.File(directory, "fullscreen-progress-layout.jsonl").writeText(
            "{\"storedAtEpochMs\":1786118955123,\"diagnostic\":{}}\n"
        )
        val store = ProgressLayoutDiagnosticStore(
            directory = directory,
            appVersionName = "test",
            appVersionCode = 1
        )

        val archive = store.archiveForSharing()!!
        ZipFile(archive).use { zip ->
            val log = zip.getInputStream(zip.getEntry("fullscreen-progress-layout.jsonl"))
                .bufferedReader().readText()
            assertTrue(log.contains("\"storedAtIso8601\":\"2026-08-07T16:09:15.123Z\""))
        }
    }

    @Test
    fun archiveContainsLogAndMatchingScreenshot() {
        val store = ProgressLayoutDiagnosticStore(
            directory = temporaryFolder.newFolder("archive"),
            appVersionName = "test",
            appVersionCode = 1,
            nowEpochMs = { 1234L },
            newEventId = { "archive-event" }
        )
        val storedEntry = store.appendWithScreenshot("tab", "{\"reason\":\"wrapped\"}")
        assertNotNull(storedEntry)
        val entry = storedEntry!!
        assertTrue(store.saveScreenshot(entry) { output ->
            output.write(byteArrayOf(1, 2, 3, 4))
            true
        })

        val storedArchive = store.archiveForSharing()
        assertNotNull(storedArchive)
        val archive = storedArchive!!
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("fullscreen-progress-layout.jsonl"))
            val screenshot = zip.getEntry("screenshots/${entry.screenshotFileName}")
            assertNotNull(screenshot)
            assertEquals(
                listOf<Byte>(1, 2, 3, 4),
                zip.getInputStream(screenshot!!).readBytes().toList()
            )
        }
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
    fun clearRemovesLogScreenshotAndArchive() {
        val store = ProgressLayoutDiagnosticStore(
            directory = temporaryFolder.newFolder("clear"),
            appVersionName = "test",
            appVersionCode = 1,
            newEventId = { "clear-event" }
        )
        val storedEntry = store.appendWithScreenshot("tab", "{\"reason\":\"wrapped\"}")
        assertNotNull(storedEntry)
        val entry = storedEntry!!
        assertTrue(store.saveScreenshot(entry) { output ->
            output.write(1)
            true
        })
        assertNotNull(store.archiveForSharing())

        assertTrue(store.clear())
        assertEquals(0, store.count())
        assertEquals(null, store.archiveForSharing())
        assertTrue(temporaryFolder.root.walkTopDown().none { file -> file.extension == "jpg" })
        assertTrue(temporaryFolder.root.walkTopDown().none { file -> file.extension == "zip" })
    }
}
