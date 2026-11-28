# BMD Velocity Motion Pipeline (`scripts/readme/velocity/`)

Deterministic headless-Chrome (`Three.js` / WebGL2 GLSL shaders + HTML5 Canvas 2D) + `FFmpeg` rendering pipeline producing light/dark **Animated WebP**, **static PNG fallbacks**, and **Level 3 pure-SVG** assets for the root `README.md`.

## Prerequisites
- **Node.js** `22+` (`npm install` inside `scripts/readme/velocity/` — `node_modules/` is gitignored)
- **Google Chrome** or **Microsoft Edge** (headless WebGL2 via ANGLE SwiftShader)
- **FFmpeg** (`libwebp_anim`, `lanczos`, `tile`) on `PATH`
- **Python 3.12+** (for Level 3 SVGs and `scripts/readme/validate_assets.py`)

## Layout
- `render.mjs` — Local ephemeral HTTP server (`127.0.0.1:0`), deterministic virtual-time Chrome frame capture (`window.renderFrame`), `libwebp_anim` budget encoder (`encodeWebpWithBudget`), contact-sheet & loop-seam generators.
- `build.mjs` — Master scene manifest (`SCENE_SPECS` for 5 Level 5 + 7 Level 4 compositions) and batch renderer outputting `assets/readme/<subdir>/<id>-{light,dark}.webp` + `<id>-static-{light,dark}.png`.
- `export_fallbacks.mjs` — Re-exports static keyframe PNG fallbacks from captured frames.
- `generate_svgs.py` — Generates Level 3 pure-SVG assets (`divider-slipstream-{light,dark}.svg`, `cta-velocity-{light,dark}.svg`).
- `verify_readme_render.mjs` — Captures Desktop (`1280px`) and Mobile (`380px`) Light/Dark README previews and checks for broken images or horizontal overflow.
- `lib/core.mjs` — Hydrodynamic Velocity palettes, offline `@font-face` loader (`website/vendor/fonts/`), easing curves, and canonical `art/BMD Black Marlin.svg` vector geometry.
- `scenes/*.html` — Individual WebGL2/Canvas2D compositions (`hero-velocity`, `engine-planner`, `benchmark-telemetry`, `living-adoption`, `closing-horizon`, `product-cinema`, `bittorrent-swarm`, `browser-to-desktop`, `architecture-blueprint`, `global-rtl`, `download-platforms`, `privacy-freedom`).

## Reproducible Build Commands
Run from repository root (`d:\XOLVER\iDM`):

```sh
# 1. Install Node dependencies (gitignored in node_modules/)
npm --prefix scripts/readme/velocity install --no-audit --no-fund

# 2. Render all 12 animated WebP + static PNG fallback pairs (light & dark)
node scripts/readme/velocity/build.mjs

#    Or render specific scenes only:
node scripts/readme/velocity/build.mjs --only hero-velocity,closing-horizon

# 3. Regenerate Level 3 pure-SVG dividers & download CTA
python scripts/readme/velocity/generate_svgs.py

# 4. Verify rendered README (1280px desktop & 380px mobile, dark & light) and validate all assets
node scripts/readme/velocity/verify_readme_render.mjs
python scripts/readme/validate_assets.py
python -m unittest scripts/readme/test_analytics.py
```

Raw PNG frames and review contact sheets go into the ignored `target/readme-velocity/` directory.
After refreshing the app screenshots, run `node scripts/readme/velocity/build.mjs --only product-cinema` to rebuild the screenshot animation in both themes.
