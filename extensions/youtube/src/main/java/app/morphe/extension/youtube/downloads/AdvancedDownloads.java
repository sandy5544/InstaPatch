package app.morphe.extension.youtube.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Adaptive YouTube downloader.
 *
 * Supports direct progressive MP4 and adaptive MP4 video + M4A audio,
 * selecting the highest directly exposed resolution and remuxing without
 * re-encoding. Ciphered and DRM-protected streams are intentionally excluded.
 */
final class AdvancedDownloads {
    private static final int TIMEOUT = 30_000;
    private static final int BUFFER = 64 * 1024;

    private AdvancedDownloads() {}

    static void download(String videoId) {
        Context context = Utils.getContext();
        File video = null;
        File audio = null;
        File output = null;
        Uri destination = null;

        try {
            Result r = resolve(videoId, context);
            if (r == null) {
                throw new IllegalStateException("No direct unprotected MP4 stream");
            }

            File dir = new File(context.getCacheDir(), "morphe-youtube");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Unable to create cache directory");
            }

            String title = sanitize(r.title);

            if (r.progressiveUrl != null) {
                output = new File(dir, unique(videoId) + ".mp4");
                download(r.progressiveUrl, output, context);
            } else {
                video = new File(dir, unique(videoId) + "-video.mp4");
                audio = new File(dir, unique(videoId) + "-audio.m4a");
                output = new File(dir, unique(videoId) + "-muxed.mp4");

                download(r.videoUrl, video, context);
                download(r.audioUrl, audio, context);
                mux(video, audio, output);
            }

            destination = createDestination(context, title + ".mp4");
            if (destination == null) {
                throw new IllegalStateException("MediaStore insert failed");
            }

            copy(context, output, destination);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(destination, values, null, null);

            Utils.showToastShort("Morphe: YouTube download complete");
        } catch (Exception e) {
            if (destination != null) {
                try {
                    context.getContentResolver().delete(destination, null, null);
                } catch (Exception ignored) {}
            }
            Logger.printException(() -> "YouTube adaptive download failed", e);
            Utils.showToastShort(
                "Morphe: direct download unavailable; YouTube protected/offline download remains native"
            );
        } finally {
            delete(video);
            delete(audio);
            delete(output);
        }
    }

    private static Result resolve(String id, Context context) throws Exception {
        JSONObject root = new JSONObject(player(id, context));
        JSONObject details = root.optJSONObject("videoDetails");
        JSONObject data = root.optJSONObject("streamingData");
        if (data == null) return null;

        String title = details == null ? id : details.optString("title", id);

        JSONObject progressive = bestProgressive(data.optJSONArray("formats"));
        JSONObject video = bestVideo(data.optJSONArray("adaptiveFormats"));
        JSONObject audio = bestAudio(data.optJSONArray("adaptiveFormats"));

        if (video != null && audio != null) {
            int progressiveHeight = progressive == null
                ? 0 : progressive.optInt("height", 0);
            if (video.optInt("height", 0) >= progressiveHeight) {
                return new Result(
                    title, null,
                    video.optString("url", ""),
                    audio.optString("url", "")
                );
            }
        }

        if (progressive != null) {
            return new Result(
                title,
                progressive.optString("url", ""),
                null,
                null
            );
        }

        return null;
    }

    private static JSONObject bestProgressive(JSONArray formats) {
        JSONObject best = null;
        int height = -1;
        long bitrate = -1;

        if (formats == null) return null;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;

            String url = f.optString("url", "");
            String mime = f.optString("mimeType", "");
            if (url.isEmpty() || !mime.startsWith("video/mp4")) continue;

            int h = f.optInt("height", 0);
            long b = f.optLong("bitrate", 0);
            if (h > height || (h == height && b > bitrate)) {
                best = f;
                height = h;
                bitrate = b;
            }
        }
        return best;
    }

    private static JSONObject bestVideo(JSONArray formats) {
        JSONObject best = null;
        int height = -1;
        int fps = -1;
        long bitrate = -1;

        if (formats == null) return null;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;

            String url = f.optString("url", "");
            String mime = f.optString("mimeType", "");
            if (url.isEmpty() || !mime.startsWith("video/mp4")) continue;

            String codec = codec(mime);
            if (!(codec.startsWith("avc1")
                || codec.startsWith("hev1")
                || codec.startsWith("hvc1")
                || codec.startsWith("av01"))) continue;

            int h = f.optInt("height", 0);
            int fpx = f.optInt("fps", 0);
            long b = f.optLong("bitrate", 0);

            if (h > height
                || (h == height && fpx > fps)
                || (h == height && fpx == fps && b > bitrate)) {
                best = f;
                height = h;
                fps = fpx;
                bitrate = b;
            }
        }
        return best;
    }

    private static JSONObject bestAudio(JSONArray formats) {
        JSONObject best = null;
        long bitrate = -1;

        if (formats == null) return null;

        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;

            String url = f.optString("url", "");
            String mime = f.optString("mimeType", "");
            if (url.isEmpty() || !mime.startsWith("audio/mp4")) continue;
            if (!codec(mime).startsWith("mp4a")) continue;

            long b = f.optLong("bitrate", 0);
            if (b > bitrate) {
                best = f;
                bitrate = b;
            }
        }
        return best;
    }

    private static void mux(File video, File audio, File output) throws Exception {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer muxer = null;

        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());

            int vt = findTrack(ve, "video/");
            int at = findTrack(ae, "audio/");
            if (vt < 0 || at < 0) {
                throw new IllegalStateException("Missing media track");
            }

            muxer = new MediaMuxer(
                output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );

            int ov = muxer.addTrack(ve.getTrackFormat(vt));
            int oa = muxer.addTrack(ae.getTrackFormat(at));
            muxer.start();

            copySamples(ve, vt, muxer, ov);
            copySamples(ae, at, muxer, oa);
            muxer.stop();
        } finally {
            try { ve.release(); } catch (Exception ignored) {}
            try { ae.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static int findTrack(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String mime = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void copySamples(
        MediaExtractor e,
        int source,
        MediaMuxer muxer,
        int target
    ) {
        e.selectTrack(source);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (true) {
            int size = e.readSampleData(buffer, 0);
            if (size < 0) break;

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = e.getSampleTime();
            info.flags = e.getSampleFlags();

            muxer.writeSampleData(target, buffer, info);
            e.advance();
        }
    }

    private static void download(String url, File destination, Context context)
        throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent(context));
        c.setRequestProperty("Accept-Encoding", "identity");

        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }

            try (
                InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
                BufferedOutputStream out =
                    new BufferedOutputStream(new FileOutputStream(destination), BUFFER)
            ) {
                byte[] buffer = new byte[BUFFER];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String player(String id, Context context) throws Exception {
        URL url = new URL(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        );
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", userAgent(context));

        JSONObject client = new JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", youtubeVersion(context))
            .put("androidSdkVersion", android.os.Build.VERSION.SDK_INT)
            .put("hl", Locale.getDefault().toLanguageTag());

        JSONObject body = new JSONObject()
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", new JSONObject().put("client", client));

        c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        try {
            if (c.getResponseCode() / 100 != 2) {
                throw new IllegalStateException("Player HTTP " + c.getResponseCode());
            }

            try (
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    private static Uri createDestination(Context context, String name) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(
            MediaStore.Video.Media.RELATIVE_PATH,
            Environment.DIRECTORY_MOVIES + "/Morphe YouTube"
        );
        v.put(MediaStore.Video.Media.IS_PENDING, 1);

        return context.getContentResolver().insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v
        );
    }

    private static void copy(Context context, File source, Uri destination)
        throws Exception {
        try (
            BufferedInputStream in =
                new BufferedInputStream(new FileInputStream(source), BUFFER);
            BufferedOutputStream out =
                new BufferedOutputStream(
                    context.getContentResolver().openOutputStream(destination),
                    BUFFER
                )
        ) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private static String codec(String mime) {
        int p = mime.indexOf("codecs=");
        if (p < 0) return "";
        String s = mime.substring(p + 7).replace("\"", "");
        int end = s.indexOf(';');
        return end < 0 ? s : s.substring(0, end);
    }

    private static String youtubeVersion(Context context) {
        try {
            PackageInfo p = context.getPackageManager()
                .getPackageInfo("com.google.android.youtube", 0);
            if (p.versionName != null && !p.versionName.isEmpty()) {
                return p.versionName;
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "YouTube version lookup failed: " + e);
        }
        return "21.38.123";
    }

    private static String userAgent(Context context) {
        return String.format(
            Locale.US,
            "com.google.android.youtube/%s (Linux; Android %s) gzip",
            youtubeVersion(context),
            android.os.Build.VERSION.RELEASE
        );
    }

    private static String sanitize(String value) {
        String s = value == null ? "" : value
            .replaceAll("[\\/:*?\"<>|]", "_")
            .replaceAll("\\s+", " ")
            .trim();
        if (s.isEmpty()) s = "YouTube video";
        return s.length() > 120 ? s.substring(0, 120).trim() : s;
    }

    private static String unique(String id) {
        return id + "-" + System.currentTimeMillis();
    }

    private static void delete(File f) {
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Exception ignored) {}
        }
    }

    private static final class Result {
        final String title;
        final String progressiveUrl;
        final String videoUrl;
        final String audioUrl;

        Result(String title, String progressiveUrl, String videoUrl, String audioUrl) {
            this.title = title;
            this.progressiveUrl = progressiveUrl;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
        }
    }
}
