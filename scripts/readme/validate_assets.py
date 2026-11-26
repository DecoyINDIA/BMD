#!/usr/bin/env python3
"""Validate generated README SVG assets and README.md asset references.

Checks:
1. Every SVG under ``assets/readme/**/*.svg`` is well-formed XML with an ``<svg>`` root.
2. Zero ``<script>`` elements and zero inline event handler attributes (``on*=``).
3. Zero external URLs (``http://`` / ``https://``) in ``<image>``, ``<style>`` (``@import``,
   ``url(...)``), ``href``, ``xlink:href``, or ``src`` attributes, and zero ``<foreignObject>``.
4. File size budgets: ``<= 80 KiB`` for ``assets/readme/analytics/*.svg``,
   ``<= 120 KiB`` for all other ``assets/readme/**/*.svg``.
5. Zero em dash (``\\u2014``) characters in any SVG under ``assets/readme/``.
6. When ``README.md`` exists, verifies all local relative image/link paths resolve on disk.
"""
from __future__ import annotations

import argparse
import re
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ASSETS_DIR = ROOT / "assets" / "readme"
DEFAULT_README = ROOT / "README.md"

ANALYTICS_BUDGET_BYTES = 80 * 1024
ARTWORK_BUDGET_BYTES = 120 * 1024

EXTERNAL_URL_RE = re.compile(r"https?://", re.IGNORECASE)
CSS_IMPORT_RE = re.compile(r"@import\b", re.IGNORECASE)
MD_LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
HTML_ATTR_RE = re.compile(r"""(?:src|srcset|href)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)


def _local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def validate_svg_file(path: Path, max_bytes: int) -> list[str]:
    """Return a list of validation errors for a single SVG file."""
    errors: list[str] = []
    if not path.exists():
        return [f"{path}: file does not exist"]

    size = path.stat().st_size
    if size == 0:
        errors.append(f"{path}: SVG file is empty")
    elif size > max_bytes:
        errors.append(f"{path}: size {size} bytes exceeds budget {max_bytes} bytes")

    try:
        raw = path.read_text(encoding="utf-8")
        root = ET.fromstring(raw)
    except (OSError, UnicodeDecodeError, ET.ParseError) as exc:
        errors.append(f"{path}: invalid XML ({exc})")
        return errors

    if "\u2014" in raw:
        errors.append(f"{path}: forbidden em dash (\\u2014) found in SVG")

    if _local_tag(root.tag) != "svg":
        errors.append(f"{path}: root element must be <svg>, got <{root.tag}>")

    if not root.attrib.get("viewBox") and not (root.attrib.get("width") and root.attrib.get("height")):
        errors.append(f"{path}: missing viewBox or width/height dimensions")

    for elem in root.iter():
        tag = _local_tag(elem.tag)
        if tag == "script":
            errors.append(f"{path}: forbidden <script> element found")
        if tag == "foreignobject":
            errors.append(f"{path}: forbidden <foreignObject> element found")

        for attr_name, attr_val in elem.attrib.items():
            attr_local = _local_tag(attr_name)
            if attr_local.startswith("on"):
                errors.append(f"{path}: forbidden inline event handler '{attr_local}' on <{tag}>")
            if attr_local in ("href", "src", "xlink:href") or attr_name.endswith("}href"):
                if EXTERNAL_URL_RE.search(attr_val):
                    errors.append(f"{path}: external URL '{attr_val}' in {attr_local} on <{tag}>")
                elif tag == "image":
                    errors.append(f"{path}: external/relative <image> href '{attr_val}' is blocked in GitHub <img> sandbox")

        if tag == "style" and elem.text:
            if CSS_IMPORT_RE.search(elem.text):
                errors.append(f"{path}: forbidden @import in <style>")
            if EXTERNAL_URL_RE.search(elem.text):
                errors.append(f"{path}: forbidden external URL in <style>")

    return errors


def _clean_md_target(raw: str) -> str:
    target = raw.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")].strip()
    else:
        # Strip optional markdown link title: path "title"
        parts = target.split(None, 1)
        if parts:
            target = parts[0]
    return target


def validate_readme_links(readme_path: Path, repo_root: Path = ROOT) -> list[str]:
    """Check that all local relative image/link paths in README.md exist on disk."""
    if not readme_path.exists():
        return []
    text = readme_path.read_text(encoding="utf-8")
    errors: list[str] = []

    candidates: list[str] = []
    for match in MD_LINK_RE.finditer(text):
        candidates.append(_clean_md_target(match.group(1)))
    for match in HTML_ATTR_RE.finditer(text):
        raw = match.group(1).strip()
        # Handle srcset comma-separated entries
        for item in raw.split(","):
            token = item.strip().split()[0] if item.strip() else ""
            if token:
                candidates.append(token)

    seen: set[str] = set()
    for target in candidates:
        if not target or target in seen:
            continue
        seen.add(target)
        low = target.lower()
        if low.startswith(("http://", "https://", "mailto:", "data:", "#")):
            continue
        path_only = urllib.parse.unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not path_only:
            continue
        resolved = (readme_path.parent / path_only).resolve()
        if not resolved.exists():
            # Also try relative to repo root if written with leading slash
            if path_only.startswith("/"):
                alt = (repo_root / path_only.lstrip("/")).resolve()
                if alt.exists():
                    continue
            errors.append(f"{readme_path}: broken local path '{target}'")

    return errors


def validate_all(
    assets_dir: Path = DEFAULT_ASSETS_DIR,
    readme_path: Path = DEFAULT_README,
    analytics_only: bool = False,
) -> tuple[int, list[str]]:
    """Validate SVGs and README links, returning ``(svg_count, errors)``."""
    errors: list[str] = []
    search_root = assets_dir / "analytics" if analytics_only else assets_dir
    svg_files = sorted(search_root.rglob("*.svg")) if search_root.exists() else []

    if not svg_files:
        errors.append(f"No SVG files found under {search_root}")

    for svg in svg_files:
        is_analytics = "analytics" in svg.relative_to(assets_dir).parts if assets_dir in svg.parents else False
        budget = ANALYTICS_BUDGET_BYTES if is_analytics else ARTWORK_BUDGET_BYTES
        errors.extend(validate_svg_file(svg, max_bytes=budget))

    if not analytics_only:
        errors.extend(validate_readme_links(readme_path))

    return len(svg_files), errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-dir", type=Path, default=DEFAULT_ASSETS_DIR)
    parser.add_argument("--readme", type=Path, default=DEFAULT_README)
    parser.add_argument("--analytics-only", action="store_true")
    args = parser.parse_args(argv)

    count, errors = validate_all(
        assets_dir=args.assets_dir,
        readme_path=args.readme,
        analytics_only=args.analytics_only,
    )
    if errors:
        for err in errors:
            print(f"FAIL: {err}", file=sys.stderr)
        return 1

    print(f"OK: validated {count} SVG file(s) under {args.assets_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
