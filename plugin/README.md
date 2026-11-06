# dillway plugin

[Paper](https://papermc.io) plugin that records player activity and runs
the trained anti-cheat specialists locally on the server.

## Admin flow

1. Record a player with `/dill rec`, stop with `/dill stop`. Chat
   buttons then offer: run prediction right away, open the label menu
   (one toggle per cheat plus `legit`), or discard.
2. Saving writes one parquet per selected label. Uploads happen when
   remote storage is enabled, and recordings stay on disk otherwise.
3. A waiting recording lives in memory until resolved. Consoles get
   command names instead of clickable buttons; everything works by
   typing the commands.

All admin commands require `dillway.admin` (default `op`). Run
`/dill models reload` after training new specialists. Config lives in
`src/main/resources/config.yml`.

## Predictions

- Models load from the local cache first, so the server predicts even
  when R2 is unreachable.
- Each specialist slices the recording by its own window size,
  extracts the same summary-stat features as training (parity-tested
  against `features.py`), and runs in-process on an async thread.
- Output per cheat: mean and max window probability, window count, and
  a verdict at the configured threshold.

## Build

```sh
./gradlew shadowJar   # → build/libs/dillway.jar
./gradlew test        # includes a Python↔Kotlin feature-parity check
```

The jar bundles ONNX Runtime; only the Linux x64 natives are kept, since
that is what production servers run. Keep the runtime on its original
package (no shade relocation): its JNI entry points resolve by the
`ai.onnxruntime` names.
