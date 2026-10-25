package com.nuvio.app.features.updater

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_not_available
import org.jetbrains.compose.resources.getString

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = true
    actual val isDebugBuild: Boolean = false
    actual val updateAssetExtension: String = "ipa"
    actual val opensUpdatePageExternally: Boolean = true

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun openUpdatePage(url: String): Boolean {
        val nativeUrl = NSURL(string = url) ?: return false
        val application = UIApplication.sharedApplication
        if (!application.canOpenURL(nativeUrl)) return false
        application.openURL(
            url = nativeUrl,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }

    actual fun getIgnoredTag(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("nuvio_ignored_update_tag")

    actual fun setIgnoredTag(tag: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (tag == null) {
            defaults.removeObjectForKey("nuvio_ignored_update_tag")
        } else {
            defaults.setObject(tag, forKey = "nuvio_ignored_update_tag")
        }
    }

    actual fun getUpdateChannel(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("nuvio_update_channel")

    actual fun setUpdateChannel(channel: String) {
        NSUserDefaults.standardUserDefaults.setObject(channel, forKey = "nuvio_update_channel")
    }

    actual fun getWhatsNewCache(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey("nuvio_whats_new_cache")

    actual fun setWhatsNewCache(payload: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey("nuvio_whats_new_cache")
        } else {
            defaults.setObject(payload, forKey = "nuvio_whats_new_cache")
        }
    }

    actual fun deleteDownloadedApk(path: String) = Unit

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = Result.failure(IllegalStateException(getString(Res.string.updates_not_available)))

    actual fun canRequestPackageInstalls(): Boolean = false

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> =
        Result.failure(IllegalStateException(runBlocking { getString(Res.string.updates_not_available) }))
}
