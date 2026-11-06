"""Pipeline configuration: R2 layout, window sizes, group keys.

All settings can be overridden with environment variables so the same
code runs locally and inside Modal.
"""

import os
from pathlib import Path

# --- R2 (S3-compatible) -----------------------------------------------------

# Modal secret with the credentials: AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
R2_SECRET_NAME = "r2-credentials"


def r2_endpoint_url() -> str:
    """Endpoint for the R2 S3 API, resolved from the environment."""
    explicit = os.environ.get("R2_ENDPOINT_URL")
    if explicit:
        return explicit.rstrip("/")
    account = os.environ.get("R2_ACCOUNT_ID")
    if not account:
        raise RuntimeError(
            "R2 endpoint not configured. Load R2_ACCOUNT_ID "
            "(or R2_ENDPOINT_URL) from the environment before running."
        )
    return f"https://{account}.r2.cloudflarestorage.com"


DATASET_BUCKET = os.environ.get("R2_DATASET_BUCKET", "dillway")
MODELS_BUCKET = os.environ.get("R2_MODELS_BUCKET", "dillway")

# Mount points inside Modal containers
DATASET_MOUNT = "/data"
MODELS_MOUNT = "/models"

# Dataset records: one parquet file per recording, object keys like
# <prefix>/<type>/<started>.parquet, e.g. recordings/legit/1674591086943.parquet
DATASET_PREFIX = os.environ.get("DW_DATASET_PREFIX", "recordings")

# Artifacts land at models/<cheat>/<version>/{model.onnx,manifest.json}
MODELS_PREFIX = "models"

# --- Windowing (data-main/docs/DATASET.md) ----------------------------------
# Seconds of packets per training/inference window, per cheat. Reach uses
# longer windows because attacks are sparse; add entries as cheats land.
WINDOW_SECONDS = {
    "speed": 3.5,
}

# A window needs at least this many movement packets to be usable
MIN_MOVEMENT_PACKETS = 20

# --- Local development -------------------------------------------------------
# Paths used only by `python -m dw_engine.train` (no Modal, no R2).
# The dataset dir is gitignored; the models dir holds local artifact output.
DATASET_LOCAL_DIR = os.environ.get("DW_DATASET_DIR", "data")
MODELS_DIR_LOCAL = os.environ.get("DW_MODELS_DIR", "target")
