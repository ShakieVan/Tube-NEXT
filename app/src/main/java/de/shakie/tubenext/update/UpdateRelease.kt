package de.shakie.tubenext.update

data class UpdateRelease(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val body: String,
    val assets: List<UpdateAsset>
) {
    val compatibleAsset: UpdateAsset?
        get() = UpdateAssetSelector.select(assets)
}

data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String
)

enum class UpdateCheckStatus {
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    NO_COMPATIBLE_ASSET,
    CHECK_FAILED
}

data class UpdateCheckResult(
    val status: UpdateCheckStatus,
    val release: UpdateRelease? = null,
    val message: String? = null
)
