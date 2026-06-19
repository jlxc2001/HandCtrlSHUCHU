# WiFiTouchSender HandGesture v5.7 Low Latency UDP

本版基于 v5.6：摄像头预览、MediaPipe 手部关键点、骨骼叠加和性能面板均保留。

## v5.7 改动

1. HandLandmarker 从 `RunningMode.IMAGE` 改为 `RunningMode.VIDEO`，让 MediaPipe 使用视频跟踪逻辑，减少每帧重复检测造成的延迟。
2. 旧的 `YUV -> JPEG -> Bitmap` 回退路径改为 `YUV_420_888 -> ARGB Bitmap` 直转，减少 JPEG 编码/解码开销。
3. 新增 `UDP 低延迟坐标模式` 开关。开启后 `/api/set` 高频坐标不再走 HTTP，而是 UDP 发送：

```text
SET <x> <y>

```

UDP 端口默认和 HTTP 端口一致，例如 47220。TCP/HTTP 和 UDP 使用同一个数字端口不冲突。

4. HTTP 坐标超时时间从 120/160ms 降低到 80/100ms，避免网络慢时长期拖尾。

## 接收端需要新增的 UDP 协议

接收端监听 UDP 端口 47220，收到 UTF-8 文本：

```text
SET 123.4 567.8
```

解析后直接执行绝对坐标移动，等价于现有 HTTP：

```text
POST /api/set x=123.4&y=567.8
```

点击、返回、HOME 仍然走 HTTP，只有高频坐标建议走 UDP。

## 继续判断瓶颈

- 骨骼流畅、光标卡：优先改接收端 UDP。
- 骨骼也卡：看性能面板里的 `推理 ms`。如果仍然 80~120ms，下一步再单独测试 GPU delegate 或纯 TFLite 手部关键点方案。
