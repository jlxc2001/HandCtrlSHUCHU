package com.jlxc.wifitouchsender;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CommandSender {
    public interface Callback {
        void onResult(String text, boolean ok);
    }

    public static class Status {
        public boolean ok;
        public boolean a11y;
        public boolean cursorShown;
        public int x;
        public int y;
        public int screenW;
        public int screenH;
        public String raw;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String host = "192.168.100.124";
    private int port = 47220;

    public void setTarget(String host, int port) {
        this.host = host == null ? "" : host.trim();
        this.port = port <= 0 ? 47220 : port;
    }

    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }

    public void move(float dx, float dy, float speed) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dx", trim(dx));
        p.put("dy", trim(dy));
        p.put("speed", trim(speed));
        postAsync("/api/move", p, null);
    }

    public void set(float x, float y) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("x", trim(x));
        p.put("y", trim(y));
        postAsync("/api/set", p, null);
    }

    public void scroll(float dy) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dy", trim(dy));
        postAsync("/api/scroll", p, null);
    }

    public void tap(Callback cb) { postAsync("/api/tap", new LinkedHashMap<>(), cb); }
    public void doubleTap(Callback cb) { postAsync("/api/doubletap", new LinkedHashMap<>(), cb); }
    public void longPress(Callback cb) { postAsync("/api/longpress", new LinkedHashMap<>(), cb); }
    public void back(Callback cb) { postAsync("/api/back", new LinkedHashMap<>(), cb); }
    public void home(Callback cb) { postAsync("/api/home", new LinkedHashMap<>(), cb); }
    public void recents(Callback cb) { postAsync("/api/recents", new LinkedHashMap<>(), cb); }

    public void status(StatusCallback cb) {
        executor.execute(() -> {
            try {
                String raw = request("GET", "/status", "");
                Status s = parseStatus(raw);
                mainHandler.post(() -> cb.onStatus(s, null));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onStatus(null, e));
            }
        });
    }

    public interface StatusCallback {
        void onStatus(Status status, Exception error);
    }

    private void postAsync(String path, Map<String, String> params, Callback cb) {
        executor.execute(() -> {
            boolean ok = true;
            String text;
            try {
                text = request("POST", path, encode(params));
                try {
                    JSONObject obj = new JSONObject(text);
                    ok = obj.optBoolean("ok", true);
                    int x = obj.optInt("x", -1);
                    int y = obj.optInt("y", -1);
                    boolean a11y = obj.optBoolean("a11y", false);
                    text = "x=" + x + " y=" + y + " 无障碍=" + a11y + (ok ? "" : " | " + obj.optString("msg"));
                } catch (Exception ignored) {
                    // keep raw text
                }
            } catch (Exception e) {
                ok = false;
                text = "发送失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            if (cb != null) {
                final boolean finalOk = ok;
                final String finalText = text;
                mainHandler.post(() -> cb.onResult(finalText, finalOk));
            }
        });
    }

    private String request(String method, String path, String body) throws Exception {
        URL url = new URL(getBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(500);
        conn.setReadTimeout(900);
        conn.setUseCaches(false);
        conn.setRequestProperty("Connection", "close");
        if ("POST".equals(method)) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.flush();
            out.close();
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String result = readAll(is);
        conn.disconnect();
        if (code < 200 || code >= 400) throw new RuntimeException("HTTP " + code + " " + result);
        return result;
    }

    private static String encode(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString().trim();
    }

    private static Status parseStatus(String raw) throws Exception {
        JSONObject obj = new JSONObject(raw);
        Status s = new Status();
        s.raw = raw;
        s.ok = obj.optBoolean("ok", false);
        s.a11y = obj.optBoolean("a11y", false);
        s.cursorShown = obj.optBoolean("cursorShown", false);
        s.x = obj.optInt("x", 0);
        s.y = obj.optInt("y", 0);
        s.screenW = obj.optInt("screenW", 2560);
        s.screenH = obj.optInt("screenH", 720);
        return s;
    }

    private static String trim(float v) {
        if (Math.abs(v) < 0.001f) return "0";
        return String.format(java.util.Locale.US, "%.3f", v);
    }
}
