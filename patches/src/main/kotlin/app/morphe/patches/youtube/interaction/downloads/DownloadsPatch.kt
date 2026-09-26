package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DownloadsPatch;"

@Suppress("unused")
val youtubeInAppDownloadsPatch = bytecodePatch(
    name = "YouTube In-App Downloads",
    description = "Uses the in-app Download action to start a background local MP4 download. " +
        "Unsupported, protected, ciphered, and DRM streams are rejected by the downloader."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        OfflineVideoEndpointFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->start(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :show_native_downloader
                    return-void
                    :show_native_downloader
                    nop
                """
            )
        }
    }
}
