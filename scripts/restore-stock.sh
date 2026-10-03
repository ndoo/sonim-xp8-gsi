#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Put stock Android 10 back from your own backup (scripts/dump-stock.sh):
# boot_a and system_a, optionally vendor_a; over fastboot or EDL. misc gets a
# recovery --wipe_data request, so the first stock boot formats userdata.
# Every image is checked against the backup's SHA256SUMS before anything is written.
#
# usage: SERIAL=... scripts/restore-stock.sh [options] BACKUP_DIR
#   --edl              write over EDL (needs EDL_LOADER); for a phone that cannot reach fastboot
#   --vendor           also restore vendor_a (only if the backup has vendor_a.bin)
#   --relock           then lock the bootloader, write back stock abl_a and abl_b and clear frp
#   --restore-nv       also write modemst1, modemst2, fsg, fsc, persist (EDL, same unit only)
#   --restore-persist  also write persist only (EDL, same unit only)
#   --work DIR         scratch space for unpacked images, about 6 GiB (default: work/restore)
#   --yes              the user has approved these writes; skip the prompts
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,17s/^# \{0,1\}//p' "$0" >&2; exit 2; }

BACKUP='' WORK=$ROOT/work/restore
VIAEDL=0 VENDOR=0 RELOCK=0 NV=0 PERSIST=0
while [ $# -gt 0 ]; do
    case $1 in
        --edl) VIAEDL=1; shift ;;
        --vendor) VENDOR=1; shift ;;
        --relock) RELOCK=1; shift ;;
        --restore-nv) NV=1; shift ;;
        --restore-persist) PERSIST=1; shift ;;
        --work) WORK=$2; shift 2 ;;
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        -*) die "unknown option $1" ;;
        *) [ -z "$BACKUP" ] || usage; BACKUP=$1; shift ;;
    esac
done
[ -n "$BACKUP" ] || usage

require_serial
need_tools adb fastboot zstd awk
BACKUP=$(cd "$BACKUP" && pwd)
[ -f "$BACKUP/SHA256SUMS" ] || die "$BACKUP/SHA256SUMS missing; not a backup from scripts/dump-stock.sh"
mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd)
XP8_MIN_FREE_GIB=${XP8_MIN_FREE_GIB:-6} "$ROOT/scripts/check-space.sh" assemble "$WORK"

NEED_EDL=$((VIAEDL | RELOCK | NV | PERSIST))
[ $NEED_EDL = 0 ] || edl_setup

parts=(boot_a system_a)
[ $VENDOR = 1 ] && parts+=(vendor_a)
[ $RELOCK = 1 ] && parts+=(abl_a abl_b)
nvparts=()
[ $NV = 1 ] && nvparts=("${NV_PARTS[@]}")
[ $PERSIST = 1 ] && [ $NV = 0 ] && nvparts=(persist)

for p in "${parts[@]}" ${nvparts[@]+"${nvparts[@]}"}; do
    backup_image "$p"
done
# Path backup_image used for NAME: the raw file in the backup, or the unpacked copy in WORK.
img() { if [ -f "$BACKUP/$1.bin" ]; then echo "$BACKUP/$1.bin"; else echo "$WORK/$1.bin"; fi; }
# An erase only discards userdata blocks; stock Android 10 then mounts the GSI's
# leftover filesystem and bootloops. Stock recovery formats it instead.
MISC_WIPE=$WORK/misc-wipe.bin
make_wipe_bcb "$MISC_WIPE"

writes() {
    printf '  %s\n' "$@"
}

if [ $VIAEDL = 0 ]; then
    check_single
    to_fastboot
    [ "$(fb_var unlocked)" = yes ] || die "fastboot flash needs the unlocked bootloader; use --edl"
    if [ "$(fb_var current-slot)" != a ]; then
        confirm switch "Slot b is active on $SERIAL. Stock Android runs from slot a; about to make slot a active (nothing is written)."
        F set_active a
        settle 0
        [ "$(fb_var current-slot)" = a ] || die "current slot is still not a; stop"
    fi
    list=(boot_a "misc (recovery --wipe_data: the next boot deletes all user data)")
    [ $VENDOR = 1 ] && list+=(vendor_a)
    list+=(system_a)
    confirm restore "About to write your stock backup to $SERIAL over fastboot:
