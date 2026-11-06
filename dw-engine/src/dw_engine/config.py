"""Pipeline configuration: R2 layout, window sizes, group keys.

All settings can be overridden with environment variables so the same
code runs locally and inside Modal.
"""

import os
from pathlib import Path

from dotenv import load_dotenv

# Load dw-engine/.env when present, so local runs need no shell exports.
# The file is absent inside Modal containers, which receive the same
# variables from the r2-credentials secret.
load_dotenv(Path(__file__).resolve().parents[2] / ".env", encoding="utf-8-sig")

# --- R2 (S3-compatible) -----------------------------------------------------

# Modal secret with the credentials: AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
# and R2_BUCKET_URL
R2_SECRET_NAME = "r2-credentials"

DATASET_BUCKET = os.environ.get("R2_DATASET_BUCKET", "dillway")
MODELS_BUCKET = os.environ.get("R2_MODELS_BUCKET", "dillway")
BUCKET_URL = os.environ.get("R2_BUCKET_URL")

# Mount points inside Modal containers
DATASET_MOUNT = "/data"
MODELS_MOUNT = "/models"

# Dataset records: one parquet file per recording, object keys like
# <prefix>/<type>/<started>.parquet, e.g. recordings/legit/1674591086943.parquet
DATASET_PREFIX = os.environ.get("DW_DATASET_PREFIX", "recordings")

# Artifacts land at models/<cheat>/<version>/{model.onnx,manifest.json};
# models/<cheat>/latest.json points at the newest committed version.
MODELS_PREFIX = "models"

# --- Windowing (TRAINING.md) -------------------------------------------------
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
