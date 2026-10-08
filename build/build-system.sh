#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Build out/system.img: TrebleDroid vanilla-old + MindTheGapps (replacing AOSP
# QuickSearchBox) + Play services force-queryable overlay + tethering apex fix +
# ImsCafXp8 + Launcher3 (Taskbar, no fixed first-screen search bar) and
# AuthService patches + lmkd zoneinfo patch + patched Messaging, without AOSP
# Provision + the XP8 overlays, side keys, System update app and boot scripts
# from out/components;
# XP8_RELEASE (the release tag, default dev) becomes ro.xp8.release.
#
# usage: build/build-system.sh [STAGE...]
#   stages: base gapps gmsquery apexfix ims launcher3 services lmkd messaging xp8 otacerts final
#   (default: all, in that order; a partial run works on work/system/system.img)
# Needs: build/fetch.sh td/ mtg/ ims/ sdk/ tools/ keys/, then build/build-components.sh
set -euo pipefail

# shellcheck source-path=SCRIPTDIR source=lib/tools.sh
. "$(dirname "$0")/lib/tools.sh"
unpack_sdk
tool_jars

PATCHES=$ROOT/system
W=$WORK/system
IMG=$W/system.img
SIZE=4G
SYS=u:object_r:system_file:s0
LIB=u:object_r:system_lib_file:s0
mkdir -p "$W" "$OUT"
# Fixed e2fsprogs clock (inode and superblock times) for a reproducible image.
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1750118400}
export E2FSPROGS_FAKE_TIME=$SOURCE_DATE_EPOCH E2FSCK_TIME=$SOURCE_DATE_EPOCH

# APEX_KEY: optional RSA-4096 PEM to sign the tethering APEX payload with.
if [ -n "${APEX_KEY:-}" ]; then
    openssl rsa -in "$APEX_KEY" -noout -text 2>/dev/null | grep -q '4096 bit' \
        || { echo "APEX_KEY=$APEX_KEY: not a readable RSA-4096 private key" >&2; exit 1; }
else
    APEX_KEY=$KEYS/com.android.tethering.pem
fi
# OTA_CERT: x509 PEM that A/B OTA payloads are signed for (otacerts stage);
# defaults to the project's certificate once one is committed.
[ -n "${OTA_CERT:-}" ] || [ ! -f "$ROOT/build/ota/xp8-ota.x509.pem" ] || OTA_CERT=$ROOT/build/ota/xp8-ota.x509.pem
if [ -n "${OTA_CERT:-}" ]; then
    openssl x509 -in "$OTA_CERT" -noout 2>/dev/null \
        || { echo "OTA_CERT=$OTA_CERT: not a readable x509 PEM certificate" >&2; exit 1; }
fi

log() { echo "== $*"; }

# --- debugfs helpers: queue commands, then apply them in one debugfs -w run ---

CMDS=$W/debugfs.cmds
CTX=$W/ctx
declare -A MADE=()

q_begin() { : > "$CMDS"; MADE=(); mkdir -p "$CTX"; }
q() { printf '%s\n' "$*" >> "$CMDS"; }

