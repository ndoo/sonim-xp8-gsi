// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.updater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Notification channels and builders shared by the service, the job and the boot receiver. */
final class Notices {
    static final String PROGRESS = "progress";
    static final String ALERTS = "alerts";
    /** The ongoing download and install notification. */
    static final int ID_PROGRESS = 1;
    /** Update available, restart, failure and updated; each replaces the previous one. */
    static final int ID_RESULT = 2;

    private Notices() {}

    static NotificationManager manager(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(PROGRESS, "System update progress",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERTS, "System updates",
                NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }

    static Notification.Builder builder(Context c, String channel, String title, String text) {
        return new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(c, 0,
                        new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }

    static Notification.Action action(Context c, String label, String action) {
        return new Notification.Action.Builder(null, label, PendingIntent.getService(c, action.hashCode(),
                new Intent(c, UpdateService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE)).build();
    }
}
