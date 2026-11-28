#!/usr/bin/env python3
"""Faithfully export the selected commit's launcher VectorDrawable; never edit repository inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image, ImageCms


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if not __debug__:
        raise SystemExit("Publication checks require normal Python execution; remove -O or PYTHONOPTIMIZE.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "assets")
    args = parser.parse_args()
    repo, out = args.repo.resolve(), args.out.resolve()
    if out == repo or repo in out.parents:
        parser.error("--out must be outside the application repository")
    commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--verify", args.ref + "^{commit}"], text=True).strip()
    source_path = "android/app/src/main/res/drawable/ic_launcher.xml"
    source = subprocess.check_output(["git", "-C", str(repo), "show", f"{commit}:{source_path}"])
    root = ET.fromstring(source)
    ns = "{http://schemas.android.com/apk/res/android}"
    assert root.tag == "vector"
    width, height = root.get(ns + "viewportWidth"), root.get(ns + "viewportHeight")
    def svg_element(child):
        if child.tag == "path":
            assert set(child.attrib) == {ns + "fillColor", ns + "pathData"}
            return f'<path fill="{child.get(ns + "fillColor")}" d="{child.get(ns + "pathData")}"/>'
        assert child.tag == "group"
        assert set(child.attrib) <= {ns + name for name in ("translateX", "translateY", "scaleX", "scaleY")}
        tx, ty = (child.get(ns + name, "0") for name in ("translateX", "translateY"))
        sx, sy = (child.get(ns + name, "1") for name in ("scaleX", "scaleY"))
        return f'<g transform="translate({tx} {ty}) scale({sx} {sy})">' + "".join(map(svg_element, child)) + '</g>'

    paths = [(child.get(ns + "fillColor"), child.get(ns + "pathData")) for child in root.iter("path")]
    markup = "\n".join(map(svg_element, root))
    out.mkdir(parents=True, exist_ok=True)
    icon = out / "black-marlin-store-icon.svg"
    icon.write_text(f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 {width} {height}">
  <title>Black Marlin launcher artwork</title>
  <desc>Launcher artwork on the opaque store tile. The mark's paths and colors are unchanged.</desc>
  <rect width="{width}" height="{height}" fill="#354230"/>
{markup}
</svg>
''', encoding="utf-8")
    assert [(p.get("fill"), p.get("d")) for p in ET.parse(icon).getroot().iter("{http://www.w3.org/2000/svg}path")] == paths
    feature = out / "black-marlin-feature-graphic-draft.svg"
    feature.write_text(f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <title>Black Marlin feature graphic draft</title>
  <desc>Original typography layout for review. This is promotional artwork, not an app screenshot.</desc>
  <rect width="1024" height="500" fill="#C4F1EB"/>
  <path d="M660 500 C700 395 846 398 1024 257 L1024 500Z" fill="#A5DED6"/>
  <path d="M795 500 C820 447 944 396 1024 425 L1024 500Z" fill="#85CBC1"/>
  <g transform="translate(90 64) scale(0.6666666666666667)">
{markup.replace("#C7CAC5", "#354230")}
  </g>
  <g fill="#112C2D" font-family="DejaVu Sans,sans-serif">
    <text x="186" y="110" font-size="28" font-weight="700">Black Marlin</text>
    <text x="90" y="234" font-size="62" font-weight="700">Your downloads.</text>
    <text x="90" y="309" font-size="62" font-weight="700">Your device.</text>
    <text x="94" y="378" font-size="25" fill="#365D59">Direct links, clear controls.</text>
  </g>
</svg>
''', encoding="utf-8")
    profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    outputs = []
    for svg, mode, dimensions in [(icon, "RGBA", (512, 512)), (feature, "RGB", (1024, 500))]:
        png = svg.with_suffix(".png")
        subprocess.run(["inkscape", str(svg), "--export-type=png", "--export-filename=" + str(png),
                        "--export-width=" + str(dimensions[0]), "--export-height=" + str(dimensions[1])], check=True)
        with Image.open(png) as image:
            image.convert(mode).save(png, format="PNG", icc_profile=profile, optimize=True)
        with Image.open(png) as image:
            assert image.size == dimensions and image.mode == mode
            if mode == "RGBA":
                assert image.getchannel("A").getextrema() == (255, 255)
                assert png.stat().st_size <= 1024 * 1024
        outputs.append({"svg": svg.name, "png": png.name, "dimensions": dimensions, "mode": mode,
            "pngBytes": png.stat().st_size, "pngSha256": digest(png), "svgSha256": digest(svg), "status": "draft-for-review"})
    manifest = {"source": source_path, "sourceSha256": hashlib.sha256(source).hexdigest(), "sourceCommit": commit,
        "sourceViewport": [width, height], "pathCount": len(paths), "pathGeometryAndColorsExact": True,
        "tool": subprocess.check_output(["inkscape", "--version"], text=True).strip(),
        "iconMaskOrShadowAdded": False, "outputs": outputs}
    (out / "asset-provenance.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
