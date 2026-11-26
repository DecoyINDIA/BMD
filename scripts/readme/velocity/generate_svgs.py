#!/usr/bin/env python3
"""Generate Level 3 Hydrodynamic Velocity pure-SVG dividers and CTA pills.

Zero <script>, zero <foreignObject>, zero external URLs, zero em-dashes.
Tested to render cleanly with system font fallbacks ('Segoe UI', system-ui).
"""
from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "assets" / "readme"

FONT_DISPLAY = "'Space Grotesk', 'Segoe UI', system-ui, -apple-system, sans-serif"
FONT_BODY = "'IBM Plex Sans', 'Segoe UI', system-ui, -apple-system, sans-serif"
FONT_MONO = "'IBM Plex Mono', 'Cascadia Mono', Consolas, ui-monospace, monospace"

MARLIN_PATHS = {
    "dorsal": "M125 118C177 88 255 83 312 111C238 158 155 217 128 293C120 252 139 209 166 173C191 139 177 115 125 118Z",
    "body": "M437 15C387 67 362 96 344 125C357 113 371 105 386 99C359 124 339 151 330 180C346 208 354 241 346 274C344 256 339 240 330 229C327 298 278 360 205 387C191 393 176 397 158 399L130 356C167 344 213 354 239 308C263 266 244 219 204 211C169 204 140 235 128 293C119 240 167 179 221 150C252 133 273 119 309 109C352 98 394 48 437 15Z",
    "belly": "M223 179C261 148 306 162 326 191C344 215 351 244 346 274C343 248 332 229 311 215C287 198 252 184 223 179Z",
    "tail": "M49 295C103 296 136 323 176 341C205 354 229 351 255 337C232 360 194 374 165 394C145 414 132 438 124 462C122 423 148 395 133 367C114 337 81 306 49 295Z",
}

THEMES = {
    "dark": {
        "line": "#1f3a30",
        "primary": "#55c99d",
        "glow": "#80dbad",
        "spec": "#c1f0d6",
        "cta_bg": "#55c99d",
        "cta_edge": "#187052",
        "cta_ink": "#06150e",
        "cta_sub": "#0b2a1d",
    },
    "light": {
        "line": "#cbe2d8",
        "primary": "#136f50",
        "glow": "#0ea472",
        "spec": "#5ae0ac",
        "cta_bg": "#136f50",
        "cta_edge": "#0a3c2b",
        "cta_ink": "#ffffff",
        "cta_sub": "#d9f5ea",
    },
}


def build_divider_slipstream(mode: str) -> str:
    t = THEMES[mode]
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 40" width="1200" height="40" role="img" aria-label="Hydrodynamic slipstream section divider">
  <defs>
    <linearGradient id="div-a-{mode}" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="{t['primary']}" stop-opacity="0"/>
      <stop offset="22%" stop-color="{t['primary']}" stop-opacity="0.35"/>
      <stop offset="50%" stop-color="{t['glow']}" stop-opacity="0.95"/>
      <stop offset="78%" stop-color="{t['primary']}" stop-opacity="0.35"/>
      <stop offset="100%" stop-color="{t['primary']}" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="div-b-{mode}" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="{t['glow']}" stop-opacity="0"/>
      <stop offset="50%" stop-color="{t['spec']}" stop-opacity="0.72"/>
      <stop offset="100%" stop-color="{t['glow']}" stop-opacity="0"/>
    </linearGradient>
  </defs>
  <style>
    .wake-a {{ stroke-dasharray: 280 920; animation: glide-a 6.5s cubic-bezier(.45,0,.55,1) infinite; }}
    .wake-b {{ stroke-dasharray: 180 1020; animation: glide-b 8.5s cubic-bezier(.45,0,.55,1) infinite; }}
    .crest-pulse {{ transform-origin: 600px 20px; animation: crest-breathe 4.5s ease-in-out infinite; }}
    .mote-l {{ animation: mote-left 6.5s ease-in-out infinite; }}
    .mote-r {{ animation: mote-right 6.5s ease-in-out infinite; }}
    @keyframes glide-a {{
      0% {{ stroke-dashoffset: 360; opacity: 0.25; }}
      50% {{ stroke-dashoffset: -360; opacity: 1; }}
      100% {{ stroke-dashoffset: -840; opacity: 0.25; }}
    }}
    @keyframes glide-b {{
      0% {{ stroke-dashoffset: -820; opacity: 0.2; }}
      50% {{ stroke-dashoffset: -220; opacity: 0.85; }}
      100% {{ stroke-dashoffset: 380; opacity: 0.2; }}
    }}
    @keyframes crest-breathe {{
      0%, 100% {{ transform: scale(1); opacity: 0.85; }}
      50% {{ transform: scale(1.14); opacity: 1; }}
    }}
    @keyframes mote-left {{
      0%, 100% {{ transform: translateX(0px); opacity: 0; }}
      20%, 80% {{ opacity: 0.85; }}
      100% {{ transform: translateX(-420px); opacity: 0; }}
    }}
    @keyframes mote-right {{
      0%, 100% {{ transform: translateX(0px); opacity: 0; }}
      20%, 80% {{ opacity: 0.85; }}
      100% {{ transform: translateX(420px); opacity: 0; }}
    }}
    @media (prefers-reduced-motion: reduce) {{
      .wake-a, .wake-b, .crest-pulse, .mote-l, .mote-r {{ animation: none; }}
      .wake-a, .wake-b {{ stroke-dasharray: none; opacity: 0.65; }}
      .mote-l, .mote-r {{ opacity: 0; }}
    }}
  </style>
  <path d="M24 20 Q 312 10, 600 20 T 1176 20" fill="none" stroke="{t['line']}" stroke-width="1"/>
  <path d="M24 20 Q 312 30, 600 20 T 1176 20" fill="none" stroke="{t['line']}" stroke-width="0.9" stroke-opacity="0.7"/>
  <path class="wake-a" d="M24 20 Q 312 10, 600 20 T 1176 20" fill="none" stroke="url(#div-a-{mode})" stroke-width="2.2" stroke-linecap="round"/>
  <path class="wake-b" d="M24 20 Q 312 30, 600 20 T 1176 20" fill="none" stroke="url(#div-b-{mode})" stroke-width="1.6" stroke-linecap="round"/>
  <circle class="mote-l" cx="560" cy="20" r="2.2" fill="{t['spec']}"/>
  <circle class="mote-r" cx="640" cy="20" r="2.2" fill="{t['spec']}"/>
  <g class="crest-pulse">
    <polygon points="600,8 618,20 600,32 582,20" fill="{t['primary']}" fill-opacity="0.16" stroke="{t['primary']}" stroke-width="1.4"/>
    <polygon points="600,14 608,20 600,26 592,20" fill="{t['glow']}" fill-opacity="0.45"/>
    <circle cx="600" cy="20" r="2.6" fill="{t['spec']}"/>
  </g>
