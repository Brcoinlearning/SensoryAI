package com.narc.arclient.camera.callback;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.process.processor.RecognizeProcessor;

import java.nio.ByteBuffer;

public class CameraImageAvailableListener implements ImageReader.OnImageAvailableListener {
    private static final String TAG = "CameraListener";

    // 缓存数组，避免每帧重复分配内存 (GC 杀手)
    private int[] argbArray;
    private byte[] yBufferBytes;
    private byte[] uBufferBytes;
    private byte[] vBufferBytes;

    @Override
    public void onImageAvailable(ImageReader reader) {
        // 1. 【繁忙丢帧】如果 AI 还没算完上一帧，这一帧直接扔掉
        // 这样可以避免积压，保证光标的实时性
        if (!RecognizeProcessor.getInstance().isReady()) {
            Image image = reader.acquireLatestImage();
            if (image != null) image.close();
            return;
        }

        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            long start = System.currentTimeMillis(); // ⏱️ 开始计时

            int width = image.getWidth();
            int height = image.getHeight();

            // 2. 【降维打击】设定缩放步长 (4)
            // 1920x1080 -> 480x270。这个尺寸对 AI 足够清晰，且速度快 16 倍。
            int step = 4;
            int smallW = width / step;
            int smallH = height / step;

            // 初始化缓存 (仅在第一帧或尺寸变化时执行)
            prepareBuffers(smallW, smallH, image);

            // 3. 【极速转换】YUV -> ARGB (纯数组操作)
            // 之前的版本在这里慢，这次我们修好了！
            fastYUVtoARGB(image, smallW, smallH, step);

            // 4. 创建 Bitmap
            // 从 int[] 创建 Bitmap 极快
            Bitmap bitmap = Bitmap.createBitmap(argbArray, smallW, smallH, Bitmap.Config.ARGB_8888);

            // ⏱️ 打印转换耗时：如果小于 20ms，说明问题解决了！
            long cost = System.currentTimeMillis() - start;
            // Log.d(TAG, "⚡️ YUV转Bitmap耗时: " + cost + "ms");

            // 5. 发送
            RecognizeProcessor.getInstance().process(new RecognizeTask(bitmap));

        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    // 初始化复用数组
    private void prepareBuffers(int smallW, int smallH, Image image) {
        if (argbArray == null || argbArray.length != smallW * smallH) {
            argbArray = new int[smallW * smallH];
        }

        // 预分配 YUV 数据拷贝缓存
        Image.Plane[] planes = image.getPlanes();
        int ySize = planes[0].getBuffer().remaining();
        int uSize = planes[1].getBuffer().remaining();
        int vSize = planes[2].getBuffer().remaining();

        if (yBufferBytes == null || yBufferBytes.length < ySize) {
            yBufferBytes = new byte[ySize];
        }
        if (uBufferBytes == null || uBufferBytes.length < uSize) {
            uBufferBytes = new byte[uSize];
        }
        if (vBufferBytes == null || vBufferBytes.length < vSize) {
            vBufferBytes = new byte[vSize];
        }
    }

    /**
     * 极速 YUV 转 ARGB (数组版)
     * 关键优化：先 bulk copy 到 byte[]，再循环访问
     */
    private void fastYUVtoARGB(Image image, int outW, int outH, int step) {
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // 1. 【批量拷贝】这是解决 2FPS 的关键！
        // JNI 批量拷贝非常快 (1080p 约 1-3ms)，比在循环里几十万次 get() 快得多。
        // get(byte[]) 会自动处理 position，所以每次都要 rewind 或者重新获取
        yBuffer.get(yBufferBytes, 0, yBuffer.remaining());
        uBuffer.get(uBufferBytes, 0, uBuffer.remaining());
        vBuffer.get(vBufferBytes, 0, vBuffer.remaining());

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // 2. 循环处理 (现在访问的是 Java 数组，速度起飞)
        int pIndex = 0;
        for (int y = 0; y < outH; y++) {
            int srcY = y * step;
            int yIdxOffset = srcY * yRowStride;
            int uvIdxOffset = (srcY / 2) * uvRowStride;

            for (int x = 0; x < outW; x++) {
                int srcX = x * step;

                // 读取 Y (从数组读)
                int Y = yBufferBytes[yIdxOffset + srcX] & 0xFF;

                // 读取 UV (从数组读)
                int uvIndex = uvIdxOffset + (srcX / 2) * uvPixelStride;

                // 边界保护 (防止个别机型 stride 对齐问题导致越界)
                if (uvIndex >= uBufferBytes.length) uvIndex = uBufferBytes.length - 1;

                int U = uBufferBytes[uvIndex] & 0xFF;
                // V 的索引逻辑和 U 一样，只是来源于 V Plane
                if (uvIndex >= vBufferBytes.length) uvIndex = vBufferBytes.length - 1;
                int V = vBufferBytes[uvIndex] & 0xFF;

                // YUV 转 RGB 公式 (整数运算优化)
                // U 和 V 在公式里通常要减 128
                int u = U - 128;
                int v = V - 128;

                // 使用位运算替代浮点乘法 (近似值，速度最快)
                int r = Y + ((351 * v) >> 8);
                int g = Y - ((179 * v + 86 * u) >> 8);
                int b = Y + ((443 * u) >> 8);

                // 钳制范围 0-255
                r = (r < 0) ? 0 : (r > 255) ? 255 : r;
                g = (g < 0) ? 0 : (g > 255) ? 255 : g;
                b = (b < 0) ? 0 : (b > 255) ? 255 : b;

                // 存入 int 数组
                argbArray[pIndex++] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }
}