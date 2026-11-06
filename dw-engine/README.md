# dw-engine

dillway anti-cheat training pipeline. Trains one binary specialist per
cheat type (legit vs cheat-X) on packet recordings, exports the model
to ONNX, and stores it on Cloudflare R2. Training runs on
[Modal](https://modal.com); data comes from R2 and artifacts go back to
R2 — nothing is committed to git.

Design follows `data-main/docs/`:

- `ARCHITECTURE.md` — tree ensembles, one specialist per cheat,
  engineered features (baseline summary stats now; physics residuals
  planned as the next feature set for movement cheats)
- `ANALYSIS.md` — all reported scores use **GroupKFold by player**;
  the production gate is **false flags per legit hour**, not accuracy
- `DATASET.md` — training samples are windows (3.5 s for speed) sliced
  from sessions, not whole sessions; metadata (player UUID, session) is
  kept per window for grouped evaluation

## Dataset format

Recordings are Parquet files, uploaded by the recorder with one object
key per recording:

    <key-prefix>/<type>/<started>.parquet

e.g. `recordings/legit/1674591086943.parquet` and
`recordings/speed/1674945465362.parquet`. Each file is a flat table of
one row per packet: the recording header (`id`, `started`, `player`,
`recording_type`) repeated on every row, the packet row (`packet_type`,
`timestamp`, plus optional payload columns — `x/y/z/yaw/pitch/f/hasPos/
hasLook` for movement packets, `animation_timestamp` for arm animation,
`itemInHandIndex` for held-item changes), and the captured player
context columns (`on_ground`, `flying`, `sprinting`, `velocity_*`, ...)
which are always present.

The directory name selects the label: `legit` is the negative class,
any other directory must match the `--cheat` name being trained.

## Pipeline

```
R2 dataset bucket ──► Modal job ──► ONNX artifacts ──► R2 models bucket
 /data/recordings/     │  load →   models/speed/<ver>/model.onnx
   legit/*.parquet     │  window →                       manifest.json
   speed/*.parquet     │  features
                       │  GroupKFold CV (group = player UUID)
                       │  RandomForest fit
                       │  skl2onnx compile + parity check
                       └─ manifest + ONNX metadata
```

The ONNX model is self-describing: it carries `dw.*` metadata (feature
order, label order, window seconds, cheat type, version) so any
inference runtime can feed it correctly without out-of-band config.

## Layout

```
src/dw_engine/
  config.py     R2 layout, mounts, window sizes, env overrides
  features.py   baseline summary-stat feature set (+future residual set)
  parquet.py    recording reader (one row per packet -> nested packets)
  dataset.py    recording glob + window slicing
  model.py      RandomForest specialist + grouped CV + FP/hour gate
  export.py     skl2onnx compile, metadata, onnxruntime parity check
  train.py      orchestration; also usable locally without Modal
  app.py        Modal app: R2 bucket mounts + train_specialist
scripts/        converter for the older JSON recordings
data/           local dataset dir for local training runs (gitignored)
converted/      converter output, ready for upload (gitignored)
```

## Setup

```sh
uv sync
```

1. Create the Modal secret with your R2 token (R2 → API → Object
   Read & Write) once:

   ```sh
   uv run modal secret create r2-credentials \
       AWS_ACCESS_KEY_ID=<key-id> \
       AWS_SECRET_ACCESS_KEY=<secret>
   ```

2. Provide the account endpoint locally (needed to build the bucket
   mounts), e.g. in `dw-engine/.env`:

   ```sh
   R2_ACCOUNT_ID=<cloudflare_account_id>
   ```

3. Fill the dataset bucket (`dillway-recordings`) with recordings, one
   directory per label:

   ```
   recordings/legit/<started>.parquet
   recordings/speed/<started>.parquet
   ```

   (Buckets and prefix can be overridden with `R2_DATASET_BUCKET`,
   `R2_MODELS_BUCKET`, `DW_DATASET_PREFIX`.)

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `R2_ACCOUNT_ID` | — | Cloudflare account id, used to build the bucket endpoint URL (needed by `modal run`) |
| `R2_ENDPOINT_URL` | — | Explicit endpoint, takes precedence over `R2_ACCOUNT_ID` |
| `R2_DATASET_BUCKET` | `dillway-recordings` | Bucket the training data is read from |
| `R2_MODELS_BUCKET` | `dillway-models` | Bucket artifacts are written to |
| `DW_DATASET_PREFIX` | `recordings` | Root prefix for label dirs inside the dataset bucket |
| `DW_DATASET_DIR` | `data` | Local dataset dir for `python -m dw_engine.train` |
| `DW_MODELS_DIR` | `target` | Local artifact output dir |

## Run

Remote (Modal, data from R2, artifacts uploaded to R2):

```sh
R2_ACCOUNT_ID=... uv run modal run src/dw_engine/app.py --dataset-prefix recordings --cheat speed
```

Optional flags: `--dataset-prefix` and `--cheat` (see above for defaults).

Local (same training path, no Modal, uses `data/` and writes to
`target/`) — useful for fast iteration on feature code:

```sh
uv run python -m dw_engine.train --cheat speed
# same shape as the Modal run: --data-dir, --out, --cheat
```

## Artifacts

`models/<cheat>/<version>/{model.onnx,manifest.json}` on the models
bucket, where `<version>` is the UTC timestamp of the training run
(e.g. `20261106T002143Z`).

### model.onnx

| | |
|---|---|
| Input | `features`, float32 tensor `[N, n_features]` |
| Outputs | `label` (int64: 0=LEGIT, 1=CHEAT) and `probabilities` (float32 `[N, 2]`); the MapModel/zipmap is disabled so outputs are plain tensors |

Feature order is embedded in the model as `dw.*` metadata
(`dw.features`, `dw.labels`, `dw.window_seconds`, `dw.cheat`,
`dw.version`) so an inference runtime can feed the model correctly
without out-of-band config.

### manifest.json

Everything the model artifact should travel with: feature list and
count, label order, window size, dataset composition (players,
recordings, windows — overall and per label), the grouped-CV validation
metrics, framework/converter versions, and the onnxruntime-vs-sklearn
parity error at export time (typically ~1e-6).

## Converting the older JSON dataset

Recordings exported before the switch to parquet live as JSON files
(one per recording). `scripts/convert_legacy_json.py` converts them to
parquet and splits them into windows with the same `WINDOW_SECONDS`
windowing the trainer uses, one window per output file:

```sh
uv run python scripts/convert_legacy_json.py --src ../data-main --out converted/recordings
```

Legit recordings are cut to the largest configured window size, since
each specialist re-slices them to its own window at training time.
Converted files train bit-identically to slicing JSON at training time
(same window count, same features). Upload the `converted/recordings`
directory tree into the bucket and the object keys land at
`recordings/legit/...`, `recordings/speed/...`.

## Metrics: how to read the run output

Validation is **GroupKFold by player** (`ANALYSIS.md`): folds never
mix two windows from the same player, so scores reflect unseen-player
performance instead of memorizing who is who. Two numbers matter:

- `false_positives_per_legit_hour` — the production gate. Estimating:
  FP windows ÷ legit-window hours. This gates release, not accuracy.
- `accuracy` / `recall` — coarse signal only, reported per fold mean ±
  std. Current dataset (7 players, one cheat supplier) yields bad
  honest numbers — that is a data collection problem (see
  `ANALYSIS.md`), not a pipeline bug.

## Adding a cheat type

1. Add an entry to `WINDOW_SECONDS` in `config.py` (e.g. reach uses a
   12 s window per `DATASET.md`).
2. Upload recordings as `recordings/<cheat>/*.parquet` next to
   `recordings/legit/`.
3. Run with `--cheat <name>`. The specialist gets its own artifacts
   under `models/<cheat>/`; legit recordings are reused as its negative
   class. Recordings for other cheats are excluded, since a
   specialist's negatives must be clean for that check.
