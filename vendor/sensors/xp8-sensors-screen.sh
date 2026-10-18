#!/system/bin/sh
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Tell the sensors HAL wrapper (sensors.xp8.so) that the screen turned off or on.
# Run by init on debug.tracing.screen_state changes (vendor/xp8-gsi.rc).
case "$1" in
    off) sig=USR1 ;;
    on) sig=USR2 ;;
    *) exit 1 ;;
esac
pid=$(pidof android.hardware.sensors@1.0-service) && kill -"$sig" "$pid"
