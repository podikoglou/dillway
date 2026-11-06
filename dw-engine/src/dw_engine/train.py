"""Training orchestrator for one cheat specialist.

Pipeline: load recordings -> slice windows -> extract features ->
grouped CV (honest numbers) -> fit on all windows -> export
parity-checked ONNX usable locally:

    uv run python -m dw_engine.train --data-dir data --cheat speed

and from Modal (see app.py), where --data-dir is the R2 dataset mount
and --out is the R2 model bucket mount.
"""

import json
import sys
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

import numpy as np

from . import VERSION, config, registry
from .dataset import build_windows, design_matrix
from .export import export_artifacts, sanitize_cheat, version_slug
from .model import fit_model, grouped_score, print_metrics


def run_training(data_dir: Path, cheat: str, out_dir: Path) -> dict:
    """Train one cheat specialist and write ONNX artifacts under out_dir."""
    window_s = config.WINDOW_SECONDS[cheat]

    print(f"Loading recordings from {data_dir} (window={window_s}s) ...")
    windows = build_windows(data_dir, cheat)
    if not windows:
        raise RuntimeError(f"No usable windows found under {data_dir}.")
    print(f"  {len(windows)} windows from {len(set(w.player for w in windows))} players")

    X_list, y_list, groups = design_matrix(windows)
    X = np.asarray(X_list, dtype=np.float64)
    y = np.asarray(y_list)
    if len(X) == 0:
        raise RuntimeError("Feature extraction rejected every window.")
    counts = Counter(y_list)
    print(f"  X: {X.shape}, labels: LEGIT={counts[0]} CHEAT={counts[1]}")

    print("Evaluating (player-grouped CV) ...")
    metrics = grouped_score(X, y, groups, window_s)
    print_metrics(metrics)

    print("Fitting final model on all windows ...")
    fitted = fit_model(X, y)

    version = version_slug()
    artifact_dir = Path(out_dir) / config.MODELS_PREFIX / sanitize_cheat(cheat) / version
    print(f"Exporting ONNX artifacts to {artifact_dir} ...")
    export_artifacts(
        fitted,
        X,
        artifact_dir,
        manifest_extra={
            "cheat": cheat,
            "version": version,
            "window_seconds": window_s,
            "features": _feature_names(),
            "labels": ["LEGIT", cheat.upper()],
            "dataset": {
                "players": len(set(groups)),
                "recordings": len(set(w.recording_id for w in windows)),
                "windows": len(X),
                "windows_by_label": {"LEGIT": counts[0], cheat.upper(): counts[1]},
            },
            "train": {
                "validation": metrics,
                "training_utc": datetime.now(UTC).isoformat(timespec="seconds"),
                "notes": "accuracy from player-grouped CV; accuracy does not gate release — false_positives_per_legit_hour does",
            },
        },
    )

    pointer = registry.publish(Path(out_dir), sanitize_cheat(cheat))
    print(f"Latest pointer: models/{sanitize_cheat(cheat)}/{registry.POINTER_NAME} -> {pointer.get('version')}")

    summary = {
        "cheat": cheat,
        "version": version,
        "artifacts_dir": str(artifact_dir),
        "model_path": str(artifact_dir / "model.onnx"),
        "manifest_path": str(artifact_dir / "manifest.json"),
        "latest_path": str(Path(out_dir) / config.MODELS_PREFIX / sanitize_cheat(cheat) / registry.POINTER_NAME),
        "latest_version": pointer.get("version"),
        "feature_count": X.shape[1],
        "windows": len(X),
        "players": len(set(groups)),
        **metrics,
    }
    print(json.dumps(summary, indent=2))
    return summary


def _feature_names() -> list[str]:
    from .features import FEATURE_NAMES

    return FEATURE_NAMES


def main(argv: list[str] | None = None) -> None:
    import argparse

    parser = argparse.ArgumentParser(description="Train one cheat specialist locally.")
    parser.add_argument("--cheat", default="speed", help="cheat type (must have a WINDOW_SECONDS entry)")
    parser.add_argument("--data-dir", type=Path, default=Path(config.DATASET_LOCAL_DIR))
    parser.add_argument("--out", type=Path, default=Path(config.MODELS_DIR_LOCAL))
    args = parser.parse_args(argv)

    try:
        run_training(args.data_dir, args.cheat, args.out)
    except Exception as exc:  # surface a readable failure
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
