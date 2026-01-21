package com.narc.arclient.camera.callback;

import static android.content.ContentValues.TAG;
import static android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.util.Log;

import androidx.annotation.NonNull;

import com.narc.arclient.camera.ICameraManager;
import com.narc.arclient.process.ProcessorManager;

import java.util.Arrays;

public class CameraStateCallback extends CameraDevice.StateCallback {

    @Override
    public void onOpened(@NonNull CameraDevice camera) {
        ICameraManager iCameraManager = ICameraManager.getInstance();
        iCameraManager.setCameraDevice(camera);

        try {
            CaptureRequest.Builder captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            // 设置控制模式为自动
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            // 设置自动对焦模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // 设置自动曝光模式
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // 设置自动白平衡
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            // 设置图像稳定模式（如果支持）
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);

            // 设置降噪模式
            captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);

            // 设置对比度和锐度（通过色彩校正）
            captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST);

            // 设置曝光补偿以增强文字对比度
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 1);

            // 启用防抖
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);

            captureRequestBuilder.addTarget(iCameraManager.getImageReader().getSurface());
            iCameraManager.setCaptureRequestBuilder(captureRequestBuilder);

            SessionConfiguration sessionConfiguration = new SessionConfiguration(SESSION_REGULAR,
                    Arrays.asList(new OutputConfiguration(iCameraManager.getImageReader().getSurface())),
                    ProcessorManager.normalExecutor, new CameraCaptureStateCallback());
            camera.createCaptureSession(sessionConfiguration);
        } catch (CameraAccessException e) {
            Log.e(TAG, e.toString());
        }
    }

    @Override
    public void onDisconnected(@NonNull CameraDevice camera) {
        Log.e(TAG, "camera disconnected");
    }

    @Override
    public void onError(@NonNull CameraDevice camera, int error) {
        Log.e(TAG, "camera error");
    }
}
