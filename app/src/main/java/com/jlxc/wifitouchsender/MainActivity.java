package com.jlxc.wifitouchsender;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREF = "wifi_mouse_sender";
    private static final int DEFAULT_PORT = 47220;
    private static final int REQ_CAMERA = 2210;

    private final CommandSender sender = new CommandSender();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText ipEdit;
    private EditText portEdit;
    private TextView statusText;
    private TextView gestureText;
    private TouchPadView padView;
    private TextureView cameraPreview;
    private SeekBar speedSeek;
    private TextView speedText;
    private Switch absSwitch;
    private Switch gestureSwitch;
    private Switch mirrorSwitch;

    private HandGestureCameraController handCamera;
    private HandGestureLogic gestureLogic;

    private float speed = 1.4f;
    private float pendingDx = 0f;
    private float pendingDy = 0f;
    private boolean moveScheduled = false;
    private long lastSetSendTime = 0L;
    private float lastSetX = 0f;
    private float lastSetY = 0f;
    private int remoteScreenW = 2560;
    private int remoteScreenH = 720;

    private final Runnable flushMoveRunnable = new Runnable() {
        @Override public void run() {
            moveScheduled = false;
            float dx = pendingDx;
            float dy = pendingDy;
            pendingDx = 0f;
            pendingDy = 0f;
            if (Math.abs(dx) > 0.2f || Math.abs(dy) > 0.2f) sender.move(dx, dy, speed);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setupHandGesture();
        loadPrefs();
        bindPad();
        applyTarget();
        checkStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (handCamera != null && handCamera.isRunning()) handCamera.stop();
    }

    @Override
    protected void onDestroy() {
        if (handCamera != null) handCamera.stop();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("WiFi 鼠标输出端 + 手势识别");
        title.setTextColor(Color.rgb(20, 24, 31));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, lp(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("连接接收端后，可用触控板控制，也可打开摄像头手势控制。手势：半捏移动，捏合点击，L形返回，五指张开HOME。接收端默认端口：47220。");
        desc.setTextColor(Color.rgb(85, 91, 103));
        desc.setTextSize(14);
        desc.setPadding(0, 0, 0, dp(10));
        root.addView(desc, lp(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, lp(-1, -2));

        ipEdit = new EditText(this);
        ipEdit.setSingleLine(true);
        ipEdit.setHint("车机IP，例如 192.168.100.124");
        ipEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        row.addView(ipEdit, new LinearLayout.LayoutParams(0, dp(52), 1f));

        portEdit = new EditText(this);
        portEdit.setSingleLine(true);
        portEdit.setHint("端口");
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(dp(92), dp(52));
        portLp.leftMargin = dp(8);
        row.addView(portEdit, portLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, dp(8));
        root.addView(row2, lp(-1, -2));

        Button saveBtn = button("保存目标");
        Button testBtn = button("连接测试");
        Button tapBtn = button("点一下");
        row2.addView(saveBtn, weightLp());
        row2.addView(testBtn, weightLpMargin());
        row2.addView(tapBtn, weightLpMargin());

        statusText = new TextView(this);
        statusText.setText("状态：未连接");
        statusText.setTextColor(Color.rgb(65, 72, 85));
        statusText.setTextSize(13);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackgroundColor(Color.WHITE);
        root.addView(statusText, lp(-1, -2));

        String lastCrash = CrashHandler.readLastCrash(this);
        if (lastCrash != null && lastCrash.length() > 0) {
            TextView crashText = new TextView(this);
            crashText.setText("上次闪退记录：\n" + lastCrash);
            crashText.setTextColor(Color.rgb(170, 36, 42));
            crashText.setTextSize(12);
            crashText.setPadding(dp(10), dp(8), dp(10), dp(8));
            crashText.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams crashLp = lp(-1, -2);
            crashLp.topMargin = dp(8);
            root.addView(crashText, crashLp);
        }

        gestureSwitch = new Switch(this);
        gestureSwitch.setText("开启摄像头手势控制");
        gestureSwitch.setTextColor(Color.rgb(35, 41, 50));
        gestureSwitch.setTextSize(15);
        gestureSwitch.setPadding(0, dp(10), 0, 0);
        root.addView(gestureSwitch, lp(-1, -2));

        mirrorSwitch = new Switch(this);
        mirrorSwitch.setText("前置摄像头左右镜像：像自拍一样控制");
        mirrorSwitch.setTextColor(Color.rgb(35, 41, 50));
        mirrorSwitch.setTextSize(14);
        mirrorSwitch.setPadding(0, dp(2), 0, 0);
        root.addView(mirrorSwitch, lp(-1, -2));

        cameraPreview = new TextureView(this);
        cameraPreview.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams cameraLp = new LinearLayout.LayoutParams(-1, dp(230));
        cameraLp.topMargin = dp(8);
        root.addView(cameraPreview, cameraLp);

        gestureText = new TextView(this);
        gestureText.setText("手势状态：未开启");
        gestureText.setTextColor(Color.rgb(65, 72, 85));
        gestureText.setTextSize(13);
        gestureText.setPadding(dp(10), dp(8), dp(10), dp(8));
        gestureText.setBackgroundColor(Color.WHITE);
        root.addView(gestureText, lp(-1, -2));

        padView = new TouchPadView(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, dp(330));
        padLp.topMargin = dp(12);
        root.addView(padView, padLp);

        LinearLayout control1 = new LinearLayout(this);
        control1.setOrientation(LinearLayout.HORIZONTAL);
        control1.setPadding(0, dp(10), 0, 0);
        root.addView(control1, lp(-1, -2));
        control1.addView(button("返回", v -> sender.back(this::showResult)), weightLp());
        control1.addView(button("主页", v -> sender.home(this::showResult)), weightLpMargin());
        control1.addView(button("最近任务", v -> sender.recents(this::showResult)), weightLpMargin());

        LinearLayout control2 = new LinearLayout(this);
        control2.setOrientation(LinearLayout.HORIZONTAL);
        control2.setPadding(0, dp(8), 0, 0);
        root.addView(control2, lp(-1, -2));
        control2.addView(button("单击", v -> sender.tap(this::showResult)), weightLp());
        control2.addView(button("双击", v -> sender.doubleTap(this::showResult)), weightLpMargin());
        control2.addView(button("长按", v -> sender.longPress(this::showResult)), weightLpMargin());

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(0, dp(12), 0, 0);
        root.addView(settings, lp(-1, -2));

        absSwitch = new Switch(this);
        absSwitch.setText("绝对触控模式：点手机触控板的位置 = 点车机屏幕对应位置");
        absSwitch.setTextColor(Color.rgb(35, 41, 50));
        absSwitch.setTextSize(14);
        settings.addView(absSwitch, lp(-1, -2));

        speedText = new TextView(this);
        speedText.setTextColor(Color.rgb(35, 41, 50));
        speedText.setTextSize(14);
        speedText.setPadding(0, dp(10), 0, 0);
        settings.addView(speedText, lp(-1, -2));

        speedSeek = new SeekBar(this);
        speedSeek.setMax(40);
        settings.addView(speedSeek, lp(-1, -2));

        TextView tips = new TextView(this);
        tips.setText("提示：手势控制需要摄像头权限和 assets/hand_landmarker.task 模型文件。第一次测试建议先点连接测试，确认接收端返回屏幕尺寸后再开启手势。若左右反了，就切换“左右镜像”。");
        tips.setTextColor(Color.rgb(100, 106, 118));
        tips.setTextSize(13);
        tips.setPadding(0, dp(8), 0, dp(20));
        root.addView(tips, lp(-1, -2));

        saveBtn.setOnClickListener(v -> {
            applyTarget();
            savePrefs();
            setStatus("已保存目标：" + sender.getBaseUrl(), true);
        });
        testBtn.setOnClickListener(v -> {
            applyTarget();
            savePrefs();
            checkStatus();
        });
        tapBtn.setOnClickListener(v -> {
            applyTarget();
            sender.tap(this::showResult);
        });

        setContentView(scroll);
    }

    private void setupHandGesture() {
        gestureLogic = new HandGestureLogic(new HandGestureLogic.Listener() {
            @Override public void onCursor(float nx, float ny) {
                sendVisionCursor(nx, ny);
            }
            @Override public void onClick() {
                applyTarget();
                sender.tap(MainActivity.this::showResult);
                setGestureText("手势动作：捏合点击", true);
            }
            @Override public void onBack() {
                applyTarget();
                sender.back(MainActivity.this::showResult);
                setGestureText("手势动作：L形返回", true);
            }
            @Override public void onHome() {
                applyTarget();
                sender.home(MainActivity.this::showResult);
                setGestureText("手势动作：五指HOME", true);
            }
            @Override public void onDebug(String text) {
                setGestureText(text, true);
            }
        });


        gestureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) startGestureMode(); else stopGestureMode();
            savePrefs();
        });
        mirrorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            gestureLogic.setMirrorX(isChecked);
            savePrefs();
        });
    }

    private void startGestureMode() {
        applyTarget();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            gestureSwitch.setChecked(false);
            return;
        }
        gestureLogic.setMirrorX(mirrorSwitch.isChecked());
        gestureLogic.reset();
        try {
            if (handCamera == null) {
                handCamera = new HandGestureCameraController(this, cameraPreview, new HandGestureCameraController.Listener() {
                    @Override public void onHandLandmarks(float[] xy) {
                        handler.post(() -> gestureLogic.process(xy));
                    }
                    @Override public void onNoHand() {
                        handler.post(() -> {
                            setGestureText("手势状态：未检测到手", false);
                            gestureLogic.reset();
                        });
                    }
                    @Override public void onStatus(String text, boolean ok) {
                        handler.post(() -> setGestureText(text, ok));
                    }
                });
            }
            handCamera.start();
        } catch (Throwable e) {
            setGestureText("手势模块启动失败：" + e.getClass().getSimpleName() + " " + (e.getMessage() == null ? "" : e.getMessage()), false);
            if (gestureSwitch != null) gestureSwitch.setChecked(false);
        }
    }

    private void stopGestureMode() {
        if (handCamera != null) handCamera.stop();
        if (gestureText != null) setGestureText("手势状态：已关闭", true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                gestureSwitch.setChecked(true);
            } else {
                setGestureText("摄像头权限被拒绝，无法使用手势控制", false);
            }
        }
    }

    private void sendVisionCursor(float nx, float ny) {
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

    private void bindPad() {
        padView.setListener(new TouchPadView.Listener() {
            @Override public void onRelativeMove(float dx, float dy) {
                applyTarget();
                pendingDx += dx;
                pendingDy += dy;
                if (!moveScheduled) {
                    moveScheduled = true;
                    handler.postDelayed(flushMoveRunnable, 24);
                }
            }

            @Override public void onAbsoluteSet(float x, float y) {
                applyTarget();
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastSetSendTime >= 24 || Math.abs(x - lastSetX) > 12 || Math.abs(y - lastSetY) > 12) {
                    lastSetSendTime = now;
                    lastSetX = x;
                    lastSetY = y;
                    sender.set(x, y);
                }
            }

            @Override public void onScroll(float dy) {
                applyTarget();
                sender.scroll(dy);
            }

            @Override public void onTap() {
                applyTarget();
                sender.tap(MainActivity.this::showResult);
            }

            @Override public void onDoubleTap() {
                applyTarget();
                sender.doubleTap(MainActivity.this::showResult);
            }

            @Override public void onLongPress() {
                applyTarget();
                sender.longPress(MainActivity.this::showResult);
            }
        });

        absSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            padView.setAbsoluteMode(isChecked);
            savePrefs();
        });

        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speed = 0.4f + progress / 10f;
                updateSpeedText();
                if (fromUser) savePrefs();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void applyTarget() {
        String ip = ipEdit.getText().toString().trim();
        int port = DEFAULT_PORT;
        try { port = Integer.parseInt(portEdit.getText().toString().trim()); } catch (Exception ignored) {}
        sender.setTarget(ip, port);
    }

    private void checkStatus() {
        applyTarget();
        setStatus("正在连接 " + sender.getBaseUrl() + " ...", true);
        sender.status((status, error) -> {
            if (error != null) {
                setStatus("连接失败：" + error.getClass().getSimpleName() + " " + error.getMessage(), false);
                return;
            }
            if (status != null) {
                remoteScreenW = status.screenW;
                remoteScreenH = status.screenH;
                padView.setRemoteScreenSize(status.screenW, status.screenH);
                setStatus("连接成功：屏幕 " + status.screenW + "×" + status.screenH +
                        " | 光标 " + status.x + "," + status.y +
                        " | 无障碍=" + status.a11y +
                        " | 悬浮窗=" + status.cursorShown, status.ok);
            }
        });
    }

    private void showResult(String text, boolean ok) {
        setStatus(text, ok);
    }

    private void setStatus(String text, boolean ok) {
        statusText.setText("状态：" + text);
        statusText.setTextColor(ok ? Color.rgb(32, 103, 51) : Color.rgb(170, 36, 42));
    }

    private void setGestureText(String text, boolean ok) {
        if (gestureText == null) return;
        gestureText.setText(text.startsWith("手势") ? text : "手势状态：" + text);
        gestureText.setTextColor(ok ? Color.rgb(32, 103, 51) : Color.rgb(170, 36, 42));
    }

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        ipEdit.setText(sp.getString("ip", "192.168.100.124"));
        portEdit.setText(String.valueOf(sp.getInt("port", DEFAULT_PORT)));
        speed = sp.getFloat("speed", 1.4f);
        int progress = Math.max(0, Math.min(40, Math.round((speed - 0.4f) * 10f)));
        speedSeek.setProgress(progress);
        absSwitch.setChecked(sp.getBoolean("abs", false));
        padView.setAbsoluteMode(absSwitch.isChecked());
        mirrorSwitch.setChecked(sp.getBoolean("mirrorX", true));
        gestureLogic.setMirrorX(mirrorSwitch.isChecked());
        updateSpeedText();
    }

    private void savePrefs() {
        int port = DEFAULT_PORT;
        try { port = Integer.parseInt(portEdit.getText().toString().trim()); } catch (Exception ignored) {}
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("ip", ipEdit.getText().toString().trim())
                .putInt("port", port)
                .putFloat("speed", speed)
                .putBoolean("abs", absSwitch != null && absSwitch.isChecked())
                .putBoolean("mirrorX", mirrorSwitch != null && mirrorSwitch.isChecked())
                .apply();
    }

    private void updateSpeedText() {
        if (speedText != null) speedText.setText(String.format(java.util.Locale.US, "光标速度：%.1f 倍", speed));
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = button(text);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private LinearLayout.LayoutParams weightLpMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.leftMargin = dp(8);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
