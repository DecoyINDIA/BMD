#!/usr/bin/env python3
"""Generate BMD Waybill-styled SVG analytics dashboards from ``data/analytics/downloads.json``.

Produces GitHub-compatible animated and static SVG assets in ``assets/readme/analytics/``:
  - ``telemetry-dashboard-light.svg`` & ``telemetry-dashboard-dark.svg``
  - ``telemetry-dashboard-static-light.svg`` & ``telemetry-dashboard-static-dark.svg``
  - ``platform-breakdown-light.svg`` & ``platform-breakdown-dark.svg``

Strictly adheres to BMD's ``DESIGN.md`` ("The Waybill on the Parcel"):
  - Blue Board (``#1f63d6``) / Navy Depot (``#0c1a33``) backdrop with Thermal Paper sheets
  - Green-by-Law: Dispatch Green (``#57cc5a``) and Stamp Ink (``#1b7a26`` / ``#6bdb6e``)
    used exclusively for download totals, delivery progress, and positive growth
  - Pure SVG + SMIL + inline CSS ``@keyframes`` (zero ``<script>``, zero external URLs)
"""
from __future__ import annotations

import argparse
import html
import json
import math
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "data" / "analytics" / "downloads.json"
DEFAULT_OUT_DIR = ROOT / "assets" / "readme" / "analytics"
DEFAULT_README = ROOT / "README.md"
ANALYTICS_START_MARKER = "<!-- BMD:ANALYTICS-TEXT:START -->"
ANALYTICS_END_MARKER = "<!-- BMD:ANALYTICS-TEXT:END -->"

FONT_DISPLAY = "'Space Grotesk', 'Segoe UI', system-ui, sans-serif"
FONT_BODY = "'IBM Plex Sans', 'Segoe UI', system-ui, sans-serif"
FONT_MONO = "'IBM Plex Mono', 'Cascadia Mono', Consolas, monospace"

MILESTONES = (100, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000)

THEMES: dict[str, dict[str, str]] = {
    "light": {
        "name": "light",
        "board": "#1f63d6",
        "board_deep": "#1854bb",
        "board_edge": "#3b78e0",
        "on_board": "#ffffff",
        "on_board_2": "#e6eefd",
        "paper": "#fcfcfb",
        "paper_2": "#f0f3f7",
        "ink": "#111318",
        "ink_2": "#434956",
        "ink_3": "#5f6676",
        "line": "#dde1e8",
        "go": "#57cc5a",
        "go_ink": "#08200b",
        "stamp": "#1b7a26",
        "link": "#0d58c9",
        "band_bg": "#111318",
        "band_fg": "#fcfcfb",
        "band_sub": "#c3cad6",
        "shadow": "#091f4d",
        "bar_muted": "#1f63d6",
        "bar_alt": "#434956",
        "bar_soft": "#5f6676",
        "grid_alpha": "0.24",
        "shadow_alpha": "0.34",
    },
    "dark": {
        "name": "dark",
        "board": "#0c1a33",
        "board_deep": "#081226",
        "board_edge": "#1c2e52",
        "on_board": "#f2f5fb",
        "on_board_2": "#c3cde0",
        "paper": "#11151d",
        "paper_2": "#192030",
        "ink": "#eef1f6",
        "ink_2": "#c3cad6",
        "ink_3": "#98a1b1",
        "line": "#2a3242",
        "go": "#57cc5a",
        "go_ink": "#08200b",
        "stamp": "#6bdb6e",
        "link": "#86b6ff",
        "band_bg": "#192030",
        "band_fg": "#eef1f6",
        "band_sub": "#98a1b1",
        "shadow": "#000000",
        "bar_muted": "#86b6ff",
        "bar_alt": "#c3cad6",
        "bar_soft": "#6f7a8f",
        "grid_alpha": "0.42",
        "shadow_alpha": "0.55",
    },
}


def esc(value: Any) -> str:
    """XML/HTML escape helper."""
    return html.escape(str(value), quote=True)


def fmt_num(value: int | float | None) -> str:
    """Format an integer with thousands separators."""
    if value is None:
        return "N/A"
    return f"{int(value):,}"


def plural_label(count: int, singular: str, plural: str | None = None) -> str:
    """Return singular or plural unit label matching count."""
    return singular if count == 1 else (plural or f"{singular}s")


def fmt_bytes(size: int) -> str:
    """Format byte size cleanly."""
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MiB"
    if size >= 1024:
        return f"{size / 1024:.0f} KiB"
    return f"{size} B"


def fmt_timestamp(iso_str: str | None) -> str:
    """Render an ISO UTC timestamp compactly as ``YYYY-MM-DD HH:MM UTC``."""
    if not iso_str:
        return "Pending"
    if "T" in iso_str and len(iso_str) >= 16:
        return f"{iso_str[:10]} {iso_str[11:16]} UTC"
    return iso_str


