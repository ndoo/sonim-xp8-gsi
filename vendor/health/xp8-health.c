// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT
// AIDL android.hardware.health.IHealth (V3) over the stock power_supply nodes, with battery
// protection: the charging policy holds the battery at a level by writing battery/input_suspend.
#define _GNU_SOURCE
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_process.h>
#include <android/binder_stability.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#define TAG "xp8-health"
#define HASH "3bab6273a5491102b29c9d7a1f0efa749533f46d"
#define PS "/sys/class/power_supply/"
#define SUSPEND PS "battery/input_suspend"
#define POLICY_PROP "persist.vendor.xp8.charging_policy"

#define HOLD_AT 80
#define RESUME_AT 75
#define BASIC_RESUME 95
// Overnight sessions: plugged in between NIGHT_FROM and NIGHT_TO local time for NIGHT_MIN_S.
// The typical unplug time is the median of those in the last HISTORY_DAYS (MIN_NIGHTS or more).
#define HISTORY "/data/vendor/xp8/charging_history"
#define HISTORY_MAX 60
#define NIGHT_FROM 20
#define NIGHT_TO 4
#define NIGHT_MIN_S (4 * 3600)
#define HISTORY_DAYS 14
#define MIN_NIGHTS 3
#define FULL_BEFORE_S 3600
#define TOP_UP_S (90 * 60)
// The fuel gauge's warm threshold (bms/temp_warm); the charger itself stops at temp_hot (55 C).
#define HOT_DECI_C 450
#define COOL_DECI_C 400
#define MAX_CB 8

enum { ST_UNKNOWN = 1, ST_CHARGING, ST_DISCHARGING, ST_NOT_CHARGING, ST_FULL };
enum { POLICY_DEFAULT = 1, POLICY_LONG_LIFE = 2, POLICY_ADAPTIVE = 3 };
enum { CS_NORMAL = 1, CS_TOO_HOT = 3, CS_LONG_LIFE = 4, CS_ADAPTIVE = 5 };
enum { HOLD_NONE, HOLD_HEAT, HOLD_NIGHT, HOLD_BASIC, HOLD_LIMIT };
// Transactions beyond IHealth, for the XP8 Buttons app and the XP8 daemon.
#define TX_CHARGE_NOW 1000
#define TX_PROTECTION_STATUS 1001

typedef struct {
    int ac, usb, wireless;
    int max_current, max_voltage;
    int status, health, present, level, voltage_mv, temp, current, cycles;
    int full_uah, counter_uah, design_uah, charging_state, policy;
    char tech[32];
} info_t;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static info_t g_info;
static int g_policy = POLICY_ADAPTIVE;
static int g_held, g_hot, g_reason, g_override, g_predicted = -1;
static time_t g_plug_wall, g_resume_wall;
static AIBinder* g_cb[MAX_CB];
static AIBinder_Class* g_cb_class;
static AIBinder_DeathRecipient* g_death;

static int read_str(const char* path, char* buf, size_t n) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t r = read(fd, buf, n - 1);
    close(fd);
    if (r < 0) return -1;
    while (r > 0 && (buf[r - 1] == '\n' || buf[r - 1] == ' ')) r--;
    buf[r] = 0;
    return 0;
}

static int read_int(const char* path, int def) {
    char b[32];
    return read_str(path, b, sizeof(b)) ? def : atoi(b);
}

static int node_int(const char* supply, const char* attr, int def) {
    char p[128];
    snprintf(p, sizeof(p), PS "%s/%s", supply, attr);
    return read_int(p, def);
}

