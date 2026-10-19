// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import java.util.Date;

/**
 * The battery protection notification. BATTERY_HOLD comes from the XP8 daemon whenever the
 * health HAL's hold starts, changes or ends; CHARGE_NOW comes from the notification's button.
 * After boot and app updates the receiver reads the hold from the HAL itself: the daemon's
 * broadcast at boot arrives before the first unlock, when this app cannot run.
 */
public class BatteryHoldReceiver extends BroadcastReceiver {
    static final String BATTERY_HOLD = "sg.ndoo.xp8.buttons.BATTERY_HOLD";
    static final String CHARGE_NOW = "sg.ndoo.xp8.buttons.CHARGE_NOW";
    static final String CHANNEL = "battery_protection";
    static final int ID = 1;
    static final int HEAT = 1, NIGHT = 2, LIMIT = 4;

    @Override
    public void onReceive(Context c, Intent it) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (CHARGE_NOW.equals(it.getAction())) {
            BatteryProtectionActivity.transact(BatteryProtectionActivity.TX_CHARGE_NOW, 0);
            nm.cancel(ID);
            return;
        }
        int reason;
        long fullBy;
        if (BATTERY_HOLD.equals(it.getAction())) {
            reason = it.getIntExtra("reason", 0);
            fullBy = it.getLongExtra("fullBy", 0);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(it.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(it.getAction())) {
            long[] hold = BatteryProtectionActivity.status();
            reason = (int) hold[0];
            fullBy = hold[1];
        } else {
            return;
        }
        if (reason != HEAT && reason != NIGHT && reason != LIMIT) {
            nm.cancel(ID);
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery protection",
                NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(c, 0,
                        new Intent(c, BatteryProtectionActivity.class), PendingIntent.FLAG_IMMUTABLE));
        String full = DateFormat.getTimeFormat(c).format(new Date(fullBy * 1000));
        if (reason == HEAT) {
            b.setContentTitle("Charging paused at 80%")
                    .setContentText("The battery is hot. Charging continues when it cools down.");
        } else {
            b.setContentTitle(reason == NIGHT ? "Charging on hold to protect battery" : "Charging limited to 80%")
                    .setContentText(reason == NIGHT ? "Full by " + full : "Battery protection is set to Limit to 80%")
                    .addAction(new Notification.Action.Builder(null, "Charge now",
                            PendingIntent.getBroadcast(c, 0,
                                    new Intent(CHARGE_NOW).setClass(c, BatteryHoldReceiver.class),
                                    PendingIntent.FLAG_IMMUTABLE)).build());
        }
        nm.notify(ID, b.build());
    }
}
