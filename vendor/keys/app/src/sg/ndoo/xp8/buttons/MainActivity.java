// SPDX-FileCopyrightText: 2026 no0406
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Settings for the XP8 side keys. Writes files/keys.properties; the side-key daemon
 * (vendor/keys/Xp8Keys.java) reads it on every key press.
 *
 * Per key (ptt, sos, camera):
 *   KEY.forward  "" (off), "*" (every push-to-talk app) or a package: press and
 *                release go to that app as Sonim's PTT/SOS broadcasts
 *   KEY.short    action on a short press, when not forwarding
 *   KEY.long     action on a press as long as Android's touch & hold delay
 *                (Accessibility; 0.4 s by default), when not forwarding
 *   KEY.hold     ms to hold before a forwarded press is sent (Sonim's "Press and
 *                Hold timer to engage PTT Key"); 0 = at once
 * Actions: none, camera, torch, assist, playpause, dnd, call:<number>,
 *          launch:<package>/<activity>
 */
public class MainActivity extends Activity {
    static final String[] KEYS = {"ptt", "sos", "camera"};
    static final String[] TITLES = {"PTT key", "SOS key", "Camera key"};
    static final String SONIM = "com.sonim.intent.action.";

    final Properties config = new Properties();
    File file;
    LinearLayout list;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        file = new File(getFilesDir(), "keys.properties");
        defaults();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) { config.load(in); } catch (Exception e) { }
        }
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);
        scroll.setFitsSystemWindows(true);
        render();
    }

    void defaults() {
        config.setProperty("ptt.forward", "*");
        config.setProperty("sos.forward", "*");
        config.setProperty("camera.forward", "");
        config.setProperty("camera.short", "camera");
    }

    void save() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            config.store(out, "XP8 Buttons; read by the side-key daemon (Xp8Keys)");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("Could not save: " + e).show();
        }
    }

    void render() {
        list.removeAllViews();
        list.addView(text("XP8 Buttons", 22, true));
        list.addView(text("Choose what the side keys do. A key that sends to a push-to-talk app works like on "
                + "stock: hold to talk, also with the app in the background. Otherwise a short and a long "
                + "press can each run an action: flashlight, camera, a call, an app and more. "
                + "A long press lasts as long as the touch & hold delay in Accessibility settings. "
                + "Changes apply on the next key press.", 14, false));
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            list.addView(gap());
            list.addView(text(TITLES[i], 18, true));
            String fwd = config.getProperty(key + ".forward", "");
            list.addView(row("Send to push-to-talk app", forwardLabel(fwd), v -> chooseForward(key)));
            if (!fwd.isEmpty())
                list.addView(row("Press and hold to engage", holdLabel(config.getProperty(key + ".hold", "0")), v -> chooseHold(key)));
            boolean on = fwd.isEmpty();
            View s = row("Short press", actionLabel(config.getProperty(key + ".short", "none")), v -> chooseAction(key, "short"));
            View l = row("Long press", actionLabel(config.getProperty(key + ".long", "none")), v -> chooseAction(key, "long"));
            s.setEnabled(on);
            l.setEnabled(on);
            s.setAlpha(on ? 1f : 0.4f);
            l.setAlpha(on ? 1f : 0.4f);
            list.addView(s);
            list.addView(l);
            if (!on) list.addView(text("Short and long press are off while this key sends to a push-to-talk app.", 12, false));
        }
    }

    // Push-to-talk apps: packages with a receiver for the Sonim broadcast of this key.
    List<String> pttApps(String key) {
        String action = SONIM + (key.equals("sos") ? "SOS_KEY_DOWN" : "PTT_KEY_DOWN");
        List<String> out = new ArrayList<>();
        for (ResolveInfo r : getPackageManager().queryBroadcastReceivers(new Intent(action), 0))
            if (!out.contains(r.activityInfo.packageName)) out.add(r.activityInfo.packageName);
        return out;
    }

    void chooseForward(String key) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        values.add(""); labels.add("Off");
        values.add("*"); labels.add("All push-to-talk apps");
        for (String p : pttApps(key)) { values.add(p); labels.add(appLabel(p)); }
        new AlertDialog.Builder(this).setTitle("Send to push-to-talk app")
            .setItems(labels.toArray(new String[0]), (d, w) -> {
                config.setProperty(key + ".forward", values.get(w));
                save();
                render();
            }).show();
    }

    void chooseAction(String key, String press) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        values.add("none"); labels.add("Nothing");
        values.add("torch"); labels.add("Flashlight on/off");
        values.add("camera"); labels.add("Open camera");
        values.add("call:"); labels.add("Call a number…");
        values.add("assist"); labels.add("Voice assistant");
        values.add("playpause"); labels.add("Play / pause media");
        values.add("dnd"); labels.add("Do Not Disturb on/off");
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(launcher, 0);
        PackageManager pm = getPackageManager();
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        for (ResolveInfo r : apps) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            values.add("launch:" + r.activityInfo.packageName + "/" + r.activityInfo.name);
            labels.add("Open " + r.loadLabel(pm));
        }
        new AlertDialog.Builder(this).setTitle(press.equals("short") ? "Short press" : "Long press")
            .setItems(labels.toArray(new String[0]), (d, w) -> {
                if (values.get(w).equals("call:")) { askNumber(key, press); return; }
                config.setProperty(key + "." + press, values.get(w));
                save();
                render();
            }).show();
    }

    void askNumber(String key, String press) {
        EditText number = new EditText(this);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        String cur = config.getProperty(key + "." + press, "");
        if (cur.startsWith("call:")) number.setText(cur.substring(5));
        new AlertDialog.Builder(this).setTitle("Number to call").setView(number)
            .setPositiveButton("OK", (d, w) -> {
                String n = number.getText().toString().replaceAll("[^0-9+*#]", "");
                if (n.isEmpty()) return;
                config.setProperty(key + "." + press, "call:" + n);
                save();
                render();
            })
            .setNegativeButton("Cancel", null).show();
    }

    static final String[] HOLD_LABELS = {"Off", "0.1 second", "0.25 second", "0.5 second", "1 second",
        "1.25 seconds", "1.5 seconds", "1.75 seconds", "2 seconds", "2.25 seconds", "2.5 seconds",
        "2.75 seconds", "3 seconds"};
    static final String[] HOLD_VALUES = {"0", "100", "250", "500", "1000", "1250", "1500", "1750",
        "2000", "2250", "2500", "2750", "3000"};

    void chooseHold(String key) {
        new AlertDialog.Builder(this).setTitle("Press and hold to engage")
            .setItems(HOLD_LABELS, (d, w) -> {
                config.setProperty(key + ".hold", HOLD_VALUES[w]);
                save();
                render();
            }).show();
    }

    String holdLabel(String ms) {
        for (int i = 0; i < HOLD_VALUES.length; i++) if (HOLD_VALUES[i].equals(ms)) return HOLD_LABELS[i];
        return ms + " ms";
    }

    String forwardLabel(String fwd) {
        return fwd.isEmpty() ? "Off" : fwd.equals("*") ? "All push-to-talk apps" : appLabel(fwd);
    }

    String actionLabel(String action) {
        if (action.equals("camera")) return "Open camera";
        if (action.equals("torch")) return "Flashlight on/off";
        if (action.equals("assist")) return "Voice assistant";
        if (action.equals("playpause")) return "Play / pause media";
        if (action.equals("dnd")) return "Do Not Disturb on/off";
        if (action.startsWith("call:")) return "Call " + action.substring(5);
        if (action.startsWith("launch:")) return "Open " + appLabel(action.substring(7, action.indexOf('/')));
        return "Nothing";
    }

    String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    View row(String title, String value, View.OnClickListener click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title + "\n" + value);
        b.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        b.setOnClickListener(click);
        return b;
    }

    TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    View gap() {
        View v = new View(this);
        v.setMinimumHeight(dp(16));
        return v;
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
