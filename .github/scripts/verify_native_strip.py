#!/usr/bin/env python3
"""Fail the build when the APK ships unstripped native libraries.

AGP silently packages native libraries it declines to strip, which once put
~93 MB of DWARF into a 117 MB APK and made the download unusable on a phone.
This checks the packaged libraries directly: no debug sections, JNI entry
points still exported, and a sane total payload size.

usage: verify_native_strip.py <apk> <ndk-home>
"""
import os
import subprocess
import sys
import tempfile
import zipfile

# Stripped payload for these two libraries is ~9 MB; unstripped was ~104 MB.
MAX_NATIVE_BYTES = 70 * 1024 * 1024
WANT_SYMBOLS = [
    "Java_com_voicepersona_llm_LlamaBridge_nativeLoad",
    "Java_com_voicepersona_asr_AsrBridge_nativeTranscribe",
]


def find_tool(ndk_home: str, name: str) -> str:
    candidate = os.path.join(
        ndk_home, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin", name
    )
    if not os.path.isfile(candidate):
        sys.exit(f"tool not found: {candidate}")
    return candidate


def main() -> int:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    apk, ndk_home = sys.argv[1], sys.argv[2]
    readelf = find_tool(ndk_home, "llvm-readelf")

    failures = []
    with zipfile.ZipFile(apk) as archive:
        libs = [n for n in archive.namelist()
                if n.startswith("lib/") and n.endswith(".so") and "voicepersona" in n]
        print(f"app libraries in APK: {len(libs)}")
        if len(libs) != 2:
            failures.append(f"expected 2 app libraries, found {libs}")

        total = 0
        for name in sorted(libs):
            with tempfile.NamedTemporaryFile(suffix=".so", delete=False) as handle:
                handle.write(archive.read(name))
                path = handle.name
            size = os.path.getsize(path)
            total += size

            sections = subprocess.run([readelf, "-S", path],
                                      capture_output=True, text=True, check=True).stdout
            dwarf = sections.count(".debug_")
            symbols = subprocess.run([readelf, "--dyn-syms", "-W", path],
                                     capture_output=True, text=True, check=True).stdout
            print(f"{name}: {size} bytes, debug sections={dwarf}")

            if dwarf:
                failures.append(f"{name} still carries {dwarf} debug sections")
            if not any(sym in symbols for sym in WANT_SYMBOLS):
                failures.append(f"{name} exports no known JNI entry point")

        print(f"total native payload: {total} bytes")
        if total > MAX_NATIVE_BYTES:
            failures.append(
                f"native payload is {total} bytes, over the {MAX_NATIVE_BYTES} limit"
            )

    if failures:
        print("FAILURES:")
        for item in failures:
            print(" -", item)
        return 1
    print("native library verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
