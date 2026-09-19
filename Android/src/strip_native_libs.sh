#!/usr/bin/env bash
# Strips debug symbols from the app's native libraries and stages the result
# where AGP picks up jniLibs for the APK.
#
# AGP's own stripReleaseDebugSymbols left these libraries untouched ("Unable to
# strip the following libraries, packaging them as they are"), which shipped
# ~93 MB of DWARF inside the APK. --strip-unneeded drops the debug sections and
# keeps the dynamic symbol table, so the JNI entry points still resolve.
#
# usage: strip_native_libs.sh <lib-build-dir> <out-dir> [strip-tool]
#   The strip tool is discovered from ANDROID_NDK_HOME, or from the newest
#   NDK under ANDROID_HOME/ndk, when not given explicitly.
set -euo pipefail

src_dir="${1:?lib build dir required}"
out_dir="${2:?output dir required}"
strip_tool="${3:-}"

if [ -z "$strip_tool" ]; then
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        strip_tool="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    elif [ -n "${ANDROID_HOME:-}" ]; then
        ndk_root="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
        if [ -n "$ndk_root" ]; then
            strip_tool="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
        fi
    fi
fi

if [ -z "$strip_tool" ] || [ ! -x "$strip_tool" ]; then
    echo "cannot find an executable llvm-strip (looked at ANDROID_NDK_HOME/ANDROID_HOME; got '${strip_tool:-unset}')" >&2
    exit 1
fi
if [ ! -d "$src_dir" ]; then
    echo "native build dir does not exist: $src_dir" >&2
    exit 1
fi

echo "strip tool: $strip_tool"
mkdir -p "$out_dir"

found=0
while IFS= read -r -d '' lib; do
    base="$(basename "$lib")"
    cp -f "$lib" "$out_dir/$base"
    before="$(stat -c%s "$out_dir/$base")"
    "$strip_tool" --strip-unneeded "$out_dir/$base"
    after="$(stat -c%s "$out_dir/$base")"
    if [ "$after" -ge "$before" ]; then
        echo "strip had no effect on $base ($before -> $after)" >&2
        exit 1
    fi
    echo "stripped $base: $before -> $after bytes"
    found=1
done < <(find "$src_dir" -name 'libvoicepersona_*.so' -print0)

if [ "$found" -ne 1 ]; then
    echo "no libvoicepersona_*.so found under $src_dir" >&2
    exit 1
fi

echo "strip complete -> $out_dir"
