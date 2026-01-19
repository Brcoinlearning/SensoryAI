package com.narc.arclient.entity;

import android.graphics.Bitmap;

/**
 * 识别任务包：单纯的数据载体，负责把图片从相机传给 AI
 */
public class RecognizeTask {

    // 核心数据：原始图片
    private Bitmap originBitmap;

    // ✅ 构造函数：必须接收 Bitmap
    public RecognizeTask(Bitmap originBitmap) {
        this.originBitmap = originBitmap;
    }

    // ✅ Getter 方法
    public Bitmap getOriginBitmap() {
        return originBitmap;
    }

    public void setOriginBitmap(Bitmap originBitmap) {
        this.originBitmap = originBitmap;
    }
}