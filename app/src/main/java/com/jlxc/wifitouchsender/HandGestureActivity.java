package com.jlxc.wifitouchsender;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class HandGestureActivity extends Activity {
    private static final String PREF = "wifi_mouse_sender";
    private static final int DEFAULT_PORT = 47220;
    private static final int REQ_CAMERA = 5101;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CommandSender sender = new CommandSender();

    private EditText ipEdit;
    private EditText portEdit;
    private TextView statusText;
    private TextView debugText;
    private TextureView preview;
    private HandSkeletonOverlayView skeletonOverlay;
    private Switch mirrorSwitch;
    private Switch autoStartSwitch;

    private GestureController controller;
    private HandGestureLogic logic;
    private int remoteScreenW = 2560;
    private int remoteScreenH = 720;
    private long lastSetSendTime = 0L;
    private float lastSetX = -1f;
    private float lastSetY = -1f;
    private long lastNoHandUiTime = 0L;

    private String gestureInfo = "未检测";
    private String perfInfo = "识别未启动";
    private String netInfo = "网络未发送";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setupLogic();
        sender.setMetricsListener(text -> {
            netInfo = text;
            renderDebug(true);
        });
        loadPrefs();
        applyTarget();
        setStatus("手势页面已打开。建议先点连接测试，再启动摄像头，最后加载模型。", true);
    }

    @Override protected void onPause() {
        stopGesture();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopGesture();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("摄像头手势控制 v5.6 性能版");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 24, 31));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, lp(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("半捏移动光标，拇指食指捏合点击，L 形返回，五指张开 HOME。v5.6 加入手部骨骼叠加、识别/网络耗时显示和高频坐标合并发送。 ");
        desc.setTextSize(14);
        desc.setTextColor(Color.rgb(85, 91, 103));
        desc.setPadding(0, dp(6), 0, dp(10));
        root.addView(desc, lp(-1, -2));

        LinearLayout targetRow = new LinearLayout(this);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(targetRow, lp(-1, -2));

        ipEdit = new EditText(this);
        ipEdit.setSingleLine(true);
        ipEdit.setHint("接收端 IP");
        ipEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        targetRow.addView(ipEdit, new LinearLayout.LayoutParams(0, dp(52), 1f));

        portEdit = new EditText(this);
        portEdit.setSingleLine(true);
        portEdit.setHint("端口");
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(dp(92), dp(52));
        portLp.leftMargin = dp(8);
        targetRow.addView(portEdit, portLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(8), 0, dp(8));
        root.addView(btnRow, lp(-1, -2));

        Button testBtn = button("连接测试");
        Button startBtn = button("启动摄像头");
        Button modelBtn = button("加载模型");
        Button stopBtn = button("停止");
        btnRow.addView(testBtn, weightLp());
        btnRow.addView(startBtn, weightLpMargin());
        btnRow.addView(modelBtn, weightLpMargin());
        btnRow.addView(stopBtn, weightLpMargin());

        mirrorSwitch = new Switch(this);
        mirrorSwitch.setText("前置摄像头左右镜像：像自拍一样控制");
        mirrorSwitch.setTextSize(14);
        mirrorSwitch.setTextColor(Color.rgb(35, 41, 50));
        root.addView(mirrorSwitch, lp(-1, -2));

        autoStartSwitch = new Switch(this);
        autoStartSwitch.setText("打开本页后自动开始手势：关闭更方便排查闪退");
        autoStartSwitch.setTextSize(14);
        autoStartSwitch.setTextColor(Color.rgb(35, 41, 50));
        root.addView(autoStartSwitch, lp(-1, -2));

        FrameLayout previewBox = new FrameLayout(this);
        preview = new TextureView(this);
        skeletonOverlay = new HandSkeletonOverlayView(this);
        previewBox.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        previewBox.addView(skeletonOverlay, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(330));
        previewLp.topMargin = dp(10);
        root.addView(previewBox, previewLp);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams statusLp = lp(-1, -2);
        statusLp.topMargin = dp(10);
        root.addView(statusText, statusLp);

        debugText = new TextView(this);
        debugText.setTextSize(13);
        debugText.setTextColor(Color.rgb(65, 72, 85));
        debugText.setPadding(dp(10), dp(8), dp(10), dp(8));
        debugText.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams debugLp = lp(-1, -2);
        debugLp.topMargin = dp(8);
        root.addView(debugText, debugLp);
        renderDebug(true);

        TextView tips = new TextView(this);
        tips.setText("性能判断：如果骨骼跟手但车机光标慢，多半是网络/接收端 HTTP 链路慢；如果骨骼本身也一卡一卡，就是本机识别链路慢。v5.6 会把坐标请求合并，只发最新点，避免网络请求堆积拖尾。后续接收端若加入 UDP 坐标接口，延迟还能继续下降。 ");
        tips.setTextSize(13);
        tips.setTextColor(Color.rgb(100, 106, 118));
        tips.setPadding(0, dp(8), 0, dp(20));
        root.addView(tips, lp(-1, -2));

        testBtn.setOnClickListener(v -> checkStatus());
        startBtn.setOnClickListener(v -> startGesture());
        modelBtn.setOnClickListener(v -> loadModelOnly());
        stopBtn.setOnClickListener(v -> stopGesture());
        mirrorSwitch.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            if (logic != null) logic.setMirrorX(checked);
            if (skeletonOverlay != null) skeletonOverlay.setMirrorX(checked);
            savePrefs();
        });
        autoStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> savePrefs());

        setContentView(scroll);
    }

    private void setupLogic() {
        logic = new HandGestureLogic(new HandGestureLogic.Listener() {
            @Override public void onCursor(float nx, float ny) { sendCursor(nx, ny); }
            @Override public void onClick() { sender.tap(HandGestureActivity.this::showCommandResult); gestureInfo = "动作：捏合点击"; renderDebug(true); }
            @Override public void onBack() { sender.back(HandGestureActivity.this::showCommandResult); gestureInfo = "动作：L 形返回"; renderDebug(true); }
            @Override public void onHome() { sender.home(HandGestureActivity.this::showCommandResult); gestureInfo = "动作：五指 HOME"; renderDebug(true); }
            @Override public void onDebug(String text) { gestureInfo = text; renderDebug(true); }
        });
    }

    private void startGesture() {
        applyTarget();
        savePrefs();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        try {
            if (logic != null) {
                logic.setMirrorX(mirrorSwitch.isChecked());
                logic.reset();
            }
            if (skeletonOverlay != null) {
                skeletonOverlay.setMirrorX(mirrorSwitch.isChecked());
                skeletonOverlay.clear();
            }
            if (controller == null) controller = new HandGestureCameraController(this, preview, createGestureListener());
            controller.start();
        } catch (Throwable e) {
            setDebug("启动摄像头失败：" + compact(e), false);
        }
    }

    private void loadModelOnly() {
        applyTarget();
        savePrefs();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        try {
            if (controller == null || !controller.isRunning()) {
                setDebug("请先点击‘启动摄像头’，确认能看到预览画面后再加载模型。", false);
                return;
            }
            if (logic != null) {
                logic.setMirrorX(mirrorSwitch.isChecked());
                logic.reset();
            }
            setDebug("正在加载 MediaPipe 模型。加载成功后会显示手部骨骼和识别/网络耗时。", true);
            controller.loadModel();
        } catch (Throwable e) {
            setDebug("加载模型失败：" + compact(e), false);
        }
    }

    private GestureEventListener createGestureListener() {
        return new GestureEventListener() {
            @Override public void onHandLandmarks(float[] xy) {
                mainHandler.post(() -> {
                    if (skeletonOverlay != null) skeletonOverlay.setLandmarks(xy);
                    if (logic != null) logic.process(xy);
                });
            }
            @Override public void onNoHand() {
                mainHandler.post(() -> {
                    if (skeletonOverlay != null) skeletonOverlay.clear();
                    if (logic != null) logic.reset();
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastNoHandUiTime > 300) {
                        lastNoHandUiTime = now;
                        gestureInfo = "未检测到手";
                        renderDebug(false);
                    }
                });
            }
            @Override public void onStatus(String text, boolean ok) {
                mainHandler.post(() -> setDebug(text, ok));
            }
            @Override public void onMetrics(String text) {
                mainHandler.post(() -> {
                    perfInfo = text;
                    renderDebug(true);
                });
            }
        };
    }

    private void stopGesture() {
        try {
            if (controller != null) controller.stop();
        } catch (Throwable ignored) {}
        if (skeletonOverlay != null) skeletonOverlay.clear();
        setDebug("手势已停止", true);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGesture();
            else setDebug("摄像头权限被拒绝", false);
        }
    }

    private void sendCursor(float nx, float ny) {
        float x = nx * Math.max(1, remoteScreenW - 1);
        float y = ny * Math.max(1, remoteScreenH - 1);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSetSendTime >= 16 || Math.abs(x - lastSetX) > 5 || Math.abs(y - lastSetY) > 5) {
            lastSetSendTime = now;
            lastSetX = x;
            lastSetY = y;
            sender.set(x, y);
        }
    }

    private void checkStatus() {
        applyTarget();
        savePrefs();
        setStatus("正在连接 " + sender.getBaseUrl() + " ...", true);
        sender.status((status, error) -> {
            if (error != null) {
                setStatus("连接失败：" + error.getClass().getSimpleName() + " " + error.getMessage(), false);
                return;
            }
            if (status != null) {
                remoteScreenW = status.screenW;
                remoteScreenH = status.screenH;
                setStatus("连接成功：屏幕 " + status.screenW + "×" + status.screenH +
                        " | 光标 " + status.x + "," + status.y +
                        " | 无障碍=" + status.a11y +
                        " | 悬浮窗=" + status.cursorShown, status.ok);
            }
        });
    }

    private void showCommandResult(String text, boolean ok) { setStatus(text, ok); }

    private void applyTarget() {
        String ip = ipEdit.getText().toString().trim();
        int port = DEFAULT_PORT;
        try { port = Integer.parseInt(portEdit.getText().toString().trim()); } catch (Exception ignored) {}
        sender.setTarget(ip, port);
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        ipEdit.setText(sp.getString("ip", "192.168.100.124"));
        portEdit.setText(String.valueOf(sp.getInt("port", DEFAULT_PORT)));
        mirrorSwitch.setChecked(sp.getBoolean("mirrorX", true));
        autoStartSwitch.setChecked(sp.getBoolean("gestureAutoStart", false));
        if (logic != null) logic.setMirrorX(mirrorSwitch.isChecked());
        if (skeletonOverlay != null) skeletonOverlay.setMirrorX(mirrorSwitch.isChecked());
        if (autoStartSwitch.isChecked()) preview.postDelayed(this::startGesture, 250);
    }

    private void savePrefs() {
        int port = DEFAULT_PORT;
        try { port = Integer.parseInt(portEdit.getText().toString().trim()); } catch (Exception ignored) {}
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("ip", ipEdit.getText().toString().trim())
                .putInt("port", port)
                .putBoolean("mirrorX", mirrorSwitch != null && mirrorSwitch.isChecked())
                .putBoolean("gestureAutoStart", autoStartSwitch != null && autoStartSwitch.isChecked())
                .apply();
    }

    private void setStatus(String text, boolean ok) {
        statusText.setText("状态：" + text);
        statusText.setTextColor(ok ? Color.rgb(32, 103, 51) : Color.rgb(170, 36, 42));
    }

    private void setDebug(String text, boolean ok) {
        gestureInfo = text;
        renderDebug(ok);
    }

    private void renderDebug(boolean ok) {
        if (debugText == null) return;
        debugText.setText("手势：" + gestureInfo + "\n性能：" + perfInfo + "\n网络：" + netInfo);
        debugText.setTextColor(ok ? Color.rgb(32, 103, 51) : Color.rgb(170, 36, 42));
    }

    private String compact(Throwable e) {
        if (e == null) return "unknown";
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : " " + msg);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams weightLp() { return new LinearLayout.LayoutParams(0, dp(48), 1f); }
    private LinearLayout.LayoutParams weightLpMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.leftMargin = dp(8);
        return lp;
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
