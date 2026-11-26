#!/usr/bin/env python3
"""Procedural Motion Graphics & Artwork Generator for DecoyINDIA/BMD README.

Generates light & dark SVG artwork pairs (GitHub <img>-safe: pure SVG + SMIL +
CSS @keyframes, zero JavaScript, zero external fonts/images, <foreignObject>-free)
and frames real unretouched BMD screenshots via ffmpeg when available.

Usage:
    python scripts/readme/generate_artwork.py
    python scripts/readme/generate_artwork.py --svgs-only
"""
from __future__ import annotations

import argparse
import html
import json
import math
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "assets" / "readme"

FONT_DISPLAY = "'Space Grotesk', 'Segoe UI', system-ui, -apple-system, sans-serif"
FONT_BODY = "'IBM Plex Sans', 'Segoe UI', system-ui, -apple-system, sans-serif"
FONT_MONO = "'IBM Plex Mono', 'Cascadia Mono', Consolas, ui-monospace, monospace"

THEMES: dict[str, dict[str, str]] = {
    "light": {
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
        "go_hover": "#47b94a",
        "go_ink": "#08200b",
        "go_edge": "#2f8f34",
        "link": "#0d58c9",
        "stamp": "#1b7a26",
        "shadow": "#06163c",
        "track_bg": "#e4e9f2",
        "inset_bg": "#133f91",
        "inset_edge": "#2f6ad3",
        "idm_wait": "#8f97a6",
        "idm_run": "#434956",
        "warn": "#c97a16",
    },
    "dark": {
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
        "go_hover": "#47b94a",
        "go_ink": "#08200b",
        "go_edge": "#2f8f34",
        "link": "#86b6ff",
        "stamp": "#6bdb6e",
        "shadow": "#000000",
        "track_bg": "#1f2838",
        "inset_bg": "#071022",
        "inset_edge": "#1d3158",
        "idm_wait": "#697387",
        "idm_run": "#9aa4b8",
        "warn": "#e59a3a",
    },
}

# ISO/IEC 15417 Code 128 Set B patterns (matching tools/website/barcode.py)
CODE128_PATTERNS = (
    "212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 221312 231212 112232 "
    "122132 122231 113222 123122 123221 223211 221132 221231 213212 223112 312131 311222 321122 "
    "321221 312212 322112 322211 212123 212321 232121 111323 131123 131321 112313 132113 132311 "
    "211313 231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 231131 213113 "
    "213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 111422 "
    "121124 121421 141122 141221 112214 112412 122114 122411 142112 142211 241211 221114 413111 "
    "241112 134111 111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 214121 "
    "412121 111143 111341 131141 114113 114311 411113 411311 113141 114131 311141 411131 211412 "
    "211214 211232 2331112"
).split()

# Exact Black Marlin icon paths from art/BMD Black Marlin Icon.svg (480x480 space)
MARLIN_PATHS = {
    "dorsal": "M125 118C177 88 255 83 312 111C238 158 155 217 128 293C120 252 139 209 166 173C191 139 177 115 125 118Z",
    "body": "M437 15C387 67 362 96 344 125C357 113 371 105 386 99C359 124 339 151 330 180C346 208 354 241 346 274C344 256 339 240 330 229C327 298 278 360 205 387C191 393 176 397 158 399L130 356C167 344 213 354 239 308C263 266 244 219 204 211C169 204 140 235 128 293C119 240 167 179 221 150C252 133 273 119 309 109C352 98 394 48 437 15Z",
    "belly": "M223 179C261 148 306 162 326 191C344 215 351 244 346 274C343 248 332 229 311 215C287 198 252 184 223 179Z",
    "tail": "M49 295C103 296 136 323 176 341C205 354 229 351 255 337C232 360 194 374 165 394C145 414 132 438 124 462C122 423 148 395 133 367C114 337 81 306 49 295Z",
}


def esc(text: str) -> str:
    return html.escape(text, quote=True)


def code128_svg_path(text: str, x0: float, y0: float, total_w: float, bar_h: float) -> tuple[str, float]:
    """Return an SVG path `d` for a scannable Code 128 Set B barcode scaled to `total_w`."""
    values = [ord(c) - 32 for c in text]
    check = (104 + sum((i + 1) * v for i, v in enumerate(values))) % 103
    syms = [104, *values, check, 106]
    total_modules = 20 + sum(sum(int(ch) for ch in CODE128_PATTERNS[s]) for s in syms)
    mod_w = total_w / total_modules
    x = 10.0
    bars: list[str] = []
    for s in syms:
        for k, w_ch in enumerate(CODE128_PATTERNS[s]):
            w = int(w_ch)
            if k % 2 == 0:
                bx = x0 + x * mod_w
                bw = w * mod_w
                bars.append(f"M{bx:.2f} {y0:.2f}h{bw:.2f}v{bar_h:.2f}h-{bw:.2f}z")
            x += w
    return "".join(bars), total_w


def marlin_defs(prefix: str) -> str:
    return f"""
    <linearGradient id="{prefix}-dorsal" x1="161" y1="110" x2="185" y2="269" gradientUnits="userSpaceOnUse">
      <stop stop-color="#80dbad"/>
      <stop offset=".29" stop-color="#55c99d"/>
      <stop offset=".67" stop-color="#187052"/>
      <stop offset="1" stop-color="#369d77"/>
    </linearGradient>
    <linearGradient id="{prefix}-tail" x1="81" y1="358" x2="204" y2="438" gradientUnits="userSpaceOnUse">
      <stop stop-color="#66d2a5"/>
      <stop offset=".63" stop-color="#359a75"/>
      <stop offset="1" stop-color="#187052"/>
    </linearGradient>"""


def marlin_group(prefix: str, x: float, y: float, scale: float, draw_anim: bool = False) -> str:
    body_cls = ' class="marlin-draw"' if draw_anim else ""
    fill_cls = ' class="marlin-fill"' if draw_anim else ""
    stroke_outline = (
        f'<path{body_cls} fill="none" stroke="#9be9c3" stroke-width="7" stroke-linecap="round" '
        f'stroke-linejoin="round" d="{MARLIN_PATHS["body"]}"/>'
        if draw_anim
        else ""
    )
    return f"""  <g transform="translate({x} {y}) scale({scale:.4f})">
    <g{fill_cls}>
      <path fill="url(#{prefix}-dorsal)" d="{MARLIN_PATHS['dorsal']}"/>
      <path fill="#c1f0d6" stroke="#187052" stroke-width="5" stroke-opacity=".72" stroke-linejoin="round" paint-order="stroke" d="{MARLIN_PATHS['body']}"/>
      <path fill="#eef7f2" d="{MARLIN_PATHS['belly']}"/>
      <path fill="url(#{prefix}-tail)" d="{MARLIN_PATHS['tail']}"/>
      <circle cx="325" cy="119" r="4.2" fill="#14211d"/>
    </g>
    {stroke_outline}
  </g>"""


def board_backdrop(w: int, h: int, t: dict[str, str], pid: str, rx: int = 16, grid: bool = True, deep: bool = False) -> tuple[str, str]:
    c_top = t["board_deep"] if deep else t["board"]
    c_bot = t["board_deep"]
    defs = f"""
    <linearGradient id="{pid}-bg" x1="0" y1="0" x2="0.2" y2="1">
      <stop offset="0%" stop-color="{c_top}"/>
      <stop offset="100%" stop-color="{c_bot}"/>
    </linearGradient>"""
    parts = [f'<rect width="{w}" height="{h}" rx="{rx}" fill="url(#{pid}-bg)"/>']
    if grid:
        grid_lines = []
        for gx in range(40, w, 40):
            grid_lines.append(f"M{gx} 0v{h}")
        for gy in range(40, h, 40):
            grid_lines.append(f"M0 {gy}h{w}")
        parts.append(f'<path d="{"".join(grid_lines)}" stroke="{t["board_edge"]}" stroke-width="1" stroke-opacity="0.22"/>')
        # Subtle corner registration marks
        marks = [
            f"M16 24h12M22 18v12",
            f"M{w-28} 24h12M{w-22} 18v12",
            f"M16 {h-24}h12M22 {h-30}v12",
            f"M{w-28} {h-24}h12M{w-22} {h-30}v12",
        ]
        parts.append(f'<path d="{"".join(marks)}" stroke="{t["on_board_2"]}" stroke-width="1.2" stroke-opacity="0.35" stroke-linecap="round"/>')
    parts.append(f'<rect x="1" y="1" width="{w-2}" height="{h-2}" rx="{max(2, rx-1)}" fill="none" stroke="{t["board_edge"]}" stroke-width="1.5"/>')
    return defs, "\n  ".join(parts)


def paper_sheet(x: float, y: float, w: float, h: float, t: dict[str, str], rx: int = 12, tilt: float = 0.0, lift: bool = True) -> str:
    cx, cy = x + w / 2, y + h / 2
    tf = f' transform="rotate({tilt} {cx:.1f} {cy:.1f})"' if tilt else ""
    dy1, op1 = (2, 0.22) if lift else (1, 0.16)
    dy2, op2 = (14, 0.34) if lift else (8, 0.24)
    return f"""  <g{tf}>
    <rect x="{x}" y="{y+dy2}" width="{w}" height="{h}" rx="{rx}" fill="{t['shadow']}" fill-opacity="{op2}"/>
    <rect x="{x}" y="{y+dy1}" width="{w}" height="{h}" rx="{rx}" fill="{t['shadow']}" fill-opacity="{op1}"/>
    <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{t['paper']}"/>
  </g>"""


def rubber_stamp(x: float, y: float, text: str, t: dict[str, str], tilt: float = -7.0, cls: str = "", w: float | None = None) -> str:
    sw = w if w is not None else max(142, len(text) * 10.6 + 28)
    sh = 38
    cx, cy = x + sw / 2, y + sh / 2
    cls_attr = f' class="{cls}"' if cls else ""
    return f"""  <g{cls_attr} transform="rotate({tilt} {cx:.1f} {cy:.1f})">
    <rect x="{x}" y="{y}" width="{sw}" height="{sh}" rx="6" fill="{t['paper']}" fill-opacity="0.94" stroke="{t['stamp']}" stroke-width="2.5"/>
    <rect x="{x-3}" y="{y-3}" width="{sw+6}" height="{sh+6}" rx="8" fill="none" stroke="{t['stamp']}" stroke-width="1.4" stroke-opacity="0.85"/>
    <text x="{cx:.1f}" y="{y+24}" text-anchor="middle" fill="{t['stamp']}" font-family="{FONT_DISPLAY}" font-size="15.5" font-weight="800" letter-spacing="1.1">{esc(text)}</text>
  </g>"""


