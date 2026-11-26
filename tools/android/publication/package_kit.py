#!/usr/bin/env python3
"""Validate prepared metadata/assets and package a review draft; never award independent approval."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from PIL import Image


def main():
    if not __debug__:
        raise SystemExit("Publication checks require normal Python execution; remove -O or PYTHONOPTIMIZE.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kit", type=Path, required=True)
    parser.add_argument("--zip", type=Path, required=True)
    args = parser.parse_args()
    kit, archive = args.kit.resolve(), args.zip.resolve()
    if archive == kit or kit in archive.parents:
        parser.error("write the ZIP outside the kit directory")
    listing = json.loads((kit / "store-listing-en-US.json").read_text(encoding="utf-8"))
    snapshot = json.loads((kit / "source-snapshot.json").read_text(encoding="utf-8"))
    provenance = json.loads((kit / "assets/asset-provenance.json").read_text(encoding="utf-8"))
    assert listing["sourceCommit"] == snapshot["commit"] == provenance["sourceCommit"], "source identities differ"
    for field, filename in [("title", "title"), ("shortDescription", "short-description"), ("fullDescription", "full-description")]:
        assert (kit / f"listing-{filename}.txt").read_text(encoding="utf-8") == listing[field] + "\n"
        assert len(listing[field]) == listing["characterCounts"][field] <= listing["characterLimits"][field]
    images = []
    for entry in provenance["outputs"]:
        path = kit / "assets" / entry["png"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["pngSha256"]
        svg = kit / "assets" / entry["svg"]
        assert hashlib.sha256(svg.read_bytes()).hexdigest() == entry["svgSha256"]
        with Image.open(path) as image:
            assert image.size == tuple(entry["dimensions"]) and image.mode == entry["mode"]
            assert image.info.get("icc_profile"), "sRGB profile missing"
            if image.mode == "RGBA":
                assert image.getchannel("A").getextrema() == (255, 255)
                assert path.stat().st_size <= 1024 * 1024
        images.append({"file": path.name, "dimensions": entry["dimensions"], "mode": entry["mode"], "bytes": path.stat().st_size})
    review_file = kit / "INDEPENDENT_REVIEW.md"
    validation = {"status": "technical-checks-completed-see-separate-independent-review",
        "checkedAtUTC": datetime.now(timezone.utc).isoformat(), "sourceCommit": snapshot["commit"],
        "listingCounts": listing["characterCounts"], "imageChecks": images,
        "sourcePathIdentity": provenance["pathGeometryAndColorsExact"],
        "reviewReceipt": review_file.name, "reviewReceiptSha256": hashlib.sha256(review_file.read_bytes()).hexdigest(),
        "storeScreenshotsReady": False, "playSubmitted": False, "productionSigningIdentityProvided": False,
        "currentMergedManifestSupplied": snapshot["releaseMergedManifest"] is not None}
    (kit / "kit-validation.json").write_text(json.dumps(validation, indent=2) + "\n", encoding="utf-8")
    files = sorted(p for p in kit.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    (kit / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(kit).as_posix()}\n" for p in files), encoding="utf-8")
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(p for p in kit.rglob("*") if p.is_file()):
            info = zipfile.ZipInfo("android-publication-kit/" + path.relative_to(kit).as_posix(), date_time=(2026, 11, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zipped.writestr(info, path.read_bytes())
    with zipfile.ZipFile(archive) as zipped:
        assert zipped.testzip() is None
    print(json.dumps({"archive": str(archive), "bytes": archive.stat().st_size,
                      "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()}, indent=2))


if __name__ == "__main__":
    main()
