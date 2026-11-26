#!/usr/bin/env python3
"""Validate generated README SVG & animated WebP/raster assets and README.md asset references.

Checks:
1. Every SVG under ``assets/readme/**/*.svg`` is well-formed XML with an ``<svg>`` root,
   valid ``viewBox``/dimensions, zero ``<script>`` / ``<foreignObject>`` / ``on*=`` handlers,
   zero external ``http(s)://`` URLs / ``@import`` / ``<image>`` tags, and zero em dashes (``\\u2014``).
2. SVG size budgets: ``<= 80 KiB`` for ``assets/readme/analytics/*.svg``,
   ``<= 120 KiB`` for all other ``assets/readme/**/*.svg``.
3. Raster asset validation (``.webp``, ``.png``, ``.gif`` under ``assets/readme/``):
   validates container headers and non-zero pixel dimensions, enforces GitHub Camo proxy
   ceiling (``< 5 MiB``, with ``<= 3.0 MiB`` for ``analytics/*.webp`` and ``<= 3.8 MiB`` for
   other ``*.webp``), and ``<= 350 KiB`` for ``.png``/``.gif``.
4. Light/dark theme parity: every ``*-light.<ext>`` or ``*-dark.<ext>`` asset must have its
   counterpart on disk, and paired raster assets must share identical pixel dimensions.
5. Static fallback guarantee: ``assets/readme/analytics/`` must contain
   ``telemetry-dashboard-static-light.svg`` and ``telemetry-dashboard-static-dark.svg``.
6. When ``README.md`` exists (and ``--analytics-only`` is not set), verifies all local relative
   image/link paths resolve on disk.
"""
from __future__ import annotations

import argparse
import re
import struct
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ASSETS_DIR = ROOT / "assets" / "readme"
DEFAULT_README = ROOT / "README.md"

ANALYTICS_SVG_BUDGET_BYTES = 80 * 1024
ARTWORK_SVG_BUDGET_BYTES = 120 * 1024
ANALYTICS_WEBP_BUDGET_BYTES = int(3.0 * 1024 * 1024)
ARTWORK_WEBP_BUDGET_BYTES = int(3.8 * 1024 * 1024)
STATIC_RASTER_BUDGET_BYTES = 350 * 1024
CAMO_HARD_CEILING_BYTES = 5 * 1024 * 1024

