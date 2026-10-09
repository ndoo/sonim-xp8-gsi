// SPDX-FileCopyrightText: 2026 no0406
// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT
//
// Side-key daemon: does what the XP8 side keys did on stock, where Sonim's
// framework (PhoneWindowManager and SonimSdkPolicy) handled them.
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
// While a key forwards PTT, the stock audio HAL's PTT profile is on
// (ptt_call_state=on), as Sonim's com.kodiak.pttExtensions set it.
//
// The XP8 Buttons app (vendor/keys/app) writes the choices to CONFIG; keys it has
// not set keep the defaults below. Started by vendor/xp8-gsi.rc as root.
//
// usage: app_process -cp xp8-keys.dex /system/etc/xp8 Xp8Keys [daemon | set k=v | get k]

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Xp8Keys {
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
    static String targetsShown;
    static Object am;
    static Method broadcastIntent, startService, setParameters;
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
            System.err.println("usage: Xp8Keys [daemon | set k=v | get k]");
        }
    }

    static void log(String m) {
        android.util.Log.i("xp8-keys", m);
    }

    // Scancode -> key name in CONFIG.
    static String keyName(int code) {
        return code == 149 || code == 249 ? "ptt" : code == 148 ? "sos" : code == 766 ? "camera" : null;
    }

    static Properties defaults() {
        Properties d = new Properties();
        d.setProperty("ptt.forward", "*");          // * = every push-to-talk app
        d.setProperty("sos.forward", "*");
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

    // TARGET is a package, or * for every push-to-talk app.
    static void ptt(boolean down, String target) {
        KeyEvent ev = new KeyEvent(down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, KEYCODE_PTT);
        Map<String, Target> all = pttTargets;
        List<String> pkgs = target.equals("*") ? new ArrayList<>(all.keySet()) : Collections.singletonList(target);
        for (String pkg : pkgs) {
            Target t = all.getOrDefault(pkg, new Target(SONIM_PTT, null));
            String action = t.action.equals(SONIM_PTT) ? SONIM + (down ? "PTT_KEY_DOWN" : "PTT_KEY_UP") : t.action;
            Intent it = new Intent(action).putExtra(Intent.EXTRA_KEY_EVENT, ev);
            if (t.service != null) send(it.setComponent(new ComponentName(pkg, t.service)));
            else send(it.setPackage(pkg).addFlags(FLAGS));
        }
    }

    static void sos(boolean down, String target, KeyEvent ev) {
        Map<String, List<String>> all = sosTargets;
        for (String a : new String[] {SONIM_SOS, KODIAK_SOS}) {
            List<String> pkgs = target.equals("*")
                    ? all.getOrDefault(a, Collections.emptyList())
                    : Collections.singletonList(target);
            for (String pkg : pkgs) {
                Intent it = a.equals(SONIM_SOS)
                        ? new Intent(SONIM + (down ? "SOS_KEY_DOWN" : "SOS_KEY_UP"))
                        : new Intent(KODIAK_SOS).putExtra(Intent.EXTRA_KEY_EVENT, ev);
                send(it.setPackage(pkg).addFlags(FLAGS));
            }
        }
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
        boolean down, longDone, sentDown;
        long downTime;
        String forward;                                          // set while forwarding a press
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
            k.downTime = SystemClock.uptimeMillis();
            String fwd = c.getProperty(name + ".forward", "");
            if (!fwd.isEmpty()) {
                // KEY.hold: hold this long (ms) before the press is forwarded, as Sonim's
                // "Press and Hold timer to engage PTT Key"; a shorter press sends nothing.
                k.forward = fwd;
                k.sentDown = false;
                long hold = 0;
                try {
                    hold = Long.parseLong(c.getProperty(name + ".hold", "0"));
                } catch (NumberFormatException e) {
                    log("bad " + name + ".hold");
                }
                Runnable press = () -> {
                    if (name.equals("sos")) {
                        sos(true, fwd, k.event(KeyEvent.ACTION_DOWN));
                    } else {
                        releasedAt = 0;
                        hal(PTT_ON);
                        ptt(true, fwd);
                    }
                    k.sentDown = true;
                    log(name + " down -> " + fwd);
                };
                if (hold > 0) k.holdTimer = TIMER.schedule(press, hold, TimeUnit.MILLISECONDS);
                else press.run();
            } else {
                k.longDone = false;
                String longAction = c.getProperty(name + ".long", "none");
                if (!longAction.isEmpty() && !longAction.equals("none"))
                    k.longPress = TIMER.schedule(() -> {
                        k.longDone = true;
                        run(longAction);
                    }, longPressMs, TimeUnit.MILLISECONDS);
            }
        } else if (value == 0 && k.down) {
            k.down = false;
            readLongPress = true;
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
                if (!k.longDone) run(c.getProperty(name + ".short", "none"));
            }
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