def next_milestone(total: int) -> int:
    """Return the next milestone strictly above ``total`` (or next 500k step)."""
    for m in MILESTONES:
        if total < m:
            return m
    step = 500_000
    return ((total // step) + 1) * step


def barcode_bars(seed_text: str, x_start: float, y: float, height: float, target_width: float) -> str:
    """Generate deterministic Code-128-style vector barcode bars."""
    acc = 2166136261
    for ch in seed_text:
        acc ^= ord(ch)
        acc = (acc * 16777619) & 0xFFFFFFFF

    bars: list[str] = []
    x = x_start
    # Start guard
    for w in (2.0, 1.5, 2.5):
        bars.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{height:.1f}"/>')
        x += w + 2.0

    idx = 0
    while x < x_start + target_width - 10:
        acc = (acc * 1664525 + 1013904223 + idx * 17) & 0xFFFFFFFF
        bar_w = 1.4 + ((acc >> 3) % 3) * 1.1
        gap_w = 1.4 + ((acc >> 7) % 3) * 1.1
        if x + bar_w > x_start + target_width - 8:
            break
        bars.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_w:.1f}" height="{height:.1f}"/>')
        x += bar_w + gap_w
        idx += 1

    # End guard
    for w in (2.5, 1.5):
        if x + w <= x_start + target_width:
            bars.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{height:.1f}"/>')
            x += w + 2.0
    return "".join(bars)


def _common_style(t: dict[str, str], animated: bool) -> str:
    anim_css = ""
    if animated:
        anim_css = """
    @keyframes pulseDot {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.45; transform: scale(0.84); }
    }
    @keyframes scanSweep {
      0% { transform: translateX(0px); opacity: 0.25; }
      50% { opacity: 0.9; }
      100% { transform: translateX(350px); opacity: 0.25; }
    }
    @keyframes barGrow {
      0% { transform: scaleX(0.01); }
      100% { transform: scaleX(1); }
    }
    .pulse-dot { transform-box: fill-box; transform-origin: center; animation: pulseDot 2.8s ease-in-out infinite; }
    .scan-tick { animation: scanSweep 4.2s ease-in-out infinite alternate; }
    .bar-grow { transform-box: fill-box; transform-origin: left center; animation: barGrow 0.85s cubic-bezier(0.16, 1, 0.3, 1) backwards; }
    @media (prefers-reduced-motion: reduce) {
      .pulse-dot, .scan-tick, .bar-grow { animation: none !important; transform: none !important; }
    }"""

    return f"""<style>
    .t-disp {{ font-family: {FONT_DISPLAY}; font-weight: 800; font-variant-numeric: tabular-nums; }}
    .t-band {{ font-family: {FONT_DISPLAY}; font-weight: 800; letter-spacing: 0.03em; }}
    .t-lbl {{ font-family: {FONT_DISPLAY}; font-weight: 700; font-size: 11px; letter-spacing: 0.06em; text-transform: uppercase; fill: {t['ink_3']}; }}
    .t-val {{ font-family: {FONT_DISPLAY}; font-weight: 800; font-variant-numeric: tabular-nums; fill: {t['ink']}; }}
    .t-body {{ font-family: {FONT_BODY}; font-weight: 400; font-variant-numeric: tabular-nums; fill: {t['ink_2']}; }}
    .t-strong {{ font-family: {FONT_BODY}; font-weight: 600; font-variant-numeric: tabular-nums; fill: {t['ink']}; }}
    .t-mono {{ font-family: {FONT_MONO}; font-variant-numeric: tabular-nums; }}
    .t-stamp {{ font-family: {FONT_DISPLAY}; font-weight: 800; letter-spacing: 0.08em; }}
    {anim_css}
  </style>"""


def _board_backdrop(w: int, h: int, t: dict[str, str]) -> str:
    grid_lines: list[str] = []
    for gx in range(40, w, 40):
        grid_lines.append(f'<line x1="{gx}" y1="0" x2="{gx}" y2="{h}"/>')
    for gy in range(40, h, 40):
        grid_lines.append(f'<line x1="0" y1="{gy}" x2="{w}" y2="{gy}"/>')
    return (
        f'<rect width="{w}" height="{h}" fill="{t["board"]}"/>'
        f'<rect width="{w}" height="{h}" fill="url(#boardGrad)" opacity="0.55"/>'
        f'<g stroke="{t["board_edge"]}" stroke-width="1" opacity="{t["grid_alpha"]}">'
        + "".join(grid_lines)
        + "</g>"
    )


def _waybill_shell(
    w: int,
    h: int,
    t: dict[str, str],
    band_left: str,
    band_right: str,
    band_x_offset: int = 22,
) -> str:
    sx, sy, sw, sh = 24, 22, w - 48, h - 44
    return (
        f'<rect x="{sx + 3}" y="{sy + 7}" width="{sw}" height="{sh}" rx="12" '
        f'fill="{t["shadow"]}" opacity="{t["shadow_alpha"]}"/>'
        f'<rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="12" '
        f'fill="{t["paper"]}" stroke="{t["line"]}" stroke-width="1"/>'
        f'<path d="M{sx},{sy + 12} a12,12 0 0 1 12,-12 h{sw - 24} a12,12 0 0 1 12,12 '
        f'v34 h-{sw} z" fill="{t["band_bg"]}"/>'
        f'<line x1="{sx}" y1="{sy + 46}" x2="{sx + sw}" y2="{sy + 46}" '
        f'stroke="{t["line"]}" stroke-width="1.5"/>'
        f'<text x="{sx + band_x_offset}" y="{sy + 29}" class="t-band" font-size="14" fill="{t["band_fg"]}">{esc(band_left)}</text>'
        f'<text x="{sx + sw - 22}" y="{sy + 28}" class="t-mono" font-size="11.5" '
        f'text-anchor="end" fill="{t["band_sub"]}">{esc(band_right)}</text>'
    )


def _animated_bar(
    x: float,
    y: float,
    width: float,
    height: float,
    rx: float,
    fill: str,
    animated: bool,
    delay_s: float = 0.0,
) -> str:
    if width <= 0:
        return ""
    if not animated:
        return f'<rect x="{x:.1f}" y="{y:.1f}" width="{width:.1f}" height="{height:.1f}" rx="{rx}" fill="{fill}"/>'
    delay_attr = f' style="animation-delay: {delay_s:.2f}s;"' if delay_s > 0 else ""
    return (
        f'<rect x="{x:.1f}" y="{y:.1f}" width="{width:.1f}" height="{height:.1f}" rx="{rx}" '
        f'fill="{fill}" class="bar-grow"{delay_attr}/>'
    )


def _format_delta_line(summary: dict[str, Any]) -> tuple[str, bool]:
    daily = summary.get("daily_delta")
    weekly = summary.get("weekly_delta")
    if daily is not None and daily > 0:
        wk = f" · +{fmt_num(weekly)} / 7d" if weekly is not None else ""
        return (f"+{fmt_num(daily)} / 24h{wk}", True)
    if daily == 0:
        wk = f" · +{fmt_num(weekly)} / 7d" if weekly is not None else ""
        return (f"0 / 24h{wk}", False)
    return ("Baseline active (< 24h sample window)", False)


def render_telemetry_dashboard(data: dict[str, Any], theme_name: str, animated: bool) -> str:
    """Render the main telemetry dashboard SVG (animated or static)."""
    t = THEMES[theme_name]
    w, h = 1120, 478
    summary = data.get("summary") or {}
    repo = data.get("repo") or {}
    platforms = data.get("platforms") or []
    snapshots = data.get("snapshots") or []

    total_product = int(summary.get("total_product_downloads") or 0)
    total_all = int(summary.get("total_all_assets") or 0)
    total_aux = int(summary.get("total_auxiliary_downloads") or 0)
    latest_tag = summary.get("latest_release_tag") or "—"
    latest_dl = int(summary.get("latest_release_downloads") or 0)
    latest_pub = (summary.get("latest_release_published_at") or "")[:10] or "—"
    release_count = int(summary.get("release_count") or 0)
    stars = int(repo.get("stars") or 0)
    forks = int(repo.get("forks") or 0)
    issues = int(repo.get("open_issues") or 0)
    contributors = int(repo.get("contributors_count") or 0)

    api_status = data.get("api_status") or "ok"
    synced_str = fmt_timestamp(data.get("last_successful_api_sync_at") or data.get("last_updated_at"))
    started_str = (data.get("tracking_started_at") or "")[:10] or "—"
    status_label = "API VERIFIED" if api_status == "ok" else "CACHED FALLBACK"

    milestone = next_milestone(total_product)
    milestone_pct = min(100.0, (total_product / milestone) * 100.0) if milestone > 0 else 0.0
    delta_text, is_positive_delta = _format_delta_line(summary)

    band_right = f"TRACKING SINCE {started_str}  ·  SYNCED {synced_str}  ·  {status_label}"
    svg_title = f"Black Marlin Downloader Release Telemetry Dashboard ({theme_name.title()} Theme)"
    svg_desc = (
        f"Live GitHub telemetry for DecoyINDIA/BMD: {fmt_num(total_product)} product and extension downloads, "
        f"{fmt_num(total_all)} total release assets, latest release {latest_tag} ({fmt_num(latest_dl)} downloads), "
        f"{fmt_num(stars)} stars, {fmt_num(forks)} forks, and {fmt_num(contributors)} contributors."
    )
    parts: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" role="img" aria-labelledby="svg-title svg-desc">',
        f'<title id="svg-title">{esc(svg_title)}</title>',
        f'<desc id="svg-desc">{esc(svg_desc)}</desc>',
        "<defs>",
        f'<linearGradient id="boardGrad" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0%" stop-color="{t["board"]}"/>'
        f'<stop offset="100%" stop-color="{t["board_deep"]}"/>'
        f"</linearGradient>",
        "</defs>",
        _common_style(t, animated),
        _board_backdrop(w, h, t),
        _waybill_shell(w, h, t, "BMD · DISPATCH TELEMETRY SHEET", band_right, band_x_offset=32),
    ]

    # Live status pulse indicator in the header band
    dot_class = ' class="pulse-dot"' if animated else ""
    dot_fill = t["go"] if api_status == "ok" else t["link"]
    parts.append(f'<circle cx="42" cy="45" r="4.5" fill="{dot_fill}"{dot_class}/>')

    # Row 1: 4-column metric fields (x=46..1074, y=68..184)
    col_x = (46, 336, 566, 826)
    col_w = (268, 208, 238, 248)
    for idx, (cx, cw) in enumerate(zip(col_x, col_w)):
        parts.append(f'<line x1="{cx}" y1="82" x2="{cx + cw}" y2="82" stroke="{t["ink"]}" stroke-width="1.5"/>')
        if idx > 0:
            parts.append(f'<line x1="{cx - 12}" y1="82" x2="{cx - 12}" y2="176" stroke="{t["line"]}" stroke-width="1"/>')

    # Col 1: Product & Extension Downloads (Green-by-Law highlight)
    parts.append(f'<text x="{col_x[0]}" y="100" class="t-lbl">PRODUCT &amp; EXT. DOWNLOADS</text>')
    parts.append(
        f'<text x="{col_x[0]}" y="138" class="t-disp" font-size="38" fill="{t["stamp"]}">'
        f'{esc(fmt_num(total_product))}</text>'
    )
    delta_fill = t["stamp"] if is_positive_delta else t["ink_3"]
    parts.append(
        f'<text x="{col_x[0]}" y="157" class="t-mono" font-size="11" fill="{delta_fill}">{esc(delta_text)}</text>'
    )
    parts.append(
        f'<text x="{col_x[0]}" y="173" class="t-body" font-size="11.5">All release assets (incl. aux): '
        f'<tspan class="t-strong">{esc(fmt_num(total_all))}</tspan></text>'
    )

    # Col 2: Latest Release
    parts.append(f'<text x="{col_x[1]}" y="100" class="t-lbl">LATEST RELEASE</text>')
    parts.append(
        f'<text x="{col_x[1]}" y="136" class="t-val" font-size="30">{esc(latest_tag)}</text>'
    )
    parts.append(
        f'<text x="{col_x[1]}" y="157" class="t-body" font-size="12">'
        f'<tspan class="t-strong">{esc(fmt_num(latest_dl))}</tspan> product downloads</text>'
    )
    parts.append(
        f'<text x="{col_x[1]}" y="173" class="t-mono" font-size="11" fill="{t["ink_3"]}">'
        f'Published {esc(latest_pub)}</text>'
    )

    # Col 3: Repository Pulse
    parts.append(f'<text x="{col_x[2]}" y="100" class="t-lbl">REPOSITORY PULSE</text>')
    parts.append(
        f'<text x="{col_x[2]}" y="128" class="t-val" font-size="21">{esc(fmt_num(stars))} '
        f'<tspan font-size="12" font-weight="600" fill="{t["ink_2"]}">{plural_label(stars, "star")}</tspan>  ·  '
        f'{esc(fmt_num(forks))} <tspan font-size="12" font-weight="600" fill="{t["ink_2"]}">{plural_label(forks, "fork")}</tspan></text>'
    )
    parts.append(
        f'<text x="{col_x[2]}" y="154" class="t-body" font-size="12">'
        f'<tspan class="t-strong">{esc(fmt_num(release_count))}</tspan> {plural_label(release_count, "release")}  ·  '
        f'<tspan class="t-strong">{esc(fmt_num(contributors))}</tspan> {plural_label(contributors, "contributor")}</text>'
    )
    parts.append(
        f'<text x="{col_x[2]}" y="173" class="t-mono" font-size="11" fill="{t["ink_3"]}">'
        f'{esc(fmt_num(issues))} open {plural_label(issues, "issue")}</text>'
    )

    # Col 4: Next Milestone (Green-by-Law delivered progress)
    parts.append(f'<text x="{col_x[3]}" y="100" class="t-lbl">DELIVERY MILESTONE</text>')
    parts.append(
        f'<text x="{col_x[3]}" y="130" class="t-val" font-size="22">'
        f'{esc(fmt_num(total_product))} <tspan font-size="13" fill="{t["ink_3"]}">/ {esc(fmt_num(milestone))}</tspan></text>'
    )
    m_track_w = 248.0
    m_fill_w = max(4.0, (milestone_pct / 100.0) * m_track_w) if total_product > 0 else 0.0
    parts.append(f'<rect x="{col_x[3]}" y="143" width="{m_track_w:.0f}" height="8" rx="4" fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1"/>')
    parts.append(_animated_bar(col_x[3], 143, m_fill_w, 8, 4, t["go"], animated, delay_s=0.1))
    parts.append(
        f'<text x="{col_x[3]}" y="171" class="t-mono" font-size="11" fill="{t["ink_3"]}">'
        f'{milestone_pct:.1f}% to {esc(fmt_num(milestone))} product downloads</text>'
    )

    # Mid horizontal divider
    parts.append(f'<line x1="46" y1="192" x2="1074" y2="192" stroke="{t["ink"]}" stroke-width="1.5"/>')

    # Row 2 Left: Platform Distribution (x=46..560)
    parts.append(f'<text x="46" y="214" class="t-lbl">PLATFORM DISTRIBUTION (PRODUCT &amp; EXTENSIONS)</text>')
    product_rows = [p for p in platforms if p.get("category") != "auxiliary"]
    bar_colors = {
        "Windows": t["go"],
        "Linux": t["bar_muted"],
        "Browser Extensions": t["bar_alt"],
        "macOS (Experimental)": t["bar_soft"],
    }
    max_plat = max((int(p.get("downloads") or 0) for p in product_rows), default=1) or 1
    row_y = 236
    bar_x = 224.0
    bar_max_w = 240.0

    for idx, plat in enumerate(product_rows[:4]):
        pname = str(plat.get("name") or "")
        pdls = int(plat.get("downloads") or 0)
        ppct = float(plat.get("share_of_product_pct") or 0.0)
        bw = (pdls / max_plat) * bar_max_w if pdls > 0 else 0.0
        parts.append(
            f'<text x="46" y="{row_y + 13}" class="t-strong" font-size="13">{esc(pname)}</text>'
        )
        parts.append(
            f'<rect x="{bar_x:.0f}" y="{row_y + 2}" width="{bar_max_w:.0f}" height="13" rx="4" '
            f'fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1"/>'
        )
        parts.append(
            _animated_bar(
                bar_x,
                row_y + 2,
                bw,
                13,
                4,
                bar_colors.get(pname, t["bar_muted"]),
                animated,
                delay_s=0.12 * idx,
            )
        )
        parts.append(
            f'<text x="556" y="{row_y + 13}" class="t-mono" font-size="12" text-anchor="end" '
            f'fill="{t["ink"]}">{esc(fmt_num(pdls))} <tspan fill="{t["ink_3"]}">({ppct:.1f}%)</tspan></text>'
        )
        row_y += 33

    parts.append(
        f'<text x="46" y="376" class="t-body" font-size="11.5">'
        f'Auxiliary assets excluded from above: <tspan class="t-strong">{esc(fmt_num(total_aux))}</tspan> '
        f'(symbols, .zsync, checksums, geo)</text>'
    )

    # Vertical split between Platform Distribution and Snapshot Timeline
    parts.append(f'<line x1="578" y1="202" x2="578" y2="382" stroke="{t["line"]}" stroke-width="1"/>')

    # Row 2 Right: Snapshot History / Honest Baseline Panel (x=598..1074)
    parts.append(f'<text x="598" y="214" class="t-lbl">TELEMETRY HISTORY (REAL SNAPSHOTS ONLY)</text>')
    panel_x, panel_y, panel_w, panel_h = 598, 228, 476, 150
    parts.append(
        f'<rect x="{panel_x}" y="{panel_y}" width="{panel_w}" height="{panel_h}" rx="8" '
        f'fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1"/>'
    )

    if len(snapshots) < 2:
        # Honest baseline indicator (never fabricates pre-tracking history!)
        parts.append(
            f'<text x="{panel_x + 18}" y="{panel_y + 30}" class="t-val" font-size="15">'
            f'Baseline Recording Active</text>'
        )
        parts.append(
            f'<text x="{panel_x + 18}" y="{panel_y + 51}" class="t-body" font-size="12">'
            f'Tracking started {esc(started_str)}. Real 6-hour GitHub API snapshots</text>'
        )
        parts.append(
            f'<text x="{panel_x + 18}" y="{panel_y + 68}" class="t-body" font-size="12">'
            f'accumulate automatically — zero synthetic or backfilled history.</text>'
        )
        # Baseline track with moving scanner tick
        track_x, track_y, track_w = panel_x + 18, panel_y + 92, panel_w - 36
        parts.append(
            f'<line x1="{track_x}" y1="{track_y}" x2="{track_x + track_w}" y2="{track_y}" '
            f'stroke="{t["line"]}" stroke-width="2" stroke-dasharray="5 4"/>'
        )
        parts.append(f'<circle cx="{track_x + 4}" cy="{track_y}" r="5" fill="{t["go"]}"/>')
        tick_class = ' class="scan-tick"' if animated else ""
        parts.append(
            f'<rect x="{track_x + 28}" y="{track_y - 4}" width="36" height="8" rx="4" '
            f'fill="{t["link"]}" opacity="0.85"{tick_class}/>'
        )
        parts.append(
            f'<text x="{track_x}" y="{panel_y + 124}" class="t-mono" font-size="10" fill="{t["ink_3"]}">'
            f'SAMPLE #1: {esc(fmt_num(total_product))} PROD  ·  {esc(fmt_num(total_all))} ALL  ·  DELTAS PENDING</text>'
        )
    else:
        # Plot real snapshot series (last up to 60 points)
        series = sorted(snapshots, key=lambda s: s["timestamp"])[-60:]
        vals = [int(s.get("total_product_downloads") or 0) for s in series]
        v_min = min(vals)
        v_max = max(vals)
        span = max(1, v_max - v_min)
        pad_l, pad_r, pad_t, pad_b = 52, 18, 22, 34
        cx0 = panel_x + pad_l
        cy0 = panel_y + pad_t
        cw = panel_w - pad_l - pad_r
        ch = panel_h - pad_t - pad_b

        for frac in (0.0, 0.5, 1.0):
            gy = cy0 + ch * (1.0 - frac)
            gv = int(round(v_min + span * frac))
            parts.append(f'<line x1="{cx0}" y1="{gy:.1f}" x2="{cx0 + cw}" y2="{gy:.1f}" stroke="{t["line"]}" stroke-width="1"/>')
            parts.append(
                f'<text x="{cx0 - 8}" y="{gy + 3.5:.1f}" class="t-mono" font-size="10" '
                f'text-anchor="end" fill="{t["ink_3"]}">{esc(fmt_num(gv))}</text>'
            )

        coords: list[tuple[float, float]] = []
        n_pts = len(series)
        for i, val in enumerate(vals):
            px = cx0 + (cw * i / max(1, n_pts - 1))
            py = cy0 + ch - ((val - v_min) / span) * ch
            coords.append((px, py))

        line_d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in coords)
        area_d = f"{line_d} L{coords[-1][0]:.1f},{cy0 + ch:.1f} L{coords[0][0]:.1f},{cy0 + ch:.1f} Z"
        parts.append(f'<path d="{area_d}" fill="{t["go"]}" opacity="0.16"/>')
        parts.append(
            f'<path d="{line_d}" fill="none" stroke="{t["stamp"]}" stroke-width="2.5" '
            f'stroke-linecap="round" stroke-linejoin="round"/>'
        )
        lx, ly = coords[-1]
        parts.append(f'<circle cx="{lx:.1f}" cy="{ly:.1f}" r="4.5" fill="{t["go"]}" stroke="{t["paper"]}" stroke-width="1.5"/>')
        first_lbl = series[0]["timestamp"][:10]
        last_lbl = series[-1]["timestamp"][:10]
        parts.append(
            f'<text x="{cx0}" y="{panel_y + panel_h - 10}" class="t-mono" font-size="10" fill="{t["ink_3"]}">'
            f'{esc(first_lbl)} ({len(series)} samples)</text>'
        )
        parts.append(
            f'<text x="{cx0 + cw}" y="{panel_y + panel_h - 10}" class="t-mono" font-size="10" '
            f'text-anchor="end" fill="{t["ink_3"]}">{esc(last_lbl)}</text>'
        )

    # Dashed thermal tear line + barcode footer
    parts.append(
        f'<line x1="46" y1="394" x2="1074" y2="394" stroke="{t["ink_3"]}" '
        f'stroke-width="1.5" stroke-dasharray="6 4" opacity="0.65"/>'
    )
    bars_svg = barcode_bars(f"{latest_tag}-{total_product}-{synced_str}", 46, 405, 24, 196)
    parts.append(f'<g fill="{t["ink"]}">{bars_svg}</g>')
    parts.append(
        f'<text x="256" y="421" class="t-mono" font-size="11" fill="{t["ink_2"]}">'
        f'REPO DecoyINDIA/BMD  ·  GPL-3.0</text>'
    )
    parts.append(
        f'<text x="1074" y="421" class="t-mono" font-size="11" text-anchor="end" fill="{t["ink_3"]}">'
        f'Periodically refreshed every 6h  ·  Last synced {esc(synced_str)}</text>'
    )

    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def render_platform_breakdown(data: dict[str, Any], theme_name: str, animated: bool = True) -> str:
    """Render the platform & release breakdown SVG."""
    t = THEMES[theme_name]
    w, h = 1120, 444
    summary = data.get("summary") or {}
    platforms = data.get("platforms") or []
    releases = data.get("releases") or []
    synced_str = fmt_timestamp(data.get("last_successful_api_sync_at") or data.get("last_updated_at"))
    total_all = int(summary.get("total_all_assets") or 0)
    total_product = int(summary.get("total_product_downloads") or 0)

    # Flatten all downloadable assets from latest release (or all releases) for top-packages column
    top_assets: list[dict[str, Any]] = []
    for rel in releases[:3]:
        tag = rel.get("tag_name") or ""
        for asset in rel.get("assets") or []:
            if asset.get("category") == "downloadable":
                top_assets.append({"tag": tag, **asset})
    top_assets.sort(key=lambda a: (-int(a.get("download_count") or 0), a.get("name") or ""))
    top_assets = top_assets[:6]

    svg_title = f"Black Marlin Downloader Platform and Release Manifest ({theme_name.title()} Theme)"
    svg_desc = (
        f"Download breakdown across Windows, Linux, macOS (Experimental), Browser Extensions, and Auxiliary assets "
        f"for DecoyINDIA/BMD ({fmt_num(total_product)} product downloads out of {fmt_num(total_all)} total release assets)."
    )
    parts: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" role="img" aria-labelledby="svg-title svg-desc">',
        f'<title id="svg-title">{esc(svg_title)}</title>',
        f'<desc id="svg-desc">{esc(svg_desc)}</desc>',
        "<defs>",
        f'<linearGradient id="boardGrad" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0%" stop-color="{t["board"]}"/>'
        f'<stop offset="100%" stop-color="{t["board_deep"]}"/>'
        f"</linearGradient>",
        "</defs>",
        _common_style(t, animated),
        _board_backdrop(w, h, t),
        _waybill_shell(
            w,
            h,
            t,
            "BMD · PLATFORM & RELEASE MANIFEST",
            f"PRODUCT {fmt_num(total_product)}  ·  ALL ASSETS {fmt_num(total_all)}  ·  ZERO DOUBLE-COUNTING",
        ),
    ]

    # Left panel: All 5 Platform Buckets with explicit asset counts
    parts.append(f'<text x="46" y="96" class="t-lbl">DOWNLOADS BY TARGET BUCKET</text>')
    parts.append(f'<line x1="46" y1="104" x2="544" y2="104" stroke="{t["ink"]}" stroke-width="1.5"/>')

    # Count assets per bucket across all releases
    bucket_file_counts: dict[str, int] = {}
    for rel in releases:
        for asset in rel.get("assets") or []:
            plat = str(asset.get("platform") or "Other / Auxiliary")
            bucket_file_counts[plat] = bucket_file_counts.get(plat, 0) + 1

    max_bucket = max((int(p.get("downloads") or 0) for p in platforms), default=1) or 1
    bucket_colors = {
        "Windows": t["go"],
        "Linux": t["bar_muted"],
        "Browser Extensions": t["bar_alt"],
        "macOS (Experimental)": t["bar_soft"],
        "Other / Auxiliary": t["ink_3"],
    }

    by = 122
    for idx, plat in enumerate(platforms):
        pname = str(plat.get("name") or "")
        pdls = int(plat.get("downloads") or 0)
        share_all = float(plat.get("share_of_all_assets_pct") or 0.0)
        nfiles = bucket_file_counts.get(pname, 0)
        bw = (pdls / max_bucket) * 320.0 if pdls > 0 else 0.0
        tag_txt = "AUX" if plat.get("category") == "auxiliary" else "PROD"

        parts.append(
            f'<text x="46" y="{by + 13}" class="t-strong" font-size="13">{esc(pname)}</text>'
        )
        parts.append(
            f'<text x="240" y="{by + 13}" class="t-mono" font-size="10.5" fill="{t["ink_3"]}">'
            f'[{tag_txt} · {nfiles} files]</text>'
        )
        parts.append(
            f'<text x="544" y="{by + 13}" class="t-mono" font-size="12.5" text-anchor="end" fill="{t["ink"]}">'
            f'{esc(fmt_num(pdls))} <tspan fill="{t["ink_3"]}">({share_all:.1f}%)</tspan></text>'
        )
        parts.append(
            f'<rect x="46" y="{by + 21}" width="498" height="11" rx="4" '
            f'fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1"/>'
        )
        parts.append(
            _animated_bar(
                46,
                by + 21,
                bw * (498.0 / 320.0),
                11,
                4,
                bucket_colors.get(pname, t["bar_muted"]),
                animated,
                delay_s=0.1 * idx,
            )
        )
        by += 49

    # Vertical separator
    parts.append(f'<line x1="572" y1="88" x2="572" y2="352" stroke="{t["line"]}" stroke-width="1"/>')

    # Right panel Top: Releases Ledger (up to 3 recent) + Top Packages
    parts.append(f'<text x="598" y="96" class="t-lbl">RELEASES LEDGER &amp; TOP PACKAGES</text>')
    parts.append(f'<line x1="598" y1="104" x2="1074" y2="104" stroke="{t["ink"]}" stroke-width="1.5"/>')

    ry = 124
    for idx, rel in enumerate(releases[:3]):
        rtag = str(rel.get("tag_name") or "")
        rdate = str(rel.get("published_at") or "")[:10]
        rprod = int(rel.get("product_downloads") or 0)
        rall = int(rel.get("all_assets_downloads") or 0)
        badge = "LATEST" if idx == 0 else "RELEASE"
        badge_bg = t["ink"] if idx == 0 else t["paper_2"]
        badge_fg = t["paper"] if idx == 0 else t["ink_2"]

        parts.append(
            f'<rect x="598" y="{ry - 11}" width="58" height="17" rx="4" fill="{badge_bg}" '
            f'stroke="{t["line"]}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="627" y="{ry + 1}" class="t-mono" font-size="9.5" text-anchor="middle" '
            f'fill="{badge_fg}">{badge}</text>'
        )
        parts.append(f'<text x="666" y="{ry + 2}" class="t-val" font-size="14">{esc(rtag)}</text>')
        parts.append(f'<text x="742" y="{ry + 2}" class="t-mono" font-size="11" fill="{t["ink_3"]}">{esc(rdate)}</text>')
        parts.append(
            f'<text x="1074" y="{ry + 2}" class="t-mono" font-size="12" text-anchor="end" fill="{t["stamp"]}">'
            f'{esc(fmt_num(rprod))} prod <tspan fill="{t["ink_3"]}">/ {esc(fmt_num(rall))} total</tspan></text>'
        )
        ry += 25

    parts.append(f'<line x1="598" y1="{ry + 2}" x2="1074" y2="{ry + 2}" stroke="{t["line"]}" stroke-width="1"/>')
    parts.append(f'<text x="598" y="{ry + 22}" class="t-lbl">TOP DOWNLOADED PACKAGES</text>')

    ay = ry + 40
    max_asset_dl = max((int(a.get("download_count") or 0) for a in top_assets), default=1) or 1
    for idx, asset in enumerate(top_assets):
        aname = str(asset.get("name") or "")
        if len(aname) > 34:
            aname = aname[:31] + "..."
        adl = int(asset.get("download_count") or 0)
        asize = fmt_bytes(int(asset.get("size") or 0))
        aw = (adl / max_asset_dl) * 120.0 if adl > 0 else 0.0

        parts.append(f'<text x="598" y="{ay}" class="t-mono" font-size="11.5" fill="{t["ink"]}">{esc(aname)}</text>')
        parts.append(f'<text x="860" y="{ay}" class="t-mono" font-size="10.5" fill="{t["ink_3"]}">{esc(asize)}</text>')
        parts.append(
            f'<rect x="916" y="{ay - 9}" width="110" height="9" rx="3" '
            f'fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1"/>'
        )
        parts.append(_animated_bar(916, ay - 9, aw * (110.0 / 120.0), 9, 3, t["go"], animated, delay_s=0.08 * idx))
        parts.append(
            f'<text x="1074" y="{ay}" class="t-mono" font-size="11.5" text-anchor="end" fill="{t["ink"]}">'
            f'{esc(fmt_num(adl))}</text>'
        )
        ay += 24

    # Footer tear line
    parts.append(
        f'<line x1="46" y1="364" x2="1074" y2="364" stroke="{t["ink_3"]}" '
        f'stroke-width="1.5" stroke-dasharray="6 4" opacity="0.65"/>'
    )
    parts.append(
        f'<text x="46" y="388" class="t-body" font-size="11.5">'
        f'Product = installers, portable archives, Linux packages, experimental macOS .dmg, browser extensions. '
        f'Auxiliary = symbols, .zsync, checksums, geo.</text>'
    )
    parts.append(
        f'<text x="1074" y="406" class="t-mono" font-size="11" text-anchor="end" fill="{t["ink_3"]}">'
        f'Last synced {esc(synced_str)}  ·  Source: GitHub Releases API</text>'
    )
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def render_markdown_telemetry(data: dict[str, Any]) -> str:
    """Render an accessible Markdown telemetry summary table from ``downloads.json``."""
    summary = data.get("summary") or {}
    repo = data.get("repo") or {}
    platforms = data.get("platforms") or []

    total_product = int(summary.get("total_product_downloads") or 0)
    total_all = int(summary.get("total_all_assets") or 0)
    latest_tag = summary.get("latest_release_tag") or "—"
    latest_dl = int(summary.get("latest_release_downloads") or 0)
    latest_pub = (summary.get("latest_release_published_at") or "")[:10] or "—"
    stars = int(repo.get("stars") or 0)
    forks = int(repo.get("forks") or 0)
    contributors = int(repo.get("contributors_count") or 0)
    tracking_started = fmt_timestamp(data.get("tracking_started_at"))
    last_synced = fmt_timestamp(data.get("last_successful_api_sync_at") or data.get("last_updated_at"))
    api_status = data.get("api_status") or "ok"
    status_note = (
        "ok (live GitHub API)"
        if api_status == "ok"
        else f"cached_fallback (last successful sync: {last_synced})"
    )

    lines = [
        "| Metric | Value |",
        "| :--- | :--- |",
        f"| **Product & Extension Downloads** | **{fmt_num(total_product)}** |",
        f"| **All Release Assets Total** (incl. symbols, `.zsync`, checksums, geo) | {fmt_num(total_all)} |",
        f"| **Latest Release** | `{latest_tag}` ({fmt_num(latest_dl)} product downloads · {latest_pub}) |",
        f"| **Repository Pulse** | {fmt_num(stars)} {plural_label(stars, 'star')} · {fmt_num(forks)} {plural_label(forks, 'fork')} · {fmt_num(contributors)} {plural_label(contributors, 'contributor')} |",
        f"| **Tracking Started** | `{tracking_started}` |",
        f"| **Last Synced** | `{last_synced}` · `api_status`: `{status_note}` |",
        "",
        "| Platform Bucket | Category | Downloads | Share (Product) | Share (All Assets) |",
        "| :--- | :--- | ---: | ---: | ---: |",
    ]
    for plat in platforms:
        pname = str(plat.get("name") or "")
        cat = "Auxiliary" if plat.get("category") == "auxiliary" else "Product"
        pdls = fmt_num(int(plat.get("downloads") or 0))
        share_prod = (
            f"{float(plat.get('share_of_product_pct') or 0.0):.1f}%"
            if plat.get("category") != "auxiliary"
            else "—"
        )
        share_all = f"{float(plat.get('share_of_all_assets_pct') or 0.0):.1f}%"
        lines.append(f"| {pname} | {cat} | {pdls} | {share_prod} | {share_all} |")

    return "\n".join(lines)


