package app.morphe.extension.youtube.patches;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Safe local YouTube downloader.
 *
 * Resolves streams through Morphe's shared YouTube resolver, including the
 * configured spoof client, PoToken and JavaScript deobfuscation where required.
 * Only resulting direct, unprotected MP4 URLs are written locally. DRM or
 * otherwise protected media is not decrypted or bypassed.
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
    private static final AtomicInteger NEXT_NOTIFICATION_ID =
            new AtomicInteger(0x4D59);

    private DownloadsPatch() {
    }

    /**
     * Called by the dedicated player action button.
     * This method never performs network or media work on the UI thread.
     */
    public static void onDownloadRequested(Object maybeVideoId) {
        if (!(maybeVideoId instanceof String)) {
            return;
        }

        String videoId = (String) maybeVideoId;
        if (!isVideoId(videoId)) {
            return;
        }

        start(videoId);
    }

    public static boolean onDownloadRequested(String videoId) {
        return start(videoId);
    }

    public static boolean start(String videoId) {
        if (!isVideoId(videoId)) {
            safeToast("Morphe: invalid YouTube video ID");
            return false;
        }

        try {
            Selection selection = resolve(videoId);
            if (selection == null) {
                safeToast("Morphe: stream is not supported by the local downloader");
                return false;
            }

            EXECUTOR.execute(() -> {
                try {
                    download(videoId, selection);
                } catch (Throwable ex) {
                    Logger.printException(
                            () -> "YouTube download worker crashed", ex);
                    safeToast("Morphe: YouTube download failed");
                }
            });
            safeToast("Morphe: YouTube download started");
            return true;
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "YouTube download queue failure", ex);
            safeToast("Morphe: YouTube download failed");
            return false;
        }
    }

    private static void download(String videoId, Selection selection) {
        Context context = Utils.getContext();
        if (context == null) {
            throw new IllegalStateException("Morphe context is unavailable");
        }

        Uri destination = null;
        File videoTemp = null;
        File audioTemp = null;
        String title = videoId;
        int notificationId = NEXT_NOTIFICATION_ID.getAndIncrement();

        try {
            title = sanitize(selection.title);
            post(context, notificationId, title, 0, true, "Preparing");

            if (selection.progressiveUrl != null) {
                destination = createMediaStoreFile(context, title + ".mp4");
                if (destination == null) {
                    throw new IllegalStateException(
                            "Could not create MediaStore destination");
                }

                long bytes = streamToMediaStore(
                        context,
                        destination,
                        selection.progressiveUrl,
                        selection.progressiveLength,
                        notificationId,
                        title
                );

                publish(context, destination);
                destination = null;

                post(
                        context,
                        notificationId,
                        title,
                        100,
                        false,
                        formatSize(bytes) + " • Download complete"
                );
                safeToast("Morphe: YouTube download complete");
                return;
            }

            if (selection.videoUrl == null || selection.audioUrl == null) {
                throw new UnsupportedOperationException(
                        "Adaptive MP4 video/audio pair unavailable");
            }

            videoTemp = new File(
                    context.getCacheDir(),
                    "morphe_yt_video_" + System.nanoTime() + ".mp4"
            );
            audioTemp = new File(
                    context.getCacheDir(),
                    "morphe_yt_audio_" + System.nanoTime() + ".m4a"
            );

            streamToFile(
                    selection.videoUrl,
                    videoTemp,
                    selection.videoLength,
                    context,
                    notificationId,
                    title,
                    5,
                    55
            );

            streamToFile(
                    selection.audioUrl,
                    audioTemp,
                    selection.audioLength,
                    context,
                    notificationId,
                    title,
                    55,
                    85
            );

            destination = createMediaStoreFile(context, title + ".mp4");
            if (destination == null) {
                throw new IllegalStateException(
                        "Could not create MediaStore destination");
            }

            post(context, notificationId, title, 85, true, "Muxing video and audio");
            muxMp4(context, videoTemp, audioTemp, destination);

            publish(context, destination);
            destination = null;

            post(
                    context,
                    notificationId,
                    title,
                    100,
                    false,
                    "Download complete"
            );
            safeToast("Morphe: YouTube download complete");
        } catch (UnsupportedOperationException ex) {
            Logger.printDebug(() ->
                    "YouTube download left to native path: " + ex.getMessage());
            safeToast("Morphe: stream is not supported by the local downloader");
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "YouTube in-app download failed", ex);
            safeToast("Morphe: YouTube download failed");
        } finally {
            if (destination != null) {
                deleteMediaStore(context, destination);
            }
            deleteQuietly(videoTemp);
            deleteQuietly(audioTemp);
        }
    }

    /**
     * Resolve using Morphe's own stream resolver instead of a second, independent
     * InnerTube implementation. This keeps downloads aligned with the same client
     * selection, PoToken, visitor ID, JavaScript deobfuscation and stream spoofing
     * machinery used by playback.
     */
    private static Selection resolve(String videoId) throws Exception {
        StreamingDataRequest request =
                StreamingDataRequest.getRequestForVideoId(videoId);

        if (request == null) {
            request = StreamingDataRequest.fetchRequestForDownload(
                    videoId,
                    DOWNLOAD_CLIENTS,
                    SpoofVideoStreamsPatch.getPreferredClient()
            );
        }

        StreamingDataRequest.StreamData stream = request.getStream();
        if (stream == null || stream.streamingData() == null) {
            return null;
        }

        PlayerResponse response =
                PlayerResponse.parseFrom(stream.streamingData());

        String title = response.hasVideoDetails()
                && !response.getVideoDetails().getTitle().isBlank()
                ? response.getVideoDetails().getTitle()
                : videoId;

        var streaming = response.getStreamingData();

        Format progressive = bestProgressive(streaming.getFormatsList());
        if (progressive != null && !progressive.getUrl().isBlank()) {
            return Selection.progressive(
                    title,
                    progressive.getUrl(),
                    progressive.getContentLength()
            );
        }

        Format video = bestVideo(streaming.getAdaptiveFormatsList());
        Format audio = bestAudio(streaming.getAdaptiveFormatsList());

        if (video == null || audio == null
                || video.getUrl().isBlank()
                || audio.getUrl().isBlank()) {
            return null;
        }

        return Selection.adaptive(
                title,
                video.getUrl(),
                video.getContentLength(),
                audio.getUrl(),
                audio.getContentLength()
        );
    }

    /*
     * Same download-capable client pool used by current Morphe YouTube.
     * TV_SIMPLY may require JavaScript and a PoToken. Those are resolved by
     * StreamingDataRequest, not by this downloader.
     */
    private static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.TV_SIMPLY,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_CREATOR
    );

    private static Format bestProgressive(List<Format> formats) {
        Format best = null;
        int bestHeight = -1;
        int bestFps = -1;
        long bestBitrate = -1;

        for (Format format : formats) {
            if (!isDirectMp4Video(format)) continue;

            int height = format.getHeight();
            int fps = format.getFps();
            long bitrate = format.getBitrate();

            if (height > bestHeight
                    || (height == bestHeight && fps > bestFps)
                    || (height == bestHeight && fps == bestFps
                    && bitrate > bestBitrate)) {
                best = format;
                bestHeight = height;
                bestFps = fps;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static Format bestVideo(List<Format> formats) {
        Format best = null;
        int bestHeight = -1;
        int bestFps = -1;
        long bestBitrate = -1;

        for (Format format : formats) {
            if (!isDirectMp4Video(format)) continue;

            int height = format.getHeight();
            int fps = format.getFps();
            long bitrate = format.getBitrate();

            if (height > bestHeight
                    || (height == bestHeight && fps > bestFps)
                    || (height == bestHeight && fps == bestFps
                    && bitrate > bestBitrate)) {
                best = format;
                bestHeight = height;
                bestFps = fps;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static Format bestAudio(List<Format> formats) {
        Format best = null;
        long bestBitrate = -1;

        for (Format format : formats) {
            if (!isDirectMp4Audio(format)) continue;

            long bitrate = format.getBitrate();
            if (bitrate > bestBitrate) {
                best = format;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static boolean isDirectMp4Video(Format format) {
        if (format == null
                || format.getUrl().isBlank()
                || !format.getMimeType().startsWith("video/mp4")) {
            return false;
        }

        String mime = format.getMimeType();
        String codecs = mimeCodecs(mime);

        return codecs.isEmpty()
                || codecs.contains("avc1")
                || codecs.contains("av01")
                || codecs.contains("hev1")
                || codecs.contains("hvc1");
    }

    private static boolean isDirectMp4Audio(Format format) {
        if (format == null
                || format.getUrl().isBlank()
                || !format.getMimeType().startsWith("audio/mp4")) {
            return false;
        }

        String codecs = mimeCodecs(format.getMimeType());
        return codecs.isEmpty() || codecs.contains("mp4a");
    }

    private static String mimeCodecs(String mime) {
        int start = mime.indexOf("codecs=\"");
        if (start < 0) return "";
        start += 8;
        int end = mime.indexOf('"', start);
        return end > start ? mime.substring(start, end) : "";
    }

    private static HttpURLConnection openConnection(
            String streamUrl,
            Context context
    ) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(streamUrl).openConnection();

        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
                "User-Agent",
                String.format(
                        Locale.US,
                        USER_AGENT_PREFIX,
                        getYouTubeVersion(context),
                        android.os.Build.VERSION.RELEASE
                )
        );
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");

        return connection;
    }

    private static Uri createMediaStoreFile(
            Context context,
            String name
    ) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES
                            + "/Morphe YouTube"
            );
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        try {
            return context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
            );
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "MediaStore insert failed", ex);
            return null;
        }
    }

    private static void publish(
            Context context,
            Uri destination
    ) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);

        int updated = context.getContentResolver().update(
                destination,
                values,
                null,
                null
        );
        if (updated <= 0) {
            throw new IllegalStateException(
                    "MediaStore publish did not update target row");
        }
    }

    private static void deleteMediaStore(
            Context context,
            Uri destination
    ) {
        try {
            context.getContentResolver().delete(
                    destination,
                    null,
                    null
            );
        } catch (Throwable ignored) {
        }
    }

    private static void post(
            Context context,
            int id,
            String title,
            int progress,
            boolean ongoing,
            String text
    ) {
        if (context == null) return;

        try {
            Utils.runOnMainThread(() -> {
                try {
                    NotificationManager manager =
                            (NotificationManager) context.getSystemService(
                                    Context.NOTIFICATION_SERVICE
                            );
                    if (manager == null) return;

                    manager.createNotificationChannel(
                            new NotificationChannel(
                                    CHANNEL,
                                    "Morphe YouTube downloads",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                    );

                    Notification.Builder builder =
                            new Notification.Builder(context, CHANNEL)
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
                        builder.setProgress(
                                100,
                                Math.max(0, progress),
                                progress < 0
                        );
                    }

                    try {
                        manager.notify(id, builder.build());
                    } catch (SecurityException ex) {
                        Logger.printDebug(() ->
                                "Notification permission unavailable");
                    }
                } catch (Throwable ex) {
                    Logger.printException(
                            () -> "Download notification failure", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Download notification dispatch failure", ex);
        }
    }

    private static void safeToast(String message) {
        try {
            Utils.runOnMainThread(() -> {
                try {
                    Utils.showToastShort(message);
                } catch (Throwable ex) {
                    Logger.printException(
                            () -> "Download toast failure", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Download toast dispatch failure", ex);
        }
    }

    private static String getYouTubeVersion(Context context) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager()
                            .getPackageInfo(
                                    "com.google.android.youtube",
                                    0
                            );
            if (info.versionName != null
                    && !info.versionName.isEmpty()) {
                return info.versionName;
            }
        } catch (Throwable ex) {
            Logger.printDebug(() ->
                    "Could not read YouTube version: " + ex);
        }

        return DEFAULT_CLIENT_VERSION;
    }

    private static boolean isVideoId(String value) {
        return value != null
                && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static String sanitize(String value) {
        String result = value == null
                ? ""
                : value
                .replaceAll("[\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();

        if (result.isEmpty()) {
            result = "YouTube video";
        }

        return result.length() > 120
                ? result.substring(0, 120).trim()
                : result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) {
            return (bytes / 1024) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(
                    Locale.US,
                    "%.1f MB",
                    bytes / (1024d * 1024d)
            );
        }

        return String.format(
                Locale.US,
                "%.2f GB",
                bytes / (1024d * 1024d * 1024d)
        );
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static final class Selection {
        final String title;
        final String progressiveUrl;
        final long progressiveLength;
        final String videoUrl;
        final long videoLength;
        final String audioUrl;
        final long audioLength;

        private Selection(
                String title,
                String progressiveUrl,
                long progressiveLength,
                String videoUrl,
                long videoLength,
                String audioUrl,
                long audioLength
        ) {
            this.title = title;
            this.progressiveUrl = progressiveUrl;
            this.progressiveLength = progressiveLength;
            this.videoUrl = videoUrl;
            this.videoLength = videoLength;
            this.audioUrl = audioUrl;
            this.audioLength = audioLength;
        }

        static Selection progressive(
                String title,
                String url,
                long length
        ) {
            return new Selection(
                    title,
                    url,
                    length,
                    null,
                    -1,
                    null,
                    -1
            );
        }

        static Selection adaptive(
                String title,
                String videoUrl,
                long videoLength,
                String audioUrl,
                long audioLength
        ) {
            return new Selection(
                    title,
                    null,
                    -1,
                    videoUrl,
                    videoLength,
                    audioUrl,
                    audioLength
            );
        }
    }
}package app.morphe.extension.youtube.patches;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Safe local YouTube downloader.
 *
 * Only direct, unprotected MP4 URLs are handled here. Ciphered, protected,
 * entitlement-gated, or DRM streams are not decoded or decrypted. Those remain
 * available through YouTube's native download path.
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
    private static final AtomicInteger NEXT_NOTIFICATION_ID =
            new AtomicInteger(0x4D59);

    private DownloadsPatch() {
    }

    /**
     * Called by the dedicated player action button.
     * This method never performs network or media work on the UI thread.
     */
    public static void onDownloadRequested(Object maybeVideoId) {
        if (!(maybeVideoId instanceof String)) {
            return;
        }

        String videoId = (String) maybeVideoId;
        if (!isVideoId(videoId)) {
            return;
        }

        start(videoId);
    }

    public static boolean onDownloadRequested(String videoId) {
        return start(videoId);
    }

    public static boolean start(String videoId) {
        if (!isVideoId(videoId)) {
            safeToast("Morphe: invalid YouTube video ID");
            return false;
        }

        try {
            Selection selection = resolve(videoId);
            if (selection == null) {
                safeToast("Morphe: stream is not supported by the local downloader");
                return false;
            }

            EXECUTOR.execute(() -> {
                try {
                    download(videoId, selection);
                } catch (Throwable ex) {
                    Logger.printException(
                            () -> "YouTube download worker crashed", ex);
                    safeToast("Morphe: YouTube download failed");
                }
            });
            safeToast("Morphe: YouTube download started");
            return true;
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "YouTube download queue failure", ex);
            safeToast("Morphe: YouTube download failed");
            return false;
        }
    }

    private static void download(String videoId, Selection selection) {
        Context context = Utils.getContext();
        if (context == null) {
            throw new IllegalStateException("Morphe context is unavailable");
        }

        Uri destination = null;
        File videoTemp = null;
        File audioTemp = null;
        String title = videoId;
        int notificationId = NEXT_NOTIFICATION_ID.getAndIncrement();

        try {
            title = sanitize(selection.title);
            post(context, notificationId, title, 0, true, "Preparing");

            if (selection.progressiveUrl != null) {
                destination = createMediaStoreFile(context, title + ".mp4");
                if (destination == null) {
                    throw new IllegalStateException(
                            "Could not create MediaStore destination");
                }

                long bytes = streamToMediaStore(
                        context,
                        destination,
                        selection.progressiveUrl,
                        selection.progressiveLength,
                        notificationId,
                        title
                );

                publish(context, destination);
                destination = null;

                post(
                        context,
                        notificationId,
                        title,
                        100,
                        false,
                        formatSize(bytes) + " • Download complete"
                );
                safeToast("Morphe: YouTube download complete");
                return;
            }

            if (selection.videoUrl == null || selection.audioUrl == null) {
                throw new UnsupportedOperationException(
                        "Adaptive MP4 video/audio pair unavailable");
            }

            videoTemp = new File(
                    context.getCacheDir(),
                    "morphe_yt_video_" + System.nanoTime() + ".mp4"
            );
            audioTemp = new File(
                    context.getCacheDir(),
                    "morphe_yt_audio_" + System.nanoTime() + ".m4a"
            );

            streamToFile(
                    selection.videoUrl,
                    videoTemp,
                    selection.videoLength,
                    context,
                    notificationId,
                    title,
                    5,
                    55
            );

            streamToFile(
                    selection.audioUrl,
                    audioTemp,
                    selection.audioLength,
                    context,
                    notificationId,
                    title,
                    55,
                    85
            );

            destination = createMediaStoreFile(context, title + ".mp4");
            if (destination == null) {
                throw new IllegalStateException(
                        "Could not create MediaStore destination");
            }

            post(context, notificationId, title, 85, true, "Muxing video and audio");
            muxMp4(context, videoTemp, audioTemp, destination);

            publish(context, destination);
            destination = null;

            post(
                    context,
                    notificationId,
                    title,
                    100,
                    false,
                    "Download complete"
            );
            safeToast("Morphe: YouTube download complete");
        } catch (UnsupportedOperationException ex) {
            Logger.printDebug(() ->
                    "YouTube download left to native path: " + ex.getMessage());
            safeToast("Morphe: stream is not supported by the local downloader");
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "YouTube in-app download failed", ex);
            safeToast("Morphe: YouTube download failed");
        } finally {
            if (destination != null) {
                deleteMediaStore(context, destination);
            }
            deleteQuietly(videoTemp);
            deleteQuietly(audioTemp);
        }
    }

    /**
     * Resolve using Morphe's own stream resolver instead of a second, independent
     * InnerTube implementation. This keeps downloads aligned with the same client
     * selection, PoToken, visitor ID, JavaScript deobfuscation and stream spoofing
     * machinery used by playback.
     */
    private static Selection resolve(String videoId) throws Exception {
        StreamingDataRequest request =
                StreamingDataRequest.getRequestForVideoId(videoId);

        if (request == null) {
            request = StreamingDataRequest.fetchRequestForDownload(
                    videoId,
                    DOWNLOAD_CLIENTS,
                    SpoofVideoStreamsPatch.getPreferredClient()
            );
        }

        StreamingDataRequest.StreamData stream = request.getStream();
        if (stream == null || stream.streamingData() == null) {
            return null;
        }

        PlayerResponse response =
                PlayerResponse.parseFrom(stream.streamingData());

        String title = response.hasVideoDetails()
                && !response.getVideoDetails().getTitle().isBlank()
                ? response.getVideoDetails().getTitle()
                : videoId;

        var streaming = response.getStreamingData();

        Format progressive = bestProgressive(streaming.getFormatsList());
        if (progressive != null && !progressive.getUrl().isBlank()) {
            return Selection.progressive(
                    title,
                    progressive.getUrl(),
                    progressive.getContentLength()
            );
        }

        Format video = bestVideo(streaming.getAdaptiveFormatsList());
        Format audio = bestAudio(streaming.getAdaptiveFormatsList());

        if (video == null || audio == null
                || video.getUrl().isBlank()
                || audio.getUrl().isBlank()) {
            return null;
        }

        return Selection.adaptive(
                title,
                video.getUrl(),
                video.getContentLength(),
                audio.getUrl(),
                audio.getContentLength()
        );
    }

    /*
     * Same download-capable client pool used by current Morphe YouTube.
     * TV_SIMPLY may require JavaScript and a PoToken. Those are resolved by
     * StreamingDataRequest, not by this downloader.
     */
    private static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.TV_SIMPLY,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_CREATOR
    );

    private static Format bestProgressive(List<Format> formats) {
        Format best = null;
        int bestHeight = -1;
        int bestFps = -1;
        long bestBitrate = -1;

        for (Format format : formats) {
            if (!isDirectMp4Video(format)) continue;

            int height = format.getHeight();
            int fps = format.getFps();
            long bitrate = format.getBitrate();

            if (height > bestHeight
                    || (height == bestHeight && fps > bestFps)
                    || (height == bestHeight && fps == bestFps
                    && bitrate > bestBitrate)) {
                best = format;
                bestHeight = height;
                bestFps = fps;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static Format bestVideo(List<Format> formats) {
        Format best = null;
        int bestHeight = -1;
        int bestFps = -1;
        long bestBitrate = -1;

        for (Format format : formats) {
            if (!isDirectMp4Video(format)) continue;

            int height = format.getHeight();
            int fps = format.getFps();
            long bitrate = format.getBitrate();

            if (height > bestHeight
                    || (height == bestHeight && fps > bestFps)
                    || (height == bestHeight && fps == bestFps
                    && bitrate > bestBitrate)) {
                best = format;
                bestHeight = height;
                bestFps = fps;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static Format bestAudio(List<Format> formats) {
        Format best = null;
        long bestBitrate = -1;

        for (Format format : formats) {
            if (!isDirectMp4Audio(format)) continue;

            long bitrate = format.getBitrate();
            if (bitrate > bestBitrate) {
                best = format;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static boolean isDirectMp4Video(Format format) {
        if (format == null
                || format.getUrl().isBlank()
                || !format.getMimeType().startsWith("video/mp4")) {
            return false;
        }

        String mime = format.getMimeType();
        String codecs = mimeCodecs(mime);

        return codecs.isEmpty()
                || codecs.contains("avc1")
                || codecs.contains("av01")
                || codecs.contains("hev1")
                || codecs.contains("hvc1");
    }

    private static boolean isDirectMp4Audio(Format format) {
        if (format == null
                || format.getUrl().isBlank()
                || !format.getMimeType().startsWith("audio/mp4")) {
            return false;
        }

        String codecs = mimeCodecs(format.getMimeType());
        return codecs.isEmpty() || codecs.contains("mp4a");
    }

    private static String mimeCodecs(String mime) {
        int start = mime.indexOf("codecs=\\\"");
        if (start < 0) return "";
        start += 8;
        int end = mime.indexOf('\\\"', start);
        return end > start ? mime.substring(start, end) : "";
    }

    private static HttpURLConnection openConnection(
            String streamUrl,
            Context context
    ) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(streamUrl).openConnection();

        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
                "User-Agent",
                String.format(
                        Locale.US,
                        USER_AGENT_PREFIX,
                        getYouTubeVersion(context),
                        android.os.Build.VERSION.RELEASE
                )
        );
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");

        return connection;
    }

    private static Uri createMediaStoreFile(
            Context context,
            String name
    ) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES
                            + "/Morphe YouTube"
            );
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        try {
            return context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
            );
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "MediaStore insert failed", ex);
            return null;
        }
    }

    private static void publish(
            Context context,
            Uri destination
    ) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);

        int updated = context.getContentResolver().update(
                destination,
                values,
                null,
                null
        );
        if (updated <= 0) {
            throw new IllegalStateException(
                    "MediaStore publish did not update target row");
        }
    }

    private static void deleteMediaStore(
            Context context,
            Uri destination
    ) {
        try {
            context.getContentResolver().delete(
                    destination,
                    null,
                    null
            );
        } catch (Throwable ignored) {
        }
    }

    private static void post(
            Context context,
            int id,
            String title,
            int progress,
            boolean ongoing,
            String text
    ) {
        if (context == null) return;

        try {
            Utils.runOnMainThread(() -> {
                try {
                    NotificationManager manager =
                            (NotificationManager) context.getSystemService(
                                    Context.NOTIFICATION_SERVICE
                            );
                    if (manager == null) return;

                    manager.createNotificationChannel(
                            new NotificationChannel(
                                    CHANNEL,
                                    "Morphe YouTube downloads",
                                    NotificationManager.IMPORTANCE_LOW
                            )
                    );

                    Notification.Builder builder =
                            new Notification.Builder(context, CHANNEL)
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
                        builder.setProgress(
                                100,
                                Math.max(0, progress),
                                progress < 0
                        );
                    }

                    try {
                        manager.notify(id, builder.build());
                    } catch (SecurityException ex) {
                        Logger.printDebug(() ->
                                "Notification permission unavailable");
                    }
                } catch (Throwable ex) {
                    Logger.printException(
                            () -> "Download notification failure", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Download notification dispatch failure", ex);
        }
    }

    private static void safeToast(String message) {
        try {
            Utils.runOnMainThread(() -> {
                try {
                    Utils.showToastShort(message);
                } catch (Throwable ex) {
                    Logger.printException(
                            () -> "Download toast failure", ex);
                }
            });
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Download toast dispatch failure", ex);
        }
    }

    private static String getYouTubeVersion(Context context) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager()
                            .getPackageInfo(
                                    "com.google.android.youtube",
                                    0
                            );
            if (info.versionName != null
                    && !info.versionName.isEmpty()) {
                return info.versionName;
            }
        } catch (Throwable ex) {
            Logger.printDebug(() ->
                    "Could not read YouTube version: " + ex);
        }

        return DEFAULT_CLIENT_VERSION;
    }

    private static boolean isVideoId(String value) {
        return value != null
                && value.matches("[A-Za-z0-9_-]{11}");
    }

    private static String sanitize(String value) {
        String result = value == null
                ? ""
                : value
                .replaceAll("[\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();

        if (result.isEmpty()) {
            result = "YouTube video";
        }

        return result.length() > 120
                ? result.substring(0, 120).trim()
                : result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) {
            return (bytes / 1024) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(
                    Locale.US,
                    "%.1f MB",
                    bytes / (1024d * 1024d)
            );
        }

        return String.format(
                Locale.US,
                "%.2f GB",
                bytes / (1024d * 1024d * 1024d)
        );
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static final class Selection {
        final String title;
        final String progressiveUrl;
        final long progressiveLength;
        final String videoUrl;
        final long videoLength;
        final String audioUrl;
        final long audioLength;

        private Selection(
                String title,
                String progressiveUrl,
                long progressiveLength,
                String videoUrl,
                long videoLength,
                String audioUrl,
                long audioLength
        ) {
            this.title = title;
            this.progressiveUrl = progressiveUrl;
            this.progressiveLength = progressiveLength;
            this.videoUrl = videoUrl;
            this.videoLength = videoLength;
            this.audioUrl = audioUrl;
            this.audioLength = audioLength;
        }

        static Selection progressive(
                String title,
                String url,
                long length
        ) {
            return new Selection(
                    title,
                    url,
                    length,
                    null,
                    -1,
                    null,
                    -1
            );
        }

        static Selection adaptive(
                String title,
                String videoUrl,
                long videoLength,
                String audioUrl,
                long audioLength
        ) {
            return new Selection(
                    title,
                    null,
                    -1,
                    videoUrl,
                    videoLength,
                    audioUrl,
                    audioLength
            );
        }
    }
}
