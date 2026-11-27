#!/usr/bin/env python3
"""Retry bounded dependency transport only; never run product gates through this wrapper."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
BACKOFF_SECONDS = (30, 60)

# These deny rules take precedence over a coincident transport error.
DENY_PATTERNS = (
    ("test/assertion failure", r"assertionerror|assertionfailederror|assertionfailed|comparisonfailure|org\.opentest4j|failures!!!|\b\d+ tests? completed, [1-9]\d* failed\b|\btests? failed\b|\bclassMethod FAILED\b|\b\w+Tests?\s*>[^\n]*\bFAILED\b"),
    ("compiler/source/R8 failure", r"compilation (?:failed|errors?)|could not compile|script compilation|unresolved reference|type mismatch|\bsyntaxerror\b|\bmissing class(?:es)?\b|(?:^|\n)\s*e:|error\[e\d+\]|ERROR:\s*R8"),
    ("lint failure", r"lint found [1-9]\d* errors?|\blinterror\b|\bError:[^\n]*\[[A-Z][A-Za-z0-9]+\]"),
    # Certificate failures by their diagnostics, never by a bare "PKIX", which is also part of
    # artifact names (org.bouncycastle:bcpkix-jdk18on).
    ("integrity/trust failure", r"(?:checksum|signature|digest|hash)[^\n]*(?:mismatch|invalid|does not match|failed|failure|error)|(?:mismatch|invalid|failed|failure|error)[^\n]*(?:checksum|signature|digest)|DOES NOT VERIFY|PKIX path (?:building|validation) failed|unable to find valid certification path|ValidatorException|SSLHandshakeException|certificate[^\n]*(?:invalid|untrusted|failed|expired|revoked)"),
    ("product task entered", r"> Task :[^\n ]*:(?:test|compile|lint|minify|assemble|package|bundle)[^\n ]*|Execution failed for task ':[^']*(?:test|compile|lint|minify|assemble|package|bundle)[^']*'"),
)
DEPENDENCY_FAILURE = re.compile(r"Could not (?:resolve|GET|HEAD|download|get resource)|Failed to fetch maven artifact", re.IGNORECASE)
TRANSPORT_FAILURE = re.compile(
    r"(?:HTTP(?:/\d(?:\.\d)?)?\s+|HTTP response code:\s*|status code\s+)(?:429|500|502|503|504)\b|"
    r"read timed out|connect(?:ion)? timed out|connection reset(?: by peer)?|SocketTimeoutException|ConnectTimeoutException",
    re.IGNORECASE,
)


# What a dependency is called is not a diagnostic: Gradle quotes the URLs it fetched and the
# group:artifact:version it resolved, and those can hold any word the rules look for (bcpkix,
# digest, signature...). The deny rules read the log with them taken out.
NAMES = re.compile(r"'[a-z][a-z0-9+.-]*://[^'\s]*'|(?<![\w.:-])[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+\b")


def classify(output: str) -> tuple[bool, str]:
    diagnostics = NAMES.sub("<name>", output)
    for reason, pattern in DENY_PATTERNS:
        if re.search(pattern, diagnostics, re.IGNORECASE):
            return False, reason
    if not DEPENDENCY_FAILURE.search(output):
        return False, "not a dependency-resolution failure"
    if not TRANSPORT_FAILURE.search(output):
        return False, "not an approved transient transport failure"
    return True, "dependency transport failure"


def gradle_wrapper(platform: str = sys.platform) -> Path:
    """The Gradle wrapper this system can start: Windows runs the batch file, since the POSIX
    script is not a program there (WinError 193)."""
    return ROOT / "android" / ("gradlew.bat" if platform == "win32" else "gradlew")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log-dir", type=Path, default=ROOT / "android/app/build/reports/dependency-resolution")
    args = parser.parse_args()
    args.log_dir.mkdir(parents=True, exist_ok=True)
    # Intentionally fixed: callers cannot wrap compile/test/lint/package commands.
    command = [str(gradle_wrapper()), "--no-daemon", "--no-parallel", "--max-workers=2",
               "--console=plain", "-PskipNativeBuild=true", ":resolveAndroidDependencies"]
    attempts = []
    for number in range(1, 4):
        path = args.log_dir / f"attempt-{number}.log"
        print(f"Dependency-only preflight attempt {number}/3", flush=True)
        timed_out = False
        with path.open("w") as log:
            try:
                result = subprocess.run(command, cwd=ROOT / "android", stdout=log,
                                        stderr=subprocess.STDOUT, timeout=600)
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
                timed_out = True
        output = path.read_text(errors="replace")
        print(output, end="" if output.endswith("\n") else "\n", flush=True)
        retryable, reason = classify(output) if code else (False, "resolved")
        if timed_out:
            retryable, reason = False, "dependency preflight exceeded its 10-minute deadline"
        attempts.append({"attempt": number, "exitCode": code, "retryableTransport": retryable,
                         "reason": reason, "log": path.name})
        (args.log_dir / "result.json").write_text(json.dumps({"resolved": code == 0,
            "task": ":resolveAndroidDependencies", "attempts": attempts}, indent=2) + "\n")
        if code == 0:
            return 0
        if not retryable or number == 3:
            print(f"Dependency preflight failed: {reason}; product gates were not retried.", file=sys.stderr)
            return code if code > 0 else 1
        delay = BACKOFF_SECONDS[number - 1]
        print(f"Retrying dependency transport after {delay} seconds; full attempt log preserved.", flush=True)
        time.sleep(delay)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
