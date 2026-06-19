package com.jlxc.wifitouchsender;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class HandGestureCameraController implements GestureController {

    private static final String MODEL_FILE = "models/hand_landmarker.task";
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private final Context context;
    private final TextureView previewView;
    private final GestureEventListener listener;
    private final ExecutorService inferExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferBusy = new AtomicBoolean(false);

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandLandmarker handLandmarker;
    private String cameraId;
    private int sensorOrientation = 0;
    private boolean frontCamera = true;
    private boolean running = false;
    private Size selectedSize = new Size(320, 240);
    private long lastInferStartMs = 0L;
    private long lastMetricMs = 0L;
    private int inferFrameCount = 0;
    private long lastInferCostMs = 0L;
    private long lastFrameDropCount = 0L;
    private boolean fastImagePathOk = true;
    private static final long MIN_INFER_INTERVAL_MS = 55L; // v5.7: 避免推理超过帧间隔时持续抢占 CPU

    public HandGestureCameraController(Context context, TextureView previewView, GestureEventListener listener) {
        this.context = context.getApplicationContext();
        this.previewView = previewView;
        this.listener = listener;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        if (running) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onStatus("没有摄像头权限", false);
            return;
        }
        startThreads();
        running = true;
        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
        listener.onStatus("摄像头预览启动中：v5.7 会先显示预览，模型加载后使用 VIDEO 跟踪模式，并叠加骨骼/耗时。", true);
    }

    @Override
    public void loadModel() {
        if (handLandmarker != null) {
            listener.onStatus("手部模型已经加载", true);
            return;
        }
        // 注意：当前用户设备上 MediaPipe createFromOptions 发生 native SIGSEGV，
        // Java try/catch 无法拦截。此方法只在用户手动点击“加载模型”时调用，
        // 先保证摄像头预览能够单独工作。
        setupHandLandmarker();
        listener.onStatus("手部模型已加载：开始识别手势", true);
    }

    @Override
    public void stop() {
        running = false;
        closeCamera();
        stopThreads();
        closeHandLandmarker();
        inferBusy.set(false);
    }

    private void setupHandLandmarker() {
        // v5.5: 不再使用 setModelAssetPath()，也不再使用 openFd。
        // 先用 AssetManager.open 把 APK assets 里的模型读进 Direct ByteBuffer，
        // 避免 compressed asset / openFd 限制导致模型读取失败。
        ByteBuffer modelBuffer;
        try {
            modelBuffer = loadModelBufferFromAssets();
        } catch (IOException e) {
            throw new RuntimeException(buildAssetMissingMessage(e), e);
        }

        BaseOptions baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetBuffer(modelBuffer)
                .build();
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .build();
        handLandmarker = HandLandmarker.createFromOptions(context, options);
        lastMetricMs = android.os.SystemClock.uptimeMillis();
        inferFrameCount = 0;
        lastFrameDropCount = 0;
    }

    private ByteBuffer loadModelBufferFromAssets() throws IOException {
        // v5.5：不用 openFd，直接用 AssetManager.open 读取。
        // 这样即使 .task 被 APK 压缩，也能读取，避免 openFd 对压缩 asset 的限制。
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 * 1024 * 1024);
        byte[] temp = new byte[64 * 1024];
        int n;
        try (InputStream in = context.getAssets().open(MODEL_FILE)) {
            while ((n = in.read(temp)) > 0) {
                bos.write(temp, 0, n);
            }
        }
        byte[] bytes = bos.toByteArray();
        if (bytes.length < 1024 * 1024) {
            throw new IOException("模型文件过小，疑似不是有效 hand_landmarker.task，size=" + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.rewind();
        return buffer;
    }

    private String buildAssetMissingMessage(IOException e) {
        String root;
        String models;
        try {
            String[] r = context.getAssets().list("");
            root = r == null ? "null" : Arrays.toString(r);
        } catch (Exception ex) {
            root = "读取失败: " + ex.getMessage();
        }
        try {
            String[] m = context.getAssets().list("models");
            models = m == null ? "null" : Arrays.toString(m);
        } catch (Exception ex) {
            models = "读取失败: " + ex.getMessage();
        }
        return "无法从 APK assets 读取模型 assets/" + MODEL_FILE
                + "。当前 APK assets 根目录=" + root
                + "，assets/models=" + models
                + "。请确认打包前存在 app/src/main/assets/models/hand_landmarker.task，"
                + "或使用 GitHub Actions 里的 Download MediaPipe hand model 步骤构建。原始错误: " + e.getMessage();
    }

    private void closeHandLandmarker() {
        try {
            if (handLandmarker != null) handLandmarker.close();
        } catch (Exception ignored) {}
        handLandmarker = null;
    }

    private void startThreads() {
        cameraThread = new HandlerThread("hand-camera-thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopThreads() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(700); } catch (InterruptedException ignored) {}
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                listener.onStatus("无法获取 CameraManager", false);
                return;
            }
            selectCamera(manager);
            if (cameraId == null) {
                listener.onStatus("没有找到可用摄像头", false);
                return;
            }
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, stateCallback, cameraHandler);
            listener.onStatus("手势摄像头启动中...", true);
        } catch (Throwable e) {
            listener.onStatus("打开摄像头失败：" + safeMsg(e), false);
        }
    }

    private void selectCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                cameraId = id;
                frontCamera = true;
                configureFromCharacteristics(c);
                return;
            }
        }
        cameraId = fallback;
        if (cameraId != null) {
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            frontCamera = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            configureFromCharacteristics(c);
        }
    }

    private void configureFromCharacteristics(CameraCharacteristics c) {
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orientation == null ? 0 : orientation;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            selectedSize = chooseSmallSize(sizes);
        }
    }

    private Size chooseSmallSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        List<Size> list = Arrays.asList(sizes);
        // IMPORTANT: return an actual supported Camera2 output size.
        // The previous version clamped width/height after picking a size; on some camera HALs
        // that creates an unsupported Surface size and can crash when configuring the session.
        return Collections.min(list, new Comparator<Size>() {
            @Override public int compare(Size a, Size b) {
                return score(a) - score(b);
            }
            private int score(Size s) {
                int w = s.getWidth();
                int h = s.getHeight();
                int area = w * h;
                int target = 320 * 240;
                int areaPenalty = Math.abs(area - target) / 700;
                int aspectPenalty = Math.abs(w * 3 - h * 4); // prefer 4:3 because hands fill frame well
                int hugePenalty = area > 640 * 480 ? 12000 : 0;
                int tinyPenalty = area < 320 * 240 ? 5000 : 0;
                return areaPenalty + aspectPenalty + hugePenalty + tinyPenalty;
            }
        });
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (running) openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createSession();
        }
        @Override public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            listener.onStatus("摄像头已断开", false);
        }
        @Override public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            listener.onStatus("摄像头错误：" + error, false);
        }
    };

    private void createSession() {
        try {
            if (cameraDevice == null || !previewView.isAvailable()) return;
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null) return;
            texture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
            Surface previewSurface = new Surface(texture);

            imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    if (handLandmarker == null) {
                        image.close();
                        return;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastInferStartMs < MIN_INFER_INTERVAL_MS) {
                        lastFrameDropCount++;
                        image.close();
                        return;
                    }
                    if (!inferBusy.compareAndSet(false, true)) {
                        lastFrameDropCount++;
                        image.close();
                        return;
                    }
                    lastInferStartMs = now;
                    final Image img = image;
                    image = null;
                    inferExecutor.execute(() -> analyzeImage(img));
                } catch (Throwable e) {
                    if (image != null) image.close();
                    inferBusy.set(false);
                    listener.onStatus("读取摄像头帧失败：" + safeMsg(e), false);
                }
            }, cameraHandler);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        listener.onStatus(handLandmarker == null
                                ? "摄像头预览已启动，分析尺寸 " + selectedSize.getWidth() + "×" + selectedSize.getHeight() + "。确认有画面后，再手动点击‘加载模型’。"
                                : "手势识别已启动：半捏移动，捏合点击，L返回，五指HOME", true);
                    } catch (Throwable e) {
                        listener.onStatus("启动预览失败：" + safeMsg(e), false);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    listener.onStatus("摄像头会话配置失败", false);
                }
            }, cameraHandler);
        } catch (Throwable e) {
            listener.onStatus("创建摄像头会话失败：" + safeMsg(e), false);
        }
    }

    private void analyzeImage(Image image) {
        long start = android.os.SystemClock.uptimeMillis();
        Bitmap fallbackBitmap = null;
        try {
            if (!running || handLandmarker == null) return;
            HandLandmarkerResult result;
            if (fastImagePathOk) {
                try {
                    result = detectWithMediaImageBuilder(image, getRotationDegrees());
                } catch (Throwable fastError) {
                    fastImagePathOk = false;
                    fallbackBitmap = imageToBitmap(image, getRotationDegrees());
                    MPImage mpImage = new BitmapImageBuilder(fallbackBitmap).build();
                    result = handLandmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis());
                }
            } else {
                fallbackBitmap = imageToBitmap(image, getRotationDegrees());
                MPImage mpImage = new BitmapImageBuilder(fallbackBitmap).build();
                result = handLandmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis());
            }

            if (result != null && !result.landmarks().isEmpty()) {
                List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> hand = result.landmarks().get(0);
                float[] xy = new float[42];
                for (int i = 0; i < 21 && i < hand.size(); i++) {
                    xy[i * 2] = hand.get(i).x();
                    xy[i * 2 + 1] = hand.get(i).y();
                }
                listener.onHandLandmarks(xy);
            } else {
                listener.onNoHand();
            }
        } catch (Throwable e) {
            listener.onStatus("手势识别失败：" + safeMsg(e), false);
        } finally {
            lastInferCostMs = android.os.SystemClock.uptimeMillis() - start;
            inferFrameCount++;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastMetricMs >= 500) {
                float fps = inferFrameCount * 1000f / Math.max(1, now - lastMetricMs);
                listener.onMetrics(String.format(java.util.Locale.US,
                        "识别FPS %.1f | 推理 %dms | 分析 %dx%d | 丢帧=%d | 图像路径=%s",
                        fps, lastInferCostMs, selectedSize.getWidth(), selectedSize.getHeight(),
                        lastFrameDropCount, fastImagePathOk ? "MediaImage" : "Bitmap/YUV"));
                inferFrameCount = 0;
                lastFrameDropCount = 0;
                lastMetricMs = now;
            }
            try { if (fallbackBitmap != null) fallbackBitmap.recycle(); } catch (Exception ignored) {}
            try { image.close(); } catch (Exception ignored) {}
            inferBusy.set(false);
        }
    }

    private HandLandmarkerResult detectWithMediaImageBuilder(Image image, int rotateDegrees) throws Exception {
        // 通过反射使用 MediaImageBuilder，避免硬依赖某个 MediaPipe 版本的编译期 API。
        // 成功时可绕开 YUV->JPEG->Bitmap，延迟明显低于旧路径。
        Class<?> cls = Class.forName("com.google.mediapipe.framework.image.MediaImageBuilder");
        Object builder = cls.getConstructor(Image.class).newInstance(image);
        try {
            cls.getMethod("setRotation", int.class).invoke(builder, rotateDegrees);
        } catch (NoSuchMethodException ignored) {
            // 老版本如果没有 setRotation，就直接使用原图方向。
        }
        MPImage mpImage = (MPImage) cls.getMethod("build").invoke(builder);
        return handLandmarker.detectForVideo(mpImage, android.os.SystemClock.uptimeMillis());
    }

    private int getRotationDegrees() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = wm == null ? Surface.ROTATION_0 : wm.getDefaultDisplay().getRotation();
        int deviceDegrees = ORIENTATIONS.get(rotation);
        if (frontCamera) return (sensorOrientation + deviceDegrees) % 360;
        return (sensorOrientation - deviceDegrees + 360) % 360;
    }

    private Bitmap imageToBitmap(Image image, int rotateDegrees) {
        // v5.7：不再走 YUV -> JPEG -> Bitmap。JPEG 编码/解码在部分机型上非常慢。
        // 这里直接从 YUV_420_888 采样成 ARGB_8888 Bitmap，减少一大段无意义转换。
        Bitmap src = yuv420ToBitmapFast(image);
        if (rotateDegrees == 0) return src;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotateDegrees);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        if (rotated != src) src.recycle();
        return rotated;
    }

    private Bitmap yuv420ToBitmapFast(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] out = new int[width * height];

        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer yBuf = yPlane.getBuffer();
        ByteBuffer uBuf = uPlane.getBuffer();
        ByteBuffer vBuf = vPlane.getBuffer();
        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int uRowStride = uPlane.getRowStride();
        int uPixelStride = uPlane.getPixelStride();
        int vRowStride = vPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();

        int pos = 0;
        for (int y = 0; y < height; y++) {
            int yRow = y * yRowStride;
            int uvRow = (y >> 1);
            for (int x = 0; x < width; x++) {
                int yValue = yBuf.get(yRow + x * yPixelStride) & 0xff;
                int uvCol = (x >> 1);
                int uValue = uBuf.get(uvRow * uRowStride + uvCol * uPixelStride) & 0xff;
                int vValue = vBuf.get(uvRow * vRowStride + uvCol * vPixelStride) & 0xff;

                int c = yValue - 16;
                int d = uValue - 128;
                int e = vValue - 128;
                if (c < 0) c = 0;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                out[pos++] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(out, 0, width, 0, 0, width, height);
        return bmp;
    }

    private static String safeMsg(Throwable e) {
        if (e == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) sb.append(" <- ");
            String msg = cur.getMessage();
            sb.append(cur.getClass().getSimpleName());
            if (msg != null && msg.length() > 0) sb.append(": ").append(msg);
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private void closeCamera() {
        try {
            if (captureSession != null) captureSession.close();
        } catch (Exception ignored) {}
        captureSession = null;
        try {
            if (cameraDevice != null) cameraDevice.close();
        } catch (Exception ignored) {}
        cameraDevice = null;
        try {
            if (imageReader != null) imageReader.close();
        } catch (Exception ignored) {}
        imageReader = null;
    }
}
