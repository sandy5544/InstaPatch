package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class MorpheDownloadsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showLibrary();
    }

    private void showLibrary() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(24, 32, 24, 24);

        TextView title = new TextView(this);
        title.setText("Morphe Downloads");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 72));

        JSONArray items = readItems();
        if (items.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No downloaded videos yet");
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(16);
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                TextView row = new TextView(this);
                row.setText(item.optString("title", "YouTube video"));
                row.setTextColor(Color.WHITE);
                row.setTextSize(16);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(16, 12, 16, 12);
                final String uri = item.optString("uri", "");
                row.setOnClickListener(v -> play(uri));
                root.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 64));
            }
        }

        setContentView(root);
    }

    private void play(String uriString) {
        if (uriString.isEmpty()) return;

        VideoView video = new VideoView(this);
        video.setBackgroundColor(Color.BLACK);
        video.setVideoURI(Uri.parse(uriString));
        video.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            video.start();
        });
        setContentView(video);
    }

    private JSONArray readItems() {
        File file = new File(getFilesDir(), "morphe_downloads.json");
        if (!file.isFile()) return new JSONArray();

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            int read;
            while (offset < data.length
                    && (read = input.read(data, offset, data.length - offset)) > 0) {
                offset += read;
            }
            return new JSONArray(new String(data, 0, offset, StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }
}
