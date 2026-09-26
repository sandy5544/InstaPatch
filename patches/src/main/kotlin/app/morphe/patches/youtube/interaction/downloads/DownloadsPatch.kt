package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val downloadsResourcePatch = resourcePatch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        copyResources(
            "downloads",
            ResourceGroup(
                "drawable",
                "morphe_yt_download_button.xml",
                "morphe_yt_download_button_bold.xml",
            )
        )
    }

    finalize {
        addTopControl(
            "downloads",
            "@+id/morphe_external_download_button",
            "@+id/morphe_external_download_button"
        )
    }
}

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/ExternalDownloadButton;"

@Suppress("unused")
val youtubeInAppDownloadsPatch = bytecodePatch(
    name = "YouTube In-App Downloads",
    description = "Adds a safe in-app MP4 download button. " +
        "Native YouTube downloads remain untouched for protected or unsupported streams.",
) {
    dependsOn(
        downloadsResourcePatch,
        legacyPlayerControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        initializeTopControl(EXTENSION_BUTTON)
    }
}
