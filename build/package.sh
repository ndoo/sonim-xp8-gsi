#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Pack release assets into out/release: system.img.xz,
# xp8-gsi-components-TAG.tar.xz, the OTA payload from build/make-ota.sh when
# present, and SHA256SUMS.
# usage: build/package.sh TAG   (after build-components.sh and build-system.sh)
set -euo pipefail

TAG=${1:?usage: build/package.sh TAG}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/out
R=$OUT/release
# GNU tar for reproducible archives (gtar on macOS).
TAR=$(command -v gtar || command -v tar)
rm -rf "$R" && mkdir -p "$R"

echo "== compressing system.img with xz"
xz -T0 -6 -v -c "$OUT/system.img" > "$R/system.img.xz" &
xzpid=$!
# Without a terminal xz prints progress only on SIGUSR1; this keeps CI logs moving.
if [ ! -t 2 ]; then
    (while sleep 5 && kill -USR1 "$xzpid" 2>/dev/null; do :; done) &
fi
wait "$xzpid"
echo "== packing components"
# Scripts and vendor data come from the git checkout of TAG; the tarball holds only built parts.
"$TAR" -C "$OUT" --owner=0 --group=0 --numeric-owner --sort=name \
    --mtime="@${SOURCE_DATE_EPOCH:-1750118400}" --format=gnu -cf - components |
    xz -T0 -6 > "$R/xp8-gsi-components-$TAG.tar.xz"
ota=()
if [ -f "$OUT/ota/ota.json" ]; then
    cp "$OUT/ota/xp8-gsi-ota-$TAG.bin" "$OUT/ota/xp8-gsi-ota-$TAG.properties" "$OUT/ota/ota.json" "$R/"
    ota=("xp8-gsi-ota-$TAG.bin" "xp8-gsi-ota-$TAG.properties" ota.json)
fi
# The raw system.img line lets users check the unpacked image (sha256sum -c --ignore-missing).
(cd "$R" && sha256sum system.img.xz "xp8-gsi-components-$TAG.tar.xz" ${ota[@]+"${ota[@]}"} &&
    (cd "$OUT" && sha256sum system.img)) > "$R/SHA256SUMS"
cat "$R/SHA256SUMS"
