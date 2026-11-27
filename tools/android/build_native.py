#!/usr/bin/env python3
"""Build both real Android JNI libraries with pinned NDK/API and locked Rust dependencies."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tomllib
from native_common import ROOT, NDK_VERSION, API, ABIS, LIBRARY, OUTPUT, digest, source_info, ndk_path, ndk_bin, tool, atomic_json
from verify_native import inspect, verify


def package_fields(path: Path) -> tuple[dict, Path | None]:
    package = tomllib.loads(path.read_text()).get("package", {}).copy()
    workspace_root = None
    for folder in path.parents:
        manifest = folder / "Cargo.toml"
        if manifest.is_file():
            workspace = tomllib.loads(manifest.read_text()).get("workspace", {})
            if "package" in workspace:
                workspace_root = folder
                for key, value in list(package.items()):
                    if isinstance(value, dict) and value.get("workspace") is True:
                        package[key] = workspace["package"].get(key, "")
                break
    return package, workspace_root


def notices(manifests: set[Path], output: Path) -> tuple[list[dict], list[str]]:
    packages, missing = [], []
    vendor_root = ROOT / "android/third_party/licenses"
    vendor_index = json.loads((vendor_root / "index.json").read_text())
    inputs = [(path, *package_fields(path)) for path in manifests]
    inputs.sort(key=lambda item: (item[1].get("name", ""), str(item[1].get("version", ""))))
    chunks = ["Black Marlin Downloader — Rust dependency notices\n", "Generated from Cargo compiler-artifact manifests in this exact locked native build.\nIncludes runtime crates and build/procedural-macro dependencies for transparency.\nThe application is GPL-3.0-or-later; individual dependency terms remain below.\n"]
    for path, package, workspace_root in inputs:
        name, version = package.get("name", path.parent.name), str(package.get("version", ""))
        license_id = package.get("license", "")
        files: dict[Path, str] = {}
        if package.get("license-file"):
            file = path.parent / package["license-file"]
            files[file] = file.name
        roots = [path.parent] + ([workspace_root] if workspace_root else [])
        if name == "aws-lc-sys":
            roots.append(path.parent / "aws-lc")
        for root in roots:
            for file in root.iterdir():
                if file.is_file() and file.name.lower().startswith(("license", "licence", "copying", "notice")):
                    files[file] = file.name
            for folder in (root / "licenses", root / "LICENSES"):
                if folder.is_dir():
                    for file in folder.iterdir():
                        if file.is_file():
                            files[file] = folder.name + "/" + file.name
        selected_license = license_id or "SEE LICENSE FILE"
        for record in vendor_index:
            if record["name"] == name and record["version"] == version:
                vcs = path.parent / ".cargo_vcs_info.json"
                if not vcs.is_file() or json.loads(vcs.read_text())["git"]["sha1"] != record["ref"]:
                    raise RuntimeError(f"License provenance differs from the published source for {name} {version}")
                files[vendor_root / record["file"]] = record["url"]
                if "MIT" in license_id:
                    selected_license = "MIT (selected from " + license_id + ")"
        available = sorted(((file, label) for file, label in files.items() if file.is_file() and file.stat().st_size < 2 * 1024 * 1024), key=lambda item: item[1])
        entry = {"name": name, "version": version, "license": selected_license, "repository": package.get("repository", ""), "licenseFiles": [label for _, label in available]}
        packages.append(entry)
        chunks.append("\n" + "=" * 72 + f"\n{name} {version}\nLicense: {entry['license']}\nRepository: {entry['repository']}\n")
        if not available:
            missing.append(f"{name} {version}")
            chunks.append("No license text included in the published crate package. Source review is required before packaging.\n")
        for file, label in available:
            chunks.append(f"\n--- {label} ---\n{file.read_text(errors='replace')}\n")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(chunks))
    return packages, missing


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--jobs", type=int, default=max(1, min(4, os.cpu_count() or 1)))
    args = parser.parse_args()
    ndk = ndk_path(args.ndk)
    binaries = ndk_bin(ndk)
    revision = source_info()
    rustc = subprocess.check_output(["rustc", "--version"], text=True).strip()
    if not rustc.startswith("rustc 1.98.0 "):
        raise RuntimeError("This Android build is pinned to Rust 1.98.0; install/select that toolchain")
    manifests: set[Path] = set()
    artifacts = {}
    for abi, (target, _machine) in ABIS.items():
        env = os.environ.copy()
        env["CARGO_INCREMENTAL"] = "0"
        compiler = str(tool(binaries, target + str(API) + "-clang"))
        archiver = str(tool(binaries, "llvm-ar"))
        env["CC_" + target.replace("-", "_")] = compiler
        env["AR_" + target.replace("-", "_")] = archiver
        env["CARGO_TARGET_" + target.replace("-", "_").upper() + "_LINKER"] = compiler
        flags = env.get("CARGO_ENCODED_RUSTFLAGS", "").split("\x1f") if env.get("CARGO_ENCODED_RUSTFLAGS") else shlex.split(env.get("RUSTFLAGS", ""))
        flags.extend(["-C", "link-arg=-Wl,-z,max-page-size=16384"])
        env["CARGO_ENCODED_RUSTFLAGS"] = "\x1f".join(flags)
        command = ["cargo", "rustc", "--locked", "-p", "bmd-android-bridge", "--lib", "--crate-type", "cdylib", "--no-default-features", "--target", target, "--profile", "android", "--jobs", str(args.jobs), "--message-format=json-render-diagnostics"]
        print(f"Building {abi} with NDK {NDK_VERSION}, API {API}, Rust 1.98.0", flush=True)
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, text=True)
        assert process.stdout is not None
        for line in process.stdout:
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                print(line, end="", flush=True)
                continue
            if item.get("reason") == "compiler-artifact" and item.get("manifest_path"):
                manifests.add(Path(item["manifest_path"]))
            if item.get("reason") == "compiler-message":
                print(item["message"].get("rendered", "Compiler diagnostic"), end="", flush=True)
        if process.wait():
            raise RuntimeError(f"Cargo failed for {abi}; no successful manifest was produced")
        target_dir = Path(env.get("CARGO_TARGET_DIR", ROOT / "target"))
        if not target_dir.is_absolute():
            target_dir = ROOT / target_dir
        compiled = target_dir / target / "android" / LIBRARY
        packaged = args.output / "jniLibs" / abi / LIBRARY
        packaged.parent.mkdir(parents=True, exist_ok=True)
        temporary = packaged.with_suffix(".so.tmp")
        shutil.copyfile(compiled, temporary)
        subprocess.run([str(tool(binaries, "llvm-strip")), "--strip-unneeded", str(temporary)], check=True)
        metadata = inspect(temporary, abi, binaries)
        temporary.replace(packaged)
        artifacts[abi] = metadata
    if revision["nativeSourceSha256"] != source_info()["nativeSourceSha256"]:
        raise RuntimeError("Native source changed during the build; rebuild to obtain consistent provenance")
    notice_path = args.output / "assets/THIRD_PARTY_RUST_NOTICES.txt"
    packages, missing = notices(manifests, notice_path)
    atomic_json(args.output / "assets/native-build.json", {"schemaVersion": 1, "profile": "android", "api": API, "ndk": NDK_VERSION, "rustc": rustc, "source": revision, "artifacts": artifacts, "noticesSha256": digest(notice_path), "packages": packages, "licenseTextReviewRequired": missing})
    verify(args.output, binaries)
    print(json.dumps({"built": list(artifacts), "nativeSourceSha256": revision["nativeSourceSha256"], "licenseTextReviewRequired": missing}, indent=2))

if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Native build failed: {error}", file=sys.stderr)
        sys.exit(1)
