package com.narc.arclient.process.processor;

import android.graphics.Bitmap;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.network.RetrofitClient;
import java.io.ByteArrayOutputStream;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Response;

public class SendRemoteProcessor {
    private static final String TAG = "SendRemote";
    private static final Gson gson = new Gson();

    // 这个方法会被 ProcessorManager 在后台线程调用
    public RecognizeTask process(RecognizeTask task) {
        if (task == null || task.getOriginBitmap() == null) {
            return task;
        }

        Log.d(TAG, "📸 准备上传图片...");
        try {
            // 1. 将 Bitmap 压缩为无损 PNG，保持与本地保存一致
            Bitmap bmp = task.getOriginBitmap();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, stream);
            byte[] byteArray = stream.toByteArray();

            // 2. 构建 Multipart 请求体 - 按照协议要求
            RequestBody requestFile = RequestBody.create(MediaType.parse("image/png"), byteArray);
            // 参数名改为 "file"（协议要求）
            MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", "capture.png", requestFile);

            // input_type 参数（协议要求）
            RequestBody inputType = RequestBody.create(MediaType.parse("text/plain"), "image");

            // session_id 参数（可选，使用当前时间戳）
            String sessionId = "img_" + System.currentTimeMillis();
            RequestBody sessionIdBody = RequestBody.create(MediaType.parse("text/plain"), sessionId);

            // 3. 🚀 发送同步请求 (execute)
            Response<ResponseBody> response = RetrofitClient.getInstance().getApi()
                    .uploadImage(filePart, inputType, sessionIdBody).execute();

            if (response.isSuccessful() && response.body() != null) {
                // 4. ✅ 成功：解析 SSE 流式响应
                String sseResponse = response.body().string();
                Log.i(TAG, "✅ 服务器返回 SSE: " + sseResponse.substring(0, Math.min(200, sseResponse.length())));

                // 解析 SSE 格式，提取最后的 response 事件中的 final_response
                String finalResponse = parseSSEResponse(sseResponse);
                task.setRecognizeResult(finalResponse);
            } else {
                // 5. ❌ 失败：记录错误码
                Log.e(TAG, "❌ 上传失败, Code: " + response.code());
                task.setRecognizeResult("识别失败: " + response.code());
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ 网络异常", e);
            task.setRecognizeResult("网络连接超时");
        }

        return task;
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