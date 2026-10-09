# Building from source (Route B)

Route B builds the release components and the system image in Docker instead
of downloading them. The result replaces step 4A of
[install.md](install.md#route-a-install-from-the-release); assembling and
flashing are the same as in Route A.

## Contents

- [Requirements](#requirements)
- [Inputs](#inputs)
- [Build](#build)
- [What the build scripts do](#what-the-build-scripts-do)
- [Assemble](#assemble)
- [Package a release](#package-a-release)
- [CI](#ci)

## Requirements

- Docker. The build image is `linux/amd64`
  ([build/Dockerfile](../build/Dockerfile), Debian trixie). On macOS use
  [colima](https://github.com/abiosoft/colima) or Docker Desktop; on Apple
  silicon the image runs under amd64 emulation.
- 40 GiB free for downloads, `work/` and `out/`
  (`scripts/check-space.sh build .` checks it). A build uses about 7 GB; the
  Docker image is extra. With colima, the VM's disk also grows.
- Time: the components build in seconds; the system image takes about
  6 minutes under amd64 emulation, plus the downloads.

## Inputs

Every download is pinned in [build/inputs.lock](../build/inputs.lock): the
cache path, the URL and the SHA-256. [build/fetch.sh](../build/fetch.sh)
downloads each into `cache/` and refuses a file whose hash differs. With
arguments, it fetches only the entries under those prefixes:

```sh
build/fetch.sh                      # everything
build/fetch.sh sdk/ tools/ keys/    # what build-components.sh needs
```

| Prefix | Contents |
|---|---|
| `td/` | TrebleDroid `ci-20250617` `system-td-arm64-vanilla-old.img.xz` (android-16.0.0_r1, VNDK 28/29) |
| `mtg/` | MindTheGapps 16 files at a fixed commit |
| `ims/` | phh's IMS app `ims-caf-u-resigned.apk` |
| `magisk/` | Magisk v30.7 (only for `assemble.sh --magisk`) |
| `keys/` | AOSP test keys (APKs and the tethering APEX) and `avbtool.py`, android-16.0.0_r1 |
| `sdk/` | Android SDK build-tools 36, platform 36, NDK r27d |
| `tools/` | apktool, smali/baksmali, patchelf 0.19.1 |

## Build

```sh
scripts/check-space.sh build .
docker build --platform linux/amd64 -t xp8-gsi-build build
docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -v "$PWD:/src" -w /src xp8-gsi-build \
  bash -c 'build/fetch.sh && build/build-components.sh && build/build-system.sh'
ls out/components out/system.img
```

`build-components.sh` prints the SHA-256 of each component, and
`build-system.sh` the SHA-256 of `out/system.img`. Builds are reproducible:
with the same checkout and `inputs.lock`, `out/system.img` is byte-identical
between builds. Signing uses the public AOSP test keys, file and filesystem
times come from `SOURCE_DATE_EPOCH` (default `1750118400`), and the
tethering APEX's hashtree salt is the SHA-256 of its payload.

To install a build on a phone that keeps its data (`flash.sh --only system`
or an OTA), give each build its own `XP8_RELEASE`
(`-e XP8_RELEASE=dev-2`). Android rereads changed system apps and applies
new default permissions only when `ro.build.version.incremental` changes,
and the build appends `XP8_RELEASE` to it.

To sign the tethering APEX with your own key, set `APEX_KEY` to an RSA-4096
private key in PEM format, inside the container (`-e APEX_KEY=/src/my.pem`
with the key in the checkout). The build stops if the file is not an
RSA-4096 key. The image then differs from the release in that one file, and
is otherwise signed with the same test keys.

## What the build scripts do

[build/build-components.sh](../build/build-components.sh) writes
`out/components/`:

- `libxp8shim.so` (32-bit) from [vendor/shim/](../vendor/shim/), with the
  NDK;
- `xp8-vibrator` and its VINTF manifest from
  [vendor/vibrator/](../vendor/vibrator/);
- the overlay APKs `XP8FrameworksRes.apk`, `XP8Settings.apk` and
  `XP8SystemUI.apk` from [vendor/rro/](../vendor/rro/), signed with the AOSP
  test key;
- the System update app `XP8Updater.apk` from
  [vendor/updater/](../vendor/updater/), compiled against the `UpdateEngine`
  stubs in `vendor/updater/stubs` and signed with the AOSP platform key.

[build/build-system.sh](../build/build-system.sh) writes `out/system.img`
(4 GiB raw ext4); run `build-components.sh` first. It runs these stages in
order; `build/build-system.sh STAGE...` reruns single stages on
`work/system/system.img`:

| Stage | Change |
|---|---|
| `base` | Unpacks the TrebleDroid image and grows it to 4 GiB |
| `gapps` | Adds MindTheGapps to `/system/product` and `/system/system_ext` |
| `gmsquery` | Adds a static overlay listing Play services in `config_forceQueryablePackages` ([system/rro/XP8GmsQueryable/](../system/rro/XP8GmsQueryable/)) |
| `apexfix` | Patches the tethering APEX ([system/apexfix/](../system/apexfix/)) |
| `ims` | Adds the patched IMS app as `ImsCafXp8` ([system/ims/](../system/ims/)) |
| `launcher3` | Patches Launcher3 ([system/launcher3/](../system/launcher3/)) |
| `services` | Patches `AuthService` in `services.jar` ([system/services/](../system/services/)) |
| `lmkd` | Byte-patches `/system/bin/lmkd` ([system/lmkd/](../system/lmkd/)) |
| `messaging` | Replaces the Messaging APK with one that has two more permissions ([system/messaging/](../system/messaging/)) |
| `xp8` | Removes AOSP Provision; adds the overlays, `XP8Buttons.apk`, the System update app `XP8Updater.apk` and `xp8-keys.dex` from `out/components/`, the side-key layouts, `xp8-gsi.rc`, the boot scripts and the [vendor/audio/](../vendor/audio/) diffs; sets `ro.xp8.release` from `XP8_RELEASE` (default `dev`), appends `.` and `XP8_RELEASE` to `ro.build.version.incremental`, and sets `ro.sf.lcd_density`; removes TrebleDroid's density trigger from `vndk.rc` and the `/sys` search from its `rw-system.sh`; removes the `blkio` cgroup from `cgroups.json` and `task_profiles.json`; turns on `parallel_restorecon` in `ueventd.rc` |
| `final` | Checks the filesystem, copies the SELinux policy files to `out/components/sepolicy/` and moves the image to `out/system.img` |

The reason for each change is in
[technical.md](technical.md#system-image-changes).

## Assemble

Assemble as in [install step 5A](install.md#route-a-install-from-the-release):

```sh
scripts/assemble.sh --docker "$BACKUP"
```

`assemble.sh` reads `out/components/` by default. `--docker` builds the
`xp8-gsi-build` image if it is missing and runs inside it; on Linux with the
Dockerfile's tools installed it can be left out. On macOS, `--docker` is
required. An `xp8-gsi-build` image built before `secilc` was added to the
Dockerfile gives a vendor image without a precompiled SELinux policy; remove
the image (`docker image rm xp8-gsi-build`) to rebuild it.

## Package a release

```sh
build/package.sh TAG
```

writes `out/release/`: `system.img.xz`, `xp8-gsi-components-TAG.tar.xz`
(the contents of `out/components/`) and `SHA256SUMS` over both. The
scripts and the `vendor/` data are not in the archive; they come from the
git checkout of the same tag.

## CI

- [.github/workflows/ci.yml](../.github/workflows/ci.yml), on pull requests
  and pushes to `main`: REUSE lint, `shellcheck -x -P SCRIPTDIR` over every
  script, and a components build uploaded as the `components` workflow
  artifact.
- [.github/workflows/release.yml](../.github/workflows/release.yml), on a
  pushed `a16-*` tag: fetches the inputs, builds the components and the
  system image, packages them, and publishes a GitHub release with the three
  assets. Tags are `a16-YYYYMMDD`; a later release on the same day is
  `a16-YYYYMMDD.N` (N from 1), titled "(update N)". Any other `a16-*` tag
  fails before the build. Run by hand (`workflow_dispatch`), it builds the
  same and uploads `out/release/` as a workflow artifact named
  `release-dryrun-<date>` instead of publishing.
