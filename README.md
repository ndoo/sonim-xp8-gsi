# Sonim XP8 (XP8800) Android 16 GSI

Scripts and a guide to move a **Sonim XP8** (model **XP8800**, Qualcomm
**Snapdragon 630 / SDM630**) from stock Android 10 to the
**TrebleDroid Android 16 GSI** with Google apps, on the stock kernel and
vendor. It covers the EDL backup, **bootloader unlock** without root, building
the boot and vendor images from your own backup, flashing, optional
**root** with Magisk, and restoring stock.

The XP8 was sold in carrier variants for AT&T/FirstNet, Verizon, Sprint,
T-Mobile, Bell, Telus and Rogers, and as an unlocked model. All report the
model number XP8800. See [Supported devices](#supported-devices) for what has
been tested.

**AI agents: follow [docs/agents.md](docs/agents.md).**

> [!CAUTION]
> This procedure voids the warranty, wipes all user data several times
> (unlock, first install, restore to stock), and can hard-brick the phone if
> an EDL write targets the wrong partition. The software is provided "as is"
> without warranty (MIT licence).

> [!CAUTION]
> Never write another unit's `modemst1`, `modemst2`, `fsg`, `fsc` or
> `persist` to your phone. They hold the IMEI and radio calibration, and your
> own copies cannot be recreated.

> [!WARNING]
> Your backup holds your IMEI/NV data and device keys. Keep it offline; never
> publish, share or commit it.

> [!WARNING]
> Unlocking turns off verified boot (orange state): anyone with physical
> access can flash the phone. Relocking needs your stock `abl_a` written back
> first (`scripts/restore-stock.sh --relock`).

> [!WARNING]
> The result runs with SELinux permissive and the stock kernel's 2020-09
> security patch level. With root, any app you grant `su` has full control of the phone.

> [!IMPORTANT]
> Only the tested stock build is supported. Carrier locks live in the modem
> NV; no ROM removes them. Check that the SIM and mobile data work on stock
> Android 10 with Wi-Fi off; if they do not, do not proceed.

> [!IMPORTANT]
> Free disk space: 8 GiB for the backup, about 14 GiB more to install from
> the release, 40 GiB more to build from source. See
> [Disk space](docs/install.md#disk-space).

## Feature status

Tested on an XP8800 running the supported stock build, with the images this
repository builds (TrebleDroid `ci-20250617`, MindTheGapps 16, stock kernel
4.4.205).

✅ works · ⚠️ works with a limitation · ❌ does not work · ❔ untested

| Area | Feature | Status | Notes |
|---|---|---|---|
| Telephony | Calls over VoLTE, in and out | ✅ | HD icon shows during calls |
| Telephony | SMS send and receive | ✅ | |
| Telephony | LTE mobile data | ✅ | Also before the first unlock after a reboot |
| Telephony | Carrier unlock | ❌ | Carrier locks live in the modem NV; no ROM removes them |
| Connectivity | Wi-Fi Internet | ✅ | |
| Connectivity | Per-app data usage and network limits | ❌ | The 4.4 kernel has no eBPF; data itself works |
| Connectivity | Wi-Fi hotspot | ⚠️ | WPA2 or no password; WPA3 is not offered (the stock hostapd HAL is 1.1, SAE needs 1.2) |
| Connectivity | WPA3 networks (as a client) | ❔ | Reported as supported by the stock Wi-Fi HAL |
| Connectivity | NFC | ❔ | |
| Location | GPS with assisted GPS (XTRA), Google Maps | ✅ | |
| Location | Compass | ✅ | |
| Hardware | Fingerprint enrol and unlock | ✅ | Sensor in the Home button |
| Hardware | Speaker: ringtone, calls, media | ✅ | |
| Hardware | Microphone in push-to-talk apps (Zello) | ✅ | Stock mic gain, as on Sonim Android 10 |
| Hardware | Vibration | ✅ | |
| Hardware | Back, Home and Recents keys | ✅ | No on-screen navigation bar |
| Hardware | PTT, SOS and camera keys | ✅ | Set up in the XP8 Buttons app: PTT and SOS go to push-to-talk apps as on stock (hold to talk, also in the background; tested with Zello), or each key runs a short- and a long-press action. See [technical.md](docs/technical.md#vendor-image) |
| Hardware | Camera capture, front and rear | ✅ | |
| System | File-based encryption, `/sdcard` | ✅ | |
| System | Google setup wizard and Play Store | ✅ | |
| System | Root with Magisk 30.7 | ✅ | Optional; everything above also works without root, see [Root is your choice](#root-is-your-choice) |
| System | SELinux enforcing | ❌ | Runs permissive |
| System | OTA updates | ⚠️ | A/B updates from Settings → System → System update, no root needed; a release that changes what stays in the vendor image is flashed from a computer. See [Update over the air](docs/install.md#update-over-the-air-ab) |
| System | Security patches | ⚠️ | The Android 16 GSI is current, but the kernel stays at the 2020-09 patch level; Sonim has not published its source |

## Supported devices

| Variant | Stock software | Status |
|---|---|---|
| XP8800, Asia/rest-of-world | Android 10, `8A.0.0-03-10.0.0-00.40.00`, patch 2020-09 | **Tested** (one unit) |
| AT&T / FirstNet | | Untested; same model XP8800. See the note below |
| Verizon, Sprint, T-Mobile | | Untested; same model XP8800 |
| Bell, Telus, Rogers | | Untested; same model XP8800 |

The build number is in Settings → About phone. `scripts/dump-stock.sh`
checks the Android version and build ID and stops on any other build unless
you pass `--allow-other-build`.

On AT&T hardware, stock Android 10 flashed over EDL did not register on the
network, most likely because of a carrier lock in the modem that this GSI
cannot remove. Returning such a unit to its own firmware restored Wi-Fi only
after its own `persist` partition was restored as well.
`scripts/dump-stock.sh` refuses to continue when the SIM or mobile data does
not work on stock.

## Root is your choice

`scripts/assemble.sh` builds the boot image **without root** by default;
`--magisk` adds Magisk.

| | No root (default) | Magisk root (`--magisk`) |
|---|---|---|
| Tested | Boot, encryption, LTE, VoLTE, SMS, vibration | Every ✅ item in [Feature status](#feature-status) |
| `adb` | Needs host authorization; `adb root` refused | Same |
| Root shell | None | `adb shell su` after you grant Shell in the Magisk app |
| Risk | Smaller attack surface | Any app you grant `su` can read and change everything, including other apps' data |
| Extra steps | None | Install the Magisk app and grant Shell after the first boot |

Either way, SELinux is permissive and the bootloader stays unlocked. To
switch later without a wipe, assemble again with or without `--magisk` and
flash only `boot_a`
([install.md](docs/install.md#switch-root-on-or-off)).

## Quick install

Route A: install from the release; nothing is compiled. This assumes the
tools, edl, the firehose loader and the userdebug ABL are set up as in
[docs/install.md](docs/install.md#prerequisites), and USB debugging is on.
Each script shows what it will write and asks before writing.

```sh
TAG=a16-YYYYMMDD           # latest tag on the releases page
git checkout "$TAG"        # the scripts must match the release
export SERIAL=XXXXXXXX     # first column of 'adb devices'
export EDL=$PWD/work/.venv/bin/edl
export EDL_LOADER=$PWD/work/loaders/prog_emmc_ufs_firehose_Sdm660_ddr.elf
BACKUP=$PWD/backups/stock

# 1. Back up every partition over EDL; ends with "backup OK". Copy it offline.
scripts/dump-stock.sh "$BACKUP"
# 2. Unlock: abl_a and frp over EDL, then fastboot flashing unlock (wipes the phone).
scripts/unlock.sh --abl work/userdebug/abl.elf "$BACKUP"
# 3. Download and verify the release.
gh release download "$TAG" -R ndoo/sonim-xp8-gsi -D out
xz -dk out/system.img.xz
(cd out && shasum -a 256 -c --ignore-missing SHA256SUMS)
tar -xJf "out/xp8-gsi-components-$TAG.tar.xz" -C out
# 4. Build boot.img and vendor.img from your backup (add --magisk for root).
scripts/assemble.sh --docker "$BACKUP"
# 5. Flash boot_a, vendor_a, system_a and erase userdata.
scripts/flash.sh --wipe
# 6. After setup, enable USB debugging again and run the checks.
scripts/verify-device.sh
```

Each step, its expected output and Route B (build from source) are in
[docs/install.md](docs/install.md).

## Documentation

| Page | Contents |
|---|---|
| [docs/install.md](docs/install.md) | Prerequisites, downloads and checksums, disk space, steps 1 to 8 for Route A (install from the release) and Route B (build from source), first boot |
| [docs/windows.md](docs/windows.md) | Route A from a Windows host: tools, edl setup, the EDL driver |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Symptoms and fixes, manual recovery over fastboot and EDL |
| [docs/restore.md](docs/restore.md) | Back to stock Android 10, relocking |
| [docs/building.md](docs/building.md) | Route B: build the components and the system image in Docker, CI releases |
| [docs/technical.md](docs/technical.md) | Device and partition facts, what the build changes and why, the unlock mechanism |
| [docs/agents.md](docs/agents.md) | Runbook for AI agents operating the phone |

Report problems as an issue at <https://github.com/ndoo/sonim-xp8-gsi/issues>,
or by email to <me@ndoo.sg>.

## Credits

- The EDL backup and Magisk method, the loader bundle and the AT&T userdebug
  images come from the XDA thread
  [Sonim XP8 (Root?)](https://xdaforums.com/t/sonim-xp8-root.3851187/):
  smokeyou (EDL and Magisk method, loader bundle, userdebug images),
  portsample (Android 10 root notes), thenatti (TWRP builds for stock), and
  the other posters who reported bricks and recoveries.
- EDL tooling: [bkerler/edl](https://github.com/bkerler/edl).
- Root: [Magisk](https://github.com/topjohnwu/Magisk) by topjohnwu.
- The Android 16 GSI: [TrebleDroid](https://github.com/TrebleDroid/treble_experimentations),
  and phh (Pierre-Hugues Husson) for the Treble GSI work and the IMS app
  `ims-caf-u`.
- Google apps: [MindTheGapps](https://gitlab.com/MindTheGapps/vendor_gapps).
- apktool, smali and baksmali; the Android Open Source Project.

See [NOTICE.md](NOTICE.md) for the third-party inputs and their licences.

## License

MIT, see [LICENSE](LICENSE). Files are annotated per the
[REUSE](https://reuse.software/) specification ([REUSE.toml](REUSE.toml),
`LICENSES/`): smali diffs derived from AOSP are Apache-2.0, and the audio
configuration diffs are BSD-3-Clause.

This repository contains no Sonim, Qualcomm or Google binaries. The
`system.img` release asset contains third-party components (TrebleDroid,
MindTheGapps, phh's IMS app) under their own terms; see
[NOTICE.md](NOTICE.md). The boot and vendor images that `assemble.sh` builds
contain files from your own phone and are for your own use only.
