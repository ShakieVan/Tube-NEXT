package de.shakie.tubenext.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class UpdateDownloader(
    private val context: Context,
    private val preferences: UpdatePreferences
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun download(
        release: UpdateRelease,
        asset: UpdateAsset,
        listener: Listener
    ): DownloadHandle {
        val cancelled = AtomicBoolean(false)
        val targetFile = targetFile(release, asset)
        val partialFile = File(targetFile.parentFile, "${targetFile.name}.part")

        Thread(Runnable {
            runCatching {
                targetFile.parentFile?.mkdirs()
                if (partialFile.exists()) partialFile.delete()
                val connection = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Tube-NEXT")
                }
                connection.use {
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("HTTP $responseCode")
                    }
                    val totalBytes = contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
                    var downloadedBytes = 0L
                    inputStream.use { input ->
                        partialFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                if (cancelled.get()) {
                                    partialFile.delete()
                                    post { listener.onCancelled() }
                                    return@Runnable
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                post {
                                    listener.onProgress(downloadedBytes, totalBytes)
                                }
                            }
                        }
                    }
                }
                if (targetFile.exists()) targetFile.delete()
                if (!partialFile.renameTo(targetFile)) {
                    throw IllegalStateException("Download konnte nicht abgeschlossen werden.")
                }
                preferences.saveDownloadedApk(release, asset, targetFile)
                post { listener.onCompleted(targetFile) }
            }.getOrElse { throwable ->
                partialFile.delete()
                post { listener.onError(throwable.message ?: throwable.javaClass.simpleName) }
            }
        }).apply {
            name = "TubeNextUpdateDownload"
            start()
        }

        return DownloadHandle {
            cancelled.set(true)
        }
    }

    fun deleteDownloadedApk(release: UpdateRelease) {
        preferences.downloadedApkFor(release)?.delete()
        preferences.clearDownloadedApk()
    }

    private fun targetFile(release: UpdateRelease, asset: UpdateAsset): File {
        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeAsset = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, "updates/$safeTag"), safeAsset)
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun interface DownloadHandle {
        fun cancel()
    }

    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onCompleted(file: File)
        fun onCancelled()
        fun onError(message: String)
    }

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
