package de.shakie.tubenext.diagnostics

import android.content.Context
import de.shakie.tubenext.BuildConfig
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProgressLayoutDiagnosticStore(
    private val directory: File,
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newEventId: () -> String = { UUID.randomUUID().toString() }
) {
    constructor(context: Context) : this(
        directory = File(context.filesDir, DIRECTORY_NAME),
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE
    )

    data class StoredEntry(
        val eventId: String,
        val screenshotFileName: String
    )

    private val logFile = File(directory, FILE_NAME)
    private val screenshotDirectory = File(directory, SCREENSHOT_DIRECTORY_NAME)
    private val archiveFile = File(directory, ARCHIVE_FILE_NAME)

    @Synchronized
    fun append(tabId: String, payload: String): Boolean = appendWithScreenshot(tabId, payload) != null

    @Synchronized
    fun appendWithScreenshot(tabId: String, payload: String): StoredEntry? {
        if (payload.length > MAX_PAYLOAD_CHARACTERS) return null
        val diagnostic = payload.trim()
        if (!diagnostic.startsWith("{") || !diagnostic.endsWith("}") ||
            diagnostic.contains('\n') || diagnostic.contains('\r')
        ) {
            return null
        }
        val storedAtEpochMs = nowEpochMs()
        val eventId = "progress-$storedAtEpochMs-${sanitizeEventId(newEventId())}"
        val screenshotFileName = "$eventId.jpg"
        val entry = buildString {
            append("{\"eventId\":\"")
            append(escapeJsonString(eventId))
            append("\",\"storedAtEpochMs\":")
            append(storedAtEpochMs)
            append(",\"storedAtIso8601\":\"")
            append(Instant.ofEpochMilli(storedAtEpochMs).toString())
            append("\",\"screenshotFileName\":\"")
            append(escapeJsonString(screenshotFileName))
            append("\",\"appVersionName\":\"")
            append(escapeJsonString(appVersionName))
            append("\",\"appVersionCode\":")
            append(appVersionCode)
            append(",\"tabId\":\"")
            append(escapeJsonString(tabId))
            append("\",\"diagnostic\":")
            append(diagnostic)
            append('}')
        }
        val retained = (readValidLines() + entry).takeLast(maxEntries.coerceAtLeast(1))
        if (!writeAtomically(retained)) return null
        pruneUnreferencedScreenshots(retained)
        archiveFile.delete()
        return StoredEntry(eventId = eventId, screenshotFileName = screenshotFileName)
    }

    @Synchronized
    fun saveScreenshot(entry: StoredEntry, encoder: (OutputStream) -> Boolean): Boolean {
        val retained = readValidLines()
        if (retained.none { line -> line.contains("\"eventId\":\"${entry.eventId}\"") }) {
            return false
        }
        return runCatching {
            check(screenshotDirectory.exists() || screenshotDirectory.mkdirs())
            val target = File(screenshotDirectory, entry.screenshotFileName)
            val temporary = File(screenshotDirectory, "${entry.screenshotFileName}.tmp")
            val encoded = temporary.outputStream().buffered().use(encoder)
            if (!encoded) {
                temporary.delete()
                return@runCatching false
            }
            moveReplacing(temporary, target)
            archiveFile.delete()
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun count(): Int = readValidLines().size

    @Synchronized
    fun archiveForSharing(): File? {
        val lines = readValidLines()
        if (lines.isEmpty()) return null
        return runCatching {
            check(directory.exists() || directory.mkdirs())
            val temporary = File(directory, "$ARCHIVE_FILE_NAME.tmp")
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(FILE_NAME))
                lines.forEach { line ->
                    zip.write(line.toByteArray(Charsets.UTF_8))
                    zip.write('\n'.code)
                }
                zip.closeEntry()
                referencedScreenshotNames(lines).forEach { name ->
                    val screenshot = File(screenshotDirectory, name)
                    if (!screenshot.isFile) return@forEach
                    zip.putNextEntry(ZipEntry("$SCREENSHOT_DIRECTORY_NAME/$name"))
                    screenshot.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            moveReplacing(temporary, archiveFile)
            archiveFile
        }.getOrNull()
    }

    @Synchronized
    fun clear(): Boolean {
        if (!directory.exists()) return true
        return directory.listFiles().orEmpty().all { file -> file.deleteRecursively() }
    }

    internal fun entries(): List<String> = readValidLines()

    private fun readValidLines(): List<String> {
        if (!logFile.isFile) return emptyList()
        return runCatching {
            logFile.useLines { lines ->
                lines.filter { line ->
                    line.startsWith("{") && line.endsWith("}")
                }.map(::addReadableTimestamp).toList().takeLast(maxEntries.coerceAtLeast(1))
            }
        }.getOrDefault(emptyList())
    }

    private fun addReadableTimestamp(line: String): String {
        if (line.contains("\"storedAtIso8601\":")) return line
        val match = STORED_AT_EPOCH_PATTERN.find(line) ?: return line
        val epochMs = match.groupValues[1].toLongOrNull() ?: return line
        val isoTimestamp = runCatching { Instant.ofEpochMilli(epochMs).toString() }.getOrNull()
            ?: return line
        return line.replaceRange(
            match.range,
            "${match.value},\"storedAtIso8601\":\"$isoTimestamp\""
        )
    }

    private fun referencedScreenshotNames(lines: List<String>): Set<String> = lines.mapNotNull { line ->
        SCREENSHOT_FILE_PATTERN.find(line)?.groupValues?.get(1)?.takeIf { name ->
            SAFE_SCREENSHOT_NAME.matches(name)
        }
    }.toSet()

    private fun pruneUnreferencedScreenshots(lines: List<String>) {
        val retainedNames = referencedScreenshotNames(lines)
        screenshotDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name !in retainedNames) {
                file.delete()
            }
        }
    }

    private fun sanitizeEventId(value: String): String = value
        .filter { character -> character.isLetterOrDigit() || character == '-' || character == '_' }
        .take(64)
        .ifBlank { "event" }

    private fun escapeJsonString(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }

    private fun writeAtomically(lines: List<String>): Boolean {
        return runCatching {
            check(directory.exists() || directory.mkdirs())
            val temporaryFile = File(directory, "$FILE_NAME.tmp")
            temporaryFile.bufferedWriter().use { writer ->
                lines.forEach { line ->
                    writer.append(line)
                    writer.newLine()
                }
            }
            moveReplacing(temporaryFile, logFile)
            true
        }.getOrDefault(false)
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "diagnostics"
        private const val FILE_NAME = "fullscreen-progress-layout.jsonl"
        private const val SCREENSHOT_DIRECTORY_NAME = "screenshots"
        private const val ARCHIVE_FILE_NAME = "fullscreen-progress-diagnostics.zip"
        private const val DEFAULT_MAX_ENTRIES = 20
        private const val MAX_PAYLOAD_CHARACTERS = 48_000
        private val STORED_AT_EPOCH_PATTERN = Regex("\\\"storedAtEpochMs\\\":(\\d{1,19})")
        private val SCREENSHOT_FILE_PATTERN = Regex("\\\"screenshotFileName\\\":\\\"([^\\\"]+)\\\"")
        private val SAFE_SCREENSHOT_NAME = Regex("[A-Za-z0-9._-]+\\.jpg")
    }
}
