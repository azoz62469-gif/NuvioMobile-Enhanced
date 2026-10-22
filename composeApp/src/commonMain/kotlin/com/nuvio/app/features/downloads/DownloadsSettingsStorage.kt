package com.nuvio.app.features.downloads

internal expect object DownloadsSettingsStorage {
    fun loadAllowMobileDataDownloads(): Boolean?
    fun saveAllowMobileDataDownloads(enabled: Boolean)
    fun loadShowDownloadButton(): Boolean?
    fun saveShowDownloadButton(enabled: Boolean)
    fun loadStorageLimitGb(): Int?
    fun saveStorageLimitGb(value: Int)
    fun loadSeasonDownloads(): Boolean?
    fun saveSeasonDownloads(value: Boolean)
    fun loadWatchedCleanup(): Boolean?
    fun saveWatchedCleanup(value: Boolean)
}
