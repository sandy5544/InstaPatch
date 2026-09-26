package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DownloadsPatch;"

@Suppress("unused")
val youtubeInAppDownloadsPatch = bytecodePatch(
    name = "YouTube In-App Downloads",
    description = "Adds an experimental in-app download action using a background direct MP4 downloader. " +
        "Protected, ciphered, and entitlement-gated streams are not decoded or decrypted."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        OfflineVideoEndpointFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->onDownloadRequested(Ljava/lang/Object;)V
                """
            )
        }
    }
}