</svg>
"""


def build_cta_velocity(mode: str) -> str:
    t = THEMES[mode]
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 480 76" width="480" height="76" role="img" aria-labelledby="cta-title">
  <title id="cta-title">Download Black Marlin Downloader v1.0.0 ({mode})</title>
  <defs>
    <clipPath id="cta-clip-{mode}">
      <rect x="4" y="4" width="472" height="66" rx="12"/>
    </clipPath>
  </defs>
  <style>
    .t-disp {{ font-family: {FONT_DISPLAY}; }}
    .t-mono {{ font-family: {FONT_MONO}; }}
    .sheen {{ animation: sheen-pass 6s ease-in-out infinite; }}
    .arrow-glide {{ animation: arrow-bob 2.4s ease-in-out infinite; }}
    @keyframes sheen-pass {{
      0%, 58% {{ transform: translateX(-120px); opacity: 0; }}
      72% {{ opacity: 0.22; }}
      90%, 100% {{ transform: translateX(560px); opacity: 0; }}
    }}
    @keyframes arrow-bob {{
      0%, 100% {{ transform: translateY(0px); }}
      50% {{ transform: translateY(3px); }}
    }}
    @media (prefers-reduced-motion: reduce) {{
      .sheen {{ animation: none; opacity: 0; }}
      .arrow-glide {{ animation: none; }}
    }}
  </style>
  <rect x="4" y="8" width="472" height="66" rx="12" fill="{t['cta_edge']}"/>
  <rect x="4" y="4" width="472" height="66" rx="12" fill="{t['cta_bg']}" stroke="{t['cta_edge']}" stroke-width="1.2"/>
  <g clip-path="url(#cta-clip-{mode})">
    <polygon class="sheen" points="0,4 58,4 22,70 -36,70" fill="#ffffff"/>
  </g>
  <g transform="translate(18 14) scale(0.095)">
    <path fill="{t['cta_ink']}" fill-opacity="0.88" d="{MARLIN_PATHS['dorsal']}"/>
    <path fill="{t['cta_ink']}" d="{MARLIN_PATHS['body']}"/>
    <path fill="{t['cta_ink']}" fill-opacity="0.72" d="{MARLIN_PATHS['tail']}"/>
  </g>
  <text x="76" y="34" fill="{t['cta_ink']}" font-size="18.5" font-weight="700" letter-spacing="-0.3" class="t-disp">Download Black Marlin v1.0.0</text>
  <text x="76" y="54" fill="{t['cta_sub']}" font-size="11.5" font-weight="500" class="t-mono">Windows x64 Installer · 19.0 MiB · Free (GPL-3.0)</text>
  <g class="arrow-glide">
    <path d="M442 25v16m-7-6l7 7 7-7" fill="none" stroke="{t['cta_ink']}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/>
  </g>
  <path d="M432 48v4h20v-4" fill="none" stroke="{t['cta_ink']}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
"""


def main() -> int:
    outputs = []
    for mode in ("light", "dark"):
        div_path = OUT / "dividers" / f"divider-slipstream-{mode}.svg"
        div_xml = build_divider_slipstream(mode)
        ET.fromstring(div_xml.encode("utf-8"))
        div_path.parent.mkdir(parents=True, exist_ok=True)
        div_path.write_text(div_xml, encoding="utf-8")
        outputs.append(div_path)

        cta_path = OUT / "branding" / f"cta-velocity-{mode}.svg"
        cta_xml = build_cta_velocity(mode)
        ET.fromstring(cta_xml.encode("utf-8"))
        cta_path.parent.mkdir(parents=True, exist_ok=True)
        cta_path.write_text(cta_xml, encoding="utf-8")
        outputs.append(cta_path)

    for p in outputs:
        print(f"generated: {p.relative_to(ROOT).as_posix()} ({p.stat().st_size} B)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
