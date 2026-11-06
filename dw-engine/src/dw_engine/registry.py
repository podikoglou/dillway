"""Model registry: immutable version dirs plus a verified latest pointer.

Layout under the models root:

    <prefix>/<cheat>/<version>/model.onnx
    <prefix>/<cheat>/<version>/manifest.json   (written last: commit marker)
    <prefix>/<cheat>/latest.json               (pointer to one version)

The version directories are immutable, so concurrent training runs never
overwrite each other's work. Version names start with a UTC timestamp and
end with a random suffix, so they sort chronologically and two runs that
start in the same second cannot collide.

`latest.json` is a cache, not the source of truth. The authoritative
answer is the highest version directory that carries a manifest. A
pointer left behind by a slower, older run is detected by comparing it
with the listing and is repaired in place. That makes an out-of-order
pointer write harmless instead of wrong.

Nothing here needs locks: every run writes only its own version
directory, and the only shared file is repaired on read.
"""

import hashlib
import json
import re
import sys
from pathlib import Path

from . import VERSION
from .config import MODELS_PREFIX

POINTER_NAME = "latest.json"
MANIFEST_NAME = "manifest.json"
MODEL_NAME = "model.onnx"
SCHEMA = 1


def list_versions(cheat_dir: Path) -> list[str]:
    """Version directory names under one cheat, oldest first."""
    if not cheat_dir.is_dir():
        return []
    names = [
        entry.name
        for entry in cheat_dir.iterdir()
        if entry.is_dir() and re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{6}", entry.name)
    ]
    return sorted(names)


def committed_versions(models_root: Path, cheat: str) -> list[str]:
    """Version directories whose artifacts are complete, oldest first.

    manifest.json is written last, so its presence marks a finished export;
    an in-flight run therefore has no committed version yet and cannot be
    picked as latest.
    """
    cheat_dir = models_root / MODELS_PREFIX / cheat
    return [
        version
        for version in list_versions(cheat_dir)
        if (cheat_dir / version / MANIFEST_NAME).is_file()
        and (cheat_dir / version / MODEL_NAME).is_file()
    ]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_pointer(models_root: Path, cheat: str, version: str) -> dict:
    """Read a committed version and describe it relative to the models root.

    Raises if the model bytes do not match the checksum recorded in the
    version's manifest, so a damaged artifact is never advertised as latest.
    """
    version_dir = models_root / MODELS_PREFIX / cheat / version
    manifest = json.loads((version_dir / MANIFEST_NAME).read_text())
    model_path = version_dir / MODEL_NAME
    digest = sha256_of(model_path)
    expected = manifest.get("model_sha256")
    if expected and digest != expected:
        raise RuntimeError(
            f"{model_path} does not match its manifest checksum "
            f"({digest} != {expected}); refusing to publish it as latest."
        )
    return {
        "schema": SCHEMA,
        "cheat": cheat,
        "version": version,
        "dw_engine_version": VERSION,
        "model": {
            "path": _key(models_root, model_path),
            "bytes": model_path.stat().st_size,
            "sha256": digest,
        },
        "manifest_path": _key(models_root, version_dir / MANIFEST_NAME),
        "window_seconds": manifest.get("window_seconds"),
        "features": len(manifest.get("features", [])),
        "labels": manifest.get("labels"),
        "dataset": manifest.get("dataset"),
        "validation": manifest.get("train", {}).get("validation"),
        "trained_utc": manifest.get("train", {}).get("training_utc"),
        "published_utc": _now(),
    }


def publish(models_root: Path, cheat: str) -> dict:
    """Point latest.json at the newest verified version and return the pointer.

    Resolving instead of writing the caller's own version means a slow run
    finishing after a newer one still publishes the newer version.
    """
    pointer = resolve_latest(models_root, cheat)
    if pointer is None:
        raise RuntimeError(
            f"No verified committed versions for {cheat!r} under {models_root}."
        )
    return pointer


def resolve_latest(models_root: Path, cheat: str, repair: bool = True) -> dict | None:
    """Return the newest verified version pointer, repairing a stale one.

    Verification covers the pointer's checksum against the version manifest
    and the model bytes on disk. A pointer left by a slower, older run, or
    one naming a damaged artifact, is rebuilt from the newest version that
    still verifies.
    """
    versions = committed_versions(models_root, cheat)
    if not versions:
        return None
    pointer_path = _pointer_path(models_root, cheat)

    pointer = None
    if pointer_path.is_file():
        try:
            pointer = json.loads(pointer_path.read_text())
        except (OSError, json.JSONDecodeError):
            pointer = None
    if (
        pointer
        and _verify(models_root, pointer)
        and pointer.get("version") == versions[-1]
    ):
        return pointer

    for version in reversed(versions):
        try:
            fresh = build_pointer(models_root, cheat, version)
        except (OSError, json.JSONDecodeError, RuntimeError) as exc:
            if version == versions[-1]:
                print(
                    f"warning: skipping unverified {cheat} version {version}: {exc}",
                    file=sys.stderr,
                )
            continue
        if repair:
            write_pointer(pointer_path, fresh)
        return fresh
    return None


def _verify(models_root: Path, pointer: dict) -> bool:
    """Check a pointer against the artifact manifest and the model bytes."""
    version = pointer.get("version")
    cheat = pointer.get("cheat")
    model = pointer.get("model") or {}
    if not version or not cheat:
        return False
    version_dir = models_root / MODELS_PREFIX / cheat / version
    model_path = models_root / model.get("path", "")
    try:
        manifest = json.loads((version_dir / MANIFEST_NAME).read_text())
        if not model_path.is_file() or model_path.stat().st_size != model.get("bytes"):
            return False
        digest = sha256_of(model_path)
        if digest != model.get("sha256"):
            return False
        expected = manifest.get("model_sha256")
        return expected is None or digest == expected
    except (OSError, json.JSONDecodeError):
        return False


def write_pointer(path: Path, payload: dict) -> None:
    """Write the pointer in one call.

    R2 publishes an object atomically when the write completes, so a reader
    sees either the previous pointer or the new one, never a partial body.
    Rename is unavailable on bucket mounts, which is why this does not use a
    temp file.
    """
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


def _pointer_path(models_root: Path, cheat: str) -> Path:
    return models_root / MODELS_PREFIX / cheat / POINTER_NAME


def _pointer_version(path: Path) -> str:
    try:
        return json.loads(path.read_text()).get("version", "")
    except (OSError, json.JSONDecodeError):
        return ""


def _key(models_root: Path, path: Path) -> str:
    """Object key, stable regardless of where the bucket is mounted."""
    return path.relative_to(models_root).as_posix()


def _now() -> str:
    import datetime as dt

    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds")


def main(argv: list[str] | None = None) -> None:
    import argparse

    parser = argparse.ArgumentParser(
        description="Show or repair the latest model pointer."
    )
    parser.add_argument("--models-dir", type=Path, default=Path("target"))
    parser.add_argument("--cheat", default="speed")
    parser.add_argument("--no-repair", action="store_true")
    args = parser.parse_args(argv)

    pointer = resolve_latest(args.models_dir, args.cheat, repair=not args.no_repair)
    if pointer is None:
        print(f"no committed versions for {args.cheat}", file=sys.stderr)
        raise SystemExit(1)
    print(json.dumps(pointer, indent=2, sort_keys=True))
