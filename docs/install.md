# Install

Step-by-step install of the Android 16 GSI on a Sonim XP8 (XP8800). Read the
warnings in the [README](../README.md) first. AI agents follow
[agents.md](agents.md) instead.

## Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Disk space](#disk-space)
- [Steps](#steps): [1 Back up](#1-back-up),
  [2 Unlock](#2-unlock-the-bootloader),
  [3 Choose root](#3-choose-root-or-no-root),
  [4A/5A Route A](#route-a-install-from-the-release),
  [4B/5B Route B](#route-b-build-from-source), [6 Flash](#6-flash),
  [7 First boot and verify](#7-first-boot-and-verify),
  [Switch root on or off](#switch-root-on-or-off),
  [Update to a newer release](#update-to-a-newer-release),
  [Update over the air](#update-over-the-air-ab),
  [8 Restore to stock](#8-restore-to-stock)

## Overview

- **Stock backup:** the EDL dump of your phone, made in step 1. It is never
  shipped. It supplies the proprietary inputs: the stock kernel in `boot_a`,
  and `/system/vendor` and the extra libraries inside `system_a`.
- **Release components:** the GitHub release assets: `system.img.xz` (the
  GSI with Google apps, the XP8 overlays, side keys and boot scripts),
  `xp8-gsi-components-<tag>.tar.xz` (MIT-licensed prebuilt parts; the
  vendor image takes `libxp8shim.so` and the vibrator service from it), and
  `SHA256SUMS`.
- **`scripts/assemble.sh`:** combines the release components with your stock
  backup on your computer and writes `out/boot.img` and `out/vendor.img`.
  These contain Sonim and Qualcomm files from your phone; do not share them.

Two routes share the same start and end:

```
1 Back up ─ 2 Unlock ─ 3 Choose root ─┬─ 4A Download release ─ 5A Assemble ─┬─ 6 Flash ─ 7 Verify ─ (8 Restore to stock)
                                      └─ 4B Build in Docker  ─ 5B Assemble ─┘
```

- **Route A** (default): download the release and assemble.
- **Route B**: build the components and the system image in Docker from
  pinned public downloads; see [building.md](building.md).

Only slot `_a` is changed. Slot `_b` stays stock and is not a fallback.

## Prerequisites

Host: macOS (Apple silicon or Intel) or Linux, with no Qualcomm drivers.
Route A also runs on Windows in Git Bash: see [windows.md](windows.md).

### Tools

- `adb` and `fastboot` from Android
  [platform-tools](https://developer.android.com/tools/releases/platform-tools)
- `git`, `zstd`, `xz`, Python 3, [uv](https://github.com/astral-sh/uv) (or `pip`
  in a virtualenv), `libusb`
- Docker: needed on macOS for step 5 (`--docker`), and for Route B
  everywhere. On macOS use [colima](https://github.com/abiosoft/colima) or
  Docker Desktop.
- Route A downloads: the [GitHub CLI](https://cli.github.com/) `gh`, or
  `curl`.

```sh
# macOS
brew install android-platform-tools git zstd xz uv libusb colima docker
colima start --cpu 4 --memory 8 --disk 100
# Debian/Ubuntu (ModemManager grabs the EDL device and must go)
sudo apt install adb fastboot git zstd xz-utils python3-venv libusb-1.0-0 docker.io
sudo apt purge modemmanager
```

### Downloads and their SHA-256

| File | Source | SHA-256 |
|---|---|---|
| bkerler/edl | <https://github.com/bkerler/edl> (tested at commit `1cda1a6`) | n/a (git) |
| Sonim firehose loader `prog_emmc_ufs_firehose_Sdm660_ddr.elf` | edl's `Loaders` submodule as `Loaders/sonim/0008c0e100010000_1b55c83cc1c00f4f_fhprg_peek.bin`, or `FlashTools.zip` in the [AndroidFileHost Sonim XP8 folder](https://androidfilehost.com/?w=files&flid=302388) (fid 4349826312261641937) | `d25b298ca36f467c3e30293e25492f08ea4831b4e98140313ff9ebca065c59b2` |
| AT&T 8.1 userdebug ABL `abl.elf` (110592 bytes) | inside `Images_XP8A_ATT-userdebug-8A.0.5-11-8.1.0-10.54.00.zip`, [AndroidFileHost Sonim XP8 folder](https://androidfilehost.com/?w=files&flid=302388) (fid 4349826312261641939, about 3.3 GB), posted in the [XDA thread](https://xdaforums.com/t/sonim-xp8-root.3851187/) | `7e6145d80b9fb46b7a9fdc326bd00d9593c21e3bd7929490abeb967bd9272648` |
| Magisk v30.7 (only with root) | <https://github.com/topjohnwu/Magisk/releases/tag/v30.7>; `scripts/assemble.sh --magisk` downloads and checks it | `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5` |
| Release components (Route A) | this repository's [releases](https://github.com/ndoo/sonim-xp8-gsi/releases) | listed in the release's `SHA256SUMS` |

None of these files are in this repository. AndroidFileHost requires picking
a mirror in a browser.

### Set up the working directory

Every command in this guide runs from the repository root. `work/`, `out/`,
`cache/` and `backups/` are ignored by git.

```sh
git clone https://github.com/ndoo/sonim-xp8-gsi.git
cd sonim-xp8-gsi
TAG=a16-YYYYMMDD           # latest tag on the releases page
git checkout "$TAG"        # the scripts must match the release you install

# edl in its own virtualenv
git clone https://github.com/bkerler/edl.git work/edl
git -C work/edl submodule update --init --recursive
uv venv work/.venv
uv pip install --python work/.venv/bin/python -e work/edl
# Linux only: udev rules and qcserial blacklist, then reboot
sudo sh work/edl/install-linux-edl-drivers.sh

# firehose loader
mkdir -p work/loaders
cp work/edl/Loaders/sonim/0008c0e100010000_1b55c83cc1c00f4f_fhprg_peek.bin \
   work/loaders/prog_emmc_ufs_firehose_Sdm660_ddr.elf
shasum -a 256 work/loaders/prog_emmc_ufs_firehose_Sdm660_ddr.elf

# userdebug ABL: download the zip in a browser, then
mkdir -p work/userdebug
unzip -j Images_XP8A_ATT-userdebug-8A.0.5-11-8.1.0-10.54.00.zip '*/abl.elf' -d work/userdebug
shasum -a 256 work/userdebug/abl.elf
```

To fetch only `abl.elf` instead of the 3.3 GB zip, use a zip client that
reads over HTTP range requests, such as the Python `remotezip` package,
pointed at the mirror URL the browser download resolves to:

```sh
uv pip install --python work/.venv/bin/python remotezip
(cd work/userdebug && ../.venv/bin/remotezip '<mirror URL>' \
  Images_XP8A_ATT-userdebug-8A.0.5-11-8.1.0-10.54.00/abl.elf)
```

Move the extracted file to `work/userdebug/abl.elf` and check its SHA-256.

### Variables

The scripts read these. Set them in every new shell:

```sh
export SERIAL=XXXXXXXX     # first column of 'adb devices'
export EDL=$PWD/work/.venv/bin/edl
export EDL_LOADER=$PWD/work/loaders/prog_emmc_ufs_firehose_Sdm660_ddr.elf
BACKUP=$PWD/backups/stock
TAG=$(git describe --tags --exact-match)   # the release tag checked out above
```

Every script refuses to run without `SERIAL`, and refuses when more than one
phone is attached over adb or fastboot, or more than one Qualcomm 9008
device in EDL. Connect only the phone you are working on.

### Phone modes

| Mode | Enter | Leave |
|---|---|---|
| EDL (USB `05c6:9008`, black screen) | `adb reboot edl`, or from power-off hold Vol+ and Vol- and press Power | `edl reset`, or hold Power about 10 s |
| fastboot | `adb reboot bootloader`, or from power-off hold Vol- and press Power | `fastboot reboot`, or hold Power 10-15 s |
| stock recovery | from power-off hold Vol+ and press Power | |

EDL is in the boot ROM and is reachable by keys from any state, including
when neither Android nor fastboot starts.

On macOS, the first time the phone appears in EDL, accept "Allow accessory
to connect?" (or set System Settings > Privacy & Security > Allow
accessories to connect). Until then edl waits.

On the phone, enable USB debugging (Settings > About phone > tap Build
number 7 times, then Developer options > USB debugging) and accept this
computer with "Always allow from this computer".

## Disk space

| Stage | Free space needed | Checked by |
|---|---|---|
| Backup (step 1) | 8 GiB in the backup directory; the finished backup is about 1.6 GiB | `scripts/dump-stock.sh` |
| Route A (steps 4A, 5A) | 8 GiB for `work/` and `out/`, plus about 6 GiB for the release assets and the 4 GiB raw `system.img` | `scripts/assemble.sh` (8 GiB) |
| Route B (steps 4B, 5B) | 40 GiB for downloads, `work/` and `out/` (a build uses about 7 GB); the Docker image is extra | `scripts/check-space.sh build` |
| Restore to stock | 6 GiB for the unpacked images | `scripts/restore-stock.sh` |

On macOS with colima, the Docker VM's disk also grows during builds.

## Steps

### 1. Back up

> [!CAUTION]
> Never write another unit's `modemst1`, `modemst2`, `fsg`, `fsc` or
> `persist`; your own copies cannot be recreated. Keep the backup offline
> and verify its checksums before you go on.

With the phone on stock Android 10 and USB debugging on:

```sh
scripts/dump-stock.sh "$BACKUP"
```

The script:

1. Checks the free space, the loader's SHA-256, the model (`XP8800`), the
   stock build (Android 10, build ID `8A.0.0-03-10.0.0-00.40.00`), and that
   the SIM is loaded and mobile data is validated.
2. Reboots to EDL, saves the partition table, and records a unit ID (a hash
   of the chip serial) in `UNIT_ID`.
3. Reads every partition except `userdata`, `system_b`, `vendor_a` and
   `vendor_b`, then `system_a` (`--with-vendor` also reads `vendor_a`), and
   reboots the phone.
4. Writes `SHA256SUMS` over the raw images, compresses them with zstd, and
   verifies every compressed file against the checksums.

It ends with `backup OK`. It refuses a directory that already holds images.
Copy `backups/stock` to a second, offline location; later steps read the
original.

### 2. Unlock the bootloader

> [!WARNING]
> Unlocking turns off verified boot (orange state) and erases all user data.
> Relocking needs your stock `abl_a` written back first.

> [!CAUTION]
> This step writes `abl_a` and `frp` over EDL. A write to any other
> partition can hard-brick the phone. Use the script: it writes only these
> two and reads both back.

The stock Android 10 bootloader has no unlock command. The AT&T Android 8.1
userdebug bootloader (ABL) has one and boots stock Android 10. The script
writes that ABL to `abl_a` and sets the OEM-unlock byte in `frp` in one EDL
session, then runs `fastboot flashing unlock`
([technical.md](technical.md#bootloader-unlock) has the details).

```sh
scripts/unlock.sh --abl work/userdebug/abl.elf "$BACKUP"
```

1. The script checks the ABL's SHA-256, that the phone in EDL is the unit
   of `$BACKUP`, and that `abl_a` is still stock.
2. It asks you to type `write`, then writes and verifies `abl_a` and `frp`.
   The previous contents go to `$BACKUP/unlock-<date>/`.
3. It resets the phone. If Android boots, the script runs
   `adb reboot bootloader`; no key needs to be held. If Android stops at a
   warning screen, press Power.
4. It checks `fastboot flashing get_unlock_ability` is `1`, asks you to type
   `unlock`, and runs `fastboot flashing unlock`. On the phone, select
   Unlock with a volume key and press Power.

The phone wipes userdata and boots stock Android 10 with an orange warning.
adb is off again after the wipe.

If `get_unlock_ability` stays `0`, or the phone reaches neither Android nor
fastboot after the ABL write, see
[troubleshooting.md](troubleshooting.md#unlock-get_unlock_ability-stays-0)
and [troubleshooting.md](troubleshooting.md#no-fastboot-after-the-abl-write).

### 3. Choose root or no root

Read [Root is your choice](../README.md#root-is-your-choice). Without root,
step 5 runs without `--magisk`. With root, add `--magisk` in step 5 and do
the Magisk setup in step 7.

### Route A: install from the release

**4A. Download and verify the release** for the tag you checked out in
[Set up the working directory](#set-up-the-working-directory):

```sh
gh release download "$TAG" -R ndoo/sonim-xp8-gsi -D out
xz -dk out/system.img.xz
(cd out && shasum -a 256 -c --ignore-missing SHA256SUMS)
tar -xJf "out/xp8-gsi-components-$TAG.tar.xz" -C out
ls out/components
```

Without `gh`, download the three assets from the release page into `out/`
with a browser or `curl -LO`. Every `shasum` line must say `OK`.

**5A. Assemble boot and vendor from your backup.**

> [!IMPORTANT]
> `assemble.sh` needs 8 GiB free for `work/` and `out/` and stops if it is
> short.

```sh
scripts/assemble.sh --docker "$BACKUP"            # no root
scripts/assemble.sh --docker --magisk "$BACKUP"   # Magisk root
```

On Linux with the tools from [build/Dockerfile](../build/Dockerfile)
installed, `--docker` can be left out. The script writes `out/boot.img`,
`out/vendor.img` and `out/assemble.sha256`, and ends with
`boot.img has no root` or `boot.img includes Magisk (root)`. Do not share
these images: they contain your phone's Sonim and Qualcomm files.

### Route B: build from source

**4B.** Build the components and the system image as described in
[building.md](building.md). The result is `out/components/` and
`out/system.img`.

**5B.** Assemble exactly as in step 5A.

### 6. Flash

> [!WARNING]
> `--wipe` runs `fastboot erase userdata` and deletes all apps, accounts and
> files. It is required on the first install. Never use `fastboot format`.
> If fastboot stalls (`could not clear input/output pipe`), hold Power
> 10-15 s and do not retry commands.

Put the phone in fastboot: power off, hold Vol- and press Power. If adb is
enabled, the script reboots the phone to fastboot itself.

```sh
scripts/flash.sh --wipe
```

The script stops if `fastboot devices` shows a serial other than
`$SERIAL`. It checks that `fastboot getvar unlocked` is `yes` and
`current-slot` is `a`, checks each image against `out/assemble.sha256`,
`out/SHA256SUMS` and the partition size, asks you to type `flash`, then
writes `boot_a` and `vendor_a`, erases `userdata` and writes `system_a`
last. The erase is a discard that returns within seconds; the phone formats
`/data` on the first boot.

The bootloader acknowledges a write at once and keeps writing for about
1 s per 15 MB. The script waits after each write
(`waiting N s for the phone to finish writing`) and checks that fastboot
answers before the next command. It ends with `all writes done` and reboots.

### 7. First boot and verify

On the first boot the phone formats and encrypts `/data` before setup
starts. If the bootloader is in dm-verity EIO mode (for example after a
restore to stock), the phone reboots once early in the first boot, with
reboot reason `dm-verity enforcing`; this switches the bootloader back to
enforcing mode ([technical.md](technical.md#init-script-and-boot-time-fixes)).

1. Go through the Google setup wizard and set a screen-lock PIN. After each
   reboot, files stay locked until the first PIN unlock; calls and mobile
   data work before that. VoLTE (IMS) is available from the first boot.
2. adb is off after the wipe. Enable USB debugging (Settings > About phone >
   tap Build number 7 times, then Developer options > USB debugging) and
   tick "Always allow from this computer"; without the tick, every reboot
   asks again.
3. Run the checks (no root needed):

   ```sh
   scripts/verify-device.sh
   ```

   It checks boot, file-based encryption, the PIN unlock (it asks you to
   unlock), `/sdcard`, SIM, network registration, mobile data, a ping, the
   IMS service, the media codecs, the vibrator, the XTRA daemon, the WebView
   provider, the hidden AOSP setup wizard, the removed AOSP search app and
   Play services visibility to apps (16 checks), prints a PASS/FAIL table, and ends with `all automatic
   checks passed`.
4. Do the hand checks it lists: a VoLTE call each way with the HD icon, an
   SMS each way, vibration, fingerprint, speaker, Maps location.

The phone reports itself as Sonim XP8800, as on stock, and the build
fingerprint reads `Sonim/XP8800/XP8800:16/...:userdebug/test-keys`. Device
name defaults to `XP8800` on a fresh install. "Phh Treble Settings" at the
top level of Settings is TrebleDroid's settings app; this build is tested
with its defaults.

**With root only:** install the Magisk app and grant root to adb's shell.

```sh
adb -s "$SERIAL" install cache/magisk/Magisk-v30.7.apk
```

Open Magisk and let it complete its setup (accept its reboot). Then, with
the screen on and unlocked, run `adb -s "$SERIAL" shell su -c id` and tap
Grant. If the screen is off, the prompt times out and Magisk stores a deny;
switch it on in Magisk > Superuser > Shell. Expected output:
`uid=0(root) ... context=u:r:magisk:s0`.

### Switch root on or off

Without a wipe: run step 5 again with or without `--magisk`, then flash
only `boot_a` from fastboot. Wait for the bootloader to finish writing
before the next command:

```sh
fastboot -s "$SERIAL" flash boot_a out/boot.img
sleep 10
fastboot -s "$SERIAL" reboot
```

### Update to a newer release

A newer release replaces `system_a` and, through its components,
`vendor_a`. User data is kept: the `userdata` fstab entry and its
encryption settings do not change between releases. `boot_a` changes only
when you switch root on or off.

1. Download, verify and unpack the new release as in step 4A, with its tag
   checked out.
2. Run step 5A again with your current root choice. This rebuilds
   `out/vendor.img` from the new components.
3. Flash without `--wipe`:

   ```sh
   scripts/flash.sh
   ```

   This writes `boot_a`, `vendor_a` and `system_a`, with `boot_a` from
   step 2. Its warning about `--wipe` applies only when coming from stock
   Android or another ROM.

To write only some images, name them with `--only`; the others and user
data are left as they are. For a release that changes only the system
image:

```sh
scripts/flash.sh --only system
```

`--only vendor,system` writes both and keeps `boot_a`. `flash.sh` waits
after each write until the phone has finished writing (about 1 s per 15 MB,
plus 5 s).

If a command prints `unknown command` or hangs, hold Power 10-15 s; see
[Fastboot stall](troubleshooting.md#fastboot-stall).

### Update over the air (A/B)

Releases that list `ota.json` among their assets can be installed while
the phone runs, into the other slot. Your data, root choice, boot image and
vendor image carry over; a failed update leaves the old slot to fall back
to. This needs:

- a vendor image assembled from a release with A/B OTA support (it sets
  `ro.vendor.build.ab_ota_partitions`); update once as in
  [Update to a newer release](#update-to-a-newer-release) if yours is older;
- slot b prepared once (from fastboot, slot a active; writes `abl_b`,
  `mdtpsecapp_b` and `modem_b`, nothing on slot a):

  ```sh
  scripts/enable-ab.sh --abl work/userdebug/abl.elf "$BACKUP"
  ```

Then open **Settings → System → System update**. It checks the latest
release and offers **Download & install**: `update_engine` streams the
payload from GitHub into the other slot while you use the phone, and
**Restart now** boots it. No computer or root is needed.

From a computer instead, with the phone booted and USB debugging on:

```sh
scripts/ota-update.sh
```

It downloads the latest release's payload, checks its SHA-256, writes it
to the other slot with `update_engine`, and reboots into that slot after
you confirm. `--json FILE` installs a specific release's `ota.json`. To go
back to the previous slot from fastboot without writing anything:

```sh
scripts/flash.sh --switch a     # or b
```

The payload carries only `system`. The overlays, side keys, boot scripts
and audio patches are on `system`, so their changes arrive over the air.
Only a release that changes what stays in `vendor` (fstab, vendor
properties, the shim library, the vibrator HAL) needs the
[PC update](#update-to-a-newer-release); its `ota.json` then asks for a
higher `ro.vendor.xp8.layout`, and System update and `ota-update.sh` stop
and say so.

### 8. Restore to stock

```sh
scripts/restore-stock.sh "$BACKUP"
```

What it writes, its options (`--edl`, `--relock`) and what the phone shows
afterwards: [restore.md](restore.md).
