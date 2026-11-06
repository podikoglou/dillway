# dillway plugin

[Paper](https://papermc.io) plugin that records player activity and runs
the trained anti-cheat specialists locally on the server.

To turn recordings into a model, see [../TRAINING.md](../TRAINING.md).

## Admin flow

1. `/dill rec start` records a player; `/dill rec stop` writes the session
   and prints its recording ID. Every button from then on carries that ID,
   so old chat messages keep working and a prediction never uses up a
   recording.
2. The staging copy is the source for predictions and later label changes.
   Saving writes one labeled copy per selected label, and uploads happen
   when remote storage is enabled.
3. A save that would put the same recording ID under a label that already
   holds it, locally or in the bucket, asks for confirmation. Confirming
   replaces the old label set instead of adding a second copy.
4. Discard removes local copies only. Uploaded objects stay in the bucket.
5. Consoles get command names instead of clickable buttons; everything
   works by typing the commands.

All admin commands require `dillway.admin` (default `op`). Run
`/dill models reload` after training new specialists. Config lives in
`src/main/resources/config.yml`.

## Live watch

`/dill watch <player>` predicts on a player while they play, instead of on
a recording that has already stopped. The buffer holds only the last window
of play, so the numbers track the current behaviour.

The live state is written above the hotbar, where it overwrites itself, so a
steady verdict sends no chat messages. Chat carries one line only when the
set of flagged models changes; the `[Report]` button and `/dill watch stop`
print the full report. Consoles have no action bar, so they get the full
report on a change instead. `watch.display` in the config selects chat-only
mode.

A watch writes nothing to disk and ends when the player leaves, on
`/dill watch stop`, or at shutdown. A player cannot be recorded and watched
at the same time: stop the other mode first.

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
