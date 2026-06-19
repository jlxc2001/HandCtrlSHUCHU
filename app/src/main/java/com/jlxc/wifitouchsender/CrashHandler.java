package com.jlxc.wifitouchsender;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashHandler {
    private static final String FILE_NAME = "last_crash.txt";
    private static boolean installed = false;

    private CrashHandler() {}

    public static synchronized void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(app, throwable);
            if (oldHandler != null) {
                oldHandler.uncaughtException(thread, throwable);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    public static String readLastCrash(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) return "";
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) Math.min(file.length(), 6000)];
            int len = in.read(data);
            in.close();
            if (len <= 0) return "";
            return new String(data, 0, len, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void writeCrash(Context context, Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            pw.println("Thread: " + Thread.currentThread().getName());
            throwable.printStackTrace(pw);
            pw.flush();
            byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME), false);
            out.write(bytes);
            out.flush();
            out.close();
        } catch (Throwable ignored) {}
    }
}
