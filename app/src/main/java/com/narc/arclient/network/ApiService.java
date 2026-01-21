package com.narc.arclient.network;

import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ApiService {
    // 假设后端接口地址是 /api/recognize
    // 如果你的后端接口路径不同，请在这里修改
    @Multipart
    @POST("api/recognize")
    Call<ResponseBody> uploadImage(@Part MultipartBody.Part image);
}