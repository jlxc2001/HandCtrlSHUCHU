# WiFiTouchSender - 手势识别发射端

这是 WiFiTouchSender 的手势识别实验版。

## 新增功能

- 保留原来的触控板控制。
- 新增摄像头手势控制开关。
- 使用 MediaPipe Hand Landmarker 检测 21 个手部关键点。
- 通过规则判断手势并复用原有 HTTP 接口发送指令：
  - 手掌/半捏状态移动：发送 `/api/set`
  - 食指 + 大拇指捏合：发送 `/api/tap`
  - 大拇指 + 食指张开成 L：发送 `/api/back`
  - 五指全部张开：发送 `/api/home`

## 接收端协议不需要变

接收端继续实现这些接口即可：

- `GET /status`
- `POST /api/set`，参数：`x`、`y`
- `POST /api/tap`
- `POST /api/back`
- `POST /api/home`

## 模型文件

运行时需要：

```text
app/src/main/assets/hand_landmarker.task
```

GitHub Actions 已经加入自动下载模型的步骤；如果你用 Android Studio 本地编译，需要手动把官方 MediaPipe Hand Landmarker 模型放到上面这个路径。

## 测试步骤

1. 先启动接收端服务，并确认无障碍权限可用。
2. 在发射端输入接收端 IP 和端口。
3. 点“连接测试”，确认能拿到屏幕尺寸。
4. 开启“摄像头手势控制”。
5. 允许摄像头权限。
6. 手放在摄像头画面中测试：
   - 手整体移动：光标跟随。
   - 食指和大拇指捏合：点击。
   - 食指和大拇指张开成 L：返回。
   - 五指张开并保持一下：HOME。

## 调参位置

手势阈值都在：

```text
app/src/main/java/com/jlxc/wifitouchsender/HandGestureLogic.java
```

重点参数：

- `PINCH_ON`：捏合触发阈值，数值越大越容易触发点击。
- `PINCH_OFF`：松开阈值，数值越大越不容易解除点击状态。
- `POSE_STABLE_MS`：L 返回 / 五指 HOME 需要稳定保持多久才触发。
- `ACTION_COOLDOWN_MS`：返回 / HOME 的防连发间隔。
