#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Bootloader unlock without root. In one EDL session: write the AT&T 8.1
# userdebug ABL to abl_a and set the OEM-unlock byte in frp (with a matching
# checksum, so Android keeps it); then reboot to fastboot and run
# `fastboot flashing unlock`. Wipes userdata.
#
# usage: SERIAL=... EDL_LOADER=... scripts/unlock.sh --abl abl.elf [--yes] BACKUP_DIR
#   --abl FILE   abl.elf from the AT&T userdebug image (110592 bytes, SHA-256 checked)
#   --yes        the user has approved the EDL writes and the unlock; skip the prompts
#   BACKUP_DIR   your backup from scripts/dump-stock.sh (needed to identify the unit)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,13s/^# \{0,1\}//p' "$0" >&2; exit 2; }

ABL='' BACKUP=''
while [ $# -gt 0 ]; do
    case $1 in
        --abl) ABL=$2; shift 2 ;;
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        -*) die "unknown option $1" ;;
        *) [ -z "$BACKUP" ] || usage; BACKUP=$1; shift ;;
    esac
done
if [ -z "$ABL" ] || [ -z "$BACKUP" ]; then usage; fi

require_serial
need_tools adb fastboot zstd cmp dd awk
edl_setup
[ -f "$ABL" ] || die "no such file: $ABL"
[ "$(sha256 "$ABL")" = "$ABL_SHA256" ] || die "$ABL has the wrong SHA-256; expected $ABL_SHA256"
BACKUP=$(cd "$BACKUP" && pwd)
[ -f "$BACKUP/SHA256SUMS" ] || die "$BACKUP/SHA256SUMS missing"
for p in abl_a frp; do
    grep -qE "  \*?$p\.bin$" "$BACKUP/SHA256SUMS" || die "$BACKUP has no $p.bin; it is needed to undo the unlock"
done

WORK=$(mktemp -d "${TMPDIR:-/tmp}/xp8-unlock.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
OUTDIR=$BACKUP/unlock-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUTDIR"

PADDED=$WORK/abl_a_padded.img
{ cat "$ABL"; head -c $((1048576 - $(fsize "$ABL"))) /dev/zero; } > "$PADDED"
[ "$(sha256 "$PADDED")" = "$ABL_PADDED_SHA256" ] || die "padded ABL has an unexpected SHA-256"
STOCK_ABL=$(awk '$2 ~ /^\*?abl_a\.bin$/ {print $1; exit}' "$BACKUP/SHA256SUMS")

check_single
if in_fastboot; then
    if [ "$(fb_var unlocked)" = yes ]; then say "$SERIAL is already unlocked"; exit 0; fi
    die "$SERIAL is in fastboot; boot Android (or enter EDL by keys) and rerun"
fi
if in_adb; then
    say "rebooting $SERIAL to EDL"
    A reboot edl
else
    say "$SERIAL is not in adb; enter EDL by keys (power off, hold Vol+ and Vol-, press Power)"
fi
wait_edl
GPT=$OUTDIR/gpt.txt
edl_identify "$OUTDIR" "$GPT"
check_unit
[ "$(part_len "$GPT" abl_a)" = 1048576 ] || die "abl_a is not 1 MiB; unexpected partition layout, stop"
[ "$(part_len "$GPT" frp)" = 524288 ] || die "frp is not 512 KiB; unexpected partition layout, stop"

say "reading abl_a and frp"
edl_read abl_a "$OUTDIR/abl_a-before.bin"
edl_read frp "$OUTDIR/frp-before.bin"
cur=$(sha256 "$OUTDIR/abl_a-before.bin")
if [ "$cur" = "$ABL_PADDED_SHA256" ]; then
    WRITE_ABL=0
    say "abl_a already holds the userdebug ABL"
elif [ "$cur" = "$STOCK_ABL" ]; then
    WRITE_ABL=1
else
    die "abl_a matches neither your stock backup nor the userdebug ABL; stop and find out why"
fi

# Android's PersistentDataBlockService reformats frp, clearing the byte, when
# its checksum (SHA-256 of 32 zero bytes + bytes 32..end) does not match.
py3 - "$OUTDIR/frp-before.bin" "$WORK/frp-unlock.bin" <<'PY'
import hashlib, sys
d = bytearray(open(sys.argv[1], "rb").read())
d[-1] = 1
if d[32:36] == bytes.fromhex("19901873"):
    d[0:32] = hashlib.sha256(bytes(32) + bytes(d[32:])).digest()
open(sys.argv[2], "wb").write(d)
PY
diffs=$(cmp -l "$OUTDIR/frp-before.bin" "$WORK/frp-unlock.bin" || true)
[ -z "$diffs" ] || [ -z "$(awk '$1 > 32 && $1 != 524288' <<< "$diffs")" ] || die "unexpected frp difference: $diffs"

confirm write "About to write over EDL to $SERIAL:
  abl_a  $([ "$WRITE_ABL" = 1 ] && echo 'userdebug ABL, zero-padded to 1 MiB' || echo '(unchanged)')
  frp    last byte (offset 524287) set to 0x01, checksum updated
Nothing else is written. The previous abl_a and frp are saved in $OUTDIR."

if [ "$WRITE_ABL" = 1 ]; then
    edl_write_verify abl_a "$PADDED"
fi
if [ -z "$diffs" ]; then
    say "frp unlock byte is already 0x01"
else
    edl_write_verify frp "$WORK/frp-unlock.bin"
fi

say "resetting; the phone boots Android or fastboot, and the script continues either way"
E_reset

for ((i = 0; i < 300; i++)); do
    in_fastboot && break
    if in_adb; then
        say "Android booted; rebooting to fastboot"
        A reboot bootloader
    fi
    sleep 1
done
in_fastboot || die "$SERIAL did not reach fastboot. If Android is at a warning screen, press Power; with USB debugging on, rerun this script (abl_a is not written again). If the screen stays black, see docs/troubleshooting.md#no-fastboot-after-the-abl-write"
check_single

if [ "$(fb_var unlocked)" = yes ]; then say "already unlocked"; exit 0; fi
ability=$(F flashing get_unlock_ability 2>&1 | sed -n 's/.*get_unlock_ability: *\([0-9]\).*/\1/p' | head -n 1)
case $ability in
    1) say "get_unlock_ability: 1" ;;
    0) die "get_unlock_ability is 0: the frp byte was not read or was cleared. Boot to EDL by keys and rerun, or use the Magisk route (docs/troubleshooting.md#unlock-get_unlock_ability-stays-0)" ;;
    *) die "fastboot flashing get_unlock_ability gave no answer; is abl_a the userdebug ABL? See docs/troubleshooting.md#no-fastboot-after-the-abl-write" ;;
esac

confirm unlock "Unlocking erases all user data on the phone and turns off verified boot."
say "running fastboot flashing unlock; on the phone, select Unlock with a volume key and press Power"
F flashing unlock
cat <<EOF

When confirmed, the phone wipes userdata and reboots to stock Android 10
with an orange "unlocked" warning. Check with:
  fastboot -s \$SERIAL getvar unlocked      # unlocked: yes  (from fastboot: power off, hold Vol-, press Power)
Saved pre-unlock abl_a and frp: $OUTDIR
Next: docs/install.md step 3 (choose root or no root).
EOF
