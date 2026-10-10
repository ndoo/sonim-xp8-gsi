#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Pack out/system.img into a signed A/B OTA payload in out/ota:
# xp8-gsi-ota-TAG.bin, xp8-gsi-ota-TAG.properties and ota.json.
# The payload is a partial update holding only system; update_engine copies
# boot and vendor from the running slot (ro.vendor.build.ab_ota_partitions).
#
# usage: OTA_KEY=key.pem build/make-ota.sh TAG [URL_BASE]
#   OTA_KEY    RSA private key (PEM) matching the OTA_CERT the image was built with
#   URL_BASE   where the release assets are served (default: the GitHub release of TAG)
# Needs: build/fetch.sh ota/
set -euo pipefail

TAG=${1:?usage: OTA_KEY=key.pem build/make-ota.sh TAG [URL_BASE]}
URL_BASE=${2:-https://github.com/ndoo/sonim-xp8-gsi/releases/download/$TAG}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE=${CACHE:-$ROOT/cache}
OUT=$ROOT/out
O=$OUT/ota
IMG=$OUT/system.img
AVB_ZIP=$CACHE/ota/avbroot-3.34.1-x86_64-unknown-linux-gnu.zip
AVBROOT=$CACHE/unpacked/avbroot/avbroot

if [ -z "${OTA_KEY:-}" ] || [ ! -f "$OTA_KEY" ]; then echo "set OTA_KEY to the payload signing key" >&2; exit 1; fi
[ -f "$IMG" ] || { echo "no $IMG: run build/build-system.sh first" >&2; exit 1; }
[ -f "$AVB_ZIP" ] || { echo "missing $AVB_ZIP: run build/fetch.sh ota/" >&2; exit 1; }
if [ ! -x "$AVBROOT" ]; then
    rm -rf "$(dirname "$AVBROOT")"
    unzip -q -d "$(dirname "$AVBROOT")" "$AVB_ZIP" avbroot
fi

prop() { debugfs -R "cat /system/build.prop" "$IMG" 2>/dev/null | sed -n "s/^$1=//p"; }
SYS_TS=$(prop ro.system.build.date.utc)
[ -n "$SYS_TS" ] || { echo "no ro.system.build.date.utc in $IMG" >&2; exit 1; }

rm -rf "$O" && mkdir -p "$O/images"
ln -s "$IMG" "$O/images/system.img"
# minor_version 7 + partial_update: update_engine adds SOURCE_COPY for the other A/B partitions.
cat > "$O/payload.toml" <<EOF
version = 2

[manifest]
block_size = 4096
minor_version = 7
partial_update = true

[[manifest.partitions]]
partition_name = "system"
version = "$SYS_TS"
EOF

BIN=xp8-gsi-ota-$TAG.bin
PROPS=xp8-gsi-ota-$TAG.properties
echo "== packing system.img into $BIN (xz)"
"$AVBROOT" payload pack -q --input-info "$O/payload.toml" --input-images "$O/images" \
    -k "$OTA_KEY" -o "$O/$BIN" -O "$O/$PROPS" &
pid=$!
# avbroot prints nothing while it compresses in memory, then writes the file.
start=$SECONDS
while kill -0 "$pid" 2>/dev/null; do
    sleep 5
    if [ -s "$O/$BIN" ]; then echo "   writing: $(stat -c %s "$O/$BIN") bytes"
    else echo "   compressing: $((SECONDS - start)) s"; fi
done
wait "$pid"
rm -rf "$O/images" "$O/payload.toml"

size=$(stat -c %s "$O/$BIN")
sum=$(sha256sum "$O/$BIN" | cut -d' ' -f1)
syssum=$(sha256sum "$IMG" | cut -d' ' -f1)
python3 - "$O/ota.json" "$TAG" "$URL_BASE/$BIN" "$size" "$sum" "$syssum" "$O/$PROPS" <<'PY'
import json, sys
out, tag, url, size, sha, syssha, props = sys.argv[1:]
headers = open(props).read().strip()
# min_vendor_layout: ro.vendor.xp8.layout the installed vendor.img must report;
# 3 means flash.sh prepared slot b, which the payload is written to.
json.dump({"tag": tag, "system_sha256": syssha, "min_vendor_layout": 3,
           "payload": {"url": url, "size": int(size), "sha256": sha, "headers": headers}},
          open(out, "w"), indent=2)
PY
cat "$O/ota.json"
