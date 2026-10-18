// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

// Sensors HAL wrapper listed in hals.conf in place of sensors.ssc.so. It loads the stock
// module and, while the screen is off, raises the maximum report latency of continuous
// non-wakeup sensors to one second so the DSP batches samples instead of waking the AP
// for each one. SIGUSR1 means screen off, SIGUSR2 screen on (vendor/sensors/xp8-sensors-screen.sh).
// It also adds the pick-up (25) and wake (23) gesture sensors, which the stock HAL lacks, as
// one-shot views of the Tilt Detector (22) so SystemUI and PhoneWindowManager find them.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "xp8-sensors"
#define FLOOR_NS 1000000000LL
#define MAX_HANDLES 256
// The stock sensor_t is 104 bytes with flags at 80, not at AOSP's 76; other sizes are left alone.
#define VENDOR_STRIDE 104
#define VENDOR_FLAGS_OFF 80
// sensors_poll_device_1.batch: 120-byte hw_device_t (uint64_t reserved[12] on LP64), then
// activate, setDelay, poll.
#define ACTIVATE_SLOT 15
#define POLL_SLOT 17
#define BATCH_SLOT 18
#define FLUSH_SLOT 19
#define TILT_TYPE 22
#define N_VIRT 2
#define PEND_MAX 8

typedef struct hw_module_methods {
    int (*open)(const void* module, const char* id, void** device);
} hw_module_methods_t;

typedef struct sensor {
    const char* name;
    const char* vendor;
    int version;
    int handle;
    int type;
    float max_range;
    float resolution;
    float power;
    int32_t min_delay;
    uint32_t fifo_reserved;
    uint32_t fifo_max;
    const char* string_type;
    const char* permission;
    int32_t max_delay;
    uint32_t flags;
    void* reserved[2];
} sensor_t;

typedef struct sensors_event {
    int32_t version;
    int32_t sensor;
    int32_t type;
    int32_t reserved0;
    int64_t timestamp;
    float data[16];
    uint32_t flags;
    uint32_t reserved1[3];
} sensors_event_t;

_Static_assert(sizeof(sensors_event_t) == 104, "sensors_event_t layout");

typedef int (*batch_fn)(void* dev, int handle, int flags, int64_t period_ns, int64_t latency_ns);
typedef int (*list_fn)(void* module, const sensor_t** list);
typedef int (*activate_fn)(void* dev, int handle, int enabled);
typedef int (*poll_fn)(void* dev, sensors_event_t* data, int count);
typedef int (*flush_fn)(void* dev, int handle);

// Same size as the stock module (264 bytes): hw_module_t, get_sensors_list, set_operation_mode.
__attribute__((visibility("default"), aligned(8))) char HMI[264];

#define MOD_METHODS 32
#define MOD_LIST 248

static void* g_real;
static void* g_real_mod;
static void** g_real_dev;
static batch_fn g_real_batch;
static activate_fn g_real_activate;
static poll_fn g_real_poll;
static flush_fn g_real_flush;
static int (*g_real_open)(const void*, const char*, void**);
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_screen_off;
static int g_pipe[2] = {-1, -1};

static struct {
    uint8_t eligible, seen;
    int flags;
    int64_t period, latency;
} g_h[MAX_HANDLES];

static struct {
    int handle, type;
    const char *name, *string_type;
    uint8_t active;
} g_virt[N_VIRT] = {
    {-1, 25, "XP8 Pick Up Gesture", "android.sensor.pick_up_gesture", 0},
    {-1, 23, "XP8 Wake Gesture", "android.sensor.wake_gesture", 0},
};
static int g_tilt = -1;
static uint8_t g_tilt_real, g_tilt_on;
static int g_virt_ready;
static sensor_t* g_ext;
static int g_ext_count;
static sensors_event_t g_pend[PEND_MAX];
static int g_pend_n;

static int virt_index(int h) {
    for (int i = 0; i < N_VIRT; i++)
        if (g_virt[i].handle == h) return i;
    return -1;
}

