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
    private Switch mirrorSwitch;
    private Switch autoStartSwitch;

    private GestureController controller;
    private HandGestureLogic logic;
    private int remoteScreenW = 2560;
    private int remoteScreenH = 720;
    private long lastSetSendTime = 0L;
    private float lastSetX = -1f;
    private float lastSetY = -1f;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setupLogic();
        loadPrefs();
        applyTarget();
        setStatus("手势页面已打开。先点连接测试，再点开始手势。", true);
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
        title.setText("摄像头手势控制 v5.2");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 24, 31));
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, lp(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("半捏移动光标，拇指食指捏合点击，L 形返回，五指张开 HOME。该页面才会加载摄像头和 MediaPipe。");
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
        Button startBtn = button("开始手势");
        Button stopBtn = button("停止");
        btnRow.addView(testBtn, weightLp());
        btnRow.addView(startBtn, weightLpMargin());
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

        preview = new TextureView(this);
        // TextureView cannot have a background drawable on Android; setting one crashes on some devices.
        // Keep it transparent and use the parent layout background instead.
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(330));
        previewLp.topMargin = dp(10);
        root.addView(preview, previewLp);

        statusText = new TextView(this);
        statusText.setTextSize(13);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams statusLp = lp(-1, -2);
        statusLp.topMargin = dp(10);
        root.addView(statusText, statusLp);

        debugText = new TextView(this);
        debugText.setText("调试：未启动");
        debugText.setTextSize(13);
        debugText.setTextColor(Color.rgb(65, 72, 85));
        debugText.setPadding(dp(10), dp(8), dp(10), dp(8));
        debugText.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams debugLp = lp(-1, -2);
        debugLp.topMargin = dp(8);
        root.addView(debugText, debugLp);

        TextView tips = new TextView(this);
        tips.setText("如果进入本页不闪退，但点开始手势闪退，问题就在 MediaPipe / 模型 / 摄像头链路。若开始后只显示未检测到手，先把手放到前摄画面中央。模型文件必须在 assets/models/hand_landmarker.task。");
        tips.setTextSize(13);
        tips.setTextColor(Color.rgb(100, 106, 118));
        tips.setPadding(0, dp(8), 0, dp(20));
        root.addView(tips, lp(-1, -2));

        testBtn.setOnClickListener(v -> checkStatus());
        startBtn.setOnClickListener(v -> startGesture());
        stopBtn.setOnClickListener(v -> stopGesture());
        mirrorSwitch.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            if (logic != null) logic.setMirrorX(checked);
            savePrefs();
        });
        autoStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> savePrefs());

        setContentView(scroll);
    }

    private void setupLogic() {
        logic = new HandGestureLogic(new HandGestureLogic.Listener() {
            @Override public void onCursor(float nx, float ny) { sendCursor(nx, ny); }
            @Override public void onClick() { sender.tap(HandGestureActivity.this::showCommandResult); setDebug("动作：捏合点击", true); }
            @Override public void onBack() { sender.back(HandGestureActivity.this::showCommandResult); setDebug("动作：L 形返回", true); }
            @Override public void onHome() { sender.home(HandGestureActivity.this::showCommandResult); setDebug("动作：五指 HOME", true); }
            @Override public void onDebug(String text) { setDebug(text, true); }
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
            if (controller == null) controller = new HandGestureCameraController(this, preview, createGestureListener());
            controller.start();
        } catch (Throwable e) {
            setDebug("启动手势失败：" + compact(e), false);
        }
    }

    private GestureEventListener createGestureListener() {
        return new GestureEventListener() {
            @Override public void onHandLandmarks(float[] xy) {
                mainHandler.post(() -> logic.process(xy));
            }
            @Override public void onNoHand() {
                mainHandler.post(() -> {
                    if (logic != null) logic.reset();
                    setDebug("未检测到手", false);
                });
            }
            @Override public void onStatus(String text, boolean ok) {
                mainHandler.post(() -> setDebug(text, ok));
            }
        };
    }

    private void stopGesture() {
        try {
            if (controller != null) controller.stop();
        } catch (Throwable ignored) {}
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
        applyTarget();
        float x = nx * Math.max(1, remoteScreenW - 1);
        float y = ny * Math.max(1, remoteScreenH - 1);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSetSendTime >= 30 || Math.abs(x - lastSetX) > 10 || Math.abs(y - lastSetY) > 10) {
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
        if (debugText == null) return;
        debugText.setText("调试：" + text);
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
