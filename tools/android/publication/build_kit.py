#!/usr/bin/env python3
"""Recreate a complete publication-preparation draft outside the application checkout."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    if not __debug__:
        raise SystemExit("Publication checks require normal Python execution; remove -O or PYTHONOPTIMIZE.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--ref", default="HEAD", help="Source commit/ref; resolved once for the complete kit")
    parser.add_argument("--out", type=Path, required=True, help="New or empty output directory outside the checkout")
    parser.add_argument("--zip", type=Path, help="Archive path outside the output directory")
    parser.add_argument("--merged-manifest", type=Path)
    parser.add_argument("--evidence-image", type=Path)
    parser.add_argument("--evidence-provenance", type=Path)
    args = parser.parse_args()
    repo, out = args.repo.resolve(), args.out.resolve()
    archive = args.zip.resolve() if args.zip else out.with_suffix(".zip")
    if out == repo or repo in out.parents:
        parser.error("--out must be outside the application repository")
    if archive == out or out in archive.parents or archive == repo or repo in archive.parents:
        parser.error("--zip must be outside the output directory and application repository")
    if out.exists() and any(out.iterdir()):
        parser.error("--out must be new or empty; existing prepared material is not overwritten")
    if bool(args.evidence_image) != bool(args.evidence_provenance):
        parser.error("supply both evidence paths, or neither")
    commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--verify", args.ref + "^{commit}"], text=True).strip()
    source = Path(__file__).resolve().parent
    templates = source / "templates"
    if not templates.is_dir():
        parser.error("run build_kit.py from the complete repository publication-tool directory")
    out.mkdir(parents=True, exist_ok=True)
    for path in sorted(templates.rglob("*")):
        if path.is_file():
            target = out / path.relative_to(templates)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    for name in ("prepare_metadata.py", "build_assets.py", "package_kit.py"):
        shutil.copyfile(source / name, out / name)
    metadata = [sys.executable, str(source / "prepare_metadata.py"), "--repo", str(repo), "--ref", commit, "--out", str(out)]
    for flag, value in [("--merged-manifest", args.merged_manifest), ("--evidence-image", args.evidence_image), ("--evidence-provenance", args.evidence_provenance)]:
        if value is not None:
            metadata += [flag, str(value.resolve())]
    subprocess.run(metadata, check=True)
    subprocess.run([sys.executable, str(source / "build_assets.py"), "--repo", str(repo), "--ref", commit, "--out", str(out / "assets")], check=True)
    subprocess.run([sys.executable, str(source / "package_kit.py"), "--kit", str(out), "--zip", str(archive)], check=True)


if __name__ == "__main__":
    main()
