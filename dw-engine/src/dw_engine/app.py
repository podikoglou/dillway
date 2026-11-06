"""Modal training job for dw-engine.

The Modal function mounts the R2 bucket twice:
  - read-only  at /data   -> <prefix>/<type>/*.parquet
  - read-write at /models -> models/<cheat>/<version>/...

Artifacts written to /models are uploaded to R2 by the bucket mount, so
`modal run src/dw_engine/app.py` is the complete remote pipeline.

Prerequisites (once):
    uv run modal secret create r2-credentials \\
        AWS_ACCESS_KEY_ID=<r2-access-key> \\
        AWS_SECRET_ACCESS_KEY=<r2-secret-key> \\
        R2_BUCKET_URL=https://<account-id>.r2.cloudflarestorage.com

Locally, R2_BUCKET_URL is read from dw-engine/.env (loaded
automatically). The container receives it from the secret.
"""

from pathlib import Path

import modal

from dw_engine import config, train

IMAGE = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_pip_install(
        "numpy==2.5.4",
        "scikit-learn==1.9.1",
        "skl2onnx==1.20.0",
        "onnx==1.23.2",
        "onnxruntime==1.31.1",
        "pyarrow==26.0.1",
        "python-dotenv==1.2.4",
    )
    .add_local_python_source("dw_engine")
)

app = modal.App("dw-engine", image=IMAGE)

r2_secret = modal.Secret.from_name(
    config.R2_SECRET_NAME,
    required_keys=["AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"],
)

if not config.BUCKET_URL:
    raise RuntimeError(
        "R2_BUCKET_URL is not set. Add it to dw-engine/.env "
        "and to the r2-credentials Modal secret."
    )

dataset_mount = modal.CloudBucketMount(
    bucket_name=config.DATASET_BUCKET,
    bucket_endpoint_url=config.BUCKET_URL,
    secret=r2_secret,
    read_only=True,
    force_path_style=True,
)
models_mount = modal.CloudBucketMount(
    bucket_name=config.MODELS_BUCKET,
    bucket_endpoint_url=config.BUCKET_URL,
    secret=r2_secret,
    read_only=False,
    force_path_style=True,
)


@app.function(
    image=IMAGE,
    secrets=[r2_secret],
    volumes={config.DATASET_MOUNT: dataset_mount, config.MODELS_MOUNT: models_mount},
    timeout=60 * 30,
)
def train_specialist(dataset_prefix: str = config.DATASET_PREFIX, cheat: str = "speed") -> dict:
    """Pull the dataset from R2, train one specialist, upload to R2."""
    return train.run_training(
        data_dir=Path(config.DATASET_MOUNT) / dataset_prefix,
        cheat=cheat,
        out_dir=Path(config.MODELS_MOUNT),
    )


@app.local_entrypoint()
def main(dataset_prefix: str = config.DATASET_PREFIX, cheat: str = "speed"):
    summary = train_specialist.remote(dataset_prefix=dataset_prefix, cheat=cheat)
    print("Run summary:")
    print(f"  cheat:      {summary['cheat']}")
    print(f"  version:    {summary['version']}")
    print(f"  latest:     {summary['latest_version']}")
    print(f"  accuracy:   {summary['accuracy']:.4f} +/- {summary['accuracy_std']:.4f}")
    print(f"  recall:     {summary['recall']:.4f}")
    print(f"  FP/legithr: {summary['false_positives_per_legit_hour']:.2f}")


if __name__ == "__main__":
    main()
