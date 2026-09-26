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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

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
    public static boolean start(String videoId) {
        if (!isVideoId(videoId)) {
            safeToast("Morphe: invalid YouTube video ID");
            return false;
        }

        try {
            EXECUTOR.execute(() -> {
                try {
                    download(videoId);
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

    private static void download(String videoId) {
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
            Selection selection = resolve(videoId);
            if (selection == null) {
                throw new UnsupportedOperationException(
                        "No supported direct unprotected MP4 stream");
            }

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

    private static Selection resolve(String videoId) throws Exception {
        JSONObject root = new JSONObject(requestPlayer(videoId));
        JSONObject details = root.optJSONObject("videoDetails");
        JSONObject streaming = root.optJSONObject("streamingData");

        if (streaming == null) {
            return null;
        }

        String title = details == null
                ? videoId
                : details.optString("title", videoId);

        JSONObject progressive = bestProgressive(
                streaming.optJSONArray("formats")
        );
        if (progressive != null) {
            return Selection.progressive(
                    title,
                    progressive.optString("url", ""),
                    progressive.optLong("contentLength", -1)
            );
        }

        JSONObject video = bestVideo(
                streaming.optJSONArray("adaptiveFormats")
        );
        JSONObject audio = bestAudio(
                streaming.optJSONArray("adaptiveFormats")
        );

        if (video == null || audio == null) {
            return null;
        }

        return Selection.adaptive(
                title,
                video.optString("url", ""),
                video.optLong("contentLength", -1),
                audio.optString("url", ""),
                audio.optLong("contentLength", -1)
        );
    }

    private static JSONObject bestProgressive(JSONArray formats) {
        if (formats == null) return null;

        JSONObject best = null;
        int bestHeight = -1;
        int bestFps = -1;
        long bestBitrate = -1;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (!isDirectMp4Video(format)) continue;

            int height = format.optInt("height", 0);
            int fps = format.optInt("fps", 0);
            long bitrate = format.optLong("bitrate", 0);

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

    private static JSONObject bestVideo(JSONArray formats) {
        if (formats == null) return null;

        JSONObject best = null;
        int bestHeight = -1;
        int bestFps = -1;
        long bestBitrate = -1;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (!isDirectMp4Video(format)) continue;

            int height = format.optInt("height", 0);
            int fps = format.optInt("fps", 0);
            long bitrate = format.optLong("bitrate", 0);

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

    private static JSONObject bestAudio(JSONArray formats) {
        if (formats == null) return null;

        JSONObject best = null;
        long bestBitrate = -1;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (!isDirectMp4Audio(format)) continue;

            long bitrate = format.optLong("bitrate", 0);
            if (bitrate > bestBitrate) {
                best = format;
                bestBitrate = bitrate;
            }
        }

        return best;
    }

    private static boolean isDirectMp4Video(JSONObject format) {
        if (format == null) return false;

        String url = format.optString("url", "");
        String mime = format.optString("mimeType", "");

        if (url.isEmpty() || !mime.startsWith("video/mp4")) return false;
        if (format.has("signatureCipher") || format.has("cipher")) return false;
        if (format.has("drmFamilies")) return false;

        String codecs = mimeCodecs(mime);
        return codecs.isEmpty()
                || codecs.contains("avc1")
                || codecs.contains("av01")
                || codecs.contains("hev1")
                || codecs.contains("hvc1");
    }

    private static boolean isDirectMp4Audio(JSONObject format) {
        if (format == null) return false;

        String url = format.optString("url", "");
        String mime = format.optString("mimeType", "");

        if (url.isEmpty() || !mime.startsWith("audio/mp4")) return false;
        if (format.has("signatureCipher") || format.has("cipher")) return false;
        if (format.has("drmFamilies")) return false;

        String codecs = mimeCodecs(mime);
        return codecs.isEmpty() || codecs.contains("mp4a");
    }

    private static String mimeCodecs(String mime) {
        int start = mime.indexOf("codecs="");
        if (start < 0) return "";
        start += 8;
        int end = mime.indexOf('"', start);
        return end > start ? mime.substring(start, end) : "";
    }

    private static String requestPlayer(String videoId) throws Exception {
        Context context = Utils.getContext();
        if (context == null) {
            throw new IllegalStateException("Morphe context is unavailable");
        }

        URL url = new URL(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        );

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty(
                "User-Agent",
                String.format(
                        Locale.US,
                        USER_AGENT_PREFIX,
                        getYouTubeVersion(context),
                        android.os.Build.VERSION.RELEASE
                )
        );

        JSONObject client = new JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", getYouTubeVersion(context))
                .put("androidSdkVersion", android.os.Build.VERSION.SDK_INT)
                .put("hl", Locale.getDefault().toLanguageTag());

        JSONObject body = new JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put("context", new JSONObject().put("client", client));

        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);

        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
                output.flush();
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Player request HTTP " + code
                );
            }

            try (InputStream input = connection.getInputStream();
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

    private static long streamToMediaStore(
            Context context,
            Uri destination,
            String url,
            long expectedLength,
            int notificationId,
            String title
    ) throws Exception {
        HttpURLConnection connection = openConnection(url, context);

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Media request HTTP " + code
                );
            }

            long length = connection.getContentLengthLong();
            if (length <= 0) length = expectedLength;

            try (BufferedInputStream input =
                         new BufferedInputStream(
                                 connection.getInputStream(), BUFFER_SIZE);
                 OutputStream raw =
                         context.getContentResolver()
                                 .openOutputStream(destination, "w")) {

                if (raw == null) {
                    throw new IllegalStateException(
                            "Output stream unavailable");
                }

                return copy(
                        input,
                        new BufferedOutputStream(raw, BUFFER_SIZE),
                        length,
                        notificationId,
                        title,
                        0,
                        100
                );
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void streamToFile(
            String url,
            File destination,
            long expectedLength,
            Context context,
            int notificationId,
            String title,
            int progressStart,
            int progressEnd
    ) throws Exception {
        HttpURLConnection connection = openConnection(url, context);

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Media request HTTP " + code
                );
            }

            long length = connection.getContentLengthLong();
            if (length <= 0) length = expectedLength;

            try (BufferedInputStream input =
                         new BufferedInputStream(
                                 connection.getInputStream(), BUFFER_SIZE);
                 OutputStream output =
                         new BufferedOutputStream(
                                 new FileOutputStream(destination),
                                 BUFFER_SIZE)) {

                copy(
                        input,
                        output,
                        length,
                        notificationId,
                        title,
                        progressStart,
                        progressEnd
                );
            }
        } finally {
            connection.disconnect();
        }
    }

    private static long copy(
            InputStream input,
            OutputStream output,
            long length,
            int notificationId,
            String title,
            int progressStart,
            int progressEnd
    ) throws Exception {
        try (OutputStream out = output) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long copied = 0;
            long lastNotification = 0;
            int read;

            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;

                long now = System.currentTimeMillis();
                if (now - lastNotification >= NOTIFICATION_INTERVAL_MS) {
                    int progress = length > 0
                            ? progressStart + (int) Math.min(
                                    progressEnd - progressStart - 1,
                                    copied * (progressEnd - progressStart)
                                            / length
                            )
                            : -1;

                    post(
                            Utils.getContext(),
                            notificationId,
                            title,
                            progress,
                            true,
                            progress >= 0
                                    ? "Downloading " + progress + "%"
                                    : "Downloading"
                    );
                    lastNotification = now;
                }
            }

            return copied;
        }
    }

    private static void muxMp4(
            Context context,
            File videoFile,
            File audioFile,
            Uri destination
    ) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        android.os.ParcelFileDescriptor pfd = null;
        MediaMuxer muxer = null;
        boolean started = false;

        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            int videoTrack = findTrack(videoExtractor, "video/");
            int audioTrack = findTrack(audioExtractor, "audio/");

            if (videoTrack < 0 || audioTrack < 0) {
                throw new IllegalStateException(
                        "Downloaded streams do not contain expected tracks");
            }

            MediaFormat videoFormat =
                    videoExtractor.getTrackFormat(videoTrack);
            MediaFormat audioFormat =
                    audioExtractor.getTrackFormat(audioTrack);

            String videoMime = videoFormat.getString(MediaFormat.KEY_MIME);
            String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);

            if (videoMime == null || !videoMime.startsWith("video/")) {
                throw new IllegalStateException("Invalid video track");
            }
            if (audioMime == null || !audioMime.startsWith("audio/")) {
                throw new IllegalStateException("Invalid audio track");
            }

            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);

            pfd = context.getContentResolver()
                    .openFileDescriptor(destination, "w");
            if (pfd == null) {
                throw new IllegalStateException(
                        "Could not open MediaStore output");
            }

            muxer = new MediaMuxer(
                    pfd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );

            int outVideo = muxer.addTrack(videoFormat);
            int outAudio = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            copyTrack(videoExtractor, muxer, outVideo);
            copyTrack(audioExtractor, muxer, outAudio);

            muxer.stop();
            started = false;
        } finally {
            if (started && muxer != null) {
                try {
                    muxer.stop();
                } catch (Throwable ignored) {
                }
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                }
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Throwable ignored) {
                }
            }
            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private static int findTrack(
            MediaExtractor extractor,
            String prefix
    ) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void copyTrack(
            MediaExtractor extractor,
            MediaMuxer muxer,
            int muxerTrack
    ) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0, extractor.getSampleTime());
            info.flags = extractor.getSampleFlags();

            muxer.writeSampleData(muxerTrack, buffer, info);
            extractor.advance();
            buffer.clear();
        }
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

        context.getContentResolver().update(
                destination,
                values,
                null,
                null
        );
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
