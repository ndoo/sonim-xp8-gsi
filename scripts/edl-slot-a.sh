#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Make slot a active again over EDL, for a phone that an update moved to an
# unprepared slot b (stock ABL: fastboot has no set_active). Changes only the
# slot flags and partition type GUIDs in the GPT; no partition contents and no
# user data are written. Runs only when slot b is active.
#
# usage: EDL_LOADER=... scripts/edl-slot-a.sh [--yes] BACKUP_DIR
#   --yes        the user has approved the GPT change; skip the prompt
#   BACKUP_DIR   your backup from scripts/dump-stock.sh (identifies the unit)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=lib/device.sh
. "$ROOT/scripts/lib/device.sh"
usage() { sed -n '5,12s/^# \{0,1\}//p' "$0" >&2; exit 2; }

BACKUP=''
while [ $# -gt 0 ]; do
    case $1 in
        --yes) YES=1; shift ;;
        -h|--help) usage ;;
        -*) die "unknown option $1" ;;
        *) [ -z "$BACKUP" ] || usage; BACKUP=$1; shift ;;
    esac
done
[ -n "$BACKUP" ] || usage

need_tools awk
edl_setup
BACKUP=$(cd "$BACKUP" && pwd)
[ -f "$BACKUP/UNIT_ID" ] || die "$BACKUP/UNIT_ID missing; this backup was not made by scripts/dump-stock.sh"

# edl's setactiveslot needs edlclient's Python; EDL is the edl script or "python edl.py".
EDL_PY=() EDL_SRC=''
if [ "${#EDL_CMD[@]}" -ge 2 ] && [ "$(basename "${EDL_CMD[1]}")" = edl.py ]; then
    EDL_PY=("${EDL_CMD[0]}") EDL_SRC=$(cd "$(dirname "${EDL_CMD[1]}")" && pwd)
elif [ -n "${EDL_PYTHON:-}" ]; then
    EDL_PY=("$EDL_PYTHON")
else
    shebang=$(head -n 1 "$(command -v "${EDL_CMD[0]}")" | sed -n 's/^#! *//p')
    read -r -a EDL_PY <<< "$shebang"
    if [ "${#EDL_PY[@]}" = 0 ] || [ "$(basename "${EDL_PY[0]}")" = sh ]; then
        die "cannot find the Python that runs ${EDL_CMD[0]}; set EDL_PYTHON to it"
    fi
fi

OUTDIR=$BACKUP/slot-a-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUTDIR"
WORK=$OUTDIR

active_slot() {
    E getactiveslot 2>&1 | sed -n 's/.*Current active slot: *\([ab]\).*/\1/p' | head -n 1
}

set_slot_a() {
    (cd "$WORK" && "${EDL_PY[@]}" - "$EDL_LOADER" "$EDL_SRC" <<'PY'
import sys
loader, src = sys.argv[1], sys.argv[2]
if src:
    sys.path.insert(0, src)
# edl's setactiveslot usage line has no --memory: parse a getactiveslot line, then switch command.
sys.argv = ["edl", "getactiveslot", "--memory=emmc", "--loader=" + loader]
from edlclient import edl
a = dict(edl.args)
if "setactiveslot" not in a or "<slot>" not in a:
    sys.exit("this edl has no setactiveslot command")
a["getactiveslot"] = False
a["setactiveslot"] = True
a["<slot>"] = "a"
sys.exit(0 if edl.main(a).run() == 0 else 1)
PY
    )
}

say "enter EDL by keys: power off (hold Power about 10 s), hold Vol+ and Vol-, press Power"
wait_edl
GPT=$OUTDIR/gpt.txt
edl_identify "$OUTDIR" "$GPT"
check_unit

slot=$(active_slot)
case $slot in
    a) say "slot a is already active; nothing written. Hold Power about 10 s to leave EDL"; exit 0 ;;
    b) ;;
    *) die "edl getactiveslot reported no active slot; nothing written" ;;
esac

say "saving the current GPT"
E rs 0 20 "$OUTDIR/gpt_main0-before.bin" >/dev/null 2>&1 || true
[ "$(fsize "$OUTDIR/gpt_main0-before.bin" 2>/dev/null || echo 0)" = 10240 ] ||
    die "could not read the GPT; nothing written"

confirm write "About to make slot a active on the phone in EDL (now slot b):
  GPT   slot flags and partition type GUIDs of every _a/_b pair, both GPT copies
No partition contents are written; user data is kept. The GPT before the
change is saved in $OUTDIR."

say "making slot a active"
set_slot_a || die "edl setactiveslot failed; do not reset the phone, run this script again"
slot=$(active_slot)
[ "$slot" = a ] || die "active slot reads '$slot' after the change; do not reset the phone, run this script again"
say "slot a is active; resetting"
E_reset

cat <<EOF
$PROG: the phone starts from slot a, with your data. Unlock it with your PIN.
Before you install another update, prepare slot b (fastboot, data kept):
  SERIAL=... scripts/enable-ab.sh --abl work/userdebug/abl.elf "$BACKUP"
or update from a computer with scripts/flash.sh, which prepares it too.
See docs/errata/slot-b-unprepared.md.
EOF
