// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

// Lights HAL module found before the stock lights.sdm660.so (libhardware tries ro.hardware,
// "qcom", first). Backlight and buttons go to the stock module. The battery, notifications and
// attention lights share the RGB LED, which this module drives itself: the kernel has no blink
// control, so a flash is a PWM period of flashOnMS + flashOffMS with a duty of on / (on + off),
// and a steady colour is a 1 ms period. The stock module writes only brightness and blinks
// through the 2 s period in Sonim's device tree, which makes part-brightness colours flicker.
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "xp8-lights"
#define STOCK "/vendor/lib64/hw/lights.sdm660.so"
#define STEADY_US 1000

typedef struct hw_module hw_module_t;
typedef struct hw_device {
    uint32_t tag;
    uint32_t version;
    hw_module_t* module;
    uint64_t reserved[12];
    int (*close)(struct hw_device* dev);
} hw_device_t;

typedef struct hw_module_methods {
    int (*open)(const hw_module_t* module, const char* id, hw_device_t** device);
} hw_module_methods_t;

struct hw_module {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    hw_module_methods_t* methods;
    void* dso;
    uint64_t reserved[25];
};

typedef struct light_state {
    unsigned int color;
    int flashMode;
    int flashOnMS;
    int flashOffMS;
    int brightnessMode;
} light_state_t;

typedef struct light_device {
    hw_device_t common;
    int (*set_light)(struct light_device* dev, const light_state_t* state);
} light_device_t;

_Static_assert(sizeof(hw_module_t) == 248, "hw_module_t layout");
_Static_assert(sizeof(light_device_t) == 128, "light_device_t layout");

static const char* const BRIGHTNESS[3] = {
    "/sys/class/leds/red/brightness", "/sys/class/leds/green/brightness",
    "/sys/class/leds/blue/brightness"};
static const char* const PWM_US[3] = {
    "/sys/class/leds/red/pwm_us", "/sys/class/leds/green/pwm_us", "/sys/class/leds/blue/pwm_us"};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static hw_module_t* stock;
static light_state_t battery, notification;
static long period_us[3];

static int write_long(const char* path, long v) {
    char buf[24];
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "open %s: %s", path, strerror(errno));
        return -1;
    }
    int n = snprintf(buf, sizeof(buf), "%ld\n", v);
    int ok = write(fd, buf, n) == n;
    if (!ok) __android_log_print(ANDROID_LOG_ERROR, TAG, "write %ld to %s: %s", v, path, strerror(errno));
    close(fd);
    return ok ? 0 : -1;
}

// The stock module's choice: battery while it is lit, otherwise notifications.
static void apply_locked(void) {
    const light_state_t* s = (battery.color & 0xffffff) ? &battery : &notification;
    int flash = (s->flashMode == 1 || s->flashMode == 2) && s->flashOnMS > 0 && s->flashOffMS > 0;
    long long total = flash ? (long long)s->flashOnMS + s->flashOffMS : 0;
    long period = flash ? (long)(total * 1000) : STEADY_US;
    int duty = flash ? (int)((s->flashOnMS * 255LL + total / 2) / total) : 0;
    if (flash && duty < 1) duty = 1;
    for (int c = 0; c < 3; c++) {
        int v = (s->color >> (16 - 8 * c)) & 0xff;
        int b = flash ? (v ? duty : 0) : v;
        if (b && period != period_us[c]) period_us[c] = write_long(PWM_US[c], period) ? 0 : period;
        write_long(BRIGHTNESS[c], b);
    }
}

static int set_battery(light_device_t* dev, const light_state_t* s) {
    (void)dev;
    pthread_mutex_lock(&lock);
    battery = *s;
    apply_locked();
    pthread_mutex_unlock(&lock);
    return 0;
}

static int set_notification(light_device_t* dev, const light_state_t* s) {
    (void)dev;
    pthread_mutex_lock(&lock);
    notification = *s;
    apply_locked();
    pthread_mutex_unlock(&lock);
    return 0;
}

// As in the stock module, an attention request only re-applies the current state.
static int set_attention(light_device_t* dev, const light_state_t* s) {
    (void)dev;
    (void)s;
    pthread_mutex_lock(&lock);
    apply_locked();
    pthread_mutex_unlock(&lock);
    return 0;
}

static int open_lights(const hw_module_t* module, const char* id, hw_device_t** device) {
    (void)module;
    if (!stock) {
        void* h = dlopen(STOCK, RTLD_NOW);
        stock = h ? dlsym(h, "HMI") : NULL;
        if (!stock) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "load " STOCK ": %s", dlerror());
            return -ENOENT;
        }
    }
    int ret = stock->methods->open(stock, id, device);
    if (ret) return ret;
    light_device_t* dev = (light_device_t*)*device;
    if (!strcmp(id, "battery")) dev->set_light = set_battery;
    else if (!strcmp(id, "notifications")) dev->set_light = set_notification;
    else if (!strcmp(id, "attention")) dev->set_light = set_attention;
    return 0;
}

static hw_module_methods_t methods = {.open = open_lights};

__attribute__((visibility("default"))) hw_module_t HMI = {
    .tag = ('H' << 24) | ('W' << 16) | ('M' << 8) | 'T',
    .module_api_version = 1,
    .hal_api_version = 0,
    .id = "lights",
    .name = "XP8 lights",
    .author = "XP8 GSI",
    .methods = &methods,
};
