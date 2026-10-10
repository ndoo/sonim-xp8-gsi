#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Prepare slot b for A/B updates over fastboot: write the userdebug ABL to
# abl_b, and copy mdtpsecapp and modem from slot a (taken from your backup)
# to mdtpsecapp_b and modem_b. Every other slot-b firmware partition already
# equals slot a on the supported stock build. User data is not touched.
# scripts/flash.sh does the same writes; this script needs no GSI images.
#
# usage: SERIAL=... scripts/enable-ab.sh --abl abl.elf [--dry-run] [--yes] BACKUP_DIR
#   --abl FILE   abl.elf from the AT&T userdebug image (the one scripts/unlock.sh wrote)
#   --dry-run    check the inputs and print the writes; do not contact the phone
#   --yes        the user has approved these writes; skip the prompt
#   BACKUP_DIR   your backup from scripts/dump-stock.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,15s/^# \{0,1\}//p' "$0" >&2; exit 2; }

ABL='' BACKUP='' DRY=0
while [ $# -gt 0 ]; do
    case $1 in
        --abl) ABL=$2; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        -*) die "unknown option $1" ;;
        *) [ -z "$BACKUP" ] || usage; BACKUP=$1; shift ;;
    esac
done
if [ -z "$ABL" ] || [ -z "$BACKUP" ]; then usage; fi

need_tools zstd awk
[ $DRY = 1 ] || { require_serial; need_tools adb fastboot; }
BACKUP=$(cd "$BACKUP" && pwd)

WORK=$(mktemp -d "${TMPDIR:-/tmp}/xp8-enable-ab.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
slot_b_inputs "$ABL"

plan="About to write to ${SERIAL:-the phone} over fastboot:
$SLOTB_PLAN
Slot a, userdata and the NV partitions are not written. The active slot stays a."
if [ $DRY = 1 ]; then
    echo "$plan"
    say "dry run: nothing written"
    exit 0
fi

check_single
to_fastboot
[ "$(fb_var unlocked)" = yes ] || die "bootloader is not unlocked (fastboot getvar unlocked); run scripts/unlock.sh first"
[ "$(fb_var current-slot)" = a ] || die "current slot is not a; run this from slot a"
confirm write "$plan"

slot_b_write

cat <<EOF
$PROG: slot b firmware is ready. Reboot with: fastboot -s $SERIAL reboot
EOF
