package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Entry point patched into YouTube's native Download action.
 *
 * The visible action remains YouTube's own Download action. The patch only
 * replaces the backend with an in-app foreground service. No external
 * downloader application is launched.
 */
@SuppressWarnings("unused")
public final class DownloadsPatch {
    private DownloadsPatch() {
    }

    public static boolean start(String videoId) {
        if (!isVideoId(videoId)) {
            return false;
        }

        try {
            Context context = Utils.getContext();
            if (context == null) {
                Logger.printDebug(() -> "YouTube download: context unavailable");
                return false;
            }

            Intent intent = new Intent(context, YouTubeDownloadService.class)
                    .setAction(YouTubeDownloadService.ACTION_START)
                    .putExtra(YouTubeDownloadService.EXTRA_VIDEO_ID, videoId);

            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }

            return true;
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Could not start native in-app download service", ex);
            return false;
        }
    }

    private static boolean isVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }
}
