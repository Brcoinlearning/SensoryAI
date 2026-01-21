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
    // ⚠️ 请替换为你的真实公网/内网地址 (ws:// 或 wss://)
    private static final String WS_URL = "ws://192.168.1.100:8080/ws/chat";

    private static WebSocketManager instance;
    private WebSocket webSocket;
    private OkHttpClient client;
    private Gson gson = new Gson();
    private String currentSessionId;
    private boolean isManualClose = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;
    private Handler reconnectHandler = new Handler(Looper.getMainLooper());

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

    public void connect() {
        isManualClose = false;
        reconnectAttempts = 0;
        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
        }
        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString();
        }
        Request request = new Request.Builder().url(WS_URL).build();
        webSocket = client.newWebSocket(request, new SocketListener());
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

    public void sendAudio(byte[] pcmData, int len) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(pcmData, 0, len));
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
        reconnectHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) {
            webSocket.close(1000, "User Closed");
            webSocket = null;
        }
    }

    private class SocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "WebSocket Connected");
            reconnectAttempts = 0;
            if (listener != null) {
                runOnUiThread(() -> listener.onConnected());
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "WebSocket Closed: " + reason);
            if (listener != null) {
                runOnUiThread(() -> listener.onDisconnected(reason));
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "WebSocket Error", t);
            if (listener != null) {
                runOnUiThread(() -> {
                    listener.onError("connection", "连接失败: " + t.getMessage());
                    listener.onDisconnected("连接失败");
                });
            }
            // 自动重连（如果不是手动关闭）
            if (!isManualClose && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++;
                Log.d(TAG, "尝试重连 (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                reconnectHandler.postDelayed(() -> connect(), RECONNECT_DELAY_MS);
            }
        }
    }

    private void handleMessage(String json) {
        try {
            BaseMessage base = gson.fromJson(json, BaseMessage.class);
            if (base == null || base.type == null)
                return;

            runOnUiThread(() -> {
                if (listener == null)
                    return;
                switch (base.type) {
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
            Log.e(TAG, "JSON Parse Error", e);
            if (listener != null) {
                runOnUiThread(() -> listener.onError("parse", "消息解析失败: " + e.getMessage()));
            }
        }
    }

    private void runOnUiThread(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}