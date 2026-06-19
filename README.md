# WiFiTouchSender HandGesture v5.1

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
