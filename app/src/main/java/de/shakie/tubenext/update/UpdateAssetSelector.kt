package de.shakie.tubenext.update

import android.os.Build

object UpdateAssetSelector {
    fun select(assets: List<UpdateAsset>): UpdateAsset? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true)
        }
        if (apkAssets.isEmpty()) return null

        Build.SUPPORTED_ABIS.forEach { abi ->
            apkAssets.firstOrNull { asset ->
                asset.name.contains(abi, ignoreCase = true)
            }?.let { return it }
        }

        return apkAssets.firstOrNull { asset ->
            asset.name.contains("universal", ignoreCase = true)
        }
    }
}