$(writes "${list[@]}")"
    flash_settle boot_a "$(img boot_a)"
    flash_settle misc "$MISC_WIPE"
    [ $VENDOR = 1 ] && flash_settle vendor_a "$(img vendor_a)"
    say "flashing system_a"
    flash_settle system_a "$(img system_a)"
    say "fastboot writes done"

    if [ $RELOCK = 1 ]; then
        confirm lock "Next: fastboot flashing lock. The phone asks for confirmation and erases user data again."
        if ! F flashing lock; then
            warn "fastboot flashing lock failed; continuing with the stock abl_a and a cleared frp"
        fi
    fi
    if [ $NEED_EDL = 0 ]; then
        F reboot || true
    else
        cat <<EOF

Next step needs EDL. Turn the phone off (hold Power 10-15 s), then hold
Vol+ and Vol- and press Power. The screen stays black in EDL.
EOF
    fi
fi

if [ $NEED_EDL = 1 ]; then
    check_single
    if in_adb; then A reboot edl; fi
    wait_edl
    GPT=$WORK/gpt.txt
    edl_identify "$WORK" "$GPT"
    check_unit
    awk '$1 == "boot_a:" && /Active True/ {f = 1} END {exit !f}' "$GPT" ||
        die "slot a is not active. Boot to fastboot and run: fastboot -s $SERIAL set_active a; then rerun"

    if [ $VIAEDL = 1 ]; then
        list=(boot_a)
        [ $VENDOR = 1 ] && list+=(vendor_a)
        list+=(system_a)
        [ $RELOCK = 1 ] && list+=("abl_a, abl_b (stock)" "frp (cleared)")
        list+=("misc (recovery --wipe_data: the next boot deletes all user data)")
        confirm restore "About to write your stock backup to this phone over EDL:
$(writes "${list[@]}")"
        edl_write_verify boot_a "$(img boot_a)"
        [ $VENDOR = 1 ] && edl_write_verify vendor_a "$(img vendor_a)"
        say "writing system_a and reading it back"
        edl_write_verify system_a "$(img system_a)"
    elif [ $RELOCK = 1 ]; then
        confirm restore "About to write the stock abl_a and abl_b from your backup, a cleared frp and the recovery wipe request to misc, over EDL."
    fi
    if [ $RELOCK = 1 ]; then
        edl_write_verify abl_a "$(img abl_a)"
        edl_write_verify abl_b "$(img abl_b)"
        # The backup's frp carries Factory Reset Protection from backup time; Android reformats a zeroed frp.
        head -c 524288 /dev/zero > "$WORK/frp-clear.bin"
        edl_write_verify frp "$WORK/frp-clear.bin"
    fi

    if [ -n "${nvparts[*]:-}" ]; then
        cat >&2 <<EOF

!!! WARNING: per-unit NV partitions !!!
These hold the IMEI, radio calibration and per-unit keys. Writing them is
only safe from a backup of this same phone, which the unit check above has
confirmed. A bad write can leave the phone without IMEI or SIM detection
for good. Write them only if the radio or Wi-Fi is broken and the backup is
known good.
EOF
        confirm RESTORE-NV "About to write: ${nvparts[*]}"
        for p in "${nvparts[@]}"; do
            edl_write_verify "$p" "$(img "$p")"
        done
    fi
    # Last write: flashing lock and NV writes must not leave misc behind.
    edl_write_verify misc "$MISC_WIPE"
    say "leaving EDL"
    E_reset
fi

for p in "${parts[@]}" ${nvparts[@]+"${nvparts[@]}"}; do
    [ -f "$BACKUP/$p.bin" ] || rm -f "$WORK/$p.bin"
done
rm -f "$WORK/gpt.txt"
cat <<EOF

Restore done. On the next boot stock recovery formats userdata, then stock
Android 10 starts setup. A warning
that the device "can't be trusted and may not work properly" can appear;
press Power to continue. Then enable USB debugging again.
If the phone ends up in fastboot instead, see docs/troubleshooting.md#phone-ends-in-fastboot.
EOF
