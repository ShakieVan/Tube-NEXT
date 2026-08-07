package de.shakie.tubenext.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ProgressLayoutDiagnosticExporter(
    context: Context,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val contentResolver = context.applicationContext.contentResolver

    data class ExportedFile(
        val displayName: String,
        val relativePath: String
    )

    fun saveToDownloads(sourceFile: File): Result<ExportedFile> = runCatching {
        check(sourceFile.isFile) { "Diagnostic source file does not exist" }
        val displayName = buildDisplayName(nowEpochMs())
        val relativeDirectory = "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_DIRECTORY/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Could not create diagnostic export" }

        try {
            val outputStream = checkNotNull(contentResolver.openOutputStream(uri, "w")) {
                "Could not open diagnostic export"
            }
            sourceFile.inputStream().use { input ->
                outputStream.use { output -> input.copyTo(output) }
            }
            check(
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                ) == 1
            ) { "Could not publish diagnostic export" }
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }

        ExportedFile(
            displayName = displayName,
            relativePath = "$relativeDirectory$displayName"
        )
    }

    companion object {
        private const val EXPORT_DIRECTORY = "Tube NEXT"
        private const val MIME_TYPE = "application/zip"
        private val FILE_NAME_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault())

        internal fun buildDisplayName(epochMs: Long): String {
            val timestamp = FILE_NAME_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMs))
            return "Tube-NEXT-fullscreen-progress-$timestamp.zip"
        }
    }
}
