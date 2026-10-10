# Restore to stock Android 10

[`scripts/restore-stock.sh`](../scripts/restore-stock.sh) puts stock
Android 10 back on the XP8 from your own backup (made in
[install step 1](install.md#1-back-up)).

> [!CAUTION]
> Write only your own phone's backup. Never write another unit's
> `modemst1`, `modemst2`, `fsg`, `fsc` or `persist`: they hold the IMEI and
> radio calibration, and a wrong copy can remove the IMEI and SIM detection
> for good.

> [!WARNING]
> Restoring erases all user data. Copy your files off the phone first.

## Contents

- [What is written](#what-is-written)
- [Before you start](#before-you-start)
- [Over fastboot](#over-fastboot)
- [Over EDL](#over-edl)
- [After the restore](#after-the-restore)
- [Relock the bootloader](#relock-the-bootloader)
- [Per-unit partitions](#per-unit-partitions)
- [Back to the GSI](#back-to-the-gsi)

## What is written

| Partition | Source | When |
|---|---|---|
| `boot_a` | stock kernel and ramdisk from the backup | always |
| `system_a` | stock system from the backup | always |
| `misc` | a recovery `--wipe_data` request, so the first stock boot formats userdata | always; the last write when EDL is used |
| `vendor_a` | backup | `--vendor` only, if the backup has `vendor_a`; stock Android 10 does not mount it |
| `abl_a` | stock bootloader from the backup | `--relock` |
| `frp` | zeros | `--relock` |
| `modemst1`, `modemst2`, `fsg`, `fsc`, `persist` | backup of the same unit | `--restore-nv`; `persist` alone with `--restore-persist` |

Nothing else is written. The `_b` partitions were never changed.

`userdata` is not erased over fastboot: `fastboot erase userdata` discards
the blocks but leaves the GSI's filesystem readable, and stock Android 10
then bootloops on it. The `misc` request makes stock recovery format
`userdata` instead.

## Before you start

- Your backup directory with `SHA256SUMS` and `UNIT_ID`. The script unpacks
  each image and checks it against `SHA256SUMS` before it writes anything;
  a mismatch stops it.
- 6 GiB of free disk for the unpacked images (`--work DIR`, default
  `work/restore`).
- `SERIAL` set to the phone's serial, and only this phone connected.
- For `--edl`, `--relock`, `--restore-nv` and `--restore-persist`: the edl
  setup and `EDL`/`EDL_LOADER` from [install.md](install.md#prerequisites).
  These modes also check that the phone in EDL is the unit the backup was
  made from (`UNIT_ID`, a hash of the chip serial).

## Over fastboot

Use it while the bootloader is unlocked and the phone reaches fastboot
(power off, hold Vol- and press Power).

```sh
scripts/restore-stock.sh "$BACKUP"
```

The script checks `fastboot getvar unlocked` is `yes` and `current-slot` is
`a`, asks you to type `restore`, then writes `boot_a`, the `misc` wipe
request and `system_a`, and reboots. If fastboot stalls after the
`system_a` write (`could not clear input/output pipe`), hold Power 10-15 s
and do not retry commands.

## Over EDL

Use it when the phone does not reach fastboot or Android. Enter EDL by
keys: power off (or pull the battery), hold Vol+ and Vol- and press Power.
The screen stays black.

```sh
scripts/restore-stock.sh --edl "$BACKUP"
```

After the checksum checks and the unit check, the script asks you to type
`restore`, then writes `boot_a` and `system_a`, reads each one back, writes
the `misc` wipe request last, and resets the phone. Reading back `system_a`
takes a few minutes.

## After the restore

Stock recovery formats `userdata`, then stock Android 10 starts setup.
Finish setup and enable USB debugging again.

If no factory reset runs and the phone does not reach setup, enter stock
recovery: power off, hold Vol+ and press Power; at "No command", hold Power
and tap Vol+; choose "Wipe data/factory reset".

- Stock Android 10 shows a yellow or red "can't be trusted and may not work
  properly" screen at each boot; press Power to continue. The bootloader is
  in dm-verity EIO mode
  ([troubleshooting.md](troubleshooting.md#verity-warning-on-stock)).
- With the userdebug ABL still in `abl_a` (no `--relock`), stock Android 10
  also shows the orange "unlocked" warning.
- If the phone ends in fastboot instead of Android, see
  [troubleshooting.md](troubleshooting.md#phone-ends-in-fastboot).

## Relock the bootloader

> [!WARNING]
> A locked bootloader refuses images that are not stock: a non-stock
> `boot_a` on a locked bootloader loops on a red "Your device is corrupt"
> screen. EDL still works in that state.

The stock Android 10 ABL has no `fastboot flashing` commands, so the lock
has to happen while the userdebug ABL is still in `abl_a`:

```sh
scripts/restore-stock.sh --relock "$BACKUP"
```

1. Over fastboot: the stock `boot_a`, the `misc` wipe request and
   `system_a`, then `fastboot flashing lock`. Confirm on the phone.
2. The script asks you to enter EDL by keys, checks the unit, and writes the
   stock `abl_a` and a zeroed `frp`, each read back, and the `misc` wipe
   request last.

The backup's `frp` is not written: it holds the Factory Reset Protection
state from backup time, and setup would then ask for the screen lock the
phone had then and may not accept it. Android formats a zeroed `frp` at the next boot.

Expected afterwards: the stock build fingerprint, `ro.boot.flash.locked=1`,
`ro.boot.verifiedbootstate=green`, and the verity warning at each boot.
`devinfo` is not written: the stock ABL does not keep its lock state there.

Without fastboot, `--edl --relock` writes the stock `abl_a` and a zeroed
`frp` without `fastboot flashing lock`; the lock state then stays whatever
the userdebug ABL last stored.

## Per-unit partitions

`modemst1`, `modemst2`, `fsg` and `fsc` hold the modem NV: IMEI, radio
calibration, carrier configuration. `persist` holds per-unit Wi-Fi and
Bluetooth data and the sensor calibration; Magisk also keeps its pre-init
data there. No install step writes them. `restore-stock.sh` writes them only
with `--restore-nv` (all five) or `--restore-persist` (only `persist`),
after checking that the phone in EDL is the unit the backup was made from,
and after you type `RESTORE-NV`.

Use these options only to repair a phone whose radio or Wi-Fi broke, from
that phone's own backup. Firmware other than this guide's can rewrite
`persist`: on a unit moved to a different Android version and back, Wi-Fi
worked again only after its own `persist` was restored.

```sh
scripts/restore-stock.sh --restore-persist "$BACKUP"
```

## Back to the GSI

Unless you relocked, the bootloader is still unlocked. Run
[install steps 4 to 7](install.md#route-a-install-from-the-release) again;
`scripts/flash.sh --backup "$BACKUP" --wipe` installs the GSI and erases the
stock data. After a relock, unlock again first
([install step 2](install.md#2-unlock-the-bootloader)).
