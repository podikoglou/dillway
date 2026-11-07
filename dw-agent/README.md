# dw-agent

[Paper](https://papermc.io) plugin that records player activity and runs
the trained anti-cheat specialists locally on the server.

To turn recordings into a model, see [../TRAINING.md](../TRAINING.md).

The plugin was previously named Dillway. On the first start after the
rename it moves `plugins/Dillway` to `plugins/dw-agent`, so recordings,
models and config carry over.

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
6. `/dill compact` merges the uploaded recordings of one label directory into
   shards, one label at a time, in the background. It lists every recording
   before it starts and shows progress above the hotbar, next to any live
   watch. Only one run exists at a time, and it never starts on its own. A
   source object is deleted only after its shard is uploaded, so an
   interrupted run leaves usable data behind. Compact only labels that are
   stable: a later relabel cannot reach a recording that already sits inside
   a shard.

All admin commands require `dillway.admin` (default `op`). The node keeps
its old name so existing permission setups work unchanged. Run
`/dill models reload` after training new specialists. Config lives in
`src/main/resources/config.yml`.

## Self-recording kit

`/dill kit` gives a player two items: a recorder and a class selector. A
right click on the recorder starts a session; the next right click stops it
and saves it straight under the type shown by the selector, with no menu.
The selector cycles through `legit` and every known cheat, and keeps its
choice inside the item, so it survives moving or dropping. The items never
move: the recorder changes its look in place, so the same slot is also the
stop button. Recording and watching are independent: a watched player can
still start a recording from the kit.

`/dill rec stop` still opens the interactive save menu. `/dill rec save
<id> <label>` saves an existing recording under one type from chat or the
console; `confirm` keeps its old meaning after the label.

## Live watch

`/dill watch <player>` predicts on a player while they play, instead of on
a recording that has already stopped. The buffer holds only the last window
of play, so the numbers track the current behaviour.

Watching is many-to-many: several admins can watch the same player, and one
admin can watch several players. A watched player has one buffer and one
prediction, shared by everyone watching them. `/dill watch stop [player]`
drops one player or every player the sender watches; the watch itself ends
only when its last viewer leaves.

The live state is written above the hotbar, where it overwrites itself, so a
watch stays silent in chat. The full report is available on demand and is
printed when the watch stops. Consoles have no action bar, so they get the
full report when a verdict changes; `watch.display` in the config selects
that chat-only mode for players too.

A watch writes nothing to disk and ends when the player leaves, when its
last viewer stops it, or at shutdown. Recording and watching do not affect
each other: a player can be recorded and watched at the same time.

## Predictions

- Models load from the local cache first, so the server predicts even
  when R2 is unreachable.
- Each specialist slices the recording by its own window size,
  extracts the same summary-stat features as training (parity-tested
  against `features.py`), and runs in-process on an async thread.
- Output per cheat: mean and max window probability, window count, and
  a verdict at the configured threshold.

## Metrics

`/dill metrics` prints in-memory counters: recording sessions and captured
packets, inference runs with average/peak/last duration, windows, flagged
verdicts and model failures, live watches, uploads, and model refreshes.
Nothing is persisted, so every counter resets with the server.

## Build

```sh
./gradlew shadowJar   # → build/libs/dw-agent.jar
./gradlew test        # includes a Python↔Kotlin feature-parity check
```

The jar bundles ONNX Runtime; only the Linux x64 natives are kept, since
that is what production servers run. Keep the runtime on its original
package (no shade relocation): its JNI entry points resolve by the
`ai.onnxruntime` names.
