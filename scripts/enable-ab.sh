#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Prepare slot b for A/B updates, once, over fastboot: write the userdebug ABL
# to abl_b, and copy mdtpsecapp and modem from slot a (taken from your backup)
# to mdtpsecapp_b and modem_b. Every other slot-b firmware partition already
# equals slot a on the supported stock build. User data is not touched.
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
[ -f "$ABL" ] || die "no such file: $ABL"
[ "$(sha256 "$ABL")" = "$ABL_SHA256" ] || die "$ABL has the wrong SHA-256; expected $ABL_SHA256"
BACKUP=$(cd "$BACKUP" && pwd)
[ -f "$BACKUP/SHA256SUMS" ] || die "$BACKUP/SHA256SUMS missing"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/xp8-enable-ab.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

PADDED=$WORK/abl_padded.img
{ cat "$ABL"; head -c $((1048576 - $(fsize "$ABL"))) /dev/zero; } > "$PADDED"
[ "$(sha256 "$PADDED")" = "$ABL_PADDED_SHA256" ] || die "padded ABL has an unexpected SHA-256"
backup_image mdtpsecapp_a
MDTP=$IMG
backup_image modem_a
MODEM=$IMG
if [ "$(sha256 "$MDTP")" != "$MDTPSECAPP_A_SHA256" ] || [ "$(sha256 "$MODEM")" != "$MODEM_A_SHA256" ]; then
    die "mdtpsecapp_a or modem_a in $BACKUP is not from the supported build $SUPPORTED_BUILD_ID; stop"
fi

plan="About to write to ${SERIAL:-the phone} over fastboot:
  abl_b         userdebug ABL, zero-padded to 1 MiB (the image abl_a holds)
  mdtpsecapp_b  mdtpsecapp_a from $BACKUP
  modem_b       modem_a from $BACKUP
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

flash_settle abl_b "$PADDED"
flash_settle mdtpsecapp_b "$MDTP"
flash_settle modem_b "$MODEM"
[ "$(fb_var current-slot)" = a ] || die "current slot changed; stop"

cat <<EOF
$PROG: slot b firmware is ready. Next, write the GSI to slot b and switch:
  SERIAL=$SERIAL scripts/flash.sh --slot b --activate
To go back to slot a: SERIAL=$SERIAL scripts/flash.sh --switch a
EOF
