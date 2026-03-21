package com.narc.arclient.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
// 👇 确保引入了刚才创建的实体类
import com.narc.arclient.entity.socket.AgentProgressMessage;
import com.narc.arclient.entity.socket.AgentResultMessage;
import com.narc.arclient.entity.socket.BaseMessage;
import com.narc.arclient.entity.socket.ErrorMessage;
import com.narc.arclient.entity.socket.SubtitleMessage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class WebSocketManager {
    private static final String TAG = "WebSocketManager";
    // ngrok 公网地址 - 使用 wss:// 安全连接，强制 IPv4
    private static final String BASE_URL = "wss://emotionless-kneadingly-tora.ngrok-free.dev";
    private static final String AUDIO_STREAM_PATH = "/ws/audio_stream";
    private static final String IMAGE_STREAM_PATH = "/ws/image_stream";

    private static WebSocketManager instance;
    private WebSocket webSocket;
    private WebSocket imageWebSocket; // 独立的图片流 WebSocket
    private OkHttpClient client;
    private Gson gson = new Gson();
    private String currentSessionId;
    private String currentSectionId; // 多模态会话ID，用于关联图片和音频
    private boolean isManualClose = false;
    private volatile boolean isAudioConnected = false;
    private volatile boolean isImageConnected = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;
    private Handler reconnectHandler = new Handler(Looper.getMainLooper());

    // 当前 WebSocket 类型：\"audio\" 或 \"image\"
    private String currentWebSocketType = "audio";

    public interface MessageListener {
        void onSubtitleUpdate(String text, boolean isFinal);

        void onAgentProgress(String stage, String status, String summary);

        void onAgentResult(String result, String sessionId);

        void onError(String stage, String message);

        void onConnected();

        void onDisconnected(String reason);
    }

    private MessageListener listener;

    private WebSocketManager() {
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 必须禁用超时
                .pingInterval(10, TimeUnit.SECONDS) // 心跳保活
                .build();
    }

    public static synchronized WebSocketManager getInstance() {
        if (instance == null) {
            instance = new WebSocketManager();
        }
        return instance;
    }

    public void setListener(MessageListener listener) {
        this.listener = listener;
    }

    /**
     * 连接到音频流（字幕）
     * 
     * @param sectionId 可选的多模态会话ID
     */
    public void connect(String sectionId) {
        connectToAudioStream(sectionId);
    }

    /**
     * 连接到音频流（字幕，无sectionId）
     */
    public void connect() {
        connectToAudioStream(null);
    }

    /**
     * 连接到音频流
     * 
     * @param sectionId 可选的多模态会话ID，如果提供则在URL中传递
     */
    public void connectToAudioStream(String sectionId) {
        isManualClose = false;
        reconnectAttempts = 0;
        isAudioConnected = false;
        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
        }
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString();
        }

        // 如果提供了sectionId，保存并添加到URL参数中
        if (sectionId != null && !sectionId.isEmpty()) {
            this.currentSectionId = sectionId;
        }

        String url = BASE_URL + AUDIO_STREAM_PATH;
        if (this.currentSectionId != null && !this.currentSectionId.isEmpty()) {
            url += "?section_id=" + this.currentSectionId;
            Log.d(TAG, "🔗 音频流连接携带 sectionId: " + this.currentSectionId);
        }

        currentWebSocketType = "audio";
        Log.d(TAG, "连接到音频流: " + url);
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new SocketListener("audio"));
    }

    /**
     * 连接到音频流（无sectionId，纯语音识别）
     */
    public void connectToAudioStream() {
        connectToAudioStream(null);
    }

    /**
     * 连接到图片流
     * 
     * @param sectionId 可选的多模态会话ID，如果提供则在URL中传递
     */
    public void connectToImageStream(String sectionId) {
        isManualClose = false;
        reconnectAttempts = 0;
        isImageConnected = false;
        if (imageWebSocket != null) {
            imageWebSocket.close(1000, "Reconnecting");
        }
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString();
        }

        // 如果提供了sectionId，保存并添加到URL参数中
        if (sectionId != null && !sectionId.isEmpty()) {
            this.currentSectionId = sectionId;
        }

        String url = BASE_URL + IMAGE_STREAM_PATH;
        if (this.currentSectionId != null && !this.currentSectionId.isEmpty()) {
            url += "?section_id=" + this.currentSectionId;
            Log.d(TAG, "🔗 图片流连接携带 sectionId: " + this.currentSectionId);
        }

        currentWebSocketType = "image";
        Log.d(TAG, "连接到图片流: " + url + ", SessionId: " + currentSessionId);
        Request request = new Request.Builder().url(url).build();
        imageWebSocket = client.newWebSocket(request, new SocketListener("image"));
    }

    /**
     * 连接到图片流（无sectionId，纯视觉识别）
     */
    public void connectToImageStream() {
        connectToImageStream(null);
    }

    /**
     * 重置会话 ID，开始新的对话
     */
    public void resetSession() {
        currentSessionId = UUID.randomUUID().toString();
        Log.d(TAG, "Session reset: " + currentSessionId);
    }

    /**
     * 获取当前会话 ID
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    /**
     * 设置多模态会话ID（用于关联图片和音频）
     */
    public void setSectionId(String sectionId) {
        this.currentSectionId = sectionId;
        Log.d(TAG, "🆔 设置 sectionId: " + sectionId);
    }

    /**
     * 获取当前多模态会话ID
     */
    public String getCurrentSectionId() {
        return currentSectionId;
    }

    /**
     * 清除多模态会话ID
     */
    public void clearSectionId() {
        this.currentSectionId = null;
        Log.d(TAG, "🧹 清除 sectionId");
    }

    public void sendAudio(byte[] pcmData, int len) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(pcmData, 0, len));
        }
    }

    /**
     * 发送图片数据块 (用于流式图片上传到 /ws/image_stream)
     * 直接发送二进制数据，后端自动处理完整图片识别
     * 
     * @param imageData 图片字节数据（JPEG/PNG 等常见格式）
     * @param len       实际数据长度
     */
    public void sendImageData(byte[] imageData, int len) {
        if (imageWebSocket != null && isImageConnected) {
            Log.d(TAG, "📤 发送图片数据: " + len + " bytes");
            imageWebSocket.send(ByteString.of(imageData, 0, len));
        } else {
            Log.e(TAG, "❌ 图片 WebSocket 未连接或未就绪");
        }
    }

    public void sendFinish() {
        if (webSocket != null) {
            // 符合协议：{"type": "final", "session_id": "xxx"}
            JsonObject json = new JsonObject();
            json.addProperty("type", "final");
            json.addProperty("session_id", currentSessionId);
            webSocket.send(json.toString());
        }
    }

    public void close() {
        isManualClose = true;
        isAudioConnected = false;
        isImageConnected = false;
        reconnectHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.close(1000, "User Closed");
            webSocket = null;
        }
        if (imageWebSocket != null) {
            imageWebSocket.close(1000, "User Closed");
            imageWebSocket = null;
        }
    }

    /**
     * 图片流 WebSocket 是否已建立且可发送
     */
    public boolean isImageWebSocketConnected() {
        return isImageConnected;
    }

    /**
     * 音频流 WebSocket 是否已建立且可发送
     */
    public boolean isAudioWebSocketConnected() {
        return isAudioConnected;
    }

    private class SocketListener extends WebSocketListener {
        private String type; // "audio" 或 "image"

        public SocketListener(String type) {
            this.type = type;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "WebSocket Connected [" + type + "]");
            reconnectAttempts = 0;
            if ("audio".equals(type)) {
                isAudioConnected = true;
            } else if ("image".equals(type)) {
                isImageConnected = true;
            }
            if ("audio".equals(type) && listener != null) {
                runOnUiThread(() -> listener.onConnected());
            } else if ("image".equals(type)) {
                Log.d(TAG, "✅ 图片流 WebSocket 已连接，可以开始推送图片");
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "WebSocket Closed: " + reason);
            if ("audio".equals(type)) {
                isAudioConnected = false;
            } else if ("image".equals(type)) {
                isImageConnected = false;
            }
            if (listener != null && "audio".equals(type)) {
                runOnUiThread(() -> listener.onDisconnected(reason));
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            if ("audio".equals(type)) {
                isAudioConnected = false;
            } else if ("image".equals(type)) {
                isImageConnected = false;
            }
            if (response != null) {
                Log.e(TAG, "WebSocket Error: code=" + response.code()
                        + ", message=" + response.message()
                        + ", headers=" + response.headers(), t);
            } else {
                Log.e(TAG, "WebSocket Error: no HTTP response, message=" + t.getMessage(), t);
            }
            if (listener != null && "audio".equals(type)) {
                runOnUiThread(() -> {
                    listener.onError("connection", "连接失败: " + t.getMessage());
                    listener.onDisconnected("连接失败");
                });
            }
            // 自动重连（如果不是手动关闭）
            if (!isManualClose && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                Log.d(TAG, "尝试重连 (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                reconnectHandler.postDelayed(() -> {
                    if ("image".equals(type)) {
                        connectToImageStream(currentSectionId);
                    } else {
                        connectToAudioStream(currentSectionId);
                    }
                }, RECONNECT_DELAY_MS);
            }
        }
    }

    private void handleMessage(String json) {
        try {
            Log.d(TAG, "📨 WebSocket 消息: " + json.substring(0, Math.min(100, json.length())) + "...");

            // 先尝试作为 JSON 解析
            BaseMessage base = null;
            try {
                base = gson.fromJson(json, BaseMessage.class);
            } catch (Exception e) {
                Log.d(TAG, "⚠️ 无法解析为 JSON，尝试作为纯文本处理");
            }

            // 如果 JSON 解析失败或没有 type，尝试直接处理为结果
            if (base == null || base.type == null) {
                Log.d(TAG, "💡 消息作为纯文本处理，可能是识别结果: " + json);
                // 当作纯文本结果返回
                if (listener != null) {
                    runOnUiThread(() -> {
                        listener.onAgentResult(json, "unknown");
                    });
                }
                return;
            }

            final BaseMessage finalBase = base; // make effectively final for lambda
            runOnUiThread(() -> {
                if (listener == null)
                    return;
                switch (finalBase.type) {
                    case "subtitle":
                        SubtitleMessage sub = gson.fromJson(json, SubtitleMessage.class);
                        // 修复：is_partial=true 表示部分结果，isFinal=!is_partial
                        listener.onSubtitleUpdate(sub.text, !sub.is_partial);
                        break;
                    case "agent_progress":
                        AgentProgressMessage prog = gson.fromJson(json, AgentProgressMessage.class);
                        // 添加 status 字段传递
                        listener.onAgentProgress(prog.stage, prog.status, prog.summary);
                        break;
                    case "agent_result":
                        AgentResultMessage res = gson.fromJson(json, AgentResultMessage.class);
                        // 传递 sessionId 供验证
                        listener.onAgentResult(gson.toJson(res.data), res.session_id);
                        break;
                    case "error":
                        ErrorMessage err = gson.fromJson(json, ErrorMessage.class);
                        // 完善错误处理：区分错误阶段
                        String stage = err.stage != null ? err.stage : "unknown";
                        String message = err.message != null ? err.message : "未知错误";
                        listener.onError(stage, message);
                        break;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "JSON Parse Error: " + e.getMessage(), e);
            if (listener != null) {
                runOnUiThread(() -> listener.onError("parse", "消息处理失败: " + e.getMessage()));
            }
        }
    }

    private void runOnUiThread(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}