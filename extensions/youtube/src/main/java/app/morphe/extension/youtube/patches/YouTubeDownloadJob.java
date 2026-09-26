package app.morphe.extension.youtube.patches;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

final class YouTubeDownloadJob {
    interface Progress {
        void update(int percent, String text);
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    private YouTubeDownloadJob() {
    }

    static void run(Context context, String videoId, Progress progress) throws Exception {
        Selection selection = resolve(context, videoId);
        if (selection == null) {
            throw new UnsupportedOperationException(
                    "No direct unprotected MP4 stream is available");
        }

        String title = sanitize(selection.title);
        progress.update(0, "Preparing " + title);

        Uri destination = null;
        File videoFile = null;
        File audioFile = null;

        try {
            if (selection.progressiveUrl != null) {
                destination = createDestination(context, title + ".mp4");
                if (destination == null) {
                    throw new IllegalStateException("Could not create MediaStore file");
                }

                long bytes = downloadToUri(
                        context, selection.progressiveUrl,
                        selection.progressiveLength, destination, progress, 0, 95);

                publish(context, destination);
                destination = null;
                progress.update(100, formatSize(bytes) + " • Download complete");
                successToast();
                return;
            }

            videoFile = new File(
                    context.getCacheDir(),
                    "morphe_yt_video_" + System.nanoTime() + ".mp4");
            audioFile = new File(
                    context.getCacheDir(),
                    "morphe_yt_audio_" + System.nanoTime() + ".m4a");

            downloadToFile(context, selection.videoUrl, selection.videoLength,
                    videoFile, progress, 5, 55);
            downloadToFile(context, selection.audioUrl, selection.audioLength,
                    audioFile, progress, 55, 85);

            destination = createDestination(context, title + ".mp4");
            if (destination == null) {
                throw new IllegalStateException("Could not create MediaStore file");
            }

            progress.update(86, "Muxing video and audio");
            muxMp4(context, videoFile, audioFile, destination);

            publish(context, destination);
            destination = null;
            progress.update(100, "Download complete");
            successToast();
        } finally {
            if (destination != null) deleteDestination(context, destination);
            deleteQuietly(videoFile);
            deleteQuietly(audioFile);
        }
    }

    private static Selection resolve(Context context, String videoId) throws Exception {
        JSONObject root = new JSONObject(requestPlayer(context, videoId));
        JSONObject details = root.optJSONObject("videoDetails");
        JSONObject streaming = root.optJSONObject("streamingData");
        if (streaming == null) return null;

        String title = details == null
                ? videoId
                : details.optString("title", videoId);

        JSONObject progressive = bestProgressive(
                streaming.optJSONArray("formats"));
        if (progressive != null) {
            return Selection.progressive(
                    title,
                    progressive.optString("url", ""),
                    progressive.optLong("contentLength", -1));
        }

        JSONObject video = bestVideo(
                streaming.optJSONArray("adaptiveFormats"));
        JSONObject audio = bestAudio(
                streaming.optJSONArray("adaptiveFormats"));

        if (video == null || audio == null) return null;

        return Selection.adaptive(
                title,
                video.optString("url", ""),
                video.optLong("contentLength", -1),
                audio.optString("url", ""),
                audio.optLong("contentLength", -1));
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
        return bestProgressive(formats);
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
        if (format.has("signatureCipher") || format.has("cipher")
                || format.has("drmFamilies")) return false;

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
        if (format.has("signatureCipher") || format.has("cipher")
                || format.has("drmFamilies")) return false;

        String codecs = mimeCodecs(mime);
        return codecs.isEmpty() || codecs.contains("mp4a");
    }

    private static String mimeCodecs(String mime) {
        int start = mime.indexOf("codecs=\"");
        if (start < 0) return "";
        start += 8;
        int end = mime.indexOf('"', start);
        return end > start ? mime.substring(start, end) : "";
    }

