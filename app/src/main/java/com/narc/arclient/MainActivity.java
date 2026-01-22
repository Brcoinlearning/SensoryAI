package com.narc.arclient;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.FrameLayout;
import com.narc.arclient.ui.SubtitleStreamView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

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

public class MainActivity extends BaseMirrorActivity<ActivityMainBinding> {

    private static final String TAG = "SilverSight";
    // private static final boolean MOCK_SUBTITLE = true; // 临时开启字幕模拟 (改由按钮控制)

    // UI 组件
    private CustomDrawView customDrawView;
    // 注意：卡片通过 mBindingPair 访问，不用 findViewById
    // Demo: 模拟字幕流（仅调试）
    private Handler subtitleDemoHandler;
    private boolean subtitleDemoRunning = false;

    // AR 卡片组件
    private TextView tvCardTitle, tvCardContent;
    private float lastCardX = -1f; // 跟随平滑用
    private float lastCardY = -1f; // 跟随平滑用

    // 状态控制
    private boolean isAnalyzing = false;
    private long openPalmStartTime = 0; // 张手关闭卡片的长按起点
    private static final long CLOSE_HOLD_MS = 800; // 张手关闭所需时长
    private long lastTriggerTime = 0;
    private static final long COOLDOWN_MS = 1000; // 改为1秒防抖
    private boolean isMicEnabled = false;

    // 最近一帧的渲染数据，用于硬件按键触发时复用指尖坐标
    private RenderData lastRenderData;

    // 触摸事件状态追踪（用于检测单击）
    private long touchDownTime = 0;
    private float touchDownX = 0;
    private float touchDownY = 0;
    private static final long TAP_TIMEOUT_MS = 500; // 单击最长允许时间
    private static final float TAP_SLOP_PX = 50; // 单击最大允许移动像素

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // 3. 初始化 麦克风按钮点击监听器 (处理用户点击交互)
        initMicButtonListener();

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
        return super.onKeyUp(keyCode, event);
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

        Log.d(TAG, "👆 onTouchEvent action=" + action + " x=" + String.format("%.1f", x) + " y="
                + String.format("%.1f", y));

