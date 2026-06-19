# WiFiTouchSender Hand Gesture v4 checked

这是针对“启动就闪退/开启手势闪退”重新检查后的版本。

## 重点修复

1. MediaPipe 从 `0.10.26` 升级到 `0.10.26.1`，用于适配 Android 15+/新旗舰机可能遇到的 16KB page-size 原生库兼容问题。
2. 移除 Manifest 里的 `android:extractNativeLibs="true"`，Gradle `jniLibs.useLegacyPackaging=false`，不再强制旧式 native lib 解包。
3. 新增 `App.java`，CrashHandler 前移到 Application 阶段，能记录更早期的启动崩溃。
4. MainActivity 启动阶段不再自动请求 `/status`，避免网络异常和接收端未开时干扰启动排查。
5. MainActivity 不直接 import MediaPipe；只有点“开启摄像头手势控制”时才反射加载 `HandGestureCameraController`。

## 使用顺序

1. 安装后先只打开 APP。
2. 能打开后，输入接收端 IP，点“连接测试”。
3. 连接成功后，再点“开启摄像头手势控制”。

## GitHub Actions

workflow 会自动下载：

```text
app/src/main/assets/hand_landmarker.task
```

如果本地 Android Studio 编译，也要手动把该文件放到 assets 目录。

## 如果仍然闪退

启动闪退：

```bash
adb logcat -c
adb shell monkey -p com.jlxc.wifitouchsender 1
adb logcat -d -v time > wifitouchsender_startup_crash.txt
```

开启手势后闪退：

```bash
adb logcat -c
# 手动打开 APP，然后点“开启摄像头手势控制”，等它闪退
adb logcat -d -v time > wifitouchsender_gesture_crash.txt
```

检查手机是否 16KB page size：

```bash
adb shell getconf PAGE_SIZE
```

如果返回 `16384`，说明确实是 16KB page-size 新机环境。
