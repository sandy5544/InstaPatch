package app.morphe.extension.youtube.patches;

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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * One native in-app download job.
 *
 * Stream resolution deliberately goes through Morphe's shared resolver so
 * configured spoof clients, PoToken handling and JavaScript deobfuscation are
 * reused instead of maintaining a second InnerTube implementation.
 *
 * Only direct, unprotected MP4 video/audio is written. Ciphered or DRM media
 * is not decrypted or bypassed.
 */
final class YouTubeDownloadJob {
    interface Progress {
        void update(int percent, String text);
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.TV_SIMPLY,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_CREATOR
    );

    private YouTubeDownloadJob() {
    }

    static void run(
            Context context,
            String videoId,
            Progress progress
    ) throws Exception {
        Selection selection = resolve(videoId);
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
                        context,
                        selection.progressiveUrl,
                        selection.progressiveLength,
                        destination,
                        progress,
                        0,
                        95
                );

                publish(context, destination);
                destination = null;
                progress.update(100, formatSize(bytes) + " • Download complete");
                UtilsToast.success();
                return;
            }

            videoFile = new File(
                    context.getCacheDir(),
                    "morphe_yt_video_" + System.nanoTime() + ".mp4"
            );
            audioFile = new File(
                    context.getCacheDir(),
                    "morphe_yt_audio_" + System.nanoTime() + ".m4a"
            );

            downloadToFile(
                    context,
                    selection.videoUrl,
                    selection.videoLength,
                    videoFile,
                    progress,
                    5,
                    55
            );
            downloadToFile(
                    context,
                    selection.audioUrl,
                    selection.audioLength,
                    audioFile,
                    progress,
                    55,
                    85
            );

            destination = createDestination(context, title + ".mp4");
            if (destination == null) {
                throw new IllegalStateException("Could not create MediaStore file");
            }

            progress.update(86, "Muxing video and audio");
            muxMp4(context, videoFile, audioFile, destination);

            publish(context, destination);
            destination = null;
            progress.update(100, "Download complete");
            UtilsToast.success();
        } finally {
            if (destination != null) {
                deleteDestination(context, destination);
            }
            deleteQuietly(videoFile);
            deleteQuietly(audioFile);
        }
    }

    private static Selection resolve(String videoId) throws Exception {
        StreamingDataRequest request =
                StreamingDataRequest.fetchRequestForDownload(
                        videoId,
                        DOWNLOAD_CLIENTS,
                        SpoofVideoStreamsPatch.getPreferredClient()
                );

        if (request == null) return null;

        StreamingDataRequest.StreamData stream = request.getStream();
        if (stream == null || stream.streamingData() == null) return null;

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
        return bestProgressive(formats);
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

        String codecs = mimeCodecs(format.getMimeType());
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

    private static long downloadToUri(
            Context context,
            String url,
            long expectedLength,
            Uri destination,
            Progress progress,
            int start,
            int end
    ) throws Exception {
        HttpURLConnection connection = openConnection(url);
        try {
            checkResponse(connection);
            long length = connection.getContentLengthLong();
            if (length <= 0) length = expectedLength;

            try (InputStream input = new BufferedInputStream(
                         connection.getInputStream(), BUFFER_SIZE);
                 OutputStream raw = context.getContentResolver()
                         .openOutputStream(destination, "w")) {
                if (raw == null) {
                    throw new IllegalStateException("Output stream unavailable");
                }
                return copy(input, new BufferedOutputStream(raw, BUFFER_SIZE),
                        length, progress, start, end);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadToFile(
            Context context,
            String url,
            long expectedLength,
            File destination,
            Progress progress,
            int start,
            int end
    ) throws Exception {
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

    private static void checkResponse(HttpURLConnection connection)
            throws Exception {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Media request HTTP " + code);
        }
    }

    private static long copy(
            InputStream input,
            OutputStream output,
            long length,
            Progress progress,
            int start,
            int end
    ) throws Exception {
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
                                    copied * (end - start) / length
                            )
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

        if (BuildCompat.isAtLeastQ()) {
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Morphe YouTube"
            );
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        try {
            return context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
            );
        } catch (Throwable ex) {
            Logger.printException(() -> "MediaStore insert failed", ex);
            return null;
        }
    }

    private static void publish(Context context, Uri destination) {
        if (!BuildCompat.isAtLeastQ()) return;

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

            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);

            pfd = context.getContentResolver()
                    .openFileDescriptor(destination, "w");
            if (pfd == null) {
                throw new IllegalStateException("Could not open output");
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
            MediaExtractor extractor,
            MediaMuxer muxer,
            int outputTrack
    ) {
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
                String title, String url, long length) {
            return new Selection(
                    title, url, length, null, -1, null, -1);
        }

        static Selection adaptive(
                String title,
                String videoUrl,
                long videoLength,
                String audioUrl,
                long audioLength
        ) {
            return new Selection(
                    title, null, -1, videoUrl, videoLength,
                    audioUrl, audioLength);
        }
    }

    private static final class BuildCompat {
        static boolean isAtLeastQ() {
            return android.os.Build.VERSION.SDK_INT >= 29;
        }
    }

    private static final class UtilsToast {
        static void success() {
            try {
                app.morphe.extension.shared.Utils.runOnMainThread(
                        () -> app.morphe.extension.shared.Utils.showToastShort(
                                "Morphe: YouTube download complete"));
            } catch (Throwable ex) {
                Logger.printException(() -> "Download toast failed", ex);
            }
        }
    }
}
