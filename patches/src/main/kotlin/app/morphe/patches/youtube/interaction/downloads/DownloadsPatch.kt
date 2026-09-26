package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/downloads/DownloadsPatch;"

@Suppress("unused")
val youtubeInAppDownloadsPatch = bytecodePatch(
    name = "YouTube In-App Downloads",
    description = "Adds an experimental in-app download action to YouTube with adaptive video + audio downloads. " +
        "Protected, ciphered, and entitlement-gated streams remain on YouTube's native offline/DRM path."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        OfflineVideoEndpointFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->onDownloadRequested(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :continue_native_download
                    return-void
                    :continue_native_download
                    nop
                """
            )
        }
    }
}
