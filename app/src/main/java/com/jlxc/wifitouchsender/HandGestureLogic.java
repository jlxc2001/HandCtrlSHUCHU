package com.jlxc.wifitouchsender;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

public class HandGestureLogic {
    public interface Listener {
        void onCursor(float nx, float ny);
        void onClick();
        void onBack();
        void onHome();
        void onDebug(String text);
    }

    private static final int WRIST = 0;
    private static final int THUMB_IP = 3;
    private static final int THUMB_TIP = 4;
    private static final int INDEX_MCP = 5;
    private static final int INDEX_PIP = 6;
    private static final int INDEX_TIP = 8;
    private static final int MIDDLE_MCP = 9;
    private static final int MIDDLE_PIP = 10;
    private static final int MIDDLE_TIP = 12;
    private static final int RING_MCP = 13;
    private static final int RING_PIP = 14;
    private static final int RING_TIP = 16;
    private static final int PINKY_MCP = 17;
    private static final int PINKY_PIP = 18;
    private static final int PINKY_TIP = 20;

    private static final float PINCH_ON = 0.34f;
    private static final float PINCH_OFF = 0.44f;
    private static final long PINCH_STABLE_MS = 70L;
    private static final long POSE_STABLE_MS = 360L;
    private static final long ACTION_COOLDOWN_MS = 1000L;

    private final Listener listener;
    private boolean mirrorX = true;
    private boolean pinchDown = false;
    private long pinchCandidateSince = 0L;
    private long lastClickTime = 0L;
    private long lastBackTime = 0L;
    private long lastHomeTime = 0L;
    private String candidatePose = "NONE";
    private long candidateSince = 0L;
    private float smoothX = -1f;
    private float smoothY = -1f;

    public HandGestureLogic(Listener listener) {
        this.listener = listener;
    }

    public void setMirrorX(boolean mirrorX) {
        this.mirrorX = mirrorX;
    }

    public void reset() {
        pinchDown = false;
        pinchCandidateSince = 0L;
        candidatePose = "NONE";
        candidateSince = 0L;
        smoothX = -1f;
        smoothY = -1f;
    }

    public void process(List<NormalizedLandmark> lm) {
        if (lm == null || lm.size() < 21) return;
        long now = android.os.SystemClock.uptimeMillis();

        float palm = Math.max(0.001f, dist(lm, WRIST, MIDDLE_MCP));
        float pinchRatio = dist(lm, THUMB_TIP, INDEX_TIP) / palm;

        boolean indexOpen = fingerExtended(lm, INDEX_TIP, INDEX_PIP, WRIST);
        boolean middleOpen = fingerExtended(lm, MIDDLE_TIP, MIDDLE_PIP, WRIST);
        boolean ringOpen = fingerExtended(lm, RING_TIP, RING_PIP, WRIST);
        boolean pinkyOpen = fingerExtended(lm, PINKY_TIP, PINKY_PIP, WRIST);
        boolean thumbOpen = thumbExtended(lm, palm);
        int openCount = (thumbOpen ? 1 : 0) + (indexOpen ? 1 : 0) + (middleOpen ? 1 : 0)
                + (ringOpen ? 1 : 0) + (pinkyOpen ? 1 : 0);

        updateCursor(lm);

        boolean homePose = thumbOpen && indexOpen && middleOpen && ringOpen && pinkyOpen;
        boolean backPose = thumbOpen && indexOpen && pinchRatio > 0.86f && openCount <= 3 && !homePose;
        boolean pinchPose = pinchRatio < PINCH_ON && !homePose && !backPose;

        String pose = homePose ? "HOME" : (backPose ? "BACK_L" : (pinchPose ? "PINCH" : "MOVE"));
        updatePoseCandidate(pose, now);

        if (homePose && isPoseStable("HOME", now, POSE_STABLE_MS)) {
            if (now - lastHomeTime > ACTION_COOLDOWN_MS) {
                lastHomeTime = now;
                pinchDown = false;
                listener.onHome();
            }
        } else if (backPose && isPoseStable("BACK_L", now, POSE_STABLE_MS)) {
            if (now - lastBackTime > ACTION_COOLDOWN_MS) {
                lastBackTime = now;
                pinchDown = false;
                listener.onBack();
            }
        } else if (pinchPose) {
            if (pinchCandidateSince == 0L) pinchCandidateSince = now;
            if (!pinchDown && now - pinchCandidateSince >= PINCH_STABLE_MS && now - lastClickTime > 280L) {
                pinchDown = true;
                lastClickTime = now;
                listener.onClick();
            }
        } else {
            pinchCandidateSince = 0L;
            if (pinchRatio > PINCH_OFF) pinchDown = false;
        }

        listener.onDebug(String.format(java.util.Locale.US,
                "手势：%s | 张开=%d | 捏合距离=%.2f | 食指=%s 拇指=%s",
                pose, openCount, pinchRatio, indexOpen ? "开" : "收", thumbOpen ? "开" : "收"));
    }

    private void updateCursor(List<NormalizedLandmark> lm) {
        float cx = (x(lm, WRIST) + x(lm, INDEX_MCP) + x(lm, MIDDLE_MCP) + x(lm, RING_MCP) + x(lm, PINKY_MCP)) / 5f;
        float cy = (y(lm, WRIST) + y(lm, INDEX_MCP) + y(lm, MIDDLE_MCP) + y(lm, RING_MCP) + y(lm, PINKY_MCP)) / 5f;
        if (mirrorX) cx = 1f - cx;
        cx = clamp01(cx);
        cy = clamp01(cy);
        if (smoothX < 0f || smoothY < 0f) {
            smoothX = cx;
            smoothY = cy;
        } else {
            float alpha = 0.34f;
            smoothX += (cx - smoothX) * alpha;
            smoothY += (cy - smoothY) * alpha;
        }
        listener.onCursor(clamp01(smoothX), clamp01(smoothY));
    }

    private void updatePoseCandidate(String pose, long now) {
        if (!pose.equals(candidatePose)) {
            candidatePose = pose;
            candidateSince = now;
        }
    }

    private boolean isPoseStable(String pose, long now, long minMs) {
        return pose.equals(candidatePose) && now - candidateSince >= minMs;
    }

    private boolean fingerExtended(List<NormalizedLandmark> lm, int tip, int pip, int wrist) {
        return dist(lm, wrist, tip) > dist(lm, wrist, pip) * 1.12f;
    }

    private boolean thumbExtended(List<NormalizedLandmark> lm, float palm) {
        float wristToTip = dist(lm, WRIST, THUMB_TIP);
        float wristToIp = dist(lm, WRIST, THUMB_IP);
        float thumbToIndex = dist(lm, THUMB_TIP, INDEX_TIP);
        return wristToTip > wristToIp * 1.04f && thumbToIndex > palm * 0.58f;
    }

    private float dist(List<NormalizedLandmark> lm, int a, int b) {
        float dx = x(lm, a) - x(lm, b);
        float dy = y(lm, a) - y(lm, b);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float x(List<NormalizedLandmark> lm, int index) { return lm.get(index).x(); }
    private float y(List<NormalizedLandmark> lm, int index) { return lm.get(index).y(); }
    private float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
