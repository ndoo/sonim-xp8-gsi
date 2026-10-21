// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.app.Activity;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;

/**
 * Settings > Battery > Battery protection: sets the charging policy of the health HAL
 * (vendor/health/xp8-health.c) through IHealth.setChargingPolicy, which the HAL keeps
 * across reboots. Stock Settings shows the resulting charging state on the Battery page.
 */
public class BatteryProtectionActivity extends Activity {
    static final String SERVICE = "android.hardware.health.IHealth/default";
    static final String DESCRIPTOR = "android.hardware.health.IHealth";
    static final int TX_SET_POLICY = 13, TX_GET_POLICY = 14;
    static final int TX_CHARGE_NOW = 1000, TX_PROTECTION_STATUS = 1001;
    static final int[] POLICIES = {3, 2, 1}; // ADAPTIVE, LONG_LIFE, DEFAULT
    static final String[] TITLES = {"Automatic", "Limit to 80%", "Off"};
    static final String[] SUMMARIES = {
        "Holds at 80% overnight and charges to full before you usually unplug",
        "Stops charging at 80% and resumes at 75%",
        "Always charges to 100%",
    };

    PreferenceScreen screen;

    // DeviceDefault's text colours have no disabled state, so a disabled row dims only its
    // checkbox; apply the theme's disabledAlpha to the whole row.
    static final class Choice extends CheckBoxPreference {
        final float disabledAlpha;

        Choice(Activity a) {
            super(a);
            TypedArray t = a.obtainStyledAttributes(new int[] {android.R.attr.disabledAlpha});
            disabledAlpha = t.getFloat(0, 0.5f);
            t.recycle();
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAlpha(isEnabled() ? 1f : disabledAlpha);
        }
    }

    public static class ProtectionFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle saved) {
            super.onCreate(saved);
            BatteryProtectionActivity a = (BatteryProtectionActivity) getActivity();
            a.screen = getPreferenceManager().createPreferenceScreen(a);
            setPreferenceScreen(a.screen);
            a.render();
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        if (Build.VERSION.SDK_INT >= 30)                        // edge-to-edge from Android 15 on
            findViewById(android.R.id.content).setOnApplyWindowInsetsListener((v, in) -> {
                Insets b = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return WindowInsets.CONSUMED;
            });
        if (saved == null)
            getFragmentManager().beginTransaction().replace(android.R.id.content, new ProtectionFragment()).commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    static IBinder hal() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    // Returns the policy, or -1 when the HAL is not reachable.
    static int transact(int code, int arg) {
        IBinder b = hal();
        if (b == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (code == TX_SET_POLICY) data.writeInt(arg);
            b.transact(code, data, reply, 0);
            reply.readException();
            return code == TX_GET_POLICY ? reply.readInt() : arg;
        } catch (Exception e) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // The HAL's hold: {reason, full-by time in s}; reason 1 heat, 2 overnight, 4 limit to 80 %.
    static long[] status() {
        IBinder b = hal();
        if (b == null) return new long[] {0, 0};
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(TX_PROTECTION_STATUS, data, reply, 0);
            reply.readException();
            return new long[] {reply.readInt(), reply.readLong()};
        } catch (Exception e) {
            return new long[] {0, 0};
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    void render() {
        screen.removeAll();
        int current = transact(TX_GET_POLICY, 0);
        long[] hold = status();
        if (hold[0] == 1 || hold[0] == 2 || hold[0] == 4) {
            Preference st = new Preference(this);
            st.setSelectable(false);
            st.setTitle(hold[0] == 1 ? "Charging paused at 80%"
                    : hold[0] == 2 ? "Charging on hold to protect battery" : "Charging limited to 80%");
            st.setSummary(hold[0] == 1 ? "The battery is hot. Charging continues when it cools down."
                    : hold[0] == 2 ? "Full by " + android.text.format.DateFormat.getTimeFormat(this)
                            .format(new java.util.Date(hold[1] * 1000))
                    : "Charge to full now lifts the limit until the charger is unplugged or you choose a mode.");
            screen.addPreference(st);
            if (hold[0] != 1) {
                Preference now = new Preference(this);
                now.setTitle("Charge to full now");
                now.setOnPreferenceClickListener(pref -> {
                    transact(TX_CHARGE_NOW, 0);
                    render();
                    return true;
                });
                screen.addPreference(now);
            }
        }
        PreferenceCategory cat = new PreferenceCategory(this);
        screen.addPreference(cat);
        for (int i = 0; i < POLICIES.length; i++) {
            final int policy = POLICIES[i];
            CheckBoxPreference p = new Choice(this);
            p.setTitle(TITLES[i]);
            p.setSummary(SUMMARIES[i]);
            p.setPersistent(false);
            p.setChecked(policy == current);
            p.setEnabled(current > 0);
            p.setOnPreferenceChangeListener((pref, value) -> {
                transact(TX_SET_POLICY, policy);
                render();
                return false;
            });
            cat.addPreference(p);
        }
        Preference note = new Preference(this);
        note.setSelectable(false);
        note.setSummary(current > 0
                ? "While charging is held, the phone runs on its battery and stays connected to the"
                    + " charger. Android shows it as plugged in, not charging."
                : "The battery protection service is not running.");
        screen.addPreference(note);
    }
}
