# For AI agents

Follow this runbook exactly when you operate the phone for a user. It
restates the warnings in the [README](../README.md) as hard rules. Commands
and variables are those of [install.md](install.md); every command runs from
the repository root.

## Hard rules

1. **Ask before acting** and wait for the user's answer:
   - before every command that writes over EDL (`scripts/unlock.sh`,
     `scripts/restore-stock.sh`);
   - before every erase or wipe (`scripts/flash.sh --wipe`, the unlock in
     step 2, every `scripts/restore-stock.sh` run);
   - for the root choice: ask "root with Magisk, or no root?" and explain the
     trade-offs in [Root is your choice](../README.md#root-is-your-choice).
     Never pick a default without asking;
   - whenever output differs from the expected output below.
2. Pass `--yes` to a script only after the user has approved that specific
   step in the conversation. Physical actions (holding keys, confirming on
   the phone) are the user's; tell them exactly what to do and when.
3. Never write `modemst1`, `modemst2`, `fsg`, `fsc` or `persist`, except
   with `scripts/restore-stock.sh --restore-nv` or `--restore-persist` when
   the user asks for it to repair the same unit.
4. Never run `edl w`, `edl e`, `edl wl`, `edl qfil` or `fastboot flash` for
   any partition yourself. Use the scripts. Never touch `xbl*`, `tz*`,
   `rpm*`, `hyp*`, `pmic*`, `keymaster*`, `keystore`, `devcfg*`,
   `cmnlib*`, `devinfo`.
5. Never run `fastboot format`, `fastboot getvar all`, `fastboot
   --set-active` or `fastboot set_active` (except `fastboot set_active a`
   in the restore recovery below, after approval), `fastboot flashing
   unlock_critical`, or anything for slot `_b` except through
   `scripts/enable-ab.sh`, `scripts/flash.sh --slot`/`--switch` and
   `scripts/ota-update.sh` after the user approved that step. Never interrupt a running
   fastboot command, and never send a fastboot command while a script is
   waiting for a write to finish. On a stall, ask the user to hold Power
   10-15 s.
6. Pin every command to `SERIAL`. If more than one phone is attached over
   adb or fastboot, or more than one 9008 device is present, stop and ask
   the user to disconnect the others.
7. Never copy, upload, print or commit the backup's contents.
8. Use Route A unless the user asks to build everything.
9. Never turn on data roaming, in Settings or with `settings put` or
   `cmd phone`. If a check needs mobile data while roaming, ask the user to
   turn roaming on, and to turn it off again afterwards.

## Runbook

Each step lists its precondition, command, expected result and failure
action. Stop and ask at every **STOP**.

**R0. Collect inputs.** Ask the user for: the phone's serial (`adb devices`),
the backup location (default `backups/stock`), Route A or B (default A), and
the root choice. Confirm they accept that all data on the phone is erased
several times.

**R1. Host check.**
Run: `adb version; fastboot --version; zstd --version; xz --version; python3 --version; docker info` and
`"$EDL" --help | head -1`.
Pass: every command prints a version. Docker is required on macOS and Windows.
On Windows, run everything in Git Bash, follow [windows.md](windows.md), and
use `python` for `python3` and `sha256sum` for `shasum -a 256`.
Then: `shasum -a 256 "$EDL_LOADER" work/userdebug/abl.elf`.
Pass: `d25b298ca36f467c3e30293e25492f08ea4831b4e98140313ff9ebca065c59b2` and
`7e6145d80b9fb46b7a9fdc326bd00d9593c21e3bd7929490abeb967bd9272648`.
Fail: stop and tell the user which file to download (see
[Prerequisites](install.md#prerequisites)).
Then: `git describe --tags --exact-match`.
Pass: prints a release tag (`a16-YYYYMMDD` or `a16-YYYYMMDD.N`); set `TAG`
to it. The scripts must match the release you install.
Fail: **STOP**; show the user the latest tag
(`gh release list -R ndoo/sonim-xp8-gsi -L 1`) and ask before running
`git checkout <tag>`.

**R2. Device check.**
Run: `adb devices; fastboot devices`.
Pass: exactly one line in total, its first column equal to `$SERIAL`, state
`device`. Then `adb -s "$SERIAL" shell getprop ro.product.model` prints
`XP8800`.
Fail: `unauthorized` → ask the user to accept the prompt. More than one
device → **STOP**.

**R3. Disk gate.**
Run: `scripts/check-space.sh assemble "$BACKUP"` (Route B also
`scripts/check-space.sh build .`).
Pass: exit status 0. Fail: **STOP**, report the free space shown.

**R4. Back up** (step 1).
Precondition: R1-R3 pass; `$BACKUP` is empty or absent.
Run: `scripts/dump-stock.sh "$BACKUP"`.
Expect: `stock checks passed`, edl reads, `backup OK`.
Fail: a stock-build or SIM/data refusal → **STOP** and report it; do not use
`--allow-other-build` or `--skip-sim-check` unless the user decides so.
After: `(cd "$BACKUP" && ls UNIT_ID SHA256SUMS boot_a.bin.zst system_a.bin.zst abl_a.bin.zst frp.bin.zst misc.bin.zst modemst1.bin.zst modemst2.bin.zst fsg.bin.zst fsc.bin.zst persist.bin.zst)`
lists every file. Ask the user to copy the backup offline.

**R5. Unlock** (step 2). **STOP** before running: ask for approval of the
EDL writes to `abl_a` and `frp` and of the wipe.
Precondition: `fastboot getvar unlocked` is not `yes` (if it is, skip R5).
Run: `scripts/unlock.sh --abl work/userdebug/abl.elf "$BACKUP"` (with `--yes`
only after approval). No key needs to be held: after the EDL reset the
script waits up to 300 s for fastboot and runs `adb reboot bootloader` if
Android boots. Tell the user to press Power if Android stops at a warning
screen, and to confirm Unlock on the phone.
Expect: `unit matches the backup`, `abl_a written and verified`,
`frp written and verified`, possibly `Android booted; rebooting to
fastboot`, `get_unlock_ability: 1`, then the fastboot unlock.
Fail: `did not reach fastboot` → ask the user what the screen shows; if
Android is up with USB debugging on, run the script again (it does not
write `abl_a` twice). `read-back ... does not match` → **STOP**, do not
reset the phone. `get_unlock_ability is 0` or any other failure → **STOP**
and offer the Magisk fallback
([troubleshooting.md](troubleshooting.md#unlock-get_unlock_ability-stays-0)),
which is untested and which the user must approve.
After: the phone wipes and boots stock Android 10 with an orange warning.
adb is off again; `scripts/flash.sh` checks `unlocked: yes` in R9.

**R6. Root choice.** **STOP**: ask the user and record the answer.

**R7A. Route A download.**
Run: the commands in [4A](install.md#route-a-install-from-the-release),
using `gh release download` or `curl`.
Pass: every `shasum` line says `OK`; `out/system.img` and
`out/components/libxp8shim.so` exist.

**R7B. Route B build** (only on request).
Precondition: R3 build gate passes.
Run: the commands in [building.md](building.md#build).
Pass: `out/system.img` and `out/components/` exist.

**R8. Assemble.**
Run: `scripts/assemble.sh --docker "$BACKUP"`, plus `--magisk` only if the
user chose root.
Pass: the last line is `boot.img has no root` or `boot.img includes Magisk
(root)`, matching the choice, and `out/assemble.sha256` lists `boot.img` and
`vendor.img`.

**R9. Flash.** **STOP**: ask for approval to erase userdata and flash.
Precondition: the phone is in fastboot (ask the user to power off, hold Vol-
and press Power) or in adb; `fastboot devices` lists `$SERIAL`. The script
checks `unlocked` → `yes` and reads `current-slot`.
Run: `scripts/flash.sh --wipe`.
Expect: `checksum OK` lines; after each flash and after the erase a
`waiting N s for the phone to finish writing` line; `all writes done`. Let
each wait run out; do not send fastboot commands during it.
Fail: `fastboot stopped answering` or a stall → ask the user to hold Power
10-15 s, enter fastboot again, then **STOP** and ask before running
`scripts/flash.sh --wipe` again (it writes every image again). All writes
are complete once `all writes done` is printed.

**R10. First boot.** Tell the user that one extra early reboot during the
first boot can occur (reboot reason `dm-verity enforcing`). Ask the user to finish setup, set a PIN, enable USB
debugging (adb is off after the wipe) and tick "Always allow". Wait for
`adb -s "$SERIAL" get-state` → `device`. If the phone shows "Can't load
Android system" or loops into recovery, **STOP** and propose
`scripts/flash.sh --misc-only`. If it reboots repeatedly and ends in
fastboot, **STOP** and report it.

**R11. Verify.**
Run: `scripts/verify-device.sh` (ask the user to unlock with the PIN when it
says so).
Pass: `all automatic checks passed` (16 PASS). A WebView failure: rerun with
`--fix-webview`. Any other FAIL: **STOP** and report the table.
Then give the user the hand-check list the script prints, and ask for the
results of one call and one SMS.

**R12. Root setup** (only if chosen): the commands in
[step 7](install.md#7-first-boot-and-verify), then
`adb -s "$SERIAL" shell su -c id` → `uid=0(root)`.

**Restore** (only on request): **STOP** for approval, then
`scripts/restore-stock.sh "$BACKUP"`, adding `--edl`, `--relock`,
`--restore-nv` or `--restore-persist` only when the user asks for them.
There is no `--wipe` option: the script always writes a recovery
`--wipe_data` request to `misc` (the last write when EDL is used), and
`--relock` writes a zeroed `frp`. With `--relock`, tell the user to
confirm the lock on the phone and, when the script says so, to enter EDL
by keys.
Expect: `Restore done`; on the next boot stock recovery formats userdata,
then stock setup starts. Tell the user that a "can't be trusted and may not
work properly" screen at each boot is expected (press Power).
Fail: the phone ends in fastboot instead of Android → **STOP**. If
`fastboot getvar unlocked` is `yes`, propose `fastboot -s "$SERIAL"
set_active a` and `fastboot -s "$SERIAL" reboot`. If it is locked, do
not write anything: point the user to the EDL procedure in
[troubleshooting.md](troubleshooting.md#phone-ends-in-fastboot).

## Reporting problems

When a command fails or its output differs from the expected output:

1. Stop, as the runbook requires.
2. Draft an issue report with: the runbook step and the exact command; the
   exact error or output; the phone model (`ro.product.model`) and stock
   build ID (`ro.build.id`); the host OS; the repository tag or commit
   (`git describe --tags --always`).
3. Redact the draft. Never include serials (the adb/fastboot serial, the
   chip serial), IMEI, ICCID, MAC addresses, phone numbers, SSIDs, or any
   backup file or its contents. Logs from the phone can contain the SIM
   ICCID; remove it.
4. Show the draft to the user and post it only with the user's explicit
   approval. If `gh auth status` shows a GitHub login, file it from the
   user's account with `gh issue create -R ndoo/sonim-xp8-gsi`. Otherwise
   suggest that the user email the report to <me@ndoo.sg>.