static int64_t effective(int h, int64_t latency) {
    if (g_screen_off && h >= 0 && h < MAX_HANDLES && g_h[h].eligible && latency < FLOOR_NS)
        return FLOOR_NS;
    return latency;
}

static int xp8_batch(void* dev, int h, int flags, int64_t period, int64_t latency) {
    pthread_mutex_lock(&g_lock);
    if (virt_index(h) >= 0) {
        pthread_mutex_unlock(&g_lock);
        return g_real_batch(dev, g_tilt, flags, period, 0);
    }
    if (h >= 0 && h < MAX_HANDLES) {
        g_h[h].seen = 1;
        g_h[h].flags = flags;
        g_h[h].period = period;
        g_h[h].latency = latency;
    }
    int64_t eff = effective(h, latency);
    pthread_mutex_unlock(&g_lock);
    return g_real_batch(dev, h, flags, period, eff);
}

static int xp8_activate(void* dev, int h, int enabled) {
    pthread_mutex_lock(&g_lock);
    int v = virt_index(h);
    if (v >= 0) {
        g_virt[v].active = enabled != 0;
    } else if (h == g_tilt) {
        g_tilt_real = enabled != 0;
    } else {
        pthread_mutex_unlock(&g_lock);
        return g_real_activate(dev, h, enabled);
    }
    int want = g_tilt_real || g_virt[0].active || g_virt[1].active;
    int r = 0;
    if (want != g_tilt_on) {
        r = g_real_activate(dev, g_tilt, want);
        if (r == 0) g_tilt_on = want;
    }
    pthread_mutex_unlock(&g_lock);
    return r;
}

static int xp8_flush(void* dev, int h) {
    pthread_mutex_lock(&g_lock);
    int v = virt_index(h);
    pthread_mutex_unlock(&g_lock);
    return v >= 0 ? -EINVAL : g_real_flush(dev, h);
}

static void emit(sensors_event_t* data, int count, int* out, const sensors_event_t* ev) {
    if (*out < count) {
        data[(*out)++] = *ev;
    } else if (g_pend_n < PEND_MAX) {
        g_pend[g_pend_n++] = *ev;
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "dropped an event, pending queue full");
    }
}

static int xp8_poll(void* dev, sensors_event_t* data, int count) {
    int out = 0;
    pthread_mutex_lock(&g_lock);
    while (g_pend_n > 0 && out < count) {
        data[out++] = g_pend[0];
        memmove(g_pend, g_pend + 1, --g_pend_n * sizeof(g_pend[0]));
    }
    pthread_mutex_unlock(&g_lock);
    if (out > 0) return out;

    // A tilt event can become up to three (itself plus one per virtual sensor).
    int room = count / (N_VIRT + 1);
    if (room < 1) room = 1;
    sensors_event_t in[room];
    do {
        int n = g_real_poll(dev, data, room);
        if (n <= 0) return n;
        memcpy(in, data, n * sizeof(in[0]));
        pthread_mutex_lock(&g_lock);
        for (int i = 0; i < n; i++) {
            if (in[i].type != TILT_TYPE || in[i].sensor != g_tilt) {
                emit(data, count, &out, &in[i]);
                continue;
            }
            for (int v = 0; v < N_VIRT; v++) {
                if (!g_virt[v].active) continue;
                sensors_event_t ev = in[i];
                ev.sensor = g_virt[v].handle;
                ev.type = g_virt[v].type;
                memset(ev.data, 0, sizeof(ev.data));
                ev.data[0] = 1.0f;
                g_virt[v].active = 0;
                emit(data, count, &out, &ev);
            }
            if (g_tilt_real) emit(data, count, &out, &in[i]);
        }
        pthread_mutex_unlock(&g_lock);
    } while (out == 0);
    return out;
}

