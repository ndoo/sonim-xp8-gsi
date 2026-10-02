# shellcheck shell=bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Helpers shared by the device scripts: serial pinning, EDL access, checksums,
# confirmations. Source it; it defines functions and constants only.

# shellcheck disable=SC2034  # used by the scripts that source this file
LOADER_SHA256=d25b298ca36f467c3e30293e25492f08ea4831b4e98140313ff9ebca065c59b2
ABL_SHA256=7e6145d80b9fb46b7a9fdc326bd00d9593c21e3bd7929490abeb967bd9272648
ABL_PADDED_SHA256=e2fa7b0e8254ca4c9621658d232caba7ca21e1ae846ce5979a27dbea9679a35a
ZERO_MISC_SHA256=30e14955ebf1352266dc2ff8067e68104607e750abb9d3b36582b8af909fcb58
SUPPORTED_BUILD_ID='8A.0.0-03-10.0.0-00.40.00'
NV_PARTS=(modemst1 modemst2 fsg fsc persist)

PROG=${PROG:-$(basename "$0" .sh)}
YES=${YES:-0}

die() { echo "$PROG: $*" >&2; exit 1; }
say() { echo "$PROG: $*"; }
warn() { echo "$PROG: warning: $*" >&2; }

need_tools() {
    local t
    for t in "$@"; do command -v "$t" >/dev/null || die "missing tool: $t"; done
}

sha256() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

sha256_stdin() {
    if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1
    else shasum -a 256 | cut -d' ' -f1; fi
}

fsize() { wc -c < "$1" | tr -d ' '; }

is_windows() { case $(uname -s) in MINGW*|MSYS*|CYGWIN*) return 0 ;; *) return 1 ;; esac; }

# On Windows python3 can be the Microsoft Store stub, which runs nothing.
py3() { if python3 -c '' 2>/dev/null; then python3 "$@"; else python "$@"; fi; }

# Ask the user to type WORD. --yes (YES=1) means the user already approved this step.
confirm() {
    local word=$1 msg=$2 ans
    echo
    echo "$msg"
    if [ "$YES" = 1 ]; then
        say "confirmed by --yes"
        return 0
    fi
    [ -t 0 ] || die "no terminal to confirm on; rerun interactively, or pass --yes once the user has approved this step"
    read -r -p "Type '$word' to continue, anything else stops: " ans
    [ "$ans" = "$word" ] || die "stopped; nothing more was written"
}

require_serial() {
    [ -n "${SERIAL:-}" ] || die "set SERIAL to your phone's serial number (the first column of 'adb devices' or 'fastboot devices')"
}

# Refuse when any adb or fastboot device other than SERIAL is attached.
check_single() {
    local n
    n=$( { adb devices 2>/dev/null | awk 'NR > 1 && NF >= 2 {print $1}'
           fastboot devices 2>/dev/null | awk 'NF >= 2 {print $1}'; } |
         grep -cvxF -- "$SERIAL" || true)
    [ "$n" = 0 ] || die "$n other adb/fastboot device(s) attached; disconnect every phone except $SERIAL"
}

