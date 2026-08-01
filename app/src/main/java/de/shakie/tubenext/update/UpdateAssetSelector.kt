package de.shakie.tubenext.update

import android.os.Build

object UpdateAssetSelector {
    fun select(assets: List<UpdateAsset>): UpdateAsset? {
        return select(assets, Build.SUPPORTED_ABIS.toList())
    }

    internal fun select(assets: List<UpdateAsset>, supportedAbis: List<String>): UpdateAsset? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true)
        }
        if (apkAssets.isEmpty()) return null

        supportedAbis.forEach { abi ->
            apkAssets.firstOrNull { asset ->
                containsAbiToken(asset.name, abi)
            }?.let { return it }
        }

        return apkAssets.firstOrNull { asset ->
            asset.name.contains("universal", ignoreCase = true)
        }
    }

    private fun containsAbiToken(assetName: String, abi: String): Boolean {
        if (abi.isBlank()) return false
        val boundaryPattern = Regex(
            "(^|[^a-z0-9_])${Regex.escape(abi.lowercase())}([^a-z0-9_]|$)",
            RegexOption.IGNORE_CASE
        )
        return boundaryPattern.containsMatchIn(assetName)
    }
}