def svg_wrap(w: int, h: int, title: str, desc: str, defs: str, style: str, body: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" aria-labelledby="svg-title svg-desc">
  <title id="svg-title">{esc(title)}</title>
  <desc id="svg-desc">{esc(desc)}</desc>
  <defs>{defs}
  </defs>
  <style>
    .t-disp {{ font-family: {FONT_DISPLAY}; }}
    .t-body {{ font-family: {FONT_BODY}; }}
    .t-mono {{ font-family: {FONT_MONO}; font-variant-numeric: tabular-nums; }}
    .tnum {{ font-variant-numeric: tabular-nums; }}
{style}
  </style>
{body}
</svg>
"""


def write_svg(rel_path: str, content: str) -> Path:
    path = OUT / rel_path
    path.parent.mkdir(parents=True, exist_ok=True)
    # Validate XML before writing
    ET.fromstring(content.encode("utf-8"))
    path.write_text(content, encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# 1. HERO WAYBILL (1200 x 520)
# ---------------------------------------------------------------------------
def build_hero_waybill(mode: str) -> str:
    t = THEMES[mode]
    pid = f"hero-{mode}"
    defs, backdrop = board_backdrop(1200, 520, t, pid, rx=16, grid=True)
    defs += marlin_defs(pid)
    defs += f"""
    <clipPath id="{pid}-wb-clip"><rect x="676" y="42" width="474" height="434" rx="12"/></clipPath>"""

    # 8-lane live telemetry bars (staggered progress filling smoothly in Dispatch Green)
    lane_specs = [
        ("W1", 0.00, 0.28, "128 MiB"),
        ("W2", 0.04, 0.25, "134 MiB"),
        ("W3", 0.02, 0.31, "122 MiB"),
        ("W4", 0.06, 0.27, "126 MiB"),
        ("W5", 0.03, 0.24, "138 MiB"),
        ("W6", 0.07, 0.30, "119 MiB"),
        ("W7", 0.05, 0.26, "131 MiB"),
        ("W8", 0.08, 0.29, "126 MiB"),
    ]
    lane_rows = []
    lane_css = []
    for idx, (lbl, delay, dur_frac, chunk) in enumerate(lane_specs):
        ly = 302 + idx * 17
        lw = 442
        start_pct = round(8 + delay * 120)
        end_pct = round(start_pct + dur_frac * 155)
        lane_rows.append(
            f'<text x="64" y="{ly+9}" fill="{t["on_board_2"]}" font-size="10.5" font-weight="600" class="t-mono">{lbl}</text>'
            f'<rect x="92" y="{ly}" width="{lw}" height="9" rx="3" fill="{t["board_edge"]}" fill-opacity="0.45"/>'
            f'<rect class="seg-fill seg-{idx}" x="92" y="{ly}" width="{lw}" height="9" rx="3" fill="{t["go"]}"/>'
            f'<text x="546" y="{ly+9}" fill="{t["on_board_2"]}" font-size="10" class="t-mono" opacity="0.88">{chunk}</text>'
        )
        lane_css.append(
            f"@keyframes seg{idx} {{ 0%, {start_pct}% {{ transform: scaleX(0.02); }} {end_pct}%, 92% {{ transform: scaleX(1); }} 100% {{ transform: scaleX(0.02); }} }}\n"
            f"    .seg-{idx} {{ transform-box: fill-box; transform-origin: left center; animation: seg{idx} 7.5s cubic-bezier(.22,.8,.36,1) infinite; }}"
        )

    # Waybill tilted card (rotated -1.7 deg)
    wb_x, wb_y, wb_w, wb_h = 676, 42, 474, 434
    bc_d, _ = code128_svg_path("BMD 1.0.0", wb_x + 22, wb_y + 340, 232, 46)

    style = f"""
    .marlin-draw {{
      stroke-dasharray: 1850;
      stroke-dashoffset: 0;
      animation: draw-marlin 1.8s ease-out 1;
    }}
    .marlin-fill {{
      animation: fill-marlin 1.8s ease-out 1;
    }}
    .stamp-pop {{
      transform-box: fill-box;
      transform-origin: center;
      animation: stamp-land 7.5s cubic-bezier(.2,.9,.3,1) infinite;
    }}
    .scan-beam {{
      animation: scan-glide 7.5s ease-in-out infinite;
    }}
    @keyframes draw-marlin {{
      0% {{ stroke-dashoffset: 1850; opacity: 1; }}
      65% {{ stroke-dashoffset: 0; opacity: 1; }}
      100% {{ stroke-dashoffset: 0; opacity: 0; }}
    }}
    @keyframes fill-marlin {{
      0%, 45% {{ opacity: 0.12; }}
      85%, 100% {{ opacity: 1; }}
    }}
    @keyframes stamp-land {{
      0%, 48% {{ opacity: 0; transform: scale(1.28) rotate(-7deg); }}
      54%, 92% {{ opacity: 1; transform: scale(1) rotate(-7deg); }}
      98%, 100% {{ opacity: 0; transform: scale(1) rotate(-7deg); }}
    }}
    @keyframes scan-glide {{
      0%, 44% {{ transform: translateX(0px); opacity: 0; }}
      48% {{ opacity: 0.85; }}
      66% {{ transform: translateX(200px); opacity: 0.85; }}
      70%, 100% {{ transform: translateX(200px); opacity: 0; }}
    }}
    {chr(10).join(lane_css)}
    @media (prefers-reduced-motion: reduce) {{
      .marlin-draw {{ animation: none; opacity: 0; }}
      .marlin-fill, .stamp-pop, .seg-fill {{ animation: none !important; opacity: 1 !important; transform: none !important; }}
      .stamp-pop {{ transform: rotate(-7deg) !important; }}
      .scan-beam {{ animation: none; opacity: 0; }}
    }}"""

    pills = [
        ("WINDOWS 10/11", 46, 114),
        ("LINUX", 168, 66),
        ("MACOS (EXP)", 242, 102),
        ("6 BROWSERS", 352, 98),
        ("MCP + REMOTE", 458, 108),
    ]
    pill_svg = []
    for label, px, pw in pills:
        pill_svg.append(
            f'<rect x="{px}" y="456" width="{pw}" height="24" rx="5" fill="{t["inset_bg"]}" stroke="{t["board_edge"]}" stroke-width="1"/>'
            f'<text x="{px + pw/2:.1f}" y="472" text-anchor="middle" fill="{t["on_board_2"]}" font-size="10.5" font-weight="700" letter-spacing="0.6" class="t-disp">{label}</text>'
        )

    body = f"""  {backdrop}

  <!-- Left Editorial Zone on Board -->
  <text x="48" y="42" fill="{t['on_board_2']}" font-size="11.5" font-weight="600" letter-spacing="1.4" class="t-mono">CARRIER: DECOYINDIA/BMD  ·  RUST 1.98  ·  GPL-3.0  ·  86 LANGUAGES</text>

  {marlin_group(pid, 44, 62, 0.25, draw_anim=True)}

  <text x="178" y="114" fill="{t['on_board']}" font-size="44" font-weight="800" letter-spacing="-1.2" class="t-disp">BLACK MARLIN</text>
  <text x="180" y="156" fill="{t['on_board_2']}" font-size="25" font-weight="700" letter-spacing="2.4" class="t-disp">DOWNLOADER</text>
  <rect x="402" y="136" width="76" height="24" rx="5" fill="{t['ink']}" stroke="{t['board_edge']}" stroke-width="1.2"/>
  <text x="440" y="152" text-anchor="middle" fill="{t['paper']}" font-size="12" font-weight="800" class="t-mono">v1.0.0</text>

  <text x="48" y="206" fill="{t['on_board']}" font-size="22" font-weight="700" class="t-disp">A download is a delivery.</text>
  <text x="48" y="232" fill="{t['on_board_2']}" font-size="14.5" class="t-body">The desktop workflow you already know — rebuilt in Rust with dynamic re-splitting.</text>

  <!-- 8-Lane Live Telemetry Panel -->
  <rect x="46" y="256" width="584" height="184" rx="10" fill="{t['inset_bg']}" stroke="{t['inset_edge']}" stroke-width="1.2"/>
  <text x="64" y="280" fill="{t['on_board']}" font-size="11.5" font-weight="700" letter-spacing="1.1" class="t-disp">LIVE SEGMENT DISPATCH  ·  8 PARALLEL WORKERS</text>
  <text x="612" y="280" text-anchor="end" fill="{t['go']}" font-size="11.5" font-weight="700" class="t-mono">1 GiB  ·  1.01 s MEDIAN</text>
  <line x1="64" y1="290" x2="612" y2="290" stroke="{t['board_edge']}" stroke-opacity="0.5" stroke-width="1"/>
  {"".join(lane_rows)}

  {"".join(pill_svg)}

  <!-- Right Tilted Thermal-Paper Waybill -->
  <g transform="rotate(-1.7 {wb_x + wb_w/2:.1f} {wb_y + wb_h/2:.1f})">
    <rect x="{wb_x}" y="{wb_y+14}" width="{wb_w}" height="{wb_h}" rx="12" fill="{t['shadow']}" fill-opacity="0.34"/>
    <rect x="{wb_x}" y="{wb_y+2}" width="{wb_w}" height="{wb_h}" rx="12" fill="{t['shadow']}" fill-opacity="0.22"/>
    <rect x="{wb_x}" y="{wb_y}" width="{wb_w}" height="{wb_h}" rx="12" fill="{t['paper']}"/>
    <g clip-path="url(#{pid}-wb-clip)">
      <rect x="{wb_x}" y="{wb_y}" width="{wb_w}" height="50" fill="{t['ink']}"/>
      <text x="{wb_x+24}" y="{wb_y+31}" fill="{t['paper']}" font-size="14.5" font-weight="800" letter-spacing="0.8" class="t-disp">WAYBILL  ·  PARCEL #BMD-1.0.0</text>
      <text x="{wb_x+wb_w-24}" y="{wb_y+30}" text-anchor="end" fill="{t['paper_2']}" font-size="11" font-weight="600" class="t-mono">DECOYINDIA</text>
    </g>

    <text x="{wb_x+26}" y="{wb_y+94}" fill="{t['ink']}" font-size="27" font-weight="800" letter-spacing="-0.6" class="t-disp">5.41&#215; faster than IDM 6.42</text>
    <text x="{wb_x+26}" y="{wb_y+120}" fill="{t['ink_2']}" font-size="13.5" class="t-body">Zero trial. Zero ads. Atomic resume &amp; torrents built in.</text>

    <!-- Ruled 2x2 Fields -->
    <line x1="{wb_x+26}" y1="{wb_y+142}" x2="{wb_x+wb_w-26}" y2="{wb_y+142}" stroke="{t['ink']}" stroke-width="1.5"/>
    <line x1="{wb_x+237}" y1="{wb_y+142}" x2="{wb_x+237}" y2="{wb_y+294}" stroke="{t['ink']}" stroke-width="1.5"/>
    <line x1="{wb_x+26}" y1="{wb_y+218}" x2="{wb_x+wb_w-26}" y2="{wb_y+218}" stroke="{t['ink']}" stroke-width="1.5"/>
    <line x1="{wb_x+26}" y1="{wb_y+294}" x2="{wb_x+wb_w-26}" y2="{wb_y+294}" stroke="{t['ink']}" stroke-width="1.5"/>

    <text x="{wb_x+32}" y="{wb_y+164}" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.8" class="t-disp">POSTAGE</text>
    <text x="{wb_x+32}" y="{wb_y+194}" fill="{t['ink']}" font-size="18" font-weight="700" class="t-body tnum">Free &#183; GPL-3.0</text>

    <text x="{wb_x+252}" y="{wb_y+164}" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.8" class="t-disp">DELIVERY (1 GiB)</text>
    <text x="{wb_x+252}" y="{wb_y+194}" fill="{t['link']}" font-size="18" font-weight="700" class="t-body tnum">1.01 s <tspan fill="{t['ink_3']}" font-size="13" font-weight="400">vs 5.46 s</tspan></text>

    <text x="{wb_x+32}" y="{wb_y+240}" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.8" class="t-disp">SYSTEMS</text>
    <text x="{wb_x+32}" y="{wb_y+270}" fill="{t['ink']}" font-size="17" font-weight="600" class="t-body">Win &#183; Linux &#183; macOS</text>

    <text x="{wb_x+252}" y="{wb_y+240}" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.8" class="t-disp">RELEASE</text>
    <text x="{wb_x+252}" y="{wb_y+270}" fill="{t['ink']}" font-size="17" font-weight="600" class="t-mono">v1.0.0 &#183; 15 crates</text>

    <!-- Dashed Thermal Tear Line & Scannable Code 128 Barcode -->
    <line x1="{wb_x+26}" y1="{wb_y+322}" x2="{wb_x+wb_w-26}" y2="{wb_y+322}" stroke="{t['ink_3']}" stroke-width="1.6" stroke-dasharray="6 5"/>
    <path d="{bc_d}" fill="{t['ink']}"/>
    <rect class="scan-beam" x="{wb_x+28}" y="{wb_y+336}" width="3" height="54" rx="1.5" fill="{t['go']}"/>
    <text x="{wb_x+138}" y="{wb_y+404}" text-anchor="middle" fill="{t['ink_2']}" font-size="11.5" font-weight="600" letter-spacing="4" class="t-mono">BMD 1.0.0</text>

    <!-- Tilted Rubber Stamp -->
    <g class="stamp-pop">
      {rubber_stamp(wb_x + 240, wb_y + 340, "DELIVERED · 5.41×", t, tilt=-7.0, w=210)}
    </g>
  </g>"""

    return svg_wrap(
        1200,
        520,
        f"Black Marlin Downloader v1.0.0 Hero Waybill ({mode})",
        "Blue board hero featuring the Black Marlin silhouette, 8-lane live segment telemetry, and tilted thermal waybill with Code 128 barcode.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 2. DIVIDERS: waybill-tear & pulse-rule (1200 x 32 / 28)
# ---------------------------------------------------------------------------
def build_waybill_tear(mode: str) -> str:
    t = THEMES[mode]
    style = f"""
    .tear-scan {{ animation: tear-slide 6s ease-in-out infinite; }}
    @keyframes tear-slide {{
      0%, 100% {{ transform: translateX(0px); }}
      50% {{ transform: translateX(980px); }}
    }}
    @media (prefers-reduced-motion: reduce) {{
      .tear-scan {{ animation: none; }}
    }}"""
    body = f"""  <rect x="0" y="6" width="1200" height="20" rx="4" fill="{t['paper_2']}" stroke="{t['line']}" stroke-width="1"/>
  <circle cx="0" cy="16" r="7" fill="{t['board']}"/>
  <circle cx="1200" cy="16" r="7" fill="{t['board']}"/>
  <line x1="20" y1="16" x2="1180" y2="16" stroke="{t['ink_3']}" stroke-width="1.5" stroke-dasharray="7 6" stroke-opacity="0.65"/>
  <rect x="530" y="7" width="140" height="18" rx="4" fill="{t['paper']}" stroke="{t['line']}" stroke-width="1"/>
  <text x="600" y="19.5" text-anchor="middle" fill="{t['ink_3']}" font-size="9.5" font-weight="700" letter-spacing="1.8" class="t-mono">CUT / WAYBILL</text>
  <g class="tear-scan">
    <rect x="92" y="10" width="28" height="12" rx="3" fill="{t['board']}" fill-opacity="0.88"/>
    <circle cx="106" cy="16" r="2.2" fill="{t['on_board']}"/>
  </g>"""
    return svg_wrap(1200, 32, f"Thermal waybill tear divider ({mode})", "Dashed thermal perforation divider with moving scanner tick.", "", style, body)


def build_pulse_rule(mode: str) -> str:
    t = THEMES[mode]
    style = f"""
    .pulse-r {{ animation: pulse-right 5s ease-in-out infinite; }}
    .pulse-l {{ animation: pulse-left 5s ease-in-out infinite; }}
    @keyframes pulse-right {{
      0% {{ transform: translateX(0px); opacity: 0; }}
      20% {{ opacity: 1; }}
      80% {{ opacity: 1; }}
      100% {{ transform: translateX(520px); opacity: 0; }}
    }}
    @keyframes pulse-left {{
      0% {{ transform: translateX(0px); opacity: 0; }}
      20% {{ opacity: 1; }}
      80% {{ opacity: 1; }}
      100% {{ transform: translateX(-520px); opacity: 0; }}
    }}
    @media (prefers-reduced-motion: reduce) {{
      .pulse-r, .pulse-l {{ animation: none; opacity: 0; }}
    }}"""
    ticks = "".join(f'<line x1="{x}" y1="10" x2="{x}" y2="18" stroke="{t["line"]}" stroke-width="1"/>' for x in range(60, 1160, 60))
    body = f"""  <line x1="24" y1="14" x2="1176" y2="14" stroke="{t['line']}" stroke-width="1.5"/>
  {ticks}
  <polygon points="600,6 608,14 600,22 592,14" fill="{t['board']}" stroke="{t['board_edge']}" stroke-width="1"/>
  <circle class="pulse-r" cx="60" cy="14" r="3.5" fill="{t['link']}"/>
  <circle class="pulse-l" cx="1140" cy="14" r="3.5" fill="{t['link']}"/>"""
    return svg_wrap(1200, 28, f"Telemetry rule divider ({mode})", "Clean telemetry baseline divider with converging pulses.", "", style, body)


# ---------------------------------------------------------------------------
# 3. SEGMENT ENGINE ANIMATION (1120 x 420)
# ---------------------------------------------------------------------------
def build_segment_engine(mode: str) -> str:
    t = THEMES[mode]
    pid = f"segeng-{mode}"
    defs, backdrop = board_backdrop(1120, 420, t, pid, rx=16, grid=True)
    sheet = paper_sheet(24, 24, 1072, 372, t, rx=12, lift=False)
    defs += f'<clipPath id="{pid}-clip"><rect x="24" y="24" width="1072" height="372" rx="12"/></clipPath>'

    # 8 segment tracks inside center column (x=312..804)
    rows = []
    css_anim = []
    segs = [
        ("S0 · W1", "0–128M", 0.00, 0.42, False),
        ("S1 · W2", "128–256M", 0.02, 0.39, False),
        ("S2 · W3", "256–384M", 0.03, 0.45, False),
        ("S3 · W4", "384–512M", 0.01, 0.41, False),
        ("S4 · W5", "512–640M", 0.04, 0.44, False),
        ("S5 · W6→W2", "640–768M", 0.05, 0.62, True),   # stalled worker dropped & split!
        ("S6 · W7", "768–896M", 0.02, 0.43, False),
        ("S7 · W8", "896M–1G", 0.06, 0.47, False),
    ]
    for i, (name, rng, dly, dur, resplit) in enumerate(segs):
        ry = 148 + i * 27
        bar_x, bar_w = 430, 260
        p_start = int(6 + dly * 100)
        p_end = int(p_start + dur * 100)
        rows.append(
            f'<text x="322" y="{ry+13}" fill="{t["ink"]}" font-size="11.5" font-weight="700" class="t-mono">{name}</text>'
            f'<rect x="{bar_x}" y="{ry+2}" width="{bar_w}" height="14" rx="3" fill="{t["track_bg"]}" stroke="{t["line"]}" stroke-width="1"/>'
        )
        if resplit:
            # First half crawls in amber/warn then drops; second half is split & completed fast by W2 in Dispatch Green
            rows.append(
                f'<rect class="w-slow" x="{bar_x}" y="{ry+2}" width="92" height="14" rx="3" fill="{t["warn"]}"/>'
                f'<rect class="w-split" x="{bar_x+92}" y="{ry+2}" width="{bar_w-92}" height="14" rx="3" fill="{t["go"]}"/>'
                f'<line x1="{bar_x+92}" y1="{ry-1}" x2="{bar_x+92}" y2="{ry+19}" stroke="{t["ink"]}" stroke-width="1.5" stroke-dasharray="2 2"/>'
                f'<text x="{bar_x+bar_w+12}" y="{ry+13}" fill="{t["warn"]}" font-size="10.5" font-weight="700" class="t-mono">DROP + RE-SPLIT</text>'
            )
            css_anim.append(
                f"@keyframes wslow {{ 0%, 10% {{ transform: scaleX(0.05); }} 38%, 92% {{ transform: scaleX(1); }} 100% {{ transform: scaleX(0.05); }} }}\n"
                f"@keyframes wsplit {{ 0%, 40% {{ transform: scaleX(0.01); opacity: 0.2; }} 44% {{ opacity: 1; }} 68%, 92% {{ transform: scaleX(1); opacity: 1; }} 100% {{ transform: scaleX(0.01); opacity: 0.2; }} }}\n"
                f".w-slow {{ transform-box: fill-box; transform-origin: left center; animation: wslow 8.5s ease-out infinite; }}\n"
                f".w-split {{ transform-box: fill-box; transform-origin: left center; animation: wsplit 8.5s cubic-bezier(.2,.8,.3,1) infinite; }}"
            )
        else:
            rows.append(
                f'<rect class="w-fast wf-{i}" x="{bar_x}" y="{ry+2}" width="{bar_w}" height="14" rx="3" fill="{t["go"]}"/>'
                f'<text x="{bar_x+bar_w+12}" y="{ry+13}" fill="{t["ink_3"]}" font-size="10.5" class="t-mono">{rng}</text>'
            )
            css_anim.append(
                f"@keyframes wf{i} {{ 0%, {p_start}% {{ transform: scaleX(0.03); }} {p_end}%, 92% {{ transform: scaleX(1); }} 100% {{ transform: scaleX(0.03); }} }}\n"
                f".wf-{i} {{ transform-box: fill-box; transform-origin: left center; animation: wf{i} 8.5s cubic-bezier(.22,.8,.35,1) infinite; }}"
            )

    style = f"""
    .commit-stamp {{
      transform-box: fill-box;
      transform-origin: center;
      animation: c-stamp 8.5s cubic-bezier(.2,.9,.3,1) infinite;
    }}
    .pulse-arrow {{
      animation: arrow-pulse 8.5s linear infinite;
    }}
    @keyframes c-stamp {{
      0%, 68% {{ opacity: 0; transform: scale(1.2) rotate(-5deg); }}
      74%, 92% {{ opacity: 1; transform: scale(1) rotate(-5deg); }}
      98%, 100% {{ opacity: 0; transform: scale(1) rotate(-5deg); }}
    }}
    @keyframes arrow-pulse {{
      0%, 100% {{ opacity: 0.45; }}
      50% {{ opacity: 1; }}
    }}
    {chr(10).join(css_anim)}
    @media (prefers-reduced-motion: reduce) {{
      .w-fast, .w-slow, .w-split, .commit-stamp, .pulse-arrow {{ animation: none !important; opacity: 1 !important; transform: none !important; }}
      .commit-stamp {{ transform: rotate(-5deg) !important; }}
    }}"""

    body = f"""  {backdrop}
  {sheet}
  <g clip-path="url(#{pid}-clip)">
    <rect x="24" y="24" width="1072" height="46" fill="{t['ink']}"/>
    <text x="48" y="53" fill="{t['paper']}" font-size="15.5" font-weight="800" letter-spacing="0.7" class="t-disp">BMD ENGINE  ·  ADAPTIVE SEGMENT PLANNER</text>
    <text x="1072" y="52" text-anchor="end" fill="{t['paper_2']}" font-size="12" class="t-mono">crates/engine/src/planner.rs</text>
  </g>

  <!-- Stage 1: Probe -->
  <rect x="48" y="94" width="222" height="278" rx="8" fill="{t['paper_2']}" stroke="{t['line']}" stroke-width="1.2"/>
  <text x="64" y="120" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.9" class="t-disp">STAGE 01  ·  PROBE()</text>
  <line x1="64" y1="130" x2="254" y2="130" stroke="{t['ink']}" stroke-width="1.5"/>
  <text x="64" y="156" fill="{t['ink']}" font-size="13.5" font-weight="700" class="t-mono">GET Range: bytes=0-</text>
  <text x="64" y="182" fill="{t['ink_2']}" font-size="12" class="t-body">• Content-Length: 1.00 GiB</text>
  <text x="64" y="204" fill="{t['ink_2']}" font-size="12" class="t-body">• Accept-Ranges: bytes</text>
  <text x="64" y="226" fill="{t['ink_2']}" font-size="12" class="t-body">• ETag / Last-Modified</text>
  <text x="64" y="248" fill="{t['ink_2']}" font-size="12" class="t-body">• HostLimits check</text>
  <rect x="64" y="274" width="190" height="78" rx="6" fill="{t['paper']}" stroke="{t['line']}" stroke-width="1"/>
  <text x="76" y="297" fill="{t['ink_3']}" font-size="10.5" font-weight="700" class="t-disp">FALLBACKS</text>
  <text x="76" y="317" fill="{t['ink']}" font-size="11.5" class="t-mono">FTP REST+RETR pool</text>
  <text x="76" y="337" fill="{t['ink']}" font-size="11.5" class="t-mono">BoringSSL browser TLS</text>

  <!-- Connector 1->2 -->
  <path class="pulse-arrow" d="M276 232h22m-7-6l7 6-7 6" fill="none" stroke="{t['link']}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>

  <!-- Stage 2: Parallel Workers + Re-split -->
  <rect x="304" y="94" width="500" height="278" rx="8" fill="{t['paper']}" stroke="{t['ink']}" stroke-width="1.5"/>
  <text x="322" y="120" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.9" class="t-disp">STAGE 02  ·  8 WORKERS + LARGEST-PART RE-SPLIT</text>
  <line x1="322" y1="130" x2="786" y2="130" stroke="{t['ink']}" stroke-width="1.5"/>
  {"".join(rows)}

  <!-- Connector 2->3 -->
  <path class="pulse-arrow" d="M810 232h22m-7-6l7 6-7 6" fill="none" stroke="{t['link']}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>

  <!-- Stage 3: Sparse Write + Atomic Rename -->
  <rect x="838" y="94" width="234" height="278" rx="8" fill="{t['paper_2']}" stroke="{t['line']}" stroke-width="1.2"/>
  <text x="854" y="120" fill="{t['ink_3']}" font-size="11" font-weight="700" letter-spacing="0.9" class="t-disp">STAGE 03  ·  ATOMIC COMMIT</text>
  <line x1="854" y1="130" x2="1056" y2="130" stroke="{t['ink']}" stroke-width="1.5"/>
  <text x="854" y="156" fill="{t['ink']}" font-size="12.5" font-weight="700" class="t-mono">sparse .bmd.part</text>
  <text x="854" y="178" fill="{t['ink_2']}" font-size="12" class="t-body">Direct offset pwrite, zero</text>
  <text x="854" y="196" fill="{t['ink_2']}" font-size="12" class="t-body">post-download file merge.</text>
  <text x="854" y="224" fill="{t['ink']}" font-size="12.5" font-weight="700" class="t-mono">craft-atomic .bmd.json</text>
  <text x="854" y="244" fill="{t['ink_2']}" font-size="12" class="t-body">Crash-safe resume table.</text>
  <text x="854" y="272" fill="{t['ink']}" font-size="12.5" font-weight="700" class="t-mono">SHA-256 + rename()</text>
  <text x="854" y="292" fill="{t['ink_2']}" font-size="12" class="t-body">Size &amp; hash verified.</text>

  <g class="commit-stamp">
    {rubber_stamp(876, 314, "DELIVERED", t, tilt=-5.0, w=156)}
  </g>"""

    return svg_wrap(
        1120,
        420,
        f"BMD Adaptive Segment Engine ({mode})",
        "Diagram showing probe(), 8 parallel workers, slow-worker drop with largest-remaining-part re-splitting, sparse writes, and atomic rename.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 4. BENCHMARK RACE (1120 x 500) — Real numbers from website/bench/results.json
# ---------------------------------------------------------------------------
def build_benchmark_race(mode: str) -> str:
    t = THEMES[mode]
    pid = f"race-{mode}"
    bench = json.loads((ROOT / "website" / "bench" / "results.json").read_text("utf-8"))
    by_id = {s["id"]: s for s in bench["scenarios"]}

    fast = by_id["fast-1g"]
    bmd = fast["clients"]["BMD"]["stats"]
    idm = fast["clients"]["IDM"]["stats"]

    b_e2e = bmd["e2e_ms"]["median"]       # 1009.35
    b_start = bmd["startup_ms"]["median"]  # 13.7
    b_run = bmd["transfer_ms"]["median"] + bmd["finish_ms"]["median"]  # 993.6
    i_e2e = idm["e2e_ms"]["median"]       # 5456.38
    i_start = idm["startup_ms"]["median"]  # 3075.7
    i_run = idm["transfer_ms"]["median"] + idm["finish_ms"]["median"]  # 2376.1
    ratio = fast["compare"]["ratio_idm_over_bmd"]  # 5.406

    defs, backdrop = board_backdrop(1120, 500, t, pid, rx=16, grid=True)
    sheet = paper_sheet(24, 24, 1072, 452, t, rx=12, lift=False)
    defs += f'<clipPath id="{pid}-clip"><rect x="24" y="24" width="1072" height="452" rx="12"/></clipPath>'

    # Scale: 0..6000 ms across 780 px (x=220..1000)
    axis_x0, axis_w, max_ms = 220.0, 780.0, 6000.0
    px_per_ms = axis_w / max_ms

    b_w_start = max(3.0, b_start * px_per_ms)
    b_w_run = b_run * px_per_ms
    b_w_tot = b_e2e * px_per_ms

    i_w_start = i_start * px_per_ms
    i_w_run = i_run * px_per_ms
    i_w_tot = i_e2e * px_per_ms

    # Total cycle 9s:
    # BMD starts at 6%, finishes at ~20% (proportional to 1.01s), turns Dispatch Green + Stamp!
    # IDM starts at 6%, startup lasts until ~50% (3.08s), transfer finishes at ~81% (5.46s)
    style = f"""
    .bmd-start {{ transform-box: fill-box; transform-origin: left center; animation: bstart 9s linear infinite; }}
    .bmd-run {{ transform-box: fill-box; transform-origin: left center; animation: brun 9s linear infinite; }}
    .bmd-done {{ opacity: 0; animation: bdone 9s ease-out infinite; }}
    .bmd-stamp {{ transform-box: fill-box; transform-origin: center; animation: bstamp 9s cubic-bezier(.2,.9,.3,1) infinite; }}
    .idm-start {{ transform-box: fill-box; transform-origin: left center; animation: istart 9s linear infinite; }}
    .idm-run {{ transform-box: fill-box; transform-origin: left center; animation: irun 9s linear infinite; }}
    @keyframes bstart {{
      0%, 5% {{ transform: scaleX(0); }}
      7%, 93% {{ transform: scaleX(1); }}
      100% {{ transform: scaleX(0); }}
    }}
    @keyframes brun {{
      0%, 7% {{ transform: scaleX(0); }}
      20%, 93% {{ transform: scaleX(1); }}
      100% {{ transform: scaleX(0); }}
    }}
    @keyframes bdone {{
      0%, 19% {{ opacity: 0; }}
      22%, 93% {{ opacity: 1; }}
      100% {{ opacity: 0; }}
    }}
    @keyframes bstamp {{
      0%, 20% {{ opacity: 0; transform: scale(1.25) rotate(-5deg); }}
      25%, 93% {{ opacity: 1; transform: scale(1) rotate(-5deg); }}
      99%, 100% {{ opacity: 0; transform: scale(1) rotate(-5deg); }}
    }}
    @keyframes istart {{
      0%, 6% {{ transform: scaleX(0); }}
      48%, 93% {{ transform: scaleX(1); }}
      100% {{ transform: scaleX(0); }}
    }}
    @keyframes irun {{
      0%, 48% {{ transform: scaleX(0); }}
      81%, 93% {{ transform: scaleX(1); }}
      100% {{ transform: scaleX(0); }}
    }}
    @media (prefers-reduced-motion: reduce) {{
      .bmd-start, .bmd-run, .bmd-done, .bmd-stamp, .idm-start, .idm-run {{
        animation: none !important; opacity: 1 !important; transform: none !important;
      }}
      .bmd-stamp {{ transform: rotate(-5deg) !important; }}
    }}"""

    axis_ticks = []
    for sec in range(0, 7):
        tx = axis_x0 + sec * 1000 * px_per_ms
        axis_ticks.append(
            f'<line x1="{tx:.1f}" y1="112" x2="{tx:.1f}" y2="232" stroke="{t["line"]}" stroke-width="1"/>'
            f'<text x="{tx:.1f}" y="248" text-anchor="middle" fill="{t["ink_3"]}" font-size="11" class="t-mono">{sec} s</text>'
        )

    other_ids = [
        ("small-256k", "256 KiB small file"),
        ("drops-50m", "50 MiB, cut every 2 MiB"),
        ("slowconn-32m", "32 MiB, 1 slow conn"),
        ("latency-100m", "100 MiB, 150 ms RTT"),
        ("capped-100m", "100 MiB, 2 MB/s cap"),
        ("maxconns-50m", "50 MiB, max 2 conns"),
    ]
    cards = []
    for idx, (sid, label) in enumerate(other_ids):
        sc = by_id[sid]
        b_ms = sc["clients"]["BMD"]["stats"]["e2e_ms"]["median"]
        i_ms = sc["clients"]["IDM"]["stats"]["e2e_ms"]["median"]
        r = sc["compare"]["ratio_idm_over_bmd"]
        col = idx % 3
        row = idx // 3
        cx = 48 + col * 344
        cy = 290 + row * 78
        cw = 330
        b_fmt = f"{b_ms:.1f} ms" if b_ms < 1000 else f"{b_ms/1000:.2f} s"
        i_fmt = f"{i_ms/1000:.2f} s"
        r_fmt = f"{r:.1f}×" if r >= 10 else f"{r:.2f}×"
        bw_max = 180.0
        ibw = bw_max
        bbw = max(3.0, bw_max * (b_ms / i_ms))
        cards.append(
            f'<rect x="{cx}" y="{cy}" width="{cw}" height="66" rx="7" fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1"/>'
            f'<text x="{cx+14}" y="{cy+20}" fill="{t["ink"]}" font-size="12.5" font-weight="700" class="t-body">{esc(label)}</text>'
            f'<text x="{cx+cw-14}" y="{cy+22}" text-anchor="end" fill="{t["stamp"]}" font-size="14" font-weight="800" class="t-disp tnum">{r_fmt} faster</text>'
            f'<rect x="{cx+14}" y="{cy+32}" width="{bbw:.1f}" height="7" rx="2" fill="{t["go"]}"/>'
            f'<text x="{cx+204}" y="{cy+39}" fill="{t["ink"]}" font-size="11" font-weight="600" class="t-mono">BMD {b_fmt}</text>'
            f'<rect x="{cx+14}" y="{cy+46}" width="{ibw:.1f}" height="7" rx="2" fill="{t["idm_wait"]}"/>'
            f'<text x="{cx+204}" y="{cy+53}" fill="{t["ink_3"]}" font-size="11" class="t-mono">IDM {i_fmt}</text>'
        )

    body = f"""  {backdrop}
  {sheet}
  <g clip-path="url(#{pid}-clip)">
    <rect x="24" y="24" width="1072" height="46" fill="{t['ink']}"/>
    <text x="48" y="53" fill="{t['paper']}" font-size="15.5" font-weight="800" letter-spacing="0.7" class="t-disp">TRACKING RACE  ·  LOCAL LOOPBACK BENCHMARK</text>
    <text x="1072" y="52" text-anchor="end" fill="{t['paper_2']}" font-size="12" class="t-mono">BMD v1.0.0 vs IDM 6.42.25.3  ·  7-run medians</text>
  </g>

  <text x="48" y="96" fill="{t['ink']}" font-size="16" font-weight="800" class="t-disp">1 GiB over a fast local link (fast-1g)</text>
  <text x="1072" y="96" text-anchor="end" fill="{t['ink_3']}" font-size="11.5" class="t-mono">Dashed = startup delay  ·  Solid = transfer + finish</text>

  {"".join(axis_ticks)}

  <!-- BMD Lane -->
  <text x="48" y="140" fill="{t['ink']}" font-size="15" font-weight="800" class="t-disp">BMD</text>
  <text x="48" y="156" fill="{t['stamp']}" font-size="11.5" font-weight="700" class="t-mono">1,009 ms (13.7 ms start)</text>
  <rect x="{axis_x0}" y="128" width="{axis_w}" height="28" rx="5" fill="{t['track_bg']}"/>
  <rect class="bmd-start" x="{axis_x0}" y="128" width="{b_w_start:.1f}" height="28" rx="3" fill="{t['link']}" fill-opacity="0.6"/>
  <rect class="bmd-run" x="{axis_x0+b_w_start:.1f}" y="128" width="{b_w_run:.1f}" height="28" rx="4" fill="{t['ink']}"/>
  <rect class="bmd-done" x="{axis_x0}" y="128" width="{b_w_tot:.1f}" height="28" rx="4" fill="{t['go']}"/>
  <text x="{axis_x0+b_w_tot+12:.1f}" y="147" fill="{t['ink']}" font-size="12.5" font-weight="700" class="t-mono">1.01 s</text>

  <g class="bmd-stamp">
    {rubber_stamp(426, 122, f"DELIVERED · {ratio:.2f}× FASTER", t, tilt=-4.0, w=276)}
  </g>

  <!-- IDM Lane -->
  <text x="48" y="196" fill="{t['ink_2']}" font-size="15" font-weight="700" class="t-disp">IDM 6.42</text>
  <text x="48" y="212" fill="{t['ink_3']}" font-size="11.5" class="t-mono">5,456 ms (3,076 ms start)</text>
  <rect x="{axis_x0}" y="184" width="{axis_w}" height="28" rx="5" fill="{t['track_bg']}"/>
  <line class="idm-start" x1="{axis_x0}" y1="198" x2="{axis_x0+i_w_start:.1f}" y2="198" stroke="{t['idm_wait']}" stroke-width="6" stroke-dasharray="8 6"/>
  <rect class="idm-run" x="{axis_x0+i_w_start:.1f}" y="184" width="{i_w_run:.1f}" height="28" rx="4" fill="{t['idm_run']}"/>
  <text x="{axis_x0+i_w_tot+12:.1f}" y="203" fill="{t['ink_2']}" font-size="12.5" font-weight="700" class="t-mono">5.46 s</text>

  <!-- All other scenarios -->
  <line x1="48" y1="268" x2="1072" y2="268" stroke="{t['ink']}" stroke-width="1.5"/>
  <text x="48" y="282" fill="{t['ink_3']}" font-size="10.5" font-weight="700" letter-spacing="0.9" class="t-disp">ALL OTHER MEASURED SCENARIOS (WEBSITE/BENCH/RESULTS.JSON)</text>
  {"".join(cards)}

  <text x="48" y="462" fill="{t['ink_3']}" font-size="11" class="t-mono">Source: website/bench/results.json · labserver 127.0.0.1:8097 · 8 connections default · Mann-Whitney p &lt; 0.001 · 2026-10-29</text>"""

    return svg_wrap(
        1120,
        500,
        f"BMD vs IDM Tracking Race Benchmark ({mode})",
        "Animated tracking race from website/bench/results.json showing BMD 1,009 ms vs IDM 6.42 5,456 ms (5.41x faster) plus six other scenarios.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 5. ARCHITECTURE MAP (1160 x 560) — 15 Rust crates + extension/
# ---------------------------------------------------------------------------
def build_architecture_map(mode: str) -> str:
    t = THEMES[mode]
    pid = f"arch-{mode}"
    defs, backdrop = board_backdrop(1160, 560, t, pid, rx=16, grid=True)
    sheet = paper_sheet(24, 24, 1112, 512, t, rx=12, lift=False)
    defs += f'<clipPath id="{pid}-clip"><rect x="24" y="24" width="1112" height="512" rx="12"/></clipPath>'

    style = """
    .bus-flow { stroke-dasharray: 7 7; animation: flow 2.4s linear infinite; }
    @keyframes flow { to { stroke-dashoffset: -28; } }
    @media (prefers-reduced-motion: reduce) { .bus-flow { animation: none; } }"""

    # Tier 1: Entry surfaces (6 cards across 1064 px)
    t1 = [
        ("extension/", "Browser Ext", "MV3 & Firefox"),
        ("crates/nmhost", "bmd-nmhost", "Native Host"),
        ("crates/app", "bmd-app", "egui Desktop"),
        ("crates/cli", "bmd-cli", "Headless CLI"),
        ("crates/mcp", "bmd-mcp", "AI stdio MCP"),
        ("crates/remote", "bmd-remote", "HTTPS Remote"),
    ]
    t1_svg = []
    for i, (path, name, sub) in enumerate(t1):
        bx = 48 + i * 178
        bw = 166
        t1_svg.append(
            f'<rect x="{bx}" y="104" width="{bw}" height="74" rx="7" fill="{t["paper_2"]}" stroke="{t["ink"]}" stroke-width="1.3"/>'
            f'<text x="{bx+12}" y="123" fill="{t["ink_3"]}" font-size="10" class="t-mono">{path}</text>'
            f'<text x="{bx+12}" y="146" fill="{t["ink"]}" font-size="14" font-weight="800" class="t-disp">{name}</text>'
            f'<text x="{bx+12}" y="165" fill="{t["ink_2"]}" font-size="11.5" class="t-body">{esc(sub)}</text>'
            f'<line class="bus-flow" x1="{bx+bw/2:.1f}" y1="178" x2="{bx+bw/2:.1f}" y2="210" stroke="{t["link"]}" stroke-width="2"/>'
        )

    # Tier 2: IPC + CNL bus (y=210..274)
    # Tier 3: Engine (y=302..386)
    # Tier 4: 7 specialist crates (y=414..496)
    t4 = [
        ("torrent", "librqbit BT"),
        ("tools", "yt-dlp/ffmpeg"),
        ("debrid", "5 Debrid APIs"),
        ("extract", "zip/7z/Hatch"),
        ("geo", "DB-IP + flags"),
        ("crash", "Minidumps"),
        ("labserver", "Fault server"),
    ]
    t4_svg = []
    for i, (crate, desc) in enumerate(t4):
        bx = 48 + i * 153
        bw = 144
        t4_svg.append(
            f'<line class="bus-flow" x1="{bx+bw/2:.1f}" y1="386" x2="{bx+bw/2:.1f}" y2="414" stroke="{t["ink_3"]}" stroke-width="1.6"/>'
            f'<rect x="{bx}" y="414" width="{bw}" height="74" rx="7" fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1.2"/>'
            f'<text x="{bx+10}" y="434" fill="{t["ink_3"]}" font-size="9.5" class="t-mono">crates/{crate}</text>'
            f'<text x="{bx+10}" y="455" fill="{t["ink"]}" font-size="13.5" font-weight="800" class="t-disp">bmd-{crate}</text>'
            f'<text x="{bx+10}" y="474" fill="{t["ink_2"]}" font-size="11" class="t-body">{desc}</text>'
        )

    body = f"""  {backdrop}
  {sheet}
  <g clip-path="url(#{pid}-clip)">
    <rect x="24" y="24" width="1112" height="46" fill="{t['ink']}"/>
    <text x="48" y="53" fill="{t['paper']}" font-size="15.5" font-weight="800" letter-spacing="0.7" class="t-disp">BMD WORKSPACE ARCHITECTURE  ·  15 RUST CRATES + EXTENSION</text>
    <text x="1112" y="52" text-anchor="end" fill="{t['paper_2']}" font-size="12" class="t-mono">Dependencies point inward · Engine knows nothing of UI</text>
  </g>

  <text x="48" y="92" fill="{t['ink_3']}" font-size="10.5" font-weight="700" letter-spacing="0.9" class="t-disp">ENTRY SURFACES &amp; CLIENTS</text>
  {"".join(t1_svg)}

  <!-- Tier 2: IPC & CNL -->
  <rect x="48" y="210" width="844" height="62" rx="8" fill="{t['board']}" fill-opacity="0.10" stroke="{t['link']}" stroke-width="1.6"/>
  <text x="66" y="234" fill="{t['link']}" font-size="13.5" font-weight="800" class="t-disp">crates/ipc  ·  bmd-ipc (Single-Instance Automation Bus)</text>
  <text x="66" y="255" fill="{t['ink_2']}" font-size="12" class="t-body">Token-authenticated local socket · Shared capture rules · LinksPart chunking · Data directory</text>

  <rect x="910" y="210" width="202" height="62" rx="8" fill="{t['paper_2']}" stroke="{t['ink']}" stroke-width="1.3"/>
  <text x="926" y="234" fill="{t['ink']}" font-size="13.5" font-weight="800" class="t-disp">crates/cnl · bmd-cnl</text>
  <text x="926" y="255" fill="{t['ink_2']}" font-size="11.5" class="t-body">Click&apos;n&apos;Load 2 (:9666)</text>
  <line class="bus-flow" x1="892" y1="241" x2="910" y2="241" stroke="{t['link']}" stroke-width="2"/>

  <!-- Bus to Engine -->
  <line class="bus-flow" x1="580" y1="272" x2="580" y2="302" stroke="{t['link']}" stroke-width="2.4"/>

  <!-- Tier 3: Engine Core -->
  <rect x="48" y="302" width="1064" height="84" rx="8" fill="{t['paper']}" stroke="{t['ink']}" stroke-width="2"/>
  <rect x="48" y="302" width="10" height="84" rx="3" fill="{t['link']}"/>
  <text x="72" y="328" fill="{t['ink']}" font-size="16" font-weight="800" class="t-disp">crates/engine  ·  bmd-engine (Core Segmented Transfer Engine)</text>
  <text x="1096" y="328" text-anchor="end" fill="{t['stamp']}" font-size="11.5" font-weight="700" class="t-mono">HTTP/1.1 · HTTPS · FTP/FTPS</text>
  <text x="72" y="352" fill="{t['ink_2']}" font-size="12.5" class="t-mono">probe() → Planner (largest-part re-split) → Worker × N → HostLimits → Token Limiter</text>
  <text x="72" y="372" fill="{t['ink_3']}" font-size="12" class="t-mono">Sparse .bmd.part + craft-atomic .bmd.json · BoringSSL browser-like TLS · sites/ resolvers</text>

  <!-- Tier 4: Specialist Crates -->
  <text x="48" y="406" fill="{t['ink_3']}" font-size="10.5" font-weight="700" letter-spacing="0.9" class="t-disp">SPECIALIST PROTOCOLS, TOOLS &amp; VERIFICATION CRATES</text>
  {"".join(t4_svg)}

  <text x="48" y="514" fill="{t['ink_3']}" font-size="11" class="t-mono">Total: 15 Cargo workspace crates (app, cli, cnl, crash, debrid, engine, extract, geo, ipc, labserver, mcp, nmhost, remote, tools, torrent) + extension/</text>"""

    return svg_wrap(
        1160,
        560,
        f"BMD Architecture Blueprint ({mode})",
        "Architectural map of BMD's 15 Rust workspace crates and browser extension connected via bmd-ipc and bmd-engine.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 6. WORKFLOW CAPTURE PIPELINE (1120 x 340)
# ---------------------------------------------------------------------------
def build_workflow_capture(mode: str) -> str:
    t = THEMES[mode]
    pid = f"wf-{mode}"
    defs, backdrop = board_backdrop(1120, 340, t, pid, rx=16, grid=True)
    sheet = paper_sheet(24, 24, 1072, 292, t, rx=12, lift=False)
    defs += f'<clipPath id="{pid}-clip"><rect x="24" y="24" width="1072" height="292" rx="12"/></clipPath>'

    stages = [
        ("01", "Browser Extension", "extension/", "Chrome, Edge, Brave,", "Opera, Vivaldi, Firefox"),
        ("02", "Native Host", "bmd-nmhost", "Checks capture rules &", "1 MB LinksPart frames"),
        ("03", "Single-Instance IPC", "bmd-ipc", "Token-protected local", "socket · zero relaunch"),
        ("04", "Dispatch Target", "app / remote / mcp", "Desktop GUI, Headless", "HTTPS, or AI Assistant"),
    ]
    cards = []
    style_rings = []
    for i, (num, title, crate, l1, l2) in enumerate(stages):
        cx = 48 + i * 268
        cy = 96
        cw, ch = 224, 152
        p0, p1, p2 = i * 20, i * 20 + 6, i * 20 + 22
        start_stop = "0%" if p0 == 0 else f"0%, {p0}%"
        style_rings.append(
            f"@keyframes ring{i} {{ {start_stop} {{ opacity: 0.25; }} {p1}%, {p2}% {{ opacity: 1; }} {p2+8}%, 100% {{ opacity: 0.25; }} }}\n"
            f".stage-ring-{i} {{ animation: ring{i} 8s ease-in-out infinite; }}"
        )
        cards.append(
            f'<rect x="{cx}" y="{cy}" width="{cw}" height="{ch}" rx="8" fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1.2"/>'
            f'<rect class="stage-ring-{i}" x="{cx}" y="{cy}" width="{cw}" height="{ch}" rx="8" fill="none" stroke="{t["link"]}" stroke-width="2.2"/>'
            f'<rect x="{cx+14}" y="{cy+14}" width="32" height="22" rx="4" fill="{t["ink"]}"/>'
            f'<text x="{cx+30}" y="{cy+29}" text-anchor="middle" fill="{t["paper"]}" font-size="11.5" font-weight="800" class="t-mono">{num}</text>'
            f'<text x="{cx+56}" y="{cy+29}" fill="{t["link"]}" font-size="11" font-weight="600" class="t-mono">{esc(crate)}</text>'
            f'<text x="{cx+14}" y="{cy+64}" fill="{t["ink"]}" font-size="16" font-weight="800" class="t-disp">{esc(title)}</text>'
            f'<text x="{cx+14}" y="{cy+92}" fill="{t["ink_2"]}" font-size="12.5" class="t-body">{esc(l1)}</text>'
            f'<text x="{cx+14}" y="{cy+110}" fill="{t["ink_2"]}" font-size="12.5" class="t-body">{esc(l2)}</text>'
            f'<line x1="{cx+14}" y1="{cy+128}" x2="{cx+cw-14}" y2="{cy+128}" stroke="{t["line"]}" stroke-width="1"/>'
        )
        if i < 3:
            ax = cx + cw + 6
            cards.append(
                f'<line x1="{ax}" y1="172" x2="{ax+32}" y2="172" stroke="{t["link"]}" stroke-width="2" stroke-dasharray="4 4"/>'
                f'<path d="M{ax+26} 166l7 6-7 6" fill="none" stroke="{t["link"]}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
            )

    style = f"""
    .pkt-dot {{ animation: pkt-move 8s linear infinite; }}
    .wf-done {{ animation: wf-stamp 8s ease-out infinite; }}
    @keyframes pkt-move {{
      0% {{ transform: translateX(0px); opacity: 0; }}
      6% {{ opacity: 1; }}
      82% {{ transform: translateX(840px); opacity: 1; }}
      88%, 100% {{ transform: translateX(840px); opacity: 0; }}
    }}
    @keyframes wf-stamp {{
      0%, 78% {{ opacity: 0.3; }}
      84%, 94% {{ opacity: 1; }}
      100% {{ opacity: 0.3; }}
    }}
    {chr(10).join(style_rings)}
    @media (prefers-reduced-motion: reduce) {{
      .pkt-dot {{ animation: none; opacity: 0; }}
      .stage-ring-0, .stage-ring-1, .stage-ring-2, .stage-ring-3, .wf-done {{ animation: none !important; opacity: 1 !important; }}
    }}"""

    body = f"""  {backdrop}
  {sheet}
  <g clip-path="url(#{pid}-clip)">
    <rect x="24" y="24" width="1072" height="46" fill="{t['ink']}"/>
    <text x="48" y="53" fill="{t['paper']}" font-size="15.5" font-weight="800" letter-spacing="0.7" class="t-disp">CAPTURE PIPELINE  ·  BROWSER EXTENSION TO ENGINE</text>
    <text x="1072" y="52" text-anchor="end" fill="{t['paper_2']}" font-size="12" class="t-mono">Zero duplicate instances · Browser keeps fallback on reject</text>
  </g>

  {"".join(cards)}

  <!-- Bottom Live Transit Track -->
  <rect x="48" y="268" width="900" height="26" rx="5" fill="{t['paper_2']}" stroke="{t['line']}" stroke-width="1"/>
  <line x1="68" y1="281" x2="928" y2="281" stroke="{t['line']}" stroke-width="2"/>
  <circle class="pkt-dot" cx="76" cy="281" r="6" fill="{t['link']}"/>
  <rect class="wf-done" x="962" y="266" width="110" height="30" rx="6" fill="{t['go']}" stroke="{t['go_ink']}" stroke-width="1.2"/>
  <text class="wf-done t-disp" x="1017" y="285" text-anchor="middle" fill="{t['go_ink']}" font-size="12" font-weight="800">DISPATCHED</text>"""

    return svg_wrap(
        1120,
        340,
        f"BMD Capture Pipeline ({mode})",
        "Animated 4-stage pipeline from browser extension through bmd-nmhost and bmd-ipc to desktop app, remote control, or MCP.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 7. FEATURE MATRIX (1120 x 480) — Packing-list ruled grid
# ---------------------------------------------------------------------------
def build_feature_matrix(mode: str) -> str:
    t = THEMES[mode]
    pid = f"feat-{mode}"
    defs, backdrop = board_backdrop(1120, 480, t, pid, rx=16, grid=True)
    sheet = paper_sheet(24, 24, 1072, 432, t, rx=12, lift=False)
    defs += f'<clipPath id="{pid}-clip"><rect x="24" y="24" width="1072" height="432" rx="12"/></clipPath>'

    # 6 capability cells in a clean ruled 3x2 grid (matching DESIGN.md Contents packing list)
    features = [
        ("01 · ENGINE", "Segmented HTTP/FTP", "Up to 128 parallel workers with dynamic largest-part re-splitting, sparse writes, and BoringSSL browser-like TLS fallback.", "crates/engine · FTP/FTPS · PAC"),
        ("02 · SWARM", "BitTorrent & Magnets", "Built-in librqbit + uTP torrent engine. Selective file picking, piece-map grid, DHT, peer filtering, and IP blocklists.", "crates/torrent · magnet: · .torrent"),
        ("03 · MEDIA", "Video & Gallery Relay", "Up to 4K 60fps HDR & H.264+AAC preset via yt-dlp + ffmpeg + gallery-dl, chapter embedding, and parallel HLS/DASH.", "crates/tools · 1,000+ sites"),
        ("04 · SERVER", "Headless & HTTPS Remote", "Run --headless on Linux or Docker/Unraid. Control queues from your phone over paired local HTTPS with device tokens.", "crates/remote · Docker · QR pairing"),
        ("05 · AUTOMATION", "MCP Server & CLI", "Official rmcp stdio server (bmd-mcp) and scriptable bmd-cli automation API so AI assistants & scripts can manage queues.", "crates/mcp · crates/cli · JSON schema"),
        ("06 · LOCALIZATION", "86 Languages & Themes", "Full RTL support (Arabic, Persian, Hebrew, Urdu), 10 built-in themes (Luna, Aero, Adwaita, Breeze, Emerald...) + theme.toml.", "i18n/ · OKLab WCAG AA palettes"),
    ]

    cells = []
    for i, (kicker, title, body_txt, spec) in enumerate(features):
        col = i % 3
        row = i // 3
        cx = 48 + col * 344
        cy = 94 + row * 172
        cw, ch = 336, 158
        # Word-wrap body_txt cleanly into 3 lines (~44 chars)
        words = body_txt.split()
        lines, cur = [], ""
        for w in words:
            if len(cur) + len(w) + 1 <= 44:
                cur = f"{cur} {w}".strip()
            else:
                lines.append(cur)
                cur = w
        if cur:
            lines.append(cur)
        line_svg = "".join(
            f'<text x="{cx+16}" y="{cy+80+li*18}" fill="{t["ink_2"]}" font-size="12.5" class="t-body">{esc(ln)}</text>'
            for li, ln in enumerate(lines[:3])
        )
        cells.append(
            f'<rect x="{cx}" y="{cy}" width="{cw}" height="{ch}" rx="8" fill="{t["paper_2"]}" stroke="{t["line"]}" stroke-width="1.2"/>'
            f'<text x="{cx+16}" y="{cy+26}" fill="{t["ink_3"]}" font-size="10.5" font-weight="700" letter-spacing="0.9" class="t-disp">{esc(kicker)}</text>'
            f'<text x="{cx+16}" y="{cy+52}" fill="{t["ink"]}" font-size="18.5" font-weight="800" class="t-disp">{esc(title)}</text>'
            f'{line_svg}'
            f'<line x1="{cx+16}" y1="{cy+130}" x2="{cx+cw-16}" y2="{cy+130}" stroke="{t["line"]}" stroke-width="1"/>'
            f'<text x="{cx+16}" y="{cy+147}" fill="{t["link"]}" font-size="11" font-weight="600" class="t-mono">{esc(spec)}</text>'
        )

    body = f"""  {backdrop}
  {sheet}
  <g clip-path="url(#{pid}-clip)">
    <rect x="24" y="24" width="1072" height="46" fill="{t['ink']}"/>
    <text x="48" y="53" fill="{t['paper']}" font-size="15.5" font-weight="800" letter-spacing="0.7" class="t-disp">CONTENTS  ·  PACKING LIST &amp; CAPABILITIES</text>
    <text x="1072" y="52" text-anchor="end" fill="{t['paper_2']}" font-size="12" class="t-mono">6 core subsystems · Free &amp; Open Source (GPL-3.0)</text>
  </g>
  {"".join(cells)}"""

    return svg_wrap(
        1120,
        480,
        f"BMD Feature Matrix ({mode})",
        "Six-card ruled manifest of BMD's core capabilities: segmented HTTP/FTP, BitTorrent, video relay, remote HTTPS, MCP/CLI, and 86 languages.",
        defs,
        "",
        body,
    )


# ---------------------------------------------------------------------------
# 8. DOWNLOAD CTA (640 x 92) — Branded Sticker-Edge Dispatch Green button
# ---------------------------------------------------------------------------
def build_download_cta(mode: str) -> str:
    t = THEMES[mode]
    pid = f"cta-{mode}"
    defs = marlin_defs(pid)
    defs += f"""
    <clipPath id="{pid}-btn"><rect x="6" y="6" width="628" height="76" rx="10"/></clipPath>"""
    style = """
    .cta-arrow { animation: bob 2.2s ease-in-out infinite; }
    .cta-sheen { animation: sheen 5.5s ease-in-out infinite; }
    @keyframes bob {
      0%, 100% { transform: translateY(0px); }
      50% { transform: translateY(4px); }
    }
    @keyframes sheen {
      0%, 60% { transform: translateX(-120px); opacity: 0; }
      72% { opacity: 0.25; }
      92%, 100% { transform: translateX(740px); opacity: 0; }
    }
    @media (prefers-reduced-motion: reduce) {
      .cta-arrow, .cta-sheen { animation: none; opacity: 0; }
      .cta-arrow { opacity: 1; }
    }"""
    body = f"""  <rect x="6" y="10" width="628" height="76" rx="10" fill="{t['go_edge']}"/>
  <rect x="6" y="6" width="628" height="76" rx="10" fill="{t['go']}" stroke="{t['go_ink']}" stroke-width="1.6"/>
  <g clip-path="url(#{pid}-btn)">
    <polygon class="cta-sheen" points="0,6 54,6 24,82 -30,82" fill="#ffffff"/>
  </g>
  <rect x="18" y="16" width="56" height="56" rx="8" fill="{t['go_ink']}" fill-opacity="0.1"/>
  {marlin_group(pid, 22, 20, 0.10, draw_anim=False)}
  <text x="88" y="40" fill="{t['go_ink']}" font-size="21" font-weight="800" letter-spacing="-0.3" class="t-disp">Download Black Marlin · v1.0.0</text>
  <text x="88" y="62" fill="{t['go_ink']}" font-size="13" font-weight="600" fill-opacity="0.88" class="t-body">Windows 10/11 · Linux · macOS (Exp) · Free &amp; Open Source</text>
  <g class="cta-arrow">
    <path d="M594 28v20m-8-7l8 8 8-8" fill="none" stroke="{t['go_ink']}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
  </g>
  <path d="M582 56v5h24v-5" fill="none" stroke="{t['go_ink']}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>"""
    return svg_wrap(
        640,
        92,
        f"Download Black Marlin v1.0.0 ({mode})",
        "Dispatch Green primary download button with sticker edge and Black Marlin crest.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 9. CLOSING WAYBILL (1120 x 300)
# ---------------------------------------------------------------------------
def build_closing_waybill(mode: str) -> str:
    t = THEMES[mode]
    pid = f"close-{mode}"
    defs, backdrop = board_backdrop(1120, 300, t, pid, rx=16, grid=True, deep=True)
    defs += marlin_defs(pid)
    defs += f'<clipPath id="{pid}-clip"><rect x="44" y="28" width="1032" height="244" rx="12"/></clipPath>'
    bc_d, _ = code128_svg_path("GPL-3.0 BMD", 772, 162, 250, 42)

    style = """
    .sign-stamp {
      transform-box: fill-box;
      transform-origin: center;
      animation: sign-pulse 6s ease-in-out infinite;
    }
    @keyframes sign-pulse {
      0%, 100% { transform: rotate(-6deg) scale(1); }
      50% { transform: rotate(-6deg) scale(1.03); }
    }
    @media (prefers-reduced-motion: reduce) {
      .sign-stamp { animation: none; transform: rotate(-6deg); }
    }"""

    body = f"""  {backdrop}
  <g transform="rotate(-1.1 560 150)">
    <rect x="44" y="42" width="1032" height="244" rx="12" fill="{t['shadow']}" fill-opacity="0.35"/>
    <rect x="44" y="30" width="1032" height="244" rx="12" fill="{t['shadow']}" fill-opacity="0.22"/>
    <rect x="44" y="28" width="1032" height="244" rx="12" fill="{t['paper']}"/>
    <g clip-path="url(#{pid}-clip)">
      <rect x="44" y="28" width="1032" height="44" fill="{t['ink']}"/>
      <text x="68" y="55" fill="{t['paper']}" font-size="14" font-weight="800" letter-spacing="0.9" class="t-disp">SIGN OFF  ·  CARRIER: DECOYINDIA/BMD</text>
      <text x="1052" y="55" text-anchor="end" fill="{t['paper_2']}" font-size="12" class="t-mono">GPL-3.0 · RUST · 86 LANGUAGES · ZERO ADS</text>
    </g>

    {marlin_group(pid, 62, 92, 0.22, draw_anim=False)}

    <text x="186" y="122" fill="{t['ink']}" font-size="30" font-weight="800" letter-spacing="-0.6" class="t-disp">Black Marlin Downloader</text>
    <text x="186" y="148" fill="{t['ink_2']}" font-size="14" class="t-body">Rebuilt from scratch in Rust. Free for everyone, forever — no trials, no telemetry, no ads.</text>

    <!-- Ruled Sign-off Fields -->
    <line x1="186" y1="168" x2="736" y2="168" stroke="{t['ink']}" stroke-width="1.5"/>
    <line x1="366" y1="168" x2="366" y2="238" stroke="{t['ink']}" stroke-width="1.5"/>
    <line x1="556" y1="168" x2="556" y2="238" stroke="{t['ink']}" stroke-width="1.5"/>
    <line x1="186" y1="238" x2="736" y2="238" stroke="{t['ink']}" stroke-width="1.5"/>

    <text x="192" y="188" fill="{t['ink_3']}" font-size="10.5" font-weight="700" letter-spacing="0.7" class="t-disp">LICENSE</text>
    <text x="192" y="218" fill="{t['ink']}" font-size="16" font-weight="700" class="t-mono">GNU GPL-3.0</text>

    <text x="378" y="188" fill="{t['ink_3']}" font-size="10.5" font-weight="700" letter-spacing="0.7" class="t-disp">LOCALIZATION</text>
    <text x="378" y="218" fill="{t['ink']}" font-size="16" font-weight="700" class="t-body">86 langs (incl. RTL)</text>

    <text x="568" y="188" fill="{t['ink_3']}" font-size="10.5" font-weight="700" letter-spacing="0.7" class="t-disp">WORKSPACE</text>
    <text x="568" y="218" fill="{t['ink']}" font-size="16" font-weight="700" class="t-mono">15 crates + ext</text>

    <!-- Barcode + Stamp -->
    <line x1="758" y1="92" x2="758" y2="246" stroke="{t['ink_3']}" stroke-width="1.4" stroke-dasharray="5 5"/>
    <g class="sign-stamp">
      {rubber_stamp(786, 94, "SIGNED FOR · FREE", t, tilt=-6.0, w=210)}
    </g>
    <path d="{bc_d}" fill="{t['ink']}"/>
    <text x="897" y="220" text-anchor="middle" fill="{t['ink_2']}" font-size="11" font-weight="600" letter-spacing="3.2" class="t-mono">GPL-3.0 BMD</text>
  </g>"""

    return svg_wrap(
        1120,
        300,
        f"Black Marlin Closing Waybill ({mode})",
        "Tilted closing thermal waybill featuring the Marlin crest, license/language/crate fields, Code 128 barcode, and Signed For stamp.",
        defs,
        style,
        body,
    )


# ---------------------------------------------------------------------------
# 10. REAL SCREENSHOT SHOWCASES (Unretouched UI pixels framed via ffmpeg)
# ---------------------------------------------------------------------------
def run_ffmpeg(args: list[str]) -> None:
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *args]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"ffmpeg failed ({res.returncode}): {res.stderr.strip()}")


