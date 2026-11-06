# dillway

An AI-powered Minecraft anti-cheat system.

Two components share one Cloudflare R2 bucket. [`plugin`](plugin/README.md)
is a [Paper](https://papermc.io) plugin that records player activity, gets
the recordings labeled as training data, and runs the trained ONNX
specialists locally for predictions. [`dw-engine`](dw-engine/README.md) is
the training pipeline — it pulls the labeled recordings from R2, trains on
[Modal](https://modal.com), and publishes the specialists back.

```
                 recordings                  recordings
+-----------+ ---------------> +---------------+ ---------------> +-----------+
|  plugin   |                  |      r2       |                   | dw-engine |
|           | <--------------- |               | <---------------  |  (Modal)  |
+-----------+    models down   +---------------+     artifacts     +-----------+
```
