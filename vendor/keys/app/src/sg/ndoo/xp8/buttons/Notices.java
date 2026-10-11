// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.Toast;

/** The toasts of Sonim's AppKey policy, with its wording. */
final class Notices {
    private Notices() { }

    /**
     * NAME is the key as shown (PTT, YELLOW, ALARM). PKG is the app it goes to; "" means no app,
     * null means automatic, which only the side-key daemon can resolve and announces itself.
     */
    static void toast(Context c, String name, String pkg) {
        if (pkg == null) return;
        String text;
        if (pkg.isEmpty()) {
            text = "No app assigned to " + name + " KEY";
        } else {
            text = name + " KEY assigned to an application named: " + label(c, pkg);
        }
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }

    static void say(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
