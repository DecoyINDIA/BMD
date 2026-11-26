#!/usr/bin/env python3
"""Require actual named Android test passes; assumptions/skips cannot satisfy the download gate."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys

TEST_CLASS = "com.blackmarlin.downloader.ui.NativeAppSmokeTest"
DESCRIPTOR_CLASS = "com.blackmarlin.downloader.storage.PublicationDescriptorDeviceTest"
BASIC = {"realDatabaseAndNativeEngineInitialize", "nativeNavigationPersistsAppearanceThroughRealRepository",
         "sharedUnsupportedLinkStaysInEditorAndNeverCreatesATask", "pinnedHttpsSourceUsesAndroidPlatformTrust"}
DOWNLOAD = "suppliedDirectLinkDownloadsThroughRustAndPublishesExactBytes"
SAF = "suppliedDirectLinkSavesThroughRealSafPickerAndVerifiesBytes"
REGULAR_DESCRIPTOR = "regularDescriptorsRemainUsableOnSupportedAndroidVersions"
OLD_PIPE_DESCRIPTOR = "olderAndroidRejectsReadAndWritePipeDescriptorsAndClosesThem"


def evaluate(output: str, api: int) -> dict:
    current: dict[str, str] = {}
    passed: set[str] = set()
    skipped: set[str] = set()
    failed: set[str] = set()
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(": ", 1)[1])
            name = current.get("class", "") + "#" + current.get("test", "")
            if code == 0:
                passed.add(name)
            elif code in {-3, -4}:
                skipped.add(name)
            elif code < 0:
                failed.add(name)
            current = {}
    required = BASIC | {SAF} | ({DOWNLOAD} if api >= 29 else set())
    expected = {TEST_CLASS + "#" + test for test in required}
    expected.add(DESCRIPTOR_CLASS + "#" + REGULAR_DESCRIPTOR)
    if api < 30:
        expected.add(DESCRIPTOR_CLASS + "#" + OLD_PIPE_DESCRIPTOR)
    missing = expected - passed
    success = re.search(r"^OK \(\d+ tests?\)", output, re.MULTILINE) is not None
    aborted = re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED", output) is not None
    if missing or failed or not success or aborted:
        raise RuntimeError(f"Instrumented acceptance failed: missing actual passes={sorted(missing)}, failed={sorted(failed)}, summary_success={success}, aborted={aborted}")
    return {"api": api, "passed": sorted(passed), "skipped": sorted(skipped),
            "mediaStoreDownloadAndPublicationVerified": api >= 29,
            "safDownloadAndPublicationVerified": True,
            "androidPlatformTrustHttpsProbeVerified": True,
            "regularDescriptorReadWriteVerified": True,
            "olderAndroidPipeRejectionVerified": api < 30}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--api", type=int, required=True)
    args = parser.parse_args()
    print(json.dumps(evaluate(args.output.read_text(errors="replace"), args.api), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Android test gate failed: {error}", file=sys.stderr)
        sys.exit(1)