# meta PATH MODE LABEL [nonul]: owner root:root; label stored NUL-terminated
# unless "nonul" (the reference image wrote services.jar/Launcher3 that way).
q_meta() {
    local f=$CTX/${3//[^a-z0-9_]/_}${4:+.$4}
    if [ ! -f "$f" ]; then
        if [ -n "${4:-}" ]; then printf '%s' "$3" > "$f"; else printf '%s\0' "$3" > "$f"; fi
    fi
    q "sif $1 mode $2"
    q "sif $1 uid 0"
    q "sif $1 gid 0"
    q "ea_set -f $f $1 security.selinux"
}

exists() { ! debugfs -R "stat $1" "$IMG" 2>&1 | grep -q 'File not found'; }

# Create missing parent directories of PATH (0755 root:root system_file).
q_parents() {
    local d=${1%/*} chain=()
    while [ -n "$d" ] && [ -z "${MADE[$d]:-}" ] && ! exists "$d"; do
        chain=("$d" "${chain[@]}")
        d=${d%/*}
    done
    for d in "${chain[@]}"; do
        q "mkdir $d"
        q_meta "$d" 040755 "$SYS"
        MADE[$d]=1
    done
}

q_put() { # SRC DST LABEL [nonul]
    q_parents "$2"
    q "write $1 $2"
    q_meta "$2" 0100644 "$3" "${4:-}"
}

q_commit() {
    local err
    err=$(debugfs -w -f "$CMDS" "$IMG" 2>&1 >/dev/null | grep -v '^debugfs [0-9]' || true)
    if [ -n "$err" ]; then echo "$err" >&2; exit 1; fi
}

dump() { debugfs -R "dump $1 $2" "$IMG" 2>/dev/null; [ -s "$2" ]; }

ls_entries() { # "mode name" per entry of an image directory, excluding . and ..
    debugfs -R "ls -p $1" "$IMG" 2>/dev/null | awk -F/ 'NF>=7 && $6!="." && $6!=".." {print $3, $6}'
}

ls_names() { ls_entries "$1" | cut -d' ' -f2; }

q_rm_tree() {
    local mode n
    while read -r mode n; do
        if [[ $mode =~ ^0?40 ]]; then q_rm_tree "$1/$n"; else q "rm $1/$n"; fi
    done < <(ls_entries "$1")
    q "rmdir $1"
}

repack_dex() { # ZIP DEX OUT [--drop-v1-sig]: replace classes.dex, zipalign
    python3 "$ROOT/build/lib/zipreplace.py" "$1" "$3.tmp" "${@:4}" classes.dex="$2"
    "$ZIPALIGN" -f -p 4 "$3.tmp" "$3"
    rm -f "$3.tmp"
}

zipnorm() { # IN OUT: sorted entries, fixed dates, methods kept
    python3 - "$1" "$2" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as i, zipfile.ZipFile(sys.argv[2], "w") as o:
    for e in sorted(i.infolist(), key=lambda e: e.filename):
        zi = zipfile.ZipInfo(e.filename, (2008, 1, 1, 0, 0, 0))
        zi.compress_type, zi.external_attr = e.compress_type, e.external_attr
        o.writestr(zi, i.read(e))
PY
}

# --- stages ---

stage_base() {
    log "base: TrebleDroid image grown to $SIZE"
    xz -dc "$CACHE/td/system-td-arm64-vanilla-old.img.xz" > "$IMG"
    truncate -s "$SIZE" "$IMG"
    # The TD image has shared_blocks (read-only dedup); unshare before writing.
    resize2fs "$IMG" >/dev/null 2>&1
    e2fsck -fy -E unshare_blocks "$IMG" >/dev/null 2>&1 || [ $? -le 1 ]
    e2fsck -fn "$IMG" >/dev/null 2>&1
}

stage_gapps() {
    log "gapps: MindTheGapps into /system/{product,system_ext}; AOSP QuickSearchBox removed"
    local f rel
    q_begin
    while IFS= read -r f; do
        rel=${f#"$CACHE"/mtg/}
        case $rel in
            *.so) q_put "$f" "/system/$rel" "$LIB" ;;
            *) q_put "$f" "/system/$rel" "$SYS" ;;
        esac
    done < <(find "$CACHE/mtg" -type f | LC_ALL=C sort)
    q_rm_tree /system/product/app/QuickSearchBox
    q_commit
}

stage_gmsquery() {
    log "gmsquery: static RRO adding Play services to config_forceQueryablePackages"
    # Play services runs in its own uid (sharedUserMaxSdkVersion), and the framework reads
    # forceQueryable from <application> only, where GmsCore does not set it.
    local r=$W/gmsquery d=$PATCHES/rro/XP8GmsQueryable
    rm -rf "$r" && mkdir -p "$r"
    "$AAPT2" compile --dir "$d/res" -o "$r/res.zip"
    "$AAPT2" link -o "$r/u.apk" --manifest "$d/AndroidManifest.xml" -I "$ANDROID_JAR" "$r/res.zip"
    "$ZIPALIGN" -f -p 4 "$r/u.apk" "$r/a.apk"
    sign_apk testkey "$r/a.apk" "$r/XP8GmsQueryable.apk"
    q_begin
    q_put "$r/XP8GmsQueryable.apk" /system/product/overlay/XP8GmsQueryable.apk "$SYS"
    q_commit
    rm -rf "$r"
}

stage_apexfix() {
    log "apexfix: null-check sLocalNetBlockedUidMap and mCookieTagMap in the tethering apex"
    local a=$W/apex t payload bc salt
    rm -rf "$a" && mkdir -p "$a"
    dump /system/apex/com.android.tethering.capex "$a/teth.capex"
    unzip -q -p "$a/teth.capex" original_apex > "$a/orig.apex"
    unzip -q -d "$a/c" "$a/orig.apex"
    payload=$a/c/apex_payload.img

    debugfs -R "dump /javalib/service-connectivity.jar $a/sc.jar" "$payload" 2>/dev/null
    unzip -q -d "$a/dex" "$a/sc.jar" 'classes*.dex'
    [ "$(find "$a/dex" -name 'classes*.dex' | wc -l)" -eq 1 ]
    "${BAKSMALI[@]}" d "$a/dex/classes.dex" -o "$a/smali"
    patch -s -p1 -d "$a" < "$PATCHES/apexfix/bpfnetmaps.smali.diff"
    patch -s -p1 -d "$a" < "$PATCHES/apexfix/networkstats.smali.diff"
    "${SMALI[@]}" a "$a/smali" -o "$a/classes.dex" --api 36 -j 1
    repack_dex "$a/sc.jar" "$a/classes.dex" "$a/sc.new.jar"

    # Rebuild the payload with the original modes, owners and labels.
    python3 "$ROOT/build/lib/e2meta.py" "$payload" / > "$a/meta.tsv"
    t=$(mktemp -d)
    debugfs -R "rdump / $t" "$payload" >/dev/null 2>&1
    rm -rf "$t/lost+found"
    cp "$a/sc.new.jar" "$t/javalib/service-connectivity.jar"
    bc=$(( $(du -sk --apparent-size "$t" | cut -f1) / 4 + 400 ))
    mke2fs -q -t ext4 -b 4096 -I 256 -N 96 \
        -O ^has_journal,^resize_inode,^metadata_csum,^64bit,^orphan_file,^metadata_csum_seed,uninit_bg \
        -m 0 -U 7d1522e1-9dfa-5edb-a43e-98e3a4d20250 -E hash_seed=7d1522e1-9dfa-5edb-a43e-98e3a4d20250 \
        -d "$t" "$a/payload.img" "$bc"
    rm -rf "$t"
    mkdir -p "$CTX"
    # lost+found is recreated by mke2fs and left unlabelled, as in the reference image.
    awk -F'\t' -v d="$CTX" '$1 != "lost+found" {
        p = ($1 == ".") ? "/" : "/" $1
        f = d "/" $5; gsub(/[^a-zA-Z0-9_\/.-]/, "_", f)
        printf "%s\t%s\n", $5, f > (d "/apexctx.list")
        print "sif " p " mode 0" $2; print "sif " p " uid " $3; print "sif " p " gid " $4
        print "ea_set -f " f " " p " security.selinux"
        print "sif " p " atime 0"; print "sif " p " mtime 0"; print "sif " p " ctime 0"; print "sif " p " crtime 0"
    }' "$a/meta.tsv" > "$a/fix.cmds"
    while IFS=$'\t' read -r label f; do printf '%s\0' "$label" > "$f"; done < <(sort -u "$CTX/apexctx.list")
    debugfs -w -f "$a/fix.cmds" "$a/payload.img" >/dev/null 2>"$a/fix.err" || true
    if grep -v '^debugfs [0-9]' "$a/fix.err"; then exit 1; fi
    e2fsck -fy "$a/payload.img" >/dev/null 2>&1 || true
    resize2fs -M "$a/payload.img" >/dev/null 2>&1
    e2fsck -fn "$a/payload.img" >/dev/null 2>&1
    bc=$(dumpe2fs -h "$a/payload.img" 2>/dev/null | awk '/^Block count/{print $3}')
    truncate -s $((bc * 4096)) "$a/payload.img"

    # avbtool picks a random salt unless given one.
    salt=$(sha256sum "$a/payload.img" | cut -d' ' -f1)
    "${AVBTOOL[@]}" add_hashtree_footer --image "$a/payload.img" --partition_size $((bc * 4096 + 286720)) \
        --do_not_generate_fec --algorithm SHA256_RSA4096 --key "$APEX_KEY" --salt "$salt" \
        --hash_algorithm sha256 --prop apex.key:com.android.tethering
    "${AVBTOOL[@]}" extract_public_key --key "$APEX_KEY" --output "$a/apex_pubkey"

    python3 - "$a" <<'PY'
import sys, zipfile
a = sys.argv[1]
c = a + "/c/"
order = [("apex_payload.img", a + "/payload.img", 0), ("assets/NOTICE.html.gz", c + "assets/NOTICE.html.gz", 0),
         ("resources.arsc", c + "resources.arsc", 0), ("AndroidManifest.xml", c + "AndroidManifest.xml", 8),
         ("apex_build_info.pb", c + "apex_build_info.pb", 8), ("apex_manifest.pb", c + "apex_manifest.pb", 8),
         ("apex_pubkey", a + "/apex_pubkey", 8)]
with zipfile.ZipFile(a + "/u.apex", "w") as z:
    for name, src, method in order:
        zi = zipfile.ZipInfo(name, (2009, 1, 1, 0, 0, 0))
        zi.compress_type = method
        z.writestr(zi, open(src, "rb").read())
PY
    "$ZIPALIGN" -f -p 4 "$a/u.apex" "$a/a.apex"
    sign_apk testkey "$a/a.apex" "$a/com.android.tethering.apex"

    q_begin
    q "rm /system/apex/com.android.tethering.capex"
    q_put "$a/com.android.tethering.apex" /system/apex/com.android.tethering.apex "$SYS"
    q_commit
    rm -rf "$a"
}

stage_ims() {
    log "ims: ImsCafXp8 priv-app (IImsRadioIndication@1.0 shift for the Sonim HAL)"
    local i=$W/ims d=/system/system_ext/priv-app/ImsCafXp8 f
    rm -rf "$i" && mkdir -p "$i"
    "${APKTOOL_IMS[@]}" d -r -f -o "$i/dec" "$CACHE/ims/ims-caf-u-resigned.apk" >/dev/null
    patch -s -p1 -d "$i/dec" < "$PATCHES/ims/ims-caf-u-xp8.smali.patch"
    "${APKTOOL_IMS[@]}" b -o "$i/b.apk" "$i/dec" >/dev/null
    zipnorm "$i/b.apk" "$i/u.apk"
    "$ZIPALIGN" -f -p 4 "$i/u.apk" "$i/a.apk"
    sign_apk platform "$i/a.apk" "$i/ImsCafXp8.apk"
    unzip -q -j -d "$i/lib" "$CACHE/ims/ims-caf-u-resigned.apk" 'lib/arm64-v8a/*.so'

    q_begin
    q_put "$i/ImsCafXp8.apk" "$d/ImsCafXp8.apk" "$SYS"
    for f in "$i"/lib/*.so; do
        q_put "$f" "$d/lib/arm64/${f##*/}" "$SYS"
    done
    q_commit
    rm -rf "$i"
}

stage_launcher3() {
    log "launcher3: no Taskbar without a navigation bar, no fixed search bar on the first screen"
    local l=$W/launcher3 d=/system/system_ext/priv-app/Launcher3QuickStep
    rm -rf "$l" && mkdir -p "$l"
    dump "$d/Launcher3QuickStep.apk" "$l/orig.apk"
    unzip -q -d "$l/dex" "$l/orig.apk" 'classes*.dex'
    [ "$(find "$l/dex" -name 'classes*.dex' | wc -l)" -eq 1 ]
    "${BAKSMALI[@]}" d "$l/dex/classes.dex" -o "$l/smali"
    python3 - "$l/smali/com/android/launcher3/taskbar/TaskbarManager.smali" "$PATCHES/launcher3/istb.smali" <<'PY'
import re, sys
f, new = sys.argv[1], open(sys.argv[2]).read()
new = "\n".join(x for x in new.splitlines() if not x.startswith("#")).strip()
s = open(f).read()
s2, n = re.subn(r'\.method private isTaskbarEnabled\(Lcom/android/launcher3/DeviceProfile;\)Z.*?\.end method',
                lambda m: new, s, count=1, flags=re.S)
assert n == 1, n
open(f, "w").write(s2)
PY
    # R8 folded QSB_ON_FIRST_SCREEN=true into every SHOULD_SHOW_FIRST_PAGE_WIDGET read; true there acts as QSB off.
    python3 - "$l/smali/com/android/launcher3/Utilities.smali" <<'PY'
import re, sys
f = sys.argv[1]
s = open(f).read()
s2, n = re.subn(r'(\n    )(sput-boolean v0, Lcom/android/launcher3/Utilities;->SHOULD_SHOW_FIRST_PAGE_WIDGET:Z\n'
                r'(?:\s*\.line \d+\n)?\s*invoke-static \{\}, Landroid/app/ActivityManager;->isRunningInTestHarness\(\)Z\s*move-result v0\n)',
                r'\1const/4 v0, 0x1\1\2', s)
assert n == 1, n
open(f, "w").write(s2)
PY
    # Also report a found screen as new: the workspace strips an empty first screen that the model still offers.
    python3 - "$l/smali/com/android/launcher3/model/WorkspaceItemSpaceFinder.smali" <<'PY'
import re, sys
f = sys.argv[1]
s = open(f).read()
add = """if-eqz v2, :xp8_new
    invoke-virtual {p2, v6}, Lcom/android/launcher3/util/IntArray;->contains(I)Z
    move-result v2
    if-nez v2, \\1
    invoke-virtual {p2, v6}, Lcom/android/launcher3/util/IntArray;->add(I)V
    goto \\1
    :xp8_new
"""
s2, n = re.subn(r'if-nez v2, (:cond_\w+)\n(?=(?:\s*\.line \d+)?\s*iget-object v2, p0, '
                r'Lcom/android/launcher3/model/WorkspaceItemSpaceFinder;->mModel:)', add, s)
assert n == 1, n
open(f, "w").write(s2)
PY
    "${SMALI[@]}" a "$l/smali" -o "$l/classes.dex" --api 36 -j 1
    repack_dex "$l/orig.apk" "$l/classes.dex" "$l/a.apk" --drop-v1-sig
    sign_apk testkey "$l/a.apk" "$l/Launcher3QuickStep.apk"

    q_begin
    exists "$d/oat" && q_rm_tree "$d/oat"
    q "rm $d/Launcher3QuickStep.apk"
    q_put "$l/Launcher3QuickStep.apk" "$d/Launcher3QuickStep.apk" "$SYS" nonul
    q_commit
    rm -rf "$l"
}

stage_services() {
    log "services: AuthService keeps HIDL fingerprint configs when AIDL instances exist"
    local s=$W/services o=/system/framework/oat/arm64 n
    rm -rf "$s" && mkdir -p "$s"
    dump /system/framework/services.jar "$s/services.jar"
    unzip -q -d "$s/dex" "$s/services.jar" classes.dex
    "${BAKSMALI[@]}" d "$s/dex/classes.dex" -o "$s/svd"
    patch -s -p1 -d "$s/svd" < "$PATCHES/services/authservice-fp.smali.diff"
    "${SMALI[@]}" a "$s/svd" -o "$s/classes.dex" --api 36 -j 1
    repack_dex "$s/services.jar" "$s/classes.dex" "$s/services-a.jar"

    q_begin
    for n in $(ls_names "$o" | grep -E '^services\.(odex|vdex|art)(\.fsv_meta)?$'); do
        q "rm $o/$n"
    done
    q "rm /system/framework/services.jar"
    q_put "$s/services-a.jar" /system/framework/services.jar "$SYS" nonul
    q_commit
    rm -rf "$s"
}

stage_lmkd() {
    log "lmkd: parse the 4.4 kernel's /proc/zoneinfo"
    local l=$W/lmkd
    rm -rf "$l" && mkdir -p "$l"
    dump /system/bin/lmkd "$l/lmkd"
    python3 "$ROOT/build/lib/bytepatch.py" "$PATCHES/lmkd/lmkd.bpatch" "$l/lmkd" "$l/lmkd.new"
    q_begin
    q "rm /system/bin/lmkd"
    q_put "$l/lmkd.new" /system/bin/lmkd u:object_r:lmkd_exec:s0
    q_meta /system/bin/lmkd 0100755 u:object_r:lmkd_exec:s0
    q "sif /system/bin/lmkd gid 2000"
    q_commit
    rm -rf "$l"
}

stage_messaging() {
    log "messaging: add RECEIVE_WAP_PUSH/READ_CELL_BROADCASTS"
    local m=$W/messaging d=/system/product/app/messaging
    rm -rf "$m" && mkdir -p "$m/m"
    dump "$d/messaging.apk" "$m/orig.apk"
    "${APKTOOL[@]}" d -s -f -o "$m/dec" "$m/orig.apk" >/dev/null
    # apktool otherwise rewrites targetSdk 24 to 36 (minSdk 36 > targetSdk).
    sed -i '/^sdkInfo:/,/targetSdkVersion/d' "$m/dec/apktool.yml"
    patch -s -p1 -d "$m/dec" < "$PATCHES/messaging/AndroidManifest.xml.diff"
    "${APKTOOL[@]}" b -o "$m/rebuilt.apk" "$m/dec" >/dev/null
    unzip -q -d "$m/m" "$m/rebuilt.apk" AndroidManifest.xml
    python3 "$ROOT/build/lib/zipreplace.py" "$m/orig.apk" "$m/u.apk" --drop-v1-sig \
        AndroidManifest.xml="$m/m/AndroidManifest.xml"
    "$ZIPALIGN" -f -p 4 "$m/u.apk" "$m/a.apk"
    sign_apk platform "$m/a.apk" "$m/messaging.apk"
    q_begin
    q "rm $d/messaging.apk"
    q_put "$m/messaging.apk" "$d/messaging.apk" "$SYS"
    q_commit
    rm -rf "$m"
}

# Repo-owned device files live on system so that A/B OTA payloads carry them;
# vendor.img keeps what is built from stock. They act only on a vendor that sets
# ro.vendor.xp8.layout=2 (see vendor/xp8-gsi.rc).
stage_xp8() {
    log "xp8: overlays, side keys, boot scripts and vendor config patches; no Provision"
    local c=$OUT/components f
    for f in XP8FrameworksRes.apk XP8Settings.apk XP8SystemUI.apk XP8Buttons.apk XP8Updater.apk xp8-keys.dex; do
        [ -f "$c/$f" ] || { echo "missing $c/$f: run build/build-components.sh" >&2; exit 1; }
    done
    q_begin
    # With AOSP Provision, two activities handle SETUP_WIZARD and Google SetupWizard gets no grants.
    q_rm_tree /system/system_ext/priv-app/Provision
    for f in XP8FrameworksRes XP8Settings XP8SystemUI; do
        q_put "$c/$f.apk" "/system/product/overlay/$f.apk" "$SYS"
    done
    q_put "$c/XP8Buttons.apk" /system/product/app/XP8Buttons/XP8Buttons.apk "$SYS"
    q_put "$c/XP8Updater.apk" /system/system_ext/priv-app/XP8Updater/XP8Updater.apk "$SYS"
    q_put "$ROOT/vendor/updater/privapp-permissions-xp8updater.xml" \
        /system/system_ext/etc/permissions/privapp-permissions-xp8updater.xml "$SYS"
    # XP8Updater compares ro.xp8.release with the tag in the latest ota.json.
    dump /system/build.prop "$W/build.prop"
    printf '\n# XP8 GSI release\nro.xp8.release=%s\n' "${XP8_RELEASE:-dev}" >> "$W/build.prop"
    # The vendor sets the density after zygote starts, which made vndk.rc restart zygote.
    printf '\n# XP8 panel density\nro.sf.lcd_density=480\n' >> "$W/build.prop"
    q "rm /system/build.prop"
    q_put "$W/build.prop" /system/build.prop "$SYS"
    # Product build.prop loads after vendor's, so its roaming default wins.
    dump /system/product/etc/build.prop "$W/product.prop"
    grep -qx 'ro.com.android.dataroaming=true' "$W/product.prop" || {
        echo "product build.prop: no ro.com.android.dataroaming=true" >&2; exit 1; }
    sed -i 's/^ro\.com\.android\.dataroaming=true$/ro.com.android.dataroaming=false/' "$W/product.prop"
    q "rm /system/product/etc/build.prop"
    q_put "$W/product.prop" /system/product/etc/build.prop "$SYS"
    dump /system/etc/init/vndk.rc "$W/vndk.rc"
    grep -qx 'on property:ro.sf.lcd_density=\*' "$W/vndk.rc" \
        || { echo "vndk.rc: no ro.sf.lcd_density trigger to remove" >&2; exit 1; }
    sed -i '/^on property:ro\.sf\.lcd_density=\*$/,/^$/d' "$W/vndk.rc"
    q "rm /system/etc/init/vndk.rc"
    q_put "$W/vndk.rc" /system/etc/init/vndk.rc "$SYS"
    # rw-system.sh walks all of /sys for a Focaltech node; the XP8 panel is Cypress.
    dump /system/bin/rw-system.sh "$W/rw-system.sh"
    grep -q 'in .(find /sys -name fts_gesture_mode);do' "$W/rw-system.sh" \
        || { echo "rw-system.sh: no fts_gesture_mode loop to remove" >&2; exit 1; }
    sed -i 's|in .(find /sys -name fts_gesture_mode);do|in ;do|' "$W/rw-system.sh"
    q "rm /system/bin/rw-system.sh"
    q_put "$W/rw-system.sh" /system/bin/rw-system.sh u:object_r:phhsu_exec:s0
    q_meta /system/bin/rw-system.sh 0100755 u:object_r:phhsu_exec:s0
    q "sif /system/bin/rw-system.sh gid 2000"
    dump /system/etc/ueventd.rc "$W/ueventd.rc"
    printf '\nparallel_restorecon enabled\n' >> "$W/ueventd.rc"
    q "rm /system/etc/ueventd.rc"
    q_put "$W/ueventd.rc" /system/etc/ueventd.rc "$SYS"
    q_put "$c/xp8-keys.dex" /system/etc/xp8/xp8-keys.dex "$SYS"
    q_put "$ROOT/vendor/xp8-gsi.rc" /system/etc/init/xp8-gsi.rc "$SYS"
    for f in xp8-gsi.sh keys/xp8-keys.sh xp8-vendor-patch.sh; do
        q_put "$ROOT/vendor/$f" "/system/bin/${f##*/}" "$SYS"
        q_meta "/system/bin/${f##*/}" 0100755 "$SYS"
    done
    for f in "$ROOT"/vendor/audio/*.diff; do
        q_put "$f" "/system/etc/xp8/vendor-patches/${f##*/}" "$SYS"
    done
    q_commit
}

