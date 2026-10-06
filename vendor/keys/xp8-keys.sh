#!/system/bin/sh
# SPDX-FileCopyrightText: 2026 no0406
# SPDX-License-Identifier: MIT
#
# Side-key daemon (vendor/keys/Xp8Keys.java); started by vendor/xp8-gsi.rc.
exec app_process -cp /system/etc/xp8/xp8-keys.dex /system/etc/xp8 Xp8Keys daemon
