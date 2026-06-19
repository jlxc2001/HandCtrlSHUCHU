# WiFiTouchSender HandGesture v1.2 arm64-safe

这是 WiFi 局域网触控发射端的手势识别实验版。

## 这版重点

- 面向高性能 64 位 Android 手机，不再照顾 32 位/低性能旧设备。
- 只打包 `arm64-v8a`，减少 MediaPipe 原生库加载问题。
- MediaPipe 手势模块改为懒加载：APP 打开时不初始化 MediaPipe，只有打开“摄像头手势控制”时才加载。
- MainActivity 和手势规则层不再直接依赖 MediaPipe 类型，避免模型/原生库异常导致启动页直接闪退。
- Camera2 输出尺寸改为选择真实支持的 YUV 尺寸，不再手动 clamp 到摄像头可能不支持的尺寸。
- 增加上次闪退记录显示：如果 Java 层闪退，下次打开首页会显示 `上次闪退记录`。

## 手势映射

- 半捏/普通手势：光标跟随手掌位置移动
- 食指 + 大拇指捏合：点击
- 大拇指 + 食指张成 L：返回
- 五指全部张开并保持：HOME

## 接收端协议

沿用原接收端 HTTP 协议：

- `/api/set`：移动光标到绝对坐标
- `/api/tap`：点击
- `/api/back`：返回
- `/api/home`：HOME
- `/status`：获取接收端状态和屏幕尺寸

默认端口：`47220`

## 模型文件

GitHub Actions 会自动下载：

```text
app/src/main/assets/hand_landmarker.task
```

本地 Android Studio 编译时，需要手动放入同名文件。

## 如果仍然闪退

先重新打开 APP 看首页有没有“上次闪退记录”。如果没有，请用 ADB 导出日志：

```bash
adb logcat -c
adb shell monkey -p com.jlxc.wifitouchsender 1
adb logcat -d -v time > wifitouchsender_crash.txt
```

如果是点击“开启摄像头手势控制”后闪退，可以这样抓：

```bash
adb logcat -c
# 手动打开 APP，然后点击“开启摄像头手势控制”，等它闪退
adb logcat -d -v time > wifitouchsender_gesture_crash.txt
```


## v3 startup-safe 说明

这版用于解决“APP 一打开就闪退 / 还没开手势就闪退”的问题。

核心变化：

1. `MainActivity` 不再直接引用 `HandGestureCameraController`，也不再直接引用任何 MediaPipe 类型。
2. 手势模块通过反射懒加载，只有点“开启摄像头手势控制”时才加载 MediaPipe 和 Camera2 控制器。
3. 如果 MediaPipe 原生库、模型文件或摄像头初始化失败，主界面应尽量保持可用，并在“手势状态”里显示错误。
4. 仍然只面向 arm64 高性能手机，`abiFilters 'arm64-v8a'` 保留。

如果这版依旧是一打开就闪退，优先抓完整 logcat，因为那就基本不是普通 Java 异常，而可能是安装包、系统环境或极早期资源/Activity 加载问题。

抓启动闪退日志：

```bash
adb logcat -c
adb shell monkey -p com.jlxc.wifitouchsender 1
adb logcat -d -v time > wifitouchsender_startup_crash.txt
```

抓点击“开启摄像头手势控制”之后的闪退日志：

```bash
adb logcat -c
# 手动打开 APP，点击开启摄像头手势控制，等闪退
adb logcat -d -v time > wifitouchsender_gesture_crash.txt
```
