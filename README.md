# WiFiTouchSender HandGesture v5.4

本版用于继续排查“摄像头能打开，但 MediaPipe HandLandmarker 模型初始化 native SIGSEGV”的问题。

关键改动：

1. 保留 v5.3 的两阶段流程：先启动摄像头，再加载模型。
2. MediaPipe 依赖回退到 `com.google.mediapipe:tasks-vision:0.10.15`。
3. 模型下载固定为 `float16/1/hand_landmarker.task`，不再使用 latest。
4. 模型路径仍为 `app/src/main/assets/models/hand_landmarker.task`。
5. Gradle 保留 `noCompress += ['task', 'tflite']`。
6. 加载模型时不再使用 `setModelAssetPath()`，改为 `openFd()` + `MappedByteBuffer` + `setModelAssetBuffer()`。
7. 显式使用 CPU delegate。

测试顺序：

1. 卸载旧版：`adb uninstall com.jlxc.wifitouchsender`
2. 安装 v5.4。
3. 进入摄像头手势模式。
4. 点“启动摄像头”，确认有预览画面。
5. 再点“加载模型”。

如果“加载模型”仍然 native 崩溃，说明问题已经不在模型路径、压缩方式、摄像头权限、Camera2 预览链路，而是 MediaPipe Android AAR 与当前 Android 16/OPPO 系统的兼容性。下一步应切换到纯 TFLite 两阶段手部模型或把 MediaPipe 放入独立进程作为临时隔离。


## v5.5 重要说明：模型必须进入 APK

源码包默认不内置 `hand_landmarker.task`。如果使用 GitHub Actions，workflow 会自动下载模型，并在打包后检查 APK 内是否存在：

```text
assets/models/hand_landmarker.task
```

如果你在本地 Android Studio/Gradle 打包，先运行根目录的：

```bat
download_model.bat
```

或者手动下载 MediaPipe Hand Landmarker 模型并放到：

```text
app/src/main/assets/models/hand_landmarker.task
```

v5.5 加入了 APK 内 assets 列表诊断；如果模型缺失，页面会显示 `assets/models` 里实际有哪些文件。
