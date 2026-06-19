package com.jlxc.wifitouchsender;

public interface GestureEventListener {
    void onHandLandmarks(float[] xy);
    void onNoHand();
    void onStatus(String text, boolean ok);
}
