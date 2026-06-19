# WiFiTouchSender HandGesture v5.3

本版修正方向：两阶段启动。

1. 点击「启动摄像头」只打开 Camera2 预览，不自动加载 MediaPipe。
2. 确认预览有画面后，再点击「加载模型」。
3. 如果加载模型时闪退，说明问题集中在 MediaPipe native `HandLandmarker.createFromOptions()`，不是摄像头权限或 Camera2 预览问题。

模型路径：`app/src/main/assets/models/hand_landmarker.task`。GitHub Actions 会自动下载。
# WiFiTouchSender HandGesture v5.2

修复内容：

- 修复进入“摄像头手势模式”立即闪退的问题。
- 原因是 `TextureView.setBackgroundColor(Color.BLACK)` 会触发 `UnsupportedOperationException: TextureView doesn't support displaying a background drawable`。
- 已移除对 TextureView 的背景设置，进入手势页面不应再因此崩溃。

测试顺序：

1. 卸载旧版：`adb uninstall com.jlxc.wifitouchsender`
2. 安装本版 APK。
3. 打开主界面。
4. 点击“进入摄像头手势模式”。
5. 若页面正常打开，再点击“开始手势”。

如果点“开始手势”后再闪退，再抓新的 log；那时问题才进入 MediaPipe / 摄像头链路。


## v5.2 修复

- MediaPipe 模型移动到 `app/src/main/assets/models/hand_landmarker.task`。
- Java 代码使用 `setModelAssetPath("models/hand_landmarker.task")`，避免 `hand_landmarker.task doesn't have a slash in it`。
- 即使模型初始化失败，也会先尝试打开摄像头预览，方便区分摄像头问题和模型问题。
