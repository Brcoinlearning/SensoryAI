package com.narc.arclient.camera.callback;

import static android.content.ContentValues.TAG;
import static com.narc.arclient.enums.CameraEnums.FPS;
import static com.narc.arclient.enums.CameraEnums.LOW_FPS;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.os.BatteryManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.narc.arclient.camera.ICameraManager;
import com.narc.arclient.process.ProcessorManager;
import com.narc.arclient.process.processor.RecognizeProcessor;

import java.util.concurrent.TimeUnit;

public class CameraCaptureStateCallback extends CameraCaptureSession.StateCallback {

    private static volatile boolean isHighFpsMode = false; // 当前是否为高帧率模式
    private static long lastGestureTime = 0; // 上次检测到手势的时间
    private static final long HIGH_FPS_TIMEOUT = 3000; // 3秒无手势后切换回低帧率

    /**
     * 由 RecognizeProcessor 在检测到手势时调用
     */
    public static void notifyGestureDetected() {
        lastGestureTime = System.currentTimeMillis();
        if (!isHighFpsMode) {
            isHighFpsMode = true;
            Log.i(TAG, "⚡ 切换到高帧率模式 (" + FPS + " FPS)");
        }
    }

    /**
     * 获取当前应该使用的帧率
     */
    private static int getCurrentFps() {
        ICameraManager cameraManager = ICameraManager.getInstance();
        if (cameraManager != null && cameraManager.isVideoRecording()) {
            return FPS;
        }
        // 检查是否应该切换回低帧率
        if (isHighFpsMode && (System.currentTimeMillis() - lastGestureTime > HIGH_FPS_TIMEOUT)) {
            isHighFpsMode = false;
            Log.i(TAG, "🔋 切换到低帧率模式 (" + LOW_FPS + " FPS) - 省电模式");
        }
        return isHighFpsMode ? FPS : LOW_FPS;
    }

    @Override
    public void onConfigured(@NonNull CameraCaptureSession session) {
        // 初始使用低帧率
        scheduleNextCapture(session);
    }

    private void scheduleNextCapture(CameraCaptureSession session) {
        int currentFps = getCurrentFps();
        int capturePeriod = 1000 / currentFps;

        ProcessorManager.scheduledExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    session.captureSingleRequest(ICameraManager.getInstance().getCaptureRequestBuilder().build(),
                            ProcessorManager.normalExecutor, new CameraCaptureCallback());
                } catch (CameraAccessException e) {
                    Log.e(TAG, e.toString());
                }
                // 递归调度下一帧，每次都重新计算帧率
                scheduleNextCapture(session);
            }
        }, capturePeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
        Log.e(TAG, "camera capture configure fail");
    }
}
