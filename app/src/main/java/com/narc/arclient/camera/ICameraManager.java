package com.narc.arclient.camera;

import static android.content.ContentValues.TAG;
import static android.content.Context.CAMERA_SERVICE;
import static com.narc.arclient.enums.CameraEnums.CAMERA_PERMISSION_REQUEST_CODE;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread; // ✅ 新增：后台线程
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.narc.arclient.MainActivity;
import com.narc.arclient.camera.callback.CameraImageAvailableListener;
import com.narc.arclient.camera.callback.CameraStateCallback;
import com.narc.arclient.enums.CameraEnums;
import com.narc.arclient.process.ProcessorManager;

import java.util.Arrays;
import java.util.Comparator;

public class ICameraManager {
    private static volatile ICameraManager iCameraManager;

    private MainActivity mainActivity;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader imageReader;

    // 👇👇👇 核心优化：后台线程 Handler 👇👇👇
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private ICameraManager(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
        startBackgroundThread(); // 启动后台线程
    }

    public static void init(MainActivity mainActivity) {
        if (iCameraManager == null) {
            iCameraManager = new ICameraManager(mainActivity);
        }
        iCameraManager.checkCameraPermission();
    }

    // 启动后台线程，防止卡死 UI
    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    // (可选) 停止线程，通常在 onDestroy 调用，这里暂且省略

    public void checkCameraPermission() {
        if (ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            ActivityCompat.requestPermissions(mainActivity, new String[]{Manifest.permission.CAMERA}, CameraEnums.CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    private void openCamera() {
        CameraManager cameraManager = (CameraManager) mainActivity.getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0]; // 默认后置
            if (ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // 👇👇👇 必须切换为 YUV_420_888 格式 (视频专用) 👇👇👇
            // JPEG 是拍照用的，处理速度极慢。YUV 是原始数据，速度极快。
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);

            // 智能选择 1080p 分辨率
            int width = 1920;
            int height = 1080;
            if (sizes != null) {
                // 简单逻辑：找最接近 1920x1080 的
                Arrays.sort(sizes, (o1, o2) -> Long.compare((long) o2.getWidth() * o2.getHeight(), (long) o1.getWidth() * o1.getHeight()));
                for (Size size : sizes) {
                    // 只要宽度在 1280 到 1920 之间都行
                    if (size.getWidth() <= 1920 && size.getWidth() >= 1280) {
                        width = size.getWidth();
                        height = size.getHeight();
                        break;
                    }
                }
            }
            Log.i(TAG, "📷 相机模式: YUV_420_888 | 分辨率: " + width + "x" + height);

            // maxImages=2 减少延迟
            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);

            // 👇👇👇 关键修改：使用 backgroundHandler 👇👇👇
            // 绝对不能用 MainLooper，否则 UI 会卡死！
            imageReader.setOnImageAvailableListener(new CameraImageAvailableListener(), backgroundHandler);

            cameraManager.openCamera(cameraId, ProcessorManager.normalExecutor, new CameraStateCallback());
        } catch (CameraAccessException e) {
            Log.e(TAG, e.toString());
        }
    }

    // ... 其他 getter/setter 和权限回调保持不变 ...

    public void permissionResultCallback(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Log.e(TAG, "camera permission denied");
            }
        }
    }
    public void setCameraDevice(CameraDevice cameraDevice) { this.cameraDevice = cameraDevice; }
    public CaptureRequest.Builder getCaptureRequestBuilder() { return captureRequestBuilder; }
    public void setCaptureRequestBuilder(CaptureRequest.Builder captureRequestBuilder) { this.captureRequestBuilder = captureRequestBuilder; }
    public AppCompatActivity getMainActivity() { return mainActivity; }
    public ImageReader getImageReader() { return imageReader; }
    public static ICameraManager getInstance() { return iCameraManager; }
}