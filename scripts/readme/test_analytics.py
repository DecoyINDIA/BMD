#!/usr/bin/env python3
"""Unit tests for BMD README analytics collection, SVG generation, and validation."""
from __future__ import annotations

import datetime as dt
import io
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import collect_stats  # noqa: E402
import generate_analytics  # noqa: E402
import validate_assets  # noqa: E402


class FakeGitHubClient(collect_stats.GitHubClient):
    """In-memory mock GitHub client supporting pagination Link headers and failures."""

    def __init__(
        self,
        responses: dict[str, tuple[Any, dict[str, str]]] | None = None,
        fail_with: Exception | None = None,
    ) -> None:
        super().__init__(token="test-token", max_retries=0)
        self.responses = responses or {}
        self.fail_with = fail_with
        self.calls: list[str] = []

    def get_json(self, url: str) -> tuple[Any, dict[str, str]]:
        self.calls.append(url)
        if self.fail_with is not None:
            raise self.fail_with
        if url not in self.responses:
            raise collect_stats.GitHubAPIError(f"Unexpected URL in mock: {url}")
        payload, headers = self.responses[url]
        self._record_rate_limit(headers)
        return payload, headers


def _sample_release(
    tag: str,
    published_at: str,
    assets: list[tuple[str, int, int]],
) -> dict[str, Any]:
    return {
        "tag_name": tag,
        "name": f"BMD {tag}",
        "published_at": published_at,
        "prerelease": False,
        "draft": False,
        "html_url": f"https://github.com/DecoyINDIA/BMD/releases/tag/{tag}",
        "assets": [
            {
                "name": name,
                "download_count": count,
                "size": size,
                "browser_download_url": f"https://github.com/DecoyINDIA/BMD/releases/download/{tag}/{name}",
            }
            for name, count, size in assets
        ],
    }


class TestAssetClassification(unittest.TestCase):
    def test_zero_double_counting_across_all_buckets(self) -> None:
        samples = [
            ("BMD-1.0.0-setup.exe", "downloadable", "Windows"),
            ("BMD-1.0.0-portable-arm64.zip", "downloadable", "Windows"),
            ("BMD-1.0.0-x64.zip", "downloadable", "Windows"),
            ("BMD-1.0.0-linux-x86_64.AppImage", "downloadable", "Linux"),
            ("BMD-1.0.0-linux-arm64.deb", "downloadable", "Linux"),
            ("BMD-1.0.0-macos-arm64-experimental.dmg", "downloadable", "macOS (Experimental)"),
            ("BMD-1.0.0-extension-chromium.zip", "downloadable", "Browser Extensions"),
            ("BMD-1.0.0-extension-firefox.xpi", "downloadable", "Browser Extensions"),
            ("BMD-1.0.0-chrome-web-store.zip", "downloadable", "Browser Extensions"),
            ("BMD-1.0.0-linux-x86_64.AppImage.zsync", "auxiliary", "Other / Auxiliary"),
            ("BMD-1.0.0-symbols-windows-x64.zip", "auxiliary", "Other / Auxiliary"),
            ("SHA256SUMS.txt", "auxiliary", "Other / Auxiliary"),
            ("SOURCE.txt", "auxiliary", "Other / Auxiliary"),
            ("LICENSE-flag-icons", "auxiliary", "Other / Auxiliary"),
            ("countries.txt", "auxiliary", "Other / Auxiliary"),
            ("flags.png", "auxiliary", "Other / Auxiliary"),
            ("ipcountry.bin.xz", "auxiliary", "Other / Auxiliary"),
        ]
        for name, expected_cat, expected_plat in samples:
            got = collect_stats.classify_asset(name)
            self.assertEqual(got["category"], expected_cat, name)
            self.assertEqual(got["platform"], expected_plat, name)


