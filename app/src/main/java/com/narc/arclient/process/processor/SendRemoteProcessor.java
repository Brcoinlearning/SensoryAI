package com.narc.arclient.process.processor;

import android.graphics.Bitmap;
import android.util.Log;
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

            // 2. 构建 Multipart 请求体（PNG 全图）
            RequestBody requestFile = RequestBody.create(MediaType.parse("image/png"), byteArray);
            MultipartBody.Part body = MultipartBody.Part.createFormData("image", "capture.png", requestFile);

            // 3. 🚀 发送同步请求 (execute)
            // 因为 process 方法本身就在后台线程运行，所以这里用同步请求最简单直接
            Response<ResponseBody> response = RetrofitClient.getInstance().getApi().uploadImage(body).execute();

            if (response.isSuccessful() && response.body() != null) {
                // 4. ✅ 成功：获取服务器返回的字符串
                String result = response.body().string();
                Log.i(TAG, "✅ 服务器识别成功: " + result);
                task.setRecognizeResult(result);
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
}