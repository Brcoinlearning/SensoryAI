package com.narc.arclient.camera.callback;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.process.processor.RecognizeProcessor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class CameraImageAvailableListener implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "CameraListener";

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            // 获取最新一帧 (YUV 格式)
            image = reader.acquireLatestImage();
            if (image == null) return;

            // 1. 将 YUV_420_888 转为 NV21 byte数组
            byte[] nv21 = YUV_420_888toNV21(image);

            // 2. 将 NV21 转为 Bitmap
            // (虽然这里用了一次 compressToJpeg，但因为是在后台线程且是 YuvImage，比硬件 JPEG 管道要快且可控)
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
            byte[] imageBytes = out.toByteArray();

            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            // 3. 发送给识别处理器
            if (bitmap != null) {
                // 直接调用 RecognizeProcessor (或者用你的 ProcessorManager)
                RecognizeProcessor.getInstance().process(new RecognizeTask(bitmap));
            }

        } catch (Exception e) {
            Log.e(TAG, "Image process error: " + e.getMessage());
        } finally {
            // ⚠️ 极其重要：必须关闭 image，否则相机流会卡死
            if (image != null) {
                image.close();
            }
        }
    }

    // 工具方法：高效提取 YUV 数据
    private byte[] YUV_420_888toNV21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int rowStride = image.getPlanes()[0].getRowStride();
        int pixelStride = image.getPlanes()[0].getPixelStride(); // 通常为 1

        // 复制 Y 分量
        int pos = 0;
        if (rowStride == width) {
            yBuffer.get(nv21, 0, ySize);
            pos = ySize;
        } else {
            // 处理 stride 对齐问题
            for (int row = 0; row < height; row++) {
                yBuffer.position(row * rowStride);
                yBuffer.get(nv21, pos, width);
                pos += width;
            }
        }

        // 复制 UV 分量 (NV21 格式: V, U, V, U...)
        int vRowStride = image.getPlanes()[2].getRowStride();
        int vPixelStride = image.getPlanes()[2].getPixelStride();

        // 这是一个简化的转换，大部分设备适用
        // 如果想追求极致速度，可以使用 RenderScript 或 Native 代码，但 Java 层这样写通常足够 30FPS
        int uvHeight = height / 2;
        int uvWidth = width / 2;

        byte[] vBytes = new byte[vBuffer.remaining()];
        byte[] uBytes = new byte[uBuffer.remaining()];
        vBuffer.get(vBytes);
        uBuffer.get(uBytes);

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int vPos = row * vRowStride + col * vPixelStride;
                int uPos = row * vRowStride + col * vPixelStride;

                // NV21 顺序: Y...Y V U V U
                nv21[pos++] = vBytes[vPos];
                nv21[pos++] = uBytes[uPos];
            }
        }
        return nv21;
    }
}