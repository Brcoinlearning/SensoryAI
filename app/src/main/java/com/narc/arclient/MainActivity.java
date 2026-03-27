package com.narc.arclient;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.narc.arclient.ui.SubtitleStreamView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity;
import com.narc.arclient.audio.AudioRecorder;
import com.narc.arclient.camera.ICameraManager;
import com.narc.arclient.databinding.ActivityMainBinding;
import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.entity.RenderData;
import com.narc.arclient.network.WebSocketManager;
import com.narc.arclient.process.ProcessorManager;
import com.narc.arclient.process.processor.RecognizeProcessor;
import com.narc.arclient.process.processor.RenderProcessor;
import com.narc.arclient.process.processor.SendRemoteProcessor;
import com.narc.arclient.utils.TTSManager;

public class MainActivity extends BaseMirrorActivity<ActivityMainBinding> {

    private static final String TAG = "SilverSight";

    // ============ 三段式模式定义 ============
    /**
     * 交互模式枚举
     * PHOTO: 拍照识药（仅图像）
     * SUBTITLE: 实时字幕（仅语音）
     * MULTI: 拍照+追问（多模态，同一sectionId）
     */
    private enum Mode {
        PHOTO("拍照识药", ""),
        SUBTITLE("实时字幕", ""),
        MULTI("拍照追问", "");

        final String displayName;
        final String icon;

        Mode(String displayName, String icon) {
            this.displayName = displayName;
            this.icon = icon;
        }
    }

    private enum StatusMotion {
        IDLE,
        LISTENING,
        PROCESSING
    }

    private enum CardPhase {
        HIDDEN,
        WAITING,
        RESULT
    }

    private enum CardInteractionType {
        NONE,
        OPTION_SELECTION,
        RESULT_NOTIFICATION
    }

    private enum DemoSegment {
        PHOTO_RECOGNITION,
        REALTIME_SUBTITLE,
        MULTI_MODAL_QA,
        REMINDER_FLOW,
        SMS_STATUS
    }

    private Mode currentMode = Mode.PHOTO; // 默认：拍照识药模式
    private boolean isModeLocked = false; // 防止交互过程中切换模式
    private String currentSectionId = null; // 多模态会话ID
    // private static final boolean MOCK_SUBTITLE = true; // 临时开启字幕模拟 (改由按钮控制)

    // UI 组件
    private CustomDrawView customDrawView;
    private android.widget.ImageView ivStatusIcon;
    private com.narc.arclient.ui.ArToastView arToastView;
    // 注意：卡片通过 mBindingPair 访问，不用 findViewById
    // Demo: 模拟字幕流（仅调试）
    private Handler subtitleDemoHandler;
    private boolean subtitleDemoRunning = false;
    private static final boolean ENABLE_LOCAL_UI_SIMULATION = false;
    private static final long LOCAL_UI_SIM_START_DELAY_MS = 1200L;
    private static final long LOCAL_UI_SIM_RESULT_DELAY_MS = 2600L;
    private final Handler localUiSimHandler = new Handler(Looper.getMainLooper());
    private boolean localUiSimRunning = false;

    // AR 卡片组件
    private TextView tvCardTitle, tvCardContent;
    private float lastCardX = -1f; // 跟随平滑用
    private float lastCardY = -1f; // 跟随平滑用

    // 状态控制
    private boolean isAnalyzing = false;
    private boolean isVoiceCardShowing = false; // 标记语音相关卡片是否显示
    private long openPalmStartTime = 0; // 张手关闭卡片的长按起点
    private long lastImageSendTime = 0; // 记录最近一次图片推送的开始时间
    private static final long CLOSE_HOLD_MS = 2000; // 张手关闭所需时长
    private long lastTriggerTime = 0;
    private static final long COOLDOWN_MS = 8000; // 改为8秒防抖
    private boolean isMicEnabled = false;
    private StatusMotion currentStatusMotion = StatusMotion.IDLE;
    // 结果是否已到达并渲染；用于避免凝聚动画回调把结果文本又覆盖回等待态。
    private boolean cardResultReady = false;
    // 卡片显式阶段：隐藏/等待中/结果可读。
    private CardPhase cardPhase = CardPhase.HIDDEN;

    // 最近一帧的渲染数据，用于硬件按键触发时复用指尖坐标
    private RenderData lastRenderData;

    // 多模态模式标志：是否等待用户提问
    private boolean isWaitingForQuestion = false;
    private static final long MULTI_AUTO_STOP_RECORD_MS = 15_000L;
    private final Handler multiRecordHandler = new Handler(Looper.getMainLooper());
    private final Runnable multiAutoStopRunnable = () -> {
        if (currentMode == Mode.MULTI && isMicEnabled) {
            Log.d(TAG, "多模态录音到达自动结束时长，触发停止");
            showArToast("录音已自动结束");
            handleMicToggle(false);
        }
    };

    // ======= 本地模拟交互 =======
    private static final long DEMO_PHOTO_DELAY_MS = 3000;
    private static final long DEMO_MULTI_VOICE_DELAY_MS = 2000;
    private final Handler demoHandler = new Handler(Looper.getMainLooper());
    private Handler multiDemoHandler;
    private final Handler backendDemoHandler = new Handler(Looper.getMainLooper());
    private static final boolean ENABLE_FAKE_BACKEND_DEMO = true;
    private static final long DEMO_TRIGGER_COOLDOWN_MS = 1200L;
    private static final long DEMO_PHOTO_RESULT_MIN_DELAY_MS = 8000L;
    private static final long DEMO_PHOTO_RESULT_MAX_DELAY_MS = 10000L;
    private static final long REMINDER_POPUP_DELAY_AFTER_CREATE_MS = 10000L;
    private static final long REMINDER_SNOOZE_DELAY_MS = 10 * 60 * 1000L;
    // 全局字幕起始延迟：避免用户还未开口时字幕提前出现。
    private static final long MULTI_QA_SUBTITLE_START_DELAY_MS = 3500L;
    private static final long SUBTITLE_DEMO_START_DELAY_MS = 3500L;
    private static final String REMINDER_PREFS_NAME = "local_reminder";
    private static final String REMINDER_KEY_DRUG = "drug";
    private static final String REMINDER_KEY_USAGE = "usage";
    private static final String REMINDER_KEY_TIME = "time";
    private static final String REMINDER_KEY_SOURCE = "source";
    private boolean backendSmsSuccessNext = true;
    private boolean multiQaDemoActive = false;
    private boolean reminderDemoActive = false;
    private boolean smsDemoActive = false;
    private ReminderCardData pendingReminderCardData;
    private final Handler localReminderHandler = new Handler(Looper.getMainLooper());
    private Runnable localReminderRunnable;
    private DemoSegment activeMultiDemoSegment = DemoSegment.MULTI_MODAL_QA;
    private DemoSegment multiDemoSelection = DemoSegment.MULTI_MODAL_QA;

    private static class ReminderCardData {
        final String drugName;
        final String usage;
        final String reminderTime;
        final String source;

        ReminderCardData(String drugName, String usage, String reminderTime, String source) {
            this.drugName = drugName;
            this.usage = usage;
            this.reminderTime = reminderTime;
            this.source = source;
        }
    }

    private interface CardActionHandler {
        void onAction();
    }

    private CardActionHandler pendingPrimaryCardAction;
    private CardActionHandler pendingSecondaryCardAction;
    private boolean isCardActionInProgress = false;
    private long lastCardActionTriggerTime = 0L;
    private static final long CARD_ACTION_COOLDOWN_MS = 1200L;
    private static final int CARD_HOVER_NONE = 0;
    private static final int CARD_HOVER_SECONDARY = 1;
    private static final int CARD_HOVER_PRIMARY = 2;
    private static final long CARD_HOVER_DWELL_MS = 300L;
    private static final long CARD_ACTION_HOVER_TRIGGER_MS = 650L;
    private int currentCardHoverTarget = CARD_HOVER_NONE;
    private int pendingCardHoverTarget = CARD_HOVER_NONE;
    private long cardHoverCandidateStartTime = 0L;
    private long cardHoverActivatedTime = 0L;
    private CardInteractionType currentCardInteractionType = CardInteractionType.NONE;
    private boolean currentResultCardClosableByOpenPalm = false;

    // 触摸事件状态追踪（用于检测单击）
    private long touchDownTime = 0;
    private float touchDownX = 0;
    private float touchDownY = 0;
    private static final long TAP_TIMEOUT_MS = 500; // 单击最长允许时间
    private static final float TAP_SLOP_PX = 50; // 单击最大允许移动像素

    // TTS 文字转语音管理器
    private TTSManager ttsManager;
    private final Handler videoStopHandler = new Handler(Looper.getMainLooper());
    private static final long VIDEO_MAX_DURATION_MS = 50_000;
    private ValueAnimator micVisibilityAnimator;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 0. 初始化 TTS 管理器 (用于眼镜发声)
        ttsManager = TTSManager.getInstance(this);

        // 1. 初始化核心处理器
        try {
            RenderProcessor.init(this);
            RecognizeProcessor.init(this);
            ProcessorManager.init(this);
        } catch (Exception e) {
            Log.e(TAG, "Processor Init Error", e);
        }

        // 2. 初始化 WebSocket 监听器 (处理后端返回的字幕和智能体消息)
        initWebSocketListener();

        // 3. 初始化所有按钮监听器 (处理用户点击交互)
        initButtonListeners();

        // 4. 绑定 UI 控件
        initViews();

        // 5. 初始化自定义绘图层 (画光标、按钮)
        initCustomView();

