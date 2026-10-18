// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

// Sensors HAL wrapper listed in hals.conf in place of sensors.ssc.so. It loads the stock
// module and, while the screen is off, raises the maximum report latency of continuous
// non-wakeup sensors to one second so the DSP batches samples instead of waking the AP
// for each one. SIGUSR1 means screen off, SIGUSR2 screen on (vendor/sensors/xp8-sensors-screen.sh).
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "xp8-sensors"
#define FLOOR_NS 1000000000LL
#define MAX_HANDLES 256
// sensors_poll_device_1.batch: 120-byte hw_device_t (uint64_t reserved[12] on LP64), then
// activate, setDelay, poll.
#define POLL_SLOT 17
#define BATCH_SLOT 18

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

typedef int (*batch_fn)(void* dev, int handle, int flags, int64_t period_ns, int64_t latency_ns);
typedef int (*list_fn)(void* module, const sensor_t** list);

// Same size as the stock module (264 bytes): hw_module_t, get_sensors_list, set_operation_mode.
__attribute__((visibility("default"), aligned(8))) char HMI[264];

#define MOD_METHODS 32
#define MOD_LIST 248

static void* g_real;
static void* g_real_mod;
static void** g_real_dev;
static batch_fn g_real_batch;
static int (*g_real_open)(const void*, const char*, void**);
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_screen_off;
static int g_pipe[2] = {-1, -1};

static struct {
    uint8_t eligible, seen;
    int flags;
    int64_t period, latency;
} g_h[MAX_HANDLES];

static int64_t effective(int h, int64_t latency) {
    if (g_screen_off && h >= 0 && h < MAX_HANDLES && g_h[h].eligible && latency < FLOOR_NS)
        return FLOOR_NS;
    return latency;
}

static int xp8_batch(void* dev, int h, int flags, int64_t period, int64_t latency) {
    pthread_mutex_lock(&g_lock);
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
        __android_log_print(ANDROID_LOG_INFO, TAG, "screen %s, rebatched %d sensors (wrapped %d)",
                            c == '1' ? "off" : "on", n_applied, g_real_dev != NULL);
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
