package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

actual object DownloadsSettingsStorage {
    actual fun loadWatchedCleanup(): Boolean? = NSUserDefaults.standardUserDefaults.boolForKey("watched_download_cleanup")
    actual fun saveWatchedCleanup(value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = "watched_download_cleanup")
    }
    actual fun loadSeasonDownloads(): Boolean? = NSUserDefaults.standardUserDefaults.boolForKey("season_downloads_enabled")
    actual fun saveSeasonDownloads(value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = "season_downloads_enabled")
    }
    actual fun loadStorageLimitGb(): Int? = NSUserDefaults.standardUserDefaults.integerForKey("download_storage_limit_gb").toInt()
    actual fun saveStorageLimitGb(value: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(value.toLong(), forKey = "download_storage_limit_gb")
    }
    private const val allowMobileDataDownloadsKey = "allow_mobile_data_downloads"
    private const val showDownloadButtonKey = "show_download_button"

    actual fun loadAllowMobileDataDownloads(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(allowMobileDataDownloadsKey) != null) {
            defaults.boolForKey(allowMobileDataDownloadsKey)
        } else {
            null
        }
    }

    actual fun saveAllowMobileDataDownloads(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = allowMobileDataDownloadsKey)
    }

    actual fun loadShowDownloadButton(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(showDownloadButtonKey) != null) {
            defaults.boolForKey(showDownloadButtonKey)
        } else {
            null
        }
    }

    actual fun saveShowDownloadButton(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = showDownloadButtonKey)
    }
}