class TestCollectStats(unittest.TestCase):
    def test_pagination_and_baseline_honest_null_deltas(self) -> None:
        page1_url = "https://api.github.com/repos/DecoyINDIA/BMD/releases?per_page=100"
        page2_url = "https://api.github.com/repos/DecoyINDIA/BMD/releases?per_page=100&page=2"
        contrib_url = "https://api.github.com/repos/DecoyINDIA/BMD/contributors?per_page=100&anon=false"
        repo_url = "https://api.github.com/repos/DecoyINDIA/BMD"

        rel_v1 = _sample_release(
            "v1.0.0",
            "2026-11-19T16:50:34Z",
            [
                ("BMD-1.0.0-setup.exe", 10, 20_000_000),
                ("BMD-1.0.0-linux-x86_64.AppImage", 5, 24_000_000),
                ("BMD-1.0.0-linux-x86_64.AppImage.zsync", 4, 80_000),
                ("BMD-1.0.0-extension-firefox.xpi", 3, 270_000),
                ("SHA256SUMS.txt", 2, 3_000),
            ],
        )
        rel_v09 = _sample_release(
            "v0.9.0",
            "2026-10-01T10:00:00Z",
            [
                ("BMD-0.9.0-macos-arm64-experimental.dmg", 2, 18_000_000),
                ("BMD-0.9.0-symbols-windows-x64.zip", 1, 4_000_000),
            ],
        )

        client = FakeGitHubClient(
            responses={
                repo_url: (
                    {
                        "stargazers_count": 42,
                        "forks_count": 7,
                        "open_issues_count": 3,
                        "subscribers_count": 5,
                        "default_branch": "main",
                        "pushed_at": "2026-11-26T12:00:00Z",
                    },
                    {"X-RateLimit-Remaining": "4999", "X-RateLimit-Limit": "5000"},
                ),
                page1_url: (
                    [rel_v1],
                    {
                        "Link": f'<{page2_url}>; rel="next"',
                        "X-RateLimit-Remaining": "4998",
                    },
                ),
                page2_url: (
                    [rel_v09],
                    {"X-RateLimit-Remaining": "4997"},
                ),
                contrib_url: (
                    [{"login": "a"}, {"login": "b"}, {"login": "c"}],
                    {"X-RateLimit-Remaining": "4996"},
                ),
            }
        )

        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "downloads.json"
            t0 = dt.datetime(2026, 11, 26, 0, 0, tzinfo=dt.timezone.utc)
            data = collect_stats.collect_stats(output_path=out_path, client=client, now=t0)

            self.assertEqual(data["api_status"], "ok")
            self.assertEqual(data["tracking_started_at"], "2026-11-26T00:00:00Z")
            self.assertEqual(data["summary"]["release_count"], 2)
            # Product: 10 + 5 + 3 + 2 = 20; Auxiliary: 4 + 2 + 1 = 7; All: 27
            self.assertEqual(data["summary"]["total_product_downloads"], 20)
            self.assertEqual(data["summary"]["total_auxiliary_downloads"], 7)
            self.assertEqual(data["summary"]["total_all_assets"], 27)
            self.assertIsNone(data["summary"]["daily_delta"])
            self.assertIsNone(data["summary"]["weekly_delta"])
            self.assertIsNone(data["summary"]["monthly_delta"])
            self.assertEqual(data["repo"]["contributors_count"], 3)

    def test_counter_decrease_and_deleted_release_no_negative_spike(self) -> None:
        repo_url = "https://api.github.com/repos/DecoyINDIA/BMD"
        rel_url = "https://api.github.com/repos/DecoyINDIA/BMD/releases?per_page=100"
        contrib_url = "https://api.github.com/repos/DecoyINDIA/BMD/contributors?per_page=100&anon=false"

        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "downloads.json"

            # Day 0: v0.9.0 has 100 downloads, v1.0.0 has 20 downloads.
            c0 = FakeGitHubClient(
                responses={
                    repo_url: ({"stargazers_count": 1, "forks_count": 0, "open_issues_count": 0, "subscribers_count": 0}, {}),
                    rel_url: (
                        [
                            _sample_release("v1.0.0", "2026-11-19T00:00:00Z", [("BMD-1.0.0-setup.exe", 20, 1000)]),
                            _sample_release("v0.9.0", "2026-10-01T00:00:00Z", [("BMD-0.9.0-setup.exe", 100, 1000)]),
                        ],
                        {},
                    ),
                    contrib_url: ([{"login": "a"}], {}),
                }
            )
            t0 = dt.datetime(2026, 11, 20, 0, 0, tzinfo=dt.timezone.utc)
            collect_stats.collect_stats(output_path=out_path, client=c0, now=t0)

            # Day 1 (+24h): v0.9.0 deleted, v1.0.0 asset replaced/dropped from 20 -> 5, plus v1.1.0 gains 12.
            c1 = FakeGitHubClient(
                responses={
                    repo_url: ({"stargazers_count": 2, "forks_count": 0, "open_issues_count": 0, "subscribers_count": 0}, {}),
                    rel_url: (
                        [
                            _sample_release("v1.1.0", "2026-11-21T00:00:00Z", [("BMD-1.1.0-setup.exe", 12, 1000)]),
                            _sample_release("v1.0.0", "2026-11-19T00:00:00Z", [("BMD-1.0.0-setup.exe", 5, 1000)]),
                        ],
                        {},
                    ),
                    contrib_url: ([{"login": "a"}], {}),
                }
            )
            t1 = dt.datetime(2026, 11, 21, 0, 0, tzinfo=dt.timezone.utc)
            data = collect_stats.collect_stats(output_path=out_path, client=c1, now=t1)

            # Daily delta must be +12 (new v1.1.0), NOT negative!
            self.assertEqual(data["summary"]["daily_delta"], 12)
            self.assertIsNone(data["summary"]["weekly_delta"])
            self.assertEqual(data["tracking_started_at"], "2026-11-20T00:00:00Z")
            anomaly_types = {a["type"] for a in data["anomalies"]}
            self.assertIn("release_removed", anomaly_types)
            self.assertIn("counter_decreased", anomaly_types)

    def test_api_failure_preserves_cache_and_marks_fallback(self) -> None:
        repo_url = "https://api.github.com/repos/DecoyINDIA/BMD"
        rel_url = "https://api.github.com/repos/DecoyINDIA/BMD/releases?per_page=100"
        contrib_url = "https://api.github.com/repos/DecoyINDIA/BMD/contributors?per_page=100&anon=false"

        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "downloads.json"
            c_ok = FakeGitHubClient(
                responses={
                    repo_url: ({"stargazers_count": 9, "forks_count": 2, "open_issues_count": 1, "subscribers_count": 1}, {}),
                    rel_url: ([_sample_release("v1.0.0", "2026-11-19T00:00:00Z", [("BMD-1.0.0-setup.exe", 15, 1000)])], {}),
                    contrib_url: ([{"login": "a"}], {}),
                }
            )
            t0 = dt.datetime(2026, 11, 26, 0, 0, tzinfo=dt.timezone.utc)
            collect_stats.collect_stats(output_path=out_path, client=c_ok, now=t0)

            c_fail = FakeGitHubClient(fail_with=collect_stats.GitHubAPIError("HTTP 403 rate limit"))
            t1 = dt.datetime(2026, 11, 26, 6, 0, tzinfo=dt.timezone.utc)
            fallback = collect_stats.collect_stats(output_path=out_path, client=c_fail, now=t1)

            self.assertEqual(fallback["api_status"], "cached_fallback")
            self.assertEqual(fallback["last_successful_api_sync_at"], "2026-11-26T00:00:00Z")
            self.assertEqual(fallback["last_updated_at"], "2026-11-26T06:00:00Z")
            self.assertEqual(fallback["summary"]["total_product_downloads"], 15)


