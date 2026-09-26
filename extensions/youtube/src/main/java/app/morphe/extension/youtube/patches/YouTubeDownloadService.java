package app.morphe.extension.youtube.patches;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class YouTubeDownloadService extends Service {
    public static final String ACTION_START =
            "app.morphe.extension.youtube.action.START_DOWNLOAD";
    public static final String EXTRA_VIDEO_ID = "video_id";

    private static final String CHANNEL_ID = "morphe_youtube_downloads";
    private static final int NOTIFICATION_ID = 0x4D59;
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r ->
                    new Thread(r, "MorpheYouTubeDownload"));

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (!startForegroundSafely(videoId)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        EXECUTOR.execute(() -> {
            try {
                YouTubeDownloadJob.run(
                        getApplicationContext(), videoId,
                        (progress, text) -> updateNotification(videoId, progress, text));
            } catch (UnsupportedOperationException ex) {
                Logger.printDebug(() ->
                        "Native YouTube download unavailable: " + ex.getMessage());
                Utils.runOnMainThread(() ->
                        Utils.showToastShort(
                                "Morphe: this stream is not available for in-app download"));
            } catch (Throwable ex) {
                Logger.printException(
                        () -> "Native YouTube download service failed", ex);
                Utils.runOnMainThread(() ->
                        Utils.showToastShort("Morphe: YouTube download failed"));
            } finally {
                stopSelfResult(startId);
            }
        });

        return START_NOT_STICKY;
    }

    /*
     * Android 15 limits dataSync foreground-service runtime. Stop promptly
     * when the platform reports the timeout instead of allowing a process
     * exception after the grace period.
     */
    public void onTimeout(int startId, int fgsType) {
        Logger.printDebug(() ->
                "YouTube download foreground-service timeout: " + fgsType);
        stopSelf(startId);
    }

    private boolean startForegroundSafely(String title) {
        try {
            Notification notification =
                    buildNotification(title, -1, "Preparing download");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Could not enter foreground download state", ex);
            return false;
        }
    }

    private void updateNotification(String title, int progress, String text) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(
                        NOTIFICATION_ID,
                        buildNotification(title, progress, text));
            }
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Download notification update failed", ex);
        }
    }

    private Notification buildNotification(String title, int progress, String text) {
        Notification.Builder builder =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        Intent libraryIntent =
                new Intent(this, MorpheDownloadsActivity.class);
        PendingIntent libraryPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        libraryIntent,
                        Build.VERSION.SDK_INT >= 23
                                ? PendingIntent.FLAG_UPDATE_CURRENT
                                        | PendingIntent.FLAG_IMMUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setSmallIcon(
                        progress >= 100
                                ? android.R.drawable.stat_sys_download_done
                                : android.R.drawable.stat_sys_download)
                .setContentIntent(libraryPendingIntent)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(progress < 100)
                .setAutoCancel(progress >= 100);

        if (progress >= 0 && progress < 100) {
            builder.setProgress(100, progress, false);
        } else {
            builder.setProgress(0, 0, progress < 0);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(
                        new NotificationChannel(
                                CHANNEL_ID, "Morphe YouTube downloads",
                                NotificationManager.IMPORTANCE_LOW));
            }
        } catch (Throwable ex) {
            Logger.printException(
                    () -> "Could not create download notification channel", ex);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
