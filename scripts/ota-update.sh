#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Install a release's A/B OTA payload with update_engine on the running phone:
# it writes system to the other slot, copies boot and vendor across, and makes
# that slot active for the next boot. User data is kept. Needs a vendor image
# of the layout ota.json names, which flash.sh writes after preparing slot b.
#
# usage: SERIAL=... scripts/ota-update.sh [--json URL|FILE] [--work DIR] [--yes]
#   --json URL|FILE  ota.json of the release to install (default: the latest release)
#   --work DIR       download directory, about 1.2 GB (default: work/ota)
#   --yes            the user has approved the update and the reboot; skip the prompts
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,13s/^# \{0,1\}//p' "$0" >&2; exit 2; }

JSON=https://github.com/ndoo/sonim-xp8-gsi/releases/latest/download/ota.json
WORK=$ROOT/work/ota
while [ $# -gt 0 ]; do
    case $1 in
        --json) JSON=$2; shift 2 ;;
        --work) WORK=$2; shift 2 ;;
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        *) die "unknown argument $1" ;;
    esac
done

require_serial
need_tools adb curl
mkdir -p "$WORK"
if [ -f "$JSON" ]; then [ "$JSON" -ef "$WORK/ota.json" ] || cp "$JSON" "$WORK/ota.json"
else curl -fsSL -o "$WORK/ota.json" "$JSON" || die "cannot fetch $JSON"; fi
{ read -r TAG; read -r URL; read -r SIZE; read -r SUM; read -r LAYOUT; } < <(py3 - "$WORK/ota.json" "$WORK/headers" <<'PY'
import json, sys
j = json.load(open(sys.argv[1]))
p = j["payload"]
open(sys.argv[2], "w").write(p["headers"].strip() + "\n")
print(j["tag"], p["url"], p["size"], p["sha256"], j.get("min_vendor_layout", 1), sep="\n")
PY
)
PAYLOAD=$WORK/$(basename "$URL")

if [ -f "$PAYLOAD" ] && [ "$(fsize "$PAYLOAD")" = "$SIZE" ] && [ "$(sha256 "$PAYLOAD")" = "$SUM" ]; then
    say "$(basename "$PAYLOAD"): already downloaded, checksum OK"
else
    say "downloading $TAG payload ($SIZE bytes)"
    if [ -t 2 ]; then curl -fL --retry 3 --progress-bar -o "$PAYLOAD.part" "$URL"
    else curl -fL --retry 3 -sS -o "$PAYLOAD.part" "$URL"; fi
    [ "$(sha256 "$PAYLOAD.part")" = "$SUM" ] || { rm -f "$PAYLOAD.part"; die "payload SHA-256 does not match ota.json"; }
    mv "$PAYLOAD.part" "$PAYLOAD"
    say "payload checksum OK"
fi

check_single
in_adb || die "$SERIAL is not booted with USB debugging on (adb devices)"
prop() { A shell getprop "$1" | tr -d '\r'; }
CUR=$(prop ro.boot.slot_suffix)
case $CUR in _a) NEXT=b ;; _b) NEXT=a ;; *) die "no ro.boot.slot_suffix on $SERIAL; stop" ;; esac
[[ ",$(prop ro.vendor.build.ab_ota_partitions)," == *,boot,*vendor,* ]] ||
    die "this vendor image has no A/B OTA support (ro.vendor.build.ab_ota_partitions); assemble and flash this release's vendor first"
[ "$(prop ro.vendor.xp8.layout | grep -x '[0-9]*' || echo 1)" -ge "$LAYOUT" ] ||
    die "$TAG needs a newer vendor image (ro.vendor.xp8.layout $LAYOUT); update from a computer once (docs/install.md#update-to-a-newer-release), which also prepares slot b; then OTA works again"
avail=$(A shell df -k /data | awk 'NR == 2 {print $4}' | tr -d '\r')
[ "${avail:-0}" -gt $((SIZE / 1024 + 524288)) ] || die "/data has ${avail:-0} KiB free; the payload needs $((SIZE / 1024)) KiB plus 512 MiB"

D=/data/local/tmp/xp8-ota
confirm update "About to install $TAG on $SERIAL with update_engine:
  system_$NEXT  from the payload
  boot_$NEXT, vendor_$NEXT  copied from slot ${CUR#_}
  then slot $NEXT becomes active for the next boot; slot ${CUR#_} stays as it is.
User data is kept."

A shell "rm -rf $D && mkdir -p $D"
A push "$PAYLOAD" "$D/payload.bin"
A push "$WORK/headers" "$D/headers"
A shell "cat > $D/run.sh" <<EOF
exec update_engine_client --update --follow --payload=file://$D/payload.bin --headers="\$(cat $D/headers)"
EOF
say "running update_engine; it reports progress until the slot is written"
log=$WORK/update_engine.log
if ! A shell "sh $D/run.sh" 2>&1 | tee "$log" | grep --line-buffered -E 'status|progress|ErrorCode|rror'; then :; fi
if grep -q 'Permission\|not allowed\|denied' "$log"; then
    say "update_engine refused the shell user; retrying as root (grant Shell in Magisk if asked)"
    A shell "su -c 'sh $D/run.sh'" 2>&1 | tee "$log" | grep --line-buffered -E 'status|progress|ErrorCode|rror' || true
fi
A shell "rm -rf $D"
grep -q UPDATED_NEED_REBOOT "$log" ||
    die "update_engine did not finish; nothing changes at the next boot. Log: $log (also: adb logcat -s update_engine)"

say "slot $NEXT is written and active for the next boot"
confirm reboot "Reboot $SERIAL into slot $NEXT now?"
A reboot
wait_adb 300 || die "$SERIAL did not come back over adb; if it is stuck, after 7 failed boots the bootloader returns to slot ${CUR#_}"
sleep 10
got=$(prop ro.boot.slot_suffix)
[ "$got" = "_$NEXT" ] || die "running slot is $got, not _$NEXT; the bootloader fell back. Log: $log"
say "running $TAG from slot $NEXT. Next: SERIAL=$SERIAL scripts/verify-device.sh"
