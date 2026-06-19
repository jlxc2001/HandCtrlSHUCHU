# WiFiTouchSender HandGesture v5.6 Performance + Skeleton

这是 WiFiTouchSender 发射端的手势识别性能优化版。

## v5.6 改动

- 摄像头预览窗口加入 21 点手部骨骼叠加，方便观察模型是否实时跟手。
- 调试面板新增三行：
  - 手势：当前识别到的姿态/动作。
  - 性能：识别 FPS、单帧推理耗时、分析分辨率、丢帧数、图像输入路径。
  - 网络：HTTP 坐标发送耗时、发送次数、待合并请求、错误数。
- 高频光标坐标发送改为“最新值覆盖/合并发送”，避免网络慢时 HTTP 请求排队造成拖尾。
- 光标平滑系数从 0.34 提升到 0.62，降低本地平滑带来的延迟。
- 摄像头分析尺寸优先选择接近 320×240 的实际支持尺寸，降低 MediaPipe 输入成本。
- 识别帧率限制约 30FPS，并主动丢弃积压帧，优先保证低延迟。
- 尝试使用 MediaPipe `MediaImageBuilder` 直接读取 `ImageReader` 的 YUV 帧；如果当前 MediaPipe 版本不支持，会自动回退到旧的 Bitmap/JPEG 路径。

## 判断延迟来源

- 如果骨骼跟手很顺，但接收端光标慢：多半是网络/接收端 HTTP 链路慢。
- 如果骨骼本身一卡一卡：多半是本机识别链路慢。
- 如果“网络 HTTP坐标”耗时长期高于 50ms：建议接收端下一步增加 UDP 坐标接口。

## 模型文件

GitHub Actions 会自动下载模型到：

```text
app/src/main/assets/models/hand_landmarker.task
```

并在打包后检查 APK 内是否包含：

```text
assets/models/hand_landmarker.task
```

本地打包请先运行：

```bat
download_model.bat
```