def _pick_shot(*candidates: str) -> Path:
    for rel in candidates:
        p = ROOT / rel
        if p.exists():
            return p
    return ROOT / candidates[-1]


def build_screenshots() -> list[Path]:
    """Frame real unretouched BMD screenshots via ffmpeg without altering app UI pixels."""
    if not shutil.which("ffmpeg"):
        print("warning: ffmpeg not found on PATH; skipping screenshot framing", file=sys.stderr)
        return []

    shots_dir = OUT / "screenshots"
    shots_dir.mkdir(parents=True, exist_ok=True)
    generated: list[Path] = []

    # 1. Hero main window framed cleanly on Blue Board / Navy Depot (never upscaled)
    for mode, candidates, bg_hex in [
        ("light", ("target/readme-shots/light/main-light.png", "docs/images/main-light.webp"), "0x1f63d6"),
        ("dark", ("target/readme-shots/dark/main-dark.png", "docs/images/main-dark.webp"), "0x0c1a33"),
    ]:
        src = _pick_shot(*candidates)
        dst = shots_dir / f"showcase-main-{mode}.png"
        run_ffmpeg([
            "-i", str(src),
            "-vf", f"scale='min(940,iw)':-2:flags=lanczos,pad=988:552:(ow-iw)/2:(oh-ih)/2:color={bg_hex}",
            "-frames:v", "1",
            str(dst),
        ])
        generated.append(dst)

    # 2. 2x2 Unretouched Dialog Gallery (progress, add-torrent/pieces, scheduler, options)
    for mode, bg_hex, card_hex in [
        ("light", "0x1f63d6", "0xfcfcfb"),
        ("dark", "0x0c1a33", "0x11151d"),
    ]:
        if mode == "light":
            inputs = [
                _pick_shot("docs/images/progress.webp"),
                _pick_shot("docs/images/progress-torrent-pieces.webp"),
                _pick_shot("website/assets/screens/video-light.webp"),
                _pick_shot("docs/images/grabber.webp"),
            ]
        else:
            inputs = [
                _pick_shot("website/assets/screens/progress-dark.webp"),
                _pick_shot("website/assets/screens/torrent-dark.webp"),
                _pick_shot("website/assets/screens/video-dark.webp"),
                _pick_shot("website/assets/screens/add-torrent-dark.webp"),
            ]
        dst = shots_dir / f"showcase-dialogs-{mode}.png"
        # Fit without upscaling inside 520x420 cell with 24px board margin -> 1088x888
        fc = (
            f"[0:v]scale=w='min(500,iw)':h='min(404,ih)':force_original_aspect_ratio=decrease:flags=lanczos,"
            f"pad=520:420:(ow-iw)/2:(oh-ih)/2:color={card_hex}[a];"
            f"[1:v]scale=w='min(500,iw)':h='min(404,ih)':force_original_aspect_ratio=decrease:flags=lanczos,"
            f"pad=520:420:(ow-iw)/2:(oh-ih)/2:color={card_hex}[b];"
            f"[2:v]scale=w='min(500,iw)':h='min(404,ih)':force_original_aspect_ratio=decrease:flags=lanczos,"
            f"pad=520:420:(ow-iw)/2:(oh-ih)/2:color={card_hex}[c];"
            f"[3:v]scale=w='min(500,iw)':h='min(404,ih)':force_original_aspect_ratio=decrease:flags=lanczos,"
            f"pad=520:420:(ow-iw)/2:(oh-ih)/2:color={card_hex}[d];"
            f"[a][b]hstack=inputs=2[top];"
            f"[c][d]hstack=inputs=2[bot];"
            f"[top][bot]vstack=inputs=2,pad=1088:888:24:24:color={bg_hex}[out]"
        )
        args = []
        for inp in inputs:
            args.extend(["-i", str(inp)])
        args.extend(["-filter_complex", fc, "-map", "[out]", "-frames:v", "1", str(dst)])
        run_ffmpeg(args)
        generated.append(dst)

    # 3. Animated GIF Tour (6 unretouched frames on Blue Board, optimized palette)
    tour_sources = [
        _pick_shot("docs/images/main-light.webp"),
        _pick_shot("docs/images/main-dark.webp"),
        _pick_shot("docs/images/progress.webp"),
        _pick_shot("docs/images/progress-torrent-pieces.webp"),
        _pick_shot("website/assets/screens/video-light.webp"),
        _pick_shot("docs/images/grabber.webp"),
    ]
    tour_dst = shots_dir / "bmd-tour.gif"
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        for idx, src in enumerate(tour_sources):
            frame_png = tmp_path / f"frame_{idx:02d}.png"
            run_ffmpeg([
                "-i", str(src),
                "-vf", "scale=w='min(912,iw)':h='min(496,ih)':force_original_aspect_ratio=decrease:flags=lanczos,"
                       "pad=960:540:(ow-iw)/2:(oh-ih)/2:color=0x1f63d6,format=rgb24",
                "-frames:v", "1",
                str(frame_png),
            ])
        palette_png = tmp_path / "palette.png"
        run_ffmpeg([
            "-framerate", "0.5",
            "-i", str(tmp_path / "frame_%02d.png"),
            "-vf", "palettegen=max_colors=256:stats_mode=full",
            "-frames:v", "1",
            str(palette_png),
        ])
        run_ffmpeg([
            "-framerate", "0.5",
            "-i", str(tmp_path / "frame_%02d.png"),
            "-i", str(palette_png),
            "-lavfi", "paletteuse=dither=sierra2_4a",
            "-loop", "0",
            str(tour_dst),
        ])
    generated.append(tour_dst)
    return generated


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate BMD README SVG artwork and screenshot showcases.")
    parser.add_argument("--svgs-only", action="store_true", help="Skip ffmpeg screenshot framing.")
    args = parser.parse_args()

    svg_builders = [
        ("hero/hero-waybill", build_hero_waybill),
        ("dividers/waybill-tear", build_waybill_tear),
        ("dividers/pulse-rule", build_pulse_rule),
        ("animations/segment-engine", build_segment_engine),
        ("animations/benchmark-race", build_benchmark_race),
        ("illustrations/architecture-map", build_architecture_map),
        ("animations/workflow-capture", build_workflow_capture),
        ("features/feature-matrix", build_feature_matrix),
        ("branding/download-cta", build_download_cta),
        ("branding/closing-waybill", build_closing_waybill),
    ]

    outputs: list[Path] = []
    for base, fn in svg_builders:
        for mode in ("light", "dark"):
            outputs.append(write_svg(f"{base}-{mode}.svg", fn(mode)))

    if not args.svgs_only:
        outputs.extend(build_screenshots())

    for p in outputs:
        rel = p.relative_to(ROOT).as_posix()
        size_kb = p.stat().st_size / 1024.0
        print(f"generated: {rel} ({size_kb:.1f} KB)")
    print(f"total files: {len(outputs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
