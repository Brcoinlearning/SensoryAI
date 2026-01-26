package com.narc.arclient.network;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ApiService {
    // 图片识别接口：POST /process_stream
    // 参数：file (图片), input_type (固定为"image"), session_id (可选)
    @Multipart
    @POST("process_stream")
    Call<ResponseBody> uploadImage(
            @Part MultipartBody.Part file,
            @Part("input_type") RequestBody inputType,
            @Part("session_id") RequestBody sessionId);
}