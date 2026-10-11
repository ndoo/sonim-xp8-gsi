// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.Collection;

/**
 * Which app Sonim's AppKey policy gives a key. The side-key daemon (vendor/keys/Xp8Keys.java,
 * pick()) applies the same rule; keep them equal. Apps claim a key with app_key_&lt;key&gt;_*
 * meta-data: claimants first, then the highest app_key_&lt;key&gt;_priority (stock sorts by
 * "priority DESC"), then the earliest install.
 */
final class AppKeyPolicy {
    private AppKeyPolicy() { }

    static String pick(Context c, Collection<String> pkgs, String key) {
        PackageManager pm = c.getPackageManager();
        String best = null;
        boolean bestClaim = false;
        int bestPrio = 0;
        long bestTime = 0;
        for (String p : pkgs) {
            boolean claim = false;
            int prio = 0;
            long time = Long.MAX_VALUE;
            try {
                PackageInfo i = pm.getPackageInfo(p, PackageManager.GET_META_DATA);
                time = i.firstInstallTime;
                Bundle m = i.applicationInfo != null ? i.applicationInfo.metaData : null;
                if (m != null) {
                    claim = m.containsKey("app_key_" + key + "_intent") || m.containsKey("app_key_" + key + "_priority")
                            || m.containsKey("app_key_" + key + "_data");
                    prio = priority(m, "app_key_" + key + "_priority");
                }
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            boolean better = best == null || (claim != bestClaim ? claim : prio != bestPrio ? prio > bestPrio
                    : time != bestTime ? time < bestTime : p.compareTo(best) < 0);
            if (better) {
                best = p;
                bestClaim = claim;
                bestPrio = prio;
                bestTime = time;
            }
        }
        return best;
    }

    private static int priority(Bundle m, String key) {
        Object o = m.get(key);
        try {
            return o instanceof Number ? ((Number) o).intValue() : o instanceof String ? Integer.parseInt((String) o) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
