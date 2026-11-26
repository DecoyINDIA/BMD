#!/usr/bin/env python3
"""Refuse absent, stale or invalid JNI artifacts; optionally inspect a built APK/AAB."""
from __future__ import annotations
import argparse
import json
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile
from native_common import ROOT, NDK_VERSION, ABIS, LIBRARY, SYMBOLS, OUTPUT, digest, ndk_path, ndk_bin, tool, source_info

SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}

def inspect(path: Path, abi: str, binaries: Path) -> dict:
    data = path.read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise RuntimeError(f"{abi}: expected a little-endian ELF64 shared library")
    if struct.unpack_from("<H", data, 16)[0] != 3:
        raise RuntimeError(f"{abi}: ELF is not a shared object")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != ABIS[abi][1]:
        raise RuntimeError(f"{abi}: wrong ELF architecture {machine}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or count == 0 or offset + count * size > len(data):
        raise RuntimeError(f"{abi}: invalid ELF program headers")
    alignments = []
    for number in range(count):
        p_type, _flags, file_offset, virtual, _physical, _file_size, _memory, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + size * number)
        if p_type == 1:
            if alignment < 16384 or alignment & (alignment - 1) or file_offset % 16384 != virtual % 16384:
                raise RuntimeError(f"{abi}: LOAD segment is not compatible with 16 KiB pages")
            alignments.append(alignment)
    if not alignments:
        raise RuntimeError(f"{abi}: ELF has no LOAD segments")
    names = subprocess.check_output([str(tool(binaries, "llvm-nm")), "--dynamic", "--defined-only", str(path)], text=True)
    exports = {line.split()[-1] for line in names.splitlines() if line.split()}
    missing = SYMBOLS - exports
    if missing:
        raise RuntimeError(f"{abi}: missing JNI exports: {', '.join(sorted(missing))}")
    dynamic = subprocess.check_output([str(tool(binaries, "llvm-readelf")), "--dynamic", str(path)], text=True)
    needed = set(re.findall(r"Shared library: \[([^]]+)\]", dynamic))
    if needed - SYSTEM_LIBRARIES:
        raise RuntimeError(f"{abi}: additional native dependencies need explicit packaging: {sorted(needed - SYSTEM_LIBRARIES)}")
    return {"sha256": digest(path), "bytes": len(data), "machine": machine, "loadAlignments": alignments, "jniSymbols": sorted(SYMBOLS), "neededLibraries": sorted(needed)}

def verify(output: Path, binaries: Path, require_current: bool = True) -> dict:
    manifest_path = output / "assets/native-build.json"
    if not manifest_path.is_file():
        raise RuntimeError("Native manifest missing. Run tools/android/build_native.py; skipNativeBuild cannot manufacture an APK backend.")
    manifest = json.loads(manifest_path.read_text())
    if (manifest.get("schemaVersion") != 1 or manifest.get("profile") != "android" or manifest.get("api") != 26
            or manifest.get("ndk") != NDK_VERSION or not manifest.get("rustc", "").startswith("rustc 1.98.0 ")):
        raise RuntimeError("Unsupported native artifact contract")
    if manifest.get("licenseTextReviewRequired"):
        raise RuntimeError("Native dependency license text review is incomplete: " + ", ".join(manifest["licenseTextReviewRequired"]))
    current = source_info()
    if require_current and (manifest["source"]["nativeSourceSha256"] != current["nativeSourceSha256"] or manifest["source"].get("gitCommit") != current.get("gitCommit")):
        raise RuntimeError("Native binaries are stale for this source revision. Rebuild the native engine before packaging.")
    for abi in ABIS:
        path = output / "jniLibs" / abi / LIBRARY
        if not path.is_file():
            raise RuntimeError(f"Missing required native ABI: {abi}")
        actual = inspect(path, abi, binaries)
        if actual != manifest["artifacts"].get(abi):
            raise RuntimeError(f"{abi}: native manifest does not match the binary")
    notices = output / "assets/THIRD_PARTY_RUST_NOTICES.txt"
    if not notices.is_file() or digest(notices) != manifest.get("noticesSha256"):
        raise RuntimeError("Native dependency notices missing or changed")
    return manifest

def verify_archive(archive: Path, binaries: Path, expected: dict) -> None:
    prefix = "base/" if archive.suffix == ".aab" else ""
    with ZipFile(archive) as package, tempfile.TemporaryDirectory(prefix="bmd-elf-") as folder:
        manifest = json.loads(package.read(prefix + "assets/native-build.json"))
        if manifest != expected:
            raise RuntimeError(f"{archive.name}: packaged native metadata does not match the verified build")
        for abi in ABIS:
            path = Path(folder) / (abi + ".so")
            path.write_bytes(package.read(prefix + f"lib/{abi}/{LIBRARY}"))
            if inspect(path, abi, binaries) != expected["artifacts"][abi]:
                raise RuntimeError(f"{archive.name}: packaged {abi} native library is stale")
        if package.read(prefix + "assets/GPL-3.0.txt") != (ROOT / "LICENSE").read_bytes():
            raise RuntimeError(f"{archive.name}: full GPL license asset missing or changed")
        notices = package.read(prefix + "assets/THIRD_PARTY_RUST_NOTICES.txt")
        import hashlib
        if hashlib.sha256(notices).hexdigest() != expected["noticesSha256"]:
            raise RuntimeError(f"{archive.name}: packaged dependency notices mismatch")
        android_report = json.loads(package.read(prefix + "assets/android-dependencies.json"))
        android_notices = package.read(prefix + "assets/THIRD_PARTY_ANDROID_NOTICES.txt")
        generated_report = ROOT / "android/app/build/generated/androidNotices/android-dependencies.json"
        if android_report != json.loads(generated_report.read_text()) or not android_report.get("dependencies"):
            raise RuntimeError(f"{archive.name}: packaged Android dependency metadata is stale or absent")
        if hashlib.sha256(android_notices).hexdigest() != android_report.get("noticesSha256"):
            raise RuntimeError(f"{archive.name}: packaged Android dependency notices mismatch")

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--ndk")
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--bundle", type=Path, action="append", default=[])
    args = parser.parse_args()
    binaries = ndk_bin(ndk_path(args.ndk))
    manifest = verify(args.output, binaries)
    for path in args.apk + args.bundle:
        verify_archive(path, binaries, manifest)
    print(json.dumps({"verified": True, "nativeSourceSha256": manifest["source"]["nativeSourceSha256"], "abis": list(ABIS), "archives": [str(p) for p in args.apk + args.bundle]}, indent=2))

if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Native verification failed: {error}", file=sys.stderr)
        sys.exit(1)