static void set_suspend(int on) {
    int fd = open(SUSPEND, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    (void)!write(fd, on ? "1" : "0", 1);
    close(fd);
}

static int map_status(const char* s) {
    if (!strcmp(s, "Charging")) return ST_CHARGING;
    if (!strcmp(s, "Discharging")) return ST_DISCHARGING;
    if (!strcmp(s, "Not charging")) return ST_NOT_CHARGING;
    if (!strcmp(s, "Full")) return ST_FULL;
    return ST_UNKNOWN;
}

// healthd's table, including the JEITA states.
static int map_health(const char* s) {
    static const struct { const char* s; int v; } t[] = {
            {"Unknown", 1}, {"Good", 2}, {"Overheat", 3}, {"Dead", 4}, {"Over voltage", 5},
            {"Unspecified failure", 6}, {"Cold", 7}, {"Warm", 2}, {"Cool", 2}, {"Hot", 3},
            {"Calibration required", 12}};
    for (size_t i = 0; i < sizeof(t) / sizeof(t[0]); i++)
        if (!strcmp(s, t[i].s)) return t[i].v;
    return 1;
}

// Charger kinds as healthd maps power_supply types; 0 = not a charger.
enum { CH_AC = 1, CH_USB, CH_WIRELESS };
static int charger_kind(const char* type) {
    if (!strcmp(type, "USB") || !strcmp(type, "USB_PD_DRP")) return CH_USB;
    if (!strcmp(type, "Mains") || !strncmp(type, "USB_", 4)) return CH_AC;
    if (!strcmp(type, "Wireless")) return CH_WIRELESS;
    return 0;
}

static struct tm local(time_t t) {
    struct tm tm;
    localtime_r(&t, &tm);
    return tm;
}

static int is_night(time_t t) {
    struct tm tm = local(t);
    return tm.tm_hour >= NIGHT_FROM || tm.tm_hour < NIGHT_TO;
}

// Median local minute of day of recent overnight unplugs, or -1.
static int predict_unplug(time_t now) {
    FILE* f = fopen(HISTORY, "re");
    if (!f) return -1;
    int mins[HISTORY_MAX], n = 0;
    long long plug, unplug;
    while (n < HISTORY_MAX && fscanf(f, "%lld %lld", &plug, &unplug) == 2) {
        if (unplug - plug < NIGHT_MIN_S || now - unplug > HISTORY_DAYS * 86400LL ||
            !is_night((time_t)plug))
            continue;
        struct tm tm = local((time_t)unplug);
        mins[n++] = tm.tm_hour * 60 + tm.tm_min;
    }
    fclose(f);
    if (n < MIN_NIGHTS) return -1;
    for (int a = 1; a < n; a++)
        for (int b = a; b > 0 && mins[b - 1] > mins[b]; b--) {
            int t = mins[b];
            mins[b] = mins[b - 1];
            mins[b - 1] = t;
        }
    return mins[n / 2];
}

// Keeps the newest HISTORY_MAX sessions.
static void record_session(time_t plug, time_t unplug) {
    long long p[HISTORY_MAX], u[HISTORY_MAX], a, b;
    int n = 0;
    FILE* f = fopen(HISTORY, "re");
    while (f && fscanf(f, "%lld %lld", &a, &b) == 2) {
        if (n == HISTORY_MAX - 1) {
            memmove(p, p + 1, sizeof(p[0]) * (n - 1));
            memmove(u, u + 1, sizeof(u[0]) * (n - 1));
            n--;
        }
        p[n] = a;
        u[n++] = b;
    }
    if (f) fclose(f);
    p[n] = plug;
    u[n++] = unplug;
    f = fopen(HISTORY ".tmp", "we");
    if (!f) return;
    for (int k = 0; k < n; k++) fprintf(f, "%lld %lld\n", p[k], u[k]);
    if (fclose(f) == 0) rename(HISTORY ".tmp", HISTORY);
}

// When an overnight hold at 80% ends: full FULL_BEFORE_S before the predicted unplug.
static time_t plan_night(time_t plug, int predicted) {
    if (predicted < 0 || !is_night(plug)) return 0;
    struct tm tm = local(plug);
    tm.tm_hour = predicted / 60;
    tm.tm_min = predicted % 60;
    tm.tm_sec = 0;
    tm.tm_isdst = -1;
    time_t unplug = mktime(&tm);
    if (unplug <= plug) {
        tm.tm_mday++;
        tm.tm_isdst = -1;
        unplug = mktime(&tm);
    }
    if (unplug - plug > 16 * 3600) return 0;
    return unplug - FULL_BEFORE_S - TOP_UP_S;
}

// Reads the nodes, applies the charging policy and fills g_info. Called with g_lock held.
static void refresh_locked(void) {
    info_t i;
    memset(&i, 0, sizeof(i));
    char s[64];
    int plugged = 0;
    DIR* d = opendir(PS);
    struct dirent* e;
    while (d && (e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char p[128];
        snprintf(p, sizeof(p), PS "%s/type", e->d_name);
        if (read_str(p, s, sizeof(s))) continue;
        int kind = charger_kind(s);
        if (!kind) continue;
        int online = node_int(e->d_name, "online", 0);
        // input_suspend clears online; present and the Type-C state still show the cable.
        int attached = online || node_int(e->d_name, "present", 0);
        if (!attached) continue;
        plugged = 1;
        if (kind == CH_AC) i.ac = 1;
        if (kind == CH_USB) i.usb = 1;
        if (kind == CH_WIRELESS) i.wireless = 1;
        int cur = node_int(e->d_name, "current_max", 0), volt = node_int(e->d_name, "voltage_max", 0);
        if (cur > i.max_current) {
            i.max_current = cur;
            i.max_voltage = volt;
        }
    }
    if (d) closedir(d);

    i.status = read_str(PS "battery/status", s, sizeof(s)) ? ST_UNKNOWN : map_status(s);
    i.health = read_str(PS "battery/health", s, sizeof(s)) ? 1 : map_health(s);
    i.present = node_int("battery", "present", 1);
    i.level = node_int("battery", "capacity", 50);
    i.voltage_mv = node_int("battery", "voltage_now", 0) / 1000;
    i.temp = node_int("battery", "temp", 250);
    i.current = node_int("battery", "current_now", 0);
    i.cycles = node_int("battery", "cycle_count", 0);
    i.full_uah = node_int("battery", "charge_full", 0);
    i.counter_uah = node_int("battery", "charge_counter", 0);
    i.design_uah = node_int("bms", "charge_full_design", 0);
    if (read_str(PS "battery/technology", i.tech, sizeof(i.tech))) strcpy(i.tech, "Li-ion");

    time_t wall = time(NULL);
    if (plugged && !g_plug_wall) {
        g_plug_wall = wall;
        g_predicted = predict_unplug(wall);
        g_resume_wall = plan_night(wall, g_predicted);
        g_override = 0;
    } else if (!plugged && g_plug_wall) {
        record_session(g_plug_wall, wall);
        g_plug_wall = g_resume_wall = 0;
        g_override = 0;
    }
    if (i.temp >= HOT_DECI_C) g_hot = 1;
    else if (i.temp <= COOL_DECI_C) g_hot = 0;

    int reason = HOLD_NONE, state = CS_NORMAL;
    if (plugged) {
        int at80 = i.level >= HOLD_AT || (g_held && g_reason != HOLD_BASIC && i.level > RESUME_AT);
        int at100 = i.level >= 100 || (g_held && g_reason == HOLD_BASIC && i.level > BASIC_RESUME);
        if (g_policy != POLICY_DEFAULT && g_hot && at80) reason = HOLD_HEAT;
        else if (g_override) reason = HOLD_NONE;
        else if (g_policy == POLICY_LONG_LIFE && at80) reason = HOLD_LIMIT;
        else if (g_policy == POLICY_ADAPTIVE && g_resume_wall && wall < g_resume_wall && at80)
            reason = HOLD_NIGHT;
        else if (g_policy == POLICY_ADAPTIVE && at100) reason = HOLD_BASIC;
        state = reason == HOLD_HEAT                         ? CS_TOO_HOT
                : reason == HOLD_NIGHT                      ? CS_ADAPTIVE
                : g_policy == POLICY_LONG_LIFE && !g_override ? CS_LONG_LIFE
                                                              : CS_NORMAL;
    }
    int hold = reason != HOLD_NONE;
    if (hold != g_held || reason != g_reason) {
        if (hold != g_held) set_suspend(hold);
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s at %d%%, %d.%d C, policy %d, reason %d",
                            hold ? "holding" : "charging", i.level, i.temp / 10, i.temp % 10,
                            g_policy, reason);
        g_held = hold;
        g_reason = reason;
    }
    // At 95-100 % the Basic hold reads as a full battery.
    if (g_held && plugged) i.status = g_reason == HOLD_BASIC ? ST_FULL : ST_NOT_CHARGING;
    i.charging_state = state;
    i.policy = g_policy;
    g_info = i;
}

// Stable AIDL parcelable: a size prefix, then the fields in declaration order.
static binder_status_t write_info(AParcel* p, const info_t* i) {
    int32_t start = AParcel_getDataPosition(p);
    binder_status_t r = AParcel_writeInt32(p, 0);
    if (r == STATUS_OK) r = AParcel_writeBool(p, i->ac);
    if (r == STATUS_OK) r = AParcel_writeBool(p, i->usb);
    if (r == STATUS_OK) r = AParcel_writeBool(p, i->wireless);
    if (r == STATUS_OK) r = AParcel_writeBool(p, 0);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->max_current);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->max_voltage);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->status);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->health);
    if (r == STATUS_OK) r = AParcel_writeBool(p, i->present);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->level);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->voltage_mv);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->temp);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->current);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->cycles);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->full_uah);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->counter_uah);
    if (r == STATUS_OK) r = AParcel_writeString(p, i->tech, strlen(i->tech));
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->current);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, 0); /* diskStats */
    if (r == STATUS_OK) r = AParcel_writeInt32(p, 0); /* storageInfos */
    if (r == STATUS_OK) r = AParcel_writeInt32(p, -1); /* batteryCapacityLevel UNSUPPORTED */
    if (r == STATUS_OK) r = AParcel_writeInt64(p, -1); /* time to full unsupported */
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->design_uah);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->charging_state);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, i->policy);
    if (r == STATUS_OK) r = AParcel_writeInt32(p, 0); /* batteryHealthData null */
    if (r != STATUS_OK) return r;
    int32_t end = AParcel_getDataPosition(p);
    if ((r = AParcel_setDataPosition(p, start)) != STATUS_OK) return r;
    if ((r = AParcel_writeInt32(p, end - start)) != STATUS_OK) return r;
    return AParcel_setDataPosition(p, end);
}

