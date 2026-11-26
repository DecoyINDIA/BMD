"""Shared, dependency-free Android native artifact provenance helpers."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[2]
NDK_VERSION = "29.0.14206865"
API = 26
ABIS = {"arm64-v8a": ("aarch64-linux-android", 183), "x86_64": ("x86_64-linux-android", 62)}
LIBRARY = "libbmd_android_bridge.so"
SYMBOLS = {
    "Java_com_blackmarlin_downloader_bridge_NativeEngine_nativeInitialize",
    "Java_com_blackmarlin_downloader_bridge_NativeEngine_nativeCommand",
    "Java_com_blackmarlin_downloader_bridge_NativeEngine_nativeSnapshot",
}
OUTPUT = ROOT / "android/app/build/generated/native"

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def native_inputs() -> list[Path]:
    paths = [ROOT / "Cargo.toml", ROOT / "Cargo.lock", ROOT / ".cargo/config.toml", ROOT / "LICENSE"]
    paths.extend(p for p in (ROOT / "android/third_party/licenses").rglob("*") if p.is_file())
    for folder in ("crates/engine", "crates/android-bridge", "tools/android"):
        for path in (ROOT / folder).rglob("*"):
            if path.is_file() and path.suffix in {".rs", ".toml", ".py"} and "__pycache__" not in path.parts:
                paths.append(path)
    return sorted(set(paths))

def source_info() -> dict:
    files = {p.relative_to(ROOT).as_posix(): digest(p) for p in native_inputs()}
    fingerprint = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    def git(*args: str) -> str:
        run = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=False)
        return run.stdout.strip() if run.returncode == 0 else ""
    commit = git("rev-parse", "HEAD") or None
    dirty = bool(git("status", "--porcelain", "--untracked-files=all", "--", "Cargo.toml", "Cargo.lock", ".cargo/config.toml", "LICENSE", "crates/engine", "crates/android-bridge", "tools/android", "android/third_party/licenses"))
    revision = (commit + ("+dirty." + fingerprint[:12] if dirty else "")) if commit else "sha256:" + fingerprint
    return {"gitCommit": commit, "dirty": dirty, "revision": revision, "nativeSourceSha256": fingerprint, "inputFiles": files}

def ndk_path(explicit: str | None = None) -> Path:
    supplied = explicit or os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    path = Path(supplied) if supplied else Path(sdk or "") / "ndk" / NDK_VERSION
    properties = path / "source.properties"
    if not properties.is_file() or f"Pkg.Revision = {NDK_VERSION}" not in properties.read_text():
        raise RuntimeError(f"Install NDK {NDK_VERSION}, or set ANDROID_NDK_HOME to that version")
    return path.resolve()

def ndk_bin(ndk: Path) -> Path:
    tag = {"Linux": "linux-x86_64", "Darwin": "darwin-x86_64", "Windows": "windows-x86_64"}.get(platform.system())
    if not tag:
        raise RuntimeError("Unsupported NDK host; use Linux, macOS, Windows or the Android CI workflow")
    path = ndk / "toolchains/llvm/prebuilt" / tag / "bin"
    if not path.is_dir():
        raise RuntimeError(f"NDK compiler directory missing: {path}")
    return path

def tool(binaries: Path, name: str) -> Path:
    for suffix in ("", ".exe", ".cmd"):
        path = binaries / (name + suffix)
        if path.is_file():
            return path
    raise RuntimeError(f"Missing NDK tool: {name}")

def atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    tmp.replace(path)