class TestSvgGenerationAndValidation(unittest.TestCase):
    def test_generate_and_validate_all_svgs(self) -> None:
        sample_data = {
            "repository": "DecoyINDIA/BMD",
            "tracking_started_at": "2026-11-26T00:00:00Z",
            "last_updated_at": "2026-11-26T06:00:00Z",
            "last_successful_api_sync_at": "2026-11-26T06:00:00Z",
            "api_status": "ok",
            "repo": {"stars": 12, "forks": 3, "open_issues": 4, "contributors_count": 3},
            "summary": {
                "release_count": 1,
                "total_product_downloads": 27,
                "total_auxiliary_downloads": 19,
                "total_all_assets": 46,
                "latest_release_tag": "v1.0.0",
                "latest_release_published_at": "2026-11-19T16:50:34Z",
                "latest_release_downloads": 27,
                "daily_delta": None,
                "weekly_delta": None,
                "monthly_delta": None,
            },
            "platforms": [
                {"name": "Windows", "downloads": 12, "category": "downloadable", "share_of_product_pct": 44.44, "share_of_all_assets_pct": 26.09},
                {"name": "Linux", "downloads": 8, "category": "downloadable", "share_of_product_pct": 29.63, "share_of_all_assets_pct": 17.39},
                {"name": "macOS (Experimental)", "downloads": 2, "category": "downloadable", "share_of_product_pct": 7.41, "share_of_all_assets_pct": 4.35},
                {"name": "Browser Extensions", "downloads": 5, "category": "downloadable", "share_of_product_pct": 18.52, "share_of_all_assets_pct": 10.87},
                {"name": "Other / Auxiliary", "downloads": 19, "category": "auxiliary", "share_of_product_pct": 0.0, "share_of_all_assets_pct": 41.30},
            ],
            "releases": [
                {
                    "tag_name": "v1.0.0",
                    "published_at": "2026-11-19T16:50:34Z",
                    "product_downloads": 27,
                    "all_assets_downloads": 46,
                    "assets": [
                        {"name": "BMD-1.0.0-portable.zip", "download_count": 3, "size": 24_664_916, "category": "downloadable", "platform": "Windows"},
                        {"name": "BMD-1.0.0-x64.zip", "download_count": 2, "size": 24_663_928, "category": "downloadable", "platform": "Windows"},
                    ],
                }
            ],
            "snapshots": [{"timestamp": "2026-11-26T06:00:00Z", "total_product_downloads": 27}],
        }

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            in_json = tmp_dir / "downloads.json"
            in_json.write_text(json.dumps(sample_data), encoding="utf-8")
            out_dir = tmp_dir / "assets" / "readme" / "analytics"
            written = generate_analytics.generate_all(
                input_path=in_json,
                out_dir=out_dir,
                readme_path=None,
            )

            self.assertEqual(len(written), 6)
            count, errors = validate_assets.validate_all(
                assets_dir=tmp_dir / "assets" / "readme",
                readme_path=tmp_dir / "README.md",
                analytics_only=True,
            )
            self.assertEqual(count, 6)
            self.assertEqual(errors, [])

            animated_svg = (out_dir / "telemetry-dashboard-light.svg").read_text(encoding="utf-8")
            static_svg = (out_dir / "telemetry-dashboard-static-light.svg").read_text(encoding="utf-8")
            self.assertIn('aria-labelledby="svg-title svg-desc"', animated_svg)
            self.assertIn('<title id="svg-title">', animated_svg)
            self.assertIn('<desc id="svg-desc">', animated_svg)
            self.assertIn("@keyframes barGrow", animated_svg)
            self.assertIn("@media (prefers-reduced-motion: reduce)", animated_svg)
            self.assertIn(".bar-grow", animated_svg)
            self.assertNotIn("<animate", animated_svg)
            self.assertNotIn("@keyframes", static_svg)
            self.assertIn('aria-labelledby="svg-title svg-desc"', static_svg)

    def test_validator_catches_script_and_external_url(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bad_svg = Path(tmp) / "bad.svg"
            bad_svg.write_text(
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">'
                "<style>@import url('https://example.com/font.css');</style>"
                "<script>alert(1)</script>"
                "</svg>",
                encoding="utf-8",
            )
            errs = validate_assets.validate_svg_file(bad_svg, max_bytes=80 * 1024)
            self.assertTrue(any("<script>" in e for e in errs))
            self.assertTrue(any("@import" in e for e in errs))

    def test_readme_marker_replacement_and_noop(self) -> None:
        sample_data = {
            "tracking_started_at": "2026-11-26T00:00:00Z",
            "last_updated_at": "2026-11-26T06:00:00Z",
            "last_successful_api_sync_at": "2026-11-26T06:00:00Z",
            "api_status": "ok",
            "repo": {"stars": 5, "forks": 1, "open_issues": 4, "contributors_count": 3},
            "summary": {
                "total_product_downloads": 27,
                "total_all_assets": 46,
                "latest_release_tag": "v1.0.0",
                "latest_release_published_at": "2026-11-19T16:50:34Z",
                "latest_release_downloads": 27,
            },
            "platforms": [
                {"name": "Windows", "downloads": 12, "category": "downloadable", "share_of_product_pct": 44.44, "share_of_all_assets_pct": 26.09},
                {"name": "Other / Auxiliary", "downloads": 19, "category": "auxiliary", "share_of_product_pct": 0.0, "share_of_all_assets_pct": 41.30},
            ],
        }
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)

            # 1. Missing README.md -> safe no-op
            missing_readme = tmp_dir / "MISSING.md"
            self.assertFalse(generate_analytics.update_readme_telemetry_block(sample_data, missing_readme))

            # 2. README without markers -> safe no-op and content untouched
            no_markers = tmp_dir / "NO_MARKERS.md"
            original_plain = "# Black Marlin Downloader\n\nPlain text.\n"
            no_markers.write_text(original_plain, encoding="utf-8")
            self.assertFalse(generate_analytics.update_readme_telemetry_block(sample_data, no_markers))
            self.assertEqual(no_markers.read_text(encoding="utf-8"), original_plain)

            # 3. README with markers -> updates strictly between markers, preserves surrounding prose
            with_markers = tmp_dir / "README.md"
            with_markers.write_text(
                "# Header\n\n<!-- BMD:ANALYTICS-TEXT:START -->\nstale\n<!-- BMD:ANALYTICS-TEXT:END -->\n\n# Footer\n",
                encoding="utf-8",
            )
            changed = generate_analytics.update_readme_telemetry_block(sample_data, with_markers)
            self.assertTrue(changed)
            updated = with_markers.read_text(encoding="utf-8")
            self.assertTrue(updated.startswith("# Header\n\n<!-- BMD:ANALYTICS-TEXT:START -->\n"))
            self.assertTrue(updated.endswith("\n<!-- BMD:ANALYTICS-TEXT:END -->\n\n# Footer\n"))
            self.assertNotIn("stale", updated)
            self.assertIn("**Product & Extension Downloads** | **27**", updated)
            self.assertIn("**All Release Assets Total**", updated)
            self.assertIn("`v1.0.0`", updated)
            self.assertIn("Windows | Product | 12", updated)
            self.assertIn("api_status`: `ok (live GitHub API)`", updated)

            # 4. Second call with identical data -> idempotent False
            self.assertFalse(generate_analytics.update_readme_telemetry_block(sample_data, with_markers))


if __name__ == "__main__":
    unittest.main()
