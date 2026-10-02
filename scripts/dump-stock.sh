#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Full EDL backup of a stock XP8: every partition except userdata, system_b,
# vendor_a and vendor_b, plus system_a; SHA-256 of each raw image; zstd.
# The backup holds your IMEI/NV data and device keys: keep it private and offline.
#
# usage: SERIAL=... EDL_LOADER=... scripts/dump-stock.sh [options] [BACKUP_DIR]
#   BACKUP_DIR           new or empty directory (default: backups/stock)
#   --with-vendor        also read vendor_a (1 GiB, an empty filesystem on stock)
#   --no-adb             the phone is already in EDL; skip the stock checks over adb
#   --skip-sim-check     continue when the SIM or mobile data does not work on stock
#   --allow-other-build  continue on a stock build other than the tested one
#   EDL                  edl command (default: edl)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,15s/^# \{0,1\}//p' "$0" >&2; exit 2; }

BACKUP=$ROOT/backups/stock VENDOR=0 NOADB=0 SKIPSIM=0 OTHERBUILD=0
while [ $# -gt 0 ]; do
    case $1 in
        --with-vendor) VENDOR=1; shift ;;
        --no-adb) NOADB=1; shift ;;
        --skip-sim-check) SKIPSIM=1; shift ;;
        --allow-other-build) OTHERBUILD=1; shift ;;
        -h|--help) usage ;;
        -*) die "unknown option $1" ;;
        *) BACKUP=$1; shift ;;
    esac
done

require_serial
need_tools adb fastboot zstd awk
edl_setup
mkdir -p "$BACKUP"
BACKUP=$(cd "$BACKUP" && pwd)
if compgen -G "$BACKUP/*.bin*" >/dev/null; then
    die "$BACKUP already holds images; choose a new directory so an existing backup is never overwritten"
fi
"$ROOT/scripts/check-space.sh" assemble "$BACKUP"

check_single
if [ $NOADB = 0 ]; then
    state=$(adb_state)
    [ "$state" = device ] || die "$SERIAL is not in adb (state: ${state:-absent}); enable USB debugging and accept this computer, or use --no-adb"
    model=$(A shell getprop ro.product.model | tr -d '\r')
    [ "$model" = XP8800 ] || die "ro.product.model is '$model', not XP8800"
    fp=$(A shell getprop ro.build.fingerprint | tr -d '\r')
    rel=$(A shell getprop ro.build.version.release | tr -d '\r')
    bid=$(A shell getprop ro.build.id | tr -d '\r')
    printf '%s\n' "$fp" > "$BACKUP/stock-build.txt"
    [ "$rel" = 10 ] || die "stock Android version is '$rel', not 10"
    if [ "$bid" != "$SUPPORTED_BUILD_ID" ]; then
        [ $OTHERBUILD = 1 ] || die "stock build '$bid' is not the tested build '$SUPPORTED_BUILD_ID'; see README.md#supported-devices before using --allow-other-build"
        warn "untested stock build: $bid ($fp)"
    fi
    say "checking the SIM and mobile data (up to 60 s)"
    for ((i = 0; i < 12; i++)); do
        sim=$(A shell getprop gsm.sim.state 2>/dev/null | tr -d '\r' || true)
        if A shell dumpsys connectivity 2>/dev/null | grep -qE 'Transports: CELLULAR.*VALIDATED'; then data=1; else data=0; fi
        [[ $sim != *LOADED* || $data = 0 ]] || break
        [ $i = 11 ] || sleep 5
    done
    if [[ $sim != *LOADED* || $data = 0 ]]; then
        msg="on stock Android 10 the SIM state is '$sim' and mobile data is $([ "$data" = 1 ] && echo validated || echo 'not validated')"
        [ $SKIPSIM = 1 ] || die "$msg. The GSI does not fix a SIM or network that fails on stock. Fix it on stock first (turn Wi-Fi off to test data), or pass --skip-sim-check"
        warn "$msg"
    fi
    say "stock checks passed; rebooting to EDL"
    A reboot edl
fi

wait_edl
GPT=$BACKUP/gpt.txt
edl_identify "$BACKUP" "$GPT"
printf '%s\n' "$UNIT_ID" > "$BACKUP/UNIT_ID"
say "unit id $UNIT_ID saved (a hash; restore-stock.sh checks it before writing per-unit partitions)"

required=(boot_a system_a abl_a frp misc devinfo "${NV_PARTS[@]}")
for p in "${required[@]}"; do
    part_len "$GPT" "$p" >/dev/null || die "partition $p is missing from the partition table; this is not the expected layout, stop"
done

say "reading all partitions except userdata, system_*, vendor_* (about 3 GiB)"
E rl "$BACKUP" --skip=userdata,system_a,system_b,vendor_a,vendor_b --genxml
say "reading system_a (4 GiB)"
E r system_a "$BACKUP/system_a.bin"
if [ $VENDOR = 1 ]; then
    say "reading vendor_a (1 GiB)"
    E r vendor_a "$BACKUP/vendor_a.bin"
fi
say "leaving EDL; the phone reboots to stock Android"
E_reset

for p in "${required[@]}"; do
    f=$BACKUP/$p.bin
    [ -f "$f" ] || die "$p.bin missing from the backup; rerun into a new directory"
    [ "$(fsize "$f")" = "$(part_len "$GPT" "$p")" ] || die "$p.bin has the wrong size; rerun into a new directory"
done

say "computing SHA-256 of the raw images"
(cd "$BACKUP" && for f in *.bin *.xml; do [ -f "$f" ] && printf '%s  %s\n' "$(sha256 "$f")" "$f"; done) > "$BACKUP/SHA256SUMS"

say "compressing with zstd -19"
for f in "$BACKUP"/*.bin; do
    if [ "$(fsize "$f")" -gt 67108864 ]; then
        say "compressing $(basename "$f")"
        zstd -19 -T0 --rm "$f"
    else
        zstd -q -19 -T0 --rm "$f"
    fi
done

say "verifying the compressed images"
bad=0
while read -r sum name; do
    case $name in *.bin) ;; *) continue ;; esac
    got=$(zstd -q -dc "$BACKUP/$name.zst" | sha256_stdin)
    [ "$got" = "$sum" ] || { echo "MISMATCH $name" >&2; bad=1; }
done < "$BACKUP/SHA256SUMS"
[ $bad = 0 ] || die "compressed images do not match; do not continue, make a new backup"

du -sh "$BACKUP"
say "backup OK: $BACKUP"
cat <<EOF

Next:
  - Copy $BACKUP to a second, offline location now. It holds your IMEI/NV
    data (modemst1, modemst2, fsg, fsc), device keys and persist. Never
    publish it or share it.
  - Bootloader unlock: scripts/unlock.sh (docs/install.md step 2).
EOF
