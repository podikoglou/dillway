# Training a specialist

The engine trains one binary specialist per cheat: `legit` against one cheat.
Specialists are independent.

## 1. Record

Use the plugin (`dw-agent/README.md`). Keep whole sessions:

```
<dataset-root>/<label>/<started>.parquet              source recording
<dataset-root>/<label>/compacted-<first>-<last>.parquet   merged shard
```

`/dill compact` merges many source recordings into shards to cut the number of
objects training has to fetch. A shard holds several whole sessions; the
trainer splits them apart again, and a recording that a leftover source and a
shard both hold counts once. Both layouts are equivalent for training.

`legit` is the shared negative class; each cheat has its own directory. A file
gets one label, so split a session that mixes legit and cheat periods. The
parquet layout is in `dw-engine/src/dw_engine/parquet.py`.

Record the same players both legit and cheating. Include hard legit cases
(lag, teleports, knockback, elytra, high CPS) and a spread of cheat settings.
Hold out whole players and recording days for a final test.

## 2. Set the window

Add one window entry per cheat in `dw-engine/src/dw_engine/config.py`. Dense
signals need short windows; sparse events need longer ones. The trainer drops
windows that are too short or have too few movement packets.

## 3. Configure

See Setup in `dw-engine/README.md` and the config notes in `dw-agent/README.md`.

The trainer needs read access to the dataset and write access to the model
prefix. The plugin needs read access to the model prefix.

## 4. Train

See Run in `dw-engine/README.md`.

## 5. Deploy

Publish the artifacts described in `dw-engine/README.md`, then reload the models
as described in `dw-agent/README.md`.

## 6. Add a cheat

See "Adding a cheat type" in `dw-engine/README.md`.

## Legacy JSON

```sh
cd dw-engine
uv run python scripts/convert_legacy_json.py \
    --src <json-root> \
    --out <parquet-root>
```
