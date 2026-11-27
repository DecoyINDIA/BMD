#!/usr/bin/env python3
"""Print bounded current-run failure evidence; never changes acceptance results."""
from __future__ import annotations

import hashlib
from pathlib import Path
import re
import uuid
import xml.etree.ElementTree as ET

LIMIT = 64 * 1024 * 1024


def emit(title: str, content: str, limit: int = 16000) -> None:
    marker = uuid.uuid4().hex
    print(f"::group::CURRENT device diagnostics / {title}", flush=True)
    print(f"::stop-commands::{marker}", flush=True)
    try:
        print(content[:limit])
        if len(content) > limit:
            print("[bounded diagnostic output truncated]")
    finally:
        print(f"::{marker}::", flush=True)
        print("::endgroup::", flush=True)


def read(path: Path) -> str | None:
    if not path.is_file():
        return None
    if path.stat().st_size > LIMIT:
        emit(str(path), "Diagnostic file exceeds the 64 MiB read bound.")
        return None
    return path.read_text(errors="replace")


def main() -> None:
    paths = [Path(name) for name in ("device-runtime.json", "acceptance.json", "release-acceptance.json",
             "debug-exit-info.txt", "release-exit-info.txt")]
    for folder in ("device-test-evidence", "release-test-evidence"):
        paths.extend(sorted(Path(folder).glob("*.txt"))[:8])
        paths.extend(sorted(Path(folder).glob("*.json"))[:8])
        for path in sorted(Path(folder).glob("*.xml"))[:8]:
            value = read(path)
            if value is None:
                continue
            try:
                nodes = ET.fromstring(value).iter("node")
                selected = [dict(node.attrib) for node in nodes if node.get("text") or node.get("content-desc")]
                import json
                emit(str(path), json.dumps(selected[:100], ensure_ascii=False, indent=2))
            except ET.ParseError as error:
                emit(str(path), f"Invalid captured XML: {error}")
    for path in paths:
        value = read(path)
        if value is not None:
            emit(str(path), "sha256=" + hashlib.sha256(path.read_bytes()).hexdigest() + "\n" + value)
    value = read(Path("logcat.txt"))
    if value is None:
        return
    lines = value.splitlines()
    fatal = re.compile(r"FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|VerifyError|UnsatisfiedLinkError|"
                       r"Unable to instantiate|lowmemorykiller|lmkd|crash of app|process .* has died", re.I)
    relevant = re.compile(r"blackmarlin|releasecheck|AndroidJUnitRunner|InstrumentationTestRunner", re.I)
    selected: set[int] = set()
    for index, line in enumerate(lines):
        if fatal.search(line):
            start, end = max(0, index - 8), min(len(lines), index + 35)
            if any(relevant.search(item) for item in lines[start:end]):
                selected.update(range(start, end))
    excerpt = "\n".join(f"{index + 1}: {lines[index][:3000]}" for index in sorted(selected)[-600:])
    emit("app/runner fatal and process-exit logcat context", excerpt or "No matching context was captured.", 60000)


if __name__ == "__main__":
    main()
