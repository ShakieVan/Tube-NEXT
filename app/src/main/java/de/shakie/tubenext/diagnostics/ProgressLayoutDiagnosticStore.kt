package de.shakie.tubenext.diagnostics

import android.content.Context
import de.shakie.tubenext.BuildConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ProgressLayoutDiagnosticStore(
    private val directory: File,
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        directory = File(context.filesDir, DIRECTORY_NAME),
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE
    )

    private val logFile = File(directory, FILE_NAME)

    @Synchronized
    fun append(tabId: String, payload: String): Boolean {
        if (payload.length > MAX_PAYLOAD_CHARACTERS) return false
        val diagnostic = payload.trim()
        if (!diagnostic.startsWith("{") || !diagnostic.endsWith("}") ||
            diagnostic.contains('\n') || diagnostic.contains('\r')
        ) {
            return false
        }
        val entry = buildString {
            append("{\"storedAtEpochMs\":")
            append(nowEpochMs())
            append(",\"appVersionName\":\"")
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
        return writeAtomically(retained)
    }

    @Synchronized
    fun count(): Int = readValidLines().size

    @Synchronized
    fun fileForSharing(): File? = logFile.takeIf { count() > 0 }

    @Synchronized
    fun clear(): Boolean {
        if (!logFile.exists()) return true
        return logFile.delete()
    }

    internal fun entries(): List<String> = readValidLines()

    private fun readValidLines(): List<String> {
        if (!logFile.isFile) return emptyList()
        return runCatching {
            logFile.useLines { lines ->
                lines.filter { line ->
                    line.startsWith("{") && line.endsWith("}")
                }.toList().takeLast(maxEntries.coerceAtLeast(1))
            }
        }.getOrDefault(emptyList())
    }

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
            try {
                Files.move(
                    temporaryFile.toPath(),
                    logFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    logFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val DIRECTORY_NAME = "diagnostics"
        private const val FILE_NAME = "fullscreen-progress-layout.jsonl"
        private const val DEFAULT_MAX_ENTRIES = 20
        private const val MAX_PAYLOAD_CHARACTERS = 48_000
    }
}
