#!/usr/bin/env python3
"""Collect live GitHub repository and release download analytics for DecoyINDIA/BMD.

Fetches official GitHub REST API telemetry (repository metadata, paginated releases,
and contributors count), transparently classifies every release asset into product
downloadables vs. auxiliary files with zero double-counting, and appends deduplicated
time-series snapshots to ``data/analytics/downloads.json``.

Usage:
    python scripts/readme/collect_stats.py
    python scripts/readme/collect_stats.py --output data/analytics/downloads.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPO = "DecoyINDIA/BMD"
DEFAULT_OUTPUT = ROOT / "data" / "analytics" / "downloads.json"
API_BASE = "https://api.github.com"

PLATFORM_ORDER = (
    "Windows",
    "Linux",
    "macOS (Experimental)",
    "Browser Extensions",
    "Other / Auxiliary",
)

PRODUCT_PLATFORMS = (
    "Windows",
    "Linux",
    "macOS (Experimental)",
    "Browser Extensions",
)

NEXT_LINK_RE = re.compile(r'<([^>]+)>;\s*rel="next"')

# Auxiliary release files that should not be conflated with end-user product downloads.
AUXILIARY_EXACT = {
    "countries.txt",
    "flags.png",
    "ipcountry.bin.xz",
    "sha256sums.txt",
    "source.txt",
}


def utc_now() -> dt.datetime:
    """Return the current UTC datetime truncated to whole seconds."""
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def iso_utc(value: dt.datetime) -> str:
    """Format a UTC datetime as ISO-8601 with trailing Z."""
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(value: str) -> dt.datetime:
    """Parse an ISO-8601 timestamp string into an aware UTC datetime."""
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    parsed = dt.datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def resolve_token() -> str:
    """Resolve GitHub auth token from environment or local ``gh auth token``."""
    token = (os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or "").strip()
    if token:
        return token
    try:
        proc = subprocess.run(
            ["gh", "auth", "token"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if proc.returncode == 0:
            candidate = proc.stdout.strip()
            if candidate:
                return candidate
    except (OSError, subprocess.SubprocessError):
        pass
    return ""


def _parse_int_header(headers: dict[str, str], key: str) -> int | None:
    """Case-insensitive integer header lookup."""
    lowered = {k.lower(): v for k, v in headers.items()}
    raw = lowered.get(key.lower())
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def _next_page_url(headers: dict[str, str]) -> str | None:
    """Extract the ``rel=\"next\"`` URL from an HTTP Link header."""
    lowered = {k.lower(): v for k, v in headers.items()}
    link = lowered.get("link", "")
    match = NEXT_LINK_RE.search(link)
    return match.group(1) if match else None


class GitHubAPIError(RuntimeError):
    """Raised when the GitHub REST API fails or exhausts rate limits."""


