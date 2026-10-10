# Notice

## What this repository contains

Scripts, build recipes, patches, configuration and source code written for
this project, plus diffs against third-party files. It contains **no Sonim,
Qualcomm or Google binaries**: no kernel, no boot, vendor or system image,
no APK, no firmware, no bootloader and no EDL loader.

| Files | Licence |
|---|---|
| Everything not listed below | MIT, see [LICENSE](LICENSE) |
| `system/**.smali`, `system/**.smali.diff`, `system/**.smali.patch`, `system/messaging/*.diff` (derived from AOSP code) | Apache-2.0, see [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| `vendor/updater/res/drawable/ic_system_update.xml` (Google Material Icons), `vendor/keys/app/res/drawable/ic_programmable_keys.xml` (Pictogrammers Material Design Icons) | Apache-2.0, see [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt) |
| `vendor/audio/*.diff` (diffs against Qualcomm/CAF audio configuration files) | BSD-3-Clause, see [LICENSES/BSD-3-Clause.txt](LICENSES/BSD-3-Clause.txt) |

The per-file licence information follows the [REUSE](https://reuse.software/)
specification ([REUSE.toml](REUSE.toml)).

## Inputs fetched at build time

`build/fetch.sh` downloads these from their publishers and checks each
against the SHA-256 in [build/inputs.lock](build/inputs.lock). None of them
is stored in this repository.

| Input | Source | Licence |
|---|---|---|
| TrebleDroid GSI `system-td-arm64-vanilla-old` `ci-20250617` | [TrebleDroid/treble_experimentations](https://github.com/TrebleDroid/treble_experimentations) | AOSP (Apache-2.0 and others) with TrebleDroid's patches; each component under its own licence |
| MindTheGapps 16 (pinned commit) | [MindTheGapps/vendor_gapps](https://gitlab.com/MindTheGapps/vendor_gapps) | Google proprietary applications, under Google's terms |
| phh IMS app `ims-caf-u-resigned.apk` | [treble.phh.me](https://treble.phh.me/) | No licence stated by the publisher |
| Magisk v30.7 (only with `--magisk`) | [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk) | GPL-3.0 |
| AOSP test keys, `avbtool.py` (`android-16.0.0_r1`) | [android.googlesource.com](https://android.googlesource.com/) | Apache-2.0 |
| Android SDK build-tools 36, platform 36, NDK r27d | [build-tools](https://developer.android.com/tools/releases/build-tools), [platforms](https://developer.android.com/tools/releases/platforms), [NDK](https://developer.android.com/ndk/downloads); downloaded from dl.google.com | Android Software Development Kit License Agreement |
| apktool 2.10.0 and the LineageOS extract-tools copies of apktool, smali and baksmali | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool), [LineageOS/android_prebuilts_extract-tools](https://github.com/LineageOS/android_prebuilts_extract-tools) | apktool: Apache-2.0; smali/baksmali: BSD-3-Clause |
| patchelf 0.19.1 | [NixOS/patchelf](https://github.com/NixOS/patchelf) | GPL-3.0 |
| Debian packages in the build container | [build/Dockerfile](build/Dockerfile) | Their Debian licences |

Tools the user installs separately: [bkerler/edl](https://github.com/bkerler/edl)
(GPL-3.0) and Android platform-tools.

## Files the user supplies

These are proprietary and are never redistributed by this project:

- **The stock backup** of the user's own phone (`scripts/dump-stock.sh`). It
  supplies the Sonim kernel and ramdisk (`boot_a`), the Sonim and Qualcomm
  vendor files and libraries (`system_a`), `xtra-daemon`, `CACertService`,
  the fstab and the audio configuration. `scripts/assemble.sh` combines them
  with this project's components on the user's computer into `boot.img` and
  `vendor.img`, which stay with the user. Sonim has not published the source
  of its kernel.
- **The Sonim-signed firehose loader** `prog_emmc_ufs_firehose_Sdm660_ddr.elf`
  and **the AT&T Android 8.1 userdebug `abl.elf`**, downloaded by the user
  from the sources in the [install guide](docs/install.md#prerequisites).

## Release assets

- `system.img.xz` is built by CI from the inputs above and this
  repository's patches. It contains TrebleDroid, MindTheGapps and the phh IMS
  app, each under its own terms, and patched AOSP components (Apache-2.0).
- `xp8-gsi-components-<tag>.tar.xz` holds parts built from this repository
  (MIT): `libxp8shim.so`, the overlay APKs and the vibrator service, plus the
  AOSP Messaging app with this repository's manifest change (Apache-2.0).

## Credits

- XDA thread [Sonim XP8 (Root?)](https://xdaforums.com/t/sonim-xp8-root.3851187/):
  smokeyou (EDL backup and Magisk method, loader bundle, AT&T userdebug
  images), portsample (Android 10 root notes), thenatti (TWRP builds for
  stock), and the posters who reported bricks and recoveries.
- [bkerler/edl](https://github.com/bkerler/edl): Qualcomm EDL tooling.
- [Magisk](https://github.com/topjohnwu/Magisk) by topjohnwu.
- [TrebleDroid](https://github.com/TrebleDroid/treble_experimentations) and
  phh (Pierre-Hugues Husson): the Treble GSI and the IMS app.
- [MindTheGapps](https://gitlab.com/MindTheGapps/vendor_gapps).
- The Android Open Source Project, apktool, smali/baksmali, LineageOS
  extract-tools.

## Trademarks

Sonim and XP8 are trademarks of Sonim Technologies. Qualcomm and Snapdragon
are trademarks of Qualcomm Incorporated. Android and Google are trademarks of
Google LLC. This project is not affiliated with or endorsed by any of them.
