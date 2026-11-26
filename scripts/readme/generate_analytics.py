#!/usr/bin/env python3
"""Generate BMD Pelagic Orbital analytics SVGs (and optional Living Adoption WebPs) from ``data/analytics/downloads.json``.

Produces GitHub-compatible animated and static SVG assets in ``assets/readme/analytics/``:
  - ``telemetry-dashboard-light.svg`` & ``telemetry-dashboard-dark.svg``
  - ``telemetry-dashboard-static-light.svg`` & ``telemetry-dashboard-static-dark.svg``
  - ``platform-breakdown-light.svg`` & ``platform-breakdown-dark.svg``

Also updates the plain-text Markdown telemetry table between
``<!-- BMD:ANALYTICS-TEXT:START -->`` and ``<!-- BMD:ANALYTICS-TEXT:END -->`` in ``README.md``,
and optionally invokes ``scripts/readme/velocity/build.mjs --only living-adoption`` when
``--with-webp`` or ``--with-webp-if-available`` is requested.

Design & Data Integrity Rules:
  - Hydrodynamic Velocity / Pelagic Orbital palette harmonizing with ``art/BMD Black Marlin.svg``
    and BMD's default ``Emerald`` theme on GitHub Light (``#F5F9F7``) and Dark (``#090E0C``).
  - Pure SVG + inline CSS ``@keyframes`` + ``prefers-reduced-motion`` (zero ``<script>``,
    zero ``<foreignObject>``, zero external URLs, zero em-dashes).
  - Strict separation of product & extension downloads vs. auxiliary assets (zero double-counting).
  - Never labels download counts as unique users, and never fabricates pre-tracking history.
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = ROOT / "data" / "analytics" / "downloads.json"
DEFAULT_OUT_DIR = ROOT / "assets" / "readme" / "analytics"
DEFAULT_README = ROOT / "README.md"
VELOCITY_DIR = ROOT / "scripts" / "readme" / "velocity"
ANALYTICS_START_MARKER = "<!-- BMD:ANALYTICS-TEXT:START -->"
ANALYTICS_END_MARKER = "<!-- BMD:ANALYTICS-TEXT:END -->"

FONT_DISPLAY = "'Space Grotesk', 'Segoe UI', system-ui, -apple-system, sans-serif"
FONT_BODY = "'IBM Plex Sans', 'Segoe UI', system-ui, -apple-system, sans-serif"
FONT_MONO = "'IBM Plex Mono', 'Cascadia Mono', Consolas, ui-monospace, monospace"

THEMES: dict[str, dict[str, str]] = {
    "light": {
        "name": "light",
        "bg": "#F5F9F7",
        "surface": "#FFFFFF",
        "surface_elev": "#EAF3EF",
        "text": "#091511",
        "text_muted": "#2B4239",
        "text_dim": "#5A756A",
        "primary": "#136F50",
        "glow": "#0EA472",
        "deep": "#0D5C43",
        "spec": "#5AE0AC",
        "cyan": "#0284C7",
        "amber": "#D97706",
        "hairline": "#CBE2D8",
        "hairline_strong": "#9AC8B5",
        "track": "#E4EFEA",
        "badge_bg": "#136F50",
        "badge_fg": "#FFFFFF",
    },
    "dark": {
        "name": "dark",
        "bg": "#090E0C",
        "surface": "#0F1915",
        "surface_elev": "#14221C",
        "text": "#ECF7F2",
        "text_muted": "#94B3A6",
        "text_dim": "#5B7A6D",
        "primary": "#55C99D",
        "glow": "#80DBAD",
        "deep": "#187052",
        "spec": "#C1F0D6",
        "cyan": "#38BDF8",
        "amber": "#F59E0B",
        "hairline": "#1F3A30",
        "hairline_strong": "#2E5848",
        "track": "#172721",
        "badge_bg": "#55C99D",
        "badge_fg": "#06150E",
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
    """Format byte size cleanly in binary MiB/KiB units."""
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MiB"
    if size >= 1024:
        return f"{size / 1024:.1f} KiB"
    return f"{size} B"


def fmt_timestamp(iso_str: str | None) -> str:
    """Render an ISO UTC timestamp compactly as ``YYYY-MM-DD HH:MM UTC``."""
    if not iso_str:
        return "Pending"
    if "T" in iso_str and len(iso_str) >= 16:
        return f"{iso_str[:10]} {iso_str[11:16]} UTC"
    return iso_str


def _parse_iso(value: str) -> dt.datetime | None:
    if not value:
        return None
    try:
        normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
        parsed = dt.datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        return parsed.astimezone(dt.timezone.utc)
    except ValueError:
        return None


def _snapshot_series_state(snapshots: list[dict[str, Any]]) -> dict[str, Any]:
    """Classify snapshot history honestly, handling sub-24h and zero-variance baselines."""
    ordered = sorted(
        (s for s in snapshots if isinstance(s, dict) and s.get("timestamp")),
        key=lambda s: str(s["timestamp"]),
    )
    count = len(ordered)
    if count < 2:
        return {"has_trajectory": False, "span_hours": 0.0, "count": count, "series": ordered}

    t0 = _parse_iso(str(ordered[0]["timestamp"]))
    t1 = _parse_iso(str(ordered[-1]["timestamp"]))
    span_hours = ((t1 - t0).total_seconds() / 3600.0) if (t0 and t1) else 0.0
    vals = [int(s.get("total_product_downloads") or 0) for s in ordered]
    has_variance = max(vals) > min(vals)
    has_trajectory = span_hours >= 24.0 and has_variance
    return {
        "has_trajectory": has_trajectory,
        "span_hours": span_hours,
        "count": count,
        "series": ordered[-60:],
    }


def _common_style(t: dict[str, str], animated: bool) -> str:
    anim_css = ""
    if animated:
        anim_css = """
    @keyframes pulseBeacon {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.45; transform: scale(0.82); }
    }
    @keyframes radarSweep {
      0% { transform: scale(0.28); opacity: 0.55; }
      100% { transform: scale(1.0); opacity: 0; }
    }
    @keyframes orbitSlow {
      0% { transform: rotate(0deg); }
      100% { transform: rotate(360deg); }
    }
    @keyframes scanTick {
      0% { transform: translateX(0px); opacity: 0.35; }
      50% { opacity: 0.95; }
      100% { transform: translateX(340px); opacity: 0.35; }
    }
    @keyframes barGrow {
      0% { transform: scaleX(0.01); }
      100% { transform: scaleX(1); }
    }
    .pulse-dot { transform-box: fill-box; transform-origin: center; animation: pulseBeacon 2.8s ease-in-out infinite; }
    .radar-ring { transform-box: fill-box; transform-origin: center; animation: radarSweep 4.8s ease-out infinite; }
    .orbit-spin { animation: orbitSlow 28s linear infinite; }
    .scan-tick { animation: scanTick 4.5s ease-in-out infinite alternate; }
    .bar-grow { transform-box: fill-box; transform-origin: left center; animation: barGrow 0.85s cubic-bezier(0.16, 1, 0.3, 1) backwards; }
    @media (prefers-reduced-motion: reduce) {
      .pulse-dot, .radar-ring, .orbit-spin, .scan-tick, .bar-grow { animation: none !important; transform: none !important; }
    }"""

    return f"""<style>
    .t-disp {{ font-family: {FONT_DISPLAY}; font-weight: 700; font-variant-numeric: tabular-nums; }}
    .t-lbl {{ font-family: {FONT_MONO}; font-weight: 600; font-size: 10.5px; letter-spacing: 0.08em; text-transform: uppercase; fill: {t['text_dim']}; }}
    .t-val {{ font-family: {FONT_DISPLAY}; font-weight: 700; font-variant-numeric: tabular-nums; fill: {t['text']}; }}
    .t-body {{ font-family: {FONT_BODY}; font-weight: 400; font-variant-numeric: tabular-nums; fill: {t['text_muted']}; }}
    .t-strong {{ font-family: {FONT_BODY}; font-weight: 600; font-variant-numeric: tabular-nums; fill: {t['text']}; }}
    .t-mono {{ font-family: {FONT_MONO}; font-variant-numeric: tabular-nums; }}
    {anim_css}
  </style>"""


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


def _format_delta_line(summary: dict[str, Any], snap_state: dict[str, Any]) -> tuple[str, bool]:
    daily = summary.get("daily_delta")
    weekly = summary.get("weekly_delta")
    if daily is not None and daily > 0:
        wk = f" · +{fmt_num(weekly)} / 7d" if weekly is not None else ""
        return (f"+{fmt_num(daily)} / 24h{wk}", True)
    if daily == 0:
        wk = f" · +{fmt_num(weekly)} / 7d" if weekly is not None else ""
        return (f"0 / 24h{wk}", False)
    count = int(snap_state.get("count") or 0)
    return (f"Baseline active ({count} {plural_label(count, 'checkpoint')}, < 24h span)", False)


def _svg_arc_path(cx: float, cy: float, r: float, start_deg: float, span_deg: float) -> str:
    """Build an SVG arc path from ``start_deg`` covering ``span_deg`` (< 360)."""
    clamped = max(1.0, min(359.5, span_deg))
    a0 = math.radians(start_deg - 90.0)
    a1 = math.radians(start_deg + clamped - 90.0)
    x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
    x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
    large_arc = 1 if clamped > 180.0 else 0
    return f"M {x0:.2f} {y0:.2f} A {r:.2f} {r:.2f} 0 {large_arc} 1 {x1:.2f} {y1:.2f}"


def _platform_style_map(t: dict[str, str]) -> dict[str, dict[str, Any]]:
    return {
        "Windows": {"r": 146.0, "color": t["primary"], "start": -24.0},
        "Linux": {"r": 115.0, "color": t["cyan"], "start": 35.0},
        "Browser Extensions": {"r": 85.0, "color": t["glow"], "start": 105.0},
        "macOS (Experimental)": {"r": 56.0, "color": t["amber"], "start": 185.0},
        "Other / Auxiliary": {"r": 176.0, "color": t["text_dim"], "start": 0.0},
    }


def _render_pelagic_orbital(
    ox: float,
    oy: float,
    t: dict[str, str],
    platforms: list[dict[str, Any]],
    total_product: int,
    total_aux: int,
    animated: bool,
) -> list[str]:
    """Render the Pelagic Orbital adoption sculpture (discrete nodes <= 120, filaments > 120)."""
    parts: list[str] = []
    styles = _platform_style_map(t)

    # Soft pelagic radial aura behind sculpture
    parts.append(f'<circle cx="{ox}" cy="{oy}" r="196" fill="url(#pelagicAura)"/>')

    # Harmonic radar pulse ring
    radar_cls = ' class="radar-ring"' if animated else ""
    parts.append(
        f'<circle cx="{ox}" cy="{oy}" r="172" fill="none" stroke="{t["glow"]}" '
        f'stroke-width="1.2" opacity="0.28"{radar_cls}/>'
    )

    # Outer auxiliary calibration perimeter (representing excluded aux assets)
    aux_r = 176.0
    parts.append(
        f'<circle cx="{ox}" cy="{oy}" r="{aux_r:.0f}" fill="none" stroke="{t["hairline"]}" '
        f'stroke-width="1.2" stroke-dasharray="3 5"/>'
    )
    aux_ticks = min(72, max(0, total_aux))
    if aux_ticks > 0:
        tick_lines: list[str] = []
        for i in range(aux_ticks):
            ang = (i / aux_ticks) * math.tau
            x1 = ox + math.cos(ang) * (aux_r - 4.0)
            y1 = oy + math.sin(ang) * (aux_r - 4.0)
            x2 = ox + math.cos(ang) * (aux_r + 4.0)
            y2 = oy + math.sin(ang) * (aux_r + 4.0)
            tick_lines.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}"/>')
        parts.append(
            f'<g stroke="{t["text_dim"]}" stroke-width="1.4" opacity="0.65">'
            + "".join(tick_lines)
            + "</g>"
        )

    # Rotating orbital group
    orbit_open = f'<g class="orbit-spin" style="transform-origin: {ox}px {oy}px;">' if animated else "<g>"
    parts.append(orbit_open)

    product_plats = [p for p in platforms if p.get("category") != "auxiliary"]
    for plat in product_plats:
        pname = str(plat.get("name") or "")
        pdls = max(0, int(plat.get("downloads") or 0))
        pct = float(plat.get("share_of_product_pct") or 0.0)
        st = styles.get(pname, {"r": 100.0, "color": t["primary"], "start": 0.0})
        r = float(st["r"])
        color = str(st["color"])
        start_deg = float(st["start"])

        parts.append(
            f'<circle cx="{ox}" cy="{oy}" r="{r:.0f}" fill="none" stroke="{t["hairline"]}" stroke-width="1.2"/>'
        )
        if pdls > 0:
            arc_d = _svg_arc_path(ox, oy, r, start_deg, max(12.0, (pct / 100.0) * 360.0))
            parts.append(
                f'<path d="{arc_d}" fill="none" stroke="{color}" stroke-width="4.5" '
                f'stroke-linecap="round" opacity="0.34"/>'
            )

        if total_product <= 120:
            # Individual countable jewel-nodes (every single real download rendered!)
            for i in range(pdls):
                theta = math.radians(start_deg - 90.0) + (i / max(1, pdls)) * math.tau
                nx = ox + math.cos(theta) * r
                ny = oy + math.sin(theta) * r
                parts.append(f'<circle cx="{nx:.1f}" cy="{ny:.1f}" r="4.2" fill="{color}"/>')
                parts.append(f'<circle cx="{nx:.1f}" cy="{ny:.1f}" r="1.4" fill="{t["bg"]}"/>')
        elif pdls > 0:
            # Density-modulated hydrodynamic filament segments for scale > 120
            bands = 18
            for i in range(bands):
                seg_deg = start_deg + (i / bands) * 360.0
                seg_d = _svg_arc_path(ox, oy, r, seg_deg, 10.0)
                alpha = 0.45 + 0.4 * math.sin(i * 1.1)
                parts.append(
                    f'<path d="{seg_d}" fill="none" stroke="{color}" stroke-width="3.2" '
                    f'stroke-linecap="round" opacity="{alpha:.2f}"/>'
                )

    parts.append("</g>")

    # Central Release Core Beacon (stays upright & stationary)
    parts.append(
        f'<circle cx="{ox}" cy="{oy}" r="32" fill="{t["surface"]}" stroke="{t["primary"]}" stroke-width="2"/>'
    )
    parts.append(
        f'<text x="{ox}" y="{oy + 5}" class="t-mono" font-size="19" font-weight="700" '
        f'text-anchor="middle" fill="{t["text"]}">{esc(fmt_num(total_product))}</text>'
    )
    parts.append(
        f'<text x="{ox}" y="{oy + 18}" class="t-mono" font-size="8.5" font-weight="600" '
        f'letter-spacing="0.08em" text-anchor="middle" fill="{t["text_dim"]}">PRODUCT</text>'
    )
    parts.append(
        f'<text x="{ox}" y="{oy + 206}" class="t-mono" font-size="10" text-anchor="middle" '
        f'fill="{t["text_dim"]}">OUTER TICKS = {esc(fmt_num(total_aux))} AUX EXCLUDED</text>'
    )
    return parts


def render_telemetry_dashboard(data: dict[str, Any], theme_name: str, animated: bool) -> str:
    """Render the Pelagic Orbital telemetry dashboard SVG (animated or static)."""
    t = THEMES[theme_name]
    w, h = 1120, 478
    summary = data.get("summary") or {}
    repo = data.get("repo") or {}
    platforms = data.get("platforms") or []
    snapshots = data.get("snapshots") or []

    total_product = int(summary.get("total_product_downloads") or 0)
    total_all = int(summary.get("total_all_assets") or 0)
    total_aux = int(summary.get("total_auxiliary_downloads") or 0)
    latest_tag = summary.get("latest_release_tag") or "-"
    latest_dl = int(summary.get("latest_release_downloads") or 0)
    latest_pub = (summary.get("latest_release_published_at") or "")[:10] or "-"
    release_count = int(summary.get("release_count") or 0)
    stars = int(repo.get("stars") or 0)
    forks = int(repo.get("forks") or 0)
    issues = int(repo.get("open_issues") or 0)
    contributors = int(repo.get("contributors_count") or 0)

    api_status = data.get("api_status") or "ok"
    synced_str = fmt_timestamp(data.get("last_successful_api_sync_at") or data.get("last_updated_at"))
    started_str = (data.get("tracking_started_at") or "")[:10] or "-"
    status_label = "API VERIFIED" if api_status == "ok" else "CACHED FALLBACK"

    snap_state = _snapshot_series_state(snapshots)
    delta_text, is_positive_delta = _format_delta_line(summary, snap_state)

    svg_title = f"Black Marlin Downloader Living Adoption Telemetry ({theme_name.title()} Theme)"
    svg_desc = (
        f"Live GitHub telemetry for DecoyINDIA/BMD: {fmt_num(total_product)} product and extension downloads, "
        f"{fmt_num(total_all)} total release assets ({fmt_num(total_aux)} auxiliary excluded), "
        f"latest release {latest_tag} ({fmt_num(latest_dl)} downloads), "
        f"{fmt_num(stars)} stars, {fmt_num(forks)} forks, and {fmt_num(contributors)} contributors."
    )

    parts: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" role="img" aria-labelledby="svg-title svg-desc">',
        f'<title id="svg-title">{esc(svg_title)}</title>',
        f'<desc id="svg-desc">{esc(svg_desc)}</desc>',
        "<defs>",
        f'<radialGradient id="pelagicAura" cx="50%" cy="50%" r="50%">'
        f'<stop offset="0%" stop-color="{t["glow"]}" stop-opacity="0.18"/>'
        f'<stop offset="100%" stop-color="{t["bg"]}" stop-opacity="0"/>'
        f"</radialGradient>",
        "</defs>",
        _common_style(t, animated),
        f'<rect width="{w}" height="{h}" rx="14" fill="{t["bg"]}"/>',
    ]

    # Header bar (clean editorial typography on canvas)
    dot_cls = ' class="pulse-dot"' if animated else ""
    dot_fill = t["glow"] if api_status == "ok" else t["amber"]
    parts.append(f'<circle cx="42" cy="34" r="4.5" fill="{dot_fill}"{dot_cls}/>')
    parts.append(
        f'<text x="56" y="38" class="t-mono" font-size="11" font-weight="600" letter-spacing="0.06em" '
        f'fill="{t["primary"]}">LIVING ADOPTION  ·  LIVE GITHUB RELEASE TELEMETRY ({esc(latest_tag)})</text>'
    )
    parts.append(
        f'<text x="1084" y="38" class="t-mono" font-size="10.5" text-anchor="end" fill="{t["text_dim"]}">'
        f'TRACKING SINCE {esc(started_str)}  ·  {esc(status_label)}</text>'
    )
    parts.append(f'<line x1="36" y1="54" x2="1084" y2="54" stroke="{t["hairline"]}" stroke-width="1"/>')

    # Left: Pelagic Orbital Sculpture
    parts.extend(_render_pelagic_orbital(236.0, 246.0, t, platforms, total_product, total_aux, animated))

    # Right: Surface Panel for KPIs, Platform Share Bars, and Honest Snapshot History
    px, py, pw, ph = 460, 74, 624, 362
    parts.append(
        f'<rect x="{px}" y="{py}" width="{pw}" height="{ph}" rx="12" '
        f'fill="{t["surface"]}" stroke="{t["hairline_strong"]}" stroke-width="1.2"/>'
    )

    # Row 1: 3 KPI columns
    kx_list = (px + 22, px + 226, px + 422)
    parts.append(f'<text x="{kx_list[0]}" y="{py + 28}" class="t-lbl">PRODUCT &amp; EXT. DOWNLOADS</text>')
    parts.append(
        f'<text x="{kx_list[0]}" y="{py + 62}" class="t-disp" font-size="34" fill="{t["primary"]}">'
        f'{esc(fmt_num(total_product))}</text>'
    )
    delta_fill = t["primary"] if is_positive_delta else t["text_dim"]
    parts.append(
        f'<text x="{kx_list[0]}" y="{py + 80}" class="t-mono" font-size="10.5" fill="{delta_fill}">'
        f'{esc(delta_text)}</text>'
    )

    parts.append(f'<text x="{kx_list[1]}" y="{py + 28}" class="t-lbl">ALL RELEASE ASSETS</text>')
    parts.append(
        f'<text x="{kx_list[1]}" y="{py + 62}" class="t-val" font-size="30">{esc(fmt_num(total_all))}</text>'
    )
    parts.append(
        f'<text x="{kx_list[1]}" y="{py + 80}" class="t-body" font-size="11.5">'
        f'+{esc(fmt_num(total_aux))} aux · {esc(latest_tag)} ({esc(latest_pub)})</text>'
    )

    parts.append(f'<text x="{kx_list[2]}" y="{py + 28}" class="t-lbl">REPOSITORY PULSE</text>')
    parts.append(
        f'<text x="{kx_list[2]}" y="{py + 56}" class="t-val" font-size="20">{esc(fmt_num(stars))} '
        f'<tspan font-size="12" font-weight="500" fill="{t["text_muted"]}">{plural_label(stars, "star")}</tspan> · '
        f'{esc(fmt_num(forks))} <tspan font-size="12" font-weight="500" fill="{t["text_muted"]}">{plural_label(forks, "fork")}</tspan></text>'
    )
    parts.append(
        f'<text x="{kx_list[2]}" y="{py + 80}" class="t-body" font-size="11.5">'
        f'<tspan class="t-strong">{esc(fmt_num(contributors))}</tspan> {plural_label(contributors, "contributor")} · '
        f'{esc(fmt_num(release_count))} {plural_label(release_count, "release")} · {esc(fmt_num(issues))} issues</text>'
    )

    parts.append(f'<line x1="{px + 20}" y1="{py + 96}" x2="{px + pw - 20}" y2="{py + 96}" stroke="{t["hairline"]}" stroke-width="1"/>')

    # Row 2: Platform Breakdown Bars
    parts.append(f'<text x="{px + 22}" y="{py + 116}" class="t-lbl">PLATFORM DISTRIBUTION (ZERO DOUBLE-COUNTING)</text>')
    styles = _platform_style_map(t)
    product_rows = [p for p in platforms if p.get("category") != "auxiliary"]
    max_plat = max((int(p.get("downloads") or 0) for p in product_rows), default=1) or 1

    ry = py + 136
    bar_x = px + 196.0
    bar_max_w = 278.0
    for idx, plat in enumerate(product_rows[:4]):
        pname = str(plat.get("name") or "")
        pdls = int(plat.get("downloads") or 0)
        ppct = float(plat.get("share_of_product_pct") or 0.0)
        color = str(styles.get(pname, {"color": t["primary"]})["color"])
        bw = (pdls / max_plat) * bar_max_w if pdls > 0 else 0.0

        parts.append(f'<circle cx="{px + 28}" cy="{ry + 6}" r="4.5" fill="{color}"/>')
        parts.append(f'<text x="{px + 42}" y="{ry + 10}" class="t-strong" font-size="12.5">{esc(pname)}</text>')
        parts.append(
            f'<rect x="{bar_x:.0f}" y="{ry}" width="{bar_max_w:.0f}" height="10" rx="4" '
            f'fill="{t["track"]}" stroke="{t["hairline"]}" stroke-width="1"/>'
        )
        parts.append(_animated_bar(bar_x, ry, bw, 10, 4, color, animated, delay_s=0.1 * idx))
        parts.append(
            f'<text x="{px + pw - 22}" y="{ry + 10}" class="t-mono" font-size="11.5" text-anchor="end" fill="{t["text"]}">'
            f'{esc(fmt_num(pdls))} <tspan fill="{t["text_dim"]}">({ppct:.1f}%)</tspan></text>'
        )
        ry += 28

    # Row 3: Honest Snapshot History Box
    bx, by, bw, bh = px + 20, py + 256, pw - 40, 88
    parts.append(
        f'<rect x="{bx}" y="{by}" width="{bw}" height="{bh}" rx="8" '
        f'fill="{t["surface_elev"]}" stroke="{t["hairline"]}" stroke-width="1"/>'
    )

    if not snap_state["has_trajectory"]:
        # Honest baseline display (covers <2 snapshots OR <24h / zero-variance checkpoints)
        n_snaps = snap_state["count"]
        parts.append(
            f'<text x="{bx + 16}" y="{by + 24}" class="t-val" font-size="13.5" fill="{t["primary"]}">'
            f'Baseline Recording Active</text>'
        )
        parts.append(
            f'<text x="{bx + 16}" y="{by + 42}" class="t-body" font-size="11.5">'
            f'Tracking started {esc(started_str)} ({n_snaps} {plural_label(n_snaps, "checkpoint")} recorded). '
            f'24h / 7d / 30d deltas appear once real span covers >= 24h.</text>'
        )
        track_x, track_y, track_w = bx + 16, by + 62, bw - 32
        parts.append(
            f'<line x1="{track_x}" y1="{track_y}" x2="{track_x + track_w}" y2="{track_y}" '
            f'stroke="{t["hairline_strong"]}" stroke-width="1.5" stroke-dasharray="4 4"/>'
        )
        parts.append(f'<circle cx="{track_x + 4}" cy="{track_y}" r="4.5" fill="{t["primary"]}"/>')
        tick_cls = ' class="scan-tick"' if animated else ""
        parts.append(
            f'<rect x="{track_x + 26}" y="{track_y - 3.5}" width="32" height="7" rx="3.5" '
            f'fill="{t["glow"]}" opacity="0.85"{tick_cls}/>'
        )
        parts.append(
            f'<text x="{track_x + track_w}" y="{by + 78}" class="t-mono" font-size="9.5" '
            f'text-anchor="end" fill="{t["text_dim"]}">NO SYNTHETIC HISTORY</text>'
        )
    else:
        series = snap_state["series"]
        vals = [int(s.get("total_product_downloads") or 0) for s in series]
        v_min, v_max = min(vals), max(vals)
        span = max(1, v_max - v_min)
        pad_l, pad_r, pad_t, pad_b = 48, 16, 18, 22
        cx0, cy0 = bx + pad_l, by + pad_t
        cw, ch = bw - pad_l - pad_r, bh - pad_t - pad_b

        for frac in (0.0, 1.0):
            gy = cy0 + ch * (1.0 - frac)
            gv = int(round(v_min + span * frac))
            parts.append(f'<line x1="{cx0}" y1="{gy:.1f}" x2="{cx0 + cw}" y2="{gy:.1f}" stroke="{t["hairline"]}" stroke-width="1"/>')
            parts.append(
                f'<text x="{cx0 - 6}" y="{gy + 3.5:.1f}" class="t-mono" font-size="9.5" '
                f'text-anchor="end" fill="{t["text_dim"]}">{esc(fmt_num(gv))}</text>'
            )

        coords: list[tuple[float, float]] = []
        n_pts = len(series)
        for i, val in enumerate(vals):
            px_i = cx0 + (cw * i / max(1, n_pts - 1))
            py_i = cy0 + ch - ((val - v_min) / span) * ch
            coords.append((px_i, py_i))

        line_d = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in coords)
        area_d = f"{line_d} L{coords[-1][0]:.1f},{cy0 + ch:.1f} L{coords[0][0]:.1f},{cy0 + ch:.1f} Z"
        parts.append(f'<path d="{area_d}" fill="{t["glow"]}" opacity="0.18"/>')
        parts.append(
            f'<path d="{line_d}" fill="none" stroke="{t["primary"]}" stroke-width="2.4" '
            f'stroke-linecap="round" stroke-linejoin="round"/>'
        )
        lx, ly = coords[-1]
        parts.append(f'<circle cx="{lx:.1f}" cy="{ly:.1f}" r="4" fill="{t["glow"]}" stroke="{t["surface"]}" stroke-width="1.5"/>')
        first_lbl = str(series[0]["timestamp"])[:10]
        last_lbl = str(series[-1]["timestamp"])[:10]
        parts.append(
            f'<text x="{cx0}" y="{by + bh - 6}" class="t-mono" font-size="9.5" fill="{t["text_dim"]}">'
            f'{esc(first_lbl)} ({n_pts} real samples)</text>'
        )
        parts.append(
            f'<text x="{cx0 + cw}" y="{by + bh - 6}" class="t-mono" font-size="9.5" text-anchor="end" '
            f'fill="{t["text_dim"]}">{esc(last_lbl)}</text>'
        )

    # Footer
    parts.append(
        f'<text x="36" y="{h - 14}" class="t-mono" font-size="10.5" fill="{t["text_dim"]}">'
        f'Source: data/analytics/downloads.json  ·  GPL-3.0</text>'
    )
    parts.append(
        f'<text x="{w - 36}" y="{h - 14}" class="t-mono" font-size="10.5" text-anchor="end" fill="{t["text_dim"]}">'
        f'Updated every 6h  ·  Last synced {esc(synced_str)}</text>'
    )
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def render_platform_breakdown(data: dict[str, Any], theme_name: str, animated: bool = True) -> str:
    """Render the Pelagic Velocity platform & release manifest SVG."""
    t = THEMES[theme_name]
    w, h = 1120, 444
    summary = data.get("summary") or {}
    platforms = data.get("platforms") or []
    releases = data.get("releases") or []
    synced_str = fmt_timestamp(data.get("last_successful_api_sync_at") or data.get("last_updated_at"))
    total_all = int(summary.get("total_all_assets") or 0)
    total_product = int(summary.get("total_product_downloads") or 0)

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
        _common_style(t, animated),
        f'<rect width="{w}" height="{h}" rx="14" fill="{t["bg"]}"/>',
        f'<text x="36" y="34" class="t-mono" font-size="11.5" font-weight="600" letter-spacing="0.06em" '
        f'fill="{t["primary"]}">PLATFORM &amp; RELEASE MANIFEST</text>',
        f'<text x="{w - 36}" y="34" class="t-mono" font-size="10.5" text-anchor="end" fill="{t["text_dim"]}">'
        f'PRODUCT {fmt_num(total_product)}  ·  ALL ASSETS {fmt_num(total_all)}  ·  ZERO DOUBLE-COUNTING</text>',
        f'<line x1="36" y1="50" x2="{w - 36}" y2="50" stroke="{t["hairline"]}" stroke-width="1"/>',
    ]

    # Left panel: All 5 Platform Buckets
    parts.append(
        f'<rect x="36" y="68" width="516" height="332" rx="12" fill="{t["surface"]}" '
        f'stroke="{t["hairline_strong"]}" stroke-width="1.2"/>'
    )
    parts.append(f'<text x="58" y="96" class="t-lbl">DOWNLOADS BY TARGET BUCKET</text>')

    bucket_file_counts: dict[str, int] = {}
    for rel in releases:
        for asset in rel.get("assets") or []:
            plat = str(asset.get("platform") or "Other / Auxiliary")
            bucket_file_counts[plat] = bucket_file_counts.get(plat, 0) + 1

    max_bucket = max((int(p.get("downloads") or 0) for p in platforms), default=1) or 1
    styles = _platform_style_map(t)

    by = 122
    for idx, plat in enumerate(platforms):
        pname = str(plat.get("name") or "")
        pdls = int(plat.get("downloads") or 0)
        share_all = float(plat.get("share_of_all_assets_pct") or 0.0)
        nfiles = bucket_file_counts.get(pname, 0)
        bw = (pdls / max_bucket) * 472.0 if pdls > 0 else 0.0
        tag_txt = "AUX" if plat.get("category") == "auxiliary" else "PROD"
        color = str(styles.get(pname, {"color": t["primary"]})["color"])

        parts.append(f'<text x="58" y="{by + 12}" class="t-strong" font-size="13">{esc(pname)}</text>')
        parts.append(
            f'<text x="242" y="{by + 12}" class="t-mono" font-size="10.5" fill="{t["text_dim"]}">'
            f'[{tag_txt} · {nfiles} {plural_label(nfiles, "file")}]</text>'
        )
        parts.append(
            f'<text x="530" y="{by + 12}" class="t-mono" font-size="12" text-anchor="end" fill="{t["text"]}">'
            f'{esc(fmt_num(pdls))} <tspan fill="{t["text_dim"]}">({share_all:.1f}%)</tspan></text>'
        )
        parts.append(
            f'<rect x="58" y="{by + 20}" width="472" height="10" rx="4" '
            f'fill="{t["track"]}" stroke="{t["hairline"]}" stroke-width="1"/>'
        )
        parts.append(_animated_bar(58, by + 20, bw, 10, 4, color, animated, delay_s=0.1 * idx))
        by += 52

    # Right panel: Releases Ledger + Top Packages
    parts.append(
        f'<rect x="572" y="68" width="512" height="332" rx="12" fill="{t["surface"]}" '
        f'stroke="{t["hairline_strong"]}" stroke-width="1.2"/>'
    )
    parts.append(f'<text x="594" y="96" class="t-lbl">RELEASES LEDGER &amp; TOP PACKAGES</text>')

    ry = 122
    for idx, rel in enumerate(releases[:3]):
        rtag = str(rel.get("tag_name") or "")
        rdate = str(rel.get("published_at") or "")[:10]
        rprod = int(rel.get("product_downloads") or 0)
        rall = int(rel.get("all_assets_downloads") or 0)
        badge = "LATEST" if idx == 0 else "RELEASE"
        badge_bg = t["badge_bg"] if idx == 0 else t["surface_elev"]
        badge_fg = t["badge_fg"] if idx == 0 else t["text_muted"]

        parts.append(
            f'<rect x="594" y="{ry - 11}" width="58" height="17" rx="4" fill="{badge_bg}" '
            f'stroke="{t["hairline"]}" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="623" y="{ry + 1}" class="t-mono" font-size="9.5" font-weight="600" '
            f'text-anchor="middle" fill="{badge_fg}">{badge}</text>'
        )
        parts.append(f'<text x="664" y="{ry + 2}" class="t-val" font-size="14">{esc(rtag)}</text>')
        parts.append(f'<text x="738" y="{ry + 2}" class="t-mono" font-size="11" fill="{t["text_dim"]}">{esc(rdate)}</text>')
        parts.append(
            f'<text x="1062" y="{ry + 2}" class="t-mono" font-size="12" text-anchor="end" fill="{t["primary"]}">'
            f'{esc(fmt_num(rprod))} prod <tspan fill="{t["text_dim"]}">/ {esc(fmt_num(rall))} total</tspan></text>'
        )
        ry += 26

    parts.append(f'<line x1="594" y1="{ry + 2}" x2="1062" y2="{ry + 2}" stroke="{t["hairline"]}" stroke-width="1"/>')
    parts.append(f'<text x="594" y="{ry + 22}" class="t-lbl">TOP DOWNLOADED PRODUCT PACKAGES</text>')

    ay = ry + 42
    max_asset_dl = max((int(a.get("download_count") or 0) for a in top_assets), default=1) or 1
    for idx, asset in enumerate(top_assets):
        aname = str(asset.get("name") or "")
        if len(aname) > 33:
            aname = aname[:30] + "..."
        adl = int(asset.get("download_count") or 0)
        asize = fmt_bytes(int(asset.get("size") or 0))
        aw = (adl / max_asset_dl) * 108.0 if adl > 0 else 0.0

        parts.append(f'<text x="594" y="{ay}" class="t-mono" font-size="11" fill="{t["text"]}">{esc(aname)}</text>')
        parts.append(f'<text x="854" y="{ay}" class="t-mono" font-size="10.5" fill="{t["text_dim"]}">{esc(asize)}</text>')
        parts.append(
            f'<rect x="916" y="{ay - 8.5}" width="108" height="9" rx="3" '
            f'fill="{t["track"]}" stroke="{t["hairline"]}" stroke-width="1"/>'
        )
        parts.append(_animated_bar(916, ay - 8.5, aw, 9, 3, t["primary"], animated, delay_s=0.08 * idx))
        parts.append(
            f'<text x="1062" y="{ay}" class="t-mono" font-size="11.5" text-anchor="end" fill="{t["text"]}">'
            f'{esc(fmt_num(adl))}</text>'
        )
        ay += 24

    # Footer
    parts.append(
        f'<text x="36" y="{h - 16}" class="t-body" font-size="11">'
        f'Product = installers, portable archives, Linux packages, experimental macOS .dmg, browser extensions. '
        f'Auxiliary = symbols, .zsync, checksums, geo.</text>'
    )
    parts.append(
        f'<text x="{w - 36}" y="{h - 16}" class="t-mono" font-size="10.5" text-anchor="end" fill="{t["text_dim"]}">'
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
    total_aux = int(summary.get("total_auxiliary_downloads") or (total_all - total_product))
    latest_tag = summary.get("latest_release_tag") or "-"
    latest_dl = int(summary.get("latest_release_downloads") or 0)
    latest_pub = (summary.get("latest_release_published_at") or "")[:10] or "-"
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

    daily = summary.get("daily_delta")
    weekly = summary.get("weekly_delta")
    monthly = summary.get("monthly_delta")
    if daily is None and weekly is None and monthly is None:
        delta_summary = "Baseline recording active (awaiting >= 24h real snapshot span)"
    else:
        d_txt = f"+{fmt_num(daily)}" if daily is not None else "pending"
        w_txt = f"+{fmt_num(weekly)}" if weekly is not None else "pending"
        m_txt = f"+{fmt_num(monthly)}" if monthly is not None else "pending"
        delta_summary = f"24h: {d_txt} · 7d: {w_txt} · 30d: {m_txt}"

    lines = [
        "| Metric | Value |",
        "| :--- | :--- |",
        f"| **Product & Extension Downloads** | **{fmt_num(total_product)}** (excludes {fmt_num(total_aux)} auxiliary files; zero double-counting) |",
        f"| **All Release Assets Total** (incl. symbols, `.zsync`, checksums, geo) | {fmt_num(total_all)} |",
        f"| **Latest Release** | `{latest_tag}` ({fmt_num(latest_dl)} product downloads · {latest_pub}) |",
        f"| **Growth Windows** | {delta_summary} |",
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
            else "n/a"
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


def _find_browser_bin() -> str | None:
    env_bin = (os.environ.get("CHROME_BIN") or os.environ.get("PUPPETEER_EXECUTABLE_PATH") or "").strip()
    if env_bin and Path(env_bin).exists():
        return env_bin
    candidates = [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
    ]
    for cand in candidates:
        if Path(cand).exists():
            return cand
    for cmd in ("google-chrome", "chromium", "chromium-browser"):
        found = shutil.which(cmd)
        if found:
            return found
    return None


def velocity_webp_ready(velocity_dir: Path = VELOCITY_DIR) -> bool:
    """Return True if Node, FFmpeg, Browser, and ``velocity/build.mjs`` are available."""
    return bool(
        shutil.which("node")
        and shutil.which("ffmpeg")
        and _find_browser_bin()
        and (velocity_dir / "build.mjs").is_file()
        and (velocity_dir / "node_modules").is_dir()
    )


def rebuild_living_adoption_webp(
    out_dir: Path = DEFAULT_OUT_DIR,
    velocity_dir: Path = VELOCITY_DIR,
    strict: bool = False,
) -> bool:
    """Rebuild ``assets/readme/analytics/living-adoption-{light,dark}.webp`` (and static PNGs) via Velocity."""
    if not velocity_webp_ready(velocity_dir):
        if strict:
            raise RuntimeError(
                "Velocity WebP dependencies missing (requires node, ffmpeg, Chrome/Chromium, and velocity/node_modules)"
            )
        return False

    env = dict(os.environ)
    browser_bin = _find_browser_bin()
    if browser_bin:
        env.setdefault("CHROME_BIN", browser_bin)

    subprocess.run(
        ["node", "build.mjs", "--only", "living-adoption"],
        cwd=velocity_dir,
        env=env,
        check=True,
    )
    # Keep static PNG fallbacks synchronized when Velocity emits keyframes.
    review_dir = (
        Path(r"C:\Users\subha\.gemini\antigravity\brain\22023cbd-5916-4595-8b3f-30b158a4c07c\scratch\review\living-adoption")
    )
    for theme in ("light", "dark"):
        keyframe = review_dir / f"living-adoption-{theme}-keyframe.png"
        static_dst = out_dir / f"living-adoption-static-{theme}.png"
        if keyframe.is_file():
            shutil.copy2(keyframe, static_dst)
    return True


def generate_all(
    input_path: Path = DEFAULT_INPUT,
    out_dir: Path = DEFAULT_OUT_DIR,
    readme_path: Path | None = DEFAULT_README,
    with_webp: bool = False,
    with_webp_if_available: bool = False,
) -> list[Path]:
    """Read ``input_path``, write all 6 analytics SVGs into ``out_dir``, and update README markers."""
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

    if with_webp or with_webp_if_available:
        if rebuild_living_adoption_webp(out_dir=out_dir, strict=with_webp):
            for theme in ("light", "dark"):
                for name in (f"living-adoption-{theme}.webp", f"living-adoption-static-{theme}.png"):
                    p = out_dir / name
                    if p.is_file():
                        written.append(p)

    return written


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="Input downloads.json path")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR, help="Output directory for SVGs")
    parser.add_argument("--readme", type=Path, default=DEFAULT_README, help="Optional README.md to update between markers")
    parser.add_argument("--skip-readme", action="store_true", help="Do not update README.md marker block")
    parser.add_argument("--with-webp", action="store_true", help="Require and rebuild living-adoption-{light,dark}.webp via Velocity")
    parser.add_argument(
        "--with-webp-if-available",
        action="store_true",
        help="Rebuild living-adoption-{light,dark}.webp if Node, Chrome/Chromium, and FFmpeg are available",
    )
    args = parser.parse_args(argv)

    written = generate_all(
        input_path=args.input,
        out_dir=args.out_dir,
        readme_path=None if args.skip_readme else args.readme,
        with_webp=args.with_webp,
        with_webp_if_available=args.with_webp_if_available,
    )
    for path in written:
        print(f"generated {path} ({path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