EXTERNAL_URL_RE = re.compile(r"https?://", re.IGNORECASE)
CSS_IMPORT_RE = re.compile(r"@import\b", re.IGNORECASE)
MD_LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
HTML_ATTR_RE = re.compile(r"""(?:src|srcset|href)\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
RASTER_EXTS = (".webp", ".png", ".gif")


def _local_tag(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def parse_raster_dimensions(path: Path) -> tuple[int, int]:
    """Parse ``(width, height)`` from WebP, PNG, or GIF headers without external packages."""
    ext = path.suffix.lower()
    data = path.read_bytes()
    if ext == ".png":
        if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            raise ValueError("invalid PNG header")
        w, h = struct.unpack(">II", data[16:24])
        if w <= 0 or h <= 0:
            raise ValueError(f"invalid PNG dimensions {w}x{h}")
        return int(w), int(h)

    if ext == ".gif":
        if len(data) < 10 or data[:6] not in (b"GIF87a", b"GIF89a"):
            raise ValueError("invalid GIF header")
        w, h = struct.unpack("<HH", data[6:10])
        if w <= 0 or h <= 0:
            raise ValueError(f"invalid GIF dimensions {w}x{h}")
        return int(w), int(h)

    if ext == ".webp":
        if len(data) < 30 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
            raise ValueError("invalid WebP RIFF header")
        chunk = data[12:16]
        if chunk == b"VP8 ":
            w = struct.unpack("<H", data[26:28])[0] & 0x3FFF
            h = struct.unpack("<H", data[28:30])[0] & 0x3FFF
        elif chunk == b"VP8L":
            b0, b1, b2, b3 = data[21:25]
            w = 1 + (((b1 & 0x3F) << 8) | b0)
            h = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))
        elif chunk == b"VP8X":
            w = 1 + int.from_bytes(data[24:27], "little")
            h = 1 + int.from_bytes(data[27:30], "little")
        else:
            raise ValueError(f"unsupported WebP chunk {chunk!r}")
        if w <= 0 or h <= 0:
            raise ValueError(f"invalid WebP dimensions {w}x{h}")
        return int(w), int(h)

    raise ValueError(f"unsupported raster extension {ext}")


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


def validate_raster_file(path: Path, max_bytes: int) -> tuple[tuple[int, int] | None, list[str]]:
    """Validate raster file header, dimensions, and size budget."""
    errors: list[str] = []
    if not path.exists():
        return None, [f"{path}: file does not exist"]

    size = path.stat().st_size
    if size == 0:
        errors.append(f"{path}: raster file is empty")
    if size >= CAMO_HARD_CEILING_BYTES:
        errors.append(f"{path}: size {size} bytes exceeds GitHub Camo 5 MiB limit")
    elif size > max_bytes:
        errors.append(f"{path}: size {size} bytes exceeds budget {max_bytes} bytes")

    dims: tuple[int, int] | None = None
    try:
        dims = parse_raster_dimensions(path)
    except (OSError, ValueError, struct.error) as exc:
        errors.append(f"{path}: invalid raster image ({exc})")

    return dims, errors


def validate_theme_parity(
    files: list[Path],
    raster_dims: dict[Path, tuple[int, int]],
) -> list[str]:
    """Verify every ``*-light.<ext>`` / ``*-dark.<ext>`` asset has its paired counterpart and matching size."""
    errors: list[str] = []
    file_set = {f.resolve() for f in files}
    checked_pairs: set[tuple[Path, Path]] = set()

    for path in files:
        stem = path.stem
        ext = path.suffix
        if stem.endswith("-light"):
            partner = path.with_name(stem[:-6] + "-dark" + ext)
            pair_key = (path.resolve(), partner.resolve())
        elif stem.endswith("-dark"):
            partner = path.with_name(stem[:-5] + "-light" + ext)
            pair_key = (partner.resolve(), path.resolve())
        else:
            continue

        if pair_key in checked_pairs:
            continue
        checked_pairs.add(pair_key)

        if partner.resolve() not in file_set and not partner.exists():
            errors.append(f"{path}: missing theme counterpart '{partner.name}'")
            continue

        d1 = raster_dims.get(path.resolve())
        d2 = raster_dims.get(partner.resolve())
        if d1 and d2 and d1 != d2:
            errors.append(
                f"{path.name} vs {partner.name}: light/dark dimension mismatch ({d1[0]}x{d1[1]} vs {d2[0]}x{d2[1]})"
            )

    return errors


def _clean_md_target(raw: str) -> str:
    target = raw.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")].strip()
    else:
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
    """Validate SVGs, raster assets, theme parity, static fallbacks, and README links."""
    errors: list[str] = []
    search_root = assets_dir / "analytics" if analytics_only else assets_dir
    svg_files = sorted(search_root.rglob("*.svg")) if search_root.exists() else []
    raster_files = (
        sorted(p for p in search_root.rglob("*") if p.is_file() and p.suffix.lower() in RASTER_EXTS)
        if search_root.exists()
        else []
    )

    if not svg_files and not raster_files:
        errors.append(f"No SVG or raster asset files found under {search_root}")

    for svg in svg_files:
        is_analytics = "analytics" in svg.relative_to(assets_dir).parts if assets_dir in svg.parents else False
        budget = ANALYTICS_SVG_BUDGET_BYTES if is_analytics else ARTWORK_SVG_BUDGET_BYTES
        errors.extend(validate_svg_file(svg, max_bytes=budget))

    raster_dims: dict[Path, tuple[int, int]] = {}
    for rast in raster_files:
        is_analytics = "analytics" in rast.relative_to(assets_dir).parts if assets_dir in rast.parents else False
        if rast.suffix.lower() == ".webp":
            budget = ANALYTICS_WEBP_BUDGET_BYTES if is_analytics else ARTWORK_WEBP_BUDGET_BYTES
        else:
            budget = STATIC_RASTER_BUDGET_BYTES
        dims, r_errs = validate_raster_file(rast, max_bytes=budget)
        if dims:
            raster_dims[rast.resolve()] = dims
        errors.extend(r_errs)

    errors.extend(validate_theme_parity(svg_files + raster_files, raster_dims))

    # Enforce static fallback SVGs in analytics/
    analytics_dir = assets_dir / "analytics"
    if analytics_dir.exists():
        for theme in ("light", "dark"):
            static_svg = analytics_dir / f"telemetry-dashboard-static-{theme}.svg"
            if not static_svg.is_file():
                errors.append(f"{analytics_dir}: missing static fallback '{static_svg.name}'")

    if not analytics_only:
        errors.extend(validate_readme_links(readme_path))

    return len(svg_files) + len(raster_files), errors


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

    print(f"OK: validated {count} asset file(s) under {args.assets_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
