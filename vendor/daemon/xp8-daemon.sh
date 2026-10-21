#!/system/bin/sh
# SPDX-FileCopyrightText: 2026 no0406
# SPDX-License-Identifier: MIT
#
# XP8 daemon (vendor/daemon/Xp8Daemon.java); started by vendor/xp8-gsi.rc.
exec app_process -cp /system/etc/xp8/xp8-daemon.dex /system/etc/xp8 Xp8Daemon daemon
