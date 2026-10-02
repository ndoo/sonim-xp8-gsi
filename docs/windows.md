# Windows host

Route A (install from the release) runs on Windows 10 and 11. Follow
[install.md](install.md) with the tools, edl setup and EDL driver below. Route B
(build from source) is not supported on Windows: build on Linux.

Windows support starts with release `a16-20261002.1`: check out that tag or a
later one.

## Tools

Run every command and script in Git Bash, not in PowerShell or `cmd`.

| Need | Install | Note |
|---|---|---|
| Git Bash | `winget install Git.Git` | Includes `xz`, `tar`, `unzip`, `curl` and `sha256sum` |
| `adb`, `fastboot`, `zstd`, Python | `winget install Google.PlatformTools Meta.Zstandard Python.Python.3.13` | |
| Docker, for step 5 (`assemble.sh --docker`) | `wsl --install --no-distribution`, then `winget install Docker.DockerDesktop` | Needs administrator approval and a restart |
| Zadig, for the EDL driver | `winget install akeo.ie.Zadig` | Needed once |

Where [install.md](install.md) uses `shasum -a 256`, use `sha256sum`.

## edl

Follow [Set up the working directory](install.md#set-up-the-working-directory),
but replace its edl lines (from `git clone https://github.com/bkerler/edl.git`
to `install-linux-edl-drivers.sh`) with these. Keep the other steps: the clone
and tag checkout, the firehose loader and the userdebug ABL.

```sh
git clone https://github.com/bkerler/edl.git work/edl
git -C work/edl submodule update --init --recursive
python -m venv work/.venv
work/.venv/Scripts/python -m pip install -r work/edl/requirements.txt
export EDL="$PWD/work/.venv/Scripts/python.exe $PWD/work/edl/edl.py"
```

- **No package install:** `pip install -e work/edl` fails. Its package
  metadata asks for `pylzma`, which has no Windows wheel, and edl does not
  import it. `requirements.txt` leaves it out.
- **Run `edl.py` from the clone's top level:** `edlclient/edl.py` is a git
  symlink, and Git for Windows checks it out as a text file.
- **No spaces in the path:** the scripts split `EDL` on spaces, so the
  repository path must have none.

The other [variables](install.md#variables) are the same as on macOS and Linux.

## EDL driver

edl needs the WinUSB driver on the Qualcomm 9008 device. Install it once:

1. Put the phone in EDL: `adb reboot edl`.
2. Open Zadig and choose Options > List All Devices.
3. Pick `QUSB__BULK` (USB ID `05C6 9008`), choose WinUSB and press
   Replace Driver or Install Driver.

Qualcomm's `qcusbser` driver (a "QDLoader 9008" COM port) does not work with
edl. The scripts stop and name the driver when it is not WinUSB, libusbK or
libusb0. adb and fastboot (`18D1:D00D`) need no driver: Windows installs
WinUSB for them.

## Differences from macOS and Linux

- `scripts/assemble.sh --docker` keeps its scratch space in the Docker volume
  `xp8-gsi-work`, not in `work/assemble`. A Windows folder cannot hold the
  vendor tree's symlinks and Unix modes. Remove the volume when done:
  `docker volume rm xp8-gsi-work`.
- The phone may not appear in `fastboot devices` after
  `adb reboot bootloader`. The screen then shows the Sonim logo and
  "Press any key to shutdown". Try another USB cable and port. In one test, a
  USB-A to USB-C cable on a rear USB 2.0 port worked where the first cable did
  not.
