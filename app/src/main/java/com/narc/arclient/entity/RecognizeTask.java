package com.narc.arclient.entity;

import android.graphics.Bitmap;
import java.util.concurrent.atomic.AtomicReference;

public class RecognizeTask {
    private Bitmap originBitmap;
    private String recognizeResult;
    private float tipX = -1;
    private float tipY = -1;

    // ✅ 静态引用：保存最新的高清 YUV 数据
    private static final AtomicReference<HighResYUVCache> latestYUVCache = new AtomicReference<>();

    /**
     * 高清 YUV 数据缓存
     */
    public static class HighResYUVCache {
        public byte[] yData;
        public byte[] uData;
        public byte[] vData;
        public int width;
        public int height;
        public int yRowStride;
        public int uvRowStride;
        public int uvPixelStride;
    }

    public RecognizeTask(Bitmap originBitmap) {
        this.originBitmap = originBitmap;
    }

    /**
     * 创建带有手指坐标的 RecognizeTask (用于智能裁剪)
     */
    public RecognizeTask(Bitmap originBitmap, float tipX, float tipY) {
        this.originBitmap = originBitmap;
        this.tipX = tipX;
        this.tipY = tipY;
    }

    public Bitmap getOriginBitmap() {
        return originBitmap;
    }

    public void setOriginBitmap(Bitmap originBitmap) {
        this.originBitmap = originBitmap;
    }

    public String getRecognizeResult() {
        return recognizeResult;
    }

    public void setRecognizeResult(String recognizeResult) {
        this.recognizeResult = recognizeResult;
    }

    /**
     * 获取手指坐标 (归一化坐标，范围 0-1)
     */
    public float getTipX() {
        return tipX;
    }

    public float getTipY() {
        return tipY;
    }

    public boolean hasTipCoordinate() {
        return tipX >= 0 && tipY >= 0;
    }

    public void setTipX(float tipX) {
        this.tipX = tipX;
    }

    public void setTipY(float tipY) {
        this.tipY = tipY;
    }

    // ✅ 静态方法：保存最新的高清 YUV 数据
    public static void setLatestHighResYUV(HighResYUVCache cache) {
        latestYUVCache.set(cache);
    }

    // ✅ 静态方法：获取最新的高清 YUV 数据
    public static HighResYUVCache getLatestHighResYUV() {
        return latestYUVCache.get();
    }
}
