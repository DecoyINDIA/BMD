#!/usr/bin/env python3
"""Verify actual DEX definitions and runner closure without borrowing target classes.

DEX table layout: https://source.android.com/docs/core/runtime/dex-format
Only real class_defs count as definitions; type/string references do not. The
platform exception set comes from the selected Android SDK's android.jar, not a
broad package-prefix allowlist. Annotation-only and reflective string references
are outside this static check and remain covered by the real device test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
from zipfile import BadZipFile, ZipFile

MAX_DEX_BYTES = 64 * 1024 * 1024
REQUIRED_DEFINITIONS = frozenset({
    "Landroidx/test/runner/AndroidJUnitRunner;",
    "Landroidx/test/ext/junit/runners/AndroidJUnit4;",
    "Landroidx/tracing/Trace;",
    "Lcom/blackmarlin/downloader/releasecheck/ReleaseRuntimeTest;",
    "Lorg/junit/Test;",
})


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def dex_class_info(data: bytes) -> tuple[set[str], set[str]]:
    """Return class definitions and member/supertype owners from a standard DEX."""
    if len(data) < 112 or len(data) > MAX_DEX_BYTES or data[:8] not in {
        b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0", b"dex\n040\0"
    }:
        raise ValueError("Unsupported, truncated or oversized DEX")

    def uint(offset: int) -> int:
        if offset < 0 or offset + 4 > len(data):
            raise ValueError("DEX offset is outside the file")
        return struct.unpack_from("<I", data, offset)[0]

    if uint(32) != len(data) or uint(36) != 112 or uint(40) != 0x12345678:
        raise ValueError("DEX header bounds or byte order do not match")

    def table(header: int, stride: int) -> range:
        count, offset = uint(header), uint(header + 4)
        if count and (offset < 112 or offset + count * stride > len(data)):
            raise ValueError("DEX table exceeds file bounds")
        return range(offset, offset + count * stride, stride)

    string_offsets = [uint(offset) for offset in table(56, 4)]

    def descriptor(index: int) -> str:
        if index >= len(string_offsets):
            raise ValueError("DEX string index is out of range")
        offset = string_offsets[index]
        for _ in range(5):  # Skip the bounded UTF-16-length ULEB128.
            if offset >= len(data):
                raise ValueError("Truncated DEX string length")
            value = data[offset]
            offset += 1
            if not value & 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", offset, min(len(data), offset + 16384))
        if end < 0:
            raise ValueError("Unterminated or oversized DEX type descriptor")
        return data[offset:end].decode("utf-8", errors="strict")

    types = [descriptor(uint(offset)) for offset in table(64, 4)]

    def type_at(index: int) -> str:
        if index >= len(types):
            raise ValueError("DEX type index is out of range")
        return types[index]

    definitions: set[str] = set()
    owners: set[str] = set()
    for offset in table(96, 32):
        name = type_at(uint(offset))
        if name in definitions:
            raise ValueError("Duplicate DEX class definition")
        definitions.add(name)
        superclass = uint(offset + 8)
        if superclass != 0xFFFFFFFF:
            owners.add(type_at(superclass))
        interfaces = uint(offset + 12)
        if interfaces:
            count = uint(interfaces)
            if interfaces + 4 + 2 * count > len(data):
                raise ValueError("DEX interface list exceeds file bounds")
            for index in range(count):
                owners.add(type_at(struct.unpack_from("<H", data, interfaces + 4 + 2 * index)[0]))
    for header in (80, 88):  # field_ids and method_ids both start with owner type_idx.
        for offset in table(header, 8):
            owners.add(type_at(struct.unpack_from("<H", data, offset)[0]))
    return definitions, owners


def apk_classes(path: Path) -> tuple[set[str], set[str]]:
    definitions: set[str] = set()
    owners: set[str] = set()
    with ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK has duplicate ZIP entries")
        dex_names = sorted(name for name in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name))
        if not dex_names or "classes.dex" not in dex_names:
            raise ValueError("APK contains no primary DEX")
        for name in dex_names:
            if archive.getinfo(name).file_size > MAX_DEX_BYTES:
                raise ValueError("APK has an oversized DEX")
            current, referenced = dex_class_info(archive.read(name))
            if definitions & current:
                raise ValueError("APK defines a class in multiple DEX files")
            definitions.update(current)
            owners.update(referenced)
    return definitions, owners


def check_class_sets(app: set[str], harness: set[str], owners: set[str], framework: set[str]) -> dict:
    overlap = sorted(app & harness)
    missing = sorted(REQUIRED_DEFINITIONS - harness)
    # Array member calls resolve through the element or platform Object class.
    normalized = {owner.lstrip("[") for owner in owners if owner.lstrip("[").startswith("L")}
    unresolved = sorted(normalized - harness - framework)
    if overlap or missing or unresolved:
        raise ValueError(json.dumps({"overlappingDefinitions": overlap[:100],
                                    "missingRequiredDefinitions": missing,
                                    "unresolvedMemberOrSupertypeOwners": unresolved[:100]}, sort_keys=True))
    return {"requiredDefinitionsPresent": sorted(REQUIRED_DEFINITIONS),
            "overlappingDefinitions": [], "unresolvedMemberOrSupertypeOwners": [],
            "harnessDefinitionCount": len(harness), "targetDefinitionCount": len(app),
            "platformOwnersUsed": sorted(normalized & framework),
            "privateHarnessDefinitionCount": sum(name.startswith(
                "Lcom/blackmarlin/downloader/releasecheck/runtime/") for name in harness)}


def verify_apks(release_apk: Path, test_apk: Path, android_jar: Path) -> dict:
    app, _ = apk_classes(release_apk)
    harness, owners = apk_classes(test_apk)
    with ZipFile(android_jar) as archive:
        framework = {"L" + name[:-6] + ";" for name in archive.namelist()
                     if name.endswith(".class") and not name.startswith("META-INF/")}
    if "Ljava/lang/Object;" not in framework or "Landroid/app/Instrumentation;" not in framework:
        raise ValueError("Expected the Android SDK platform android.jar")
    result = check_class_sets(app, harness, owners, framework)
    return {"schemaVersion": 1, "status": "passed",
            "scope": "DEX class definitions, member-owner and supertype closure; device execution remains mandatory.",
            "applicationApkSha256": digest(release_apk), "harnessApkSha256": digest(test_apk),
            "androidJarSha256": digest(android_jar), **result}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify_apks(args.release_apk, args.test_apk, args.android_jar), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, BadZipFile, struct.error) as error:
        print(f"Release harness DEX verification failed: {error}", file=sys.stderr)
        sys.exit(1)
