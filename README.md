# dillway

An AI-powered Minecraft anti-cheat system.

| component | role |
|---|---|
| `plugin` | [Paper](https://papermc.io) plugin. Records player activity, labels stopped recordings as training data, and runs the downloaded ONNX specialists locally for predictions. See `plugin/README.md` |
| `dw-engine` | Training pipeline. Pulls labeled recordings from R2, trains on [Modal](https://modal.com), and publishes the compiled ONNX specialists back to R2. See `dw-engine/README.md` |
| Cloudflare R2 | Object store shared by both: labeled recordings go in, model artifacts come out |

```
               recordings                 artifacts
+-----------+ ------------->  +---------------+  <------------- +-----------+
|  plugin   |                  |      r2       |                 | dw-engine |
|           | <-------------   |  recordings/  |                 |  (Modal)  |
+-----------+    models down   |     models/   |                 +-----------+
```