class GitHubClient:
    """Minimal stdlib GitHub REST API client with pagination and rate-limit checks."""

    def __init__(self, token: str = "", timeout: int = 20, max_retries: int = 3) -> None:
        self.token = token
        self.timeout = timeout
        self.max_retries = max_retries
        self.rate_limit: dict[str, Any] = {
            "remaining": None,
            "limit": None,
            "reset_at": None,
        }

    def _headers(self) -> dict[str, str]:
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "bmd-readme-analytics",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def get_json(self, url: str) -> tuple[Any, dict[str, str]]:
        """Perform a GET request returning parsed JSON and response headers."""
        for attempt in range(self.max_retries + 1):
            req = urllib.request.Request(url, headers=self._headers())
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    headers = dict(resp.headers.items())
                    self._record_rate_limit(headers)
                    payload = json.load(resp)
                    return payload, headers
            except urllib.error.HTTPError as exc:
                err_headers = dict(exc.headers.items()) if exc.headers else {}
                self._record_rate_limit(err_headers)
                remaining = _parse_int_header(err_headers, "X-RateLimit-Remaining")
                if exc.code in (403, 429) and remaining == 0:
                    reset_epoch = _parse_int_header(err_headers, "X-RateLimit-Reset")
                    reset_msg = (
                        iso_utc(dt.datetime.fromtimestamp(reset_epoch, dt.timezone.utc))
                        if reset_epoch
                        else "unknown"
                    )
                    raise GitHubAPIError(
                        f"GitHub API rate limit exhausted (HTTP {exc.code}, resets at {reset_msg})"
                    ) from exc

                retry_after = _parse_int_header(err_headers, "Retry-After")
                if exc.code in (403, 429) and retry_after is not None and retry_after <= 30 and attempt < self.max_retries:
                    time.sleep(retry_after)
                    continue

                if exc.code in (500, 502, 503, 504) and attempt < self.max_retries:
                    time.sleep(1.5 * (attempt + 1))
                    continue

                raise GitHubAPIError(f"GitHub API HTTP {exc.code} for {url}: {exc.reason}") from exc
            except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
                if attempt < self.max_retries:
                    time.sleep(1.5 * (attempt + 1))
                    continue
                raise GitHubAPIError(f"GitHub API request failed for {url}: {exc}") from exc

        raise GitHubAPIError(f"GitHub API request failed after retries for {url}")

    def _record_rate_limit(self, headers: dict[str, str]) -> None:
        remaining = _parse_int_header(headers, "X-RateLimit-Remaining")
        limit = _parse_int_header(headers, "X-RateLimit-Limit")
        reset_epoch = _parse_int_header(headers, "X-RateLimit-Reset")
        if remaining is not None:
            self.rate_limit["remaining"] = remaining
        if limit is not None:
            self.rate_limit["limit"] = limit
        if reset_epoch is not None:
            self.rate_limit["reset_at"] = iso_utc(
                dt.datetime.fromtimestamp(reset_epoch, dt.timezone.utc)
            )

    def get_paginated(self, path: str, params: dict[str, Any] | None = None) -> list[Any]:
        """Fetch all pages for a list endpoint using ``per_page=100`` and Link headers."""
        query = {"per_page": "100"}
        if params:
            query.update({k: str(v) for k, v in params.items()})
        url: str | None = f"{API_BASE}/{path.lstrip('/')}?{urllib.parse.urlencode(query)}"
        items: list[Any] = []
        while url:
            if self.rate_limit.get("remaining") == 0:
                raise GitHubAPIError("GitHub API rate limit remaining reached 0 during pagination")
            data, headers = self.get_json(url)
            if not isinstance(data, list):
                raise GitHubAPIError(f"Expected list response from {url}, got {type(data).__name__}")
            items.extend(data)
            url = _next_page_url(headers)
        return items


def classify_asset(name: str) -> dict[str, str]:
    """Classify a GitHub release asset transparently with zero double-counting.

    Returns a dictionary with:
    - ``category``: ``"downloadable"`` or ``"auxiliary"``
    - ``kind``: ``"installer_or_package"``, ``"browser_extension"``, or ``"auxiliary"``
    - ``platform``: one of ``PLATFORM_ORDER``
    """
    low = name.lower().strip()

    is_auxiliary = (
        low in AUXILIARY_EXACT
        or low.endswith((".zsync", ".sha256", ".sha512", ".sig", ".asc"))
        or low.startswith(("license-", "sha256sums", "source."))
        or "-symbols-" in low
        or "symbols-" in low
        or ".sbom" in low
    )
    if is_auxiliary:
        return {
            "category": "auxiliary",
            "kind": "auxiliary",
            "platform": "Other / Auxiliary",
        }

    if (
        low.endswith(".xpi")
        or "extension-" in low
        or "chrome-web-store" in low
        or "web-store" in low
        or "chromium" in low
        or "firefox" in low
    ):
        return {
            "category": "downloadable",
            "kind": "browser_extension",
            "platform": "Browser Extensions",
        }

    if low.endswith(".dmg") or "macos" in low or "darwin" in low or "-osx" in low:
        return {
            "category": "downloadable",
            "kind": "installer_or_package",
            "platform": "macOS (Experimental)",
        }

    if (
        low.endswith((".appimage", ".deb", ".rpm", ".tar.gz", ".tar.xz", ".tar.zst", ".flatpak", ".snap"))
        or "linux" in low
    ):
        return {
            "category": "downloadable",
            "kind": "installer_or_package",
            "platform": "Linux",
        }

    if (
        low.endswith((".exe", ".msi", ".msix"))
        or "windows" in low
        or "win32" in low
        or "win64" in low
        or "setup" in low
        or "portable" in low
        or low.endswith(("-x64.zip", "-x86.zip", "-arm64.zip"))
    ):
        return {
            "category": "downloadable",
            "kind": "installer_or_package",
            "platform": "Windows",
        }

    return {
        "category": "auxiliary",
        "kind": "auxiliary",
        "platform": "Other / Auxiliary",
    }


