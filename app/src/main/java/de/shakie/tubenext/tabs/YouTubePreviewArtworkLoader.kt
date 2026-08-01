package de.shakie.tubenext.tabs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class YouTubePreviewArtworkLoader {
    fun load(videoId: String): Bitmap? {
        val artworkUrl = artworkUrlForVideoId(videoId) ?: return null
        val connection = runCatching {
            (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "image/jpeg")
            }
        }.getOrNull() ?: return null

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_IMAGE_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    declaredLength.takeIf { it in 1..MAX_IMAGE_BYTES }
                        ?.toInt()
                        ?: INITIAL_BUFFER_BYTES
                )
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000
        private const val MAX_IMAGE_BYTES = 2L * 1024L * 1024L
        private const val INITIAL_BUFFER_BYTES = 32 * 1024
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")

        fun artworkUrlForVideoId(videoId: String): String? {
            if (!VIDEO_ID_REGEX.matches(videoId)) return null
            return "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        }
    }
}
