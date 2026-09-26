package app.morphe.extension.youtube.downloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class DownloadsPatch {
    private static final String CHANNEL = "morphe_youtube_downloads";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final int BUFFER = 64 * 1024;

    private DownloadsPatch() {}

    public static boolean onDownloadRequested(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return false;
        EXECUTOR.execute(() -> download(videoId));
        Utils.showToastShort("Morphe: YouTube download started");
        return true;
    }

    private static void download(String videoId) {
        Uri destination = null;
        try {
            Player player = resolve(videoId);
            if (player == null || player.url == null || player.url.isBlank()) {
                throw new IllegalStateException("No direct progressive MP4 stream was returned");
            }

            Context context = Utils.getContext();
            String title = player.title == null || player.title.isBlank() ? videoId : player.title;
            destination = createMediaStoreFile(context, sanitize(title) + ".mp4");
            if (destination == null) throw new IllegalStateException("Could not create media file");

            post(context, videoId, title, 0, true, "Downloading");

            HttpURLConnection connection = (HttpURLConnection) new URL(player.url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                "com.google.android.youtube/21.16.256 (Linux; U; Android) gzip");

            try (
                BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                OutputStream raw = context.getContentResolver().openOutputStream(destination)
            ) {
                if (raw == null) throw new IllegalStateException("Output stream unavailable");
                BufferedOutputStream output = new BufferedOutputStream(raw);
                byte[] buffer = new byte[BUFFER];
                int read;
                long downloaded = 0;
                long lastPost = 0;

                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastPost >= 1000) {
                        int progress = player.contentLength > 0
                            ? (int) Math.min(99, downloaded * 100 / player.contentLength)
                            : -1;
                        post(context, videoId, title, progress, true, "Downloading");
                        lastPost = now;
                    }
                }
                output.flush();
                output.close();
            } finally {
                connection.disconnect();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(destination, done, null, null);
            post(context, videoId, title, 100, false, "Download complete");
            Utils.showToastShort("Morphe: download complete");
        } catch (Exception ex) {
            if (destination != null) {
                try { Utils.getContext().getContentResolver().delete(destination, null, null); }
                catch (Exception ignored) {}
            }
            Logger.printException(() -> "YouTube in-app download failed", ex);
            post(Utils.getContext(), videoId, videoId, 0, false, "Download failed");
            Utils.showToastShort("Morphe: download failed");
        }
    }

    private static Uri createMediaStoreFile(Context context, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
            Environment.DIRECTORY_MOVIES + "/Morphe YouTube");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        return context.getContentResolver().insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    private static Player resolve(String videoId) throws Exception {
        JSONObject root = new JSONObject(requestPlayer(videoId));
        JSONObject details = root.optJSONObject("videoDetails");
        JSONObject streaming = root.optJSONObject("streamingData");
        if (streaming == null) return null;

        String title = details == null ? videoId : details.optString("title", videoId);
        JSONArray formats = streaming.optJSONArray("formats");
        if (formats == null) return null;

        JSONObject best = null;
        int bestHeight = -1;
        long contentLength = -1;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;

            String url = format.optString("url", "");
            String mime = format.optString("mimeType", "");
            int height = format.optInt("height", 0);

            // Do not decipher signatureCipher/cipher or touch DRM-protected formats.
            if (url.isBlank() || !mime.startsWith("video/mp4")) continue;

            if (height > bestHeight) {
                best = format;
                bestHeight = height;
                contentLength = format.optLong("contentLength", -1);
            }
        }

        return best == null ? null :
            new Player(title, best.optString("url"), contentLength);
    }

    private static String requestPlayer(String videoId) throws Exception {
        URL url = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent",
            "com.google.android.youtube/21.16.256 (Linux; U; Android) gzip");

        JSONObject body = new JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", new JSONObject().put("client",
                new JSONObject()
                    .put("clientName", "ANDROID")
                    .put("clientVersion", "21.16.256")
                    .put("androidSdkVersion", 35)
                    .put("hl", Locale.getDefault().toLanguageTag())));

        connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Player request HTTP " + code);
            }

            java.io.InputStream input = connection.getInputStream();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private static String sanitize(String value) {
        String result = value
            .replaceAll("[\\\\/:*?\\\"<>|]", "_")
            .replaceAll("\\s+", " ")
            .trim();
        if (result.isEmpty()) result = "YouTube video";
        return result.length() > 120 ? result.substring(0, 120).trim() : result;
    }

    private static void post(Context context, String key, String title, int progress,
                             boolean ongoing, String text) {
        Utils.runOnMainThread(() -> {
            NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Morphe YouTube downloads", NotificationManager.IMPORTANCE_LOW));

            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download
                    : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);

            if (ongoing) builder.setProgress(100, Math.max(0, progress), progress < 0);
            manager.notify(key.hashCode(), builder.build());
        });
    }

    private static final class Player {
        final String title;
        final String url;
        final long contentLength;

        Player(String title, String url, long contentLength) {
            this.title = title;
            this.url = url;
            this.contentLength = contentLength;
        }
    }
}
