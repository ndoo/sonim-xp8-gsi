#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Check a booted GSI over adb, without root. Prints a PASS/FAIL table and the
# checks that need a person. Exit status 0 only when every automatic check passes.
#
# usage: SERIAL=... scripts/verify-device.sh [--fix-webview] [--wait-unlock SECONDS]
#   --fix-webview        if no WebView provider is set, set com.android.webview
#   --wait-unlock N      wait up to N s for the screen unlock with the PIN (default 120)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,10s/^# \{0,1\}//p' "$0" >&2; exit 2; }

FIXWV=0 UNLOCKWAIT=120
while [ $# -gt 0 ]; do
    case $1 in
        --fix-webview) FIXWV=1; shift ;;
        --wait-unlock) UNLOCKWAIT=$2; shift 2 ;;
        -h|--help) usage ;;
        *) die "unknown argument $1" ;;
    esac
done

require_serial
need_tools adb fastboot
check_single
state=$(adb_state)
case $state in
    device) ;;
    unauthorized) die "adb is not authorized: unlock the phone, accept the prompt and tick 'Always allow from this computer'" ;;
    *) die "$SERIAL is not in adb (state: ${state:-absent}). After a wipe adb is off: enable USB debugging in Developer options" ;;
esac

sh_() { A shell "$@" 2>/dev/null | tr -d '\r'; }
prop() { sh_ getprop "$1"; }

RESULTS=() FAILS=0
record() { # status name detail
    RESULTS+=("$(printf '%-4s  %-28s %s' "$1" "$2" "$3")")
    [ "$1" = FAIL ] && FAILS=$((FAILS + 1))
    return 0
}
check() { # name detail command...
    local name=$1 detail=$2; shift 2
    if "$@"; then record PASS "$name" "$detail"; else record FAIL "$name" "$detail"; fi
}

say "waiting for boot to complete"
for ((i = 0; i < 300; i++)); do
    [ "$(prop sys.boot_completed)" = 1 ] && break
    sleep 1
done
check "boot completed" "sys.boot_completed=$(prop sys.boot_completed)" test "$(prop sys.boot_completed)" = 1

crypto=$(prop ro.crypto.type)
check "file-based encryption" "ro.crypto.type=$crypto" test "$crypto" = file

if [ "$(prop sys.user.0.ce_available)" != true ]; then
    say "user storage is locked: unlock the phone with your PIN now (waiting up to $UNLOCKWAIT s)"
    for ((i = 0; i < UNLOCKWAIT; i++)); do
        [ "$(prop sys.user.0.ce_available)" = true ] && break
        sleep 1
    done
fi
ce=$(prop sys.user.0.ce_available)
check "user 0 unlocked (CE keys)" "sys.user.0.ce_available=${ce:-unset}" test "$ce" = true

sdcard_ok() { sh_ sm list-volumes | grep -q 'emulated;0 mounted' || sh_ ls -d /sdcard/Download >/dev/null; }
check "/sdcard mounted" "emulated;0" sdcard_ok

sim=$(prop gsm.sim.state)
check "SIM loaded" "gsm.sim.state=$sim" grep -q LOADED <<< "$sim"

# Only the "last known state" block: the log sections after it hold old states.
svc=$(sh_ dumpsys telephony.registry | awk '
    /^local logs:/ {exit}
    /Phone Id=/ {sub(/.*Phone Id=/, ""); id = $1}
    /mServiceState=/ && match($0, /mDataRegState=[0-9]+\([A-Z_]+\)/) {
        printf "%sslot %s: %s", sep, id, substr($0, RSTART, RLENGTH); sep = ", "}' || true)
check "network registration" "${svc:-no service state}" grep -q IN_SERVICE <<< "$svc"

cell=$(A shell dumpsys connectivity 2>/dev/null | grep -E 'Transports: CELLULAR' || true)
check "mobile data validated" "CELLULAR network with VALIDATED" grep -q VALIDATED <<< "$cell"

ping_ok() { sh_ ping -c 3 -W 5 8.8.8.8 | grep -qE ', [1-9][0-9]* received'; }
check "ping 8.8.8.8" "default network (turn Wi-Fi off to test mobile data)" ping_ok

ims=$(sh_ pidof org.codeaurora.ims || true)
check "IMS service running" "org.codeaurora.ims${ims:+ pid $ims}" test -n "$ims"

omx=$(prop init.svc.vendor.media.omx)
check "media codecs (OMX)" "init.svc.vendor.media.omx=${omx:-unset}" test "$omx" = running

vib=$(sh_ service check android.hardware.vibrator.IVibrator/default)
check "vibrator HAL" "IVibrator/default" grep -q ': found' <<< "$vib"

xtra=$(sh_ pidof xtra-daemon || true)
check "XTRA daemon (GPS assist)" "xtra-daemon${xtra:+ pid $xtra}" test -n "$xtra"

wv() { A shell dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package' | tr -d '\r'; }
wvline=$(wv)
if grep -q 'is null' <<< "$wvline" && [ $FIXWV = 1 ]; then
    say "setting the WebView provider to com.android.webview"
    A shell cmd webviewupdate set-webview-implementation com.android.webview >/dev/null 2>&1 || true
    wvline=$(wv)
fi
wv_ok() { [ -n "$wvline" ] && ! grep -q 'is null' <<< "$wvline"; }
check "WebView provider set" "${wvline:-no output}" wv_ok

prov=$(sh_ pm path com.android.provision || true)
check "AOSP Provision hidden" "pm path com.android.provision ${prov:-empty}" test -z "$prov"

echo
echo "Automatic checks on $SERIAL"
printf '%s\n' "${RESULTS[@]}"
echo
echo "Information (not checked):"
printf '  %-28s %s\n' "build" "$(prop ro.build.display.id)" \
    "verified boot state" "$(prop ro.boot.verifiedbootstate)" \
    "SELinux" "$(sh_ getenforce)" \
    "adb secure" "ro.adb.secure=$(prop ro.adb.secure)" \
    "root (su in PATH)" "$(sh_ 'command -v su' || true)"

if grep -q 'WebView provider set' <<< "$(printf '%s\n' "${RESULTS[@]}" | grep '^FAIL')"; then
    echo
    echo "Fix the WebView provider with:"
    echo "  adb -s $SERIAL shell cmd webviewupdate set-webview-implementation com.android.webview"
    echo "or rerun with --fix-webview."
fi

cat <<'EOF'

Check by hand (these need a person):
  [ ] VoLTE call out and in: the call connects and the status bar shows the HD icon
  [ ] SMS: send one and receive one
  [ ] Speaker: ringtone on an incoming call, and media playback
  [ ] Vibration: a vibrating notification or keyboard haptics
  [ ] Fingerprint: enrol a finger (sensor in the Home button) and unlock with it
  [ ] Location: Google Maps shows the blue dot
EOF

echo
if [ "$FAILS" = 0 ]; then
    say "all automatic checks passed"
else
    say "$FAILS automatic check(s) failed"
    exit 1
fi
