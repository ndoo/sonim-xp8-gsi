#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Flash the GSI over fastboot: boot, vendor, then system last, on one slot.
# Needs an unlocked bootloader. Writes nothing else unless asked.
#
# usage: SERIAL=... scripts/flash.sh [options]
#   --dir DIR      directory with boot.img, vendor.img and system.img (default: out)
#   --boot FILE    --vendor FILE    --system FILE    override single images
#   --only LIST    write only these of boot,vendor,system (comma-separated), e.g.
#                  --only system to update system and keep boot, vendor and data
#   --slot a|b     slot to write (default: the current slot); b needs scripts/enable-ab.sh
#   --activate     then make that slot the active one (fastboot set_active)
#   --switch a|b   write nothing; make that slot active and reboot
#   --wipe         fastboot erase userdata; required when coming from stock or any
#                  other ROM (the GSI uses file-based encryption). Deletes all user data.
#   --clear-misc   also write 1 MiB of zeros (stock content) to misc
#   --misc-only    write only the zeroed misc, then reboot (fixes a recovery boot loop)
#   --yes          the user has approved these writes; skip the prompt
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,20s/^# \{0,1\}//p' "$0" >&2; exit 2; }

DIR=$ROOT/out BOOT='' VENDOR='' SYSTEM='' ONLY='' WIPE=0 MISC=0 MISCONLY=0 SLOT='' ACTIVATE=0 SWITCH=''
while [ $# -gt 0 ]; do
    case $1 in
        --dir) DIR=$2; shift 2 ;;
        --boot) BOOT=$2; shift 2 ;;
        --vendor) VENDOR=$2; shift 2 ;;
        --system) SYSTEM=$2; shift 2 ;;
        --only) ONLY=$2; shift 2 ;;
        --slot) SLOT=$2; shift 2 ;;
        --activate) ACTIVATE=1; shift ;;
        --switch) SWITCH=$2; shift 2 ;;
        --wipe) WIPE=1; shift ;;
        --clear-misc) MISC=1; shift ;;
        --misc-only) MISC=1; MISCONLY=1; shift ;;
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        *) die "unknown argument $1" ;;
    esac
done
case $SLOT in ''|a|b) ;; *) die "--slot: a or b" ;; esac
case $SWITCH in ''|a|b) ;; *) die "--switch: a or b" ;; esac
BOOT=${BOOT:-$DIR/boot.img} VENDOR=${VENDOR:-$DIR/vendor.img} SYSTEM=${SYSTEM:-$DIR/system.img}
if [ -n "$ONLY" ]; then
    for p in ${ONLY//,/ }; do
        case $p in boot|vendor|system) ;; *) die "--only: unknown image '$p' (boot, vendor, system)" ;; esac
    done
    [[ ",$ONLY," == *,boot,* ]] || BOOT=''
    [[ ",$ONLY," == *,vendor,* ]] || VENDOR=''
    [[ ",$ONLY," == *,system,* ]] || SYSTEM=''
    [ -n "$BOOT$VENDOR$SYSTEM" ] || die "--only: no image selected"
fi

require_serial
need_tools adb fastboot
if [ $MISCONLY = 1 ] || [ -n "$SWITCH" ]; then BOOT='' VENDOR='' SYSTEM=''; fi
for f in ${BOOT:+"$BOOT"} ${VENDOR:+"$VENDOR"} ${SYSTEM:+"$SYSTEM"}; do
    [ -f "$f" ] || {
        [ -f "$f.zst" ] && die "$f is compressed; run: zstd -d '$f.zst'"
        die "missing $f"
    }
done

# Checksums written by assemble.sh and listed in the release SHA256SUMS, when present.
verify_listed() {
    local list=$1 f want
    [ -f "$list" ] || return 0
    for f in ${BOOT:+"$BOOT"} ${VENDOR:+"$VENDOR"} ${SYSTEM:+"$SYSTEM"}; do
        want=$(awk -v n="$(basename "$f")" '$2 == n || $2 == "*" n {print $1; exit}' "$list")
        [ -n "$want" ] || continue
        [ "$(sha256 "$f")" = "$want" ] || die "$f does not match $list"
        say "$(basename "$f"): checksum OK ($list)"
    done
}
if [ $MISCONLY = 0 ] && [ -z "$SWITCH" ]; then
    verify_listed "$(dirname "${BOOT:-${VENDOR:-$SYSTEM}}")/assemble.sha256"
    verify_listed "$(dirname "${SYSTEM:-${VENDOR:-$BOOT}}")/SHA256SUMS"
fi

check_single
to_fastboot
[ "$(fb_var unlocked)" = yes ] || die "bootloader is not unlocked (fastboot getvar unlocked); run scripts/unlock.sh first"
CUR=$(fb_var current-slot)
case $CUR in a|b) ;; *) die "fastboot reports no current slot ('$CUR'); stop" ;; esac
SLOT=${SLOT:-$CUR}

