// SPDX-FileCopyrightText: 2026 no0406
// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.text.InputType;
import android.view.MenuItem;
import android.view.WindowInsets;
import android.widget.EditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Settings > Programmable keys: what the XP8 side keys do. Writes files/keys.properties;
 * the side-key daemon (vendor/keys/Xp8Keys.java) reads it on every key press.
 *
 * Per key (ptt, camera, sos; shown with Sonim's names PTT, YELLOW and ALARM):
 *   KEY.forward  "" (off), "*" (every push-to-talk app) or a package: press and
 *                release go to that app as on stock (Sonim, Kodiak or MCPTT intents)
 *   KEY.short    action on a short press, when not forwarding
 *   KEY.long     action on a press as long as Android's touch & hold delay
 *                (Accessibility; 0.4 s by default), when not forwarding
 *   KEY.hold     ms to hold before a forwarded press is sent (Sonim's "Press and
 *                Hold timer to engage PTT Key"); 0 = at once
 * Actions: none, camera, torch, assist, playpause, dnd, call:<number>,
 *          launch:<package>/<activity>
 */
public class MainActivity extends Activity {
    static final String[] KEYS = {"ptt", "camera", "sos"};
    static final String[] TITLES = {"PTT key", "YELLOW key", "ALARM key"};
    static final String[] PTT_ACTIONS = {"com.sonim.intent.action.PTT_KEY_DOWN",
        "com.kodiak.intent.action.PTT_BUTTON", "com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY"};
    static final String[] SOS_ACTIONS = {"com.sonim.intent.action.SOS_KEY_DOWN",
        "com.kodiak.intent.action.KEYCODE_SOS"};

    final Properties config = new Properties();
    File file;
    PreferenceScreen screen;

    public static class KeysFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle saved) {
            super.onCreate(saved);
            MainActivity a = (MainActivity) getActivity();
            a.screen = getPreferenceManager().createPreferenceScreen(a);
            setPreferenceScreen(a.screen);
            a.render();
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        // Before super.onCreate, which recreates a saved KeysFragment.
        file = new File(getFilesDir(), "keys.properties");
        defaults();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) { config.load(in); } catch (Exception e) { }
        }
        super.onCreate(saved);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener((v, in) -> {
            Insets b = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsets.CONSUMED;
        });
        if (saved == null)
            getFragmentManager().beginTransaction().replace(android.R.id.content, new KeysFragment()).commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        screen.removeAll();
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            PreferenceCategory cat = new PreferenceCategory(this);
            cat.setTitle(TITLES[i]);
            screen.addPreference(cat);
            String fwd = config.getProperty(key + ".forward", "");
            cat.addPreference(pref("Send to push-to-talk app", forwardLabel(fwd), () -> chooseForward(key)));
            if (!fwd.isEmpty()) cat.addPreference(hold(key));
            boolean on = fwd.isEmpty();
            String off = "Off while this key sends to a push-to-talk app";
            Preference s = pref("Short press", on ? actionLabel(config.getProperty(key + ".short", "none")) : off,
                () -> chooseAction(key, "short"));
            Preference l = pref("Long press", on ? actionLabel(config.getProperty(key + ".long", "none")) : off,
                () -> chooseAction(key, "long"));
            s.setEnabled(on);
            l.setEnabled(on);
            cat.addPreference(s);
            cat.addPreference(l);
        }
        // Settings has no exported Timing controls page; open Accessibility with that row highlighted.
        screen.addPreference(pref("Long press delay", longPressLabel(), () -> startActivity(
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", "tap_assistance_preference_screen"))));
    }

    String longPressLabel() {
        int ms = Settings.Secure.getInt(getContentResolver(), "long_press_timeout", 400);
        return ms == 400 ? "Short" : ms == 1000 ? "Medium" : ms == 1500 ? "Long" : ms + " ms";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen != null) render();
    }

    Preference pref(String title, String summary, Runnable click) {
        Preference p = new Preference(this);
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(summary);
        p.setOnPreferenceClickListener(x -> { click.run(); return true; });
        return p;
    }

    ListPreference hold(String key) {
        ListPreference p = new ListPreference(this);
        p.setPersistent(false);
        p.setTitle("Press and hold to engage");
        p.setDialogTitle("Press and hold to engage");
        p.setEntries(HOLD_LABELS);
        p.setEntryValues(HOLD_VALUES);
        String ms = config.getProperty(key + ".hold", "0");
        p.setValue(ms);
        p.setSummary(holdLabel(ms));
        p.setOnPreferenceChangeListener((x, v) -> {
            config.setProperty(key + ".hold", (String) v);
            save();
            p.setSummary(holdLabel((String) v));
            return true;
        });
        return p;
    }

    // Push-to-talk apps: packages that take one of the daemon's actions for this key.
    List<String> pttApps(String key) {
        String[] actions = key.equals("sos") ? SOS_ACTIONS : PTT_ACTIONS;
        List<String> out = new ArrayList<>();
        for (String a : actions) {
            for (ResolveInfo r : getPackageManager().queryBroadcastReceivers(new Intent(a), 0))
                if (!out.contains(r.activityInfo.packageName)) out.add(r.activityInfo.packageName);
            if (!key.equals("sos"))
                for (ResolveInfo r : getPackageManager().queryIntentServices(new Intent(a), 0))
                    if (!out.contains(r.serviceInfo.packageName)) out.add(r.serviceInfo.packageName);
        }
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
                String cur = config.getProperty(key + ".forward", "");
                if (key.equals("ptt") && values.get(w).isEmpty() && labels.size() > 2 && !cur.isEmpty()) {
                    confirmPttOff(cur.equals("*") ? String.join(", ", labels.subList(2, labels.size())) : appLabel(cur));
                    return;
                }
                setForward(key, values.get(w));
            }).show();
    }

    // Adapted from stock's prog_keys_change_pttkey_function warning.
    void confirmPttOff(String apps) {
        new AlertDialog.Builder(this)
            .setMessage(apps + " will no longer get the PTT key, so you can't talk in push-to-talk calls "
                + "with it. Turn it off?")
            .setPositiveButton("Turn off", (d, w) -> setForward("ptt", ""))
            .setNegativeButton("Cancel", null).show();
    }

    void setForward(String key, String value) {
        config.setProperty(key + ".forward", value);
        save();
        render();
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
}