adb_state() { adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' || true; }
in_adb() { [ "$(adb_state)" = device ]; }
in_fastboot() { fastboot devices 2>/dev/null | awk '{print $1}' | grep -qxF -- "$SERIAL"; }
# Git Bash would rewrite phone paths such as /sdcard into Windows paths.
A() { MSYS_NO_PATHCONV=1 adb -s "$SERIAL" "$@"; }
F() { fastboot -s "$SERIAL" "$@"; }

# getvar output goes to stderr as "name: value".
fb_var() {
    F getvar "$1" 2>&1 | sed -n "s/^\(([a-z]*) \)\{0,1\}$1: *//p" | head -n 1 | tr -d '\r'
}

wait_fastboot() {
    local t=${1:-90} i
    for ((i = 0; i < t; i++)); do
        in_fastboot && return 0
        sleep 1
    done
    return 1
}

wait_adb() {
    local t=${1:-120} i
    for ((i = 0; i < t; i++)); do
        in_adb && return 0
        sleep 1
    done
    return 1
}

to_fastboot() {
    if in_fastboot; then return 0; fi
    if in_adb; then
        say "rebooting $SERIAL to fastboot"
        A reboot bootloader
    else
        say "phone $SERIAL is not in adb or fastboot; power it off, then hold Vol- and press Power"
    fi
    wait_fastboot 120 || die "$SERIAL did not appear in fastboot (fastboot devices); if another serial is listed, check SERIAL"
    check_single
}

# The ABL acks a write at once and keeps writing for about 1 s per 15 MB of image; a command
# sent before it finishes can get "unknown command" and leave fastboot stuck.
settle() {
    local secs=$(( $1 / 15000000 + 5 ))
    say "waiting ${secs} s for the phone to finish writing"
    sleep "$secs"
    for _ in 1 2 3 4 5 6; do
        [ "$(fb_var unlocked)" = yes ] && return 0
        sleep 10
    done
    die "fastboot stopped answering. Hold Power 10-15 s, enter fastboot again (hold Vol-, press Power) and rerun the script"
}
flash_settle() { F flash "$1" "$2"; settle "$(fsize "$2")"; }

# --- EDL (Qualcomm 9008) ---------------------------------------------------

edl_setup() {
    [ -n "${EDL_LOADER:-}" ] || die "set EDL_LOADER to the Sonim firehose loader prog_emmc_ufs_firehose_Sdm660_ddr.elf"
    [ -f "$EDL_LOADER" ] || die "no such file: $EDL_LOADER"
    [ "$(sha256 "$EDL_LOADER")" = "$LOADER_SHA256" ] ||
        die "$EDL_LOADER has the wrong SHA-256; expected $LOADER_SHA256"
    read -r -a EDL_CMD <<< "${EDL:-edl}"
    command -v "${EDL_CMD[0]}" >/dev/null ||
        die "edl not found; install bkerler/edl or set EDL (e.g. EDL=\"\$PWD/.venv/bin/edl\")"
    if [ "$(uname -s)" = Darwin ] && [ -z "${DYLD_FALLBACK_LIBRARY_PATH:-}" ]; then
        local d
        for d in /opt/homebrew/lib /usr/local/lib; do
            if [ -e "$d/libusb-1.0.dylib" ]; then export DYLD_FALLBACK_LIBRARY_PATH=$d; break; fi
        done
    fi
}

E() { "${EDL_CMD[@]}" --loader="$EDL_LOADER" --memory=emmc "$@"; }
# edl reset rejects --memory, and prints a USBError traceback as the phone disconnects.
E_reset() { "${EDL_CMD[@]}" --loader="$EDL_LOADER" reset >/dev/null 2>&1 || true; }

# One "svc:<driver service>" line per attached 9008 device.
win_9008_services() {
    # shellcheck disable=SC2016  # PowerShell variables
    MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -NonInteractive -Command \
        'Get-CimInstance Win32_PnPEntity | Where-Object { $_.DeviceID -like "USB\VID_05C6&PID_9008*" } | ForEach-Object { "svc:" + $_.Service }' |
        tr -d '\r'
}

count_9008() {
    case $(uname -s) in
        MINGW*|MSYS*|CYGWIN*)
            win_9008_services | grep -c '^svc:' || true ;;
        Darwin)
            ioreg -p IOUSB -l -w0 | awk '
                /"idProduct" = / {p = $NF}
                /"idVendor" = / {if ($NF == 1478 && p == 36872) n++; p = ""}
                END {print n + 0}' ;;
        *)
            local d n=0
            for d in /sys/bus/usb/devices/*; do
                [ "$(cat "$d/idVendor" 2>/dev/null)" = 05c6 ] &&
                    [ "$(cat "$d/idProduct" 2>/dev/null)" = 9008 ] && n=$((n + 1))
            done
            echo "$n" ;;
    esac
}

wait_edl() {
    local i n=0
    say "waiting for one Qualcomm 9008 (EDL) device"
    for ((i = 0; i < 300; i++)); do
        n=$(count_9008)
        [ "$n" -ge 1 ] && break
        sleep 1
    done
    [ "$n" -ge 1 ] || die "no 9008 device; enter EDL by keys (power off, hold Vol+ and Vol-, press Power). macOS: accept 'Allow accessory to connect'. Windows: see docs/windows.md#edl-driver"
    [ "$n" = 1 ] || die "$n Qualcomm 9008 devices attached; disconnect all but one"
    if is_windows; then
        local svc
        svc=$(win_9008_services | sed 's/^svc://')
        case $svc in
            WinUSB|libusbK|libusb0) ;;
            *) die "the 9008 device uses the driver '${svc:-none}'; edl needs WinUSB. Install it with Zadig: docs/windows.md#edl-driver" ;;
        esac
    fi
    sleep 2
}

# First command of an EDL session: needs the Sahara handshake, which reports
# the chip serial. Writes the masked GPT to $2 and sets UNIT_ID (a hash of the
# chip serial) without printing the serial.
edl_identify() {
    local dir=$1 gpt=$2 log serial
    log=$(mktemp "$dir/.edl.XXXXXX")
    if ! E printgpt > "$log" 2>&1; then
        sed -E 's/([Ss]erial[^:]*: *)(0x)?[0-9a-fA-F]+/\1<hidden>/' "$log" | tail -n 20 >&2
        rm -f "$log"
        die "edl printgpt failed"
    fi
    serial=$(sed -nE 's/.*(Chip Serial Number|Serial|Device serial) *: *(0x)?([0-9a-fA-F]+).*/\3/p' "$log" |
             head -n 1 | tr 'A-F' 'a-f')
    tr -d '\r' < "$log" | sed -E 's/([Ss]erial[^:]*: *)(0x)?[0-9a-fA-F]+/\1<hidden>/' > "$gpt"
    rm -f "$log"
    if [ -z "$serial" ]; then
        die "edl skipped the Sahara handshake (loader already running), so the unit cannot be identified; hold Power about 10 s, enter EDL again by keys and rerun"
    fi
    UNIT_ID=$(printf 'xp8-gsi-unit-v1:%s' "$serial" | sha256_stdin | cut -c1-16)
    grep -q 'Offset 0x' "$gpt" || die "no partition table in the edl output ($gpt)"
}

