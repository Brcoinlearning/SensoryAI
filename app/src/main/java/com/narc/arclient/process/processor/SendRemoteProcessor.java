package com.narc.arclient.process.processor;

import android.graphics.Bitmap;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.network.RetrofitClient;
import com.narc.arclient.network.WebSocketManager;
import com.narc.arclient.process.ImageStreamProcessor;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Response;

public class SendRemoteProcessor {
    private static final String TAG = "SendRemote";
    private static final Gson gson = new Gson();
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // ⚙️ 传输方式控制标志
    // true: 使用新的 WebSocket 流式传输（推荐）
    // false: 使用旧的 HTTP multipart 上传（保留备用）
    private static final boolean USE_WEBSOCKET_STREAMING = true;

    /**
     * 格式化时间戳
     */
    private String formatTime(long timestamp) {
        return timeFormat.format(new Date(timestamp));
    }

    // 这个方法会被 ProcessorManager 在后台线程调用
    /**
     * 以手指坐标为中心进行智能裁剪
     * 
     * @param bitmap 原始 bitmap
     * @param tipX   手指坐标 X (归一化, 0-1)
     * @param tipY   手指坐标 Y (归一化, 0-1)
     * @return 裁剪后的 bitmap
     */
    private Bitmap smartCropAroundTip(Bitmap bitmap, float tipX, float tipY) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // 🎯 裁剪区域大小：取原始尺寸的 60%，最小 400px，最大不超过原尺寸
        int cropSize = (int) (Math.max(width, height) * 0.6f);
        cropSize = Math.max(cropSize, 400); // 最小 400px
        cropSize = Math.min(cropSize, Math.min(width, height)); // 不超过最小边

        // 计算裁剪起点（以手指为中心）
        int cropCenterX = (int) (tipX * width);
        int cropCenterY = (int) (tipY * height);

        int cropStartX = Math.max(0, cropCenterX - cropSize / 2);
        int cropStartY = Math.max(0, cropCenterY - cropSize / 2);

        // 确保裁剪区域不超出图片边界
        if (cropStartX + cropSize > width) {
            cropStartX = width - cropSize;
        }
        if (cropStartY + cropSize > height) {
            cropStartY = height - cropSize;
        }

        Log.d(TAG, "✂️ 裁剪参数 - 手指位置:(" + String.format("%.2f", tipX) + "," + String.format("%.2f", tipY) + "), " +
                "裁剪起点:(" + cropStartX + "," + cropStartY + "), 裁剪大小:" + cropSize + "x" + cropSize);

