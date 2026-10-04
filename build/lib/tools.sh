# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Sourced by build-*.sh: unpacks the fetched SDK pieces once into cache/unpacked
# and exports tool paths. Run build/fetch.sh first.
# shellcheck shell=bash disable=SC2034

ROOT=${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
CACHE=${CACHE:-$ROOT/cache}
WORK=${WORK:-$ROOT/work}
OUT=${OUT:-$ROOT/out}
UNP=$CACHE/unpacked
NDK_VER=r27d

need() {
    local f
    for f; do
        [ -e "$CACHE/$f" ] || { echo "missing cache/$f: run build/fetch.sh" >&2; exit 1; }
    done
}

unpack_sdk() {
    need sdk/build-tools_r36_linux.zip sdk/platform-36_r02.zip
    mkdir -p "$UNP"
    if [ ! -x "$UNP/build-tools/aapt2" ]; then
        rm -rf "$UNP/build-tools" "$UNP/bt.tmp"
        unzip -q -d "$UNP/bt.tmp" "$CACHE/sdk/build-tools_r36_linux.zip"
        mv "$UNP/bt.tmp/android-16" "$UNP/build-tools"
        rmdir "$UNP/bt.tmp"
    fi
    if [ ! -f "$UNP/android-36/android.jar" ]; then
        unzip -q -o -d "$UNP" "$CACHE/sdk/platform-36_r02.zip" android-36/android.jar
    fi
    AAPT2=$UNP/build-tools/aapt2
    ZIPALIGN=$UNP/build-tools/zipalign
    APKSIGNER=(java -jar "$UNP/build-tools/lib/apksigner.jar")
    D8=(java -cp "$UNP/build-tools/lib/d8.jar" com.android.tools.r8.D8)
    ANDROID_JAR=$UNP/android-36/android.jar
}

unpack_ndk() {
    need sdk/android-ndk-$NDK_VER-linux.zip
    mkdir -p "$UNP"
    local p=android-ndk-$NDK_VER/toolchains/llvm/prebuilt/linux-x86_64
    if [ ! -e "$UNP/$p/lib/libxml2.so.2" ]; then
        rm -rf "$UNP/android-ndk-$NDK_VER"
        unzip -q -o -d "$UNP" "$CACHE/sdk/android-ndk-$NDK_VER-linux.zip" \
            "$p/bin/clang" "$p/bin/clang-[0-9]*" "$p/bin/ld.lld" "$p/bin/lld" \
            "$p/bin/llvm-strip" "$p/bin/llvm-readelf" "$p/bin/llvm-readobj" "$p/lib/libxml2.so.2" \
            "$p/sysroot/*" "$p/lib/clang/*/include/*" \
            "$p/lib/clang/*/lib/linux/libclang_rt.builtins-a*-android.a" \
            "$p/lib/clang/*/lib/linux/arm/*" "$p/lib/clang/*/lib/linux/aarch64/*"
    fi
    NDK_BIN=$UNP/$p/bin
}

unpack_patchelf() {
    need tools/patchelf-0.19.1-x86_64.tar.gz
    if [ ! -x "$UNP/patchelf/bin/patchelf" ]; then
        mkdir -p "$UNP/patchelf"
        tar -xzf "$CACHE/tools/patchelf-0.19.1-x86_64.tar.gz" -C "$UNP/patchelf" ./bin/patchelf
    fi
    PATCHELF=$UNP/patchelf/bin/patchelf
}

tool_jars() {
    need tools/apktool.jar tools/apktool_2.10.0.jar tools/smali.jar tools/baksmali.jar tools/avbtool.py
    APKTOOL=(java -jar "$CACHE/tools/apktool.jar")
    APKTOOL_IMS=(java -jar "$CACHE/tools/apktool_2.10.0.jar")
    SMALI=(java -jar "$CACHE/tools/smali.jar")
    BAKSMALI=(java -jar "$CACHE/tools/baksmali.jar")
    AVBTOOL=(python3 "$CACHE/tools/avbtool.py")
}

KEYS=$CACHE/keys

sign_apk() { # key in out
    "${APKSIGNER[@]}" sign --key "$KEYS/$1.pk8" --cert "$KEYS/$1.x509.pem" --out "$3" "$2"
    rm -f "$3.idsig"
}
