#!/usr/bin/env python3
"""A fake BMD release for Update now's end-to-end tests (never a real one): the asset, its
SHA256SUMS.txt (releases aren't signed), and GitHub's latest-release JSON for BMD_UPDATE_FEED.

    mkrel.py --out DIR --base URL --asset NAME --kind zip|targz|file --program FILE
             [--version 9.9.9] [--sums good|bad] [--asset-base URL] [--pad MB]

zip / targz: a folder named after the asset holding the program and a NEW-COPY.txt marker (the
portable zip, the .tar.gz). file: the program itself with a marker appended (an AppImage's place).
--sums bad lists a wrong checksum; --asset-base serves the asset elsewhere (a slow server);
--pad adds that many MB of random bytes to the archive (a long download).
"""
import argparse
import hashlib
import io
import json
import os
import tarfile
import zipfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--version", default="9.9.9")
    ap.add_argument("--asset", required=True)
    ap.add_argument("--kind", choices=["zip", "targz", "file"], required=True)
    ap.add_argument("--program", required=True)
    ap.add_argument("--program-name", default=None)
    ap.add_argument("--sums", choices=["good", "bad"], default="good")
    ap.add_argument("--asset-base", default=None)
    ap.add_argument("--pad", type=int, default=0)
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    marker = f"NEW-COPY {a.version}\n".encode()
    prog = open(a.program, "rb").read()
    name = a.program_name or os.path.basename(a.program)
    folder = a.asset
    for ext in (".tar.gz", ".zip"):
        if folder.endswith(ext):
            folder = folder[: -len(ext)]
    asset_path = os.path.join(a.out, a.asset)
    pad = os.urandom(a.pad * 1024 * 1024) if a.pad else b""
    if a.kind == "zip":
        with zipfile.ZipFile(asset_path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr(f"{folder}/{name}", prog)
            z.writestr(f"{folder}/NEW-COPY.txt", marker)
            if pad:
                z.writestr(f"{folder}/padding.bin", pad, compress_type=zipfile.ZIP_STORED)
    elif a.kind == "targz":
        files = [(name, prog, 0o755), ("NEW-COPY.txt", marker, 0o644)]
        if pad:
            files.append(("padding.bin", pad, 0o644))
        with tarfile.open(asset_path, "w:gz") as t:
            for n, data, mode in files:
                info = tarfile.TarInfo(f"{folder}/{n}")
                info.size, info.mode = len(data), mode
                t.addfile(info, io.BytesIO(data))
    else:
        open(asset_path, "wb").write(prog + b"\n" + marker)
        if os.name != "nt":
            os.chmod(asset_path, 0o755)

    digest = hashlib.sha256(open(asset_path, "rb").read()).hexdigest()
    if a.sums == "bad":
        digest = "0" * 64
    sums = os.path.join(a.out, "SHA256SUMS.txt")
    with open(sums, "w", newline="\n") as f:
        f.write(f"{digest}  {a.asset}\n")
    base = a.base.rstrip("/")
    asset_base = (a.asset_base or base).rstrip("/")
    json.dump({
        "tag_name": f"v{a.version}",
        "published_at": "2026-01-01T00:00:00Z",
        "html_url": f"{base}/release.html",
        "body": "## A test release\n\n- Made by Update now's end-to-end tests. Not a real release.",
        "assets": [
            {"name": a.asset, "browser_download_url": f"{asset_base}/{a.asset}"},
            {"name": "SHA256SUMS.txt", "browser_download_url": f"{base}/SHA256SUMS.txt"},
        ],
    }, open(os.path.join(a.out, "latest.json"), "w"), indent=1)
    print(f"{a.asset}: sha256 {digest[:16]}... ({a.sums})")


if __name__ == "__main__":
    main()
