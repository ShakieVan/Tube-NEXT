package de.shakie.tubenext.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetSelectorTest {
    @Test
    fun `selects first supported abi in device priority order`() {
        val assets = listOf(
            asset("Tube-NEXT-x86_64.apk"),
            asset("Tube-NEXT-arm64-v8a.apk"),
            asset("Tube-NEXT-armeabi-v7a.apk")
        )

        assertEquals(
            "Tube-NEXT-arm64-v8a.apk",
            UpdateAssetSelector.select(assets, listOf("arm64-v8a", "armeabi-v7a"))?.name
        )
    }

    @Test
    fun `does not confuse x86 with x86 64`() {
        val assets = listOf(
            asset("Tube-NEXT-x86_64.apk"),
            asset("Tube-NEXT-x86.apk")
        )

        assertEquals(
            "Tube-NEXT-x86.apk",
            UpdateAssetSelector.select(assets, listOf("x86"))?.name
        )
    }

    @Test
    fun `falls back to universal apk and ignores non apk assets`() {
        val assets = listOf(
            asset("checksums.txt"),
            asset("Tube-NEXT-universal.APK")
        )

        assertEquals(
            "Tube-NEXT-universal.APK",
            UpdateAssetSelector.select(assets, listOf("arm64-v8a"))?.name
        )
        assertNull(UpdateAssetSelector.select(listOf(asset("source.zip")), listOf("x86_64")))
    }

    private fun asset(name: String): UpdateAsset {
        return UpdateAsset(
            name = name,
            downloadUrl = "https://example.invalid/$name",
            sizeBytes = 1,
            contentType = "application/vnd.android.package-archive"
        )
    }
}
