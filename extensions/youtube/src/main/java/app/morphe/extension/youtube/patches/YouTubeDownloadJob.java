package app.morphe.extension.youtube.patches;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

final class YouTubeDownloadJob {
    interface Progress {
        void update(int percent, String text);
    }

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized;
    private static final String PROCESS_PREFIX = "morphe-youtube-";

    private YouTubeDownloadJob() {
    }

    static void run(
            Context context,
            String videoId,
            Progress progress) throws Exception {
        ensureEngine(context);

        File workDir = new File(
                context.getCacheDir(),
                "morphe-youtube/" + System.nanoTime());

        if (!workDir.mkdirs() && !workDir.isDirectory()) {
            throw new IllegalStateException("Could not create downloader workspace");
        }

        String processId = PROCESS_PREFIX + System.nanoTime();
        File output = null;
        Uri destination = null;

        try {
            progress.update(0, "Starting yt-dlp");

            YoutubeDLRequest request = new YoutubeDLRequest(
                    "https://www.youtube.com/watch?v=" + videoId);

            request.addOption("--no-playlist");
            request.addOption("--no-mtime");
            request.addOption("--newline");
            request.addOption("-f",
                    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]");
            request.addOption("--merge-output-format", "mp4");
            request.addOption("-o",
                    new File(workDir, "%(title)s.%(ext)s").getAbsolutePath());

            YoutubeDL.getInstance().execute(
                    request,
                    processId,
                    (percent, etaSeconds, line) -> {
                        int safePercent = percent == null
                                ? -1
                                : Math.max(0, Math.min(99, percent.intValue()));
                        String message = line == null || line.trim().isEmpty()
                                ? "Downloading"
                                : line.trim();
                        progress.update(safePercent, message);
                        return kotlin.Unit.INSTANCE;
                    });

            output = findDownloadedMp4(workDir);
            if (output == null || output.length() <= 0) {
                throw new IllegalStateException(
                        "yt-dlp completed without an MP4 output");
            }

            progress.update(96, "Saving to Movies/Morphe YouTube");

            String displayName = sanitizeName(output.getName());
            destination = createDestination(context, displayName);

            if (destination == null) {
                throw new IllegalStateException("Could not create MediaStore item");
            }

            copyToMediaStore(context, output, destination);
            publish(context, destination);
            destination = null;

            progress.update(100, "Download complete");
            Utils.runOnMainThread(() ->
                    Utils.showToastShort("Morphe: YouTube download complete"));
        } finally {
            if (destination != null) {
                deleteDestination(context, destination);
            }

            try {
                YoutubeDL.getInstance().destroyProcessById(processId);
            } catch (Throwable ex) {
                Logger.printDebug(() ->
                        "Could not stop yt-dlp process: " + ex);
            }

            deleteRecursively(workDir);
        }
    }

    private static void ensureEngine(Context context) throws Exception {
        if (initialized) return;

        synchronized (INIT_LOCK) {
            if (initialized) return;

            try {
                YoutubeDL.getInstance().init(context.getApplicationContext());
                FFmpeg.getInstance().init(context.getApplicationContext());
                initialized = true;
            } catch (Throwable ex) {
                initialized = false;
                throw ex;
            }
        }
    }

    private static File findDownloadedMp4(File workDir) {
        File[] files = workDir.listFiles();
        if (files == null) return null;

        File best = null;
        for (File file : files) {
            if (!file.isFile()) continue;
            if (!file.getName().toLowerCase(Locale.US).endsWith(".mp4")) continue;

            if (best == null || file.lastModified() > best.lastModified()) {
                best = file;
            }
        }
        return best;
    }

    private static Uri createDestination(Context context, String name) {
        ContentValues values = new ContentValues();
        values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                name.endsWith(".mp4") ? name : name + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Morphe YouTube");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        try {
            return context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values);
        } catch (Throwable ex) {
            Logger.printException(() -> "MediaStore insert failed", ex);
            return null;
        }
    }

    private static void copyToMediaStore(
            Context context,
            File source,
            Uri destination) throws Exception {
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output =
                     context.getContentResolver().openOutputStream(
                             destination, "w")) {
            if (output == null) {
                throw new IllegalStateException(
                        "MediaStore output stream unavailable");
            }

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void publish(Context context, Uri destination) {
        if (Build.VERSION.SDK_INT < 29) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);

        int updated = context.getContentResolver().update(
                destination, values, null, null);

        if (updated <= 0) {
            throw new IllegalStateException(
                    "MediaStore publish did not update target row");
        }
    }

    private static void deleteDestination(Context context, Uri destination) {
        try {
            context.getContentResolver().delete(
                    destination, null, null);
        } catch (Throwable ignored) {
        }
    }

    private static String sanitizeName(String value) {
        String result = value == null
                ? "YouTube video.mp4"
                : value.replaceAll("[\\/:*?\"<>|]", "_")
                        .replaceAll("\\s+", " ")
                        .trim();

        if (result.isEmpty()) result = "YouTube video.mp4";
        if (!result.toLowerCase(Locale.US).endsWith(".mp4")) {
            result += ".mp4";
        }

        return result.length() > 140
                ? result.substring(0, 136) + ".mp4"
                : result;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;

        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }
}
