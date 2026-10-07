// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.updater;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.UpdateEngine;
import android.os.UpdateEngine.UpdateStatusConstants;
import android.os.UpdateEngineCallback;
import android.provider.Settings;
import android.text.format.DateUtils;

import org.json.JSONObject;

/**
 * Checks ota.json once a day and posts "System update available" once per release. An
 * installable release is then downloaded by a one-off job on an unmetered network with the
 * battery not low, like Pixel's automatic downloads, unless Developer options > Automatic
 * system updates is off.
 */
public class UpdateCheckJob extends JobService {
    static final int CHECK = 1;
    static final int DOWNLOAD = 2;

    final Handler ui = new Handler(Looper.getMainLooper());

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(CHECK) != null) return;
        js.schedule(new JobInfo.Builder(CHECK, new ComponentName(c, UpdateCheckJob.class))
                .setPeriodic(DateUtils.DAY_IN_MILLIS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    static void scheduleDownload(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(DOWNLOAD) != null) return;
        js.schedule(new JobInfo.Builder(DOWNLOAD, new ComponentName(c, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(MainActivity.fetch(MainActivity.OTA_JSON));
                getSharedPreferences("updater", MODE_PRIVATE).edit()
                        .putLong("checked", System.currentTimeMillis()).apply();
                if (params.getJobId() == CHECK) {
                    check(j);
                    jobFinished(params, false);
                } else {
                    ui.post(() -> download(params, j));
                }
            } catch (Exception e) {
                jobFinished(params, true);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static boolean automatic(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "ota_disable_automatic_update", 0) == 0;
    }

    static boolean installable(JSONObject j) {
        String tag = j.optString("tag");
        return !tag.isEmpty() && !tag.equals(MainActivity.release())
                && MainActivity.layout() >= j.optInt("min_vendor_layout", 1);
    }

    void check(JSONObject j) {
        String tag = j.optString("tag");
        if (tag.isEmpty() || tag.equals(MainActivity.release())) return;
        boolean ok = installable(j);
        boolean auto = ok && automatic(this);
        if (auto) scheduleDownload(this);
        SharedPreferences prefs = getSharedPreferences("updater", MODE_PRIVATE);
        if (tag.equals(prefs.getString("offered", ""))) return;
        prefs.edit().putString("offered", tag).apply();
        String text = !ok ? tag + " needs an update from a computer. Tap for details."
                : auto ? "Your device can be updated to " + tag + ". It downloads on Wi-Fi, or tap to download now."
                : "Your device can be updated to " + tag + ". Tap to download.";
        Notices.manager(this).notify(Notices.ID_RESULT, Notices.builder(this, Notices.ALERTS,
                "System update available", text).setAutoCancel(true).build());
    }

    /** Starts the payload only if update_engine is idle; bind() reports its status first. */
    void download(JobParameters params, JSONObject j) {
        UpdateEngine engine = new UpdateEngine();
        UpdateEngineCallback callback = new UpdateEngineCallback() {
            boolean done;

            @Override
            public void onStatusUpdate(int s, float percent) {
                if (done) return;
                done = true;
                if (s == UpdateStatusConstants.IDLE && installable(j) && automatic(UpdateCheckJob.this)) {
                    Network net = params.getNetwork();
                    MainActivity.apply(UpdateCheckJob.this, engine, j.optJSONObject("payload"),
                            net != null ? net.getNetworkHandle() : 0, true);
                }
                UpdateService.ENGINE.execute(engine::unbind);
                jobFinished(params, false);
            }

            @Override
            public void onPayloadApplicationComplete(int error) {}
        };
        UpdateService.ENGINE.execute(() -> engine.bind(callback, ui));
    }
}
