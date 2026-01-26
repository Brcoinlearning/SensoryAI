package com.narc.arclient.network;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {

    // ❌ 旧代码 (内网 IP，只能在同一 WiFi 下用)
    // private static final String BASE_URL = "http://192.168.1.100:5000/";

    // ✅ 新代码 (公网 IP 或 域名)
    // 方案 A: 如果你有云服务器 (阿里云/腾讯云/AWS)
    // private static final String BASE_URL = "http://123.45.67.89:5000/";

    // 方案 B: 如果你有域名 (推荐，更稳定，且支持 HTTPS)
    // private static final String BASE_URL = "https://api.silversight.com/";

    // 方案 C: ngrok 内网穿透 (开发阶段)
    // 使用 HTTPS 安全连接，与后端 ngrok 地址一致
    private static final String BASE_URL = "https://emotionless-kneadingly-tora.ngrok-free.dev/";

    private static RetrofitClient instance;
    private ApiService apiService;

    private RetrofitClient() {
        // 增加超时时间，互联网传输可能比局域网慢
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                .connectTimeout(30, TimeUnit.SECONDS) // 连接超时 30s
                .readTimeout(30, TimeUnit.SECONDS) // 读取超时 30s
                .writeTimeout(30, TimeUnit.SECONDS) // 写入超时 30s
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ApiService.class);
    }

    public static synchronized RetrofitClient getInstance() {
        if (instance == null) {
            instance = new RetrofitClient();
        }
        return instance;
    }

    public ApiService getApi() {
        return apiService;
    }
}