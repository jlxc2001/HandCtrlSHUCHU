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
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class HandGestureCameraController {
    public interface Listener {
        void onHandLandmarks(HandLandmarkerResult result);
        void onStatus(String text, boolean ok);
    }

    private static final String MODEL_FILE = "hand_landmarker.task";
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private final Context context;
    private final TextureView previewView;
    private final Listener listener;
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

    public HandGestureCameraController(Context context, TextureView previewView, Listener listener) {
        this.context = context.getApplicationContext();
        this.previewView = previewView;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onStatus("没有摄像头权限", false);
            return;
        }
        try {
            setupHandLandmarker();
        } catch (Exception e) {
            listener.onStatus("手部模型初始化失败：" + e.getMessage() + "。请确认 assets/hand_landmarker.task 已存在。", false);
            closeHandLandmarker();
            return;
        }
        startThreads();
        running = true;
        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    public void stop() {
        running = false;
        closeCamera();
        stopThreads();
        closeHandLandmarker();
        inferBusy.set(false);
    }

    private void setupHandLandmarker() {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
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
        } catch (Exception e) {
            listener.onStatus("打开摄像头失败：" + e.getMessage(), false);
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
        if (sizes == null || sizes.length == 0) return new Size(320, 240);
        List<Size> list = Arrays.asList(sizes);
        Size best = Collections.min(list, new Comparator<Size>() {
            @Override public int compare(Size a, Size b) {
                int da = Math.abs(a.getWidth() - 320) + Math.abs(a.getHeight() - 240);
                int db = Math.abs(b.getWidth() - 320) + Math.abs(b.getHeight() - 240);
                return da - db;
            }
        });
        int w = Math.max(160, Math.min(640, best.getWidth()));
        int h = Math.max(120, Math.min(480, best.getHeight()));
        return new Size(w, h);
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
                } catch (Exception e) {
                    if (image != null) image.close();
                    inferBusy.set(false);
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
                        listener.onStatus("手势识别已启动：半捏移动，捏合点击，L返回，五指HOME", true);
                    } catch (Exception e) {
                        listener.onStatus("启动预览失败：" + e.getMessage(), false);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {
                    listener.onStatus("摄像头会话配置失败", false);
                }
            }, cameraHandler);
        } catch (Exception e) {
            listener.onStatus("创建摄像头会话失败：" + e.getMessage(), false);
        }
    }

    private void analyzeImage(Image image) {
        try {
            if (!running || handLandmarker == null) return;
            Bitmap bitmap = imageToBitmap(image, getRotationDegrees());
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            HandLandmarkerResult result = handLandmarker.detect(mpImage);
            listener.onHandLandmarks(result);
        } catch (Exception e) {
            listener.onStatus("手势识别失败：" + e.getMessage(), false);
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
