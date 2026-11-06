# dillway

An AI-powered Minecraft anti-cheat system.

## Architecture
dillway consists of two components. For storage, it uses Cloudflare R2 (though
it can use any S3-compatible store).

- `dw-agent` is a Paper plugin that records player activity and can upload it
  as training data or make a prediction about whether the player is cheating or not.

- `dw-engine` is the training pipeline which runs on modal, it mounts the
  training data, trains the model, and uploads the model to R2.

`dw-agent` is what runs the model to make predictions, it's done off the main
thread for performance reasons. It downloads the model from R2 and can reload
it dynamically.

```
                 recordings                         recordings
+------------+ ---------------> +---------------+ --------------->  +-----------+
|  dw-agent  |                  |      r2       |                   | dw-engine |
|  (paper)   | <--------------- |               | <---------------  |  (modal)  |
+------------+    models down   +---------------+     artifacts     +-----------+
```
