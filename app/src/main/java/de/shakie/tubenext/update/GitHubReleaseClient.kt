package de.shakie.tubenext.update

import de.shakie.tubenext.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleaseClient {
    fun checkLatestRelease(): UpdateCheckResult {
        return runCatching {
            val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Tube-NEXT/${BuildConfig.VERSION_NAME}")
            }
            connection.use {
                if (responseCode !in 200..299) {
                    return UpdateCheckResult(
                        status = UpdateCheckStatus.CHECK_FAILED,
                        message = "GitHub hat HTTP $responseCode geliefert."
                    )
                }
                val release = parseRelease(readBody(connection))
                if (VersionNames.compare(release.versionName, BuildConfig.VERSION_NAME) <= 0) {
                    return UpdateCheckResult(UpdateCheckStatus.UP_TO_DATE, release)
                }
                if (release.compatibleAsset == null) {
                    return UpdateCheckResult(UpdateCheckStatus.NO_COMPATIBLE_ASSET, release)
                }
                UpdateCheckResult(UpdateCheckStatus.UPDATE_AVAILABLE, release)
            }
        }.getOrElse { throwable ->
            UpdateCheckResult(
                status = UpdateCheckStatus.CHECK_FAILED,
                message = throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    private fun parseRelease(json: String): UpdateRelease {
        val root = JSONObject(json)
        val tagName = root.optString("tag_name")
        val assetsJson = root.optJSONArray("assets")
        val assets = buildList {
            if (assetsJson != null) {
                for (index in 0 until assetsJson.length()) {
                    val assetJson = assetsJson.getJSONObject(index)
                    add(
                        UpdateAsset(
                            name = assetJson.optString("name"),
                            downloadUrl = assetJson.optString("browser_download_url"),
                            sizeBytes = assetJson.optLong("size", 0L),
                            contentType = assetJson.optString("content_type")
                        )
                    )
                }
            }
        }
        return UpdateRelease(
            tagName = tagName,
            versionName = VersionNames.normalize(tagName),
            htmlUrl = root.optString("html_url"),
            body = root.optString("body"),
            assets = assets
        )
    }

    private fun readBody(connection: HttpURLConnection): String {
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            return reader.readText()
        }
    }

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }

    private companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/ShakieVan/Tube-NEXT/releases/latest"
    }
}
