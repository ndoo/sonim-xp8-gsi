// SPDX-FileCopyrightText: 2026 no0406
// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;
import android.os.Build;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
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
 *   KEY.forward  "" (off), "auto" (the app Sonim's AppKey policy picks; "*" from older
 *                versions means the same) or a package: press and release go to that
 *                one app as on stock (Sonim, Kodiak or MCPTT intents)
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
        if (Build.VERSION.SDK_INT >= 30)                        // edge-to-edge from Android 15 on
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
        config.setProperty("ptt.forward", "auto");
        config.setProperty("sos.forward", "auto");
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
            Preference app = pref("Select " + keyName(key) + " Key app", assignment(key), () -> chooseApp(key));
            String pkg = assignedPackage(key);
            if (pkg != null) app.setIcon(appIcon(pkg));
            cat.addPreference(app);
            if (key.equals("ptt")) cat.addPreference(hold(key));
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

    // What the key does now, in the words of stock's "Select ... Key app" summaries.
    String assignment(String key) {
        String fwd = config.getProperty(key + ".forward", "");
        if (fwd.equals("*") || fwd.equals("auto")) {
            String a = AppKeyPolicy.pick(this, pttApps(key), key.equals("sos") ? "sos" : "ptt");
            return a == null ? "Automatic (no app)" : "Automatic (" + appLabel(a) + ")";
        }
        if (!fwd.isEmpty()) return pttApps(key).contains(fwd) ? appLabel(fwd) : appLabel(fwd) + " (not installed)";
        String action = config.getProperty(key + ".short", "none");
        return action.equals("none") ? "No Application" : action.equals("camera") ? "Camera"
            : action.startsWith("launch:") ? appLabel(action.substring(7, action.indexOf('/'))) : actionLabel(action);
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

    // Titles wrap, as on stock, instead of being cut off.
    static void wrap(View v) {
        TextView t = (TextView) v.findViewById(android.R.id.title);
        if (t != null) {
            t.setSingleLine(false);
            t.setMaxLines(3);
        }
    }

    static final class Row extends Preference {
        Row(Context c) { super(c); }

        @Override
        protected void onBindView(View v) {
            super.onBindView(v);
            wrap(v);
        }
    }

    static final class HoldRow extends ListPreference {
        HoldRow(Context c) { super(c); }

        @Override
        protected void onBindView(View v) {
            super.onBindView(v);
            wrap(v);
        }
    }

    Preference pref(String title, String summary, Runnable click) {
        Preference p = new Row(this);
        p.setPersistent(false);
        p.setTitle(title);
        p.setSummary(summary);
        p.setOnPreferenceClickListener(x -> { click.run(); return true; });
        return p;
    }

    ListPreference hold(String key) {
        ListPreference p = new HoldRow(this);
        p.setPersistent(false);
        p.setTitle("Select Press and Hold timer to engage PTT Key");
        p.setDialogTitle("Select Press and Hold timer to engage PTT Key");
        p.setEntries(HOLD_LABELS);
        p.setEntryValues(HOLD_VALUES);
        String ms = config.getProperty(key + ".hold", "0");
        p.setValue(ms);
        p.setSummary(holdLabel(ms));
        p.setOnPreferenceChangeListener((x, v) -> {
            config.setProperty(key + ".hold", (String) v);
            save();
            p.setSummary(holdLabel((String) v));
            if (!v.equals("0")) Notices.say(this, "Press and Hold only takes effect with the screen off, "
                    + "or with the screen on and the PTT app in the background.");
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

    static final class Entry {
        final String value, label;
        final Drawable icon;

        Entry(String value, String label, Drawable icon) {
            this.value = value;
            this.label = label;
            this.icon = icon;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // Like stock: "No Application", then every launchable app by name, with its icon. Apps that take the
    // key as a push-to-talk button get it as before; any other app opens when the key is pressed.
    void chooseApp(String key) {
        PackageManager pm = getPackageManager();
        List<String> ptt = pttApps(key);
        List<Entry> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
            seen.add(pkg);
            apps.add(new Entry(ptt.contains(pkg) ? pkg : "launch:" + pkg + "/" + r.activityInfo.name,
                    r.loadLabel(pm).toString(), r.loadIcon(pm)));
        }
        for (String pkg : ptt)                                  // takes the key but has no launcher icon
            if (!seen.contains(pkg)) apps.add(new Entry(pkg, appLabel(pkg), appIcon(pkg)));
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("", "No Application", null));
        if (!key.equals("camera")) entries.add(new Entry("auto", "Automatic", null));
        entries.addAll(apps);
        new AlertDialog.Builder(this).setTitle("Select " + keyName(key) + " Key app")
            .setAdapter(iconAdapter(entries), (d, w) -> {
                String v = entries.get(w).value;
                if (v.startsWith("launch:")) {
                    config.setProperty(key + ".forward", "");
                    config.setProperty(key + ".short", v);
                } else {
                    config.setProperty(key + ".forward", v);
                    if (v.isEmpty()) {
                        config.setProperty(key + ".short", "none");
                        config.setProperty(key + ".long", "none");
                    }
                }
                save();
                render();
                String pkg = v.startsWith("launch:") ? v.substring(7, v.indexOf('/')) : v;
                Notices.toast(this, keyName(key), v.equals("auto") ? null : pkg);
            }).show();
    }

    // A list of names, each with its icon when it has one.
    ArrayAdapter<Entry> iconAdapter(List<Entry> entries) {
        int size = dp(40);
        return new ArrayAdapter<Entry>(this, android.R.layout.select_dialog_item, entries) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convert, parent);
                Drawable d = getItem(position).icon;
                d = d != null ? d : new ColorDrawable(Color.TRANSPARENT);     // keeps the names aligned
                d.setBounds(0, 0, size, size);
                t.setCompoundDrawables(d, null, null, null);
                t.setCompoundDrawablePadding(dp(16));
                return t;
            }
        };
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    Drawable appIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // The package the key's row shows an icon for, or null.
    String assignedPackage(String key) {
        String fwd = config.getProperty(key + ".forward", "");
        if (fwd.equals("*") || fwd.equals("auto")) return AppKeyPolicy.pick(this, pttApps(key), key.equals("sos") ? "sos" : "ptt");
        if (!fwd.isEmpty()) return fwd;
        String action = config.getProperty(key + ".short", "none");
        return action.startsWith("launch:") ? action.substring(7, action.indexOf('/')) : null;
    }

    String keyName(String key) {
        return key.equals("ptt") ? "PTT" : key.equals("sos") ? "ALARM" : "YELLOW";
    }

    void chooseAction(String key, String press) {
        PackageManager pm = getPackageManager();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("none", "Nothing", null));
        entries.add(new Entry("torch", "Flashlight on/off", null));
        entries.add(new Entry("camera", "Open camera", null));
        entries.add(new Entry("call:", "Call a number…", null));
        entries.add(new Entry("assist", "Voice assistant", null));
        entries.add(new Entry("playpause", "Play / pause media", null));
        entries.add(new Entry("dnd", "Do Not Disturb on/off", null));
        List<ResolveInfo> apps = pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        for (ResolveInfo r : apps) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            entries.add(new Entry("launch:" + r.activityInfo.packageName + "/" + r.activityInfo.name,
                    "Open " + r.loadLabel(pm), r.loadIcon(pm)));
        }
        new AlertDialog.Builder(this).setTitle(press.equals("short") ? "Short press" : "Long press")
            .setAdapter(iconAdapter(entries), (d, w) -> {
                String v = entries.get(w).value;
                if (v.equals("call:")) { askNumber(key, press); return; }
                config.setProperty(key + "." + press, v);
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
