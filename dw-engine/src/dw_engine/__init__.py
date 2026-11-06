"""dw-engine: dillway anti-cheat training pipeline.

Reads labeled packet sessions from R2, trains one binary specialist per
cheat type (see TRAINING.md), validates it with player-grouped CV,
exports the model to ONNX, and uploads the artifacts back to R2.
Training runs on Modal.
"""

VERSION = "0.1.0"