# Partition length in bytes from a printgpt listing.
part_len() {
    local hex
    hex=$(awk -v n="$2:" '$1 == n {gsub(",", "", $5); print $5; exit}' "$1")
    [ -n "$hex" ] || return 1
    echo $((hex))
}

# Read partition $1 to file $2 and check that it has the GPT length.
edl_read() {
    local len
    E r "$1" "$2" >/dev/null 2>&1 || true
    [ -f "$2" ] || die "edl read of $1 produced no file"
    len=$(part_len "$GPT" "$1") || die "$1 not in the partition table"
    [ "$(fsize "$2")" = "$len" ] || die "edl read of $1 returned $(fsize "$2") bytes, expected $len"
}

# Write file $2 to partition $1, read it back and compare the written length.
edl_write_verify() {
    local part=$1 file=$2 len n rb
    len=$(part_len "$GPT" "$part") || die "$part not in the partition table"
    n=$(fsize "$file")
    [ "$n" -le "$len" ] || die "$file ($n bytes) is larger than $part ($len bytes)"
    say "writing $part"
    E w "$part" "$file" || die "edl write of $part failed; do not reset the phone, see docs/troubleshooting.md"
    rb=$(mktemp "${WORK:-${TMPDIR:-/tmp}}/.rb.XXXXXX")
    say "reading $part back"
    edl_read "$part" "$rb"
    if [ "$(head -c "$n" "$rb" | sha256_stdin)" != "$(sha256 "$file")" ]; then
        rm -f "$rb"
        die "read-back of $part does not match $file; do not reset the phone, write it again or restore it from your backup"
    fi
    rm -f "$rb"
    say "$part written and verified"
}

# --- backups -----------------------------------------------------------------

# Raw image for NAME from BACKUP ($NAME.bin or .bin.zst) into $WORK, verified
# against BACKUP/SHA256SUMS. Sets IMG.
backup_image() {
    local name=$1 want got
    want=$(awk -v f="$name.bin" '$2 == f || $2 == "*" f {print $1; exit}' "$BACKUP/SHA256SUMS")
    [ -n "$want" ] || die "$BACKUP/SHA256SUMS has no entry for $name.bin"
    if [ -f "$BACKUP/$name.bin" ]; then
        IMG=$BACKUP/$name.bin
    elif [ -f "$BACKUP/$name.bin.zst" ]; then
        IMG=$WORK/$name.bin
        say "unpacking $name"
        zstd -q -d -f "$BACKUP/$name.bin.zst" -o "$IMG"
    else
        die "$BACKUP has no $name.bin or $name.bin.zst"
    fi
    got=$(sha256 "$IMG")
    [ "$got" = "$want" ] || die "$name: SHA-256 $got does not match $BACKUP/SHA256SUMS; the backup is damaged, stop"
    say "$name: checksum OK"
}

# misc image holding a bootloader message that starts recovery with --wipe_data.
make_wipe_bcb() {
    py3 - "$1" <<'PY'
import sys
m = bytearray(1048576)
m[0:13] = b"boot-recovery"
r = b"recovery\n--wipe_data\n"
m[64:64 + len(r)] = r
open(sys.argv[1], "wb").write(m)
PY
}

check_unit() {
    [ -f "$BACKUP/UNIT_ID" ] || die "$BACKUP/UNIT_ID missing; this backup was not made by scripts/dump-stock.sh"
    [ "$UNIT_ID" = "$(tr -d ' \n' < "$BACKUP/UNIT_ID")" ] ||
        die "the phone in EDL is not the unit this backup was made from; stop"
    say "unit matches the backup"
}
