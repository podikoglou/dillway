# dw-engine

dillway anti-cheat training pipeline. Trains one binary specialist per
cheat type (legit vs cheat-X) on packet recordings, exports the model
to ONNX, and stores it on Cloudflare R2. Training runs on
[Modal](https://modal.com); data and artifacts both go through R2 —
nothing is committed to git.

Modeling decisions and evaluation protocol live in `data-main/docs/`
(`ARCHITECTURE.md`, `ANALYSIS.md`, `DATASET.md`).

## Setup

```sh
uv sync
uv run modal secret create r2-credentials \
    AWS_ACCESS_KEY_ID=<key-id> \
    AWS_SECRET_ACCESS_KEY=<secret> \
    R2_BUCKET_URL=https://<account-id>.r2.cloudflarestorage.com
```

Put the same endpoint in `dw-engine/.env` (loaded automatically for the
local mount build) and upload recordings to the `dillway` bucket, one
directory per label. Overrides and other environment variables are
documented in `src/dw_engine/config.py`.

## Run

```sh
uv run modal run src/dw_engine/app.py --cheat speed   # on Modal, R2 in and out
uv run python -m dw_engine.train --cheat speed        # local, for feature iteration
```

## Artifacts

Each run publishes `models/<cheat>/<version>/model.onnx` +
`manifest.json`, plus `models/<cheat>/latest.json` naming the current
version. The ONNX model is self-describing: it carries `dw.*` metadata
(feature order, label order, window seconds, cheat type, version), so
any runtime can feed it without out-of-band config. The pointer is a
cache in front of the version listing; resolution verifies and repairs
it, so concurrent training runs are safe without locks. That logic
lives in `registry.py`. The pre-parquet JSON dataset is migrated by
`scripts/convert_legacy_json.py`.

## Inference in the plugin

The Paper plugin consumes these artifacts without any config exchange,
held together by two invariants. Both halves must move together:

- **Feature parity.** The plugin re-implements `features.py` in Kotlin,
  locked by a parity test on a mixed packet stream. Any feature change
  must port there and refresh the fixture; models exported before such
  a change must not be trusted.
- **Metadata contract.** The plugin rejects a model whose embedded
  `dw.*` metadata it cannot reconcile (unknown features, missing
  window seconds, cheat mismatch).

## Adding a cheat type

1. Add an entry to `WINDOW_SECONDS` in `config.py`.
2. Upload recordings as `recordings/<cheat>/*.parquet` next to
   `recordings/legit/`.
3. Run with `--cheat <name>`. Legit recordings are the negative class;
   recordings for other cheats are excluded, since a specialist's
   negatives must be clean.
