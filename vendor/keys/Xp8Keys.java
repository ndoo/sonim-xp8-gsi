// SPDX-FileCopyrightText: 2026 no0406
// SPDX-License-Identifier: MIT
//
// Side-key daemon: does what the XP8 side keys did on stock, where Sonim's
// SPCCService (a system app the GSI cannot install) handled them.
//
// Reads the gpio-keys input device directly: PTT (scancode 149), SOS (148) and
// the camera key (766). Each key either
//   - forwards press and release to push-to-talk apps as Sonim's broadcasts
//     (com.sonim.intent.action.PTT_KEY_DOWN/UP, SOS_KEY_DOWN/UP), which Zello and
//     others receive also in the background, or
//   - runs a short-press and a long-press action. A long press is Android's
//     touch & hold delay (Settings.Secure long_press_timeout, set under
//     Accessibility), as for the touch screen.
// While a key forwards PTT, the stock audio HAL's PTT profile is on
// (ptt_call_state=on), as Sonim's com.kodiak.pttExtensions set it.
//
// The XP8 Buttons app (vendor/keys/app) writes the choices to CONFIG; keys it has
// not set keep the defaults below. Started by vendor/xp8-gsi.rc as root.
//
// usage: app_process -cp xp8-keys.dex /vendor/etc/xp8 Xp8Keys [daemon | set k=v | get k]

import android.content.Intent;

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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Xp8Keys {
    static final String CONFIG = "/data/data/sg.ndoo.xp8.buttons/files/keys.properties";
    static final String SONIM = "com.sonim.intent.action.";
    static final String PTT_ON = "ptt_call_state=on", PTT_OFF = "ptt_call_state=off";
    static final long LONG_PRESS_DEFAULT = 400;    // ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT

    static final ExecutorService SEND = Executors.newSingleThreadExecutor();
    static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    static final Map<String, List<String>> TARGETS = new ConcurrentHashMap<>();
    static Method setParameters;
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

    // Scancode -> key name in CONFIG, and the Sonim broadcast it forwards.
    static String keyName(int code) {
        return code == 149 ? "ptt" : code == 148 ? "sos" : code == 766 ? "camera" : null;
    }

    static String sonimKey(String key) {
        return key.equals("sos") ? "SOS_KEY" : "PTT_KEY";
    }

    static Properties defaults() {
        Properties d = new Properties();
        d.setProperty("ptt.forward", "*");          // * = every app with a receiver
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

    // Packages with a receiver for each Sonim action; refreshed every minute.
    static void refreshTargets() {
        for (String a : new String[] {"PTT_KEY_DOWN", "PTT_KEY_UP", "SOS_KEY_DOWN", "SOS_KEY_UP"}) {
            List<String> pkgs = new ArrayList<>();
            for (String l : exec("cmd", "package", "query-receivers", "--brief", "-a", SONIM + a).split("\n")) {
                int slash = l.trim().indexOf('/');
                if (slash > 0 && !pkgs.contains(l.trim().substring(0, slash))) pkgs.add(l.trim().substring(0, slash));
            }
            TARGETS.put(SONIM + a, pkgs);
        }
    }

    // Explicit broadcasts through IActivityManager.broadcastIntent: no am process start,
    // so no added latency. TARGET is a package, or * for every receiver of ACTION.
    static void broadcast(String action, String target) {
        SEND.execute(() -> {
            try {
                Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
                Method bi = null;
                for (Method m : am.getClass().getMethods())
                    if (m.getName().equals("broadcastIntent") && m.getParameterTypes().length == 13) bi = m;
                List<String> pkgs = target.equals("*")
                        ? TARGETS.getOrDefault(action, Collections.emptyList())
                        : Collections.singletonList(target);
                for (String pkg : pkgs) {
                    Intent it = new Intent(action).setPackage(pkg).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    bi.invoke(am, null, it, null, null, 0, null, null, null, -1, null, false, false, 0);
                }
            } catch (Exception e) {
                log("broadcast " + action + " failed: " + e);
            }
        });
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
            broadcast("sg.ndoo.xp8.buttons.TORCH", "sg.ndoo.xp8.buttons");
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
        boolean down, longDone, sentDown;
        String forward;                                          // set while forwarding a press
        ScheduledFuture<?> longPress, holdTimer;

        Key(String name) {
            this.name = name;
        }
    }

    static String gpioKeys() throws Exception {
        File[] devs = new File("/sys/class/input").listFiles((d, n) -> n.startsWith("event"));
        if (devs != null)
            for (File d : devs) {
                File name = new File(d, "device/name");
                if (name.exists() && new String(Files.readAllBytes(name.toPath())).trim().equals("gpio-keys"))
                    return "/dev/input/" + d.getName();
            }
        throw new IllegalStateException("no gpio-keys input device");
    }

    // struct input_event on arm64: timeval (16 bytes), type (2), code (2), value (4).
    static void daemon() throws Exception {
        String dev = gpioKeys();
        hal(PTT_OFF);
        refreshTargets();
        log("watching " + dev + "; broadcast targets " + TARGETS);
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

        Map<Integer, Key> keys = new HashMap<>();
        DataInputStream in = new DataInputStream(new FileInputStream(dev));
        byte[] ev = new byte[24];
        ByteBuffer b = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
        while (true) {
            in.readFully(ev);
            int type = b.getShort(16), code = b.getShort(18), value = b.getInt(20);
            String name = type == 1 ? keyName(code) : null;     // EV_KEY of a side key only
            if (name == null || value == 2) continue;           // ignore autorepeat
            Key k = keys.computeIfAbsent(code, x -> new Key(name));
            Properties c = config();
            if (value == 1 && !k.down) {
                k.down = true;
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
                        String sk = sonimKey(name);
                        if (sk.equals("PTT_KEY")) {
                            releasedAt = 0;
                            hal(PTT_ON);
                        }
                        broadcast(SONIM + sk + "_DOWN", fwd);
                        k.sentDown = true;
                        log(name + " down -> " + sk + " to " + fwd);
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
                        String sk = sonimKey(name);
                        broadcast(SONIM + sk + "_UP", k.forward);
                        if (sk.equals("PTT_KEY")) releasedAt = System.currentTimeMillis();
                        log(name + " up -> " + sk + " to " + k.forward);
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
    }
}
