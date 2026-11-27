#!/usr/bin/env python3
"""Sign test-only copies of the verified R8 APK/harness, proving all payloads unchanged."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
from zipfile import ZipFile

from check_release_harness import verify_apks

APP_ID = "com.blackmarlin.downloader"
TEST_ID = "com.blackmarlin.downloader.releasecheck"
ROOT = Path(__file__).resolve().parents[2]
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", re.IGNORECASE)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def payloads(path: Path) -> dict[str, dict]:
    with ZipFile(path) as apk:
        names = apk.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError("APK has duplicate ZIP entries")
        return {name: {"sha256": hashlib.sha256(apk.read(name)).hexdigest(), "bytes": apk.getinfo(name).file_size}
                for name in sorted(names) if not name.endswith("/") and not SIGNATURE_ENTRY.fullmatch(name)}


def run(args: list[str], env: dict | None = None) -> str:
    result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed ({result.returncode}): {result.stderr[-3000:]}")
    return result.stdout


def check_manifest(apk: Path, build_tools: Path, expected_package: str, target: str | None = None) -> None:
    badging = run([str(build_tools / "aapt2"), "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not package or package.group(1) != expected_package:
        raise RuntimeError("Unexpected APK package ID")
    if target is None and "application-debuggable" in badging.splitlines():
        raise RuntimeError("Release target is debuggable")
    if target is not None:
        manifest = run([str(build_tools / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"])
        if not re.search(r':targetPackage\([^)]*\)="' + re.escape(target) + '"', manifest):
            raise RuntimeError("Instrumentation does not target the production package")
        if "androidx.test.runner.AndroidJUnitRunner" not in manifest:
            raise RuntimeError("Unexpected instrumentation runner")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output == ROOT or ROOT in output.parents:
        raise RuntimeError("Ephemeral key and signed copies must be outside the repository/artifact tree")
    if output.exists():
        raise RuntimeError("Use a fresh private directory for ephemeral runtime signing")
    output.mkdir(parents=True, mode=0o700)
    original_hash = digest(args.release_apk)
    harness_hash = digest(args.test_apk)
    harness_closure = verify_apks(args.release_apk, args.test_apk, args.android_jar, args.build_tools)
    check_manifest(args.release_apk, args.build_tools, APP_ID)
    check_manifest(args.test_apk, args.build_tools, TEST_ID, APP_ID)
    original_payloads = payloads(args.release_apk)
    if not any(re.fullmatch(r"classes\d*\.dex", name) for name in original_payloads):
        raise RuntimeError("Release APK contains no DEX")
    if not all(f"lib/{abi}/libbmd_android_bridge.so" in original_payloads for abi in ("arm64-v8a", "x86_64")):
        raise RuntimeError("Release APK is missing a required native ABI")
    env = os.environ.copy()
    env["BMD_RUNTIME_SIGNING_PASSWORD"] = secrets.token_hex(32)
    keystore = output / "ephemeral-runtime-test.p12"
    certificate_digests = []
    records = {}
    try:
        run(["keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(keystore),
             "-alias", "bmd-runtime", "-keyalg", "RSA", "-keysize", "3072", "-validity", "2",
             "-dname", "CN=BMD ephemeral runtime test only", "-storepass:env", "BMD_RUNTIME_SIGNING_PASSWORD",
             "-keypass:env", "BMD_RUNTIME_SIGNING_PASSWORD"], env)
        for kind, source in (("release", args.release_apk), ("harness", args.test_apk)):
            signed = output / f"{kind}-test-signed.apk"
            run([str(args.build_tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias", "bmd-runtime",
                 "--ks-pass", "env:BMD_RUNTIME_SIGNING_PASSWORD", "--key-pass", "env:BMD_RUNTIME_SIGNING_PASSWORD",
                 "--v4-signing-enabled", "false", "--out", str(signed), str(source)], env)
            before = payloads(source)
            if payloads(signed) != before:
                raise RuntimeError(f"Signing changed a non-signature {kind} APK payload")
            verification = run([str(args.build_tools / "apksigner"), "verify", "--print-certs", str(signed)])
            certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", verification)
            if not certificate:
                raise RuntimeError("Missing verified signer identity")
            certificate_digests.append(certificate.group(1))
            check_manifest(signed, args.build_tools, APP_ID if kind == "release" else TEST_ID,
                           None if kind == "release" else APP_ID)
            if kind == "release":
                run([str(args.build_tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(signed)])
            records[kind] = {"unsignedApkSha256": digest(source), "signedApkSha256": digest(signed),
                             "unchangedPayloadEntries": before}
        if len(set(certificate_digests)) != 1:
            raise RuntimeError("Release target and instrumentation have different signers")
        if digest(args.release_apk) != original_hash or digest(args.test_apk) != harness_hash:
            raise RuntimeError("Original APK changed during runtime-copy preparation")
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps({"purpose": "Ephemeral runtime test only; not production signing or distribution.",
            "applicationId": APP_ID, "debuggable": False, "sameTestCertificate": True,
            "allNonSignaturePayloadsUnchanged": True, "originalApksUnchanged": True,
            "harnessDexClosure": harness_closure,
            "certificateSha256": certificate_digests[0], **records}, indent=2) + "\n")
        print(json.dumps({"prepared": True, "evidence": str(args.evidence),
                          "releaseApkSha256": records["release"]["signedApkSha256"]}))
    except BaseException:
        shutil.rmtree(output, ignore_errors=True)
        raise
    finally:
        keystore.unlink(missing_ok=True)
        env.pop("BMD_RUNTIME_SIGNING_PASSWORD", None)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Release runtime preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
