// SPDX-FileCopyrightText: 2026 no0406
// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT
//
// XP8 daemon: does what the XP8 side keys did on stock, where Sonim's framework
// (PhoneWindowManager and SonimSdkPolicy) handled them, and drives the battery
// protection notification of XP8 Buttons.
//
// Reads the gpio-keys input device directly: PTT (scancode 149), SOS (148) and
// the camera key (766), and the headset PTT button (249 on PTT-Headset-Button).
// Each key either
//   - forwards press and release to push-to-talk apps as stock did, which Zello
//     and others receive also in the background, or
//   - runs a short-press and a long-press action. A long press is Android's
//     touch & hold delay (Settings.Secure long_press_timeout, set under
//     Accessibility), as for the touch screen.
// As on stock, each push-to-talk app gets one PTT action: Sonim's
// PTT_KEY_DOWN/UP, Kodiak's PTT_BUTTON or MCPTT's CRITICAL_COMMUNICATION_CONTROL_KEY,
// the last of these it declares a receiver or service for, with the key event
// (stock keycode 228) as android.intent.extra.KEY_EVENT. SOS goes out as Sonim's
// SOS_KEY_DOWN/UP and Kodiak's KEYCODE_SOS.
// While a key forwards PTT to a Kodiak app, the stock audio HAL's PTT profile is on
// (ptt_call_state=on), as Sonim's com.kodiak.pttExtensions set it; other apps get none, as on stock.
// A PTT press turns the screen on, as stock's PhoneWindowManager.wakePTTKeyEvent did.
// As there, the hold time does not apply to a push-to-talk app in front of an unlocked, lit
// screen, and the headset PTT button answers or ends a phone call (KEYCODE_HEADSETHOOK).
//
// Like Sonim's AppKey policy, a key goes to one app: the one chosen in XP8 Buttons, or by
// default the app the policy picks. Apps claim a key with app_key_ptt_* (or app_key_sos_*)
// meta-data; the highest app_key_*_priority wins, then the earliest install (stock queries
// "priority DESC"); with no claimant, the earliest installed push-to-talk app. When that app
// changes (a new install, or the chosen app is uninstalled), or a key has no app, XP8 Buttons
// shows a toast.
//
// The XP8 Buttons app (vendor/keys/app) writes the choices to CONFIG; keys it has
// not set keep the defaults below. Started by vendor/xp8-gsi.rc as root.
//
// usage: app_process -cp xp8-daemon.dex /system/etc/xp8 Xp8Daemon [daemon | set k=v | get k]

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Xp8Daemon {
    static final String CONFIG = "/data/data/sg.ndoo.xp8.buttons/files/keys.properties";
    static final String SONIM = "com.sonim.intent.action.";
    static final String SONIM_PTT = SONIM + "PTT_KEY_DOWN", SONIM_SOS = SONIM + "SOS_KEY_DOWN";
    static final String KODIAK_PTT = "com.kodiak.intent.action.PTT_BUTTON";
    static final String MCPTT_PTT = "com.mcx.intent.action.CRITICAL_COMMUNICATION_CONTROL_KEY";
    static final String KODIAK_SOS = "com.kodiak.intent.action.KEYCODE_SOS";
    static final int KEYCODE_SOS = 227, KEYCODE_PTT = 228;    // Sonim's KeyEvent values
    static final int FLAGS = Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND;
    static final int USER_ALL = -1;
    static final String PTT_ON = "ptt_call_state=on", PTT_OFF = "ptt_call_state=off";
    static final long LONG_PRESS_DEFAULT = 400;    // ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT

    static final ExecutorService SEND = Executors.newSingleThreadExecutor();
    static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    static volatile Map<String, Target> pttTargets = Collections.emptyMap();
    static volatile Map<String, List<String>> sosTargets = Collections.emptyMap();
    static final String APP = "sg.ndoo.xp8.buttons", NOTICE = APP + ".NOTICE", AUTO = "auto";
    static volatile String pttAuto, sosAuto;      // the app "auto" resolves to; null = none
    static boolean autoKnown;                      // pttAuto and sosAuto have been computed once
    static long noticedAt;                         // last "no app" toast, so a held key shows one
    static Object pm;
    static Method getPackageInfo;
    static boolean longFlags;
    static String targetsShown;
    static Object am, power, atm, wm, audio;
    static Method broadcastIntent, startService, setParameters, wakeUp;
    static volatile long releasedAt;       // last PTT release; the HAL profile goes off 300 ms later
    static volatile long longPressMs = LONG_PRESS_DEFAULT;
    static volatile boolean readLongPress; // re-read long_press_timeout after a key release
    static Properties config;
    static long configTime = -1;

    public static void main(String[] a) throws Exception {
        Class<?> as = Class.forName("android.media.AudioSystem");
        setParameters = as.getMethod("setParameters", String.class);
        if (a.length == 2 && a[0].equals("set")) {
            System.out.println(setParameters.invoke(null, a[1]));
        } else if (a.length == 2 && a[0].equals("get")) {
            System.out.println(as.getMethod("getParameters", String.class).invoke(null, a[1]));
        } else if (a.length == 0 || a[0].equals("daemon")) {
            daemon();
        } else {
            System.err.println("usage: Xp8Daemon [daemon | set k=v | get k]");
        }
    }

    static void log(String m) {
        android.util.Log.i("xp8-daemon", m);
    }

    // Scancode -> key name in CONFIG.
    static String keyName(int code) {
        return code == 149 || code == 249 ? "ptt" : code == 148 ? "sos" : code == 766 ? "camera" : null;
    }

    static Properties defaults() {
        Properties d = new Properties();
        d.setProperty("ptt.forward", AUTO);         // AUTO, or a package; "*" from older versions means AUTO
        d.setProperty("sos.forward", AUTO);
        d.setProperty("camera.forward", "");
        d.setProperty("camera.short", "camera");
        return d;
    }

    // CONFIG, reloaded when it changes.
    static Properties config() {
        File f = new File(CONFIG);
        long t = f.exists() ? f.lastModified() : 0;
        if (t != configTime) {
            Properties c = new Properties(defaults());
            if (t != 0) {
                try (FileInputStream in = new FileInputStream(f)) {
                    c.load(in);
                } catch (Exception e) {
                    log("config read failed: " + e);
                }
            }
            Map<String, String> shown = new TreeMap<>();
            for (String k : c.stringPropertyNames()) shown.put(k, c.getProperty(k));
            log("config " + (t != 0 ? "loaded" : "defaults") + ": " + shown);
            config = c;
            configTime = t;
        }
        return config;
    }

    // Android's touch & hold delay; unset (null) means the framework default.
    static void longPressTimeout() {
        String v = exec("settings", "get", "secure", "long_press_timeout").trim();
        long ms = LONG_PRESS_DEFAULT;
        try {
            ms = Long.parseLong(v);
        } catch (NumberFormatException e) {
            if (!v.equals("null")) log("bad long_press_timeout: " + v);
        }
        if (ms != longPressMs) log("long press " + ms + " ms");
        longPressMs = ms;
    }

    static String exec(String... cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            for (String l; (l = r.readLine()) != null; ) out.append(l).append('\n');
            p.waitFor();
        } catch (Exception e) {
            log(String.join(" ", cmd) + " failed: " + e);
        }
        return out.toString();
    }

    // The PTT action a package gets, and its service class when it takes the action as a service.
    static final class Target {
        final String action, service;

        Target(String action, String service) {
            this.action = action;
            this.service = service;
        }

        @Override
        public String toString() {
            return action.substring(action.lastIndexOf('.') + 1) + (service != null ? " service " + service : "");
        }
    }

    // Receiver or service components for ACTION, as package/class.
    static List<ComponentName> components(String query, String action) {
        List<ComponentName> out = new ArrayList<>();
        for (String l : exec("cmd", "package", query, "--components", "-a", action).split("\n")) {
            ComponentName c = ComponentName.unflattenFromString(l.trim());
            if (c != null) out.add(c);
        }
        return out;
    }

    // Push-to-talk apps and their actions, as SonimSdkPolicy built them; refreshed every minute.
    static void refreshTargets() {
        Map<String, Target> ptt = new LinkedHashMap<>();
        for (String a : new String[] {SONIM_PTT, KODIAK_PTT, MCPTT_PTT}) {
            for (ComponentName c : components("query-receivers", a)) ptt.put(c.getPackageName(), new Target(a, null));
            for (ComponentName c : components("query-services", a))
                ptt.put(c.getPackageName(), new Target(a, c.getClassName()));
        }
        if (ptt.containsKey("com.slacorp.eptt.android"))      // stock ptt_override_packages
            ptt.put("com.slacorp.eptt.android", new Target(SONIM_PTT, null));
        Map<String, List<String>> sos = new HashMap<>();
        for (String a : new String[] {SONIM_SOS, KODIAK_SOS}) {
            List<String> pkgs = new ArrayList<>();
            for (ComponentName c : components("query-receivers", a))
                if (!pkgs.contains(c.getPackageName())) pkgs.add(c.getPackageName());
            sos.put(a, pkgs);
        }
        String shown = "ptt " + ptt + ", sos " + sos;
        if (!shown.equals(targetsShown)) log("targets: " + shown);
        targetsShown = shown;
        pttTargets = ptt;
        sosTargets = sos;
        List<String> sosPkgs = new ArrayList<>();
        for (List<String> l : sos.values()) for (String p : l) if (!sosPkgs.contains(p)) sosPkgs.add(p);
        String ptta = pick(ptt.keySet(), "ptt"), sosa = pick(sosPkgs, "sos");
        if (autoKnown) {
            if (!Objects.equals(ptta, pttAuto)) autoChanged("ptt", ptta);
            if (!Objects.equals(sosa, sosAuto)) autoChanged("sos", sosa);
        }
        gone("ptt", ptt.keySet(), ptta);
        gone("sos", sosPkgs, sosa);
        if (!Objects.equals(ptta, pttAuto) || !Objects.equals(sosa, sosAuto))
            log("auto: ptt " + ptta + ", sos " + sosa);
        pttAuto = ptta;
        sosAuto = sosa;
        autoKnown = true;
    }

    static void initPackageManager() throws Exception {
        pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
        try {
            getPackageInfo = ipm.getMethod("getPackageInfo", String.class, long.class, int.class);   // Android 13 and later
            longFlags = true;
        } catch (NoSuchMethodException e) {
            getPackageInfo = ipm.getMethod("getPackageInfo", String.class, int.class, int.class);
        }
    }

    static int priority(Bundle m, String key) {
        Object o = m.get(key);
        try {
            return o instanceof Number ? ((Number) o).intValue() : o instanceof String ? Integer.parseInt((String) o) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // AppKey policy: claimants first, then the highest priority, then the earliest install.
    static String pick(Collection<String> pkgs, String key) {
        String best = null;
        boolean bestClaim = false;
        int bestPrio = 0;
        long bestTime = 0;
        for (String p : pkgs) {
            boolean claim = false;
            int prio = 0;
            long time = Long.MAX_VALUE;
            try {
                Object flags = longFlags ? (Object) (long) PackageManager.GET_META_DATA : (Object) PackageManager.GET_META_DATA;
                PackageInfo i = (PackageInfo) getPackageInfo.invoke(pm, p, flags, 0);
                if (i == null) continue;                        // not installed
                time = i.firstInstallTime;
                Bundle m = i.applicationInfo != null ? i.applicationInfo.metaData : null;
                if (m != null) {
                    claim = m.containsKey("app_key_" + key + "_intent") || m.containsKey("app_key_" + key + "_priority")
                            || m.containsKey("app_key_" + key + "_data");
                    prio = priority(m, "app_key_" + key + "_priority");
                }
            } catch (Exception e) {
                log("package info " + p + ": " + e);
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

    // The app a key goes to: its own choice while that app is installed, else what the policy picks.
    static String target(String name, String fwd) {
        if (fwd.isEmpty()) return null;
        boolean sos = name.equals("sos");
        String auto = sos ? sosAuto : pttAuto;
        if (fwd.equals("*") || fwd.equals(AUTO)) return auto;
        boolean installed = sos ? sosTargets.values().stream().anyMatch(l -> l.contains(fwd)) : pttTargets.containsKey(fwd);
        return installed ? fwd : auto;
    }

    // XP8 Buttons shows the toast; PKG empty means no app.
    static void notice(String name, String pkg) {
        send(new Intent(NOTICE).setPackage(APP).addFlags(FLAGS).putExtra("key", name).putExtra("pkg", pkg == null ? "" : pkg));
    }

    static final Set<String> missing = new HashSet<>();    // keys whose chosen app is not installed

    // A key's chosen app was uninstalled: it falls back to the policy's pick, and says so once.
    static void gone(String name, Collection<String> installed, String auto) {
        String fwd = config().getProperty(name + ".forward", "");
        boolean explicit = !fwd.isEmpty() && !fwd.equals(AUTO) && !fwd.equals("*");
        if (explicit && !installed.contains(fwd)) {
            if (missing.add(name)) {
                log(name + " key app " + fwd + " is gone, now " + auto);
                notice(name, auto);
            }
        } else {
            missing.remove(name);
        }
    }

    // The policy picked another app. Only a key left on automatic follows it.
    static void autoChanged(String name, String pkg) {
        String fwd = config().getProperty(name + ".forward", "");
        if (fwd.equals(AUTO) || fwd.equals("*")) {
            log(name + " key app is now " + pkg);
            notice(name, pkg);
        }
    }

    static void initActivityManager() throws Exception {
        am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> iam = Class.forName("android.app.IActivityManager");
        Class<?> thread = Class.forName("android.app.IApplicationThread");
        broadcastIntent = iam.getMethod("broadcastIntent", thread, Intent.class, String.class,
                Class.forName("android.content.IIntentReceiver"), int.class, String.class, Bundle.class,
                String[].class, int.class, Bundle.class, boolean.class, boolean.class, int.class);
        startService = iam.getMethod("startService", thread, Intent.class, String.class, boolean.class,
                String.class, String.class, int.class);
    }

    static void initPowerManager() throws Exception {
        Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "power");
        power = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", android.os.IBinder.class)
                .invoke(null, b);
        wakeUp = power.getClass().getMethod("wakeUp", long.class, int.class, String.class, String.class);
    }

    static Object service(String name, String stub) throws Exception {
        Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stub + "$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
    }

    static void initState() {
        try {
            atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            wm = service("window", "android.view.IWindowManager");
            audio = service("audio", "android.media.IAudioService");
        } catch (Exception e) {
            log("no window or audio state: " + e);
        }
    }

    // The app in front, when the screen is on and unlocked; else null.
    static String frontApp() {
        try {
            if (!(boolean) power.getClass().getMethod("isInteractive").invoke(power)) return null;
            if ((boolean) wm.getClass().getMethod("isKeyguardLocked").invoke(wm)) return null;
            Object task = atm.getClass().getMethod("getFocusedRootTaskInfo").invoke(atm);
            ComponentName top = task == null ? null : (ComponentName) task.getClass().getField("topActivity").get(task);
            return top == null ? null : top.getPackageName();
        } catch (Exception e) {
            log("front app: " + e);
            return null;
        }
    }

    // A cellular call ringing or in progress (audio mode RINGTONE or IN_CALL).
    static boolean inCall() {
        try {
            int mode = (int) audio.getClass().getMethod("getMode").invoke(audio);
            return mode == 1 || mode == 2;
        } catch (Exception e) {
            log("audio mode: " + e);
            return false;
        }
    }

    // The kernel wake lock keeps the phone from suspending until the screen is on.
    static void wake() {
        try {
            Files.write(new File("/sys/power/wake_lock").toPath(), "xp8-daemon 2000000000".getBytes());
            wakeUp.invoke(power, SystemClock.uptimeMillis(), 6, "xp8-daemon:PTT", "android"); // WAKE_REASON_WAKE_KEY
        } catch (Exception e) {
            log("wake failed: " + e);
        }
    }

    // Through IActivityManager directly: no am process start, so no added latency.
    static void send(Intent it) {
        SEND.execute(() -> {
            try {
                if (it.getComponent() != null) {
                    ComponentName r = (ComponentName) startService.invoke(am, null, it, null, false, "com.android.shell", null, 0);
                    if (r == null || r.getPackageName().startsWith("!") || r.getPackageName().startsWith("?"))
                        log("service " + it.getComponent().flattenToShortString() + " not started: " + r);
                } else {
                    broadcastIntent.invoke(am, null, it, null, null, 0, null, null, null, -1, null, false, false, USER_ALL);
                }
            } catch (Exception e) {
                log((it.getComponent() != null ? "service " : "broadcast ") + it.getAction() + " failed: " + e);
            }
        });
    }

    // PKG is the one app the key goes to. As on stock, it gets the intent also while it is in front.
    static void ptt(boolean down, String pkg) {
        KeyEvent ev = new KeyEvent(down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, KEYCODE_PTT);
        Target t = pttTargets.getOrDefault(pkg, new Target(SONIM_PTT, null));
        String action = t.action.equals(SONIM_PTT) ? SONIM + (down ? "PTT_KEY_DOWN" : "PTT_KEY_UP") : t.action;
        Intent it = new Intent(action).putExtra(Intent.EXTRA_KEY_EVENT, ev);
        if (t.service != null) send(it.setComponent(new ComponentName(pkg, t.service)));
        else send(it.setPackage(pkg).addFlags(FLAGS));
    }

    static void sos(boolean down, String pkg, KeyEvent ev) {
        Map<String, List<String>> all = sosTargets;
        for (String a : new String[] {SONIM_SOS, KODIAK_SOS}) {
            if (!all.getOrDefault(a, Collections.emptyList()).contains(pkg)) continue;
            Intent it = a.equals(SONIM_SOS)
                    ? new Intent(SONIM + (down ? "SOS_KEY_DOWN" : "SOS_KEY_UP"))
                    : new Intent(KODIAK_SOS).putExtra(Intent.EXTRA_KEY_EVENT, ev);
            send(it.setPackage(pkg).addFlags(FLAGS));
        }
    }

    // debug.xp8.ptt_profile on|off overrides, for measurements.
    static boolean profile(String pkg) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                    .invoke(null, "debug.xp8.ptt_profile", "");
            if ("on".equals(v)) return true;
            if ("off".equals(v)) return false;
        } catch (Exception e) {
            log("debug.xp8.ptt_profile: " + e);
        }
        Target t = pttTargets.get(pkg);
        return t != null && KODIAK_PTT.equals(t.action);
    }

    static void hal(String kv) {
        try {
            setParameters.invoke(null, kv);
        } catch (Exception e) {
            log(kv + " failed: " + e);
        }
    }

    // ACTION: none | camera | torch | assist | playpause | dnd | call:<number> | launch:<package>/<activity>
    static void run(String action) {
        if (action == null || action.isEmpty() || action.equals("none")) return;
        log("action " + action);
        if (action.equals("torch")) {                           // the app toggles it through CameraManager
            send(new Intent("sg.ndoo.xp8.buttons.TORCH").setPackage("sg.ndoo.xp8.buttons").addFlags(FLAGS));
            return;
        }
        SEND.execute(() -> {
            if (action.equals("camera"))
                exec("am", "start", "-a", "android.media.action.STILL_IMAGE_CAMERA");
            else if (action.equals("assist"))
                exec("am", "start", "-a", "android.intent.action.VOICE_COMMAND", "-f", "0x10000000");
            else if (action.equals("playpause"))
                exec("input", "keyevent", "85");                 // KEYCODE_MEDIA_PLAY_PAUSE
            else if (action.equals("dnd"))
                exec("cmd", "notification", "set_dnd",
                        exec("settings", "get", "global", "zen_mode").trim().equals("0") ? "priority" : "off");
            else if (action.startsWith("call:"))
                exec("am", "start", "-a", "android.intent.action.CALL", "-d", "tel:" + action.substring(5));
            else if (action.startsWith("launch:"))
                exec("am", "start", "-n", action.substring(7), "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER");
        });
    }

    static final class Key {
        final String name;
        final int code;
        boolean down, longDone, sentDown, hook;
        long downTime;
        String forward, front;                                   // set while forwarding a press
        ScheduledFuture<?> longPress, holdTimer;

        Key(String name, int code) {
            this.name = name;
            this.code = code;
        }

        // SOS keeps the key event's scancode, as stock passed it on.
        KeyEvent event(int action) {
            return new KeyEvent(downTime, SystemClock.uptimeMillis(), action, KEYCODE_SOS, 0, 0, -1, code);
        }
    }

    static String inputDevice(String wanted) throws Exception {
        File[] devs = new File("/sys/class/input").listFiles((d, n) -> n.startsWith("event"));
        if (devs != null)
            for (File d : devs) {
                File name = new File(d, "device/name");
                if (name.exists() && new String(Files.readAllBytes(name.toPath())).trim().equals(wanted))
                    return "/dev/input/" + d.getName();
            }
        return null;
    }

    static final Map<Integer, Key> KEYS = new HashMap<>();

    static synchronized void onKey(int code, int value) {
        String name = keyName(code);
        if (name == null || value == 2) return;                 // ignore autorepeat
        Key k = KEYS.computeIfAbsent(code, x -> new Key(name, code));
        Properties c = config();
        if (value == 1 && !k.down) {
            k.down = true;
            String front = name.equals("ptt") ? frontApp() : null;   // before the wake below
            if (name.equals("ptt")) wake();
            k.downTime = SystemClock.uptimeMillis();
            if (code == 249 && inCall()) {
                k.hook = true;
                return;
            }
            String fwd = c.getProperty(name + ".forward", "");
            String app = target(name, fwd);
            if (!fwd.isEmpty() && app == null) {
                k.forward = "";                                  // nothing to send; the release does nothing either
                k.sentDown = false;
                long now = SystemClock.uptimeMillis();
                if (now - noticedAt > 1000) {
                    noticedAt = now;
                    notice(name, null);
                }
                log(name + " down: no app");
            } else if (!fwd.isEmpty()) {
                // KEY.hold: hold this long (ms) before the press is forwarded, as Sonim's
                // "Press and Hold timer to engage PTT Key"; a shorter press sends nothing.
                k.forward = app;
                k.sentDown = false;
                long hold = 0;
                try {
                    hold = Long.parseLong(c.getProperty(name + ".hold", "0"));
                } catch (NumberFormatException e) {
                    log("bad " + name + ".hold");
                }
                if (front != null && front.equals(app)) hold = 0;   // stock: no hold with the app in front
                k.front = front;
                Runnable press = () -> {
                    if (name.equals("sos")) {
                        sos(true, app, k.event(KeyEvent.ACTION_DOWN));
                    } else {
                        releasedAt = 0;
                        if (profile(app)) hal(PTT_ON);
                        ptt(true, app);
                    }
                    k.sentDown = true;
                    log(name + " down -> " + app + (k.front != null ? ", " + k.front + " in front" : ""));
                };
                if (hold > 0) k.holdTimer = TIMER.schedule(press, hold, TimeUnit.MILLISECONDS);
                else press.run();
            } else {
                k.longDone = false;
                String longAction = c.getProperty(name + ".long", "none");
                String shortAction = c.getProperty(name + ".short", "none");
                if (!name.equals("camera") && shortAction.startsWith("launch:")
                        && (longAction.isEmpty() || longAction.equals("none"))) {
                    // Stock opens an app that is not a push-to-talk app when the key is pressed,
                    // after the hold time unless that app is in front already.
                    k.longDone = true;
                    long hold = 0;
                    try {
                        hold = Long.parseLong(c.getProperty(name + ".hold", "0"));
                    } catch (NumberFormatException e) {
                        log("bad " + name + ".hold");
                    }
                    String pkg = shortAction.substring(7, Math.max(7, shortAction.indexOf('/')));
                    if (pkg.equals(front)) hold = 0;
                    if (hold > 0) k.holdTimer = TIMER.schedule(() -> run(shortAction), hold, TimeUnit.MILLISECONDS);
                    else run(shortAction);
                }
                if (!longAction.isEmpty() && !longAction.equals("none"))
                    k.longPress = TIMER.schedule(() -> {
                        k.longDone = true;
                        run(longAction);
                    }, longPressMs, TimeUnit.MILLISECONDS);
            }
        } else if (value == 0 && k.down) {
            k.down = false;
            readLongPress = true;
            if (k.hook) {
                k.hook = false;
                log("headset ptt in a call -> headsethook");
                SEND.execute(() -> exec("input", "keyevent", "KEYCODE_HEADSETHOOK"));
                return;
            }
            if (k.forward != null) {
                if (k.holdTimer != null) k.holdTimer.cancel(false);
                k.holdTimer = null;
                if (k.sentDown) {
                    if (name.equals("sos")) {
                        sos(false, k.forward, k.event(KeyEvent.ACTION_UP));
                    } else {
                        ptt(false, k.forward);
                        releasedAt = System.currentTimeMillis();
                    }
                    log(name + " up -> " + k.forward);
                } else {
                    log(name + " released before the hold timer; nothing sent");
                }
                k.forward = null;
            } else {
                if (k.longPress != null) k.longPress.cancel(false);
                k.longPress = null;
                if (k.holdTimer != null) k.holdTimer.cancel(false);     // released before the hold time
                k.holdTimer = null;
                if (!k.longDone) run(c.getProperty(name + ".short", "none"));
            }
        }
    }

    // Battery protection: the health HAL (vendor/health/xp8-health.c) calls back on every battery
    // change; when its hold starts, changes or ends, XP8 Buttons shows or clears its notification.
    static final String HEALTH = "android.hardware.health.IHealth/default";
    static final String HEALTH_DESCRIPTOR = "android.hardware.health.IHealth";
    static final int TX_REGISTER_CALLBACK = 1, TX_PROTECTION_STATUS = 1001;
    static final String BATTERY_HOLD = APP + ".BATTERY_HOLD";
    static volatile IBinder health;
    static String holdShown = "";

    static final Binder HEALTH_CALLBACK = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code != 1) return super.onTransact(code, data, reply, flags);   // healthInfoChanged
            SEND.execute(Xp8Daemon::checkHold);
            return true;
        }
    };

    static void watchBattery() {
        HEALTH_CALLBACK.attachInterface(null, "android.hardware.health.IHealthInfoCallback");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                            .getMethod("checkService", String.class).invoke(null, HEALTH);
                    if (b != null && b != health) {
                        Parcel d = Parcel.obtain(), r = Parcel.obtain();
                        try {
                            d.writeInterfaceToken(HEALTH_DESCRIPTOR);
                            d.writeStrongBinder(HEALTH_CALLBACK);
                            b.transact(TX_REGISTER_CALLBACK, d, r, 0);
                            r.readException();
                        } finally {
                            d.recycle();
                            r.recycle();
                        }
                        health = b;
                        log("battery protection: watching the health HAL");
                    }
                } catch (Exception e) {
                    health = null;
                    log("battery protection: " + e);
                }
                try {
                    Thread.sleep(health != null && health.isBinderAlive() ? 60000 : 10000);
                } catch (InterruptedException e) {
                    return;
                }
                if (health != null && !health.isBinderAlive()) health = null;
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // HAL reason: 0 none, 1 heat, 2 overnight, 3 at 100 % (no notice), 4 limit to 80 %.
    static void checkHold() {
        IBinder b = health;
        if (b == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(HEALTH_DESCRIPTOR);
            b.transact(TX_PROTECTION_STATUS, d, r, 0);
            r.readException();
            int reason = r.readInt();
            long fullBy = r.readLong();
            if (reason == 3) reason = 0;
            String shown = reason + ":" + fullBy;
            if (shown.equals(holdShown)) return;
            holdShown = shown;
            send(new Intent(BATTERY_HOLD).setPackage(APP).addFlags(FLAGS)
                    .putExtra("reason", reason).putExtra("fullBy", fullBy));
        } catch (Exception e) {
            log("battery protection status: " + e);
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    // struct input_event on arm64: timeval (16 bytes), type (2), code (2), value (4).
    static void read(String dev) throws Exception {
        DataInputStream in = new DataInputStream(new FileInputStream(dev));
        byte[] ev = new byte[24];
        ByteBuffer b = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
        while (true) {
            in.readFully(ev);
            if (b.getShort(16) == 1) onKey(b.getShort(18), b.getInt(20));   // EV_KEY
        }
    }

    static void daemon() throws Exception {
        String keys = inputDevice("gpio-keys");
        if (keys == null) throw new IllegalStateException("no gpio-keys input device");
        String headset = inputDevice("PTT-Headset-Button");
        initActivityManager();
        watchBattery();
        try {
            initPowerManager();
        } catch (Exception e) {
            log("no power manager: " + e);
        }
        initState();
        try {
            initPackageManager();
        } catch (Exception e) {
            log("no package manager: " + e);
        }
        hal(PTT_OFF);
        refreshTargets();
        log("watching " + keys + (headset != null ? " and " + headset : ""));
        config();
        longPressTimeout();
        log("long press " + longPressMs + " ms");
        Thread housekeeping = new Thread(() -> {
            long refreshed = System.currentTimeMillis();
            while (true) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                long t = releasedAt;
                if (t != 0 && System.currentTimeMillis() - t >= 300) {
                    releasedAt = 0;
                    hal(PTT_OFF);
                }
                if (readLongPress) {
                    readLongPress = false;
                    longPressTimeout();
                }
                if (System.currentTimeMillis() - refreshed > 60000) {
                    refreshed = System.currentTimeMillis();
                    refreshTargets();
                }
            }
        });
        housekeeping.setDaemon(true);
        housekeeping.start();
        if (headset != null) {
            Thread h = new Thread(() -> {
                try {
                    read(headset);
                } catch (Exception e) {
                    log(headset + ": " + e);
                }
            });
            h.setDaemon(true);
            h.start();
        }
        read(keys);
    }
}
