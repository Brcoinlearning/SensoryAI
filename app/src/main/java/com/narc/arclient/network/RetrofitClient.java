package com.narc.arclient.network;

import com.narc.arclient.BuildConfig;
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

    private static final String BASE_URL = BuildConfig.API_BASE_URL;

    private static RetrofitClient instance;
    private ApiService apiService;

    private RetrofitClient() {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    // 添加 ngrok 跳过浏览器警告的 header
                    okhttp3.Request request = chain.request().newBuilder()
                            .addHeader("ngrok-skip-browser-warning", "true")
                            .build();
                    return chain.proceed(request);
                })
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS);

        if (BuildConfig.ENABLE_HTTP_LOGGING) {
            clientBuilder.addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS));
        }

        OkHttpClient client = clientBuilder.build();

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