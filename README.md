# WiFiTouchSender Hand Gesture v5 clean

这版从原始可用的 WiFiTouchSender 重新接入手势识别，而不是继续在 v2/v3/v4 上打补丁。

核心变化：

1. MainActivity 保持原始触控板发射端逻辑，只新增“进入摄像头手势模式”按钮。
2. 摄像头、MediaPipe、手势识别全部放到独立 HandGestureActivity。
3. 打开主界面不会初始化摄像头，也不会创建 HandGestureCameraController。
4. 修复 v4 里“连接测试”按钮没有真正调用 checkStatus() 的问题。
5. 只面向 arm64-v8a 高性能手机。

测试顺序：

1. 先打开 App 主界面。
2. 测试触控板和连接测试。
3. 点击“进入摄像头手势模式”。
4. 在手势页点“连接测试”。
5. 再点“开始手势”。

如果主界面能打开，但手势页或开始手势闪退，问题就集中在 MediaPipe / 模型 / 摄像头链路。

本地编译需要把 MediaPipe 模型放到：

```text
app/src/main/assets/hand_landmarker.task
```

GitHub Actions 会自动下载模型。