static void* screen_thread(void* arg) {
    (void)arg;
    for (;;) {
        char c;
        ssize_t n = read(g_pipe[0], &c, 1);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return NULL;
        pthread_mutex_lock(&g_lock);
        g_screen_off = c == '1';
        int n_applied = 0;
        for (int h = 0; h < MAX_HANDLES && g_real_dev; h++) {
            if (!g_h[h].eligible || !g_h[h].seen) continue;
            g_real_batch(g_real_dev, h, g_h[h].flags, g_h[h].period, effective(h, g_h[h].latency));
            n_applied++;
        }
        pthread_mutex_unlock(&g_lock);
        __android_log_print(ANDROID_LOG_INFO, TAG, "screen %s, rebatched %d sensors (wrapped %d, gesture sensors %d)",
                            c == '1' ? "off" : "on", n_applied, g_real_dev != NULL, g_virt_ready);
    }
}

static void on_signal(int sig) {
    int saved = errno;
    char c = sig == SIGUSR1 ? '1' : '0';
    (void)!write(g_pipe[1], &c, 1);
    errno = saved;
}

// The stock module's sensor_t is larger than AOSP's 96 bytes; use the entry size at which every
// entry has a unique in-range handle, a positive type, a small version and no reserved flag bits.
static size_t sensor_stride(const void* list, int count) {
    for (size_t stride = sizeof(sensor_t); stride <= 128; stride += 8) {
        uint8_t used[MAX_HANDLES] = {0};
        int ok = count > 0;
        for (int i = 0; i < count && ok; i++) {
            const sensor_t* s = (const sensor_t*)((const char*)list + i * stride);
            ok = s->handle >= 0 && s->handle < MAX_HANDLES && !used[s->handle] && s->type > 0 &&
                 s->version > 0 && s->version < 16 && (s->flags >> 8) == 0 && s->name &&
                 s->vendor;
            if (ok) used[s->handle] = 1;
        }
        if (ok) return stride;
    }
    return 0;
}

// Copy the stock list and append the virtual sensors, cloned from the Tilt Detector. Returns 0
// when there is no wake-up Tilt Detector or no free handle, and the list is left as stock.
static uint32_t* vendor_flags(const sensor_t* s) {
    return (uint32_t*)((char*)s + VENDOR_FLAGS_OFF);
}

static int build_ext_list(const sensor_t* list, int count, size_t stride) {
    if (stride != VENDOR_STRIDE) return 0;
    const sensor_t* tilt = NULL;
    int max_handle = 0;
    for (int i = 0; i < count; i++) {
        const sensor_t* s = (const sensor_t*)((const char*)list + i * stride);
        if (s->type == TILT_TYPE && (*vendor_flags(s) & 1)) tilt = s;
        if (s->handle > max_handle) max_handle = s->handle;
    }
    if (!tilt || max_handle + N_VIRT >= MAX_HANDLES) return 0;
    char* buf = calloc(count + N_VIRT, stride);
    if (!buf) return 0;
    memcpy(buf, list, count * stride);
    for (int v = 0; v < N_VIRT; v++) {
        sensor_t* s = (sensor_t*)(buf + (count + v) * stride);
        memcpy(s, tilt, stride);
        s->name = g_virt[v].name;
        s->handle = max_handle + 1 + v;
        s->type = g_virt[v].type;
        s->string_type = g_virt[v].string_type;
        s->permission = NULL;
        s->max_range = 1;
        s->resolution = 1;
        s->min_delay = -1;
        s->max_delay = 0;
        s->fifo_reserved = 0;
        s->fifo_max = 0;
        *vendor_flags(s) = 0x5;
        g_virt[v].handle = s->handle;
    }
    g_tilt = tilt->handle;
    g_ext = (sensor_t*)buf;
    g_ext_count = count + N_VIRT;
    return 1;
}

static int ensure_ext_locked(void) {
    if (g_ext) return 1;
    const sensor_t* list = NULL;
    int count = (*(list_fn*)((char*)g_real_mod + MOD_LIST))(g_real_mod, &list);
    size_t stride = list ? sensor_stride(list, count) : 0;
    return stride && build_ext_list(list, count, stride);
}

