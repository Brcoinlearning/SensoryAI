package com.narc.arclient.process;

import android.graphics.Bitmap;
import android.util.Log;
import com.narc.arclient.network.WebSocketManager;
import java.io.ByteArrayOutputStream;

/**
 * 图片流式传输处理器
 * 
 * 功能：
 * - 将 Bitmap 转换为 JPEG 字节流
 * - 按照指定块大小分割图片数据
 * - 通过 WebSocket 流式推送到服务器
 * - 管理推流的生命周期
 */
public class ImageStreamProcessor {
    private static final String TAG = "ImageStreamProcessor";

    // 图片分块大小：200KB（平衡延迟和吞吐量）
    private static final int CHUNK_SIZE = 200 * 1024;

    // JPEG 压缩质量：70%（平衡质量和速度）
    private static final int JPEG_QUALITY = 70;

    private String sessionId;
    private boolean isStreaming = false;

    public ImageStreamProcessor() {
        this.sessionId = WebSocketManager.getInstance().getCurrentSessionId();
    }

    /**
     * 开始流式推送图片
     * 
     * @param bitmap     原始 Bitmap 图片
     * @param onProgress 进度回调
     * @param onComplete 完成回调
     * @param onError    错误回调
     */
    public void startStreaming(Bitmap bitmap,
            ProgressCallback onProgress,
            CompleteCallback onComplete,
            ErrorCallback onError) {
        if (isStreaming) {
            Log.w(TAG, "已有流式传输在进行中");
            return;
        }

        new Thread(() -> {
            try {
                isStreaming = true;
                long startTime = System.currentTimeMillis();
                Log.d(TAG, "🎬 开始流式推送图片");
                Log.d(TAG, "   Bitmap 尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());

                // 步骤1：压缩图片到 JPEG
                long compressStart = System.currentTimeMillis();
                Log.d(TAG, "   📦 步骤1: 压缩图片");
                byte[] imageData = compressBitmap(bitmap);
                long compressEnd = System.currentTimeMillis();
                Log.d(TAG, "   ✅ 压缩完成，耗时: " + (compressEnd - compressStart) + "ms");
                Log.d(TAG, "   📊 压缩后大小: " + (imageData.length / 1024) + "KB");

                if (imageData.length == 0) {
                    throw new Exception("图片压缩失败");
                }

                // 步骤2：直接推送完整图片数据
                long uploadStart = System.currentTimeMillis();
                Log.d(TAG, "   📡 步骤2: 推送图片数据到 /ws/image_stream");

                // 直接发送完整图片，后端自动处理识别
                WebSocketManager.getInstance().sendImageData(imageData, imageData.length);

                long uploadEnd = System.currentTimeMillis();

                if (onProgress != null) {
                    onProgress.onProgress(100, 1, 1);
                }

                Log.d(TAG, "   📤 已发送 (100%), 大小: " + (imageData.length / 1024) + "KB");

                long totalTime = uploadEnd - startTime;

                Log.d(TAG, "✅ 流式推送完成");
                Log.d(TAG, "   总耗时: " + totalTime + "ms (" + (totalTime / 1000.0) + "s)");
                Log.d(TAG,
                        "   吞吐量: " + String.format("%.2f", imageData.length / 1024.0 / (uploadEnd - uploadStart) * 1000)
                                + " KB/s");

                isStreaming = false;
                if (onComplete != null) {
                    onComplete.onComplete();
                }

            } catch (Exception e) {
                isStreaming = false;
                Log.e(TAG, "❌ 流式推送失败", e);
                if (onError != null) {
                    onError.onError(e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 停止流式推送
     */
    public void stopStreaming() {
        isStreaming = false;
        Log.d(TAG, "⏹️ 已停止流式推送");
    }

    /**
     * 将 Bitmap 压缩为 JPEG 字节流
     */
    private byte[] compressBitmap(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
        return stream.toByteArray();
    }

    /**
     * 获取推流状态
     */
    public boolean isStreaming() {
        return isStreaming;
    }

    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(int percent, int currentChunk, int totalChunks);
    }

    /**
     * 完成回调接口
     */
    public interface CompleteCallback {
        void onComplete();
    }

    /**
     * 错误回调接口
     */
    public interface ErrorCallback {
        void onError(String message);
    }
}
