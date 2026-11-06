# dillway
An AI-powered Minecraft anti-cheat system.

## Architecture

| component | role |
|---|---|
| `plugin` | [Paper](https://papermc.io) plugin. Records player activity, labels stopped recordings as training data, and runs the downloaded ONNX specialists locally for predictions. See `plugin/README.md` |
| `dw-engine` | Training pipeline. Pulls labeled recordings from R2, trains on [Modal](https://modal.com), and publishes the compiled ONNX specialists back to R2. See `dw-engine/README.md` |
| Cloudflare R2 | Object store shared by both: `recordings/<label>/*.parquet` goes in, `models/<cheat>/latest.json` + `models/<cheat>/<version>/model.onnx` come out |

```
               recordings                 artifacts
+-----------+ ------------->  +---------------+  <------------- +-----------+
|  plugin   |                  |      r2       |                 | dw-engine |
|           | <-------------   |  recordings/  |                 |  (Modal)  |
+-----------+    models down   |     models/   |                 +-----------+
```

- `preprocess` is a program that preprocesses the training data

## How a prediction works

1. `dw-engine` trains one binary specialist per cheat and publishes
   `models/<cheat>/latest.json` plus `models/<cheat>/<version>/model.onnx`
   in the bucket. The export embeds `dw.*` metadata (feature order,
   window seconds, cheat name) so the plugin needs no out-of-band config.
2. The plugin downloads that artifact at startup (and on
   `/dill models reload`), verifies its SHA-256, and caches it under
   `plugins/Dillway/models/`.
3. An admin records a player with `/dill rec <name>`, stops with
   `/dill stop <name>`, and picks `Run prediction`. The plugin slices
   the recording with the model's own window seconds, rebuilds the
   summary-stat features in Kotlin (parity-tested against `features.py`),
   and runs every specialist in-process on an async thread.
4. The admin sees one line per cheat model: mean and max window
   probability, window count, and a flag verdict at the configured
   `prediction.threshold`.
