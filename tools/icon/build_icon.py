"""Resize the square PNG export of art/BMD Black Marlin Icon.svg.

Writes the desktop, extension, website and documentation icons.
Run from the repository root: python -I tools/icon/build_icon.py
"""

import base64
import re
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "art" / "Black Marlin Downloader.png"
APP = ROOT / "crates" / "app" / "assets" / "icons" / "app"
EXT = ROOT / "extension" / "icons"
WEB = ROOT / "website"
SIZES = [16, 20, 24, 32, 48, 64, 128, 256]
FAVICON_SIZES = [16, 32, 48, 64]


def save_ico(path: Path, sizes: list[int], icons: dict[int, Image.Image]) -> None:
    icons[sizes[-1]].save(
        path,
        sizes=[(s, s) for s in sizes],
        append_images=[icons[s] for s in sizes[:-1]],
    )


def main() -> None:
    src = Image.open(SRC).convert("RGBA")
    icons = {s: src.resize((s, s), Image.LANCZOS) for s in SIZES}
    for size in SIZES:
        icons[size].save(APP / f"bmd-{size}.png")
    save_ico(APP / "bmd.ico", SIZES, icons)
    for size in (16, 32, 48, 128):
        icons[size].save(EXT / f"icon-{size}.png")

    video = EXT.parent / "video.js"
    logo = base64.b64encode((EXT / "icon-48.png").read_bytes())
    source, replaced = re.subn(
        rb'(?<=const LOGO = "data:image/png;base64,)[^"]+',
        logo,
        video.read_bytes(),
    )
    if replaced != 1:
        raise ValueError("Expected one embedded icon in extension/video.js")
    video.write_bytes(source)

    save_ico(WEB / "favicon.ico", FAVICON_SIZES, icons)
    assets = WEB / "assets"
    icons[32].save(assets / "favicon-32.png")
    icons[64].save(assets / "logo-64.png")
    icons[256].save(assets / "logo-256.webp", lossless=True)
    src.resize((180, 180), Image.LANCZOS).save(assets / "apple-touch-icon.png")
    src.resize((640, 640), Image.LANCZOS).save(assets / "logo-640.png")
    src.resize((320, 320), Image.LANCZOS).save(ROOT / "docs" / "images" / "logo.png")
    print("Wrote desktop, extension, website and documentation icons")


if __name__ == "__main__":
    main()