static void notify(AIBinder* cb, const info_t* i) {
    AParcel* in = NULL;
    AParcel* out = NULL;
    if (AIBinder_prepareTransaction(cb, &in) != STATUS_OK) return;
    if (AParcel_writeInt32(in, 1) != STATUS_OK || write_info(in, i) != STATUS_OK) {
        AParcel_delete(in);
        return;
    }
    if (AIBinder_transact(cb, 1 /* healthInfoChanged */, &in, &out, FLAG_ONEWAY) == STATUS_OK)
        AParcel_delete(out);
}

static void refresh_and_notify(AIBinder* only) {
    AIBinder* cbs[MAX_CB];
    int n = 0;
    pthread_mutex_lock(&g_lock);
    refresh_locked();
    info_t i = g_info;
    for (int k = 0; k < MAX_CB; k++) {
        if (!g_cb[k] || (only && g_cb[k] != only)) continue;
        AIBinder_incStrong(g_cb[k]);
        cbs[n++] = g_cb[k];
    }
    pthread_mutex_unlock(&g_lock);
    for (int k = 0; k < n; k++) {
        notify(cbs[k], &i);
        AIBinder_decStrong(cbs[k]);
    }
}

static void remove_cb(AIBinder* b) {
    pthread_mutex_lock(&g_lock);
    for (int k = 0; k < MAX_CB; k++) {
        if (g_cb[k] != b) continue;
        AIBinder_unlinkToDeath(b, g_death, b);
        AIBinder_decStrong(b);
        g_cb[k] = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

static void on_died(void* cookie) {
    pthread_mutex_lock(&g_lock);
    for (int k = 0; k < MAX_CB; k++) {
        if (g_cb[k] != cookie) continue;
        AIBinder_decStrong(g_cb[k]);
        g_cb[k] = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

static binder_status_t reply(AParcel* out, AStatus* st) {
    binder_status_t r = AParcel_writeStatusHeader(out, st);
    AStatus_delete(st);
    return r;
}

static binder_status_t reply_int(AParcel* out, int32_t v) {
    binder_status_t r = reply(out, AStatus_newOk());
    return r == STATUS_OK ? AParcel_writeInt32(out, v) : r;
}

static binder_status_t on_transact(AIBinder* b, transaction_code_t code, const AParcel* in,
                                   AParcel* out) {
    (void)b;
    binder_status_t r;
    AIBinder* cb = NULL;
    int32_t v;
    info_t i;
    switch (code) {
        case 1: /* registerCallback */
            if ((r = AParcel_readStrongBinder(in, &cb)) != STATUS_OK) return r;
            if (!cb || !AIBinder_associateClass(cb, g_cb_class)) {
                if (cb) AIBinder_decStrong(cb);
                return reply(out, AStatus_fromExceptionCode(EX_ILLEGAL_ARGUMENT));
            }
            pthread_mutex_lock(&g_lock);
            int slot = -1;
            for (int k = 0; k < MAX_CB && slot < 0; k++)
                if (!g_cb[k]) slot = k;
            if (slot >= 0) {
                g_cb[slot] = cb;
                AIBinder_linkToDeath(cb, g_death, cb);
            }
            pthread_mutex_unlock(&g_lock);
            if (slot < 0) {
                AIBinder_decStrong(cb);
                return reply(out, AStatus_fromExceptionCode(EX_ILLEGAL_STATE));
            }
            refresh_and_notify(cb);
            return reply(out, AStatus_newOk());
        case 2: /* unregisterCallback */
            if ((r = AParcel_readStrongBinder(in, &cb)) != STATUS_OK) return r;
            if (cb) {
                remove_cb(cb);
                AIBinder_decStrong(cb);
            }
            return reply(out, AStatus_newOk());
        case 3: /* update */
            refresh_and_notify(NULL);
            return reply(out, AStatus_newOk());
        case 4: case 5: case 6: case 7: case 9: case 14:
            pthread_mutex_lock(&g_lock);
            refresh_locked();
            i = g_info;
            pthread_mutex_unlock(&g_lock);
            return reply_int(out, code == 4   ? i.counter_uah
                                  : code == 5 ? i.current
                                  : code == 6 ? i.current
                                  : code == 7 ? i.level
                                  : code == 9 ? i.status
                                              : i.policy);
        case 10: /* getStorageInfo */
        case 11: /* getDiskStats */
            return reply_int(out, 0);
        case 12: /* getHealthInfo */
            pthread_mutex_lock(&g_lock);
            refresh_locked();
            i = g_info;
            pthread_mutex_unlock(&g_lock);
            if ((r = reply(out, AStatus_newOk())) != STATUS_OK) return r;
            if ((r = AParcel_writeInt32(out, 1)) != STATUS_OK) return r;
            return write_info(out, &i);
        case 13: /* setChargingPolicy */
            if ((r = AParcel_readInt32(in, &v)) != STATUS_OK) return r;
            if (v < POLICY_DEFAULT || v > POLICY_ADAPTIVE)
                return reply(out, AStatus_fromExceptionCode(EX_ILLEGAL_ARGUMENT));
            pthread_mutex_lock(&g_lock);
            g_policy = v;
            g_override = 0;
            pthread_mutex_unlock(&g_lock);
            char s[4];
            snprintf(s, sizeof(s), "%d", v);
            __system_property_set(POLICY_PROP, s);
            __android_log_print(ANDROID_LOG_INFO, TAG, "charging policy %d", v);
            refresh_and_notify(NULL);
            return reply(out, AStatus_newOk());
        case TX_CHARGE_NOW: /* hold off until the charger is unplugged */
            pthread_mutex_lock(&g_lock);
            if (g_plug_wall) g_override = 1;
            pthread_mutex_unlock(&g_lock);
            __android_log_print(ANDROID_LOG_INFO, TAG, "charge now until unplugged");
            refresh_and_notify(NULL);
            return reply(out, AStatus_newOk());
        case TX_PROTECTION_STATUS: /* reason, full-by time (s), override, predicted unplug minute */
            pthread_mutex_lock(&g_lock);
            refresh_locked();
            int32_t reason = g_held ? g_reason : HOLD_NONE, ovr = g_override, pred = g_predicted;
            int64_t full_by = reason == HOLD_NIGHT ? (int64_t)g_resume_wall + TOP_UP_S : 0;
            pthread_mutex_unlock(&g_lock);
            if ((r = reply_int(out, reason)) != STATUS_OK) return r;
            if ((r = AParcel_writeInt64(out, full_by)) != STATUS_OK) return r;
            if ((r = AParcel_writeInt32(out, ovr)) != STATUS_OK) return r;
            return AParcel_writeInt32(out, pred);
        case 8:  /* getEnergyCounterNwh */
        case 15: /* getBatteryHealthData */
            return reply(out, AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
        case 16777215: /* getInterfaceVersion */
            return reply_int(out, 3);
        case 16777214: /* getInterfaceHash */
            if ((r = reply(out, AStatus_newOk())) != STATUS_OK) return r;
            return AParcel_writeString(out, HASH, strlen(HASH));
        default:
            return STATUS_UNKNOWN_TRANSACTION;
    }
}

static binder_status_t cb_transact(AIBinder* b, transaction_code_t c, const AParcel* in,
                                   AParcel* out) {
    (void)b, (void)c, (void)in, (void)out;
    return STATUS_UNKNOWN_TRANSACTION;
}

static void* on_create(void* args) { return args; }
static void on_destroy(void* data) { (void)data; }

static void* event_loop(void* arg) {
    (void)arg;
    struct sockaddr_nl a = {.nl_family = AF_NETLINK, .nl_groups = 1};
    int fd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    if (fd >= 0 && bind(fd, (struct sockaddr*)&a, sizeof(a)) != 0) {
        close(fd);
        fd = -1;
    }
    if (fd < 0) __android_log_print(ANDROID_LOG_ERROR, TAG, "no uevent socket, polling only");
    char buf[2048];
    for (;;) {
        pthread_mutex_lock(&g_lock);
        int plugged = g_plug_wall != 0;
        pthread_mutex_unlock(&g_lock);
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int n = poll(&p, fd >= 0 ? 1 : 0, (plugged ? 60 : 600) * 1000);
        if (n > 0) {
            ssize_t len = recv(fd, buf, sizeof(buf) - 1, 0);
            if (len <= 0) continue;
            buf[len] = 0;
            int ours = 0;
            for (char* s = buf; s < buf + len; s += strlen(s) + 1)
                if (!strcmp(s, "SUBSYSTEM=power_supply")) ours = 1;
            if (!ours) continue;
        }
        refresh_and_notify(NULL);
    }
    return NULL;
}

static void on_term(int sig) {
    (void)sig;
    set_suspend(0);
    _exit(0);
}

int main(int argc, char** argv) {
    // --test [NAME]: register without VINTF stability, for checks from a root shell.
    int test = argc > 1 && !strcmp(argv[1], "--test");
    const char* name = !test ? "android.hardware.health.IHealth/default"
                       : argc > 2 ? argv[2]
                                  : "android.hardware.health.IHealth/xp8test";
    set_suspend(0);
    signal(SIGTERM, on_term);
    char s[PROP_VALUE_MAX] = "";
    if (__system_property_get(POLICY_PROP, s) > 0 && atoi(s) >= POLICY_DEFAULT &&
        atoi(s) <= POLICY_ADAPTIVE)
        g_policy = atoi(s);
    g_death = AIBinder_DeathRecipient_new(on_died);
    g_cb_class = AIBinder_Class_define("android.hardware.health.IHealthInfoCallback", on_create,
                                       on_destroy, cb_transact);
    AIBinder_Class* clazz = AIBinder_Class_define("android.hardware.health.IHealth", on_create,
                                                  on_destroy, on_transact);
    AIBinder* binder = AIBinder_new(clazz, NULL);
    if (!test) AIBinder_markVintfStability(binder);
    pthread_mutex_lock(&g_lock);
    refresh_locked();
    pthread_mutex_unlock(&g_lock);
    pthread_t t;
    pthread_create(&t, NULL, event_loop, NULL);
    ABinderProcess_setThreadPoolMaxThreadCount(0);
    binder_exception_t e = AServiceManager_addService(binder, name);
    if (e != EX_NONE) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "addService %s failed: %d", name, e);
        set_suspend(0);
        return 1;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "serving %s, charging policy %d", name, g_policy);
    ABinderProcess_joinThreadPool();
    set_suspend(0);
    return 1;
}
