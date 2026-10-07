// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.updater;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.os.UpdateEngine.UpdateStatusConstants;
import android.os.UpdateEngineCallback;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps an ongoing progress notification while update_engine applies a payload, then replaces
 * it with a restart or failure notification. Pause and resume suspend the download.
 */
public class UpdateService extends Service {
    static final String ACTION_PAUSE = "sg.ndoo.xp8.updater.PAUSE";
    static final String ACTION_RESUME = "sg.ndoo.xp8.updater.RESUME";
    static final String ACTION_REBOOT = "sg.ndoo.xp8.updater.REBOOT";
    static final int USER_CANCELED = 48;
    /** Runs every UpdateEngine call: update_engine can block a caller for seconds while downloading. */
    static final ExecutorService ENGINE = Executors.newSingleThreadExecutor();

    final Handler ui = new Handler(Looper.getMainLooper());
    NotificationManager nm;
    SharedPreferences prefs;
    UpdateEngine engine;
    int status = UpdateStatusConstants.DOWNLOADING;
    int percent = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = Notices.manager(this);
        prefs = getSharedPreferences("updater", MODE_PRIVATE);
        engine = new UpdateEngine();
        UpdateEngineCallback callback = new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int s, float p) {
                status(s, p);
            }

            @Override
            public void onPayloadApplicationComplete(int error) {
                // An automatic download that failed is retried after the next daily check.
                if (error != 0 && error != USER_CANCELED && !prefs.getBoolean("auto", false)) {
                    finish(Notices.builder(UpdateService.this, Notices.ALERTS,
                            "Couldn't install the update",
                            "update_engine reported error " + error + ". Tap to try again.")
                            .setAutoCancel(true).build());
                }
            }
        };
        ENGINE.execute(() -> engine.bind(callback, ui));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_REBOOT.equals(action)) {
            getSystemService(PowerManager.class).reboot(null);
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action)) {
            pause(this, engine, ACTION_PAUSE.equals(action));
        }
        startForeground(Notices.ID_PROGRESS, progress(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ENGINE.execute(engine::unbind);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** update_engine reports no status for a suspended download, so the flag lives in prefs. */
    static void pause(Context c, UpdateEngine e, boolean pause) {
        c.getSharedPreferences("updater", MODE_PRIVATE).edit().putBoolean("paused", pause).commit();
        ENGINE.execute(pause ? e::suspend : e::resume);
    }

    void status(int s, float p) {
        switch (s) {
            case UpdateStatusConstants.UPDATE_AVAILABLE:
            case UpdateStatusConstants.DOWNLOADING:
            case UpdateStatusConstants.VERIFYING:
            case UpdateStatusConstants.FINALIZING:
                int whole = (int) (p * 100);
                if (s == status && whole == percent) return;
                status = s;
                percent = whole;
                nm.notify(Notices.ID_PROGRESS, progress());
                break;
            case UpdateStatusConstants.UPDATED_NEED_REBOOT:
                finish(Notices.builder(this, Notices.ALERTS, "Restart to finish installing",
                        "The update is installed on the other slot. Your data is kept.")
                        .addAction(Notices.action(this, "Restart now", ACTION_REBOOT))
                        .build());
                break;
            case UpdateStatusConstants.IDLE:
                // A failed apply reports IDLE before onPayloadApplicationComplete.
                status = s;
                ui.postDelayed(() -> { if (status == UpdateStatusConstants.IDLE) finish(null); }, 2000);
                break;
            default:
                break;
        }
    }

    /** Leaves the foreground and posts {@code n}, if any, in place of the progress. */
    void finish(Notification n) {
        prefs.edit().remove("paused").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (n != null) nm.notify(Notices.ID_RESULT, n);
        stopSelf();
    }

    Notification progress() {
        boolean installing = status == UpdateStatusConstants.VERIFYING
                || status == UpdateStatusConstants.FINALIZING;
        boolean paused = !installing && prefs.getBoolean("paused", false);
        Notification.Builder b = Notices.builder(this, Notices.PROGRESS,
                paused ? "System update paused"
                        : installing ? "Installing system update" : "Downloading system update",
                percent < 0 ? "" : percent + "%")
                .setProgress(100, Math.max(percent, 0), percent < 0)
                .setOngoing(true);
        if (!installing) {
            b.addAction(paused ? Notices.action(this, "Resume", ACTION_RESUME)
                    : Notices.action(this, "Pause", ACTION_PAUSE));
        }
        return b.build();
    }
}