stage_otacerts() {
    [ -n "${OTA_CERT:-}" ] || { log "otacerts: no OTA_CERT, keeping TrebleDroid's"; return; }
    log "otacerts: update_engine accepts payloads signed for $(basename "$OTA_CERT")"
    local o=$W/otacerts
    rm -rf "$o" && mkdir -p "$o"
    cp "$OTA_CERT" "$o/xp8-ota.x509.pem"
    python3 "$ROOT/build/lib/otacerts.py" "$o/otacerts.zip" "$o/xp8-ota.x509.pem"
    q_begin
    q "rm /system/etc/security/otacerts.zip"
    q_put "$o/otacerts.zip" /system/etc/security/otacerts.zip "$SYS"
    q_commit
    rm -rf "$o"
}

stage_final() {
    log "final: fsck and out/system.img"
    e2fsck -fn "$IMG" >/dev/null 2>&1
    # assemble.sh precompiles the vendor's SELinux policy against these.
    local s=$OUT/components/sepolicy p
    rm -rf "$s"
    for p in system:/system system_ext:/system/system_ext product:/system/product; do
        mkdir -p "$s/${p%%:*}"
        debugfs -R "rdump ${p#*:}/etc/selinux $s/${p%%:*}" "$IMG" 2>/dev/null
    done
    [ -s "$s/system/selinux/plat_sepolicy_and_mapping.sha256" ] \
        || { echo "no SELinux policy in $IMG" >&2; exit 1; }
    mv "$IMG" "$OUT/system.img"
    (cd "$OUT" && sha256sum system.img)
}

all=(base gapps gmsquery apexfix ims launcher3 services lmkd messaging xp8 otacerts final)
stages=("$@")
[ ${#stages[@]} -gt 0 ] || stages=("${all[@]}")
for s in "${stages[@]}"; do
    [[ " ${all[*]} " == *" $s "* ]] || { echo "unknown stage: $s" >&2; exit 2; }
    [ "$s" = base ] || [ -f "$IMG" ] || { echo "no $IMG: run the base stage first" >&2; exit 1; }
    start=$SECONDS
    "stage_$s"
    echo "   ($s: $((SECONDS - start)) s)"
done
rm -rf "$CTX" "$CMDS"