if [ -n "$SWITCH" ]; then
    [ "$SWITCH" != "$CUR" ] || { say "slot $CUR is already active"; F reboot || true; exit 0; }
    confirm switch "About to make slot $SWITCH active on $SERIAL (now $CUR) and reboot. Nothing is written."
    F set_active "$SWITCH"
    settle 0
    [ "$(fb_var current-slot)" = "$SWITCH" ] || die "current-slot is not $SWITCH after set_active; stop"
    F reboot || true
    exit 0
fi

# The userdebug ABL answers no partition-size queries; sizes from the XP8800 GPT.
part_size() {
    local v
    v=$(fb_var "partition-size:$1")
    if [ -n "$v" ] && [ $((v)) -gt 0 ]; then echo $((v)); return; fi
    case $1 in
        boot_[ab]) echo 67108864 ;;
        vendor_[ab]) echo 1073741824 ;;
        system_[ab]) echo 4294967296 ;;
        misc) echo 1048576 ;;
        *) return 1 ;;
    esac
}

for pair in ${BOOT:+"boot_$SLOT:$BOOT"} ${VENDOR:+"vendor_$SLOT:$VENDOR"} ${SYSTEM:+"system_$SLOT:$SYSTEM"}; do
    part=${pair%%:*} file=${pair#*:}
    size=$(part_size "$part") || die "unknown size for $part"
    [ "$(fsize "$file")" -le "$size" ] || die "$file is larger than $part"
done


ZERO=
if [ $MISC = 1 ]; then
    [ "$(part_size misc)" = 1048576 ] || die "misc is not 1 MiB; not clearing it"
    ZERO=$(mktemp "${TMPDIR:-/tmp}/xp8-misc.XXXXXX")
    trap 'rm -f "$ZERO"' EXIT
    head -c 1048576 /dev/zero > "$ZERO"
fi

if [ $MISCONLY = 1 ]; then
    confirm flash "About to write 1 MiB of zeros (the stock content) to misc on $SERIAL."
    flash_settle misc "$ZERO"
    F reboot || true
    exit 0
fi

plan="About to write to $SERIAL over fastboot (current slot $CUR):"
[ -n "$BOOT" ] && plan+="
  boot_$SLOT    $BOOT"
[ -n "$VENDOR" ] && plan+="
  vendor_$SLOT  $VENDOR"
[ $MISC = 1 ] && plan+="
  misc      1 MiB of zeros"
[ $WIPE = 1 ] && plan+="
  userdata  ERASE: deletes all apps, accounts and files on the phone"
[ -n "$SYSTEM" ] && plan+="
  system_$SLOT  $SYSTEM"
[ $ACTIVATE = 1 ] && [ "$SLOT" != "$CUR" ] && plan+="
  set_active $SLOT: the next boot starts slot $SLOT"
if [ $WIPE = 0 ]; then
    warn "no --wipe: userdata is kept. Coming from stock Android or another ROM, the GSI cannot use the old data; rerun with --wipe"
fi
confirm flash "$plan"

[ -n "$BOOT" ] && flash_settle "boot_$SLOT" "$BOOT"
[ -n "$VENDOR" ] && flash_settle "vendor_$SLOT" "$VENDOR"
[ $MISC = 1 ] && flash_settle misc "$ZERO"
if [ $WIPE = 1 ]; then
    say "erasing userdata"
    F erase userdata
    settle 0
fi
if [ -n "$SYSTEM" ]; then
    say "flashing system_$SLOT"
    flash_settle "system_$SLOT" "$SYSTEM"
fi
if [ $ACTIVATE = 1 ] && [ "$SLOT" != "$CUR" ]; then
    say "making slot $SLOT active"
    F set_active "$SLOT"
    settle 0
    [ "$(fb_var current-slot)" = "$SLOT" ] || die "current-slot is not $SLOT after set_active; stop"
fi

cat <<EOF
$PROG: all writes done. Rebooting.
If 'fastboot reboot' fails with "could not clear input/output pipe" or hangs,
do not retry fastboot commands: hold Power 10-15 s. The phone then boots.
EOF
F reboot || true

echo
if [ $WIPE = 1 ]; then
    cat <<EOF
The first boot formats and encrypts /data before setup starts.
Then:
  1. Go through setup and set a screen-lock PIN.
  2. Enable USB debugging (Settings > About phone > tap Build number 7 times;
     Developer options > USB debugging), plug in, tick "Always allow".
  3. Run: SERIAL=$SERIAL scripts/verify-device.sh
EOF
else
    echo "After boot, unlock the phone and run: SERIAL=$SERIAL scripts/verify-device.sh"
fi
cat <<EOF
If the phone shows "Can't load Android system" or keeps booting to recovery:
  SERIAL=$SERIAL scripts/flash.sh --misc-only   (see docs/troubleshooting.md)
EOF