        // 6. 检查权限并启动逻辑
        checkPermissionsAndStart();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event != null && isDemoTriggerKey(keyCode) && event.getRepeatCount() == 0) {
            if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.MULTI) {
                showArToast("当前模式仅支持点按切换，触发请用悬停");
                return true;
            }
            boolean handled = tryTriggerCaptureViaHardware();
            if (handled) {
                Log.d(TAG, "硬件按键触发演示，keyCode=" + keyCode + ", mode=" + currentMode.displayName);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isDemoTriggerKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CAMERA
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 触摸/触控板事件在此捕获
        int action = event.getAction();
        float x = event.getX();
        float y = event.getY();

        // Log.d(TAG, "👆 onTouchEvent action=" + action + " x=" + String.format("%.1f",
        // x) + " y="
        // + String.format("%.1f", y));

        if (action == MotionEvent.ACTION_DOWN) {
            touchDownTime = System.currentTimeMillis();
            touchDownX = x;
            touchDownY = y;
        } else if (action == MotionEvent.ACTION_UP) {
            long duration = System.currentTimeMillis() - touchDownTime;
            float distance = (float) Math
                    .sqrt((x - touchDownX) * (x - touchDownX) + (y - touchDownY) * (y - touchDownY));

            if (duration < TAP_TIMEOUT_MS && distance < TAP_SLOP_PX) {
                if (isInteractiveCardVisible()) {
                    return true;
                }
                if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.MULTI) {
                    cycleMultiDemoSelectionByHardware();
                    return true;
                }
                boolean handled = tryTriggerCaptureViaHardware();
                if (handled)
                    return true;
            }
        }

        return super.onTouchEvent(event);
    }

    /**
     * 初始化 WebSocket 消息监听
     * 负责接收服务器发来的：实时字幕、智能体思考过程、最终结果、错误信息
     */
    private void initWebSocketListener() {
        WebSocketManager.getInstance().setListener(new WebSocketManager.MessageListener() {
            @Override
            public void onSubtitleUpdate(String text, boolean isFinal) {
                long receiveTime = System.currentTimeMillis();
                if (isFinal) {
                    Log.i(TAG, "💬 [字幕最终结果] 时间: " + receiveTime + ", 内容: " + text);
                }

                // ============ 根据模式处理字幕 ============
                if (currentMode == Mode.SUBTITLE) {
                    // 实时字幕模式：显示字幕，不显示卡片
                    updateSubtitle(text, isFinal);
                } else if (currentMode == Mode.MULTI) {
                    // 多模态模式：也显示字幕（提供实时反馈）
                    updateSubtitle(text, isFinal);
                }
                // 拍照模式：不处理字幕
            }

            @Override
            public void onAgentProgress(String stage, String status, String summary) {
                // 产品调整：不展示中间“感知/理解/决策”等过程文案。
                // 仅保留等待GIF，最终由 onAgentResult 一次性展示结果。
                // runOnUiThread(() -> {
                // startCardSequence();
                // isVoiceCardShowing = true;
                // int color = "completed".equals(status)
                // ? getResources().getColor(R.color.status_success, null)
                // : getResources().getColor(R.color.primary_teal_light, null);
                // String stageText = getStageText(stage);
                // setCardText(stageText, summary, color);
                // positionCardTopCenter();
                // });
                Log.d(TAG, "🧭 [agent_progress已忽略] stage=" + stage + ", status=" + status);
            }

            @Override
            public void onAgentResult(String result, String sessionId) {
                long receiveTime = System.currentTimeMillis();
                Log.i(TAG, "📥 [识别完成] 收到结果，时间: " + receiveTime + ", session: " + sessionId + ", mode: " + currentMode);

                // 计算端到端耗时
                if (lastImageSendTime > 0) {
                    long totalLatency = receiveTime - lastImageSendTime;
                    Log.i(TAG, "⏱️ 端到端耗时: " + totalLatency + "ms (" + (totalLatency / 1000.0) + "s)");
                }

                // 解析并显示智能体最终回复
                runOnUiThread(() -> {
                    try {
                        Log.d(TAG, "📩 收到智能体结果，长度: " + result.length());
                        Log.d(TAG, "📩 前200字符: " + result.substring(0, Math.min(200, result.length())));

                        // ============ 根据模式处理结果 ============
                        if (currentMode == Mode.SUBTITLE) {
                            // 实时字幕模式：不显示卡片，只在日志记录
                            Log.d(TAG, "💬 [实时字幕模式] 忽略agent结果，只显示字幕");
                            return;
                        }

                        String displayText = result;
                        String title = "识别结果";

                        // 先尝试解析为 JSON
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(result);

                            if (currentMode == Mode.PHOTO) {
                                // ============ 拍照识药模式：直接使用data字段 ============
                                title = "药品信息";

                                // 优先使用data字段（后端已格式化）
                                if (json.has("data")) {
                                    displayText = json.optString("data", result);
                                    Log.d(TAG, "📦 [拍照识药] 使用data字段");
                                } else {
                                    // 降级方案：尝试解析结构化字段
                                    StringBuilder sb = new StringBuilder();
                                    if (json.has("drug_name")) {
                                        sb.append("药品名称：").append(json.optString("drug_name", "未知")).append("\n");
                                    }
                                    if (json.has("brand")) {
                                        sb.append("品牌：").append(json.optString("brand", "未知")).append("\n");
                                    }
                                    if (json.has("specification")) {
                                        sb.append("规格：").append(json.optString("specification", "未知"))
                                                .append("\n");
                                    }
                                    if (json.has("function")) {
                                        sb.append("功能：").append(json.optString("function", "未知")).append("\n");
                                    }
                                    if (json.has("indications")) {
                                        sb.append("主治：").append(json.optString("indications", "未知")).append("\n");
                                    }
                                    if (json.has("usage")) {
                                        sb.append("用法用量：").append(json.optString("usage", "未知"));
                                    }

                                    if (sb.length() > 0) {
                                        displayText = sb.toString();
                                    } else {
                                        displayText = json.optString("answer", result);
                                    }
                                }

                            } else if (currentMode == Mode.MULTI) {
                                // ============ 多模态模式：直接使用data字段 ============
                                title = "回答";

                                ReminderCardData reminderCardData = parseReminderCardDataFromJson(json);
                                if (reminderCardData != null) {
                                    setStatusMotion(StatusMotion.IDLE);
                                    isModeLocked = true;
                                    isVoiceCardShowing = true;
                                    isWaitingForQuestion = false;
                                    updateStatus("等待确认", R.drawable.ic_assistant);
                                    showReminderConfirmCard(reminderCardData);
                                    return;
                                }

                                // 优先使用data字段
                                if (json.has("data")) {
                                    displayText = json.optString("data", result);
                                    Log.d(TAG, "💬 [拍照追问] 使用data字段");
                                } else if (json.has("answer")) {
                                    displayText = json.optString("answer", result);
                                    Log.d(TAG, "💬 [拍照追问] 使用answer字段");
                                } else if (json.has("results") && json.getJSONObject("results").has("primary")) {
                                    org.json.JSONObject primary = json.getJSONObject("results")
                                            .getJSONObject("primary");
                                    if (primary.has("original")) {
                                        displayText = primary.getJSONObject("original").optString("answer", result);
                                    }
                                }
                            }
                        } catch (org.json.JSONException jsonEx) {
                            Log.d(TAG, "💡 JSON 解析失败，直接显示为纯文本结果");
                            // JSON 解析失败，直接使用原始结果
                        }

                        // 处理纯文本中的转义换行符，提升可读性
                        displayText = displayText.replace("\\n", "\n");

                        setStatusMotion(StatusMotion.IDLE);

                        // 显示最终结果
                        if (currentMode == Mode.MULTI) {
                            isModeLocked = false;
                            isVoiceCardShowing = true; // 标记为语音卡片
                            positionCardTopCenter(); // 多模态结果卡片定位到顶部居中
                            isWaitingForQuestion = false;
                        }
                        setCardText(title, displayText, getResources().getColor(R.color.status_success, null));

                    } catch (Exception e) {
                        Log.e(TAG, "❌ 处理识别结果失败", e);
                        setStatusMotion(StatusMotion.IDLE);
                        // 即使解析失败，也显示原始结果
                        setCardText("识别结果", result, getResources().getColor(R.color.status_success, null));
                    }
                    triggerVibration();
                });
            }

            @Override
            public void onError(String stage, String message) {
                // 区分错误类型显示
                String errorMsg = "subtitle".equals(stage) ? "字幕错误" : "智能体错误";
                updateStatus(errorMsg + ": " + message, R.drawable.ic_assistant);
                if (currentMode == Mode.MULTI) {
                    isModeLocked = false;
                    isWaitingForQuestion = false;
                }
            }

            @Override
            public void onConnected() {
                if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.SUBTITLE) {
                    Log.d(TAG, "字幕模拟模式下忽略 WebSocket connected 状态覆盖");
                    return;
                }
                updateStatus("已连接", R.drawable.ic_assistant);
            }

            @Override
            public void onDisconnected(String reason) {
                if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.SUBTITLE) {
                    Log.d(TAG, "字幕模拟模式下忽略 WebSocket disconnected 状态覆盖: " + reason);
                    return;
                }
                updateStatus("已断开: " + reason, R.drawable.ic_assistant);
            }
        });
    }

    /**
     * 初始化所有按钮监听器：麦克风按钮 + 模式切换按钮
     * 当用户手指在 AR 眼镜前点击虚拟按钮时触发
     */
    private void initButtonListeners() {
        if (RenderProcessor.getInstance() != null) {
            // 麦克风按钮监听
            RenderProcessor.getInstance().setOnMicStatusListener(new RenderProcessor.OnMicStatusListener() {
                @Override
                public void onMicClick(boolean isOn) {
                    runOnUiThread(() -> {
                        handleMicToggle(isOn);
                    });
                }
            });

            // 模式切换按钮监听
            RenderProcessor.getInstance().setOnModeSwitchListener(new RenderProcessor.OnModeSwitchListener() {
                @Override
                public void onModeSwitch(int modeIndex) {
                    runOnUiThread(() -> {
                        handleModeSwitch(modeIndex);
                    });
                }
            });
        }
    }

    /**
     * 绑定布局中的 View（通过 mBindingPair 访问卡片和字幕）
     */
    private void initViews() {
        Log.d(TAG, "================== 开始初始化 Views ==================");
        Log.d(TAG, "mBindingPair=" + (mBindingPair != null));
        ivStatusIcon = findViewById(R.id.iv_status_icon);
        arToastView = findViewById(R.id.ar_toast_view);
        Log.d(TAG, "================== 初始化完成 ==================");
    }

    private void checkPermissionsAndStart() {
        Log.d(TAG, "🔐 检查权限...");

        // 检查必需的运行时权限
        String[] requiredPermissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };

        boolean allGranted = true;
        for (String permission : requiredPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                Log.w(TAG, "⚠️ 缺少权限: " + permission);
                break;
            }
        }

        if (allGranted) {
            Log.d(TAG, "✅ 所有权限已授予");
            initializeApp();
        } else {
            Log.d(TAG, "📋 请求运行时权限...");
            ActivityCompat.requestPermissions(this, requiredPermissions, 100);
        }
    }

    private void initializeApp() {

        // 简单模拟器判断
        boolean isEmulator = android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.BRAND.startsWith("generic");

        View debugPanel = findViewById(R.id.debug_panel);
        if (isEmulator) {
            if (debugPanel != null)
                debugPanel.setVisibility(View.VISIBLE);
            updateStatus("模式：模拟器");
            View btnMock = findViewById(R.id.btn_mock_data);
            if (btnMock != null) {
                btnMock.setOnClickListener(v -> {
                    triggerFakeBackendDemo(System.currentTimeMillis());
                });
            }
        } else {
            if (debugPanel != null)
                debugPanel.setVisibility(View.GONE);
            // 延时启动摄像头，避免初始化冲突
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    ICameraManager.init(this);
                    updateStatus("系统就绪");
                } catch (Exception e) {
                    Log.e(TAG, "Camera Init Fail", e);
                }
            }, 1000);
        }

        if (ENABLE_LOCAL_UI_SIMULATION) {
            localUiSimHandler.removeCallbacksAndMessages(null);
            localUiSimHandler.postDelayed(this::runLocalUiSimulationOnce, LOCAL_UI_SIM_START_DELAY_MS);
        }

        initLocalReminderSchedule();
        bindCardActionListeners();

    }

    private void runLocalUiSimulationOnce() {
        if (localUiSimRunning) {
            return;
        }
        localUiSimRunning = true;
        isModeLocked = true;
        isVoiceCardShowing = true;

        showArToast("本地模拟：进入处理中");
        updateStatus("处理中", R.drawable.ic_processing);
        setStatusMotion(StatusMotion.PROCESSING);
        startCardSequence();
        positionCardTopCenter();
        // startCardSequence 内部会更新一次状态文案，这里覆盖为验收所需口径。
        updateStatus("处理中", R.drawable.ic_processing);

        localUiSimHandler.postDelayed(() -> {
            setStatusMotion(StatusMotion.IDLE);
            setCardText("模拟结果", "这是本地模拟结果，用于验收 processing 与卡片等待动效。",
                    getResources().getColor(R.color.status_success, null));
            positionCardTopCenter();
            updateStatus("系统就绪", R.drawable.ic_assistant);
            showArToast("本地模拟完成");
            localUiSimRunning = false;
            isModeLocked = false;
        }, LOCAL_UI_SIM_RESULT_DELAY_MS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 100) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.e(TAG, "❌ 用户拒绝权限: " + permissions[i]);
                }
            }

            if (allGranted) {
                Log.d(TAG, "✅ 用户授予所有权限");
                initializeApp();
            } else {
                Log.e(TAG, "⚠️ 部分权限被拒绝，应用功能受限");
                Toast.makeText(this, "需要相机和麦克风权限才能正常使用", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 处理麦克风开关逻辑
     */
    private void handleMicToggle(boolean isOn) {
        Log.d(TAG, "🎤 handleMicToggle: " + isOn + ", currentMode=" + currentMode + ", isMicEnabled=" + isMicEnabled);

        if (isMicEnabled == isOn) {
            Log.d(TAG, "🎤 麦克风状态未变化，忽略重复切换");
            return;
        }

        // ============ 模式检查 ============
        // 拍照识药模式：禁止使用麦克风
        if (currentMode == Mode.PHOTO) {
            String msg = "当前为拍照识药模式，请切换到实时字幕或拍照追问模式";
            updateStatus(msg);
            showArToast(msg);
            Log.w(TAG, "⚠️ 麦克风被阻止：当前为拍照识药模式");
            return;
        }

        // 多模态模式：如果是开启麦克风，必须先拍照；如果是关闭麦克风，总是允许
        if (currentMode == Mode.MULTI && isOn && !isWaitingForQuestion) {
            String msg = "请先拍照，然后提问";
            updateStatus(msg);
            showArToast(msg);
            Log.w(TAG, "⚠️ 麦克风被阻止：多模态模式需先拍照");
            return;
        }

        isMicEnabled = isOn;

        // 1. 更新渲染器 UI (红圆点 <-> 红方块)
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setMicState(isMicEnabled);
        }

        // 2. 震动反馈
        triggerVibration();

        // 实时字幕模拟：麦克风开关直接驱动本地字幕流，不依赖后端连接
        if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.SUBTITLE) {
            if (isMicEnabled) {
                updateStatus("实时字幕通道已连接", R.drawable.ic_microphone_active);
                setStatusMotion(StatusMotion.LISTENING);
                showArToast("开始接收语音字幕");
                startSubtitleMockDemo();
            } else {
                stopSubtitleMockDemo();
                setStatusMotion(StatusMotion.IDLE);
                updateStatus("系统就绪", R.drawable.ic_assistant);
                showArToast("字幕通道已关闭");
            }
            return;
        }

        // 多模态模拟统一链路：拍照后自动开麦 -> 字幕 -> 处理态 -> 按意图返回不同卡片
        if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.MULTI
                && (multiQaDemoActive || reminderDemoActive || smsDemoActive)) {
            if (isMicEnabled) {
                if (activeMultiDemoSegment == DemoSegment.REMINDER_FLOW) {
                    updateStatus("正在聆听提醒需求", R.drawable.ic_microphone_active);
                    playReminderQuestionSubtitleWithPauses();
                } else if (activeMultiDemoSegment == DemoSegment.SMS_STATUS) {
                    updateStatus("正在聆听短信需求", R.drawable.ic_microphone_active);
                    playSmsQuestionSubtitleWithPauses();
                } else {
                    updateStatus("正在聆听提问", R.drawable.ic_microphone_active);
                    showArToast("请开始提问，完成后关闭麦克风");
                    playMultiQaQuestionSubtitleWithPauses();
                }
                setStatusMotion(StatusMotion.LISTENING);
            } else {
                backendDemoHandler.removeCallbacksAndMessages(null);
                mBindingPair.updateView(binding -> {
                    if (binding.subtitleView != null) {
                        binding.subtitleView.clearImmediate();
                    }
                    return null;
                });

                updateStatus("问题分析中", R.drawable.ic_processing);
                setStatusMotion(StatusMotion.PROCESSING);
                isVoiceCardShowing = true;
                startCardSequence();
                positionCardTopCenter();
                setCardText("分析中", "正在结合图像与语音意图...",
                        getResources().getColor(R.color.primary_teal_light, null), false);

                long waitMs = DEMO_PHOTO_RESULT_MIN_DELAY_MS
                        + (long) (Math.random()
                                * (DEMO_PHOTO_RESULT_MAX_DELAY_MS - DEMO_PHOTO_RESULT_MIN_DELAY_MS + 1));

                backendDemoHandler.postDelayed(() -> {
                    setStatusMotion(StatusMotion.IDLE);
                    if (activeMultiDemoSegment == DemoSegment.REMINDER_FLOW) {
                        updateStatus("等待确认", R.drawable.ic_assistant);
                        showReminderConfirmCard();
                    } else if (activeMultiDemoSegment == DemoSegment.SMS_STATUS) {
                        updateStatus("等待确认", R.drawable.ic_assistant);
                        showSmsConfirmCard();
                    } else {
                        setCardText("药品有效期分析", "生产日期：2025年7月21日\n有效期至：2028年7月20日\n结论：当前仍在有效期内",
                                getResources().getColor(R.color.status_success, null));
                        positionCardTopCenter();
                        updateStatus("问答完成", R.drawable.ic_assistant);
                        showArToast("问答结果已返回");
                        isModeLocked = false;
                    }

                    isWaitingForQuestion = false;
                    multiQaDemoActive = false;
                    reminderDemoActive = false;
                    smsDemoActive = false;
                    activeMultiDemoSegment = DemoSegment.MULTI_MODAL_QA;
                }, waitMs);
            }
            return;
        }

        // 3. 开启或停止录音推流
        if (isMicEnabled) {
            String status = "正在聆听";
            updateStatus(status, R.drawable.ic_microphone_active);
            setStatusMotion(StatusMotion.LISTENING);
            showArToast(status);
            if (ttsManager != null) {
                ttsManager.speakWithSound(status);
            }

            // ============ 根据模式连接WebSocket ============
            // 多模态模式：传递sectionId
            if (currentMode == Mode.MULTI && currentSectionId != null) {
                Log.d(TAG, "📞 多模态模式：使用 sectionId=" + currentSectionId);
                WebSocketManager.getInstance().connect(currentSectionId);
            } else {
                // 实时字幕模式：不传sectionId
                Log.d(TAG, "📞 实时字幕模式：不使用 sectionId");
                WebSocketManager.getInstance().connect();
            }

            // 启动录音机
            Log.d(TAG, "📞 准备调用 AudioRecorder.getInstance().start()");
            try {
                AudioRecorder recorder = AudioRecorder.getInstance();
                if (recorder != null) {
                    Context ctx = getApplicationContext();
                    recorder.start(ctx);
                    Log.d(TAG, "📞 start() 调用完成");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ AudioRecorder.start() 异常", e);
            }

            if (currentMode == Mode.MULTI) {
                multiRecordHandler.removeCallbacks(multiAutoStopRunnable);
                multiRecordHandler.postDelayed(multiAutoStopRunnable, MULTI_AUTO_STOP_RECORD_MS);
            }
        } else {
            multiRecordHandler.removeCallbacks(multiAutoStopRunnable);
            String status = "思考中";
            updateStatus(status, R.drawable.ic_processing);
            setStatusMotion(StatusMotion.PROCESSING);
            showArToast(status);
            if (ttsManager != null) {
                ttsManager.speakWithSound(status);
            }

            // 停止录音 (它内部会发送结束包)
            Log.d(TAG, "📞 准备调用 AudioRecorder.getInstance().stop()");
            AudioRecorder recorder = AudioRecorder.getInstance();
            if (recorder != null) {
                recorder.stop();
                Log.d(TAG, "📞 stop() 调用完成");
            }

            if (currentMode == Mode.MULTI) {
                isWaitingForQuestion = false;
                isModeLocked = true;
            }
        }
    }

    private void playMultiQaQuestionSubtitleWithPauses() {
        // 与功能二保持一致：开麦后先等待2秒，再以不规则节奏逐步出字。
        long baseDelay = MULTI_QA_SUBTITLE_START_DELAY_MS;

        postMultiQaSubtitleAt(baseDelay + 0L, "请", false);
        postMultiQaSubtitleAt(baseDelay + 260L, "请问", false);
        postMultiQaSubtitleAt(baseDelay + 620L, "请问这", false);
        postMultiQaSubtitleAt(baseDelay + 860L, "请问这个", false);
        postMultiQaSubtitleAt(baseDelay + 1260L, "请问这个药", false);
        postMultiQaSubtitleAt(baseDelay + 1660L, "请问这个药过期", false);
        postMultiQaSubtitleAt(baseDelay + 2060L, "请问这个药过期了", false);
        postMultiQaSubtitleAt(baseDelay + 2460L, "请问这个药过期了吗", false);
        postMultiQaSubtitleAt(baseDelay + 2860L, "请问这个药过期了吗？", true);
    }

    private void postMultiQaSubtitleAt(long delayMs, String text, boolean isFinal) {
        backendDemoHandler.postDelayed(() -> updateSubtitle(text, isFinal), delayMs);
    }

    /**
     * 处理模式切换逻辑
     * 
     * @param modeIndex 0=拍照识药, 1=实时字幕, 2=拍照追问
     */
    private void handleModeSwitch(int modeIndex) {
        // 如果当前有交互正在进行，禁止切换
        if (isModeLocked) {
            String msg = "请完成当前操作后再切换模式";
            updateStatus(msg);
            showArToast(msg);
            Log.w(TAG, "⚠️ 模式切换被阻止：当前交互未完成");
            return;
        }

        // 转换为枚举类型
        Mode newMode;
        switch (modeIndex) {
            case 0:
                newMode = Mode.PHOTO;
                break;
            case 1:
                newMode = Mode.SUBTITLE;
                break;
            case 2:
                newMode = Mode.MULTI;
                break;
            default:
                Log.e(TAG, "❌ 无效的模式索引: " + modeIndex);
                return;
        }

        // 如果是同一个模式，忽略
        if (currentMode == newMode) {
            Log.d(TAG, "⚠️ 已经是当前模式，忽略切换");
            return;
        }

        Log.d(TAG, "🔄 模式切换: " + currentMode.displayName + " -> " + newMode.displayName);

        // 重置当前状态
        resetState();

        // 切换模式
        currentMode = newMode;

        // 拍照识药模式下默认隐藏麦克风；其他模式显示麦克风
        animateMicVisibility(newMode == Mode.PHOTO ? 0f : 1f);

        // 更新渲染器的模式显示
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setCurrentMode(modeIndex);
        }

        // 更新UI反馈
        String statusMsg = "已切换到：" + newMode.displayName;
        updateStatus(statusMsg, R.drawable.ic_assistant);
        showArToast(statusMsg);
        if (ENABLE_FAKE_BACKEND_DEMO && newMode == Mode.MULTI) {
            showArToast("点按切换功能，触发仅支持悬停");
        }
        if (ttsManager != null) {
            ttsManager.speakWithSound(newMode.displayName + "模式");
        }

        // 震动反馈
        triggerVibration();

        Log.d(TAG, "✅ 模式切换完成: " + newMode.displayName);
    }

    /**
     * 重置所有交互状态
     * 在切换模式时调用，确保干净的状态
     */
    private void resetState() {
        Log.d(TAG, "🔄 resetState: 重置所有交互状态");
        multiRecordHandler.removeCallbacks(multiAutoStopRunnable);

        // 1. 停止录音
        if (isMicEnabled) {
            AudioRecorder recorder = AudioRecorder.getInstance();
            if (recorder != null) {
                recorder.stop();
            }
            isMicEnabled = false;
            if (RenderProcessor.getInstance() != null) {
                RenderProcessor.getInstance().setMicState(false);
            }
        }

        // 2. 解锁渲染器
        isAnalyzing = false;
        isWaitingForQuestion = false;
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setLocked(false);
            RenderProcessor.getInstance().setCloseProgress(0f);
        }

        // 3. 关闭卡片
        closeCardImmediately();

        // 4. 清除字幕
        mBindingPair.updateView(binding -> {
            if (binding.subtitleView != null) {
                binding.subtitleView.clearImmediate();
            }
            return null;
        });

        // 5. 清除多模态会话ID
        currentSectionId = null;
        WebSocketManager.getInstance().clearSectionId();

        // 6. 重置其他状态
        openPalmStartTime = 0;
        lastCardX = -1f;
        lastCardY = -1f;
        isVoiceCardShowing = false;
        setStatusMotion(StatusMotion.IDLE);

        Log.d(TAG, "✅ resetState: 状态重置完成");
    }

    private void animateMicVisibility(float targetProgress) {
        RenderProcessor processor = RenderProcessor.getInstance();
        if (processor == null) {
            return;
        }
        float target = Math.max(0f, Math.min(1f, targetProgress));
        float start = processor.getMicVisibilityProgress();
        if (Math.abs(start - target) < 0.001f) {
            processor.setMicVisibilityProgress(target);
            return;
        }
        if (micVisibilityAnimator != null) {
            micVisibilityAnimator.cancel();
        }
        micVisibilityAnimator = ValueAnimator.ofFloat(start, target);
        micVisibilityAnimator.setDuration(target < start ? 320L : 300L);
        if (target < start) {
            micVisibilityAnimator.setInterpolator(new DecelerateInterpolator(1.2f));
        } else {
            micVisibilityAnimator.setInterpolator(new OvershootInterpolator(1.2f));
        }
        micVisibilityAnimator.addUpdateListener(animation -> {
            if (RenderProcessor.getInstance() != null) {
                RenderProcessor.getInstance().setMicVisibilityProgress((float) animation.getAnimatedValue());
            }
        });
        micVisibilityAnimator.start();
    }

    /**
     * 立即关闭卡片（不带动画）
     */
    private void closeCardImmediately() {
        multiRecordHandler.removeCallbacks(multiAutoStopRunnable);
        clearCardActions();
        currentCardInteractionType = CardInteractionType.NONE;
        currentResultCardClosableByOpenPalm = false;
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot != null) {
                cardRoot.setVisibility(View.GONE);
            }
            return null;
        });
        cardPhase = CardPhase.HIDDEN;
        cardResultReady = false;
        isVoiceCardShowing = false;
        openPalmStartTime = 0;
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setCloseProgress(0f);
            RenderProcessor.getInstance().setCardBlockingGestureProgress(false);
        }
    }

    /**
     * 更新实时字幕（使用 mBindingPair 实现合目镜像）
     */
    private void updateSubtitle(String text, boolean isFinal) {
        runOnUiThread(() -> {
            // 使用 mBindingPair.updateView 更新字幕，这样会同时更新左右两眼
            mBindingPair.updateView(binding -> {
                if (binding.subtitleView != null) {
                    binding.subtitleView.updateSubtitle(text, isFinal);
                }
                return null;
            });

            // Log.d(TAG, "✅ [合目镜像] 字幕更新到左右两眼: " + text);
        });
    }

    // 适配 AR 眼镜的独立 Toast：在底部浮窗显示（区别于字幕），通过 mBindingPair 保证双眼同步
    private void showArToast(String message, int iconRes) {
        runOnUiThread(() -> mBindingPair.updateView(binding -> {
            if (binding.arToastView != null) {
                binding.arToastView.show(message, normalizeToastIconRes(message, iconRes));
            }
            return null;
        }));
    }

    private void showArToast(String message) {
        showArToast(message, 0);
    }

    public void notifyVideoRecordingStarted() {
        runOnUiThread(() -> {
            String msg = "录制已开始";
            showArToast(msg);
            if (ttsManager != null) {
                ttsManager.speakWithSound(msg);
            }
            Log.d(TAG, "相机本地录像已开始");

            // 20 秒后自动停止录像
            videoStopHandler.removeCallbacksAndMessages(null);
            videoStopHandler.postDelayed(() -> {
                if (ICameraManager.getInstance() != null) {
                    ICameraManager.getInstance().stopVideoRecording();
                }
                Log.d(TAG, "本地录像已自动停止（50秒）");
                String stopMsg = "录制已结束";
                showArToast(stopMsg);
                if (ttsManager != null) {
                    ttsManager.speakWithSound(stopMsg);
                }
            }, VIDEO_MAX_DURATION_MS);
        });
    }

    // ======= 调试：字幕模拟流 =======
    private void startSubtitleMockDemo() {
        if (subtitleDemoRunning)
            return;
        subtitleDemoRunning = true;
        if (subtitleDemoHandler == null) {
            subtitleDemoHandler = new Handler(Looper.getMainLooper());
        }

        // 开始前停顿，避免字幕早于用户说话出现。
        long baseDelay = SUBTITLE_DEMO_START_DELAY_MS;

        postSubtitleAt(baseDelay + 0, "妈", false);
        postSubtitleAt(baseDelay + 300, "妈，", false);
        postSubtitleAt(baseDelay + 600, "妈，这", false);
        postSubtitleAt(baseDelay + 900, "妈，这个", false);
        postSubtitleAt(baseDelay + 1200, "妈，这个药", false);
        postSubtitleAt(baseDelay + 1500, "妈，这个药医", false);
        postSubtitleAt(baseDelay + 1800, "妈，这个药医生", false);

        // 纠错演示：先错后改
        postSubtitleAt(baseDelay + 2100, "妈，这个药医生睡了", false);
        postSubtitleAt(baseDelay + 2700, "妈，这个药医生说了", false);

        postSubtitleAt(baseDelay + 3300, "妈，这个药医生说了，要", false);
        postSubtitleAt(baseDelay + 3900, "妈，这个药医生说了，要饭后", false);
        postSubtitleAt(baseDelay + 4500, "妈，这个药医生说了，要饭后吃。", true);

        // 下一句（停顿 1 秒）
        postSubtitleAt(baseDelay + 5500, "一次", false);
        postSubtitleAt(baseDelay + 5900, "一次吃", false);
        postSubtitleAt(baseDelay + 6300, "一次吃两", false);
        postSubtitleAt(baseDelay + 6700, "一次吃两粒", false);
        postSubtitleAt(baseDelay + 7200, "一次吃两粒，", false);
        postSubtitleAt(baseDelay + 7500, "一次吃两粒，别", false);
        postSubtitleAt(baseDelay + 7800, "一次吃两粒，别忘了", false);
        postSubtitleAt(baseDelay + 8100, "一次吃两粒，别忘了喝", false);
        postSubtitleAt(baseDelay + 8400, "一次吃两粒，别忘了喝温水", false);
        postSubtitleAt(baseDelay + 8700, "一次吃两粒，别忘了喝温水。", true);

        // 单次演示：播放一遍后停止，不再循环
        subtitleDemoHandler.postDelayed(() -> {
            // 使用 mBindingPair 清除字幕
            mBindingPair.updateView(binding -> {
                if (binding.subtitleView != null)
                    binding.subtitleView.clearImmediate();
                return null;
            });
            subtitleDemoRunning = false;
        }, baseDelay + 11000);
    }

    private void postSubtitleAt(long delayMs, String text, boolean isFinal) {
        if (subtitleDemoHandler == null)
            return;
        subtitleDemoHandler.postDelayed(() -> updateSubtitle(text, isFinal), delayMs);
    }

    private void stopSubtitleMockDemo() {
        subtitleDemoRunning = false;
        if (subtitleDemoHandler != null) {
            subtitleDemoHandler.removeCallbacksAndMessages(null);
        }
        // 使用 mBindingPair 清除字幕
        mBindingPair.updateView(binding -> {
            if (binding.subtitleView != null)
                binding.subtitleView.clearImmediate();
            return null;
        });
    }

    private void simulatePhotoRecognitionDemo() {
        setCardText("识别中", "正在分析图片…", getResources().getColor(R.color.primary_teal_light, null));
        demoHandler.postDelayed(() -> {
            String demoResult = "药品名称：感冒灵颗粒\n"
                    + "品牌：999\n"
                    + "规格：10g×9袋\n"
                    + "功能：解热镇痛\n"
                    + "主治：感冒引起的头痛、发热、鼻塞、流涕\n"
                    + "用法用量：开水冲服，一次1袋，一日3次";
            setStatusMotion(StatusMotion.IDLE);
            setCardText("药品信息", demoResult, getResources().getColor(R.color.status_success, null));
            Log.d(TAG, "🎬 [拍照模拟] 已输出识别结果");
        }, DEMO_PHOTO_DELAY_MS);
    }

    // 多模态问答模拟 - 第一步：拍照识别生产日期
    private void simulateMultiModalPhotoDemo() {
        setCardText("识别中", "正在分析图片…", getResources().getColor(R.color.primary_teal_light, null));
        demoHandler.postDelayed(() -> {
            isWaitingForQuestion = true;
            String promptMsg = "拍照完成";
            setStatusMotion(StatusMotion.IDLE);
            closeCardImmediately();
            isVoiceCardShowing = false;
            updateStatus(promptMsg, R.drawable.ic_microphone_active);
            showArToast(promptMsg);

            // 自动开启麦克风录音
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Log.d(TAG, "🎤 多模态模式：自动触发录音");
                handleMicToggle(true);
            }, 500);

            Log.d(TAG, "🎬 [多模态拍照模拟] 照片已识别，等待用户提问");
        }, DEMO_PHOTO_DELAY_MS);
    }

    // 多模态问答模拟 - 第二步：语音问答
    private void simulateMultiModalVoiceDemo() {
        if (multiDemoHandler == null) {
            multiDemoHandler = new Handler(Looper.getMainLooper());
        }

        // 标记正在等待用户关闭麦克风，允许触发回答显示
        isWaitingForQuestion = true;

        // 模拟用户提问的字幕 - 逐字显示，非均匀停顿
        setCardText("正在聆听", "识别您的语音中...", getResources().getColor(R.color.primary_teal_light, null));

        // 用户提问逐字显示 - 使用不规则间隔，更像真实语音识别
        // 1秒后开始显示字幕
        multiDemoHandler.postDelayed(() -> updateSubtitle("请", false), 2500);
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问", false), 2750); // 250ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这", false), 3080); // 330ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个", false), 3280); // 200ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个药", false), 3680); // 400ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个药过", false), 3880); // 200ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个药过期", false), 4280); // 400ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个药过期了", false), 4480); // 200ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个药过期了吗", false), 4880); // 400ms
        multiDemoHandler.postDelayed(() -> updateSubtitle("请问这个药过期了吗？", true), 5080); // 200ms

        // 问题显示完成后3秒自动停止录音
        multiDemoHandler.postDelayed(() -> {
            Log.d(TAG, "🎬 [多模态模拟] 3秒停顿后自动关闭麦克风");
            handleMicToggle(false);
        }, 8080); // 5080ms + 3000ms = 8080ms

        // 标记已接收用户提问，等待用户关闭麦克风

        Log.d(TAG, "🎬 [多模态语音模拟] 已显示用户提问，等待用户关闭麦克风");
    }

    // 多模态问答模拟 - 第三步：显示回答（当用户关闭麦克风时调用）
    private void simulateMultiModalAnswerDemo() {
        if (multiDemoHandler == null) {
            multiDemoHandler = new Handler(Looper.getMainLooper());
        }

        // 清除字幕
        mBindingPair.updateView(binding -> {
            if (binding.subtitleView != null)
                binding.subtitleView.clearImmediate();
            return null;
        });

        // 显示"分析中"过渡状态
        setCardText("分析中", "正在查询药品信息...", getResources().getColor(R.color.primary_teal_light, null));
        positionCardTopCenter();

        // 15秒后显示完整回答
        multiDemoHandler.postDelayed(() -> {
            String answer = "根据您刚才拍摄的栀子金花丸生产日期图片：\n"
                    + "生产日期：2025年7月21日\n"
                    + "有效期至：2028年7月20日\n"
                    + "今天是2026年1月30日，该药品还在有效期内，距离过期还有1年多时间。\n"
                    + "所以这个药没有过期，可以安全使用。";

            setStatusMotion(StatusMotion.IDLE);
            setCardText("药品有效期分析", answer, getResources().getColor(R.color.status_success, null));
            positionCardTopCenter();

            // 关闭等待问题状态
            isWaitingForQuestion = false;

            Log.d(TAG, "🎬 [多模态语音模拟] 已输出问答结果");
        }, 8000); // 8秒后显示回答
    }

    @Override
    protected void onDestroy() {
        currentStatusMotion = StatusMotion.IDLE;
        setStatusMotion(StatusMotion.IDLE);
        multiRecordHandler.removeCallbacks(multiAutoStopRunnable);
        if (subtitleDemoHandler != null) {
            subtitleDemoHandler.removeCallbacksAndMessages(null);
        }
        if (multiDemoHandler != null) {
            multiDemoHandler.removeCallbacksAndMessages(null);
        }
        subtitleDemoRunning = false;

        videoStopHandler.removeCallbacksAndMessages(null);
        localUiSimHandler.removeCallbacksAndMessages(null);
        localReminderHandler.removeCallbacksAndMessages(null);
        if (micVisibilityAnimator != null) {
            micVisibilityAnimator.cancel();
            micVisibilityAnimator = null;
        }

        // 停止本地录像
        if (ICameraManager.getInstance() != null) {
            ICameraManager.getInstance().stopVideoRecording();
        }

        // 释放TTS资源
        if (ttsManager != null) {
            ttsManager.release();
        }

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        runOnUiThread(() -> mBindingPair.updateView(binding -> {
            clearStatusMotion(binding);
            return null;
        }));
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentStatusMotion != StatusMotion.IDLE) {
            setStatusMotion(currentStatusMotion);
        }
    }

    /**
     * 将智能体阶段英文转换为中文
     */
    private String getStageText(String stage) {
        if (stage == null)
            return "处理中";
        switch (stage) {
            case "perception":
                return "感知层";
            case "understanding":
                return "理解层";
            case "decision":
                return "决策层";
            case "response":
                return "响应层";
            default:
                return "处理中";
        }
    }

    /**
     * 核心循环：更新 AR 视图
     * 被 ProcessorManager 调用
     */
    public void updateView(RenderData renderData, RecognizeTask recognizeTask) {
        // 1. 将数据传递给渲染层 (画光标、按钮)
        // if (renderData != null) {
        // Log.d(TAG, "🎨 updateView: 指尖=" + String.format("(%.3f, %.3f)",
        // renderData.getTipX(), renderData.getTipY()));
        // } else {
        // Log.w(TAG, "⚠️ updateView: renderData为null");
        // }

        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setRenderData(renderData);
        }
        if (renderData != null) {
            lastRenderData = renderData;
        } else {
        }

        if (renderData == null)
            return;

        if (handleInteractiveCardGesture(renderData)) {
            return;
        }

        // 2. 处理手势触发的【视觉识别】(HTTP 拍照)
        if (isAnalyzing) {
            final boolean[] isCardVisibleWhileAnalyzing = { false };
            mBindingPair.updateView(binding -> {
                View cardRoot = binding.includeArCard.getRoot();
                if (cardRoot != null) {
                    isCardVisibleWhileAnalyzing[0] = (cardRoot.getVisibility() == View.VISIBLE);
                }
                return null;
            });

            // Log.d(TAG, "🔄 [分析中] isAnalyzing=true, openPalm=" + renderData.isOpenPalm());
            // 如果正在分析中...
            if (renderData.isOpenPalm() && isCardVisibleWhileAnalyzing[0] && canCloseCurrentCardByOpenPalm()) {
                long now = System.currentTimeMillis();
                if (openPalmStartTime == 0)
                    openPalmStartTime = now;
                float holdProgress = Math.min(1f, (float) (now - openPalmStartTime) / CLOSE_HOLD_MS);
                RenderProcessor.getInstance().setCloseProgress(holdProgress);
                if (now - openPalmStartTime >= CLOSE_HOLD_MS) {
                    Log.d(TAG, "✋ [张手] 长按完成，关闭卡片");
                    closeCard();
                    return;
                }
            } else {
                openPalmStartTime = 0;
                RenderProcessor.getInstance().setCloseProgress(0f);
            }
            // 更新卡片位置：语音卡片保持顶部居中，视觉识别卡片跟随手指
            if (!isVoiceCardShowing) {
                updateCardPosition(renderData.getTipX(), renderData.getTipY());
            }

            // 检查是否有 HTTP 识别结果返回（过滤等待占位文案，避免覆盖等待GIF）
            if (recognizeTask != null && recognizeTask.getRecognizeResult() != null
                    && !isPendingBackendResult(recognizeTask.getRecognizeResult())) {
                runOnUiThread(() -> {
                    // 显示 HTTP 返回的图片识别结果
                    setCardText(recognizeTask.getRecognizeResult(), "视觉识别成功",
                            getResources().getColor(R.color.status_success, null));
                    triggerVibration();
                    // 不自动关闭，等待用户张手关闭
                });
            }

        } else {
            // 2.5. 通用张手关闭逻辑 - 适用于语音卡片等非视觉识别场景
            // 增加卡片可见性检查，避免重复触发关闭
            final boolean[] isCardVisible = { false };
            mBindingPair.updateView(binding -> {
                View cardRoot = binding.includeArCard.getRoot();
                if (cardRoot != null) {
                    isCardVisible[0] = (cardRoot.getVisibility() == View.VISIBLE);
                }
                return null;
            });

            if (isVoiceCardShowing && isCardVisible[0] && renderData.isOpenPalm() && canCloseCurrentCardByOpenPalm()) {
                long now = System.currentTimeMillis();
                if (openPalmStartTime == 0)
                    openPalmStartTime = now;
                float holdProgress = Math.min(1f, (float) (now - openPalmStartTime) / CLOSE_HOLD_MS);
                RenderProcessor.getInstance().setCloseProgress(holdProgress);
                if (now - openPalmStartTime >= CLOSE_HOLD_MS) {
                    Log.d(TAG, "✋ [张手] 长按完成，关闭语音卡片");
                    closeCard();
                    return;
                }
            } else {
                openPalmStartTime = 0;
                RenderProcessor.getInstance().setCloseProgress(0f);
            }

            // 3. 如果未处于分析状态，且触发了悬停 (isTriggered)
            // ============ 多模态模式：悬停关闭麦克风 ============
            if (renderData.isTriggered() && isMicEnabled && currentMode == Mode.MULTI && isWaitingForQuestion) {
                long now = System.currentTimeMillis();
                Log.d(TAG, "👆 [多模态录音] 检测到悬停触发，关闭麦克风");
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    lastTriggerTime = now;
                    handleMicToggle(false);
                    return;
                }
            }

            // ============ 拍照模式：悬停触发拍照 ============
            // 只有拍照识药和拍照追问模式才允许悬停触发拍照
            boolean canTriggerPhotoCapture = false;
            boolean blockedByMainUiControlHover = isMainUiControlHovering(renderData);
            if (currentMode == Mode.PHOTO) {
                canTriggerPhotoCapture = !isMicEnabled
                        && cardPhase == CardPhase.HIDDEN
                        && !blockedByMainUiControlHover;
            } else if (currentMode == Mode.MULTI) {
                canTriggerPhotoCapture = !isMicEnabled
                        && !isWaitingForQuestion
                        && currentSectionId == null
                        && cardPhase == CardPhase.HIDDEN
                        && !isModeLocked
                        && !blockedByMainUiControlHover;
            }

            if (renderData.isTriggered() && canTriggerPhotoCapture) {
                long now = System.currentTimeMillis();
                Log.d(TAG, "👆 [触发检测] isTriggered=true, mode=" + currentMode.displayName +
                        ", 冷却时间=" + (now - lastTriggerTime) + "ms");
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    Log.d(TAG, "✅ [触发成功] 冷却已过，开始分析");
                    RenderProcessor.getInstance().setLocked(true);
                    RenderProcessor.getInstance().setCloseProgress(0f);
                    openPalmStartTime = 0;
                    lastTriggerTime = now;
                    performPhotoCapture(renderData.getTipX(), renderData.getTipY(), "悬停触发");
                }
            }
        }
    }

    private boolean isMainUiControlHovering(RenderData renderData) {
        RenderProcessor processor = RenderProcessor.getInstance();
        if (processor != null && processor.isHoveringMainUiControl()) {
            return true;
        }

        // 回退：当渲染层状态尚未刷新时，仍用识别层的麦克风悬停信号兜底。
        return renderData != null && renderData.isMicHovered();
    }

    private boolean canCloseCurrentCardByOpenPalm() {
        return cardPhase == CardPhase.RESULT
                && currentCardInteractionType == CardInteractionType.RESULT_NOTIFICATION
                && currentResultCardClosableByOpenPalm;
    }

    // 更新底部状态栏文字（使用 mBindingPair 实现合目镜像）
    public void updateStatus(String msg) {
        updateStatus(msg, null);
    }

    public void updateStatus(String msg, Integer iconRes) {
        runOnUiThread(() -> {
            mBindingPair.updateView(binding -> {
                if (binding.tvStatus != null) {
                    binding.tvStatus.setText(msg);
                }

                if (iconRes != null && binding.ivStatusIcon != null) {
                    if (iconRes == R.drawable.ic_processing && currentStatusMotion == StatusMotion.PROCESSING) {
                        // processing 运动态：优先保持 GIF，避免被静态图标覆盖。
                        binding.ivStatusIcon.setImageTintList(null);
                        binding.ivStatusIcon.setImageResource(R.raw.loading_status);
                    } else {
                        binding.ivStatusIcon.setImageResource(iconRes);
                        applyStatusIconTint(binding.ivStatusIcon, iconRes);
                    }
                }

                boolean keepsMotion = iconRes != null
                        && (iconRes == R.drawable.ic_microphone_active || iconRes == R.drawable.ic_processing);
                if (!keepsMotion) {
                    clearStatusMotion(binding);
                    if (iconRes != null) {
                        currentStatusMotion = StatusMotion.IDLE;
                    }
                }
                return null;
            });

            Log.d(TAG, "✅ [合目镜像] 状态栏更新到左右两眼: " + msg);
        });

        // 朗读状态信息（仅朗读关键提示，避免过于繁琐）
        if (ttsManager != null && msg != null && !msg.isEmpty()) {
            // 只朗读关键信息，过滤掉冗余的状态提示
            if (msg.contains("失败") || msg.contains("错误") || msg.contains("完成") ||
                    msg.contains("成功") || msg.contains("开始")) {
                ttsManager.speak(msg);
            }
        }
    }

    // 初始化全屏绘图 View
    private void initCustomView() {
        customDrawView = new CustomDrawView(this);
        addContentView(customDrawView, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // 自定义 View 类
    public class CustomDrawView extends View {
        public CustomDrawView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (RenderProcessor.getInstance() != null) {
                RenderProcessor.getInstance().draw(canvas);
            }
            postInvalidateOnAnimation();
        }
    }

    /**
     * 单击右侧镜腿硬件键触发拍照/识别，复用与手势一致的流水线
     */
    private boolean tryTriggerCaptureViaHardware() {
        long now = System.currentTimeMillis();
        Log.d(TAG, "硬件触发检查开始");

        if (ENABLE_FAKE_BACKEND_DEMO) {
            return triggerFakeBackendDemo(now);
        }

        // ============ 模式检查 ============
        if (currentMode == Mode.SUBTITLE) {
            updateStatus("当前为实时字幕模式，无法拍照");
            Log.w(TAG, "硬件触发失败：当前为实时字幕模式");
            return false;
        }

        if (isMicEnabled) {
            updateStatus("麦克风占用中，暂不触发拍照");
            Log.w(TAG, "硬件触发失败：麦克风占用");
            return false;
        }

        if (isAnalyzing) {
            updateStatus("识别进行中，请稍候");
            Log.w(TAG, "硬件触发失败：识别进行中");
            return false;
        }

        if (now - lastTriggerTime <= COOLDOWN_MS) {
            updateStatus("触发过于频繁");
            Log.w(TAG, "硬件触发失败：防抖冷却中 gap=" + (now - lastTriggerTime) + "ms");
            return false;
        }

        if (lastRenderData == null) {
            updateStatus("尚未获取指尖位置，无法拍照");
            Log.w(TAG, "硬件触发失败：lastRenderData为null");
            return false;
        }

        Log.d(TAG, "硬件触发状态检查通过，准备拍照");
        performPhotoCapture(lastRenderData.getTipX(), lastRenderData.getTipY(), "硬件按键触发");
        return true;
    }

    private boolean triggerFakeBackendDemo(long now) {
        if (now - lastTriggerTime <= DEMO_TRIGGER_COOLDOWN_MS) {
            showArToast("演示触发过于频繁");
            return false;
        }

        lastTriggerTime = now;
        stopSubtitleMockDemo();
        backendDemoHandler.removeCallbacksAndMessages(null);
        closeCardImmediately();
        isAnalyzing = false;
        isModeLocked = false;
        isWaitingForQuestion = false;
        multiQaDemoActive = false;
        reminderDemoActive = false;
        smsDemoActive = false;
        activeMultiDemoSegment = DemoSegment.MULTI_MODAL_QA;
        setStatusMotion(StatusMotion.IDLE);

        DemoSegment segment;
        if (currentMode == Mode.PHOTO) {
            segment = DemoSegment.PHOTO_RECOGNITION;
        } else if (currentMode == Mode.SUBTITLE) {
            segment = DemoSegment.REALTIME_SUBTITLE;
        } else {
            segment = multiDemoSelection;
        }

        switch (segment) {
            case PHOTO_RECOGNITION:
                playDemoPhotoRecognition();
                break;
            case REALTIME_SUBTITLE:
                playDemoRealtimeSubtitle();
                break;
            case MULTI_MODAL_QA:
            case REMINDER_FLOW:
            case SMS_STATUS:
                startDemoMultiModalFlow(segment);
                break;
            default:
                return false;
        }

        return true;
    }

    private void cycleMultiDemoSelectionByHardware() {
        if (multiDemoSelection == DemoSegment.MULTI_MODAL_QA) {
            multiDemoSelection = DemoSegment.REMINDER_FLOW;
        } else if (multiDemoSelection == DemoSegment.REMINDER_FLOW) {
            multiDemoSelection = DemoSegment.SMS_STATUS;
        } else {
            multiDemoSelection = DemoSegment.MULTI_MODAL_QA;
        }

        String selectionLabel;
        if (multiDemoSelection == DemoSegment.MULTI_MODAL_QA) {
            selectionLabel = "拍照追问";
        } else if (multiDemoSelection == DemoSegment.REMINDER_FLOW) {
            selectionLabel = "智能提醒";
        } else {
            selectionLabel = "短信确认";
        }

        updateStatus("已切换到：" + selectionLabel, R.drawable.ic_assistant);
        showArToast("已切换：" + selectionLabel + "（点按切换）");
        Log.d(TAG, "点按切换多模态演示: " + selectionLabel);
    }

    private void playDemoPhotoRecognition() {
        isAnalyzing = true;
        isModeLocked = true;
        startCardSequence();
        positionCardTopCenter();
        updateStatus("图像已上传，等待后端识别", R.drawable.ic_processing);
        showArToast("已进入识别队列");

        long waitMs = 10_000L;

        backendDemoHandler.postDelayed(() -> {
            String displayText = buildPhotoInfoText(createDemoPhotoResultJson(), "识别结果为空");
            setStatusMotion(StatusMotion.IDLE);
            setCardText("药品信息", displayText, getResources().getColor(R.color.status_success, null));
            positionCardTopCenter();
            updateStatus("识别完成", R.drawable.ic_assistant);
            showArToast("识别结果已返回");
        }, waitMs);
    }

    private org.json.JSONObject createDemoPhotoResultJson() {
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("drug_name", "感冒灵颗粒");
            json.put("brand", "999");
            json.put("specification", "10g×9袋");
            json.put("function", "解热镇痛");
            json.put("indications", "感冒引起的头痛、发热、鼻塞、流涕");
            json.put("usage", "开水冲服，一次1袋，一日3次");
        } catch (org.json.JSONException ignored) {
        }
        return json;
    }

    private String buildPhotoInfoText(org.json.JSONObject json, String fallback) {
        if (json == null) {
            return fallback;
        }

        if (json.has("data")) {
            return json.optString("data", fallback);
        }

        StringBuilder sb = new StringBuilder();
        if (json.has("drug_name")) {
            sb.append("药品名称：").append(json.optString("drug_name", "未知")).append("\n");
        }
        if (json.has("brand")) {
            sb.append("品牌：").append(json.optString("brand", "未知")).append("\n");
        }
        if (json.has("specification")) {
            sb.append("规格：").append(json.optString("specification", "未知")).append("\n");
        }
        if (json.has("function")) {
            sb.append("功能：").append(json.optString("function", "未知")).append("\n");
        }
        if (json.has("indications")) {
            sb.append("主治：").append(json.optString("indications", "未知")).append("\n");
        }
        if (json.has("usage")) {
            sb.append("用法用量：").append(json.optString("usage", "未知"));
        }

        if (sb.length() > 0) {
            return sb.toString();
        }
        return json.optString("answer", fallback);
    }

    private void playDemoRealtimeSubtitle() {
        updateStatus("实时字幕通道已连接", R.drawable.ic_microphone_active);
        showArToast("开始接收语音字幕");

        String[] frames = new String[] {
                "妈", "妈，这", "妈，这个药", "妈，这个药医生说", "妈，这个药医生说了，要饭后吃。",
                "一次", "一次吃两粒", "一次吃两粒，别忘了喝温水。"
        };

        long start = SUBTITLE_DEMO_START_DELAY_MS;
        long step = 360L;
        for (int i = 0; i < frames.length; i++) {
            final boolean isFinal = (i == frames.length - 1);
            final String frame = frames[i];
            backendDemoHandler.postDelayed(() -> updateSubtitle(frame, isFinal), start + i * step);
        }

        backendDemoHandler.postDelayed(() -> showArToast("字幕输出完成"), start + frames.length * step + 200L);
    }

    private void playDemoMultiModalQa() {
        startDemoMultiModalFlow(DemoSegment.MULTI_MODAL_QA);
    }

    private void startDemoMultiModalFlow(DemoSegment segment) {
        isAnalyzing = false;
        updateStatus("多模态会话建立中", R.drawable.ic_processing);
        setStatusMotion(StatusMotion.PROCESSING);
        showArToast("拍照完成，正在自动开启录音");

        activeMultiDemoSegment = segment;
        multiQaDemoActive = segment == DemoSegment.MULTI_MODAL_QA;
        reminderDemoActive = segment == DemoSegment.REMINDER_FLOW;
        smsDemoActive = segment == DemoSegment.SMS_STATUS;

        backendDemoHandler.postDelayed(() -> {
            setStatusMotion(StatusMotion.IDLE);
            isWaitingForQuestion = true;
            isModeLocked = true;
            if (segment == DemoSegment.REMINDER_FLOW) {
                updateStatus("正在聆听提醒需求", R.drawable.ic_microphone_active);
            } else if (segment == DemoSegment.SMS_STATUS) {
                updateStatus("正在聆听短信需求", R.drawable.ic_microphone_active);
            } else {
                updateStatus("正在聆听提问", R.drawable.ic_microphone_active);
            }
            handleMicToggle(true);
        }, 800L);
    }

    private void playDemoReminderFlow() {
        startDemoMultiModalFlow(DemoSegment.REMINDER_FLOW);
    }

    private void playReminderQuestionSubtitleWithPauses() {
        long baseDelay = MULTI_QA_SUBTITLE_START_DELAY_MS;
        postMultiQaSubtitleAt(baseDelay + 0L, "请", false);
        postMultiQaSubtitleAt(baseDelay + 220L, "请你", false);
        postMultiQaSubtitleAt(baseDelay + 520L, "请你帮", false);
        postMultiQaSubtitleAt(baseDelay + 820L, "请你帮我", false);
        postMultiQaSubtitleAt(baseDelay + 1180L, "请你帮我设", false);
        postMultiQaSubtitleAt(baseDelay + 1480L, "请你帮我设置", false);
        postMultiQaSubtitleAt(baseDelay + 1880L, "请你帮我设置一下", false);
        postMultiQaSubtitleAt(baseDelay + 2320L, "请你帮我设置一下用药", false);
        postMultiQaSubtitleAt(baseDelay + 2760L, "请你帮我设置一下用药提醒", true);

        backendDemoHandler.postDelayed(() -> {
            if (reminderDemoActive && isMicEnabled) {
                handleMicToggle(false);
            }
        }, baseDelay + 4300L);
    }

    private void showReminderConfirmCard() {
        showReminderConfirmCard(buildDefaultReminderCardData());
    }

    private void showReminderConfirmCard(ReminderCardData rawData) {
        ReminderCardData data = normalizeReminderCardData(rawData);
        pendingReminderCardData = data;
        String primaryLine = "药品：" + data.drugName + "\n服用：" + data.usage;
        String metaLine = "信息来源：" + data.source + "｜提醒时间 " + data.reminderTime;

        showInteractiveConfirmCard("确认创建用药提醒",
                primaryLine,
                metaLine,
                "取消创建",
                this::onReminderCreateDeferred,
                "确认创建",
                this::onReminderCreateConfirmed,
                getResources().getColor(R.color.primary_teal_light, null));
        positionCardTopCenter();
        showArToast("已生成提醒方案，请确认是否创建");
    }

    private ReminderCardData buildDefaultReminderCardData() {
        return new ReminderCardData(
                "999感冒灵颗粒",
                "一次1袋，一日3次",
                "20:26",
                "照片识别");
    }

    private ReminderCardData normalizeReminderCardData(ReminderCardData raw) {
        ReminderCardData fallback = buildDefaultReminderCardData();
        if (raw == null) {
            return fallback;
        }

        String drugName = firstNonBlank(raw.drugName, fallback.drugName);
        String usage = firstNonBlank(raw.usage, fallback.usage);
        String reminderTime = firstNonBlank(raw.reminderTime, fallback.reminderTime);
        String source = firstNonBlank(raw.source, fallback.source);

        source = normalizeSingleSourceLabel(source);
        return new ReminderCardData(drugName, usage, reminderTime, source);
    }

    private ReminderCardData parseReminderCardDataFromJson(org.json.JSONObject json) {
        if (json == null) {
            return null;
        }

        String intent = json.optString("intent", "");
        boolean reminderIntent = intent.contains("提醒") || intent.toLowerCase(Locale.ROOT).contains("remind")
                || json.has("reminder") || json.has("reminder_time") || json.has("reminderTime");
        if (!reminderIntent) {
            return null;
        }

        org.json.JSONObject reminderObj = json.optJSONObject("reminder");
        org.json.JSONObject medicationObj = json.optJSONObject("medication");

        String drugName = firstNonBlank(
                json.optString("drug_name", null),
                json.optString("drugName", null),
                reminderObj != null ? reminderObj.optString("drug_name", null) : null,
                reminderObj != null ? reminderObj.optString("drugName", null) : null,
                medicationObj != null ? medicationObj.optString("name", null) : null);

        String usage = firstNonBlank(
                json.optString("usage", null),
                json.optString("dosage", null),
                json.optString("dose", null),
                reminderObj != null ? reminderObj.optString("usage", null) : null,
                reminderObj != null ? reminderObj.optString("dosage", null) : null,
                reminderObj != null ? reminderObj.optString("dose", null) : null,
                medicationObj != null ? medicationObj.optString("usage", null) : null);

        String reminderTime = firstNonBlank(
                json.optString("reminder_time", null),
                json.optString("reminderTime", null),
                json.optString("time", null),
                reminderObj != null ? reminderObj.optString("reminder_time", null) : null,
                reminderObj != null ? reminderObj.optString("reminderTime", null) : null,
                reminderObj != null ? reminderObj.optString("time", null) : null);

        String source = firstNonBlank(
                json.optString("source", null),
                json.optString("data_source", null),
                reminderObj != null ? reminderObj.optString("source", null) : null,
                reminderObj != null ? reminderObj.optString("data_source", null) : null);

        if (isBlank(drugName) && isBlank(usage) && isBlank(reminderTime)) {
            return null;
        }
        return new ReminderCardData(drugName, usage, reminderTime, source);
    }

    private String normalizeSingleSourceLabel(String source) {
        if (isBlank(source)) {
            return "照片识别";
        }

        String normalized = source.trim();
        String[] delimiters = new String[] { "|", "/", "、", ",", "，" };
        for (String delimiter : delimiters) {
            int idx = normalized.indexOf(delimiter);
            if (idx > 0) {
                normalized = normalized.substring(0, idx).trim();
                break;
            }
        }
        return normalized.isEmpty() ? "照片识别" : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void playDemoSmsStatus() {
        startDemoMultiModalFlow(DemoSegment.SMS_STATUS);
    }

    private void showSmsConfirmCard() {
        isVoiceCardShowing = true;
        isModeLocked = true;
        clearCardActions();
        showInteractiveConfirmCard("发送短信确认",
                "收件人：儿子（18983810096）",
                "内容：999感冒灵颗粒快吃完了，帮我买一点回来",
                "取消",
                this::onSmsSendCancelled,
                "发送",
                this::onSmsSendConfirmed,
                getResources().getColor(R.color.primary_teal_light, null));
        positionCardTopCenter();
        showArToast("已生成短信内容，请确认发送");
    }

    private void playSmsQuestionSubtitleWithPauses() {
        long baseDelay = MULTI_QA_SUBTITLE_START_DELAY_MS;
        // 前半部分 360ms，后半部分 600ms，逐渐放慢。
        String[] frames = new String[] {
                "告", "告诉", "告诉我", "告诉我儿子", "告诉我儿子，这个药",
                "告诉我儿子，这个药快吃完了", "告诉我儿子，这个药快吃完了，让他帮我买", "告诉我儿子，这个药快吃完了，让他帮我买一点回来"
        };
        long[] stepArray = new long[] { 360, 360, 360, 360, 600, 800, 800, 800 };
        long accumulatedTime = baseDelay;
        for (int i = 0; i < frames.length; i++) {
            final boolean isFinal = (i == frames.length - 1);
            final String frame = frames[i];
            postMultiQaSubtitleAt(accumulatedTime, frame, isFinal);
            accumulatedTime += stepArray[i];
        }

        backendDemoHandler.postDelayed(() -> {
            if (smsDemoActive && isMicEnabled) {
                handleMicToggle(false);
            }
        }, accumulatedTime + 1400L);
    }

    private String buildConfirmCardContent(String primaryLine, String metaLine, String secondaryAction,
            String primaryAction) {
        StringBuilder sb = new StringBuilder();
        sb.append(primaryLine);
        if (metaLine != null && !metaLine.trim().isEmpty()) {
            sb.append("\n").append(metaLine.trim());
        }
        sb.append("\n\n").append("[").append(secondaryAction).append("]   [").append(primaryAction).append("]");
        return sb.toString();
    }

    /**
     * 通用拍照上传方法 - 被悬停触发和硬件按键触发共用
     */
    private void performPhotoCapture(float tipX, float tipY, String source) {
        // ============ 模式检查 ============
        // 实时字幕模式：禁止拍照
        if (currentMode == Mode.SUBTITLE) {
            String msg = "当前为实时字幕模式，无法拍照";
            updateStatus(msg);
            showArToast(msg);
            Log.w(TAG, "⚠️ 拍照被阻止：当前为实时字幕模式");
            return;
        }

        isAnalyzing = true;
        isModeLocked = true; // 锁定模式，禁止切换
        triggerVibration();

        if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.PHOTO) {
            Log.d(TAG, "[" + source + "] 拍照识药模拟：按真实链路展示等待与结果");
            backendDemoHandler.removeCallbacksAndMessages(null);
            playDemoPhotoRecognition();
            return;
        }

        if (ENABLE_FAKE_BACKEND_DEMO && currentMode == Mode.MULTI) {
            Log.d(TAG, "[" + source + "] 多模态模拟：按当前选择触发子功能=" + multiDemoSelection);
            backendDemoHandler.removeCallbacksAndMessages(null);
            closeCardImmediately();
            isAnalyzing = false;
            isModeLocked = false;
            if (RenderProcessor.getInstance() != null) {
                RenderProcessor.getInstance().setLocked(false);
                RenderProcessor.getInstance().setCloseProgress(0f);
            }
            openPalmStartTime = 0;

            startDemoMultiModalFlow(multiDemoSelection);
            return;
        }

        RecognizeTask.HighResYUVCache yuvCache = RecognizeTask.getLatestHighResYUV();
        Log.d(TAG, "📷 [" + source + "] YUV缓存: " + (yuvCache != null ? "有效" : "为null"));

        if (yuvCache == null) {
            updateStatus("未获取到高清帧，稍后重试");
            Log.w(TAG, "📷 [" + source + "] 失败：YUV缓存为null");
            isAnalyzing = false;
            isModeLocked = false;
            return;
        }

        // ============ 多模态模式：生成sectionId ============
        if (currentMode == Mode.MULTI) {
            currentSectionId = java.util.UUID.randomUUID().toString();
            Log.d(TAG, "🆔 多模态模式：生成 sectionId=" + currentSectionId);
            // 设置到 WebSocketManager，用于后续图片和音频连接
            WebSocketManager.getInstance().setSectionId(currentSectionId);
        }

        Log.d(TAG, "📷 [" + source + "] 开始生成完整高清图");
        Bitmap fullHighResBitmap = convertFullYUVToRGB(yuvCache);
        if (fullHighResBitmap != null) {
            Log.d(TAG, "📷 [" + source + "] 完整高清图生成成功: " + fullHighResBitmap.getWidth() + "x"
                    + fullHighResBitmap.getHeight());
            Bitmap saveCopy = fullHighResBitmap.copy(Bitmap.Config.ARGB_8888, true);
            // 暂时注释：不保存药品识别本地图片
            // saveDebugImage(saveCopy, tipX, tipY);

            startCardSequence();

            final long requestStartTime = System.currentTimeMillis(); // 记录请求开始时间
            lastImageSendTime = requestStartTime; // 记录以便端到端计时
            Log.i(TAG, "📸 [图片识别请求] 开始时间: " + requestStartTime);

            ProcessorManager.normalExecutor.execute(() -> {
                try {
                    Log.e(TAG, "🔴🔴🔴 [" + source + "] 线程池执行！准备调用 SendRemoteProcessor");
                    Log.d(TAG, "📷 [" + source + "] 线程池: 开始上传");
                    // 传递手指坐标用于裁剪优化
                    RecognizeTask uploadTask = new RecognizeTask(fullHighResBitmap, tipX, tipY);
                    Log.e(TAG, "🔴🔴🔴 [" + source + "] RecognizeTask 已创建，tipX=" + tipX + ", tipY=" + tipY);
                    SendRemoteProcessor processor = new SendRemoteProcessor();
                    Log.e(TAG, "🔴🔴🔴 [" + source + "] SendRemoteProcessor 已创建，即将调用 process()");

                    System.err.println(">>> MainActivity: 准备调用 processor.process()");
                    System.err.println(">>> uploadTask: " + uploadTask);
                    System.err.println(">>> processor: " + processor);

                    final RecognizeTask[] resultHolder = new RecognizeTask[1];
                    try {
                        System.err.println(">>> MainActivity: 调用 process() - BEFORE");
                        resultHolder[0] = processor.process(uploadTask);
                        System.err.println(">>> MainActivity: 调用 process() - AFTER");
                    } catch (Throwable e) {
                        System.err.println(">>> MainActivity: process() 抛出异常: " + e.getMessage());
                        e.printStackTrace();
                        Log.e(TAG, "❌❌❌ process() 异常", e);
                    }

                    RecognizeTask result = resultHolder[0];
                    Log.e(TAG, "🔴🔴🔴 [" + source + "] process() 返回！result=" + result);

                    long responseTime = System.currentTimeMillis();
                    long totalElapsed = responseTime - requestStartTime;
                    Log.i(TAG, "✅ [图片识别完成] 响应时间: " + responseTime + ", 总耗时: " + totalElapsed + "ms ("
                            + String.format(Locale.US, "%.2f", totalElapsed / 1000.0) + "秒)");
                    Log.i(TAG, "📷 [" + source + "] 高清全图上传完成, result=" + result);

                    runOnUiThread(() -> {
                        // ============ 根据模式处理结果 ============
                        if (currentMode == Mode.PHOTO) {
                            // 拍照识药模式：如果只是“已推送等待处理”的占位文案，则保持等待GIF，不覆盖卡片。
                            if (result != null && result.getRecognizeResult() != null) {
                                String recognizeText = result.getRecognizeResult();
                                if (isPendingBackendResult(recognizeText)) {
                                    Log.d(TAG, "⏳ [拍照识药] 后端处理中，保持等待GIF: " + recognizeText);
                                    return;
                                }

                                setStatusMotion(StatusMotion.IDLE);
                                Log.d(TAG, "📷 [拍照识药] 识别结果: " + recognizeText);
                                setCardText("识别结果", recognizeText,
                                        getResources().getColor(R.color.status_success, null));
                            } else {
                                setStatusMotion(StatusMotion.IDLE);
                                Log.w(TAG, "📷 [拍照识药] 结果为空");
                                setCardText("识别失败", "无法识别图片内容",
                                        getResources().getColor(R.color.status_error, null));
                            }
                        } else if (currentMode == Mode.MULTI) {
                            // 多模态进入提问阶段：释放视觉识别锁，允许后续手势结束录音。
                            isAnalyzing = false;
                            isModeLocked = false;
                            if (RenderProcessor.getInstance() != null) {
                                RenderProcessor.getInstance().setLocked(false);
                                RenderProcessor.getInstance().setCloseProgress(0f);
                            }
                            openPalmStartTime = 0;
                            setStatusMotion(StatusMotion.IDLE);
                            // 多模态模式：用状态栏/Toast 承载过渡提示，避免生成需手动关闭的临时卡片。
                            isWaitingForQuestion = true;
                            String promptMsg = "拍照完成";
                            closeCardImmediately();
                            isVoiceCardShowing = false;
                            updateStatus(promptMsg, R.drawable.ic_microphone_active);
                            showArToast(promptMsg);

                            // 自动开启麦克风录音
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                Log.d(TAG, "🎤 多模态模式：自动触发录音");
                                // 模拟麦克风点击，开启录音
                                handleMicToggle(true);
                            }, 500); // 延迟500ms，确保卡片显示完成
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "��🔴 [" + source + "] 上传异常！异常信息: " + e.getMessage());
                    Log.e(TAG, "🔴🔴🔴 [" + source + "] 异常堆栈:", e);
                    runOnUiThread(() -> {
                        setStatusMotion(StatusMotion.IDLE);
                        setCardText("识别失败", "网络错误，请重试",
                                getResources().getColor(R.color.status_error, null));
                    });
                } finally {
                    if (fullHighResBitmap != null && !fullHighResBitmap.isRecycled()) {
                        fullHighResBitmap.recycle();
                    }
                    Log.d(TAG, "📷 [" + source + "] 完成，等待张手关闭");
                }
            });
        } else {
            updateStatus("未获取到高清帧，稍后重试");
            Log.w(TAG, "📷 [" + source + "] 失败：完整高清图生成失败");
            isAnalyzing = false;
        }
    }

    /**
     * 将卡片定位到顶部居中位置（用于语音识别等非视觉场景）
     */
    private void positionCardTopCenter() {
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot == null) {
                return null;
            }

            // 确保卡片可见
            if (cardRoot.getVisibility() != View.VISIBLE) {
                cardRoot.setVisibility(View.VISIBLE);
            }

            // 获取父容器尺寸
            int parentW = binding.getRoot().getWidth();
            int parentH = binding.getRoot().getHeight();
            if (parentW == 0 || parentH == 0) {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                parentW = metrics.widthPixels;
                parentH = metrics.heightPixels;
            }

            // 获取卡片尺寸
            int cardW = cardRoot.getWidth();
            if (cardW == 0) {
                cardW = cardRoot.getMeasuredWidth();
            }
            if (cardW == 0) {
                // 默认估算宽度
                cardW = (int) (parentW * 0.4f);
            }

            // 计算顶部居中位置
            float centerX = (parentW - cardW) / 2.0f;
            float topMargin = parentH * 0.15f; // 距离顶部 15%

            // 使用 LayoutParams 定位
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cardRoot.getLayoutParams();
            if (params == null) {
                params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
            }
            params.leftMargin = (int) centerX;
            params.topMargin = (int) topMargin;
            cardRoot.setLayoutParams(params);

            Log.d(TAG, "📍 语音卡片定位: 顶部居中 (" + (int) centerX + ", " + (int) topMargin + ")");
            return null;
        });
    }

    // 更新 AR 卡片位置（视觉识别场景：固定中间偏右，不再跟随手指）
    private void updateCardPosition(float tipX, float tipY) {
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot == null) {
                Log.w(TAG, "⚠️ 卡片为null，无法更新位置");
                return null;
            }

            if (cardRoot.getVisibility() != View.VISIBLE) {
                Log.d(TAG, "⚠️ 卡片不可见，跳过位置更新");
                return null;
            }

            // 强制重新测量卡片尺寸，确保获取到最新内容的实际大小
            cardRoot.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

            // 使用实际父容器尺寸而非整机分辨率，避免坐标超出眼镜视区
            int parentW = binding.getRoot().getWidth();
            int parentH = binding.getRoot().getHeight();
            if (parentW == 0 || parentH == 0) {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                parentW = metrics.widthPixels;
                parentH = metrics.heightPixels;
            }

            // 获取卡片测量后的实际尺寸
            int cardW = cardRoot.getMeasuredWidth();
            int cardH = cardRoot.getMeasuredHeight();

            // 固定位置：画面中心，横向轻微右移（约 +6% 屏宽）
            float centerX = parentW * 0.56f;
            float centerY = parentH * 0.50f;
            float finalX = centerX - cardW / 2f;
            float finalY = centerY - cardH / 2f;

            // 确保有足够空间显示完整卡片内容，只在超出屏幕时才限制
            if (cardW > 0 && finalX + cardW > parentW) {
                finalX = Math.max(0, parentW - cardW);
            }
            if (cardH > 0 && finalY + cardH > parentH) {
                finalY = Math.max(0, parentH - cardH);
            }

            // 确保不会超出左上边界
            finalX = Math.max(0, finalX);
            finalY = Math.max(0, finalY);

            // 记录上次位置用于平滑
            lastCardX = finalX;
            lastCardY = finalY;

            // 使用 LayoutParams 定位（setX/setY 在 FrameLayout 中不工作）
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cardRoot.getLayoutParams();
            if (params == null) {
                params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
            }
            params.leftMargin = (int) finalX;
            params.topMargin = (int) finalY;
            params.gravity = android.view.Gravity.NO_GRAVITY; // 禁用重力，使用 margin 定位
            cardRoot.setLayoutParams(params);

            Log.d(TAG, "📍 卡片固定定位: center-right (" + (int) finalX + ", " + (int) finalY + ")");
            return null;

        });

    }

    // 显示 AR 卡片 (初始状态)
    private void startCardSequence() {
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot != null) {
                cardResultReady = false;
                cardPhase = CardPhase.WAITING;
                lastCardX = -1f;
                lastCardY = -1f;
                cardRoot.setVisibility(View.VISIBLE);
                cardRoot.bringToFront();

                if (binding.cardTransitionOverlay != null) {
                    binding.cardTransitionOverlay.playMaterialize(cardRoot, () -> {
                        android.view.animation.Animation settle = android.view.animation.AnimationUtils.loadAnimation(
                                this, R.anim.card_fade_in);
                        cardRoot.startAnimation(settle);
                        // 若结果已先到达，避免回调把结果文本覆盖回等待态。
                        if (!cardResultReady) {
                            binding.includeArCard.tvCardContent.setVisibility(View.GONE);
                            if (binding.includeArCard.ivCardWaiting != null) {
                                binding.includeArCard.ivCardWaiting.setVisibility(View.VISIBLE);
                            }
                        } else {
                            Log.d(TAG, "⏭️ 跳过等待态回调：结果已到达");
                        }
                    });
                } else {
                    // 回退：overlay 不可用时继续使用现有入场动画
                    android.view.animation.Animation fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                            this, R.anim.card_fade_in);
                    cardRoot.startAnimation(fadeIn);
                }

                Log.d(TAG, "✅ [卡片已显示] via mBindingPair");
                setCardWaitingState("分析中", getResources().getColor(R.color.primary_teal_light, null));

                // 更新状态图标为处理中
                updateStatus("分析中", R.drawable.ic_processing);
                setStatusMotion(StatusMotion.PROCESSING);
            } else {
                Log.e(TAG, "❌ 卡片为null，无法显示");
            }
            return null;
        });
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setCardBlockingGestureProgress(true);
        }
    }

    private void setCardWaitingState(String title, int color) {
        mBindingPair.updateView(binding -> {
            if (binding.includeArCard.tvCardTitle != null && binding.includeArCard.tvCardContent != null) {
                if (cardResultReady) {
                    Log.d(TAG, "⏭️ 跳过等待态渲染：结果已到达");
                    return null;
                }
                currentCardInteractionType = CardInteractionType.NONE;
                currentResultCardClosableByOpenPalm = false;
                binding.includeArCard.tvCardTitle.setText(title);
                binding.includeArCard.tvCardTitle.setTextColor(color);
                binding.includeArCard.tvCardContent.setVisibility(View.GONE);
                if (binding.includeArCard.tvCardMeta != null) {
                    binding.includeArCard.tvCardMeta.setVisibility(View.GONE);
                }
                if (binding.includeArCard.vCardActionDivider != null) {
                    binding.includeArCard.vCardActionDivider.setVisibility(View.GONE);
                }
                if (binding.includeArCard.layoutCardActions != null) {
                    binding.includeArCard.layoutCardActions.setVisibility(View.GONE);
                }
                if (binding.includeArCard.ivCardWaiting != null) {
                    binding.includeArCard.ivCardWaiting.setVisibility(View.VISIBLE);
                }
            }
            return null;
        });
    }

    // 关闭 AR 卡片
    private void closeCard() {
        multiRecordHandler.removeCallbacks(multiAutoStopRunnable);
        clearCardActions();
        currentCardInteractionType = CardInteractionType.NONE;
        currentResultCardClosableByOpenPalm = false;
        if (isMicEnabled) {
            AudioRecorder recorder = AudioRecorder.getInstance();
            if (recorder != null) {
                recorder.stop();
            }
            isMicEnabled = false;
            if (RenderProcessor.getInstance() != null) {
                RenderProcessor.getInstance().setMicState(false);
            }
        }

        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot != null) {
                Runnable closeAction = () -> {
                    cardRoot.setVisibility(View.GONE);
                    // 卡片关闭成功：播放提示音
                    if (ttsManager != null) {
                        ttsManager.speakWithSound("卡片已关闭");
                    }
                };

                if (binding.cardTransitionOverlay != null) {
                    binding.cardTransitionOverlay.playDissolve(cardRoot, closeAction);
                } else {
                    // 回退：overlay 不可用时继续使用现有退场动画
                    android.view.animation.Animation fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                            this, R.anim.card_fade_out);
                    fadeOut.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(android.view.animation.Animation animation) {
                        }

                        @Override
                        public void onAnimationEnd(android.view.animation.Animation animation) {
                            closeAction.run();
                        }

                        @Override
                        public void onAnimationRepeat(android.view.animation.Animation animation) {
                        }
                    });
                    cardRoot.startAnimation(fadeOut);
                }
                Log.d(TAG, "✅ [卡片已关闭] via mBindingPair");
            }
            return null;
        });
        isAnalyzing = false;
        isModeLocked = false; // 解锁模式
        cardPhase = CardPhase.HIDDEN;
        cardResultReady = false;
        isVoiceCardShowing = false;
        RenderProcessor.getInstance().setLocked(false);
        RenderProcessor.getInstance().setCloseProgress(0f);
        RenderProcessor.getInstance().setCardBlockingGestureProgress(false);
        openPalmStartTime = 0;
        lastCardX = -1f;
        lastCardY = -1f;
        isWaitingForQuestion = false;
        currentSectionId = null;
        WebSocketManager.getInstance().clearSectionId();
        setStatusMotion(StatusMotion.IDLE);
        updateStatus("系统就绪", R.drawable.ic_assistant);
        triggerVibration();
    }

    private void setStatusMotion(StatusMotion motion) {
        currentStatusMotion = motion;
        runOnUiThread(() -> mBindingPair.updateView(binding -> {
            applyStatusMotion(binding, motion);
            return null;
        }));
    }

    private void applyStatusMotion(ActivityMainBinding binding, StatusMotion motion) {
        clearStatusMotion(binding);

        if (motion == StatusMotion.IDLE) {
            return;
        }

        if (binding.statusIndicator != null) {
            android.view.animation.Animation breathe = android.view.animation.AnimationUtils.loadAnimation(this,
                    R.anim.pulse);
            binding.statusIndicator.startAnimation(breathe);
        }

        if (motion == StatusMotion.PROCESSING && binding.ivStatusIcon != null) {
            // 播放加载 GIF，取代旋转动画
            binding.ivStatusIcon.clearAnimation();
            binding.ivStatusIcon.setImageTintList(null);
            binding.ivStatusIcon.setImageResource(R.raw.loading_status);
        }
    }

    private void clearStatusMotion(ActivityMainBinding binding) {
        if (binding.statusIndicator != null) {
            binding.statusIndicator.clearAnimation();
            binding.statusIndicator.setAlpha(1f);
        }
        if (binding.ivStatusIcon != null) {
            binding.ivStatusIcon.clearAnimation();
        }
    }

    // 设置卡片文字
    private void setCardText(String title, String content, int color) {
        setCardText(title, content, color, true);
    }

    private void setCardText(String title, String content, int color, boolean closableByOpenPalm) {
        cardResultReady = true;
        cardPhase = CardPhase.RESULT;
        ParsedCardContent parsedContent = parseCardContent(content);
        mBindingPair.updateView(binding -> {
            if (binding.includeArCard.tvCardTitle != null && binding.includeArCard.tvCardContent != null) {
                View cardRoot = binding.includeArCard.getRoot();
                if (cardRoot != null) {
                    if (cardRoot.getVisibility() != View.VISIBLE) {
                        cardRoot.setVisibility(View.VISIBLE);
                    }
                    cardRoot.bringToFront();
                    // 避免淡出动画残留导致卡片可见但完全透明。
                    cardRoot.clearAnimation();
                    cardRoot.setAlpha(1f);
                    cardRoot.setScaleX(1f);
                    cardRoot.setScaleY(1f);
                    cardRoot.setTranslationX(0f);
                    cardRoot.setTranslationY(0f);
                }

                // 结果到达：隐藏等待 GIF，确保内容文字可见
                if (binding.includeArCard.ivCardWaiting != null) {
                    binding.includeArCard.ivCardWaiting.setVisibility(View.GONE);
                }
                binding.includeArCard.tvCardContent.setVisibility(View.VISIBLE);
                binding.includeArCard.tvCardTitle.setText(title);
                binding.includeArCard.tvCardTitle.setTextColor(color);
                binding.includeArCard.tvCardContent.setText(parsedContent.primaryText);

                if (binding.includeArCard.tvCardMeta != null) {
                    if (parsedContent.hasMeta()) {
                        binding.includeArCard.tvCardMeta.setText(parsedContent.metaText);
                        binding.includeArCard.tvCardMeta.setVisibility(View.VISIBLE);
                    } else {
                        binding.includeArCard.tvCardMeta.setVisibility(View.GONE);
                    }
                }

                if (binding.includeArCard.layoutCardActions != null
                        && binding.includeArCard.vCardActionDivider != null) {
                    if (parsedContent.hasActions()) {
                        currentCardInteractionType = CardInteractionType.OPTION_SELECTION;
                        currentResultCardClosableByOpenPalm = false;
                        binding.includeArCard.vCardActionDivider.setVisibility(View.VISIBLE);
                        binding.includeArCard.layoutCardActions.setVisibility(View.VISIBLE);
                        if (binding.includeArCard.tvCardActionSecondary != null) {
                            binding.includeArCard.tvCardActionSecondary.setText(parsedContent.secondaryAction);
                        }
                        if (binding.includeArCard.tvCardActionPrimary != null) {
                            binding.includeArCard.tvCardActionPrimary.setText(parsedContent.primaryAction);
                        }
                        applyCardActionEnabledState(binding, hasPendingCardActions() && !isCardActionInProgress);
                    } else {
                        currentCardInteractionType = CardInteractionType.RESULT_NOTIFICATION;
                        currentResultCardClosableByOpenPalm = closableByOpenPalm;
                        binding.includeArCard.vCardActionDivider.setVisibility(View.GONE);
                        binding.includeArCard.layoutCardActions.setVisibility(View.GONE);
                        clearCardActions();
                    }
                }

                // 根据标题设置图标
                android.widget.ImageView ivCardIcon = binding.includeArCard.ivCardIcon;
                if (ivCardIcon != null) {
                    if (title.contains("分析") || title.contains("处理")) {
                        ivCardIcon.setImageResource(R.drawable.ic_processing);
                    } else if (title.contains("成功") || title.contains("完成")) {
                        ivCardIcon.setImageResource(R.drawable.ic_assistant);
                    } else {
                        ivCardIcon.setImageResource(R.drawable.ic_assistant);
                    }

                    try {
                        ivCardIcon.setColorFilter(color);
                    } catch (Exception ignored) {
                    }
                }

                Log.d(TAG, "✅ [卡片文本已更新] title=" + title);
            }
            return null;
        });

        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setCardBlockingGestureProgress(cardPhase != CardPhase.HIDDEN);
        }

        // 使用TTS朗读卡片内容
        if (ttsManager != null && content != null && !content.isEmpty()) {
            // 朗读格式：标题，内容
            String speakText = title + "。" + parsedContent.primaryText;
            ttsManager.speak(speakText);
        }
    }

    private boolean isPendingBackendResult(String text) {
        if (text == null)
            return false;
        return text.contains("等待服务器处理")
                || text.contains("已成功推送")
                || text.contains("后端处理中");
    }

    private static final Pattern CARD_ACTION_PATTERN = Pattern.compile("^\\[(.+?)]\\s+\\[(.+?)]$");

    private static class ParsedCardContent {
        final String primaryText;
        final String metaText;
        final String secondaryAction;
        final String primaryAction;

        ParsedCardContent(String primaryText, String metaText, String secondaryAction, String primaryAction) {
            this.primaryText = primaryText;
            this.metaText = metaText;
            this.secondaryAction = secondaryAction;
            this.primaryAction = primaryAction;
        }

        boolean hasMeta() {
            return metaText != null && !metaText.isEmpty();
        }

        boolean hasActions() {
            return secondaryAction != null && !secondaryAction.isEmpty()
                    && primaryAction != null && !primaryAction.isEmpty();
        }
    }

    private ParsedCardContent parseCardContent(String content) {
        if (content == null) {
            return new ParsedCardContent("", null, null, null);
        }

        String normalized = content.trim();
        int actionStart = normalized.lastIndexOf("\n\n[");
        if (actionStart < 0) {
            return new ParsedCardContent(normalized, null, null, null);
        }

        String actionLine = normalized.substring(actionStart + 2).trim();
        Matcher matcher = CARD_ACTION_PATTERN.matcher(actionLine);
        if (!matcher.matches()) {
            return new ParsedCardContent(normalized, null, null, null);
        }

        String secondaryAction = matcher.group(1).trim();
        String primaryAction = matcher.group(2).trim();

        String body = normalized.substring(0, actionStart).trim();
        int metaLineIdx = body.lastIndexOf('\n');
        if (metaLineIdx > 0 && metaLineIdx < body.length() - 1) {
            String primaryText = body.substring(0, metaLineIdx).trim();
            String metaText = body.substring(metaLineIdx + 1).trim();
            return new ParsedCardContent(primaryText, metaText, secondaryAction, primaryAction);
        }

        return new ParsedCardContent(body, null, secondaryAction, primaryAction);
    }

    private void bindCardActionListeners() {
        mBindingPair.updateView(binding -> {
            if (binding.includeArCard.tvCardActionSecondary != null) {
                binding.includeArCard.tvCardActionSecondary.setOnClickListener(v -> executeCardAction(false));
            }
            if (binding.includeArCard.tvCardActionPrimary != null) {
                binding.includeArCard.tvCardActionPrimary.setOnClickListener(v -> executeCardAction(true));
            }
            applyCardActionEnabledState(binding, false);
            return null;
        });
    }

    private void bindCardActions(CardActionHandler secondaryAction, CardActionHandler primaryAction) {
        pendingSecondaryCardAction = secondaryAction;
        pendingPrimaryCardAction = primaryAction;
        isCardActionInProgress = false;
        currentCardInteractionType = CardInteractionType.OPTION_SELECTION;
        currentResultCardClosableByOpenPalm = false;
        runOnUiThread(() -> mBindingPair.updateView(binding -> {
            applyCardActionEnabledState(binding, hasPendingCardActions());
            return null;
        }));
    }

    private void clearCardActions() {
        pendingSecondaryCardAction = null;
        pendingPrimaryCardAction = null;
        isCardActionInProgress = false;
        resetCardHoverTracking();
        runOnUiThread(() -> mBindingPair.updateView(binding -> {
            applyCardActionEnabledState(binding, false);
            return null;
        }));
    }

    private boolean hasPendingCardActions() {
        return pendingSecondaryCardAction != null || pendingPrimaryCardAction != null;
    }

    private void executeCardAction(boolean isPrimaryAction) {
        CardActionHandler actionHandler = isPrimaryAction ? pendingPrimaryCardAction : pendingSecondaryCardAction;
        if (actionHandler == null || isCardActionInProgress) {
            return;
        }
        isCardActionInProgress = true;
        triggerVibration();
        mBindingPair.updateView(binding -> {
            applyCardActionEnabledState(binding, false);
            return null;
        });
        actionHandler.onAction();
    }

    private void applyCardActionEnabledState(ActivityMainBinding binding, boolean enabled) {
        if (binding.includeArCard.tvCardActionSecondary != null) {
            binding.includeArCard.tvCardActionSecondary.setEnabled(enabled);
            binding.includeArCard.tvCardActionSecondary.setClickable(enabled);
            binding.includeArCard.tvCardActionSecondary.setAlpha(enabled ? 1f : 0.5f);
        }
        if (binding.includeArCard.tvCardActionPrimary != null) {
            binding.includeArCard.tvCardActionPrimary.setEnabled(enabled);
            binding.includeArCard.tvCardActionPrimary.setClickable(enabled);
            binding.includeArCard.tvCardActionPrimary.setAlpha(enabled ? 1f : 0.5f);
        }
        if (!enabled) {
            applyCardHoverState(binding, CARD_HOVER_NONE);
        } else {
            applyCardHoverState(binding, currentCardHoverTarget);
        }
    }

    private boolean isInteractiveCardVisible() {
        final boolean[] visible = { false };
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            View actionRow = binding.includeArCard.layoutCardActions;
            visible[0] = cardRoot != null
                    && cardRoot.getVisibility() == View.VISIBLE
                    && actionRow != null
                    && actionRow.getVisibility() == View.VISIBLE
                    && hasPendingCardActions();
            return null;
        });
        return visible[0];
    }

    private boolean handleInteractiveCardGesture(RenderData renderData) {
        if (!isInteractiveCardVisible()) {
            resetCardHoverTracking();
            return false;
        }

        // 功能性卡片期间：禁用张手关闭进度，避免误关闭。
        openPalmStartTime = 0;
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setCloseProgress(0f);
        }

        Boolean hoverPrimaryAction = resolveHoverPrimaryAction(renderData);
        updateCardHoverByHoverResult(hoverPrimaryAction);

        if (currentCardHoverTarget == CARD_HOVER_NONE) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (isCardActionInProgress) {
            return true;
        }
        if (now - lastCardActionTriggerTime <= CARD_ACTION_COOLDOWN_MS) {
            return true;
        }

        if (cardHoverActivatedTime == 0L || now - cardHoverActivatedTime < CARD_ACTION_HOVER_TRIGGER_MS) {
            return true;
        }

        lastCardActionTriggerTime = now;
        boolean executePrimary = currentCardHoverTarget == CARD_HOVER_PRIMARY;
        executeCardAction(executePrimary);
        resetCardHoverTracking();
        return true;
    }

    private void updateCardHoverByHoverResult(Boolean hoverPrimaryAction) {
        int candidateTarget = CARD_HOVER_NONE;
        if (hoverPrimaryAction != null) {
            candidateTarget = hoverPrimaryAction ? CARD_HOVER_PRIMARY : CARD_HOVER_SECONDARY;
        }

        if (candidateTarget == CARD_HOVER_NONE) {
            resetCardHoverTracking();
            return;
        }

        long now = System.currentTimeMillis();
        if (pendingCardHoverTarget != candidateTarget) {
            pendingCardHoverTarget = candidateTarget;
            cardHoverCandidateStartTime = now;
            return;
        }

        if (now - cardHoverCandidateStartTime >= CARD_HOVER_DWELL_MS) {
            setCardHoverTarget(candidateTarget);
        }
    }

    private void resetCardHoverTracking() {
        pendingCardHoverTarget = CARD_HOVER_NONE;
        cardHoverCandidateStartTime = 0L;
        cardHoverActivatedTime = 0L;
        setCardHoverTarget(CARD_HOVER_NONE);
    }

    private void setCardHoverTarget(int target) {
        if (currentCardHoverTarget == target) {
            return;
        }
        currentCardHoverTarget = target;
        cardHoverActivatedTime = target == CARD_HOVER_NONE ? 0L : System.currentTimeMillis();
        runOnUiThread(() -> mBindingPair.updateView(binding -> {
            applyCardHoverState(binding, currentCardHoverTarget);
            return null;
        }));
    }

    private void applyCardHoverState(ActivityMainBinding binding, int target) {
        TextView secondaryView = binding.includeArCard.tvCardActionSecondary;
        TextView primaryView = binding.includeArCard.tvCardActionPrimary;
        if (secondaryView != null) {
            secondaryView.setActivated(target == CARD_HOVER_SECONDARY);
            boolean isSecondaryHovered = target == CARD_HOVER_SECONDARY;
            secondaryView.setPivotX(secondaryView.getWidth() / 2f);
            secondaryView.setPivotY(secondaryView.getHeight() / 2f);
            secondaryView.setScaleX(isSecondaryHovered ? 1.14f : 1f);
            secondaryView.setScaleY(isSecondaryHovered ? 1.14f : 1f);
        }
        if (primaryView != null) {
            primaryView.setActivated(target == CARD_HOVER_PRIMARY);
            boolean isPrimaryHovered = target == CARD_HOVER_PRIMARY;
            primaryView.setPivotX(primaryView.getWidth() / 2f);
            primaryView.setPivotY(primaryView.getHeight() / 2f);
            primaryView.setScaleX(isPrimaryHovered ? 1.14f : 1f);
            primaryView.setScaleY(isPrimaryHovered ? 1.14f : 1f);
        }
    }

    private Boolean resolveHoverPrimaryAction(RenderData renderData) {
        final Boolean[] result = { null };
        mBindingPair.updateView(binding -> {
            View root = binding.getRoot();
            TextView secondaryView = binding.includeArCard.tvCardActionSecondary;
            TextView primaryView = binding.includeArCard.tvCardActionPrimary;
            View actionRow = binding.includeArCard.layoutCardActions;

            if (root == null || actionRow == null || secondaryView == null || primaryView == null
                    || actionRow.getVisibility() != View.VISIBLE
                    || secondaryView.getVisibility() != View.VISIBLE
                    || primaryView.getVisibility() != View.VISIBLE) {
                result[0] = null;
                return null;
            }

            int rootWidth = root.getWidth();
            int rootHeight = root.getHeight();
            if (rootWidth <= 0 || rootHeight <= 0) {
                result[0] = null;
                return null;
            }

            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            RenderProcessor processor = RenderProcessor.getInstance();
            float tipX;
            float tipY;
            if (processor != null && processor.hasLeftCursorPoint()) {
                tipX = rootLocation[0] + processor.getLeftCursorNormX() * rootWidth;
                tipY = rootLocation[1] + processor.getLeftCursorNormY() * rootHeight;
            } else {
                // 回退：渲染层未给出光标点时，使用原始手势坐标。
                tipX = rootLocation[0] + renderData.getTipX() * rootWidth;
                tipY = rootLocation[1] + renderData.getTipY() * rootHeight;
            }

            Rect secondaryRect = new Rect();
            Rect primaryRect = new Rect();
            secondaryView.getGlobalVisibleRect(secondaryRect);
            primaryView.getGlobalVisibleRect(primaryRect);

            if (secondaryRect.contains((int) tipX, (int) tipY)) {
                result[0] = Boolean.FALSE;
            } else if (primaryRect.contains((int) tipX, (int) tipY)) {
                result[0] = Boolean.TRUE;
            } else {
                result[0] = null;
            }
            return null;
        });
        return result[0];
    }

    private void showInteractiveConfirmCard(String title,
            String primaryLine,
            String metaLine,
            String secondaryActionLabel,
            CardActionHandler secondaryAction,
            String primaryActionLabel,
            CardActionHandler primaryAction,
            int color) {
        bindCardActions(secondaryAction, primaryAction);
        setCardText(title, buildConfirmCardContent(primaryLine, metaLine, secondaryActionLabel, primaryActionLabel),
                color);
    }

    private void onReminderCreateDeferred() {
        backendDemoHandler.removeCallbacksAndMessages(null);
        clearCardActions();
        reminderDemoActive = false;
        isWaitingForQuestion = false;
        pendingReminderCardData = null;
        showArToast("取消创建成功");
        setStatusMotion(StatusMotion.IDLE);
        closeCardImmediately();
        updateStatus("已取消创建", R.drawable.ic_assistant);
        isVoiceCardShowing = false;
        backendDemoHandler.postDelayed(this::finishCardFlowToIdle, 900L);
    }

    private void onReminderCreateConfirmed() {
        backendDemoHandler.removeCallbacksAndMessages(null);
        clearCardActions();
        reminderDemoActive = false;
        isWaitingForQuestion = false;
        ReminderCardData reminderData = normalizeReminderCardData(pendingReminderCardData);
        showArToast("已确认：创建提醒");
        updateStatus("提醒创建中", R.drawable.ic_processing);
        setStatusMotion(StatusMotion.PROCESSING);
        setCardText("提醒创建中", "正在同步提醒策略...",
                getResources().getColor(R.color.primary_teal_light, null), false);
        positionCardTopCenter();

        long waitMs = DEMO_PHOTO_RESULT_MIN_DELAY_MS
                + (long) (Math.random()
                        * (DEMO_PHOTO_RESULT_MAX_DELAY_MS - DEMO_PHOTO_RESULT_MIN_DELAY_MS + 1));

        backendDemoHandler.postDelayed(() -> {
            setStatusMotion(StatusMotion.IDLE);
            updateStatus("提醒创建完成", R.drawable.ic_assistant);
            setCardText("创建成功", "药品：" + reminderData.drugName + "\n提醒时间：每日 " + reminderData.reminderTime,
                    getResources().getColor(R.color.status_success, null));
            positionCardTopCenter();
            showArToast("提醒创建成功");
            saveLocalReminderData(reminderData);
            scheduleLocalReminderAtPlanTime(reminderData);
            pendingReminderCardData = null;
        }, waitMs);
    }

    private void showReminderDoseCard(ReminderCardData rawData) {
        ReminderCardData data = normalizeReminderCardData(rawData);
        pendingReminderCardData = data;
        isVoiceCardShowing = true;

        String currentTime = getCurrentTimeLabel();
        String primaryLine = "药品：" + data.drugName + "\n服用：" + data.usage;
        String metaLine = "当前时间：" + currentTime + "｜计划时间 " + data.reminderTime;

        updateStatus("用药提醒", R.drawable.ic_assistant);
        showInteractiveConfirmCard("用药提醒",
                primaryLine,
                metaLine,
                "稍后提醒",
                this::onReminderDoseDeferred,
                "已服用",
                this::onReminderDoseTaken,
                getResources().getColor(R.color.primary_teal_light, null));
        positionCardTopCenter();
        showArToast("到点提醒，请确认是否已服用");
    }

    private String getCurrentTimeLabel() {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
    }

    private void initLocalReminderSchedule() {
        ReminderCardData savedReminder = loadLocalReminderData();
        if (savedReminder != null) {
            scheduleLocalReminderAtPlanTime(savedReminder);
        }
    }

    private void saveLocalReminderData(ReminderCardData rawData) {
        ReminderCardData data = normalizeReminderCardData(rawData);
        SharedPreferences preferences = getSharedPreferences(REMINDER_PREFS_NAME, MODE_PRIVATE);
        preferences.edit()
                .putString(REMINDER_KEY_DRUG, data.drugName)
                .putString(REMINDER_KEY_USAGE, data.usage)
                .putString(REMINDER_KEY_TIME, data.reminderTime)
                .putString(REMINDER_KEY_SOURCE, data.source)
                .apply();
    }

    private ReminderCardData loadLocalReminderData() {
        SharedPreferences preferences = getSharedPreferences(REMINDER_PREFS_NAME, MODE_PRIVATE);
        String reminderTime = preferences.getString(REMINDER_KEY_TIME, null);
        if (isBlank(reminderTime)) {
            return null;
        }

        return normalizeReminderCardData(new ReminderCardData(
                preferences.getString(REMINDER_KEY_DRUG, null),
                preferences.getString(REMINDER_KEY_USAGE, null),
                reminderTime,
                preferences.getString(REMINDER_KEY_SOURCE, null)));
    }

    private void scheduleLocalReminderAtPlanTime(ReminderCardData rawData) {
        ReminderCardData data = normalizeReminderCardData(rawData);
        saveLocalReminderData(data);
        long triggerAt = computeNextReminderTriggerAt(data.reminderTime);
        scheduleLocalReminder(data, triggerAt);
    }

    private void scheduleLocalReminderAfterDelay(ReminderCardData rawData, long delayMs) {
        ReminderCardData data = normalizeReminderCardData(rawData);
        saveLocalReminderData(data);
        long triggerAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        scheduleLocalReminder(data, triggerAt);
    }

    private void scheduleLocalReminder(ReminderCardData data, long triggerAtMs) {
        cancelLocalReminderSchedule();
        long delayMs = Math.max(0L, triggerAtMs - System.currentTimeMillis());
        localReminderRunnable = () -> showReminderDoseCard(data);
        localReminderHandler.postDelayed(localReminderRunnable, delayMs);
        Log.d(TAG, "本地提醒已调度, triggerAt=" + triggerAtMs + ", delayMs=" + delayMs);
    }

    private void cancelLocalReminderSchedule() {
        if (localReminderRunnable != null) {
            localReminderHandler.removeCallbacks(localReminderRunnable);
            localReminderRunnable = null;
        }
    }

    private long computeNextReminderTriggerAt(String reminderTime) {
        String safeTime = firstNonBlank(reminderTime, "20:26");
        int hour = 20;
        int minute = 26;
        if (!isBlank(safeTime) && safeTime.contains(":")) {
            String[] parts = safeTime.split(":");
            if (parts.length >= 2) {
                try {
                    hour = Integer.parseInt(parts[0].trim());
                    minute = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    hour = 20;
                    minute = 26;
                }
            }
        }

        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, hour)));
        target.set(Calendar.MINUTE, Math.max(0, Math.min(59, minute)));
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis();
    }

    private void onReminderDoseDeferred() {
        backendDemoHandler.removeCallbacksAndMessages(null);
        clearCardActions();
        ReminderCardData reminderData = normalizeReminderCardData(
                pendingReminderCardData != null ? pendingReminderCardData : loadLocalReminderData());
        scheduleLocalReminderAfterDelay(reminderData, REMINDER_SNOOZE_DELAY_MS);
        pendingReminderCardData = null;
        showArToast("已选择：稍后提醒");
        updateStatus("提醒已顺延", R.drawable.ic_assistant);
        setCardText("提醒已顺延", "已延后10分钟再次提醒",
                getResources().getColor(R.color.status_warning_warm, null));
        positionCardTopCenter();
        backendDemoHandler.postDelayed(this::finishCardFlowToIdle, 1800L);
    }

    private void onReminderDoseTaken() {
        backendDemoHandler.removeCallbacksAndMessages(null);
        clearCardActions();
        ReminderCardData reminderData = normalizeReminderCardData(
                pendingReminderCardData != null ? pendingReminderCardData : loadLocalReminderData());
        scheduleLocalReminderAtPlanTime(reminderData);
        pendingReminderCardData = null;
        showArToast("已确认：已服用");
        updateStatus("提醒记录已同步", R.drawable.ic_assistant);
        setCardText("提醒完成", "已记录本次服药\n家属端状态已同步",
                getResources().getColor(R.color.status_success, null));
        positionCardTopCenter();
        showArToast("服药记录已同步");
        backendDemoHandler.postDelayed(this::finishCardFlowToIdle, 1800L);
    }

    private void onSmsSendCancelled() {
        backendDemoHandler.removeCallbacksAndMessages(null);
        clearCardActions();
        showArToast("已取消发送");
        updateStatus("短信已取消", R.drawable.ic_assistant);
        setCardText("发送已取消", "本次未发送短信",
                getResources().getColor(R.color.status_warning_warm, null));
        positionCardTopCenter();
        backendDemoHandler.postDelayed(this::finishCardFlowToIdle, 1500L);
    }

    private void onSmsSendConfirmed() {
        backendDemoHandler.removeCallbacksAndMessages(null);
        clearCardActions();
        showArToast("已确认：发送");
        updateStatus("短信发送中", R.drawable.ic_processing);
        setStatusMotion(StatusMotion.PROCESSING);
        setCardText("发送中", "正在提交短信，等待运营商回执...",
                getResources().getColor(R.color.primary_teal_light, null), false);
        positionCardTopCenter();

        long waitMs = DEMO_PHOTO_RESULT_MIN_DELAY_MS
                + (long) (Math.random()
                        * (DEMO_PHOTO_RESULT_MAX_DELAY_MS - DEMO_PHOTO_RESULT_MIN_DELAY_MS + 1));

        backendDemoHandler.postDelayed(() -> {
            setStatusMotion(StatusMotion.IDLE);
            if (backendSmsSuccessNext) {
                updateStatus("短信发送完成", R.drawable.ic_assistant);
                setCardText("发送结果", "已发送给儿子（18983810096）\n内容：999感冒灵颗粒快吃完了，帮我买一点回来",
                        getResources().getColor(R.color.status_success, null));
                showArToast("短信发送成功");
            } else {
                updateStatus("短信发送异常", R.drawable.ic_assistant);
                setCardText("发送结果", "短信发送失败，已切换电话通知",
                        getResources().getColor(R.color.status_warning_warm, null));
                showArToast("短信发送失败，已降级处理");
            }
            positionCardTopCenter();
            backendSmsSuccessNext = !backendSmsSuccessNext;
        }, waitMs);
    }

    private void finishCardFlowToIdle() {
        clearCardActions();
        isModeLocked = false;
        updateStatus("系统就绪", R.drawable.ic_assistant);
    }

    // 震动反馈
    private void triggerVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null)
                v.vibrate(100);
        } catch (Exception e) {
        }
    }

    private void applyStatusIconTint(ImageView iconView, int iconRes) {
        int tintRes = R.color.text_secondary;
        if (iconRes == R.drawable.ic_microphone_active) {
            tintRes = R.color.primary_teal_light;
        } else if (iconRes == R.drawable.ic_processing) {
            tintRes = R.color.primary_teal;
        } else if (iconRes == R.drawable.ic_assistant) {
            tintRes = R.color.text_secondary;
        }

        try {
            android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(
                    getResources().getColor(tintRes, null));
            iconView.setImageTintList(tint);
        } catch (Exception ignored) {
        }
    }

    private int normalizeToastIconRes(String message, int iconRes) {
        if (iconRes == 0)
            return 0;
        if (message == null)
            return 0;

        // AR spec: only success/exception toasts should use icons; descriptive hints
        // should be text-only.
        boolean isSuccess = message.contains("成功") || message.contains("完成");
        boolean isException = message.contains("失败") || message.contains("错误") || message.contains("异常");
        if (isSuccess || isException) {
            return iconRes;
        }
        return 0;
    }

    // 保存调试图片
    private void saveDebugImage(Bitmap originalBitmap, float x, float y) {
        new Thread(() -> {
            try {
                Bitmap mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);

                // 【方案5】移除红圈标记，保留原始图像便于分析
                // Canvas canvas = new Canvas(mutableBitmap);
                // Paint paint = new Paint();
                // paint.setColor(Color.RED);
                // paint.setStrokeWidth(8f);
                // paint.setStyle(Paint.Style.STROKE);
                // float pixelX = x * mutableBitmap.getWidth();
                // float pixelY = y * mutableBitmap.getHeight();
                // canvas.drawCircle(pixelX, pixelY, 50f, paint);

                // 【方案1】使用 PNG 无损格式保存
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                        .format(new java.util.Date());
                String filename = "AR_" + timestamp + ".png";

                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/SensoryAI");

                android.net.Uri uri = getContentResolver()
                        .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        // PNG 无损压缩（quality 参数对 PNG 无效，但保留写法）
                        mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        out.close();
                        Log.d(TAG, "✅ PNG 无损图片已保存: " + filename);
                    }
                }

                mutableBitmap.recycle();
            } catch (Exception e) {
                Log.e(TAG, "Save Error", e);
                // 如果出错，也要尝试回收原始 bitmap
                if (originalBitmap != null && !originalBitmap.isRecycled()) {
                    originalBitmap.recycle();
                }
            }
        }).start();
    }

    /**
     * ✅ 从高清 YUV 缓存数据裁剪指尖周围区域
     * 
     * @param cache 高清 YUV 缓存数据 (1920x1080)
     * @param tipX  指尖 X 坐标 (0-1 归一化)
     * @param tipY  指尖 Y 坐标 (0-1 归一化)
     * @return 裁剪后的高清 Bitmap (约 600x600)
     */
    private Bitmap cropHighResFromYUV(RecognizeTask.HighResYUVCache cache, float tipX, float tipY) {
        if (cache == null) {
            Log.w(TAG, "YUV 缓存为空，无法裁剪");
            return null;
        }

        try {
            int fullWidth = 1920;
            int fullHeight = 1080;

            // 计算指尖在原图的像素坐标
            int centerX = (int) (tipX * fullWidth);
            int centerY = (int) (tipY * fullHeight);

            // 裁剪区域大小（600x600）
            int cropSize = 600;

            // 计算裁剪起点，确保以指尖为中心
            int cropLeft = centerX - cropSize / 2;
            int cropTop = centerY - cropSize / 2;

            // 边界处理：确保裁剪区域在图片范围内
            if (cropLeft < 0)
                cropLeft = 0;
            if (cropTop < 0)
                cropTop = 0;
            if (cropLeft + cropSize > fullWidth)
                cropLeft = fullWidth - cropSize;
            if (cropTop + cropSize > fullHeight)
                cropTop = fullHeight - cropSize;

            Log.d(TAG, String.format("🔍 裁剪信息: 原图 %dx%d, 指尖 (%.2f,%.2f) = (%d,%d), 裁剪 [%d,%d,%d,%d]",
                    fullWidth, fullHeight, tipX, tipY, centerX, centerY,
                    cropLeft, cropTop, cropLeft + cropSize, cropTop + cropSize));

            // 从 YUV 缓存数据裁剪指定区域并转换为 RGB Bitmap
            return cropYUVToRGB(cache, cropLeft, cropTop, cropSize, cropSize);

        } catch (Exception e) {
            Log.e(TAG, "高清裁剪异常", e);
            return null;
        }
    }

    /**
     * 从 YUV 缓存数据裁剪指定区域并转换为 RGB Bitmap
     */
    private Bitmap cropYUVToRGB(RecognizeTask.HighResYUVCache cache, int startX, int startY, int width, int height) {
        if (cache == null) {
            Log.w(TAG, "YUV 缓存为空");
            return null;
        }

        try {
            byte[] yBytes = cache.yData;
            byte[] uBytes = cache.uData;
            byte[] vBytes = cache.vData;

            if (yBytes == null || uBytes == null || vBytes == null) {
                Log.w(TAG, "YUV 数据不完整");
                return null;
            }

            int fullWidth = cache.width;
            int yRowStride = cache.yRowStride;
            int uvRowStride = cache.uvRowStride;
            int uvPixelStride = cache.uvPixelStride;

            int[] pixels = new int[width * height];
            int pixelIndex = 0;

            for (int y = 0; y < height; y++) {
                int srcY = startY + y;
                int yRowOffset = srcY * yRowStride;
                int uvRowOffset = (srcY / 2) * uvRowStride;

                for (int x = 0; x < width; x++) {
                    int srcX = startX + x;

                    int yIndex = yRowOffset + srcX;
                    int uvIndex = uvRowOffset + (srcX / 2) * uvPixelStride;

                    if (yIndex >= yBytes.length)
                        yIndex = yBytes.length - 1;
                    if (uvIndex >= uBytes.length)
                        uvIndex = uBytes.length - 1;
                    if (uvIndex >= vBytes.length)
                        uvIndex = vBytes.length - 1;

                    int Y = (yBytes[yIndex] & 0xFF);
                    int U = (uBytes[uvIndex] & 0xFF) - 128;
                    int V = (vBytes[uvIndex] & 0xFF) - 128;

                    // 标准 YUV 到 RGB 转换
                    int R = (int) (Y + 1.370705f * V);
                    int G = (int) (Y - 0.337633f * U - 0.698001f * V);
                    int B = (int) (Y + 1.732446f * U);

                    R = Math.max(0, Math.min(255, R));
                    G = Math.max(0, Math.min(255, G));
                    B = Math.max(0, Math.min(255, B));

                    pixels[pixelIndex++] = 0xFF000000 | (R << 16) | (G << 8) | B;
                }
            }

            Log.d(TAG, "✅ YUV 裁剪转换完成: " + width + "x" + height);
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);

        } catch (Exception e) {
            Log.e(TAG, "YUV 转换异常", e);
            return null;
        }
    }

    /**
     * 将完整的高清 YUV 数据转换为 RGB Bitmap（用于保存调试图片）
     * 
     * @param cache 高清 YUV 缓存数据 (1920x1080)
     * @return 完整的高清 Bitmap
     */
    private Bitmap convertFullYUVToRGB(RecognizeTask.HighResYUVCache cache) {
        if (cache == null) {
            Log.w(TAG, "YUV 缓存为空");
            return null;
        }

        try {
            byte[] yBytes = cache.yData;
            byte[] uBytes = cache.uData;
            byte[] vBytes = cache.vData;

            if (yBytes == null || uBytes == null || vBytes == null) {
                Log.w(TAG, "YUV 数据不完整");
                return null;
            }

            int width = cache.width;
            int height = cache.height;
            int yRowStride = cache.yRowStride;
            int uvRowStride = cache.uvRowStride;
            int uvPixelStride = cache.uvPixelStride;

            Log.d(TAG, String.format("📸 YUV转换参数: %dx%d, yStride=%d, uvStride=%d, uvPixelStride=%d",
                    width, height, yRowStride, uvRowStride, uvPixelStride));

            int[] pixels = new int[width * height];
            int pixelIndex = 0;

            for (int y = 0; y < height; y++) {
                int yRowOffset = y * yRowStride;
                int uvRowOffset = (y / 2) * uvRowStride;

                for (int x = 0; x < width; x++) {
                    int yIndex = yRowOffset + x;
                    if (yIndex >= yBytes.length)
                        yIndex = yBytes.length - 1;

                    int uvIndex = uvRowOffset + (x / 2) * uvPixelStride;
                    if (uvIndex >= uBytes.length)
                        uvIndex = uBytes.length - 1;
                    if (uvIndex >= vBytes.length)
                        uvIndex = vBytes.length - 1;

                    int Y = (yBytes[yIndex] & 0xFF);
                    int U = (uBytes[uvIndex] & 0xFF) - 128;
                    int V = (vBytes[uvIndex] & 0xFF) - 128;

                    int R = (int) (Y + 1.370705f * V);
                    int G = (int) (Y - 0.337633f * U - 0.698001f * V);
                    int B = (int) (Y + 1.732446f * U);

                    R = Math.max(0, Math.min(255, R));
                    G = Math.max(0, Math.min(255, G));
                    B = Math.max(0, Math.min(255, B));

                    pixels[pixelIndex++] = 0xFF000000 | (R << 16) | (G << 8) | B;
                }
            }

            Log.d(TAG, "✅ 完整高清图转换完成: " + width + "x" + height);
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);

        } catch (Exception e) {
            Log.e(TAG, "完整 YUV 转换异常", e);
            return null;
        }
    }
}