def update_readme_telemetry_block(
    data: dict[str, Any],
    readme_path: Path = DEFAULT_README,
) -> bool:
    """Update the telemetry block between markers in ``readme_path`` if both markers exist.

    Returns ``True`` if ``readme_path`` was modified, or ``False`` for a safe no-op.
    """
    if not readme_path.exists():
        return False

    original = readme_path.read_text(encoding="utf-8")
    start_idx = original.find(ANALYTICS_START_MARKER)
    if start_idx == -1:
        return False
    end_idx = original.find(ANALYTICS_END_MARKER, start_idx + len(ANALYTICS_START_MARKER))
    if end_idx == -1:
        return False

    block = render_markdown_telemetry(data)
    updated = (
        original[: start_idx + len(ANALYTICS_START_MARKER)]
        + "\n"
        + block
        + "\n"
        + original[end_idx:]
    )
    if updated == original:
        return False

    readme_path.write_text(updated, encoding="utf-8")
    return True


def generate_all(
    input_path: Path = DEFAULT_INPUT,
    out_dir: Path = DEFAULT_OUT_DIR,
    readme_path: Path | None = DEFAULT_README,
) -> list[Path]:
    """Read ``input_path``, write all 6 analytics SVGs into ``out_dir``, and update README markers if present."""
    with input_path.open("r", encoding="utf-8") as fh:
        data = json.load(fh)

    out_dir.mkdir(parents=True, exist_ok=True)
    outputs: list[tuple[str, str]] = []
    for theme in ("light", "dark"):
        outputs.append((f"telemetry-dashboard-{theme}.svg", render_telemetry_dashboard(data, theme, animated=True)))
        outputs.append((f"telemetry-dashboard-static-{theme}.svg", render_telemetry_dashboard(data, theme, animated=False)))
        outputs.append((f"platform-breakdown-{theme}.svg", render_platform_breakdown(data, theme, animated=True)))

    written: list[Path] = []
    for filename, content in outputs:
        target = out_dir / filename
        target.write_text(content, encoding="utf-8")
        written.append(target)

    if readme_path is not None:
        update_readme_telemetry_block(data, readme_path=readme_path)

    return written


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="Input downloads.json path")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR, help="Output directory for SVGs")
    parser.add_argument("--readme", type=Path, default=DEFAULT_README, help="Optional README.md to update between markers")
    parser.add_argument("--skip-readme", action="store_true", help="Do not update README.md marker block")
    args = parser.parse_args(argv)

    written = generate_all(
        input_path=args.input,
        out_dir=args.out_dir,
        readme_path=None if args.skip_readme else args.readme,
    )
    for path in written:
        print(f"generated {path} ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
