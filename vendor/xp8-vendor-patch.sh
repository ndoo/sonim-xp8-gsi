#!/system/bin/sh
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Apply /system/etc/xp8/vendor-patches/*.diff to copies of the stock vendor files
# in /dev/xp8 and bind-mount each result over the original. Run by init at post-fs.
P=/system/etc/xp8/vendor-patches
T=/dev/xp8
mkdir -p $T
targets() { sed -n 's|^+++ b/||p' "$1" | cut -f1; }
for d in "$P"/*.diff; do
    targets "$d" | while read -r f; do
        mkdir -p "$T/${f%/*}"
        cp -p "/vendor/$f" "$T/$f"
    done
    if ! patch -s -p1 -d $T -i "$d"; then
        log -p e -t xp8-vendor-patch "failed: $d"
        continue
    fi
    targets "$d" | while read -r f; do
        chcon "$(stat -c %C "/vendor/$f")" "$T/$f"
        mount -o bind "$T/$f" "/vendor/$f"
    done
    log -t xp8-vendor-patch "applied ${d##*/}"
done
