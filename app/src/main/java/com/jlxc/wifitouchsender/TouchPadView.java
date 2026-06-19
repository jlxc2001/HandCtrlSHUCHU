package com.jlxc.wifitouchsender;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class TouchPadView extends View {
    public interface Listener {
        void onRelativeMove(float dx, float dy);
        void onAbsoluteSet(float x, float y);
        void onScroll(float dy);
        void onTap();
        void onDoubleTap();
        void onLongPress();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private boolean absoluteMode = false;
    private int screenW = 2560;
    private int screenH = 720;

    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private float moved;
    private long downTime;
    private long lastTapTime;
    private boolean twoFinger;
    private float lastTwoY;

    public TouchPadView(Context context) { super(context); init(); }
    public TouchPadView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setFocusable(true);
        setClickable(true);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setAbsoluteMode(boolean absoluteMode) {
        this.absoluteMode = absoluteMode;
        invalidate();
    }

    public void setRemoteScreenSize(int w, int h) {
        if (w > 0) screenW = w;
        if (h > 0) screenH = h;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF171A20);
        canvas.drawRoundRect(new RectF(0, 0, w, h), dp(18), dp(18), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF333945);
        for (int i = 1; i < 4; i++) {
            float x = w * i / 4f;
            canvas.drawLine(x, dp(14), x, h - dp(14), paint);
        }
        for (int i = 1; i < 3; i++) {
            float y = h * i / 3f;
            canvas.drawLine(dp(14), y, w - dp(14), y, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFF4E5A6E);
        canvas.drawRoundRect(new RectF(dp(1), dp(1), w - dp(1), h - dp(1)), dp(18), dp(18), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE8EAED);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(22));
        canvas.drawText(absoluteMode ? "绝对触控模式" : "相对触控板模式", w / 2f, h / 2f - dp(10), paint);

        paint.setColor(0xFF9AA0A6);
        paint.setTextSize(dp(13));
        canvas.drawText("单指滑动移动光标，单击点击，长按长按，双指上下滚动", w / 2f, h / 2f + dp(24), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null) return true;
        getParent().requestDisallowInterceptTouchEvent(true);
        int action = event.getActionMasked();

        if (event.getPointerCount() >= 2) {
            handleTwoFinger(event, action);
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                twoFinger = false;
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                downTime = SystemClock.uptimeMillis();
                moved = 0f;
                if (absoluteMode) listener.onAbsoluteSet(mapX(lastX), mapY(lastY));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float y = event.getY();
                float dx = x - lastX;
                float dy = y - lastY;
                moved += Math.sqrt(dx * dx + dy * dy);
                lastX = x;
                lastY = y;
                if (absoluteMode) {
                    listener.onAbsoluteSet(mapX(x), mapY(y));
                } else {
                    if (Math.abs(dx) > 0.2f || Math.abs(dy) > 0.2f) listener.onRelativeMove(dx, dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                performClick();
                if (!twoFinger) {
                    long now = SystemClock.uptimeMillis();
                    long duration = now - downTime;
                    float total = distance(event.getX(), event.getY(), downX, downY) + moved * 0.15f;
                    if (absoluteMode) listener.onAbsoluteSet(mapX(event.getX()), mapY(event.getY()));
                    if (total < dp(14)) {
                        if (duration >= 450) {
                            listener.onLongPress();
                        } else {
                            if (now - lastTapTime < 320) {
                                listener.onDoubleTap();
                                lastTapTime = 0;
                            } else {
                                listener.onTap();
                                lastTapTime = now;
                            }
                        }
                    }
                }
                twoFinger = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                twoFinger = false;
                return true;
            }
        }
        return true;
    }

    private void handleTwoFinger(MotionEvent event, int action) {
        float cy = (event.getY(0) + event.getY(1)) / 2f;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN) {
            twoFinger = true;
            lastTwoY = cy;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            twoFinger = true;
            float dy = cy - lastTwoY;
            if (Math.abs(dy) >= dp(5)) {
                listener.onScroll(dy);
                lastTwoY = cy;
            }
        }
        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            twoFinger = true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float mapX(float x) {
        int w = Math.max(1, getWidth());
        return Math.max(0, Math.min(screenW - 1, x / w * screenW));
    }

    private float mapY(float y) {
        int h = Math.max(1, getHeight());
        return Math.max(0, Math.min(screenH - 1, y / h * screenH));
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
