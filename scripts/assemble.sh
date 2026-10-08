#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Build boot.img and vendor.img from your own stock backup and the release components.
# Compiles only the SELinux policy. The results contain your phone's proprietary files: do not share them.
#
# usage: scripts/assemble.sh [options] STOCK_DIR
#   STOCK_DIR          your EDL backup: boot_a.bin[.zst] and system_a.bin[.zst] (read only)
#   --components DIR   release components (default: out/components)
#   --out DIR          where boot.img and vendor.img go (default: out)
#   --work DIR         scratch space, about 6 GiB (default: work/assemble)
#   --magisk           root: patch boot.img with Magisk. Off unless you ask for it.
#   --docker           run inside the build/Dockerfile image (needed on macOS and
#                      Windows; on Windows --work is the Docker volume xp8-gsi-work)
#   --keep-work        keep the unpacked system_a and vendor tree
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
usage() { sed -n '8,17s/^# \{0,1\}//p' "$0" >&2; exit 2; }
die() { echo "assemble: $*" >&2; exit 1; }
abs() { mkdir -p "$1" && (cd "$1" && pwd); }

COMPONENTS=$ROOT/out/components OUTDIR=$ROOT/out WORKDIR=$ROOT/work/assemble
MAGISK=0 DOCKER=0 KEEP=0 STOCK=
while [ $# -gt 0 ]; do
    case $1 in
        --components) COMPONENTS=$2; shift 2 ;;
        --out) OUTDIR=$2; shift 2 ;;
        --work) WORKDIR=$2; shift 2 ;;
        --magisk) MAGISK=1; shift ;;
        --docker) DOCKER=1; shift ;;
        --keep-work) KEEP=1; shift ;;
        -h|--help) usage ;;
        -*) die "unknown option $1" ;;
        *) [ -z "$STOCK" ] || usage; STOCK=$1; shift ;;
    esac
done
[ -n "$STOCK" ] || usage
[ -d "$STOCK" ] || die "no such directory: $STOCK"
[ -d "$COMPONENTS" ] || die "no components directory at $COMPONENTS (unpack the release components, or run build/build-components.sh)"
STOCK=$(cd "$STOCK" && pwd)
COMPONENTS=$(cd "$COMPONENTS" && pwd)
OUTDIR=$(abs "$OUTDIR")
WORKDIR=$(abs "$WORKDIR")

if [ $DOCKER = 1 ]; then
    img=xp8-gsi-build
    docker image inspect "$img" >/dev/null 2>&1 ||
        docker build --platform linux/amd64 -t "$img" "$ROOT/build"
    args=(--components /components --out /out --work /work)
    [ $MAGISK = 1 ] && args+=(--magisk)
    [ $KEEP = 1 ] && args+=(--keep-work)
    case $(uname -s) in
        MINGW*|MSYS*|CYGWIN*)
            # A Windows folder cannot hold the vendor tree's symlinks and modes.
            exec env MSYS_NO_PATHCONV=1 docker run --rm --platform linux/amd64 -e HOME=/tmp \
                -v "$(cygpath -w "$ROOT"):/src" -v "$(cygpath -w "$STOCK"):/stock:ro" \
                -v "$(cygpath -w "$COMPONENTS"):/components:ro" -v "$(cygpath -w "$OUTDIR"):/out" \
                -v xp8-gsi-work:/work -w /src \
                "$img" scripts/assemble.sh "${args[@]}" /stock ;;
    esac
    exec docker run --rm --platform linux/amd64 -u "$(id -u):$(id -g)" -e HOME=/tmp \
        -v "$ROOT:/src" -v "$STOCK:/stock:ro" -v "$COMPONENTS:/components:ro" \
        -v "$OUTDIR:/out" -v "$WORKDIR:/work" -w /src \
        "$img" scripts/assemble.sh "${args[@]}" /stock
fi

[ "$(uname -s)" = Linux ] || die "run natively on Linux, or add --docker"
for t in python3 debugfs mke2fs tune2fs e2fsck zstd zip unzip patch fdtget fdtput java curl; do
    command -v "$t" >/dev/null || die "missing tool: $t (see build/Dockerfile)"
done

"$ROOT/scripts/check-space.sh" assemble "$WORKDIR" "$OUTDIR"

fetch=(sdk/build-tools sdk/platform keys/platform tools/patchelf)
[ $MAGISK = 1 ] && fetch+=(magisk/)
"$ROOT/build/fetch.sh" "${fetch[@]}"
export WORK=$WORKDIR OUT=$OUTDIR
# shellcheck source=../build/lib/tools.sh
. "$ROOT/build/lib/tools.sh"
mkdir -p "$UNP"
unpack_sdk

pe_tgz=$CACHE/tools/patchelf-0.19.1-$(uname -m).tar.gz
if [ -n "${PATCHELF:-}" ]; then
    :
elif [ -f "$pe_tgz" ]; then
    PATCHELF=$UNP/patchelf-0.19.1/bin/patchelf
    [ -x "$PATCHELF" ] || { mkdir -p "$UNP/patchelf-0.19.1" && tar -xzf "$pe_tgz" -C "$UNP/patchelf-0.19.1"; }
else
    PATCHELF=$(command -v patchelf) || die "missing tool: patchelf"
    echo "assemble: warning: using $($PATCHELF --version); libgui_vendor.so bytes differ from the reference build (0.19.1)" >&2
fi

stock_img() { # name: sets IMG to the raw image, unpacking a .zst copy into WORKDIR
    if [ -f "$STOCK/$1.bin" ]; then
        IMG=$STOCK/$1.bin
    elif [ -f "$STOCK/$1.bin.zst" ]; then
        IMG=$WORKDIR/$1.bin
        zstd -q -d -f "$STOCK/$1.bin.zst" -o "$IMG"
    else
        die "$STOCK has no $1.bin or $1.bin.zst"
    fi
}

echo "assemble: boot"
stock_img boot_a
boot=$IMG
if [ $MAGISK = 1 ]; then
    apks=("$CACHE"/magisk/Magisk-v*.apk)
    apk=${apks[-1]}
    [ -f "$apk" ] || die "no Magisk APK in cache/magisk (build/fetch.sh magisk/)"
    echo "assemble: patching boot with $(basename "$apk")"
    bash "$ROOT/build/lib/magisk-patch.sh" "$apk" "$boot" "$WORKDIR/boot-magisk.img" "$WORKDIR/magisk"
    boot=$WORKDIR/boot-magisk.img
fi
python3 "$ROOT/build/lib/repack.py" "$boot" "$OUTDIR/boot.img" --cmdline-add androidboot.selinux=permissive

echo "assemble: vendor"
stock_img system_a
system=$IMG
python3 "$ROOT/build/lib/mkvendor.py" --system "$system" --components "$COMPONENTS" \
    --repo "$ROOT" --work "$WORKDIR/vendor" --out "$OUTDIR/vendor.img" --keys "$KEYS" \
    --apksigner "$(printf '%q ' "${APKSIGNER[@]}")" --zipalign "$ZIPALIGN" --patchelf "$PATCHELF"

if [ $KEEP = 0 ]; then
    rm -rf "$WORKDIR/vendor" "$WORKDIR/magisk" "$WORKDIR/boot-magisk.img"
    rm -f "$WORKDIR/boot_a.bin" "$WORKDIR/system_a.bin"
fi
(cd "$OUTDIR" && sha256sum boot.img vendor.img | tee assemble.sha256)
if [ $MAGISK = 1 ]; then
    echo "assemble: boot.img includes Magisk (root)"
else
    echo "assemble: boot.img has no root (add --magisk for Magisk)"
fi
