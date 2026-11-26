#!/usr/bin/env python3
"""Observe, rather than assume, the emulator API, ABI and kernel page size."""
from __future__ import annotations

import argparse
import json
import re
import subprocess


def positive_integer(value: str, label: str) -> int:
    value = value.strip()
    if not re.fullmatch(r"[1-9][0-9]*", value):
        raise ValueError(f"Missing or ambiguous {label}: {value[:160]!r}")
    return int(value)


def smaps_page_size(value: str) -> int:
    rows = [line for line in value.splitlines() if line.startswith("KernelPageSize:")]
    if not rows:
        raise ValueError("No KernelPageSize observations in /proc/self/smaps")
    sizes = set()
    for row in rows:
        match = re.fullmatch(r"KernelPageSize:\s+([1-9][0-9]*)\s+kB\s*", row)
        if not match:
            raise ValueError(f"Malformed KernelPageSize observation: {row[:160]!r}")
        sizes.add(int(match[1]) * 1024)
    if len(sizes) != 1:
        raise ValueError(f"Ambiguous KernelPageSize observations: {sorted(sizes)}")
    size = sizes.pop()
    if size & (size - 1):
        raise ValueError(f"Kernel page size is not a power of two: {size}")
    return size


def may_use_smaps(api: int, result: subprocess.CompletedProcess[str]) -> bool:
    # The API 26 image does not ship getconf. Never treat an adb transport,
    # command timeout or a failing modern-image observation as a fallback.
    return (
        api == 26
        and result.returncode == 127
        and re.search(r"\bgetconf:\s*(?:inaccessible or )?not found\b", result.stdout + result.stderr) is not None
    )


def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["adb", "shell", *args], text=True, capture_output=True,
        timeout=30, check=check,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--page-size", type=int, required=True)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()
    api = positive_integer(adb("getprop", "ro.build.version.sdk").stdout, "Android API")
    abi = adb("getprop", "ro.product.cpu.abi").stdout.strip()
    if api != args.api or abi != "x86_64":
        raise ValueError(f"Unexpected runtime API/ABI: {api}/{abi!r}")
    result = adb("getconf", "PAGE_SIZE", check=False)
    if result.returncode == 0:
        page_size = positive_integer(result.stdout, "getconf PAGE_SIZE")
        method = "getconf PAGE_SIZE"
    elif may_use_smaps(api, result):
        page_size = smaps_page_size(adb("cat", "/proc/self/smaps").stdout)
        method = "/proc/self/smaps KernelPageSize (API 26 getconf unavailable)"
    else:
        raise ValueError(f"Required getconf observation failed: exit {result.returncode}; {result.stderr[:160]!r}")
    if page_size != args.page_size:
        raise ValueError(f"Expected {args.page_size}-byte pages; observed {page_size}")
    meminfo = adb("cat", "/proc/meminfo").stdout
    totals = re.findall(r"^MemTotal:\s+([1-9][0-9]*)\s+kB\s*$", meminfo, re.MULTILINE)
    if len(totals) != 1:
        raise ValueError("Missing or ambiguous observed guest MemTotal")
    print(json.dumps({
        "observedApi": api,
        "observedPageSizeBytes": page_size,
        "pageSizeObservationMethod": method,
        "systemImageTag": args.image,
        "observedAbi": abi,
        "observedGuestMemTotalBytes": int(totals[0]) * 1024,
    }, indent=2))


if __name__ == "__main__":
    main()
