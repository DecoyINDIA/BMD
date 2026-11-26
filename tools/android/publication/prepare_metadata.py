#!/usr/bin/env python3
"""Rebuild publication metadata from explicit inputs; do not edit the application repo.

Official-source research was performed on SOURCE_RESEARCH_CHECKED_ON. Running this
script again hashes local inputs; it does not repeat that research or grant review.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

SOURCE_RESEARCH_CHECKED_ON = "2026-11-26"
TITLE = "Black Marlin Downloader"
SHORT = "Direct link downloads with pause, network controls and local save recovery"
FULL = """Bring direct HTTP and HTTPS file downloads into one native Android app.

Add a link by typing, tapping Paste, or sharing text from another app. Review the file details the source provides, choose a name and destination, then start.

Keep transfers under control
Pause a download and resume when the source supports safe continuation. Review a restart when the source changes. Choose unmetered connections by default, or allow another network for a task. Adjust transfer and connection limits in Settings.

Recover a save
If a destination cannot finish saving, Black Marlin keeps the downloaded data for recovery. Retry saving from those retained bytes. When a provider may already have saved a document, select it for a full comparison or explicitly choose a separate copy.

Find your files
Search, sort and select saved files. Open or share a file with Android, remove it from the app's list, or confirm deletion from its destination. Choose a local folder through Android's picker; Downloads is also available on supported Android versions.

Check the file you expected
Enter an optional expected SHA-256 checksum before starting. A matching result confirms that the downloaded bytes match that value. For sources that need authentication, enter HTTP Basic credentials or a Bearer token scoped to that exact HTTPS origin.

Your device, your choices
Choose a light or dark theme and control notification privacy. Black Marlin does not require its own account and includes no advertising or analytics tracking. Paste reads clipboard text only when you ask. Diagnostic reports are generated locally for you to review before sharing.

