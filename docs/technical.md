# Technical notes

For maintainers and porters: the device facts the scripts depend on, what
the build changes in each image and why, and the bootloader unlock
mechanism.

## Contents

- [Device facts](#device-facts)
- [Partitions](#partitions)
- [Kernel limits](#kernel-limits)
- [What the build changes and why](#what-the-build-changes-and-why)
  - [Boot image](#boot-image)
  - [Vendor image](#vendor-image)
  - [Init script and boot-time fixes](#init-script-and-boot-time-fixes)
  - [System image changes](#system-image-changes)
- [Bootloader unlock](#bootloader-unlock)
- [A/B updates](#ab-updates)

## Device facts

| Property | Value |
|---|---|
| Model / device | `XP8800` / `XP8800` |
| SoC | Snapdragon 630 (SDM630), platform `sdm660`; Sahara HWID `0x000ac0e1` |
| RAM / display | 3.7 GB / 1080x1920 |
| Storage | eMMC, one LUN, 512-byte sectors (about 58 GiB) |
| Stock software | Android 10, build `8A.0.0-03-10.0.0-00.40.00`, security patch 2020-09-01, kernel `4.4.205-perf+`, launch API 25 |
| Layout | A/B (`ro.build.ab_update=true`), legacy system-as-root (`/` is `/dev/root`), no dynamic partitions, no `metadata` partition |
| Treble | Not enabled on stock (`ro.treble.enabled=false`, `ro.vndk.lite=true`, VNDK 29). `/vendor` is a symlink to `/system/vendor`; `vendor_a`/`vendor_b` hold empty filesystems. The vendor SELinux policy is already split, and the stock DTB carries a disabled early-mount `vendor` fstab node. The build moves `/system/vendor` to `vendor_a` |
| Verified boot | AVB 1.0 / dm-verity; boot image header v0 with gzip kernel, appended DTBs and an appended AVB1 signature |
| Encryption on stock | FDE (`forceencrypt=footer`) |
| Bootloader | edk2 LinuxLoader in `abl_a`; the stock ABL has no `fastboot flashing` or `flash:` command |
| EDL | Qualcomm 9008 in the boot ROM, reachable by keys from any state; needs the Sonim-signed firehose loader |
| USB IDs | `05c6:9008` (EDL), `18d1:d00d` (fastboot) |

Both slots hold the same firmware on the supported build, except
`mdtpsecapp_b` (a different build of the MDTP trusted app). `abl_b` holds the
stock ABL. The inactive slot's partitions carry Qualcomm's inactive type
GUID (`77036cd4-…`); a slot switch swaps the type GUIDs of every `_a`/`_b`
pair. The install writes slot `_a`. Slot `_b` is used only after
`scripts/enable-ab.sh` has prepared it, see [A/B updates](#ab-updates).

## Partitions

Partition facts the scripts rely on. `dump-stock.sh` checks that the
required partitions exist; `unlock.sh` checks the `abl_a` and `frp` sizes.

| Partition | Size | Role |
|---|---|---|
| `boot_a` | 64 MiB | Kernel and ramdisk (recovery is inside boot). The GSI boot image goes here |
| `system_a` | 4 GiB | Stock system with `/system/vendor`; the GSI goes here. `assemble.sh` reads the stock vendor tree, properties and libraries from the backup copy |
| `vendor_a` | 1 GiB | Empty on stock; the assembled vendor image goes here |
| `abl_a` | 1 MiB | Bootloader. `unlock.sh` writes the userdebug ABL; `--relock` writes the stock one back |
| `abl_b`, `mdtpsecapp_b`, `modem_b` | 1 MiB, 4 MiB, 110 MiB | Slot-b firmware; `enable-ab.sh` writes the userdebug ABL and copies `mdtpsecapp_a` and `modem_a`. `--relock` writes the stock `abl_b` back |
| `boot_b`, `system_b`, `vendor_b` | as slot a | Written by `flash.sh --slot b` or by `update_engine` (`ota-update.sh`) |
| `frp` | 512 KiB | Factory Reset Protection data; the last byte (offset 524287) is the OEM-unlock flag |
| `misc` | 1 MiB | Bootloader control block; stock content is all zeros |
| `userdata` | about 46 GiB | Erased on install, formatted by `fs_mgr` on first boot |
| `modemst1`, `modemst2`, `fsg`, `fsc` | | Per-unit modem NV (IMEI, calibration); only `--restore-nv` writes them |
| `persist` | 32 MiB | Per-unit Wi-Fi, Bluetooth and sensor calibration; Magisk pre-init data |
| `devinfo` | 4 KiB | All zeros on stock; not used for the lock state, never written |
| `gpt_main0` (backup file) | 20 sectors | Protective MBR, primary GPT header and entries; written with `edl ws 0` only for [recovery](troubleshooting.md#phone-ends-in-fastboot) |

Never written by any script: `xbl*`, `tz*`, `rpm*`, `hyp*`, `pmic*`,
`keymaster*`, `keystore`, `devcfg*`, `cmnlib*`, `devinfo`. A bad keymaster
write is the hard brick reported for this phone.

`flash.sh` uses fixed sizes for `boot`, `vendor`, `system` and `misc`
because the userdebug ABL answers no `getvar partition-size` query.
`fastboot` splits images larger than `max-download-size` (512 MiB) into
sparse chunks.

## Kernel limits

The stock 4.4 kernel stays; its source is not published. These limits
follow from it and do not change with a newer GSI:

- No eBPF (`CONFIG_BPF_SYSCALL` off): `NetBpfLoad` refuses to run. Mobile
  data and Wi-Fi work through TrebleDroid's fallback; per-app data usage and
  limits do not.
- No `userfaultfd`: ART uses its fallback garbage collector.
- memcg on cgroup v1 only, no PSI (`/proc/pressure`), no cgroup v2 freezer:
  `lmkd` uses vmstat for reclaim detection; the cached-app freezer is
  unavailable.
- Pre-4.6 ext4 encryption with Qualcomm PFK/ICE: file-based encryption v1
  only; no metadata encryption.

## What the build changes and why

### Boot image

[`build/lib/repack.py`](../build/lib/repack.py) rebuilds the stock `boot_a`
(or its Magisk-patched copy) and keeps the kernel, ramdisk, offsets and OS
version:

- In every appended DTB with `/firmware/android/fstab/vendor`, it sets
  `status = "okay"` and `fsmgr_flags = "wait,slotselect"`. First-stage init
  then mounts `vendor_a` at `/vendor`. `verify` is dropped because the
  vendor image has no verity metadata. No ramdisk fstab is needed.
- It appends `androidboot.selinux=permissive` to the kernel command line.
  Enforcing mode has not been attempted.
- The appended AVB1 signature is carried over and no longer matches; the
  unlocked bootloader accepts it (`verifiedbootstate=orange`).

With `--magisk`, [`build/lib/magisk-patch.sh`](../build/lib/magisk-patch.sh)
first runs Magisk's own `boot_patch.sh` on the host with the flags the
Magisk app picks on this phone: `KEEPVERITY=true`, `KEEPFORCEENCRYPT=true`,
`PATCHVBMETAFLAG=false` (AVB 1.0 has no vbmeta flags), `LEGACYSAR=true`.

### Vendor image

[`build/lib/mkvendor.py`](../build/lib/mkvendor.py) reads `/system/vendor`
from the raw stock `system_a` with `debugfs` (no root, no loop mount) and
writes a 1 GiB ext4 image. Stock entries keep their owner, mode and file
capabilities; SELinux labels are recomputed from `plat_file_contexts` and
`vendor_file_contexts`, as for a real `/vendor` partition. Timestamps and
the filesystem UUID are fixed. The image is checked against the planned
metadata before it is written to `out/`. The changes, with
[`vendor/fs_config.tsv`](../vendor/fs_config.tsv) listing every added or
removed entry:

| Change | Why | Source |
|---|---|---|
| `fstab.qcom`: `verify` dropped from the `system` line | The GSI has no verity metadata | [`vendor/fstab.qcom.diff`](../vendor/fstab.qcom.diff) |
| `fstab.qcom`: `userdata` uses `formattable,fileencryption=ice:aes-256-cts:v1` in place of `forceencrypt=footer,crashcheck` | Android 16 has no FDE. On this kernel `ice` selects the private mode that routes contents encryption through the eMMC inline crypto engine. `aes-256-xts` passes a loop-device test but on `/data` causes dm-verity and EIO errors and a hard reset during the first boot. `formattable` lets `fs_mgr` format an erased `userdata`; this is why the install uses `fastboot erase userdata` and not `fastboot format`, whose host mke2fs 1.47 sets a feature the phone's e2fsck rejects | [`vendor/fstab.qcom.diff`](../vendor/fstab.qcom.diff) |
| `ro.vndk.version=29`, `ro.vndk.lite=true` | Stock sets them on `/system`, which the GSI replaces | [`vendor/props/vndk.prop`](../vendor/props/vndk.prop) |
| Device properties from stock `/default.prop` (`ro.zygote`, `ro.bionic.*`, `dalvik.vm.isa.*`, `ro.oem_unlock_supported`), `/system/sdm660_64.prop` and `/system/build.prop`, appended to the vendor `build.prop`; `/system/vendor/` rewritten to `/vendor/` | They live on the stock `system_a`. Without `ro.zygote`, init cannot expand `init.${ro.zygote}.rc` and `odrefresh` aborts. Build identity, product, Google, Treble, APEX, carrier and dexopt properties are skipped | `mkvendor.py` |
| `ro.telephony.default_network=9,9`, `telephony.lteOnCdmaDevice=0` | Replace the stock values: LTE/GSM/WCDMA on both slots, no CDMA | [`vendor/props/override.prop`](../vendor/props/override.prop) |
| `ro.adb.secure=1`, `ro.debuggable=0`, `persist.sys.usb.config=none` | Stock user-build adb policy: host authorization, no `adb root`. The GSI's `/system/build.prop` enables adb, so adb is off after every wipe until turned on in Developer options | [`vendor/props/append.prop`](../vendor/props/append.prop) |
| `ro.product.property_source_order=vendor,odm,product,system_ext,system` | The phone reports the stock model Sonim XP8800 and fingerprint `Sonim/XP8800/XP8800:16/...`; Device name defaults to `XP8800` | [`vendor/props/append.prop`](../vendor/props/append.prop) |
| `ro.vendor.xp8.layout=2` | The repo's overlays, side keys, boot scripts and audio patches are on `system`, where A/B OTAs carry them; `xp8-gsi.rc` there acts only with this property, and `ota.json` names the lowest layout a payload needs (`min_vendor_layout`) | [`vendor/props/append.prop`](../vendor/props/append.prop) |
| `ro.telephony.sim_slots.count=2`, `ro.com.android.dataroaming=false` | Two SIM slots; roaming off by default | [`vendor/props/append.prop`](../vendor/props/append.prop) |
| 190 libraries copied from stock `/system/lib*` and `/system/product/lib*` | Stock vendor blobs link against them (mostly non-VNDK HIDL interface libraries, plus `libdrm`, `libchrome`, `libinput` and others). The GSI's linker namespace for vendor processes cannot see them on `/system`; without them about 40 HALs fail with `CANNOT LINK EXECUTABLE`, including `qcrild`, audio, camera and the hardware composer. The list is specific to this stock build and GSI | [`vendor/libs.txt`](../vendor/libs.txt) |
| `etc/cgroups.json` mounting memcg v1 at `/dev/memcg` | Android 16's `cgroups.json` mounts memory cgroups only on v2. Without a memcg mount `lmkd` exits and `system_server` dies waiting for its socket | [`vendor/cgroups.json`](../vendor/cgroups.json) |
| `libxp8shim.so`, added as a dependency of `/vendor/lib/libgui_vendor.so` | Provides `PermissionCache::checkPermission`, `fgetfilecon_raw` and `setsockcreatecon_raw`, which the stock 32-bit vendor libraries need and the GSI's VNDK 29 `libbinder`/`libselinux` lack | [`vendor/shim/`](../vendor/shim/) |
| `xp8-vibrator` AIDL `IVibrator` service | Android 16 uses only the AIDL vibrator interface; the stock HAL is HIDL. The service switches `/sys/class/timed_output/vibrator/enable` | [`vendor/vibrator/`](../vendor/vibrator/) |
| `xtra-daemon` byte patch (four instructions in `XtraIzatAdapter::onReceiveXtraServers`) | The modem reports XTRA servers as bare host names, which the daemon rejects as "unsupported url"; the patch loads its built-in `https://path{1,2,3}.xtracloud.net` URLs. The input and output SHA-256 are checked | [`vendor/xtra-daemon.bpatch`](../vendor/xtra-daemon.bpatch) |
| `CACertService` re-signed with the AOSP test platform key; its `oat/` removed | It runs as `android.uid.phone`. With the Sonim signature the package manager skips it, and XTRA's HTTPS download blocks waiting for `vendor.qti.hardware.cacert@1.0` | `mkvendor.py` |

### Init script and boot-time fixes

[`vendor/xp8-gsi.rc`](../vendor/xp8-gsi.rc) (init triggers) and
[`vendor/xp8-gsi.sh`](../vendor/xp8-gsi.sh) (started at `boot_completed`),
installed on `system` as `/system/etc/init/xp8-gsi.rc` and `/system/bin/`,
so that A/B OTAs update them. Every trigger also needs
`ro.vendor.xp8.layout=2`, which the vendor image sets: an older vendor
image still carries its own copy of these files.

| Item | Why |
|---|---|
| `on post-fs`: [`xp8-vendor-patch.sh`](../vendor/xp8-vendor-patch.sh) applies `/system/etc/xp8/vendor-patches/*.diff` ([`vendor/audio/`](../vendor/audio/)) to copies of the stock vendor files in `/dev/xp8` with toybox `patch` and bind-mounts them over the originals | The patched files hold Qualcomm and Sonim content, so `system` carries only the diffs; the audio HAL starts later and reads the patched copies |
| `on late-fs`: unmount TrebleDroid's binds over `/vendor/lib{,64}/libpdx_default_transport.so` | TrebleDroid masks the library at `post-fs`; the stock `libgui_vendor.so` needs it. Without the unmount the OMX media HAL fails to link and crash-loops, and on a fresh install Rescue Party then stores a recovery wipe prompt in `misc` |
| `on late-fs`: `ro.config.media_vol_steps` 15 and `ro.config.media_vol_default` 5, set with `resetprop_phh` | TrebleDroid's `rw-system.sh` sets 25 steps and a default of 8 for every device at `post-fs`. Stock sets neither, so Android uses 15 steps with a default of 15/3 = 5. The volume curves are unchanged; only the step size differs (#11) |
| `on post-fs && property:ro.boot.veritymode=logging`: reboot with reason `dm-verity enforcing` | After a dm-verity error (for example a failed stock boot) the ABL stays in dm-verity EIO mode (`androidboot.veritymode=logging`). Android 16's `update_verifier` then reboots every boot of a slot not yet marked successful, until the ABL marks `boot_a` unbootable. This reboot reason switches the ABL back to enforcing, at the cost of one extra reboot on the first boot |
| Stop `sudaemon`; with Magisk, `/system/xbin/su` becomes a symlink to Magisk | Magisk `su` is the only root path. A bind mount would be shadowed by later TrebleDroid mounts |
| `vendor.xp8-vibrator` service | Starts the AIDL vibrator with the `hal_vibrator_default` domain |
| `vendor.xp8-keys` service, started at `boot_completed` | Runs the side-key daemon as root (`su` domain, like `xp8-gsi`); it reads `/dev/input`, sends broadcasts and starts activities. init restarts it if it exits |
| `persist.sys.phh.adb_secure=1` | TrebleDroid hook that sets `ro.adb.secure=1` and restarts adbd at each boot |
| Restart the phone process once when the SIM is loaded and no IMS service is bound | TrebleDroid enables CAF IMS (`persist.sys.phh.ims.caf`) after the phone process has started on the first boot, so IMS would bind only from the second boot |
| Set `com.android.webview` when the WebView provider is null | After a wipe, WebViewUpdateService picks no provider and does not retry; Play services setup screens then crash |
| Mount `emulated;0` again until MediaProvider sees `external_primary` (up to three tries); `persist.xp8.no_sm_mount=1` turns it off | StorageManagerService does not record the unlock of user 0 on this GSI, so `/sdcard` stays unavailable to MediaProvider |
| `persist.wm.debug.predictive_back_anim=0` | Turns off the predictive back animation |

### System image changes

[`build/build-system.sh`](../build/build-system.sh) starts from TrebleDroid
`ci-20250617` `system-td-arm64-vanilla-old`. The `-old` variant carries the
VNDK 28/29 libraries and `/system/etc/selinux/mapping/29.0.cil`, which the
Android 10 vendor policy needs.

| Change | Why | Source |
|---|---|---|
| MindTheGapps 16 | Google apps and Play services | [`build/inputs.lock`](../build/inputs.lock) |
| AOSP QuickSearchBox (`/system/product/app/QuickSearchBox`) removed | The Google app from MindTheGapps provides search. With both installed, QuickSearchBox comes first as the global search activity, whose widget Launcher3's search bar shows | [`build/build-system.sh`](../build/build-system.sh) |
| `XP8GmsQueryable` overlay (`/system/product/overlay`): `config_forceQueryablePackages` adds Google Play services | Play services runs in its own uid (its `sharedUserMaxSdkVersion` keeps it out of `com.google.uid.shared` on Android 16), and the framework reads `forceQueryable` only from `<application>`, where GmsCore does not set it. Without the overlay, an app without a `<queries>` entry for `com.google.android.gms` cannot see Play services: the client library reports it missing ("won't run without Google Play services") although it is installed. Google's own builds list Play services through a framework overlay that MindTheGapps does not ship. The array keeps the three AOSP entries | [`system/rro/XP8GmsQueryable/`](../system/rro/XP8GmsQueryable/) |
| Tethering APEX: null checks for `sLocalNetBlockedUidMap` in `BpfNetMaps`; the APEX is rebuilt, re-signed with AOSP's tethering test key (the key TrebleDroid's APEX already carries, or `APEX_KEY`) and stored uncompressed | Without eBPF the map is null and the connectivity service dereferences it | [`system/apexfix/`](../system/apexfix/) |
| phh's IMS app `ims-caf-u` as `ImsCafXp8`, platform-signed | VoLTE. The Sonim IMS HAL numbers `IImsRadioIndication` transactions one higher than the interface the app implements from code `0x17` on; the patch drops code `0x17` and shifts the higher codes down by one | [`system/ims/`](../system/ims/) |
| Launcher3 `isTaskbarEnabled` | With taskbar/navigation bar unification, Launcher3 enables its Taskbar even when the navigation bar is off for the hardware keys; the patch enables it only when the window manager has a navigation bar | [`system/launcher3/`](../system/launcher3/) |
| Launcher3 `Utilities.SHOULD_SHOW_FIRST_PAGE_WIDGET` set to true | The first home screen then has no fixed search bar, as in LineageOS Trebuchet (`QSB_ON_FIRST_SCREEN` false): the top row is free for icons and widgets, and a search widget can be added, moved or removed like any other. R8 folded `QSB_ON_FIRST_SCREEN` (true) into every read of this flag, so setting it to true takes the `QSB_ON_FIRST_SCREEN` false paths. `WorkspaceItemSpaceFinder.findSpaceForItem` also reports the screen an auto-added icon lands on as a new screen; without it, an icon placed on an emptied first screen (which the workspace removes, while the model still offers it) is not shown until the launcher restarts. Android 16 r1 has this with `QSB_ON_FIRST_SCREEN` false too; later Launcher3 (LineageOS 23.2) binds every screen its added items use | [`build/build-system.sh`](../build/build-system.sh) |
| `AuthService` in `services.jar` keeps the HIDL fingerprint configuration when AIDL instances exist | The stock fingerprint HAL is HIDL; unpatched, `AuthService` drops the HIDL configuration (`config_biometric_sensors`) when AIDL fingerprint instances are declared | [`system/services/`](../system/services/) |
| Messaging manifest gains `RECEIVE_WAP_PUSH` and `READ_CELL_BROADCASTS` | Without them Android 16 rejects the GSI's Messaging as the default SMS app. The patched APK replaces the GSI's | [`system/messaging/`](../system/messaging/) |
| AOSP Provision (`/system/system_ext/priv-app/Provision`) removed | With it, two activities handle `SETUP_WIZARD`; the package manager then grants nothing to Google SetupWizard, which crash-loops on the Wi-Fi screen | [`build/build-system.sh`](../build/build-system.sh) |
| Side-key daemon `/system/etc/xp8/xp8-keys.dex` and the XP8 Buttons app `/system/product/app/XP8Buttons` | On stock, Sonim's SPCCService turned the PTT and SOS keys into `com.sonim.intent.action.PTT_KEY_DOWN/UP` and `SOS_KEY_DOWN/UP` broadcasts, which push-to-talk apps such as Zello receive also in the background; a Settings screen (Programmable Keys) chose the app per key and a press-and-hold timer. Both are Sonim system apps the GSI cannot install. The daemon reads the gpio-keys device's scancodes directly (PTT 149, SOS 148, camera 766), so it does not depend on the key layout: the stock `gpio-keys.kl` stays unchanged, although Android 16 rejects it for Sonim's `PTT` and `SOS` labels and falls back to `Generic.kl`. Per key, the daemon forwards press and release to a chosen push-to-talk app (or all of them) with the same broadcasts, after an optional hold time, or runs a short-press and a long-press action (flashlight, camera, a call, an app, voice assistant, play/pause, Do Not Disturb); a long press lasts Android's touch & hold delay (`long_press_timeout`, under Accessibility), as on the touch screen. While PTT is forwarded it sets the stock audio HAL's `ptt_call_state=on`, as Sonim's `com.kodiak.pttExtensions` did; for `AUDIO_SOURCE_VOICE_COMMUNICATION` the HAL then uses its PTT microphone setup (`voice-speaker-qmic`). XP8 Buttons stores the choices in its `files/keys.properties`; without it PTT and SOS go to every push-to-talk app and the camera key opens the camera | [`vendor/keys/`](../vendor/keys/) |
| `XP8FrameworksRes` overlay (`/system/product/overlay`) | `config_showNavigationBar=false` (hardware Back, Home and Recents keys); `config_biometric_sensors` declares the fingerprint sensor; `config_locationProviderPackageNames` lists Google Play services and the fused provider, so the default permission grants give Play services location | [`vendor/rro/XP8FrameworksRes/`](../vendor/rro/XP8FrameworksRes/) |
| `XP8Settings` and `XP8SystemUI` overlays (`/system/product/overlay`) | Fingerprint enrolment text and sensor position for the sensor in the Home button; `config_show_wifi_hotspot_speed=false`, so Settings uses the hotspot screen that hides WPA3 when the hotspot reports no SAE (the stock hostapd HAL is 1.1; the newer screen offers WPA3 regardless); SystemUI status bar padding and the app-ops indicator dot | [`vendor/rro/`](../vendor/rro/) |
| Audio, patched at boot: `SND_DEVICE_OUT_SPEAKER_SAFE` mapped to the speaker ACDB ID; `speaker-safe` mixer paths routed to the speaker | Android 16 selects the speaker-safe device for ringtones during a call and for notifications; the stock files have no ACDB ID for it and route its mixer paths to the default device | [`vendor/audio/`](../vendor/audio/) |

The GSI is a userdebug build (`test-keys`); the vendor properties restore
the stock adb policy. The platform key is public, so anyone who can install
a package with system privileges can already replace system code; signing
the tethering APEX with AOSP's public key does not add a new class of
attacker.

## Bootloader unlock

The stock Android 10 ABL has no `fastboot flashing` commands and does not
keep its lock state in `devinfo`. The AT&T Android 8.1 userdebug ABL
(`abl.elf`, 110592 bytes) has `fastboot flashing unlock`, boots stock
Android 10, and carries the same root certificate and `HW_ID` as the stock
ABL, with `SW_ID` `0x1C` (rollback version 0), so secure boot accepts it.

[`scripts/unlock.sh`](../scripts/unlock.sh), in one EDL session:

1. writes the userdebug ABL to `abl_a`, zero-padded to the 1 MiB partition
   so that no stock bytes remain, and reads it back;
2. sets the last byte of `frp` (offset 524287) to `0x01`, the OEM-unlock
   flag that `fastboot flashing get_unlock_ability` reads, and reads it
   back;
3. rewrites the `frp` checksum when `frp` holds Android's data block
   (magic `19901873` at byte 32): bytes 0-31 are the SHA-256 of 32 zero
   bytes followed by bytes 32 to the end. Android's PersistentDataBlockService
   reformats `frp` at boot when the checksum does not match, which would
   clear the byte;
4. checks that nothing else in `frp` changed.

Then `fastboot flashing unlock` sets the unlocked state and wipes userdata.
`fastboot flashing unlock_critical` is not needed. Relocking is the reverse:
`fastboot flashing lock` while the userdebug ABL is still in `abl_a`, then
the stock `abl_a` and a zeroed `frp` over EDL
([restore.md](restore.md#relock-the-bootloader)).

## A/B updates

Releases carry an A/B OTA payload when CI holds the signing key
(`build/make-ota.sh`, packed with [avbroot](https://github.com/chenxiaolong/avbroot)).
The payload is a partial update holding only `system`, signed with the key
whose certificate the system image carries in
`/system/etc/security/otacerts.zip` (`build/ota/xp8-ota.x509.pem`). `boot`
and `vendor` are built from each user's own backup, so no release can carry
them. For partial updates `update_engine` adds a copy from the running slot
for every partition in `ro.vendor.build.ab_ota_partitions`
(`boot,system,vendor`, from the vendor image) that the payload omits.

| Step | What runs |
|---|---|
| Prepare slot b once | `enable-ab.sh` over fastboot: userdebug ABL to `abl_b` (XBL loads the ABL of the active slot; the stock ABL has no `flash` or `set_active`), `mdtpsecapp_a` and `modem_a` to `_b` |
| Check and install | Settings → System update opens `XP8Updater` (`/system/system_ext/priv-app`, platform-signed, intent-filter priority 100 so it comes before Play services' `SystemUpdateActivity`). It reads `releases/latest/download/ota.json`, compares its `tag` with `ro.xp8.release` (set by `build-system.sh` from `XP8_RELEASE`) and `min_vendor_layout` with `ro.vendor.xp8.layout`, and calls `UpdateEngine.applyPayload` with the payload URL, so `update_engine` streams it from GitHub. `ota-update.sh` does the same from a computer over adb |
| Install | `update_engine` writes `system` to the other slot, copies `boot` and `vendor`, and makes that slot active through the boot control HAL (`bootctrl.sdm660`) |
| First boot | The ABL boots the new slot with retry count 7; `update_verifier` and `boot_control` mark it successful once Android has booted |
| Fallback | A slot that is not marked successful after 7 boots is marked unbootable, and the ABL switches back to the previous slot |

A dm-verity failure does not trigger the fallback: the ABL uses AVB 1.0 on
this phone, and only AVB 2.0 failures mark a slot unbootable.
`flash.sh --switch a` or `--switch b` changes the active slot from fastboot
without writing.
