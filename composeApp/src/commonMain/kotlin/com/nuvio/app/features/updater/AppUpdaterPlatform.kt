package com.nuvio.app.features.updater

expect object AppUpdaterPlatform {
    val isSupported: Boolean
    val isDebugBuild: Boolean
    val updateAssetExtension: String
    val opensUpdatePageExternally: Boolean

    fun getSupportedAbis(): List<String>

    fun openUpdatePage(url: String): Boolean

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    fun getUpdateChannel(): String?

    fun setUpdateChannel(channel: String)

    fun getWhatsNewCache(): String?

    fun setWhatsNewCache(payload: String?)

    fun deleteDownloadedApk(path: String)

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<Unit>
}
