package com.jlxc.wifitouchsender;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
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

    private final CommandSender sender = new CommandSender();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText ipEdit;
    private EditText portEdit;
    private TextView statusText;
    private TouchPadView padView;
    private SeekBar speedSeek;
    private TextView speedText;
    private Switch absSwitch;

    private float speed = 1.4f;
    private float pendingDx = 0f;
    private float pendingDy = 0f;
    private boolean moveScheduled = false;
    private long lastSetSendTime = 0L;
    private float lastSetX = 0f;
    private float lastSetY = 0f;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        loadPrefs();
        bindPad();
        applyTarget();
        checkStatus();
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
        title.setText("WiFi 鼠标输出端 v5 Clean");
        title.setTextColor(Color.rgb(20, 24, 31));
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, lp(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("主界面保持原版触控板逻辑，手势识别被放到独立页面。这样可以先确认基础发射端稳定，再单独排查摄像头/MediaPipe。");
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

        Button gestureBtn = button("进入摄像头手势模式");
        LinearLayout.LayoutParams gestureBtnLp = lp(-1, dp(48));
        gestureBtnLp.bottomMargin = dp(8);
        root.addView(gestureBtn, gestureBtnLp);

        statusText = new TextView(this);
        statusText.setText("状态：未连接");
        statusText.setTextColor(Color.rgb(65, 72, 85));
        statusText.setTextSize(13);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackgroundColor(Color.WHITE);
        root.addView(statusText, lp(-1, -2));

        padView = new TouchPadView(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, dp(390));
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
        tips.setText("提示：这版从稳定原版重新接入手势。启动主界面不会初始化摄像头和 MediaPipe。先测试触控板，再进入摄像头手势页面。");
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
        gestureBtn.setOnClickListener(v -> {
            applyTarget();
            savePrefs();
            startActivity(new Intent(this, HandGestureActivity.class));
        });

        setContentView(scroll);
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

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        ipEdit.setText(sp.getString("ip", "192.168.100.124"));
        portEdit.setText(String.valueOf(sp.getInt("port", DEFAULT_PORT)));
        speed = sp.getFloat("speed", 1.4f);
        int progress = Math.max(0, Math.min(40, Math.round((speed - 0.4f) * 10f)));
        speedSeek.setProgress(progress);
        absSwitch.setChecked(sp.getBoolean("abs", false));
        padView.setAbsoluteMode(absSwitch.isChecked());
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
