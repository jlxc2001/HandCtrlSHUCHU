package com.jlxc.wifitouchsender;

import android.app.Application;

/**
 * Installs crash recording before MainActivity is created.
 * This catches crashes that happen during Activity field initialization or early UI creation.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
    }
}