def _build_release_records(raw_releases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Transform GitHub release payloads into normalized release records."""
    records: list[dict[str, Any]] = []
    for rel in raw_releases:
        if rel.get("draft"):
            continue

        tag = str(rel.get("tag_name") or "").strip()
        if not tag:
            continue

        platform_totals = {p: 0 for p in PLATFORM_ORDER}
        product_downloads = 0
        auxiliary_downloads = 0
        all_assets_downloads = 0
        assets_out: list[dict[str, Any]] = []

        for raw_asset in rel.get("assets") or []:
            name = str(raw_asset.get("name") or "")
            count = max(0, int(raw_asset.get("download_count") or 0))
            size = max(0, int(raw_asset.get("size") or 0))
            info = classify_asset(name)

            all_assets_downloads += count
            platform_totals[info["platform"]] += count
            if info["category"] == "downloadable":
                product_downloads += count
            else:
                auxiliary_downloads += count

            assets_out.append(
                {
                    "name": name,
                    "download_count": count,
                    "size": size,
                    "browser_download_url": str(raw_asset.get("browser_download_url") or ""),
                    "category": info["category"],
                    "kind": info["kind"],
                    "platform": info["platform"],
                }
            )

        assets_out.sort(key=lambda a: (-a["download_count"], a["name"]))
        records.append(
            {
                "tag_name": tag,
                "name": str(rel.get("name") or tag),
                "published_at": str(rel.get("published_at") or rel.get("created_at") or ""),
                "prerelease": bool(rel.get("prerelease", False)),
                "html_url": str(rel.get("html_url") or ""),
                "product_downloads": product_downloads,
                "auxiliary_downloads": auxiliary_downloads,
                "all_assets_downloads": all_assets_downloads,
                "asset_count": len(assets_out),
                "platforms": platform_totals,
                "assets": assets_out,
            }
        )

    records.sort(key=lambda r: r.get("published_at") or "", reverse=True)
    return records


def _merge_snapshot(
    snapshots: list[dict[str, Any]],
    new_snap: dict[str, Any],
    dedup_seconds: int = 300,
    max_snapshots: int = 1460,
) -> list[dict[str, Any]]:
    """Append or deduplicate a snapshot and bound long-term snapshot growth."""
    ordered = sorted(snapshots, key=lambda s: s["timestamp"])
    if ordered:
        last = ordered[-1]
        last_dt = parse_iso(last["timestamp"])
        new_dt = parse_iso(new_snap["timestamp"])
        if abs((new_dt - last_dt).total_seconds()) < dedup_seconds:
            ordered[-1] = new_snap
        else:
            ordered.append(new_snap)
    else:
        ordered.append(new_snap)

    if len(ordered) <= max_snapshots:
        return ordered

    # Keep the most recent 360 snapshots intact and thin older ones to 1 per UTC day.
    cutoff_idx = max(0, len(ordered) - 360)
    older = ordered[:cutoff_idx]
    recent = ordered[cutoff_idx:]
    daily_buckets: dict[str, dict[str, Any]] = {}
    for snap in older:
        day_key = snap["timestamp"][:10]
        daily_buckets[day_key] = snap
    thinned = sorted(daily_buckets.values(), key=lambda s: s["timestamp"])
    combined = thinned + recent
    return combined[-max_snapshots:]


def _monotonic_step_delta(prev_snap: dict[str, Any], curr_snap: dict[str, Any]) -> int:
    """Compute non-negative product download growth between two snapshots.

    Handles deleted releases (ignored rather than subtracted) and asset replacements
    or GitHub counter decreases (clamped at 0 per release) so historical anomalies
    never generate misleading negative spikes.
    """
    prev_map = prev_snap.get("per_release_product")
    curr_map = curr_snap.get("per_release_product")
    if isinstance(prev_map, dict) and isinstance(curr_map, dict):
        delta = 0
        for tag, curr_val in curr_map.items():
            prev_val = int(prev_map.get(tag, 0))
            diff = int(curr_val) - prev_val
            if diff > 0:
                delta += diff
        return delta

    raw_diff = int(curr_snap.get("total_product_downloads", 0)) - int(
        prev_snap.get("total_product_downloads", 0)
    )
    return max(0, raw_diff)


def compute_window_deltas(
    snapshots: list[dict[str, Any]],
    now: dt.datetime,
) -> tuple[dict[str, int | None], dict[str, str]]:
    """Compute honest 24h, 7d, and 30d deltas, returning None when history is shorter."""
    ordered = sorted(snapshots, key=lambda s: s["timestamp"])
    windows = (
        ("daily_delta", dt.timedelta(days=1), "24h"),
        ("weekly_delta", dt.timedelta(days=7), "7d"),
        ("monthly_delta", dt.timedelta(days=30), "30d"),
    )

    if len(ordered) < 2:
        note = "Baseline only (fewer than 2 snapshots recorded)"
        return (
            {"daily_delta": None, "weekly_delta": None, "monthly_delta": None},
            {"daily_delta": note, "weekly_delta": note, "monthly_delta": note},
        )

    first_dt = parse_iso(ordered[0]["timestamp"])
    latest_dt = parse_iso(ordered[-1]["timestamp"])
    span = latest_dt - first_dt

    deltas: dict[str, int | None] = {}
    notes: dict[str, str] = {}

    for field, duration, label in windows:
        if span < duration:
            deltas[field] = None
            hours = int(span.total_seconds() // 3600)
            notes[field] = (
                f"Insufficient history ({hours}h recorded; requires >= {label} since tracking started)"
            )
            continue

        target_dt = latest_dt - duration
        start_idx = 0
        for idx, snap in enumerate(ordered):
            if parse_iso(snap["timestamp"]) <= target_dt:
                start_idx = idx
            else:
                break

        total_gain = 0
        for idx in range(start_idx, len(ordered) - 1):
            total_gain += _monotonic_step_delta(ordered[idx], ordered[idx + 1])

        deltas[field] = total_gain
        since_ts = ordered[start_idx]["timestamp"]
        notes[field] = f"Computed from {since_ts} to {ordered[-1]['timestamp']}"

    return deltas, notes


def detect_anomalies(
    prev_snapshot: dict[str, Any] | None,
    curr_releases: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Record transparent notices when a release disappears or a counter decreases."""
    if not prev_snapshot:
        return []
    prev_rel = prev_snapshot.get("per_release_product") or {}
    curr_rel = {r["tag_name"]: r["product_downloads"] for r in curr_releases}
    anomalies: list[dict[str, Any]] = []
    for tag, old_count in prev_rel.items():
        if tag not in curr_rel:
            anomalies.append(
                {
                    "type": "release_removed",
                    "tag_name": tag,
                    "previous_product_downloads": int(old_count),
                }
            )
        elif curr_rel[tag] < int(old_count):
            anomalies.append(
                {
                    "type": "counter_decreased",
                    "tag_name": tag,
                    "previous_product_downloads": int(old_count),
                    "current_product_downloads": int(curr_rel[tag]),
                }
            )
    return anomalies


def load_existing(path: Path) -> dict[str, Any] | None:
    """Load an existing ``downloads.json`` file if present and valid JSON."""
    if not path.exists():
        return None
    try:
        with path.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict):
            return data
    except (OSError, json.JSONDecodeError):
        return None
    return None


