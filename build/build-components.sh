#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Build the MIT components (shim, vibrator, RROs, side keys) into out/components.
# Needs: build/fetch.sh sdk/ tools/ keys/
set -euo pipefail

# shellcheck source-path=SCRIPTDIR source=lib/tools.sh
. "$(dirname "$0")/lib/tools.sh"
unpack_sdk
unpack_ndk
unpack_patchelf

SRC=$ROOT/vendor
C=$OUT/components
W=$WORK/components
rm -rf "$W" && mkdir -p "$W" "$C"

# libxp8shim.so: 32-bit only, as only /vendor/lib/libgui_vendor.so needs it.
"$NDK_BIN/clang" --target=armv7a-linux-androideabi29 -O2 -fPIC -shared -nostdlib \
    -Wl,-soname,libxp8shim.so -Wl,-z,now -o "$W/libxp8shim.so" "$SRC/shim/xp8shim.cpp"
"$PATCHELF" --add-needed libselinux.so --add-needed libc.so "$W/libxp8shim.so"
cp "$W/libxp8shim.so" "$C/"

# xp8-vibrator: links against a stub libbinder_ndk that adds the LL-NDK symbols.
V=$W/vibrator && mkdir -p "$V"
SYSROOT=$NDK_BIN/../sysroot
VTGT=aarch64-linux-android30
"$NDK_BIN/clang" --target=$VTGT -O2 -fPIE -Wall -Werror -I "$SRC/vibrator/include" \
    -c -o "$V/xp8-vibrator.o" "$SRC/vibrator/xp8-vibrator.c"
python3 "$ROOT/build/lib/ndkstub.py" "$NDK_BIN/llvm-readelf" "$V" \
    "$SYSROOT/usr/lib/aarch64-linux-android/30/libbinder_ndk.so" \
    "$SRC/vibrator/libbinder_ndk.platform.txt" "$V/xp8-vibrator.o" >/dev/null
mkdir -p "$V/stub"
"$NDK_BIN/clang" --target=$VTGT -shared -nostdlib -Wl,-soname,libbinder_ndk.so \
    -Wl,--version-script,"$V/stub.map" -o "$V/stub/libbinder_ndk.so" "$V/stub.c"
"$NDK_BIN/clang" --target=$VTGT -pie -Wl,-z,now -o "$C/xp8-vibrator" "$V/xp8-vibrator.o" \
    -L "$V/stub" -lbinder_ndk -llog
cp "$SRC/vibrator/android.hardware.vibrator-xp8.xml" "$C/"

# Static RROs, signed with the AOSP testkey.
for d in "$SRC"/rro/*/; do
    n=$(basename "$d")
    r=$W/rro/$n && mkdir -p "$r"
    "$AAPT2" compile --dir "$d/res" -o "$r/res.zip"
    "$AAPT2" link -o "$r/u.apk" --manifest "$d/AndroidManifest.xml" -I "$ANDROID_JAR" "$r/res.zip"
    "$ZIPALIGN" -f -p 4 "$r/u.apk" "$r/a.apk"
    sign_apk testkey "$r/a.apk" "$C/$n.apk"
done

# Side keys: the daemon (vendor/keys/Xp8Keys.java, run with app_process) and the
# XP8 Buttons settings app, signed with the AOSP testkey.
K=$W/keys && mkdir -p "$K/daemon" "$K/app"
javac --release 11 -Xlint:-options -cp "$ANDROID_JAR" -d "$K/daemon" "$SRC/keys/Xp8Keys.java"
"${D8[@]}" --release --min-api 29 --lib "$ANDROID_JAR" --output "$K/daemon" "$K"/daemon/*.class
cp "$K/daemon/classes.dex" "$C/xp8-keys.dex"
"$AAPT2" link -o "$K/u.apk" --manifest "$SRC/keys/app/AndroidManifest.xml" -I "$ANDROID_JAR"     --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 1.0
mapfile -t java < <(find "$SRC/keys/app/src" -name '*.java' | sort)
javac --release 11 -Xlint:-options -cp "$ANDROID_JAR" -d "$K/app" "${java[@]}"
mapfile -t classes < <(find "$K/app" -name '*.class' | sort)
"${D8[@]}" --release --min-api 29 --lib "$ANDROID_JAR" --output "$K" "${classes[@]}"
touch -d "@${SOURCE_DATE_EPOCH:-1750118400}" "$K/classes.dex"
(cd "$K" && zip -q -X u.apk classes.dex)
"$ZIPALIGN" -f -p 4 "$K/u.apk" "$K/a.apk"
sign_apk testkey "$K/a.apk" "$C/XP8Buttons.apk"

# System update app, platform-signed for UpdateEngine and REBOOT; vendor/updater/stubs
# stand in for the @SystemApi classes at compile time only.
U=$W/updater && mkdir -p "$U/stubs" "$U/app" "$U/gen"
javac --release 11 -Xlint:-options -cp "$ANDROID_JAR" -d "$U/stubs" "$SRC"/updater/stubs/android/os/*.java
"$AAPT2" compile --dir "$SRC/updater/res" -o "$U/res.zip"
"$AAPT2" link -o "$U/u.apk" --manifest "$SRC/updater/AndroidManifest.xml" -I "$ANDROID_JAR" \
    --java "$U/gen" --min-sdk-version 29 --target-sdk-version 34 --version-code 3 --version-name 1.2 "$U/res.zip"
mapfile -t java < <(find "$SRC/updater/src" "$U/gen" -name '*.java' | sort)
javac --release 11 -Xlint:-options -cp "$ANDROID_JAR:$U/stubs" -d "$U/app" "${java[@]}"
mapfile -t classes < <(find "$U/app" -name '*.class' | sort)
"${D8[@]}" --release --min-api 29 --lib "$ANDROID_JAR" --classpath "$U/stubs" --output "$U" "${classes[@]}"
touch -d "@${SOURCE_DATE_EPOCH:-1750118400}" "$U/classes.dex"
(cd "$U" && zip -q -X u.apk classes.dex)
"$ZIPALIGN" -f -p 4 "$U/u.apk" "$U/a.apk"
sign_apk platform "$U/a.apk" "$C/XP8Updater.apk"

rm -rf "$W"
(cd "$C" && sha256sum ./*.so ./*.apk ./*.dex xp8-vibrator ./*.xml)