    private static String requestPlayer(Context context, String videoId) throws Exception {
        URL url = new URL(
                "https://youtubei.googleapis.com/youtubei/v1/player"
                        + "?alt=json&prettyPrint=false");

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-YouTube-Client-Name", "3");
        connection.setRequestProperty(
                "X-YouTube-Client-Version", getYouTubeVersion(context));

        JSONObject client = new JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", getYouTubeVersion(context))
                .put("androidSdkVersion", Build.VERSION.SDK_INT)
                .put("hl", Locale.getDefault().toLanguageTag());

        JSONObject body = new JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put("context", new JSONObject().put("client", client));

        try {
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Player request HTTP " + code);
            }

            try (InputStream input = connection.getInputStream();
                 java.io.ByteArrayOutputStream output =
                         new java.io.ByteArrayOutputStream()) {
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

    private static long downloadToUri(
            Context context, String url, long expectedLength, Uri destination,
            Progress progress, int start, int end) throws Exception {
        HttpURLConnection connection = openConnection(url);
        try {
            checkResponse(connection);
            long length = connection.getContentLengthLong();
            if (length <= 0) length = expectedLength;

            try (InputStream input = new BufferedInputStream(
                         connection.getInputStream(), BUFFER_SIZE);
                 OutputStream raw = context.getContentResolver()
                         .openOutputStream(destination, "w")) {
                if (raw == null) throw new IllegalStateException(
                        "Output stream unavailable");
                return copy(input,
                        new BufferedOutputStream(raw, BUFFER_SIZE),
                        length, progress, start, end);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadToFile(
            Context context, String url, long expectedLength, File destination,
            Progress progress, int start, int end) throws Exception {
        HttpURLConnection connection = openConnection(url);
        try {
            checkResponse(connection);
            long length = connection.getContentLengthLong();
            if (length <= 0) length = expectedLength;

            try (InputStream input = new BufferedInputStream(
                         connection.getInputStream(), BUFFER_SIZE);
                 OutputStream output = new BufferedOutputStream(
                         new FileOutputStream(destination), BUFFER_SIZE)) {
                copy(input, output, length, progress, start, end);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String streamUrl)
            throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(streamUrl).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static void checkResponse(HttpURLConnection connection)
            throws Exception {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Media request HTTP " + code);
        }
    }

    private static long copy(
            InputStream input, OutputStream output, long length,
            Progress progress, int start, int end) throws Exception {
        try (OutputStream out = output) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long copied = 0;
            long lastUpdate = 0;
            int read;

            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdate >= 750) {
                    int percent = length > 0
                            ? start + (int) Math.min(
                                    end - start - 1,
                                    copied * (end - start) / length)
                            : -1;
                    progress.update(percent, "Downloading");
                    lastUpdate = now;
                }
            }
            return copied;
        }
    }

    private static Uri createDestination(Context context, String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Morphe YouTube");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        try {
            return context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Throwable ex) {
            Logger.printException(() -> "MediaStore insert failed", ex);
            return null;
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
            context.getContentResolver().delete(destination, null, null);
        } catch (Throwable ignored) {
        }
    }

    private static void muxMp4(
            Context context, File videoFile, File audioFile, Uri destination)
            throws Exception {
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

            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);

            pfd = context.getContentResolver()
                    .openFileDescriptor(destination, "w");
            if (pfd == null) throw new IllegalStateException(
                    "Could not open MediaStore output");

            muxer = new MediaMuxer(
                    pfd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

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
                try { muxer.stop(); } catch (Throwable ignored) { }
            }
            if (muxer != null) {
                try { muxer.release(); } catch (Throwable ignored) { }
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Throwable ignored) { }
            }
            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copyTrack(
            MediaExtractor extractor, MediaMuxer muxer, int outputTrack) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs =
                    Math.max(0, extractor.getSampleTime());
            info.flags = extractor.getSampleFlags();

            muxer.writeSampleData(outputTrack, buffer, info);
            extractor.advance();
            buffer.clear();
        }
    }

    private static String getYouTubeVersion(Context context) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(
                            "com.google.android.youtube", 0);
            if (info.versionName != null && !info.versionName.isEmpty()) {
                return info.versionName;
            }
        } catch (Throwable ex) {
            Logger.printDebug(() ->
                    "Could not read YouTube version: " + ex);
        }
        return "21.38.123";
    }

    private static String sanitize(String value) {
        String result = value == null ? "" : value
                .replaceAll("[\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
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

    private static void successToast() {
        try {
            Utils.runOnMainThread(() ->
                    Utils.showToastShort("Morphe: YouTube download complete"));
        } catch (Throwable ex) {
            Logger.printException(() -> "Download toast failed", ex);
        }
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

        private Selection(String title, String progressiveUrl, long progressiveLength,
                          String videoUrl, long videoLength,
                          String audioUrl, long audioLength) {
            this.title = title;
            this.progressiveUrl = progressiveUrl;
            this.progressiveLength = progressiveLength;
            this.videoUrl = videoUrl;
            this.videoLength = videoLength;
            this.audioUrl = audioUrl;
            this.audioLength = audioLength;
        }

        static Selection progressive(String title, String url, long length) {
            return new Selection(title, url, length, null, -1, null, -1);
        }

        static Selection adaptive(String title, String videoUrl, long videoLength,
                                  String audioUrl, long audioLength) {
            return new Selection(title, null, -1, videoUrl, videoLength,
                    audioUrl, audioLength);
        }
    }
}
