# Update installed to an unprepared slot b

System update installs a release into slot b, the slot the phone is not
running. Slot b needs the bootloader and firmware that `scripts/enable-ab.sh`
writes. Up to `a16-20261010`, the install and the update from a computer did
not write them, and System update did not check for them. In later
releases `scripts/flash.sh` prepares slot b every time it installs or
updates the GSI, and System update installs only on a phone whose vendor
image `flash.sh` wrote that way (`ro.vendor.xp8.layout` 3). Variables
(`SERIAL`, `BACKUP`, `EDL`, `EDL_LOADER`) are those of
[install.md](../install.md#variables).

- [Affected phones](#affected-phones)
- [Symptom](#symptom)
- [Cause](#cause)
- [Prevent it](#prevent-it)
- [Recover](#recover)

## Affected phones

A phone running `a16-20261006`, `a16-20261006.1`, `a16-20261007` or
`a16-20261010`, installed or updated from a computer, on which
`scripts/enable-ab.sh` never ran. On `a16-20261010` System update also
downloads and installs updates on its own on Wi-Fi; on the earlier releases
the update starts when you tap **Download & install** (or run
`scripts/ota-update.sh`).

## Symptom

After a system update and the restart:

- the lock screen rejects your correct PIN, so you cannot unlock the phone
  or turn on USB debugging;
- in fastboot (power off, hold Vol-, press Power), `fastboot set_active a`
  and `fastboot flash` fail with `unknown command`.

Your data is still on the phone, and slot a still holds the release you had
before the update.

## Cause

The update wrote the new release to slot b and made slot b active. Slot b
still had the stock bootloader (`abl_b`) and the stock `mdtpsecapp_b` and
`modem_b`, so the phone started the new release with stock firmware. The
rejected PIN most likely comes from that mismatch; this is not confirmed.
The stock bootloader has no `set_active` or `flash` command, so fastboot
cannot switch back.

## Prevent it

If your phone still starts normally and you never ran `enable-ab.sh`:

1. Turn off automatic updates. On `a16-20261010`: Settings → System →
   System update, turn off **Download updates automatically on Wi-Fi**.
   This is the same setting as Developer options → **Automatic system
   updates**. Do not tap **Download & install**.
2. Prepare slot b from a computer. Put the phone in fastboot (power off,
   hold Vol-, press Power), then:

   ```sh
   scripts/enable-ab.sh --abl work/userdebug/abl.elf "$BACKUP"
   ```

   It writes only `abl_b`, `mdtpsecapp_b` and `modem_b`; slot a and your
   data are not touched.

After that, System update can install releases again, and you can turn
automatic updates back on. Releases after `a16-20261010` ask for one
update from a computer
([Update to a newer release](../install.md#update-to-a-newer-release))
before they install over the air; that update also prepares slot b, so
step 2 is then not needed.

## Recover

You need the computer set up as in [install.md](../install.md#prerequisites)
(edl, the firehose loader, the userdebug `abl.elf`) and your phone's own
backup. `scripts/edl-slot-a.sh` is newer than `a16-20261010`; run it from
the main branch:

```sh
git fetch origin
git checkout origin/main
```

1. Switch the phone back to slot a over EDL:

   ```sh
   scripts/edl-slot-a.sh "$BACKUP"
   ```

   When it asks, enter EDL: power off (hold Power about 10 s), hold Vol+
   and Vol-, and press Power. The script checks the loader's SHA-256,
   identifies the phone against your backup, checks that slot b is active,
   saves the current partition table in `$BACKUP/slot-a-<date>/`, and asks
   you to type `write`. It then makes slot a active, checks the result and
   restarts the phone.
2. The phone starts the release you had before the update. Unlock it with
   your PIN; your data is kept.
3. Prepare slot b as in [Prevent it](#prevent-it), step 2.
4. Install the update again from Settings → System → System update.

If the script stops with `do not reset the phone`, leave the phone in EDL
and run the script again. If it says that slot a is already active, the
phone has a different problem; see
[troubleshooting.md](../troubleshooting.md).
