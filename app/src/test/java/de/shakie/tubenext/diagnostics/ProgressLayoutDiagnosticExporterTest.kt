package de.shakie.tubenext.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ProgressLayoutDiagnosticExporterTest {
    @Test
    fun `export name contains local timestamp and jsonl suffix`() {
        val timestamp = ZonedDateTime.of(
            2026,
            8,
            7,
            16,
            29,
            15,
            123_000_000,
            ZoneId.systemDefault()
        ).toInstant().toEpochMilli()
        assertEquals(
            "Tube-NEXT-fullscreen-progress-20260807-162915-123.jsonl",
            ProgressLayoutDiagnosticExporter.buildDisplayName(timestamp)
        )
    }
}