def collect_stats(
    repo: str = DEFAULT_REPO,
    output_path: Path = DEFAULT_OUTPUT,
    client: GitHubClient | None = None,
    now: dt.datetime | None = None,
) -> dict[str, Any]:
    """Collect stats from GitHub API and write ``output_path``, preserving cache on failure."""
    now_dt = (now or utc_now()).astimezone(dt.timezone.utc).replace(microsecond=0)
    now_iso = iso_utc(now_dt)
    existing = load_existing(output_path)

    if client is None:
        client = GitHubClient(token=resolve_token())

    try:
        repo_data, _ = client.get_json(f"{API_BASE}/repos/{repo}")
        raw_releases = client.get_paginated(f"repos/{repo}/releases")
        contributors = client.get_paginated(f"repos/{repo}/contributors", params={"anon": "false"})
    except GitHubAPIError as exc:
        if existing is None:
            raise
        preserved = dict(existing)
        preserved["last_updated_at"] = now_iso
        preserved["api_status"] = "cached_fallback"
        preserved["api_error"] = str(exc)
        preserved["rate_limit"] = client.rate_limit
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("w", encoding="utf-8") as fh:
            json.dump(preserved, fh, indent=2)
            fh.write("\n")
        return preserved

    releases = _build_release_records(raw_releases)
    platform_totals = {p: 0 for p in PLATFORM_ORDER}
    total_product_downloads = 0
    total_auxiliary_downloads = 0
    total_all_assets = 0

    for rel in releases:
        total_product_downloads += rel["product_downloads"]
        total_auxiliary_downloads += rel["auxiliary_downloads"]
        total_all_assets += rel["all_assets_downloads"]
        for p in PLATFORM_ORDER:
            platform_totals[p] += rel["platforms"][p]

    latest_release = releases[0] if releases else None
    most_downloaded_release = (
        max(releases, key=lambda r: (r["product_downloads"], r["published_at"]))
        if releases
        else None
    )

    product_platform_counts = {p: platform_totals[p] for p in PRODUCT_PLATFORMS}
    top_platform_name = max(
        PRODUCT_PLATFORMS,
        key=lambda p: (product_platform_counts[p], -PRODUCT_PLATFORMS.index(p)),
    )

    previous_snapshots = list(existing.get("snapshots") or []) if existing else []
    prev_latest = previous_snapshots[-1] if previous_snapshots else None
    anomalies = detect_anomalies(prev_latest, releases)

    new_snapshot = {
        "timestamp": now_iso,
        "total_product_downloads": total_product_downloads,
        "total_all_assets": total_all_assets,
        "stars": int(repo_data.get("stargazers_count") or 0),
        "forks": int(repo_data.get("forks_count") or 0),
        "open_issues": int(repo_data.get("open_issues_count") or 0),
        "release_count": len(releases),
        "per_release_product": {r["tag_name"]: r["product_downloads"] for r in releases},
        "per_platform_product": product_platform_counts,
    }
    snapshots = _merge_snapshot(previous_snapshots, new_snapshot)

    tracking_started_at = (
        str(existing.get("tracking_started_at"))
        if existing and existing.get("tracking_started_at")
        else snapshots[0]["timestamp"]
    )
    deltas, delta_notes = compute_window_deltas(snapshots, now_dt)

    # Preserve previously recorded anomaly log entries (bounded to last 50).
    prior_anomalies = list(existing.get("anomalies") or []) if existing else []
    for item in anomalies:
        prior_anomalies.append({"detected_at": now_iso, **item})

    payload: dict[str, Any] = {
        "schema_version": 1,
        "repository": repo,
        "tracking_started_at": tracking_started_at,
        "last_updated_at": now_iso,
        "last_successful_api_sync_at": now_iso,
        "api_status": "ok",
        "api_error": None,
        "rate_limit": client.rate_limit,
        "classification_policy": {
            "product_downloads": (
                "Installers, portable archives, Linux packages (.AppImage, .deb, .rpm, .tar.gz), "
                "experimental macOS .dmg, and browser extensions (.zip, .xpi). Zero double-counting."
            ),
            "auxiliary_assets": (
                "Delta metadata (.zsync), debug symbols (-symbols-*), checksums (SHA256SUMS.txt), "
                "licenses, source pointers, and geo data (countries.txt, flags.png, ipcountry.bin.xz)."
            ),
        },
        "repo": {
            "stars": int(repo_data.get("stargazers_count") or 0),
            "forks": int(repo_data.get("forks_count") or 0),
            "open_issues": int(repo_data.get("open_issues_count") or 0),
            "watchers": int(repo_data.get("subscribers_count") or 0),
            "contributors_count": len(contributors),
            "default_branch": str(repo_data.get("default_branch") or "main"),
            "pushed_at": str(repo_data.get("pushed_at") or ""),
        },
        "summary": {
            "release_count": len(releases),
            "total_product_downloads": total_product_downloads,
            "total_auxiliary_downloads": total_auxiliary_downloads,
            "total_all_assets": total_all_assets,
            "latest_release_tag": latest_release["tag_name"] if latest_release else None,
            "latest_release_published_at": latest_release["published_at"] if latest_release else None,
            "latest_release_downloads": latest_release["product_downloads"] if latest_release else 0,
            "latest_release_all_assets": latest_release["all_assets_downloads"] if latest_release else 0,
            "most_downloaded_release": (
                {
                    "tag_name": most_downloaded_release["tag_name"],
                    "product_downloads": most_downloaded_release["product_downloads"],
                    "all_assets_downloads": most_downloaded_release["all_assets_downloads"],
                }
                if most_downloaded_release
                else None
            ),
            "most_downloaded_platform": (
                {
                    "name": top_platform_name,
                    "downloads": product_platform_counts[top_platform_name],
                }
                if total_product_downloads > 0
                else None
            ),
            "daily_delta": deltas["daily_delta"],
            "weekly_delta": deltas["weekly_delta"],
            "monthly_delta": deltas["monthly_delta"],
            "delta_notes": delta_notes,
        },
        "platforms": [
            {
                "name": name,
                "downloads": platform_totals[name],
                "category": "auxiliary" if name == "Other / Auxiliary" else "downloadable",
                "share_of_product_pct": (
                    round((platform_totals[name] / total_product_downloads) * 100.0, 2)
                    if name != "Other / Auxiliary" and total_product_downloads > 0
                    else 0.0
                ),
                "share_of_all_assets_pct": (
                    round((platform_totals[name] / total_all_assets) * 100.0, 2)
                    if total_all_assets > 0
                    else 0.0
                ),
            }
            for name in PLATFORM_ORDER
        ],
        "releases": releases,
        "snapshots": snapshots,
        "anomalies": prior_anomalies[-50:],
    }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)
        fh.write("\n")
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=DEFAULT_REPO, help="GitHub owner/repo")
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help="Destination JSON path",
    )
    args = parser.parse_args(argv)

    try:
        result = collect_stats(repo=args.repo, output_path=args.output)
    except GitHubAPIError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    summary = result["summary"]
    print(
        f"[{result['api_status']}] {result['repository']}: "
        f"product={summary['total_product_downloads']} "
        f"all_assets={summary['total_all_assets']} "
        f"releases={summary['release_count']} "
        f"snapshots={len(result['snapshots'])} "
        f"-> {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
