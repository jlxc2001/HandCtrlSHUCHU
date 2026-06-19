# WiFiTouchSender / WiFi 鼠标输出端

这是给 `WiFiTouchDemo` 接收端配套的手机端原生 APK。它不需要蓝牙、不需要 USB、不需要无障碍权限，只负责把手机触控板上的动作通过局域网 HTTP 指令发送给车机接收端。

## 使用方式

1. 车机端继续运行之前的 `WiFiTouchDemo` 接收端，并启动服务。
2. 在手机上安装本项目打包出的 APK。
3. 打开手机端 APK，填写车机 IP，例如：

   ```text
   192.168.100.124
   ```

4. 端口保持默认：

   ```text
   47220
   ```

5. 点击“连接测试”。如果成功，会显示车机屏幕分辨率、光标坐标、无障碍状态和悬浮窗状态。
6. 在触控板区域滑动即可移动车机端悬浮光标。

## 操作说明

- 单指滑动：移动光标
- 单击：点击光标所在位置
- 双击：双击
- 长按：长按
- 双指上下滑动：滚动
- 返回 / 主页 / 最近任务：调用接收端无障碍全局动作
- 相对触控板模式：像笔记本触控板一样移动光标
- 绝对触控模式：手机触控板坐标直接映射到车机屏幕坐标

## 与接收端的接口协议

接收端默认地址：

```text
http://车机IP:47220
```

### 状态

```http
GET /status
```

返回示例：

```json
{
  "ok": true,
  "ip": "192.168.100.124",
  "port": 47220,
  "x": 1280,
  "y": 360,
  "screenW": 2560,
  "screenH": 720,
  "cursorShown": true,
  "a11y": true
}
```

### 相对移动

```http
POST /api/move
Content-Type: application/x-www-form-urlencoded

dx=10&dy=5&speed=1.4
```

### 绝对设置光标位置

```http
POST /api/set
Content-Type: application/x-www-form-urlencoded

x=1280&y=360
```

### 点击类动作

```http
POST /api/tap
POST /api/doubletap
POST /api/longpress
```

### 滚动

```http
POST /api/scroll
Content-Type: application/x-www-form-urlencoded

dy=30
```

### 系统按键

```http
POST /api/back
POST /api/home
POST /api/recents
```

## GitHub Actions 打包

上传到 GitHub 后，进入 Actions，运行：

```text
Build Debug APK
```

打包产物名称：

```text
WiFiTouchSender-debug-apk
```

APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 注意

如果手机端连接测试成功，但点击/返回/Home 无效，说明网络没问题，问题大概率在车机接收端的无障碍服务没有真正生效。

如果连接测试失败，先确认手机和车机在同一个 WiFi / 热点局域网内，并且车机端服务已经启动。
