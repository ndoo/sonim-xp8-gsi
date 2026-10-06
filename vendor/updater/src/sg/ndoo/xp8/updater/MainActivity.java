// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.updater;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.os.UpdateEngine.UpdateStatusConstants;
import android.os.UpdateEngineCallback;
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
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Settings > System > System update, laid out like the Pixel screen. Checks the latest
 * release's ota.json and installs its payload with update_engine, which streams it from
 * GitHub into the inactive slot. ro.xp8.release (system) names the installed release;
 * ro.vendor.xp8.layout (vendor) must reach the payload's min_vendor_layout, else the
 * release needs a PC update.
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
    JSONObject ota;
    int engineStatus = UpdateStatusConstants.IDLE;

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
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        notes = new Button(this, null, android.R.attr.borderlessButtonStyle);
        notes.setText("Release notes");
        notes.setAllCaps(false);
        notes.setTextColor(accent);
        notes.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(RELEASES + ota.optString("tag")))));
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
        engine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int s, float percent) {
                engineStatus = s;
                showEngine(s, percent);
            }

            @Override
            public void onPayloadApplicationComplete(int error) {
                if (error != 0) {
                    engineStatus = UpdateStatusConstants.IDLE;
                    show("Couldn't install the update", details()
                            + "\n\nupdate_engine reported error " + error + ". Nothing changes at the next restart.",
                            -1, "Try again", v -> check());
                }
            }
        }, ui);
        // bind() reports the current status first; check only when update_engine is idle.
        ui.post(() -> { if (engineStatus == UpdateStatusConstants.IDLE) check(); });
    }

    void showEngine(int s, float percent) {
        switch (s) {
            case UpdateStatusConstants.DOWNLOADING:
                show("Downloading and installing system update", details(), percent,
                        "Cancel", v -> engine.cancel());
                break;
            case UpdateStatusConstants.VERIFYING:
            case UpdateStatusConstants.FINALIZING:
                show("Installing system update", details(), percent, "Cancel", v -> engine.cancel());
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
        int layout = parseInt(prop("/vendor/build.prop", "ro.vendor.xp8.layout"), 1);
        if (tag.equals(release())) {
            show("Your system is up to date", details(), -1, "Check for update", v -> check());
        } else if (layout < need) {
            show("Update needs a computer", details() + "\n\n" + tag
                    + " needs a newer vendor image. Update from a computer once (install guide, "
                    + "\"Update to a newer release\"); later updates install here again.",
                    -1, "Check for update", v -> check());
        } else {
            show("System update available", details() + "\n\nYour device will be updated to " + tag
                    + ". It installs while you use the phone, and your data is kept.\n\nSize: "
                    + Formatter.formatFileSize(this, p.optLong("size")),
                    -1, "Download & install", v -> install(p));
        }
    }

    void install(JSONObject p) {
        engine.resetStatus();
        engine.applyPayload(p.optString("url"), 0, p.optLong("size"),
                p.optString("headers").trim().split("\n"));
        show("Downloading and installing system update", details(), 0, null, null);
    }

    /** percent -1 hides the progress bar; a null button text hides the primary button. */
    void show(String t, String b, float percent, String button, View.OnClickListener l) {
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
        notes.setVisibility(ota != null ? View.VISIBLE : View.GONE);
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

    static String release() { return prop("/system/build.prop", "ro.xp8.release"); }

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

    static String prop(String file, String key) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { p.load(in); } catch (Exception e) { }
        return p.getProperty(key, "");
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
