// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.updater;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/** Schedules the daily check and, after booting a new release, posts "System updated". */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        UpdateCheckJob.schedule(c);
        SharedPreferences prefs = c.getSharedPreferences("updater", Context.MODE_PRIVATE);
        String was = prefs.getString("booted", "");
        String now = MainActivity.release();
        prefs.edit().putString("booted", now).remove("paused").apply();
        if (was.isEmpty() || now.isEmpty() || was.equals(now)) return;
        Notices.manager(c).notify(Notices.ID_RESULT, Notices.builder(c, Notices.ALERTS,
                "System updated", "Your device is updated to " + now + ". Tap to see what's new.")
                .setContentIntent(PendingIntent.getActivity(c, 0,
                        new Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.RELEASES + now))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build());
    }
}
