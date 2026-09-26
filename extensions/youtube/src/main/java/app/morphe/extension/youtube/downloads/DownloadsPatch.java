package app.morphe.extension.youtube.downloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Local YouTube downloader used by the Morphe in-app download hook.
 *
 * Direct downloads use unprotected URLs returned by InnerTube. Ciphered and
 * DRM/protected streams are left to YouTube's native offline path, preserving
 * YouTube-authorized license handling rather than attempting to extract keys.
 */
@SuppressWarnings("unused")
public final class DownloadsPatch {
    private static final String CHANNEL = "morphe_youtube_downloads";
    private static final String DEFAULT_CLIENT_VERSION = "21.38.123";
    private static final String USER_AGENT_PREFIX =
        "com.google.android.youtube/%s (Linux; U; Android %s) gzip";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long NOTIFICATION_INTERVAL_MS = 1_000L;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final AtomicInteger NEXT_NOTIFICATION_ID = new AtomicInteger(0x4D59);

    private DownloadsPatch() {}

    public static boolean onDownloadRequested(String videoId) {
        if (!isVideoId(videoId)) return false;

        // This method is injected into YouTube's UI callback. Keep it tiny:
        // validate the ID, enqueue work, and return. No network, MediaStore,
        // notification, JSON, or media work is allowed on this call stack.
        try {
            EXECUTOR.execute(() -> {
                try {
                    download(videoId);
                } catch (Throwable ex) {
                    Logger.printException(() -> "YouTube download worker crashed", ex);
                    Utils.runOnMainThread(() ->
                        Utils.showToastShort("Morphe: YouTube download failed"));
                }
            });
            Utils.showToastShort("Morphe: YouTube download started");
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "YouTube download hook failed", ex);
            return false;
        }
    }

    private static void download(String videoId) {
        Uri destination = null;
        String title = videoId;

        try {
            PlayerResponse player = resolve(videoId);
            if (player == null || player.url == null || player.url.isBlank()) {
                throw new IllegalStateException(
                    "No direct progressive MP4 stream was returned");
            }

            title = sanitize(player.title);
            Context context = Utils.getContext();
            destination = createMediaStoreFile(context, title + ".mp4");
            if (destination == null) {
                throw new IllegalStateException("Could not create MediaStore destination");
            }

            int notificationId = NEXT_NOTIFICATION_ID.getAndIncrement();
            post(context, notificationId, title, 0, true, "Downloading");

            long downloaded = streamToMediaStore(
                context, destination, player.url, player.contentLength,
                notificationId, title
            );

            markComplete(context, destination);
            post(context, notificationId, title, 100, false,
                formatSize(downloaded) + " • Download complete");
            Utils.showToastShort("Morphe: YouTube download complete");
        } catch (Throwable ex) {
            if (destination != null) {
                try {
                    Utils.getContext().getContentResolver()
                        .delete(destination, null, null);
                } catch (Exception ignored) {
                }
            }

            Logger.printException(() -> "YouTube in-app download failed", ex);
            post(Utils.getContext(), NEXT_NOTIFICATION_ID.getAndIncrement(),
                title, 0, false, "Download failed");
            Utils.showToastShort("Morphe: YouTube download failed");
        }
    }

    private static long streamToMediaStore(
        Context context,
        Uri destination,
        String url,
        long expectedLength,
        int notificationId,
        String title
    ) throws Exception {
        HttpURLConnection connection = openStream(url, context);

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Media request HTTP " + code);
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength <= 0) contentLength = expectedLength;

            try (
                BufferedInputStream input =
                    new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                OutputStream raw =
                    context.getContentResolver().openOutputStream(destination);
            ) {
                if (raw == null) {
                    throw new IllegalStateException("Output stream unavailable");
                }

                BufferedOutputStream output =
                    new BufferedOutputStream(raw, BUFFER_SIZE);

                byte[] buffer = new byte[BUFFER_SIZE];
                long downloaded = 0;
                long lastNotification = 0;
                int read;

                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloaded += read;

                    long now = System.currentTimeMillis();
                    if (now - lastNotification >= NOTIFICATION_INTERVAL_MS) {
                        int progress = contentLength > 0
                            ? (int) Math.min(99, downloaded * 100L / contentLength)
                            : -1;
                        post(context, notificationId, title, progress,
                            true, progress >= 0 ? "Downloading " + progress + "%" : "Downloading");
                        lastNotification = now;
                    }
                }

                output.flush();
                output.close();
                return downloaded;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openStream(String streamUrl, Context context)
        throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(streamUrl).openConnection();

        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", userAgent(context));
        connection.setRequestProperty("Accept", "video/mp4,*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static String userAgent(Context context) {
        return String.format(
            Locale.US,
            USER_AGENT_PREFIX,
            getYouTubeVersion(context),
            android.os.Build.VERSION.RELEASE
        );
    }

    private static PlayerResponse resolve(String videoId) throws Exception {
        JSONObject root = new JSONObject(requestPlayer(videoId));
        JSONObject details = root.optJSONObject("videoDetails");
        JSONObject streaming = root.optJSONObject("streamingData");

        if (streaming == null) return null;

        String title = details == null
            ? videoId
            : details.optString("title", videoId);

        JSONArray formats = streaming.optJSONArray("formats");
        if (formats == null) return null;

        JSONObject best = null;
        int bestHeight = -1;
        long bestLength = -1;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null) continue;

            String directUrl = format.optString("url", "");
            String mimeType = format.optString("mimeType", "");
            int height = format.optInt("height", 0);
            long length = format.optLong("contentLength", -1);

            // Deliberately reject ciphered/protected formats. No signature deciphering.
            if (directUrl.isBlank() || !mimeType.startsWith("video/mp4")) continue;

            if (height > bestHeight) {
                best = format;
                bestHeight = height;
                bestLength = length;
            }
        }

        return best == null
            ? null
            : new PlayerResponse(
                title,
                best.optString("url", ""),
                bestLength,
                best.optInt("width", 0),
                best.optInt("height", 0)
            );
    }

    private static String requestPlayer(String videoId) throws Exception {
        Context context = Utils.getContext();
        String clientVersion = getYouTubeVersion(context);
        String androidVersion = android.os.Build.VERSION.RELEASE;

        URL url = new URL(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        );

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent",
            String.format(Locale.US, USER_AGENT_PREFIX,
                clientVersion, androidVersion));

        JSONObject client = new JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", clientVersion)
            .put("androidSdkVersion", android.os.Build.VERSION.SDK_INT)
            .put("hl", Locale.getDefault().toLanguageTag());

        JSONObject body = new JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", new JSONObject().put("client", client));

        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.getOutputStream().write(request);

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Player request HTTP " + code);
            }

            try (java.io.InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String getYouTubeVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo("com.google.android.youtube", 0);
            if (info.versionName != null && !info.versionName.isBlank()) {
                return info.versionName;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read YouTube version: " + ex);
        }
        return DEFAULT_CLIENT_VERSION;
    }

    private static Uri createMediaStoreFile(Context context, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(
            MediaStore.Video.Media.RELATIVE_PATH,
            Environment.DIRECTORY_MOVIES + "/Morphe YouTube"
        );
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        return context.getContentResolver().insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        );
    }

    private static void markComplete(Context context, Uri destination) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        context.getContentResolver().update(destination, values, null, null);
    }

    private static boolean isVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static String sanitize(String value) {
        String result = value == null ? "" : value
            .replaceAll("[\\/:*?\"<>|]", "_")
            .replaceAll("\s+", " ")
            .trim();

        if (result.isEmpty()) result = "YouTube video";
        return result.length() > 120
            ? result.substring(0, 120).trim()
            : result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB",
                bytes / (1024d * 1024d));
        }
        return String.format(Locale.US, "%.2f GB",
            bytes / (1024d * 1024d * 1024d));
    }

    private static void post(
        Context context,
        int id,
        String title,
        int progress,
        boolean ongoing,
        String text
    ) {
        Utils.runOnMainThread(() -> {
            NotificationManager manager =
                (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                );
            if (manager == null) return;

            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL,
                "Morphe YouTube downloads",
                NotificationManager.IMPORTANCE_LOW
            ));

            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(
                    ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done
                )
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);

            if (ongoing) {
                builder.setProgress(100, Math.max(0, progress), progress < 0);
            }

            manager.notify(id, builder.build());
        });
    }

    private static final class PlayerResponse {
        final String title;
        final String url;
        final long contentLength;
        final int width;
        final int height;

        PlayerResponse(
            String title,
            String url,
            long contentLength,
            int width,
            int height
        ) {
            this.title = title;
            this.url = url;
            this.contentLength = contentLength;
            this.width = width;
            this.height = height;
        }
    }
}
