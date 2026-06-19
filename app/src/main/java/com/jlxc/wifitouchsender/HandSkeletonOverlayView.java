package com.jlxc.wifitouchsender;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class HandSkeletonOverlayView extends View {
    private static final int[][] BONES = new int[][]{
            {0, 1}, {1, 2}, {2, 3}, {3, 4},
            {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {5, 9}, {9, 10}, {10, 11}, {11, 12},
            {9, 13}, {13, 14}, {14, 15}, {15, 16},
            {13, 17}, {17, 18}, {18, 19}, {19, 20},
            {0, 17}
    };

    private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint palmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] landmarks;
    private boolean mirrorX = true;
    private long lastUpdateMs = 0L;

    public HandSkeletonOverlayView(Context context) { super(context); init(); }
    public HandSkeletonOverlayView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        bonePaint.setColor(Color.argb(230, 0, 255, 170));
        bonePaint.setStrokeWidth(dp(3.0f));
        bonePaint.setStyle(Paint.Style.STROKE);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);

        pointPaint.setColor(Color.argb(245, 255, 255, 255));
        pointPaint.setStyle(Paint.Style.FILL);

        palmPaint.setColor(Color.argb(70, 0, 255, 170));
        palmPaint.setStyle(Paint.Style.FILL);
    }

    public void setMirrorX(boolean mirrorX) {
        this.mirrorX = mirrorX;
        invalidate();
    }

    public void setLandmarks(float[] xy) {
        if (xy == null || xy.length < 42) {
            clear();
            return;
        }
        this.landmarks = xy.clone();
        lastUpdateMs = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    public void clear() {
        landmarks = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float[] xy = landmarks;
        if (xy == null || xy.length < 42) return;
        // 识别结果过旧时自动隐藏骨骼，避免画面停住误判为卡顿。
        if (android.os.SystemClock.uptimeMillis() - lastUpdateMs > 450) return;

        float[] px = new float[21];
        float[] py = new float[21];
        for (int i = 0; i < 21; i++) {
            float nx = xy[i * 2];
            float ny = xy[i * 2 + 1];
            if (mirrorX) nx = 1f - nx;
            px[i] = clamp(nx) * getWidth();
            py[i] = clamp(ny) * getHeight();
        }

        android.graphics.Path palm = new android.graphics.Path();
        palm.moveTo(px[0], py[0]);
        palm.lineTo(px[5], py[5]);
        palm.lineTo(px[9], py[9]);
        palm.lineTo(px[13], py[13]);
        palm.lineTo(px[17], py[17]);
        palm.close();
        canvas.drawPath(palm, palmPaint);

        for (int[] b : BONES) {
            canvas.drawLine(px[b[0]], py[b[0]], px[b[1]], py[b[1]], bonePaint);
        }
        for (int i = 0; i < 21; i++) {
            float r = (i == 4 || i == 8) ? dp(5.0f) : dp(3.6f);
            canvas.drawCircle(px[i], py[i], r, pointPaint);
        }
    }

    private float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
