package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_subtitle_picker_failed_copy
import nuvio.composeapp.generated.resources.autosync_subtitle_picker_failed_open
import nuvio.composeapp.generated.resources.autosync_subtitle_picker_failed_read
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalForeignApi::class)
internal actual fun rememberSubtitleFilePicker(
    onPicked: (fileUrl: String) -> Unit,
    onError: (message: String) -> Unit,
): () -> Unit {
    val delegateHolder = remember { mutableStateOf<SubtitleDocumentPickerDelegate?>(null) }
    val unableToOpen = stringResource(Res.string.autosync_subtitle_picker_failed_open)
    val unableToCopy = stringResource(Res.string.autosync_subtitle_picker_failed_copy)
    val unableToRead = stringResource(Res.string.autosync_subtitle_picker_failed_read)

    return {
        val presenter = topSubtitlePickerController()
        if (presenter == null) {
            onError(unableToOpen)
        } else {
            val picker = UIDocumentPickerViewController(
                documentTypes = listOf("public.text", "public.data"),
                inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
            )
            val delegate = SubtitleDocumentPickerDelegate(
                onPicked = { url ->
                    val didStartAccess = url.startAccessingSecurityScopedResource()
                    try {
                        val copiedUrl = copySubtitleToTemporaryDirectory(url)
                        if (copiedUrl == null) {
                            onError(unableToCopy)
                        } else {
                            onPicked(copiedUrl)
                        }
                    } catch (_: Throwable) {
                        onError(unableToRead)
                    } finally {
                        if (didStartAccess) {
                            url.stopAccessingSecurityScopedResource()
                        }
                        delegateHolder.value = null
                    }
                },
                onDismissed = {
                    delegateHolder.value = null
                },
            )
            delegateHolder.value = delegate
            picker.delegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun copySubtitleToTemporaryDirectory(url: NSURL): String? {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/nuvio-external-subtitles"
    val fileManager = NSFileManager.defaultManager
    fileManager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val extension = url.pathExtension
        ?.takeIf { it.isNotBlank() }
        ?.let { ".$it" }
        ?: ".srt"
    val destinationPath = "$directory/${NSUUID().UUIDString}$extension"
    val destinationUrl = NSURL.fileURLWithPath(destinationPath)
    return if (fileManager.copyItemAtURL(url, destinationUrl, null)) {
        destinationUrl.absoluteString
    } else {
        null
    }
}

private class SubtitleDocumentPickerDelegate(
    private val onPicked: (NSURL) -> Unit,
    private val onDismissed: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onDismissed()
        } else {
            onPicked(url)
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDismissed()
    }
}

private fun topSubtitlePickerController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
