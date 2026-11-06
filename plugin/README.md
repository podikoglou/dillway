# dillway plugin

[Paper](https://papermc.io) plugin for recording player activity and running
the trained anti-cheat specialists locally on the server.

## Commands

All require `dillway.admin` (default `op`).

| Command | What it does |
|---|---|
| `/dill rec <player>` | Start recording a player. No cheat type is picked here; the label is chosen when the recording stops |
| `/dill stop <player>` | Stop and show the chooser menu |
| `/dill training` | Open the label menu for your waiting recording |
| `/dill label <name>` | Toggle one label (`legit`, or a cheat). `legit` is exclusive of cheats |
| `/dill save` | Write one parquet per selected label and upload them |
| `/dill predict` | Run inference locally with the loaded models |
| `/dill discard` | Drop the recording |
| `/dill models` | Show the loaded models and their versions |
| `/dill models reload` | Download the newest models from R2 and swap them in memory |

## Workflow

1. `/dill rec <player>` — record whatever the player does, honestly or
   with a cheat client. The recording keeps no label while it runs.
   Chat shows a clickable **[Stop]** button (consoles get the command
   name instead).
2. `/dill stop <player>` — chat shows two clickable steps:
   - **[Run prediction]** runs inference straight away.
   - **[Use as training data]** opens a toggle menu one button per
       cheat plus `legit`; a recording with two cheats becomes a positive
       for both, `legit` is exclusive. **[Done]** writes one parquet per
       selected label under `plugins/Dillway/<label>/` and pushes each to
       `s3://<bucket>/<key-prefix>/<label>/<started>.parquet` once the
       upload succeeds (the local copy is then removed, kept on failure).
   - **[Discard]** drops it.
3. A waiting recording lives in memory until the admin resolves it, and is
   re-shown on every click. Consoles get command names instead of clickable
   buttons; everything works by typing the commands.

## Predictions

- Models load from the cache at `plugins/Dillway/models/` first, so the
  server predicts even when R2 is unreachable.
- A specialist slices the recording with the window seconds stored in the
  model (`dw.window_seconds`), extracts the same summary-stat features as
  training (`dw.features`, parity-tested against `dw-engine`), and runs the
  model in-process on an async thread.
- Output per cheat: mean and max window probability, the window count,
  and a verdict (`FLAGGED` when any window reaches
  `prediction.threshold`).
- To train a new specialist, run
  `uv run modal run src/dw_engine/app.py --cheat <name>` in `dw-engine/`,
  then `/dill models reload` on the server.

## Config

```yaml
cheats:
  - speed      # labels the admin can give; also the models to refresh

prediction:
  threshold: 0.5

models:
  refresh-on-startup: true
  prefix: "models"

s3:
  # Same credentials and bucket serve recordings and model downloads.
  enabled: false
  endpoint: "https://<account-id>.r2.cloudflarestorage.com"
  path-style: true
  bucket: "dillway"
  access-key-id: ""
  secret-access-key: ""
  key-prefix: "recordings"
```

With `s3.enabled: false` the plugin keeps recordings locally under
`plugins/Dillway/<label>/` and loads only cached models.

## Build

```sh
./gradlew shadowJar   # → build/libs/dillway.jar
./gradlew test        # includes a Python↔Kotlin feature-parity check
```

The jar bundles ONNX Runtime; only the Linux x64 natives are kept, since
that is what production servers run. Keep the runtime on its original
package (no shade relocation): its JNI entry points resolve by the
`ai.onnxruntime` names.
