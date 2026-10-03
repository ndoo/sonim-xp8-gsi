# Troubleshooting

Symptoms and fixes, then manual recovery for the cases where the scripts
cannot run. Variables (`SERIAL`, `BACKUP`, `EDL`, `EDL_LOADER`) are those of
[install.md](install.md#variables).

Report problems as an issue at
<https://github.com/ndoo/sonim-xp8-gsi/issues>, or by email to
<me@ndoo.sg>. Leave out serials, IMEI, ICCID and backup files.

## Contents

- [Symptoms](#symptoms)
- [Manual recovery](#manual-recovery)
  - [EDL shell function](#edl-shell-function)
  - [Unpack and check a backup image](#unpack-and-check-a-backup-image)
  - [Phone ends in fastboot](#phone-ends-in-fastboot)
  - [misc and the recovery loop](#misc-and-the-recovery-loop)
  - [Fastboot stall](#fastboot-stall)
  - [No fastboot after the ABL write](#no-fastboot-after-the-abl-write)
  - [Unlock: get_unlock_ability stays 0](#unlock-get_unlock_ability-stays-0)
  - [Magisk app bootloop on a locked bootloader](#magisk-app-bootloop-on-a-locked-bootloader)
  - [FRP prompt after a restore](#frp-prompt-after-a-restore)
  - [Verity warning on stock](#verity-warning-on-stock)
  - [WebView](#webview)

## Symptoms

| Symptom | Fix |
|---|---|
| "Can't load Android system", or the phone keeps booting to recovery | A wipe prompt is stored in `misc`. See [misc and the recovery loop](#misc-and-the-recovery-loop) |
| `fastboot` prints `could not clear input/output pipe`, `unknown command`, or hangs after a flash | See [Fastboot stall](#fastboot-stall) |
| The hotspot does not start after updating from an earlier install | A hotspot saved with WPA3 security cannot start: the stock hostapd HAL (1.1) has no WPA3 (SAE). Open Settings > Network & internet > Hotspot & tethering > Wi-Fi hotspot > Security and choose WPA2-Personal |
| `fastboot format:ext4 userdata` was used and `/data` does not mount | Use `fastboot erase userdata` (`scripts/flash.sh --wipe`): the host's mke2fs 1.47 sets a feature the phone's e2fsck rejects |
| Windows: no 9008 device is found, or the script names a driver other than WinUSB | See [EDL driver](windows.md#edl-driver) in windows.md |
| An app says it "won't run without Google Play services, which are missing", while Play services is installed | Releases before the `XP8GmsQueryable` overlay hide Play services from apps that do not declare it in `<queries>`. Update the system image (`scripts/flash.sh --only system`); `verify-device.sh` checks "Play services visible" |
| Play Store shows "Couldn't sign in"; `adb logcat` shows `BAD_AUTHENTICATION` | Google revoked the stored sign-in. Open Settings > Passwords, passkeys & accounts, tap the Google account and sign in again when asked (or remove and add it) |
| `adb devices` is empty after the install | adb is off after a wipe: enable USB debugging in Developer options and tick "Always allow" |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the prompt |
| Apps that show web pages crash or are blank, or `verify-device.sh` fails "WebView provider set" | See [WebView](#webview) |
| Stock Android 10 shows "can't be trusted and may not work properly" at each boot | Expected after a restore; press Power. See [Verity warning on stock](#verity-warning-on-stock) |
| The phone boots to fastboot instead of Android | See [Phone ends in fastboot](#phone-ends-in-fastboot) |
| The GSI reboots about 20 s into every boot and ends in fastboot | The bootloader is in dm-verity EIO mode and the vendor image lacks the verity EIO reboot. `fastboot -s "$SERIAL" set_active a`, then flash a vendor image assembled from this repository |
| The GSI stays on the boot splash | Hold Power 10-15 s; the next boot continues |
| `su: request rejected` or `Permission denied` from `adb shell su` | Magisk stored a deny; Magisk > Superuser > Shell on |
| `unlock.sh`: `get_unlock_ability is 0` | See [Unlock: get_unlock_ability stays 0](#unlock-get_unlock_ability-stays-0) |
| Black screen after `unlock.sh` wrote `abl_a`; neither Android nor fastboot | See [No fastboot after the ABL write](#no-fastboot-after-the-abl-write) |
| Red "Your device is corrupt. It can't be trusted and will not boot" loop | See [Magisk app bootloop on a locked bootloader](#magisk-app-bootloop-on-a-locked-bootloader) |
| Stock setup asks for a screen lock or Google account from earlier | See [FRP prompt after a restore](#frp-prompt-after-a-restore) |
| edl waits at "Waiting for the device" on macOS | Accept "Allow accessory to connect", then replug |
| edl fails to load libusb on macOS | `export DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib` (Intel: `/usr/local/lib`) |
| edl hangs on Linux | ModemManager or missing udev rules; see [install.md](install.md#tools) |
| Traceback with `USBError(5)` after `edl reset` | Expected; the phone is rebooting |
| A script says another device is attached | Disconnect every phone except `$SERIAL` |
| `edl skipped the Sahara handshake` | Hold Power about 10 s, enter EDL again by keys, run the script again |
| A script says `do not reset the phone` after an EDL write | The write or its read-back failed. Leave the phone in EDL and write the partition again from your backup ([EDL shell function](#edl-shell-function)), or run `scripts/restore-stock.sh --edl` |

## Manual recovery

Use these only when the scripts cannot run. Every write below targets one
named partition with an image from your own backup, checked against the
backup's `SHA256SUMS`. Never write `xbl*`, `tz*`, `rpm*`, `hyp*`, `pmic*`,
`keymaster*`, `keystore`, `devcfg*`, `cmnlib*` or `devinfo`, and never
another unit's `modemst1`, `modemst2`, `fsg`, `fsc` or `persist`. Slot `_b`
is a fallback only after `scripts/enable-ab.sh` and a full install to it.

### EDL shell function

```sh
# macOS only: edl needs Homebrew's libusb (Intel Macs: /usr/local/lib)
export DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib
E() { "$EDL" --loader="$EDL_LOADER" --memory=emmc "$@"; }
```

Enter EDL by keys: power off (or pull and reinsert the battery), hold Vol+
and Vol-, press Power, and release all keys at the black screen. If USB
drops right after the loader upload, repeat. The loader stays resident
after a command exits, so several `E` commands can run in a row.

`E w <partition> <file>` writes, `E r <partition> <file>` reads. To leave
EDL, run `"$EDL" --loader="$EDL_LOADER" reset` (it rejects `--memory`), or
hold Power about 10 s.

### Unpack and check a backup image

```sh
mkdir -p work/restore
zstd -d "$BACKUP/boot_a.bin.zst" -o work/restore/boot_a.bin
shasum -a 256 work/restore/boot_a.bin
grep ' boot_a.bin$' "$BACKUP/SHA256SUMS"           # the two hashes must match
```

Replace `boot_a` with the partition you need. Do not write an image whose
hashes differ.

### Phone ends in fastboot

After several failed boots the bootloader marks `boot_a` unbootable and
starts fastboot instead.

- **Unlocked bootloader:**

  ```sh
  fastboot -s "$SERIAL" set_active a
  fastboot -s "$SERIAL" reboot
  ```

- **Locked stock bootloader** (after `restore-stock.sh --relock`): it
  rejects `set_active`, and `fastboot reboot recovery` also lands in
  fastboot. Write the primary GPT from your backup over EDL; it differs from
  the current one only in the `boot_a` attribute byte and the CRCs. Unpack
  and check `gpt_main0` as [above](#unpack-and-check-a-backup-image), enter
  EDL by keys, then:

  ```sh
  E ws 0 work/restore/gpt_main0.bin
  E w misc work/restore/misc-wipe.bin       # only if userdata still needs formatting
  "$EDL" --loader="$EDL_LOADER" reset
  ```

  `gpt_main0.bin` holds the protective MBR, the GPT header and the partition
  entries (20 sectors on the XP8800). Leave the backup GPT as it is.
  `misc-wipe.bin` is the recovery wipe request that `restore-stock.sh`
  leaves in `work/restore/`.

### misc and the recovery loop

`misc` holds the bootloader control block. Two commands stored there cause
loops:

- **Recovery loop.** Once recovery starts with a wipe prompt (for example
  "Can't load Android system" after a crash loop during the first boot),
  the command stays in `misc`. "Try again" and a data wipe in recovery do
  not clear it.
- **Fastboot loop.** `adb reboot bootloader` writes `bootonce-bootloader`.
  If the bootloader does not clear it, the phone keeps returning to
  fastboot.

The stock content of `misc` is 1 MiB of zeros (SHA-256
`30e14955ebf1352266dc2ff8067e68104607e750abb9d3b36582b8af909fcb58`). From
fastboot (power off, hold Vol-, press Power):

```sh
scripts/flash.sh --misc-only
```

Without fastboot, write your backup's `misc` over EDL, unpacked and checked
as [above](#unpack-and-check-a-backup-image):

```sh
E w misc work/restore/misc.bin
```

### Fastboot stall

Killing a fastboot command mid-transfer, or sending one while the
bootloader is still writing, can stall the phone's fastboot USB endpoint:
the phone stays enumerated, and every later command prints its status line
and times out, or fails with `unknown command`. Only a power cycle clears
it: hold Power 10-15 s.

- Do not retry or interrupt fastboot commands; avoid `fastboot getvar all`.
- The bootloader acknowledges a write at once and keeps writing for about
  1 s per 15 MB. `scripts/flash.sh` waits after each write; running it again
  writes every image again.
- After `scripts/flash.sh` prints `all writes done`, every write is
  complete; a stall on `fastboot reboot` only needs the Power hold.

### No fastboot after the ABL write

If the phone reaches neither Android nor fastboot after `unlock.sh` wrote
`abl_a`, the ABL failed to load. Write back your stock `abl_a` over EDL:
unpack and check `abl_a` as [above](#unpack-and-check-a-backup-image),
enter EDL by keys, then:

```sh
E w abl_a work/restore/abl_a.bin
"$EDL" --loader="$EDL_LOADER" reset
```

`unlock.sh` also saved the `abl_a` and `frp` it replaced in
`$BACKUP/unlock-<date>/`. Do not switch to slot `_b` unless `scripts/enable-ab.sh` has prepared it.

### Unlock: get_unlock_ability stays 0

> [!NOTE]
> Untested: this procedure has not been run as written.

`fastboot flashing get_unlock_ability` reads the last byte of `frp`. If it
stays `0` after `unlock.sh`, set the byte as root in Android instead. This
needs Magisk root on stock Android 10 and wipes the phone one more time.
`abl_a` already holds the userdebug ABL from `unlock.sh`.

1. Patch your stock `boot_a` with Magisk on the host, with dm-verity kept
   (`build/lib/magisk-patch.sh` uses the Magisk app's flags for this phone,
   including `KEEPVERITY=true`):

   ```sh
   mkdir -p work/magisk-root
   zstd -d "$BACKUP/boot_a.bin.zst" -o work/magisk-root/boot_a.bin
   shasum -a 256 work/magisk-root/boot_a.bin
   grep ' boot_a.bin$' "$BACKUP/SHA256SUMS"          # the two hashes must match
   docker build --platform linux/amd64 -t xp8-gsi-build build
   docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" -e HOME=/tmp \
     -v "$PWD:/src" -w /src xp8-gsi-build bash -c 'build/fetch.sh magisk/ &&
       bash build/lib/magisk-patch.sh cache/magisk/Magisk-v30.7.apk work/magisk-root/boot_a.bin work/magisk-root/boot_a-magisk.img work/magisk-root/w'
   ```

2. Write it to `boot_a` over EDL and compare the read-back (the read returns
   the full 64 MiB partition):

   ```sh
   adb -s "$SERIAL" reboot edl
   E w boot_a work/magisk-root/boot_a-magisk.img
   E r boot_a work/magisk-root/readback.img
   head -c "$(wc -c < work/magisk-root/boot_a-magisk.img)" work/magisk-root/readback.img | shasum -a 256
   shasum -a 256 work/magisk-root/boot_a-magisk.img        # must match
   "$EDL" --loader="$EDL_LOADER" reset
   ```

   The locked bootloader shows a "different operating system" warning for
   about 5 s, then boots.
3. The patched image cannot decrypt the existing data. Wipe it from the
   on-screen prompt, or from stock recovery (power off, hold Vol+ and press
   Power; at "No command" hold Power and tap Vol+; choose "Wipe
   data/factory reset"). Finish setup and enable USB debugging again.
4. Install the Magisk app (`adb -s "$SERIAL" install cache/magisk/Magisk-v30.7.apk`).
   While the bootloader is locked, decline "Requires additional setup",
   Direct install and app update prompts (see
   [the next section](#magisk-app-bootloop-on-a-locked-bootloader)). Switch
   on Magisk > Superuser > Shell.
5. Set the byte and go straight to fastboot; Android may clear it again at
   the next boot. `su -c 'a; b'` runs `b` without root, so run a script
   under `su`:

   ```sh
   printf '%s\n' 'printf "\001" | dd of=/dev/block/bootdevice/by-name/frp bs=1 seek=524287 conv=notrunc' \
     > work/magisk-root/frp.sh
   adb -s "$SERIAL" push work/magisk-root/frp.sh /data/local/tmp/frp.sh
   adb -s "$SERIAL" shell su -c sh /data/local/tmp/frp.sh
   adb -s "$SERIAL" reboot bootloader
   fastboot -s "$SERIAL" flashing get_unlock_ability   # get_unlock_ability: 1
   fastboot -s "$SERIAL" flashing unlock
   ```

   Confirm Unlock on the phone. It wipes userdata and boots stock Android 10
   with an orange warning. Continue with
   [install step 3](install.md#3-choose-root-or-no-root); `scripts/flash.sh`
   replaces `boot_a`.

### Magisk app bootloop on a locked bootloader

On a locked bootloader, the Magisk app's "Requires additional setup",
Direct install or update step rewrites `boot_a` without dm-verity. After a
later power cycle the locked bootloader loops on a red "Your device is
corrupt. It can't be trusted and will not boot" screen. After the unlock,
these steps no longer break booting.

Write back your stock `boot_a` (or the host-patched image from the previous
section) and stock `misc` over EDL. Unpack and check both as
[above](#unpack-and-check-a-backup-image), enter EDL by keys from the
bootloop, then:

```sh
E w boot_a work/restore/boot_a.bin
E w misc work/restore/misc.bin
"$EDL" --loader="$EDL_LOADER" reset
```

### FRP prompt after a restore

`frp` holds Factory Reset Protection state. If it carries a state from
earlier, stock setup asks for the screen lock or Google account the phone
had then, and may not accept the old PIN. Write a zeroed `frp`; Android
formats a zeroed `frp` at the next boot. `scripts/restore-stock.sh --relock` writes a zeroed `frp`
and never the backup's. Over EDL:

```sh
head -c 524288 /dev/zero > work/restore/frp-zero.bin
E w frp work/restore/frp-zero.bin
"$EDL" --loader="$EDL_LOADER" reset
```

A zeroed `frp` also clears the OEM-unlock byte; to unlock again later, run
`scripts/unlock.sh`.

### Verity warning on stock

After a dm-verity error, the bootloader stays in dm-verity EIO mode:
`fastboot oem device-info` shows `Verity mode: false`, and Android reports
`ro.boot.veritymode=logging`. Stock Android 10 then shows a yellow or red
"can't be trusted and may not work properly" screen at each boot. Press
Power; Android boots normally.

On the GSI, the vendor init script reboots once with reason
`dm-verity enforcing`, which switches the bootloader back to enforcing
mode. On stock Android 10, `adb reboot "dm-verity enforcing"` is untested.

### WebView

After a wipe, Android can start with no WebView provider; apps that show web
pages then crash or stay blank. The vendor init script sets
`com.android.webview` at boot when the provider is unset. If the check
still fails:

```sh
scripts/verify-device.sh --fix-webview
```