        // 执行裁剪
        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropStartX, cropStartY, cropSize, cropSize);

        // 如果原 bitmap 过大且裁剪后不是它，回收原 bitmap 以节省内存
        if (croppedBitmap != bitmap && bitmap.getWidth() > 1000) {
            bitmap.recycle();
        }

        return croppedBitmap;
    }

    public RecognizeTask process(RecognizeTask task) {
        // 最简单的日志，确保能输出
        System.err.println(">>> SendRemoteProcessor.process() START");
        long startTime = System.currentTimeMillis();
        System.err.println(">>> startTime: " + startTime);

        try {
            Log.e(TAG, "================================================================================");
            Log.e(TAG, "⏱️ [开始] process() 执行");
            Log.e(TAG, "   时间戳: " + startTime);
            Log.e(TAG, "   传输方式: " + (USE_WEBSOCKET_STREAMING ? "WebSocket 流式" : "HTTP Multipart"));
            Log.e(TAG, "================================================================================");
        } catch (Exception e) {
            System.err.println(">>> 日志输出失败: " + e.getMessage());
        }

        if (task == null) {
            System.err.println(">>> task is null");
            Log.e(TAG, "❌ Task 为 null");
            return task;
        }

        if (task.getOriginBitmap() == null) {
            System.err.println(">>> originBitmap is null");
            Log.e(TAG, "❌ OriginBitmap 为 null");
            return task;
        }

        System.err.println(">>> task and bitmap OK");

        // 使用不同的传输方式
        if (USE_WEBSOCKET_STREAMING) {
            return processWithWebSocketStreaming(task, startTime);
        } else {
            return processWithHttpMultipart(task, startTime);
        }
    }

    /**
     * WebSocket 流式传输方式（新方式）
     */
    private RecognizeTask processWithWebSocketStreaming(RecognizeTask task, long startTime) {
        Log.e(TAG, "\n🔵 开始 WebSocket 流式传输流程");

        try {
            Bitmap bmp = task.getOriginBitmap();
            Log.e(TAG, "⏱️ [步骤1] 获取Bitmap");
            Log.e(TAG, "   尺寸: " + bmp.getWidth() + "x" + bmp.getHeight());

            // 确保 WebSocket 已连接
            if (!ensureWebSocketConnected()) {
                task.setRecognizeResult("WebSocket 连接失败");
                return task;
            }

            // 创建流式处理器并启动推送
            ImageStreamProcessor processor = new ImageStreamProcessor();

            // 使用同步方式等待推送完成
            final Object lock = new Object();
            final String[] result = { null };
            final boolean[] completed = { false };
            final long[] responseTime = { 0 }; // 记录收到响应的时间

            processor.startStreaming(
                    bmp,
                    (percent, currentChunk, totalChunks) -> {
                        Log.d(TAG, "   📊 进度: " + percent + "% (" + currentChunk + "/" + totalChunks + ")");
                    },
                    () -> {
                        Log.e(TAG, "✅ 图片推送完成");
                        long uploadEnd = System.currentTimeMillis();
                        long uploadTime = uploadEnd - startTime;
                        Log.e(TAG, "   发送耗时: " + uploadTime + "ms (" + (uploadTime / 1000.0) + "s)");
                        // 标记完成，唤醒等待线程，避免误报超时
                        synchronized (lock) {
                            completed[0] = true;
                            lock.notifyAll();
                        }
                    },
                    (errorMsg) -> {
                        Log.e(TAG, "❌ 推送失败: " + errorMsg);
                        task.setRecognizeResult("图片推送失败: " + errorMsg);
                        synchronized (lock) {
                            completed[0] = true;
                            lock.notifyAll();
                        }
                    });

            // 等待推送完成（超时30秒）
            synchronized (lock) {
                if (!completed[0]) {
                    lock.wait(30000);
                }
            }

            if (!completed[0]) {
                processor.stopStreaming();
                task.setRecognizeResult("图片推送超时");
                return task;
            }

            // ⏰ 这里后端已经收到图片，现在要等待处理结果
            // 但是目前的 WebSocket 回调机制会在主线程异步触发
            // 需要等待后端返回结果或超时（最多等待 30 秒）
            Log.e(TAG, "\n⏳ 已推送图片，等待后端处理结果...");

            long uploadCompleteTime = System.currentTimeMillis();
            long uploadDuration = uploadCompleteTime - startTime;
            Log.e(TAG, "   📤 推送完成耗时: " + uploadDuration + "ms");

            // 设置成功状态，等待后端通过 WebSocket 返回结果
            // 这里暂时返回 pending 状态，实际结果会通过 WebSocket 消息回调
            task.setRecognizeResult("图片已成功推送，等待服务器处理...");

            // ⏱️ 完整的端到端时间（包括后端处理时间）
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            Log.e(TAG, "\n================================================================================");
            Log.e(TAG, "⏱️ [完成] WebSocket 流式传输");
            Log.e(TAG, "   推送耗时: " + uploadDuration + "ms");
            Log.e(TAG, "   端到端耗时: " + totalTime + "ms (" + (totalTime / 1000.0) + "s)");
            Log.e(TAG, "   说明: 实际结果会通过 WebSocket onAgentResult 回调返回");
            Log.e(TAG, "================================================================================\n");

            return task;

        } catch (InterruptedException e) {
            Log.e(TAG, "❌ 推送被中断", e);
            task.setRecognizeResult("推送被中断: " + e.getMessage());
            return task;
        } catch (Exception e) {
            Log.e(TAG, "❌ WebSocket 流式传输失败", e);
            task.setRecognizeResult("流式传输异常: " + e.getMessage());
            return task;
        }
    }

    /**
     * HTTP Multipart 上传方式（旧方式，已保留）
     */
    private RecognizeTask processWithHttpMultipart(RecognizeTask task, long startTime) {
        Log.e(TAG, "\n🟡 开始 HTTP Multipart 上传流程（备用方式）");

        String sessionId = "img_" + System.currentTimeMillis();

        try {
            // 步骤1: 获取Bitmap
            long step1Time = System.currentTimeMillis();
            Bitmap bmp = task.getOriginBitmap();
            Log.e(TAG, "\n⏱️ [步骤1] 获取Bitmap");
            Log.e(TAG, "   时间: " + formatTime(step1Time) + " (距开始: " + (step1Time - startTime) + "ms)");
            Log.e(TAG, "   尺寸: " + bmp.getWidth() + "x" + bmp.getHeight());

            // 步骤2: 压缩图片
            long compressStart = System.currentTimeMillis();
            Log.e(TAG, "\n⏱️ [步骤2] 开始压缩图片");
            Log.e(TAG, "   时间: " + formatTime(compressStart));

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream);
            byte[] byteArray = stream.toByteArray();

            long compressEnd = System.currentTimeMillis();
            Log.e(TAG, "   ✅ 压缩完成，耗时: " + (compressEnd - compressStart) + "ms");
            Log.e(TAG, "   压缩后大小: " + (byteArray.length / 1024) + "KB");

            // 步骤3: 构建请求体
            long buildStart = System.currentTimeMillis();
            Log.e(TAG, "\n⏱️ [步骤3] 构建HTTP请求体");
            Log.e(TAG, "   时间: " + formatTime(buildStart));

            RequestBody requestFile = RequestBody.create(MediaType.parse("image/jpeg"), byteArray);
            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", "capture.jpg", requestFile);
            RequestBody inputType = RequestBody.create(MediaType.parse("text/plain"), "image");
            RequestBody sessionIdBody = RequestBody.create(MediaType.parse("text/plain"), sessionId);

            long buildEnd = System.currentTimeMillis();
            Log.e(TAG, "   ✅ 构建完成，耗时: " + (buildEnd - buildStart) + "ms");
            Log.e(TAG, "   SessionId: " + sessionId);

            // 步骤4: 发送网络请求（这是最耗时的部分）
            long uploadStart = System.currentTimeMillis();
            Log.e(TAG, "\n⏱️ [步骤4] 🚀 发起HTTP请求 (execute)");
            Log.e(TAG, "   开始时间: " + formatTime(uploadStart));
            Log.e(TAG, "   目标URL: " + "https://emotionless-kneadingly-tora.ngrok-free.dev/process_stream");
            Log.e(TAG, "   ⚠️ 这一步可能需要较长时间，请耐心等待...");

            Response<ResponseBody> response = RetrofitClient.getInstance().getApi()
                    .uploadImage(filePart, inputType, sessionIdBody).execute();

            long uploadEnd = System.currentTimeMillis();
            Log.e(TAG, "   ✅ HTTP请求返回！");
            Log.e(TAG, "   结束时间: " + formatTime(uploadEnd));
            Log.e(TAG, "   网络耗时: " + (uploadEnd - uploadStart) + "ms (" + ((uploadEnd - uploadStart) / 1000.0) + "秒)");
            Log.e(TAG, "   响应码: " + response.code());

            if (response.isSuccessful() && response.body() != null) {
                // 步骤5: 解析响应
                long parseStart = System.currentTimeMillis();
                Log.e(TAG, "\n⏱️ [步骤5] 解析SSE响应");
                Log.e(TAG, "   时间: " + formatTime(parseStart));

                String sseResponse = response.body().string();
                Log.e(TAG, "   响应长度: " + sseResponse.length() + " 字符");

                String finalResponse = parseSSEResponse(sseResponse);

                long parseEnd = System.currentTimeMillis();
                Log.e(TAG, "   ✅ 解析完成，耗时: " + (parseEnd - parseStart) + "ms");
                Log.e(TAG, "   结果长度: " + finalResponse.length() + " 字符");

                task.setRecognizeResult(finalResponse);
            } else {
                String errorBody = "";
                try {
                    if (response.errorBody() != null) {
                        errorBody = response.errorBody().string();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "读取错误响应体失败", e);
                }
                Log.e(TAG, "\n❌ HTTP请求失败");
                Log.e(TAG, "   响应码: " + response.code());
                Log.e(TAG, "   错误信息: " + response.message());
                Log.e(TAG, "   错误详情: " + errorBody);
                task.setRecognizeResult("识别失败: " + response.code() + " - " + response.message());
            }

        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "\n❌ 网络超时异常");
            Log.e(TAG, "   异常类型: SocketTimeoutException");
            Log.e(TAG, "   错误信息: " + e.getMessage());
            Log.e(TAG, "   💡 建议: 服务器处理时间过长，请检查后端性能或增加超时时间");
            task.setRecognizeResult("网络请求超时，请稍后重试");
        } catch (Exception e) {
            long errorTime = System.currentTimeMillis();
            Log.e(TAG, "\n❌ 发生异常");
            Log.e(TAG, "   时间: " + formatTime(errorTime));
            Log.e(TAG, "   异常类型: " + e.getClass().getSimpleName());
            Log.e(TAG, "   错误信息: " + e.getMessage());
            Log.e(TAG, "   堆栈跟踪:", e);
            task.setRecognizeResult("网络连接失败: " + e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        System.err.println("\n" + "================================================================================");
        System.err.println("⏱️ [结束] process() 完成，总耗时: " + totalTime + "ms");
        System.err.println("================================================================================\n");

        Log.e(TAG, "\n" + "================================================================================");
        Log.e(TAG, "⏱️ [结束] process() 完成");
        Log.e(TAG, "   结束时间: " + formatTime(endTime));
        Log.e(TAG, "   总耗时: " + totalTime + "ms (" + (totalTime / 1000.0) + "秒)");
        Log.e(TAG, "   SessionId: " + sessionId);
        Log.e(TAG, "================================================================================\n");

        return task;
    }

    /**
     * 确保 WebSocket 连接到图片流
     */
    private boolean ensureWebSocketConnected() {
        Log.d(TAG, "🔗 检查图片流 WebSocket 连接状态...");
        WebSocketManager wsManager = WebSocketManager.getInstance();

        // 如果没有会话ID，先重置会话
        if (wsManager.getCurrentSessionId() == null) {
            wsManager.resetSession();
        }

        // 已连接则直接复用，避免重复重连
        if (wsManager.isImageWebSocketConnected()) {
            Log.d(TAG, "✅ 图片流 WebSocket 已连接（复用现有连接）");
            return true;
        }

        // 连接到图片流 (/ws/image_stream)
        wsManager.connectToImageStream(wsManager.getCurrentSectionId());

        // 等待连接建立（最多3秒）
        for (int i = 0; i < 30; i++) {
            if (wsManager.isImageWebSocketConnected()) {
                Log.d(TAG, "✅ 图片流 WebSocket 已连接，SessionId: " + wsManager.getCurrentSessionId());
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        Log.e(TAG, "❌ 图片流 WebSocket 连接超时");
        return false;
    }

    /**
     * 解析 SSE 响应，提取 final_response
     */
    private String parseSSEResponse(String sseText) {
        try {
            // SSE 格式： event: xxx\nid: xxx\ndata: {json}\n\n
            // 我们需要找到最后一个 event: response，并提取其中的 final_response
            String[] events = sseText.split("\n\n");

            for (int i = events.length - 1; i >= 0; i--) {
                String event = events[i];
                if (event.contains("event: response")) {
                    // 提取 data: 后面的 JSON
                    String[] lines = event.split("\n");
                    for (String line : lines) {
                        if (line.startsWith("data: ")) {
                            String jsonStr = line.substring(6); // 去掉 "data: "
                            JsonObject json = gson.fromJson(jsonStr, JsonObject.class);

                            // 提取 data.final_response
                            if (json.has("data") && json.getAsJsonObject("data").has("final_response")) {
                                return json.getAsJsonObject("data").get("final_response").getAsString();
                            }
                        }
                    }
                }
            }

            // 如果没找到，返回错误信息
            return "解析失败：未找到 final_response";

        } catch (Exception e) {
            Log.e(TAG, "SSE 解析异常", e);
            return "解析错误: " + e.getMessage();
        }
    }
}