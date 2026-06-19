package com.jlxc.wifitouchsender;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
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
        listener.onStatus("摄像头预览启动中：本版不会自动加载 MediaPipe，避免原生库崩溃导致看不到画面。", true);
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
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .build();
        handLandmarker = HandLandmarker.createFromOptions(context, options);
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
                int target = 640 * 480;
                int areaPenalty = Math.abs(area - target) / 1000;
                int aspectPenalty = Math.abs(w * 3 - h * 4); // prefer 4:3 because hands fill frame well
                int hugePenalty = area > 1280 * 720 ? 10000 : 0;
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

            imageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    if (!inferBusy.compareAndSet(false, true)) {
                        image.close();
                        return;
                    }
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
                                ? "摄像头预览已启动。确认有画面后，再手动点击‘加载模型/启用识别’。"
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
        try {
            if (!running || handLandmarker == null) return;
            Bitmap bitmap = imageToBitmap(image, getRotationDegrees());
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            HandLandmarkerResult result = handLandmarker.detect(mpImage);
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
            bitmap.recycle();
        } catch (Throwable e) {
            listener.onStatus("手势识别失败：" + safeMsg(e), false);
        } finally {
            try { image.close(); } catch (Exception ignored) {}
            inferBusy.set(false);
        }
    }

    private int getRotationDegrees() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = wm == null ? Surface.ROTATION_0 : wm.getDefaultDisplay().getRotation();
        int deviceDegrees = ORIENTATIONS.get(rotation);
        if (frontCamera) return (sensorOrientation + deviceDegrees) % 360;
        return (sensorOrientation - deviceDegrees + 360) % 360;
    }

    private Bitmap imageToBitmap(Image image, int rotateDegrees) {
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()), 72, out);
        byte[] jpeg = out.toByteArray();
        Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (src == null) throw new RuntimeException("bitmap decode failed");
        if (rotateDegrees == 0) return src;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotateDegrees);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        if (rotated != src) src.recycle();
        return rotated;
    }

    private byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] nv21 = new byte[ySize + uvSize * 2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        int yRowStride = image.getPlanes()[0].getRowStride();
        int yPixelStride = image.getPlanes()[0].getPixelStride();
        int uRowStride = image.getPlanes()[1].getRowStride();
        int uPixelStride = image.getPlanes()[1].getPixelStride();
        int vRowStride = image.getPlanes()[2].getRowStride();
        int vPixelStride = image.getPlanes()[2].getPixelStride();

        int pos = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride);
            }
        }
        int uvPos = ySize;
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                nv21[uvPos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
                nv21[uvPos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
            }
        }
        return nv21;
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