This version supports direct HTTP(S) file links. Android and the source server can interrupt background work; resume depends on the source. Some document providers require choosing a local folder. Sources, document providers and receiving apps handle their own requests and files under their own policies."""

SOURCES = [
    ("P01", "Create and set up your app", "https://support.google.com/googleplay/android-developer/answer/9859152?hl=en"),
    ("P02", "Add preview assets to showcase your app", "https://support.google.com/googleplay/android-developer/answer/9866151?hl=en"),
    ("P03", "Google Play icon design specifications", "https://developer.android.com/distribute/google-play/resources/icon-design-specifications"),
    ("P04", "Provide information for Google Play's Data safety section", "https://support.google.com/googleplay/android-developer/answer/10787469?hl=en-GB"),
    ("P05", "Understanding foreground service and full-screen intent requirements", "https://support.google.com/googleplay/android-developer/answer/13392821?hl=en"),
    ("P06", "Prepare your app for review", "https://support.google.com/googleplay/android-developer/answer/9859455?hl=en-en"),
    ("P07", "Developer Program Policy: privacy policy and account deletion", "https://support.google.com/googleplay/android-developer/answer/18265346?hl=en-GB"),
    ("P08", "Meet Google Play's target API level requirement", "https://developer.android.com/google/play/requirements/target-sdk"),
    ("P09", "Device and network abuse: user-initiated data transfer jobs", "https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-IN"),
    ("P10", "App testing requirements for new personal developer accounts", "https://support.google.com/googleplay/android-developer/answer/14151465?hl=en"),
    ("P11", "Sign your app", "https://developer.android.com/studio/publish/app-signing"),
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    if not __debug__:
        raise SystemExit("Publication checks require normal Python execution; remove -O or PYTHONOPTIMIZE.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True, help="BMD Git checkout; read only")
    parser.add_argument("--ref", default="HEAD", help="Exact source commit or ref to snapshot")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--merged-manifest", type=Path, help="Optional actual release merged manifest; caller must verify its build provenance")
    parser.add_argument("--evidence-image", type=Path, help="Optional exact CI screenshot; requires matching provenance")
    parser.add_argument("--evidence-provenance", type=Path, help="JSON with verification-only status, SHA-256 and relative sourceArtifactPath")
    args = parser.parse_args()
    repo, out = args.repo.resolve(), args.out.resolve()
    if out == repo or repo in out.parents:
        parser.error("--out must be outside the application repository")
    if bool(args.evidence_image) != bool(args.evidence_provenance):
        parser.error("supply both --evidence-image and --evidence-provenance, or neither")
    evidence = None
    if args.evidence_image:
        if not args.evidence_image.is_file() or not args.evidence_provenance.is_file():
            parser.error("both evidence files must exist")
        evidence = json.loads(args.evidence_provenance.read_text(encoding="utf-8"))
        if evidence.get("status") != "verification-evidence-only-not-for-store-upload":
            parser.error("evidence must retain its verification-only classification")
        if evidence.get("sha256") != digest(args.evidence_image.read_bytes()):
            parser.error("evidence image does not match the recorded SHA-256")
        relative = PurePosixPath(evidence.get("sourceArtifactPath", ""))
        if str(relative) == "." or relative.is_absolute() or ".." in relative.parts or "sourceCapture" in evidence:
            parser.error("provenance must use a relative sourceArtifactPath, without machine-specific sourceCapture")
    commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--verify", args.ref + "^{commit}"], text=True).strip()
    def source(path):
        return subprocess.check_output(["git", "-C", str(repo), "show", f"{commit}:{path}"])
    claim_evidence = {
        "reviewPauseResumeNetwork": ["android/app/src/main/java/com/blackmarlin/downloader/ui/DownloadScreens.kt", "android/app/src/main/java/com/blackmarlin/downloader/data/BmdRepository.kt", "android/app/src/main/java/com/blackmarlin/downloader/platform/TransferCoordinator.kt"],
        "publicationRecovery": ["android/app/src/main/java/com/blackmarlin/downloader/storage/PublicationManager.kt"],
        "filesAndAppearance": ["android/app/src/main/java/com/blackmarlin/downloader/ui/FileScreens.kt", "android/app/src/main/java/com/blackmarlin/downloader/ui/SettingsScreens.kt"],
        "credentialsAndPrivacy": ["android/app/src/main/java/com/blackmarlin/downloader/data/CredentialVault.kt", "android/app/src/main/java/com/blackmarlin/downloader/domain/DownloadPolicy.kt", "docs/android/PRIVACY.md"],
    }
    paths = sorted({
        "android/app/src/main/AndroidManifest.xml", "android/app/build.gradle.kts",
        "android/app/src/main/res/drawable/ic_launcher.xml", "android/app/src/main/res/xml/backup_rules.xml",
        "android/app/src/main/res/xml/data_extraction_rules.xml", "android/app/src/main/res/xml/provider_paths.xml",
        "android/app/src/main/java/com/blackmarlin/downloader/MainActivity.kt",
        "android/app/src/main/java/com/blackmarlin/downloader/ui/BmdViewModel.kt",
        "android/app/src/main/java/com/blackmarlin/downloader/platform/TransferExecutors.kt",
        "android/app/src/main/java/com/blackmarlin/downloader/storage/PrivateTransferStorage.kt",
        "docs/android/PRIVACY.md", "docs/android/BUILD_AND_RELEASE.md",
        *(path for items in claim_evidence.values() for path in items),
    })
    inputs = {path: source(path) for path in paths}
    android = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.fromstring(inputs["android/app/src/main/AndroidManifest.xml"])
    requested = [n.get(android + "name") for n in manifest.findall("uses-permission")]
    gradle = inputs["android/app/build.gradle.kts"].decode("utf-8")
    def literal(name, quoted=False):
        pattern = rf'\b{name}\s*=\s*"([^"]+)"' if quoted else rf'\b{name}\s*=\s*(\d+)'
        found = re.search(pattern, gradle)
        if found is None:
            parser.error(f"cannot read literal {name} from the selected app build file")
        return found.group(1) if quoted else int(found.group(1))
    abi_match = re.search(r'abiFilters\s*\+=\s*setOf\(([^)]+)\)', gradle)
    if abi_match is None:
        parser.error("cannot read explicit ABI filters from the selected app build file")
    compatibility = {"applicationId": literal("applicationId", True), "minSdk": literal("minSdk"),
        "targetSdk": literal("targetSdk"), "versionName": literal("versionName", True),
        "versionCode": literal("versionCode"), "abis": re.findall(r'"([^"]+)"', abi_match.group(1))}
    merged = None
    if args.merged_manifest:
        data = args.merged_manifest.read_bytes()
        parsed = ET.fromstring(data)
        merged = {"sha256": digest(data), "package": parsed.get("package"),
                  "requestedPermissions": [n.get(android + "name") for n in parsed.findall("uses-permission")],
                  "provenance": "Caller-supplied release merged manifest; verify against the selected build separately."}
    counts = {"title": len(TITLE), "shortDescription": len(SHORT), "fullDescription": len(FULL)}
    limits = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
    assert all(counts[key] <= limits[key] for key in limits)
    listing = {
        "status": "draft-for-independent-review-not-submitted", "language": "en-US", "sourceCommit": commit,
        "title": TITLE, "shortDescription": SHORT, "fullDescription": FULL, "characterCounts": counts,
        "characterLimits": limits, "limitsSource": "P01", "proposedCategory": "Tools",
        "publisherName": None, "supportEmail": None, "privacyPolicyUrl": None, "contentRating": None,
        "priceModel": None, "launchCountries": None, "listingScreenshotsReady": False, "storeApproval": None,
        "compatibility": compatibility,
        "claimEvidence": claim_evidence,
        "claimsIntentionallyAbsent": ["measured speed or battery advantage", "uninterrupted background execution",
            "universal Android8 support", "unlimited connections", "video-site extraction", "all traffic encrypted",
            "Play approval", "independent security-lab certification"],
    }
    snapshot = {"localSnapshotRecordedOn": datetime.now(timezone.utc).date().isoformat(),
        "officialSourcesCheckedOn": SOURCE_RESEARCH_CHECKED_ON, "commit": commit,
        "snapshotMethod": "git show of the exact commit; unrelated working-tree edits excluded",
        "sourceDeclaredPermissions": requested, "releaseMergedManifest": merged,
        "historicalMergedManifest": {"commit": "0c89a5e88ec00ba6bb5eec740e18c5892817e581",
            "sha256": "66b24b396adbf993b8eaf4b5ed72ef501f680648b9bafbc62fa1d4299fbd327c",
            "requestedPermissions": ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS", "android.permission.RUN_USER_INITIATED_JOBS",
                "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.WAKE_LOCK", "android.permission.RECEIVE_BOOT_COMPLETED",
                "com.blackmarlin.downloader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"],
            "scope": "Previously inspected historical release merge; not current-binary proof"},
        "files": [{"path": path, "sha256": digest(data)} for path, data in inputs.items()]}
    out.mkdir(parents=True, exist_ok=True)
    def write_json(name, data):
        (out / name).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    write_json("store-listing-en-US.json", listing)
    for name, content in [("title", TITLE), ("short-description", SHORT), ("full-description", FULL)]:
        (out / f"listing-{name}.txt").write_text(content + "\n", encoding="utf-8")
    write_json("sources.json", {"checkedOn": SOURCE_RESEARCH_CHECKED_ON, "sources": [
        {"id": sid, "title": title, "url": url, "kind": "official-primary"} for sid, title, url in SOURCES]})
    write_json("source-snapshot.json", snapshot)
    if evidence:
        folder = out / "verification-evidence"
        folder.mkdir(exist_ok=True)
        target = folder / "observed-ci-capture.png"
        if target.resolve() != args.evidence_image.resolve():
            shutil.copyfile(args.evidence_image, target)
        assert digest(target.read_bytes()) == evidence["sha256"]
        evidence["bytesUnchanged"] = True
        write_json("verification-evidence/provenance.json", evidence)
    print(json.dumps({"sourceCommit": commit, "counts": counts, "sourceFilesHashed": len(inputs),
                      "currentMergedManifestSupplied": merged is not None, "evidenceImageSupplied": evidence is not None}, indent=2))


if __name__ == "__main__":
    main()