        if (action == MotionEvent.ACTION_DOWN) {
            touchDownTime = System.currentTimeMillis();
            touchDownX = x;
            touchDownY = y;
        } else if (action == MotionEvent.ACTION_UP) {
            long duration = System.currentTimeMillis() - touchDownTime;
            float distance = (float) Math
                    .sqrt((x - touchDownX) * (x - touchDownX) + (y - touchDownY) * (y - touchDownY));

            if (duration < TAP_TIMEOUT_MS && distance < TAP_SLOP_PX) {
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
                // 实时更新字幕
                updateSubtitle(text, isFinal);
            }

            @Override
            public void onAgentProgress(String stage, String status, String summary) {
                // 显示智能体思考状态 (例如：正在感知、决策中)
                runOnUiThread(() -> {
                    startCardSequence(); // 确保卡片可见
                    // 根据状态显示不同颜色
                    int color = "completed".equals(status) ? Color.CYAN : Color.YELLOW;
                    String stageText = getStageText(stage);
                    setCardText("🤖 " + stageText, summary, color);
                });
            }

            @Override
            public void onAgentResult(String result, String sessionId) {
                // 显示智能体最终回复
                runOnUiThread(() -> {
                    setCardText("✅ 智能体回复", result, Color.GREEN);
                    triggerVibration();
                });
            }

            @Override
            public void onError(String stage, String message) {
                // 区分错误类型显示
                String errorMsg = "subtitle".equals(stage) ? "❗ 字幕错误" : "❗ 智能体错误";
                updateStatus(errorMsg + ": " + message);
            }

            @Override
            public void onConnected() {
                updateStatus("🔗 已连接");
            }

            @Override
            public void onDisconnected(String reason) {
                updateStatus("🔌 已断开: " + reason);
            }
        });
    }

    /**
     * 初始化麦克风按钮点击监听
     * 当用户手指在 AR 眼镜前点击虚拟按钮时触发
     */
    private void initMicButtonListener() {
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setOnMicStatusListener(new RenderProcessor.OnMicStatusListener() {
                @Override
                public void onMicClick(boolean isOn) {
                    runOnUiThread(() -> {
                        handleMicToggle(isOn);
                    });
                }
            });

            // 字幕模拟按钮监听
            RenderProcessor.getInstance().setOnSubtitleMockListener(new RenderProcessor.OnSubtitleMockListener() {
                @Override
                public void onSubtitleMockClick(boolean isOn) {
                    runOnUiThread(() -> {
                        handleSubtitleMockToggle(isOn);
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
        Log.d(TAG, "================== 初始化完成 ==================");
    }

    private void checkPermissionsAndStart() {
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
                    // 👇👇👇【关键修复】👇👇👇
                    // 补齐了后面3个参数 (isMicHovered, micProgress, isMicTriggered) 以匹配你的 RenderData
                    RenderData mockData = new RenderData(0.5f, 0.5f, 1.0f, true, false, null, false, 0f, false);
                    updateView(mockData, null);
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
    }

    /**
     * 处理麦克风开关逻辑
     */
    private void handleMicToggle(boolean isOn) {
        isMicEnabled = isOn;

        // 1. 更新渲染器 UI (红圆点 <-> 红方块)
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setMicState(isMicEnabled);
        }

        // 2. 震动反馈
        triggerVibration();

        // 3. 开启或停止录音推流
        if (isMicEnabled) {
            String status = "🎙️ 正在聆听...";
            updateStatus(status);
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            // 启动录音机 (它内部会自动连接 WebSocket)
            AudioRecorder.getInstance().start(getApplicationContext());
        } else {
            String status = "⏹️ 思考中...";
            updateStatus(status);
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            // 停止录音 (它内部会发送结束包)
            AudioRecorder.getInstance().stop();
        }
    }

    /**
     * 处理字幕模拟开关逻辑
     */
    private void handleSubtitleMockToggle(boolean isOn) {
        // 更新渲染器 UI
        if (RenderProcessor.getInstance() != null) {
            RenderProcessor.getInstance().setSubtitleMockState(isOn);
        }

        // 震动反馈
        triggerVibration();

        if (isOn) {
            String status = "📝 开启字幕模拟";
            updateStatus(status);
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            startSubtitleMockDemo();
        } else {
            String status = "⏹️ 关闭字幕模拟";
            updateStatus(status);
            Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
            stopSubtitleMockDemo();
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

            Log.d(TAG, "✅ [合目镜像] 字幕更新到左右两眼: " + text);
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

        postSubtitleAt(0, "正在聆听…", false);
        postSubtitleAt(600, "您好，我是您的随身助理", false);
        postSubtitleAt(1400, "您好，我是您的随身助理，正在为您记录", false);
        postSubtitleAt(2200, "您好，我是您的随身助理，正在为您记录。", true);

        // 第二句
        postSubtitleAt(3800, "今天天气不错", false);
        postSubtitleAt(4400, "今天天气不错，东南风 2 级", false);
        postSubtitleAt(5200, "今天天气不错，东南风 2 级，体感舒适。", true);

        // 第三句（较长，测试 3 行省略）
        postSubtitleAt(7200, "附近有一家评分 4.8 的面馆", false);
        postSubtitleAt(8000, "附近有一家评分 4.8 的面馆，午市优惠力度较大", false);
        postSubtitleAt(9000, "附近有一家评分 4.8 的面馆，午市优惠力度较大，步行大约 6 分钟即可到达，是否需要我为您导航？", true);

        // 循环演示：10.5s 后再次开始
        subtitleDemoHandler.postDelayed(() -> {
            // 使用 mBindingPair 清除字幕
            mBindingPair.updateView(binding -> {
                if (binding.subtitleView != null)
                    binding.subtitleView.clearImmediate();
                return null;
            });
            subtitleDemoRunning = false;
            startSubtitleMockDemo();
        }, 10500);
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

    @Override
    protected void onDestroy() {
        if (subtitleDemoHandler != null) {
            subtitleDemoHandler.removeCallbacksAndMessages(null);
        }
        subtitleDemoRunning = false;
        super.onDestroy();
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

        // 2. 处理手势触发的【视觉识别】(HTTP 拍照)
        if (isAnalyzing) {
            Log.d(TAG, "🔄 [分析中] isAnalyzing=true, openPalm=" + renderData.isOpenPalm());
            // 如果正在分析中...
            if (renderData.isOpenPalm()) {
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
            // 更新卡片位置跟随手指
            updateCardPosition(renderData.getTipX(), renderData.getTipY());

            // 检查是否有 HTTP 识别结果返回
            if (recognizeTask != null && recognizeTask.getRecognizeResult() != null) {
                runOnUiThread(() -> {
                    // 显示 HTTP 返回的图片识别结果
                    setCardText(recognizeTask.getRecognizeResult(), "视觉识别成功", Color.GREEN);
                    triggerVibration();
                    // 不自动关闭，等待用户张手关闭
                });
            }

        } else {
            // 3. 如果未处于分析状态，且触发了悬停 (isTriggered)
            // 且不在麦克风录音模式下 (避免冲突)
            if (renderData.isTriggered() && !isMicEnabled) {
                long now = System.currentTimeMillis();
                Log.d(TAG, "👆 [触发检测] isTriggered=true, 冷却时间=" + (now - lastTriggerTime) + "ms, COOLDOWN=" + COOLDOWN_MS
                        + "ms");
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    Log.d(TAG, "✅ [触发成功] 冷却已过，开始分析");
                    isAnalyzing = true;
                    RenderProcessor.getInstance().setLocked(true);
                    RenderProcessor.getInstance().setCloseProgress(0f);
                    openPalmStartTime = 0;
                    lastTriggerTime = now;
                    triggerVibration();

                    RecognizeTask.HighResYUVCache yuvCache = RecognizeTask.getLatestHighResYUV();
                    if (yuvCache != null) {
                        Bitmap fullHighResBitmap = convertFullYUVToRGB(yuvCache);
                        if (fullHighResBitmap != null) {
                            // 用同一帧作为本地保存与上传的来源，保证一致性
                            Bitmap saveCopy = fullHighResBitmap.copy(Bitmap.Config.ARGB_8888, true);
                            saveDebugImage(saveCopy, renderData.getTipX(), renderData.getTipY());

                            ProcessorManager.normalExecutor.execute(() -> {
                                try {
                                    RecognizeTask uploadTask = new RecognizeTask(fullHighResBitmap);
                                    SendRemoteProcessor processor = new SendRemoteProcessor();
                                    RecognizeTask result = processor.process(uploadTask);

                                    if (recognizeTask != null) {
                                        recognizeTask.setRecognizeResult(result.getRecognizeResult());
                                    }

                                    Log.i(TAG, "高清全图上传完成");
                                } catch (Exception e) {
                                    Log.e(TAG, "高清全图上传失败", e);
                                    runOnUiThread(() -> {
                                        setCardText("❌ 识别失败", "网络错误，请重试", Color.RED);
                                        // 不自动关闭，等待用户张手关闭
                                    });
                                } finally {
                                    if (fullHighResBitmap != null && !fullHighResBitmap.isRecycled()) {
                                        fullHighResBitmap.recycle();
                                    }
                                }
                            });
                        }
                    }

                    // 显示 "正在识别..." 卡片
                    startCardSequence();
                }
            }
        }
    }

    // 更新底部状态栏文字（使用 mBindingPair 实现合目镜像）
    public void updateStatus(String msg) {
        runOnUiThread(() -> {
            mBindingPair.updateView(binding -> {
                if (binding.tvStatus != null) {
                    binding.tvStatus.setText(msg);
                }
                return null;
            });
            Log.d(TAG, "✅ [合目镜像] 状态栏更新到左右两眼: " + msg);
        });
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
        Log.d(TAG, "🔳 [硬件拍照] 开始触发检查...");

        if (isMicEnabled) {
            updateStatus("麦克风占用中，暂不触发拍照");
            Log.w(TAG, "🔳 [硬件拍照] 失败：麦克风占用");
            return false;
        }

        if (isAnalyzing) {
            updateStatus("识别进行中，请稍候");
            Log.w(TAG, "🔳 [硬件拍照] 失败：已在分析中");
            return false;
        }

        if (now - lastTriggerTime <= COOLDOWN_MS) {
            updateStatus("触发过于频繁");
            Log.w(TAG, "🔳 [硬件拍照] 失败：防抖冷却中 gap=" + (now - lastTriggerTime) + "ms");
            return false;
        }

        if (lastRenderData == null) {
            updateStatus("尚未获取指尖位置，无法拍照");
            Log.w(TAG, "🔳 [硬件拍照] 失败：lastRenderData为null");
            return false;
        }

        Log.d(TAG, "🔳 [硬件拍照] 状态检查通过，准备拍照...");
        isAnalyzing = true;
        lastTriggerTime = now;
        triggerVibration();

        RecognizeTask.HighResYUVCache yuvCache = RecognizeTask.getLatestHighResYUV();
        Log.d(TAG, "🔳 [硬件拍照] YUV缓存: " + (yuvCache != null ? "有效" : "为null"));

        if (yuvCache == null) {
            updateStatus("未获取到高清帧，稍后重试");
            Log.w(TAG, "🔳 [硬件拍照] 失败：YUV缓存为null");
            isAnalyzing = false;
            return false;
        }

        // 先将完整高清帧转换为 RGB，并保存为 PNG（不裁剪，完整保留原图）
        Log.d(TAG, "🔳 [硬件拍照] 开始生成完整高清图");
        Bitmap fullHighResBitmap = convertFullYUVToRGB(yuvCache);
        if (fullHighResBitmap != null) {
            Log.d(TAG, "🔳 [硬件拍照] 完整高清图生成成功: " + fullHighResBitmap.getWidth() + "x" + fullHighResBitmap.getHeight());
            Bitmap saveCopy = fullHighResBitmap.copy(Bitmap.Config.ARGB_8888, true);
            saveDebugImage(saveCopy, lastRenderData.getTipX(), lastRenderData.getTipY());

            startCardSequence();

            ProcessorManager.normalExecutor.execute(() -> {
                try {
                    Log.d(TAG, "🔳 [硬件拍照] 线程池: 开始上传");
                    RecognizeTask uploadTask = new RecognizeTask(fullHighResBitmap);
                    SendRemoteProcessor processor = new SendRemoteProcessor();
                    RecognizeTask result = processor.process(uploadTask);
                    Log.i(TAG, "🔳 [硬件拍照] 高清全图上传完成, result=" + result);

                    runOnUiThread(() -> {
                        if (result != null && result.getRecognizeResult() != null) {
                            Log.d(TAG, "🔳 [硬件拍照] 识别结果: " + result.getRecognizeResult());
                            setCardText(result.getRecognizeResult(), "硬件触发识别成功", Color.GREEN);
                        } else {
                            Log.w(TAG, "🔳 [硬件拍照] 结果为空");
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "🔳 [硬件拍照] 上传失败", e);
                    runOnUiThread(() -> setCardText("❌ 识别失败", "网络错误，请重试", Color.RED));
                } finally {
                    if (fullHighResBitmap != null && !fullHighResBitmap.isRecycled()) {
                        fullHighResBitmap.recycle();
                    }
                    // 保持 isAnalyzing=true，等待用户张手关闭
                    Log.d(TAG, "🔳 [硬件拍照] 完成，等待张手关闭");
                }
            });
            return true;
        } else {
            updateStatus("未获取到高清帧，稍后重试");
            Log.w(TAG, "🔳 [硬件拍照] 失败：完整高清图生成失败");
            // 保持 isAnalyzing=true，等待用户张手关闭或冷却后再开
            return false;
        }
    }

    // 更新 AR 卡片位置
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

            // 使用实际父容器尺寸而非整机分辨率，避免坐标超出眼镜视区
            int parentW = binding.getRoot().getWidth();
            int parentH = binding.getRoot().getHeight();
            if (parentW == 0 || parentH == 0) {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                parentW = metrics.widthPixels;
                parentH = metrics.heightPixels;
            }

            // 指尖丢失或越界时，使用默认位置（屏幕偏右且居中略上）
            boolean tipInvalid = (tipX <= 0.02f || tipX >= 0.98f || tipY <= 0.02f || tipY >= 0.98f);
            float safeTipX = tipInvalid ? 0.58f : tipX; // 更靠中心，避免过右
            float safeTipY = tipInvalid ? 0.52f : tipY;

            float baseX = safeTipX * parentW;
            float baseY = safeTipY * parentH;

            float offsetX = 40f; // 轻微右移，避免遮挡指尖
            float offsetY = -80f; // 上移，避免超出视野

            float tempY = baseY + offsetY;
            if (tempY < 0)
                tempY = 20;

            float finalX = baseX + offsetX;
            float finalY = tempY;

            // 简单低通滤波，改善跟随平滑度
            if (lastCardX >= 0 && lastCardY >= 0) {
                finalX = lastCardX * 0.6f + finalX * 0.4f;
                finalY = lastCardY * 0.6f + finalY * 0.4f;
            }

            // 限制在屏幕范围内，避免被镜像裁剪
            int cardW = cardRoot.getWidth();
            int cardH = cardRoot.getHeight();
            if (cardW == 0)
                cardW = cardRoot.getMeasuredWidth();
            if (cardH == 0)
                cardH = cardRoot.getMeasuredHeight();
            finalX = Math.max(0, Math.min(finalX, parentW - cardW));
            finalY = Math.max(0, Math.min(finalY, parentH - cardH));

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

            Log.d(TAG, String.format(
                    "✅ 卡片位置已更新: parent=%dx%d card=%dx%d tipXY=(%.2f,%.2f) safe=(%.2f,%.2f) -> finalXY=(%.0f, %.0f)",
                    parentW, parentH, cardW, cardH, tipX, tipY, safeTipX, safeTipY, finalX, finalY));
            return null;
        });
    }

    // 显示 AR 卡片 (初始状态)
    private void startCardSequence() {
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot != null) {
                lastCardX = -1f;
                lastCardY = -1f;
                cardRoot.setVisibility(View.VISIBLE);
                cardRoot.bringToFront();
                Log.d(TAG, "✅ [卡片已显示] via mBindingPair");
                setCardText("🔍 分析中...", "请稍候...", Color.YELLOW);
            } else {
                Log.e(TAG, "❌ 卡片为null，无法显示");
            }
            return null;
        });
    }

    // 关闭 AR 卡片
    private void closeCard() {
        mBindingPair.updateView(binding -> {
            View cardRoot = binding.includeArCard.getRoot();
            if (cardRoot != null) {
                cardRoot.setVisibility(View.GONE);
                Log.d(TAG, "✅ [卡片已关闭] via mBindingPair");
            }
            return null;
        });
        isAnalyzing = false;
        RenderProcessor.getInstance().setLocked(false);
        RenderProcessor.getInstance().setCloseProgress(0f);
        openPalmStartTime = 0;
        lastCardX = -1f;
        lastCardY = -1f;
        updateStatus("卡片已关闭");
        triggerVibration();
    }

    // 设置卡片文字
    private void setCardText(String title, String content, int color) {
        mBindingPair.updateView(binding -> {
            if (binding.includeArCard.tvCardTitle != null && binding.includeArCard.tvCardContent != null) {
                binding.includeArCard.tvCardTitle.setText(title);
                binding.includeArCard.tvCardTitle.setTextColor(color);
                binding.includeArCard.tvCardContent.setText(content);
                Log.d(TAG, "✅ [卡片文本已更新] title=" + title);
            }
            return null;
        });
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