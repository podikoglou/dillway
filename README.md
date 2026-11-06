# dillway
An AI-powered Minecraft anti-cheat system.

## Architecture

```
+------+  uploads to  +------------+ 
|  S3  | <---------   |  dw-agent  | 
+------+              +------------+ 
   ||
   \/ gets data from           
+-------------+ 
|  dw-engine  |                     
+-------------+ 

```

- `dw-engine` trains the anti-cheat models: it pulls labeled packet
  sessions from R2, runs training on
  [Modal](https://modal.com), and uploads the compiled ONNX model
  back to R2. See `dw-engine/README.md`.
- `dillway-agent` is the [Paper](https://papermc.io) plugin that that takes
  care of recording player activity for both training and making predictions.

- `preprocess` is a program that preprocesses the training data 
