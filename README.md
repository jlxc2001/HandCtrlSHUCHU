# WiFiTouchSender v4 startup baseline no MediaPipe

这是诊断包，不包含 MediaPipe、摄像头手势、任何 native 模型依赖。

用途：如果 HandGesture v4 仍然启动闪退，但这个 baseline 能打开，说明问题集中在 MediaPipe/native 依赖或模型链路；如果这个也闪退，则问题在基础 Activity/系统/安装环境，需要看 logcat。
