// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.updater;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngine.UpdateStatusConstants;
import android.os.UpdateEngineCallback;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Settings > System > System update, laid out like the Pixel screen. Checks the latest
 * release's ota.json and installs its payload with update_engine, which streams it from
 * GitHub into the inactive slot. ro.xp8.release (system) names the installed release;
 * ro.vendor.xp8.layout (vendor) must reach the payload's min_vendor_layout, else the
 * release needs a PC update. Layout 3 also means flash.sh prepared slot b's firmware.
 */
public class MainActivity extends Activity {
    static final String OTA_JSON =
            "https://github.com/ndoo/sonim-xp8-gsi/releases/latest/download/ota.json";
    static final String RELEASES = "https://github.com/ndoo/sonim-xp8-gsi/releases/tag/";

    final Handler ui = new Handler(Looper.getMainLooper());
    UpdateEngine engine;
    SharedPreferences prefs;
    TextView title;
    TextView body;
    TextView progressText;
    ProgressBar bar;
    Button primary;
    Button notes;
    Switch automatic;
    JSONObject ota;
    int engineStatus = UpdateStatusConstants.IDLE;
    float enginePercent;
    boolean serviceStarted;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("updater", MODE_PRIVATE);
        int accent = attrColor(android.R.attr.colorAccent);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(24));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_system_update);
        content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = text(28, attrColor(android.R.attr.textColorPrimary));
        title.setPadding(0, dp(24), 0, dp(16));
        content.addView(title);
        body = text(16, attrColor(android.R.attr.textColorSecondary));
        body.setLineSpacing(dp(4), 1f);
        content.addView(body);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setPadding(0, dp(24), 0, 0);
        content.addView(bar);
        progressText = text(14, attrColor(android.R.attr.textColorSecondary));
        content.addView(progressText);
        automatic = new Switch(this);
        automatic.setText("Download updates automatically on Wi-Fi");
        automatic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        automatic.setTextColor(attrColor(android.R.attr.textColorPrimary));
        automatic.setPadding(0, dp(24), 0, 0);
        automatic.setOnCheckedChangeListener((b, on) -> setAutomatic(on));
        content.addView(automatic);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        notes = new Button(this, null, android.R.attr.borderlessButtonStyle);
        notes.setAllCaps(false);
        notes.setTextColor(accent);
        primary = new Button(this, null, android.R.attr.borderlessButtonStyle);
        primary.setAllCaps(false);
        primary.setTextColor(attrColor(android.R.attr.colorBackground));
        primary.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(accent);
        pill.setCornerRadius(dp(20));
        primary.setBackground(pill);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(16), dp(12), dp(24), dp(16));
        buttons.addView(notes);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMarginStart(dp(8));
        buttons.addView(primary, lp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buttons);
        setContentView(root);

        engine = new UpdateEngine();
        UpdateEngineCallback callback = new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int s, float percent) {
                engineStatus = s;
                enginePercent = percent;
                showEngine(s, percent);
            }

            @Override
            public void onPayloadApplicationComplete(int error) {
                if (error == UpdateService.USER_CANCELED) {
                    engineStatus = UpdateStatusConstants.IDLE;
                    check();
                } else if (error != 0) {
                    engineStatus = UpdateStatusConstants.IDLE;
                    show("Couldn't install the update", details()
                            + "\n\nupdate_engine reported error " + error + ". Nothing changes at the next restart.",
                            -1, "Try again", v -> check());
                }
            }
        };
        // bind() reports the current status first; check only when update_engine is idle.
        UpdateService.ENGINE.execute(() -> {
            engine.bind(callback, ui);
            ui.post(() -> { if (engineStatus == UpdateStatusConstants.IDLE) check(); });
        });
        UpdateCheckJob.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pause and Resume in the notification do not reach this activity's callback.
        showEngine(engineStatus, enginePercent);
        automatic.setChecked(UpdateCheckJob.automatic(this));
    }

    /** Shares Developer options > Automatic system updates (ota_disable_automatic_update). */
    void setAutomatic(boolean on) {
        if (on == UpdateCheckJob.automatic(this)) return;
        Settings.Global.putInt(getContentResolver(), "ota_disable_automatic_update", on ? 0 : 1);
        if (!on) {
            getSystemService(JobScheduler.class).cancel(UpdateCheckJob.DOWNLOAD);
        } else if (ota != null && UpdateCheckJob.installable(ota)) {
            UpdateCheckJob.scheduleDownload(this);
        }
    }

    @Override
    protected void onDestroy() {
        UpdateService.ENGINE.execute(engine::unbind);
        super.onDestroy();
    }

    void showEngine(int s, float percent) {
        switch (s) {
            case UpdateStatusConstants.UPDATE_AVAILABLE:
            case UpdateStatusConstants.DOWNLOADING:
            case UpdateStatusConstants.VERIFYING:
            case UpdateStatusConstants.FINALIZING:
                if (!serviceStarted) {
                    serviceStarted = true;
                    startForegroundService(new Intent(this, UpdateService.class));
                }
                break;
            default:
                break;
        }
        switch (s) {
            case UpdateStatusConstants.DOWNLOADING:
                if (prefs.getBoolean("paused", false)) {
                    show("System update paused", details(), percent,
                            "Resume", v -> pause(false), "Cancel", v -> cancel());
                } else {
                    show("Downloading and installing system update", details(), percent,
                            "Pause", v -> pause(true), "Cancel", v -> cancel());
                }
                break;
            case UpdateStatusConstants.VERIFYING:
            case UpdateStatusConstants.FINALIZING:
                show("Installing system update", details(), percent, "Cancel", v -> cancel());
                break;
            case UpdateStatusConstants.UPDATED_NEED_REBOOT:
                show("Restart to finish installing", details()
                        + "\n\nThe update is installed on the other slot. Your data is kept.",
                        -1, "Restart now", v -> getSystemService(PowerManager.class).reboot(null));
                break;
            default:
                break;
        }
    }

    void cancel() {
        UpdateService.ENGINE.execute(engine::cancel);
    }

    void pause(boolean pause) {
        UpdateService.pause(this, engine, pause);
        startService(new Intent(this, UpdateService.class));
        showEngine(engineStatus, enginePercent);
    }

    void check() {
        show("Checking for update…", details(), 0, null, null);
        bar.setIndeterminate(true);
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(fetch(OTA_JSON));
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
                ui.post(() -> offer(j));
            } catch (Exception e) {
                ui.post(() -> show("Couldn't check for update",
                        details() + "\n\n" + e.getMessage(), -1, "Try again", v -> check()));
            }
        }).start();
    }

    void offer(JSONObject j) {
        if (engineStatus != UpdateStatusConstants.IDLE) return;
        ota = j;
        String tag = j.optString("tag");
        JSONObject p = j.optJSONObject("payload");
        int need = j.optInt("min_vendor_layout", 1);
        int layout = layout();
        prefs.edit().putString("offered", tag).apply();
        if (tag.equals(release())) {
            show("Your system is up to date", details(), -1, "Check for update", v -> check());
        } else if (layout < need) {
            show("Update needs a computer", details() + "\n\n" + tag
                    + " needs vendor layout " + need + ", this phone has " + layout + "."
                    + (layout < 3 ? " Slot b, where updates are installed, may still have the stock"
                            + " bootloader and firmware; after an update there the lock screen"
                            + " can reject your PIN." : "")
                    + " Update from a computer once (install guide, "
                    + "\"Update to a newer release\"); this also prepares slot b, and later"
                    + " updates install here again.",
                    -1, "Check for update", v -> check());
        } else {
            show("System update available", details() + "\n\nYour device will be updated to " + tag
                    + ". It installs while you use the phone, and your data is kept."
                    + (UpdateCheckJob.automatic(this) ? " On Wi-Fi it downloads automatically." : "")
                    + "\n\nSize: "
                    + Formatter.formatFileSize(this, p.optLong("size")),
                    -1, "Download & install", v -> confirm(p));
        }
    }

    void confirm(JSONObject p) {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && battery.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
                && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) {
            show("Battery too low", details() + "\n\nYour battery is at "
                    + battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100
                            / battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    + "%. Connect your charger to install the update.",
                    -1, "Try again", v -> confirm(p));
            return;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (nc == null || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            install(p);
            return;
        }
        boolean cellular = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        boolean roaming = cellular && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        new AlertDialog.Builder(this)
                .setTitle(roaming ? "Download using roaming data?"
                        : cellular ? "Download using mobile data?" : "Download on a metered network?")
                .setMessage("This update is " + Formatter.formatFileSize(this, p.optLong("size")) + ". "
                        + (roaming ? "Your carrier may charge roaming fees for the data."
                                : cellular ? "Your carrier may charge for the data."
                                : "This network is metered, so the data may be limited or charged.")
                        + " To avoid this, connect to an unmetered Wi-Fi network.")
                .setPositiveButton("Download", (d, w) -> install(p))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void install(JSONObject p) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        apply(this, engine, p, 0, false);
        serviceStarted = true;
        show("Downloading and installing system update", details(), 0, null, null);
    }

    /** A non-zero network handle binds the download to that network (NETWORK_ID). */
    static void apply(Context c, UpdateEngine e, JSONObject p, long network, boolean auto) {
        String headers = p.optString("headers").trim();
        if (network != 0) headers += "\nNETWORK_ID=" + network;
        c.getSharedPreferences("updater", MODE_PRIVATE).edit()
                .remove("paused").putBoolean("auto", auto).commit();
        String[] h = headers.split("\n");
        UpdateService.ENGINE.execute(() -> {
            e.resetStatus();
            e.applyPayload(p.optString("url"), 0, p.optLong("size"), h);
        });
        try {
            c.startForegroundService(new Intent(c, UpdateService.class));
        } catch (IllegalStateException ex) {
            // Background start refused; the activity starts the service when opened.
        }
    }

    void show(String t, String b, float percent, String button, View.OnClickListener l) {
        show(t, b, percent, button, l, null, null);
    }

    /**
     * percent -1 hides the progress bar; a null button text hides the primary button; a null
     * secondary shows Release notes when an ota.json has been read.
     */
    void show(String t, String b, float percent, String button, View.OnClickListener l,
            String secondary, View.OnClickListener sl) {
        title.setText(t);
        body.setText(b);
        boolean busy = percent >= 0;
        bar.setVisibility(busy ? View.VISIBLE : View.GONE);
        bar.setIndeterminate(false);
        progressText.setVisibility(busy && percent > 0 ? View.VISIBLE : View.GONE);
        if (busy) {
            bar.setProgress((int) (percent * 1000));
            progressText.setText(String.format("%.0f%%", percent * 100));
        }
        if (secondary == null && ota != null) {
            secondary = "Release notes";
            sl = v -> startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(RELEASES + ota.optString("tag"))));
        }
        notes.setVisibility(secondary != null ? View.VISIBLE : View.GONE);
        notes.setText(secondary);
        notes.setOnClickListener(sl);
        primary.setVisibility(button != null ? View.VISIBLE : View.GONE);
        primary.setText(button);
        primary.setOnClickListener(l);
    }

    String details() {
        long checked = prefs.getLong("checked", 0);
        return "Installed release: " + (release().isEmpty() ? "unknown" : release())
                + "\nAndroid version: " + Build.VERSION.RELEASE
                + "\nAndroid security update: " + Build.VERSION.SECURITY_PATCH
                + (checked == 0 ? "" : "\nLast checked for update: "
                        + (System.currentTimeMillis() - checked < DateUtils.MINUTE_IN_MILLIS ? "just now"
                                : DateUtils.getRelativeTimeSpanString(checked)));
    }

    static String release() { return SystemProperties.get("ro.xp8.release", ""); }

    static int layout() { return parseInt(SystemProperties.get("ro.vendor.xp8.layout", ""), 1); }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            for (String l; (l = r.readLine()) != null; ) b.append(l).append('\n');
            return b.toString();
        } finally {
            c.disconnect();
        }
    }

    static int parseInt(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    TextView text(int sp, int color) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    int attrColor(int attr) {
        TypedArray a = obtainStyledAttributes(new int[] {attr});
        int c = a.getColor(0, 0);
        a.recycle();
        return c;
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