static int xp8_get_sensors_list(void* module, const sensor_t** list) {
    (void)module;
    pthread_mutex_lock(&g_lock);
    int ext = ensure_ext_locked();
    pthread_mutex_unlock(&g_lock);
    if (ext) {
        *list = g_ext;
        return g_ext_count;
    }
    return (*(list_fn*)((char*)g_real_mod + MOD_LIST))(g_real_mod, list);
}

static int xp8_open(const void* module, const char* id, void** device) {
    (void)module;
    int r = g_real_open(g_real_mod, id, device);
    if (r != 0 || !*device) return r;
    void** slots = (void**)*device;
    if (!slots[POLL_SLOT] || !slots[BATCH_SLOT]) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "unexpected device layout, batch not wrapped");
        return r;
    }
    pthread_mutex_lock(&g_lock);
    g_real_dev = slots;
    g_real_batch = (batch_fn)slots[BATCH_SLOT];
    slots[BATCH_SLOT] = (void*)xp8_batch;
    const sensor_t* list = NULL;
    int count = (*(list_fn*)((char*)g_real_mod + MOD_LIST))(g_real_mod, &list);
    size_t stride = list ? sensor_stride(list, count) : 0;
    if (!stride) {
        slots[BATCH_SLOT] = (void*)g_real_batch;
        g_real_dev = NULL;
        pthread_mutex_unlock(&g_lock);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "unrecognised sensor list layout, batch not wrapped");
        return r;
    }
    if (!g_virt_ready && ensure_ext_locked()) {
        if (slots[ACTIVATE_SLOT] && slots[FLUSH_SLOT]) {
            g_real_activate = (activate_fn)slots[ACTIVATE_SLOT];
            g_real_poll = (poll_fn)slots[POLL_SLOT];
            g_real_flush = (flush_fn)slots[FLUSH_SLOT];
            slots[ACTIVATE_SLOT] = (void*)xp8_activate;
            slots[POLL_SLOT] = (void*)xp8_poll;
            slots[FLUSH_SLOT] = (void*)xp8_flush;
            g_virt_ready = 1;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "no activate or flush slot, gesture sensors listed but inert");
        }
    }
    for (int i = 0; i < count; i++) {
        const sensor_t* s = (const sensor_t*)((const char*)list + i * stride);
        int cont = ((s->flags >> 1) & 7) == 0 && (s->flags & 1) == 0;
        int type_ok = s->type == 1 || s->type == 2 || s->type == 4 || s->type == 6 ||
                      s->type == 14 || s->type == 16 || s->type == 35;
        if (s->handle >= 0 && s->handle < MAX_HANDLES) g_h[s->handle].eligible = cont && type_ok;
    }
    pthread_mutex_unlock(&g_lock);
    __android_log_print(ANDROID_LOG_INFO, TAG, "wrapped batch of the %s device, %d sensors, entry size %zu",
                        id, count, stride);
    return r;
}

static const hw_module_methods_t g_methods = {xp8_open};

__attribute__((constructor)) static void init(void) {
    g_real = dlopen("sensors.ssc.so", RTLD_NOW);
    g_real_mod = g_real ? dlsym(g_real, "HMI") : NULL;
    if (!g_real_mod) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "cannot load sensors.ssc.so: %s", dlerror());
        return;
    }
    memcpy(HMI, g_real_mod, sizeof(HMI));
    const hw_module_methods_t* m = *(const hw_module_methods_t**)((char*)g_real_mod + MOD_METHODS);
    g_real_open = m->open;
    *(const hw_module_methods_t**)(HMI + MOD_METHODS) = &g_methods;
    *(list_fn*)(HMI + MOD_LIST) = xp8_get_sensors_list;
    if (pipe2(g_pipe, O_CLOEXEC) == 0) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_signal;
        sa.sa_flags = SA_RESTART;
        sigaction(SIGUSR1, &sa, NULL);
        sigaction(SIGUSR2, &sa, NULL);
        pthread_t t;
        pthread_create(&t, NULL, screen_thread, NULL);
    }
}
