package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.youtube.patches.DownloadsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;

@SuppressWarnings("unused")
public final class ExternalDownloadButton {

    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_external_download_button",
                    null,
                    "morphe_yt_download_button",
                    SharedYouTubeSettings.EXTERNAL_DOWNLOADER,
                    ExternalDownloadButton::onDownloadClick,
                    null
            );
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "initialize download button failure", ex);
        }
    }

    private static void onDownloadClick(View view) {
        try {
            String videoId = VideoInformation.getVideoId();
            DownloadsPatch.start(videoId);
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "download button click failure", ex);
        }
    }
}
