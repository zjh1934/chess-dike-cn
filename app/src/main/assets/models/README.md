# ONNX 识别模型（本分支由 CI 注入，不进 git）

本目录存放 ONNX 两步式棋盘识别引擎的两个模型文件（共约 21.5MB）。**模型文件不进
git 仓库**（见仓库根目录 `.gitignore`），与上游 v3.4 完全一致。

上游发版时用 `gh release download assets` 从本仓 `assets` 预发布页把这两个文件
注入 APK；本分支 `Build-CN` 流水线改为直接从**上游公开 assets Release**
（`Silva6c/chess-dike` @ tag `assets`）下载同名文件注入，并做 sha256 逐字节校验、
出包后逐个 APK 校验「里面真有这两个模型」。所以：
- 直接 `git clone` 源码本地编译：目录为空 → 自动回退 YOLO（上游自带逻辑）
- 走本仓库 CI 出的包：**ONNX 主力 + YOLO 回退，两套识别引擎齐备**

| 文件名 | 大小 | 用途 |
|---|---|---|
| `4_v6-0301.onnx` | 10,704,184 B | RTMPose 棋盘四角关键点检测 |
| `nano_v3-0319.onnx` | 10,853,848 B | Swin 交叉点分类（16 类） |

## 来源

模型与算法来源：[yolo12138/Chinese_Chess_Recognition](https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition)（Hugging Face Space）。
工程实现参考：[Shirakawa-Kotone/chinese-chess-helper](https://github.com/Shirakawa-Kotone/chinese-chess-helper)
（OnnxRecognizer 原实现），本工程的移植实现位于 `OnnxBoardRecognizer.kt` / `OnnxGeom.kt`。

模型文件本身由上游作者公开发布在其仓库 `assets` 预发布页；本分支只是按上游同一
口径在构建时取用，**不修改模型、不重新分发源码副本、不写入 git 历史**。

## 模型校验

上游 `android.yml` 记录的 sha256（本分支 CI 自检沿用）：

```
fbd895d40ab81b41b8188f881866485678b7871775821aa50cfe74004a1dc796  4_v6-0301.onnx
cb1c1eb93370cb9c6bd65bc8a8b0f5ddc8dbc34cfc83ca902c447960b6ef7e3c  nano_v3-0319.onnx
```

服务启动时若加载失败，状态栏会提示「ONNX 引擎加载失败，已回退 YOLO 识别」。
