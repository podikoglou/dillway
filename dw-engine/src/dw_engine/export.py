"""ONNX export for sklearn specialists.

The RandomForest is compiled with skl2onnx. Options:
  - zipmap disabled → outputs are plain 'label' (int64) and
    'probabilities' (Nx2 float32), friendly to any downstream runtime.
  - metadata embedded in the model (under the 'dw.' namespace) so the
    artifact is self-describing: feature order, label order, window
    size, and where it came from.

Every export is parity-checked against onnxruntime before it is allowed
to leave the machine.
"""

import json
import re
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sklearn
import skl2onnx
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType

from . import VERSION

TOLERANCE = 1e-5


def model_as_bytes(fitted_model, n_features: int) -> bytes:
    """Compile the fitted sklearn model to ONNX bytes (no zipmap)."""
    initial_types = [("features", FloatTensorType([None, n_features]))]
    options = {id(fitted_model): {"zipmap": False}}
    onnx_model = to_onnx(fitted_model, initial_types=initial_types, options=options)
    return onnx_model.SerializeToString()


def attach_metadata(onnx_bytes: bytes, meta: dict) -> bytes:
    """Embed key/value metadata into the ONNX model proto."""
    import onnx

    onnx_model = onnx.load_model_from_string(onnx_bytes)
    for key, value in meta.items():
        entry = onnx_model.metadata_props.add()
        entry.key = f"dw.{key}"
        entry.value = value
    return onnx_model.SerializeToString()


def export_artifacts(
    fitted_model,
    X: np.ndarray,
    out_dir: Path,
    manifest_extra: dict,
) -> Path:
    """Compile, parity-check, and write model.onnx + manifest.json.

    Parity check: onnxruntime must reproduce sklearn's probabilities on
    X to within TOLERANCE, otherwise the export is refused.

    Returns the directory the artifacts were written to.
    """
    onnx_bytes = model_as_bytes(fitted_model, X.shape[1])

    session = ort.InferenceSession(onnx_bytes, providers=["CPUExecutionProvider"])
    outputs = session.run(None, {"features": X.astype(np.float32)})
    proba_onnx = np.asarray(outputs[1])
    proba_sk = fitted_model.predict_proba(X)
    max_diff = float(np.max(np.abs(proba_onnx - proba_sk.astype(np.float32))))
    if max_diff > TOLERANCE:
        raise RuntimeError(
            f"ONNX parity check failed: max |dp| = {max_diff:.2e} > {TOLERANCE:.0e}"
        )

    manifest = {
        "dw_engine_version": VERSION,
        "framework": f"scikit-learn {sklearn.__version__}",
        "converter": f"skl2onnx {skl2onnx.__version__}",
        "onnx_max_abs_p_diff": max_diff,
        **manifest_extra,
    }
    onnx_bytes = attach_metadata(
        onnx_bytes,
        {
            "engine_version": manifest["dw_engine_version"],
            "features": json.dumps(manifest_extra["features"]),
            "labels": json.dumps(manifest_extra["labels"]),
            "window_seconds": str(manifest_extra["window_seconds"]),
            "cheat": manifest_extra["cheat"],
            "version": manifest_extra["version"],
        },
    )

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "model.onnx").write_bytes(onnx_bytes)
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )
    return out_dir


def version_slug() -> str:
    """UTC timestamp used as the model artifact version."""
    import datetime as dt

    return dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%SZ")


def sanitize_cheat(cheat: str) -> str:
    return re.sub(r"[^a-z0-9_-]", "_", cheat.lower())
