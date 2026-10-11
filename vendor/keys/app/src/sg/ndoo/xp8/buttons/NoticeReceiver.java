// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Sent by the side-key daemon (root) when a key's app changes or a key has none. */
public class NoticeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            int from = getSentFromUid();
            if (from != 0 && from != 1000) return;
        }
        String key = intent.getStringExtra("key");
        String pkg = intent.getStringExtra("pkg");
        if (key == null || pkg == null) return;
        Notices.toast(context, key.equals("ptt") ? "PTT" : key.equals("sos") ? "ALARM" : "YELLOW", pkg);
    }
}
