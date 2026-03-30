package com.narc.arclient.process.processor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import com.narc.arclient.entity.RenderData;

public class RenderProcessor {

    private static final String TAG = "RenderProcessor";
    private static RenderProcessor instance;
    private Context context;
    private RenderData renderData;

    // ============ 画笔定义 ============
    private Paint paintCursor; // 白色指尖圈
    private Paint paintCursorProgress; // 指尖上的绿色进度条
    private Paint paintCloseProgress; // 张手关闭的红色读条
    private Paint paintCursorGlow; // 指尖柔和光晕

    // UI 画笔
    private Paint paintRedFill; // 苹果红 (实心)
    private Paint paintWhiteRing; // 白色圆环 (空心)
    private Paint paintBtnHover; // 按钮悬停读条 (赛博黄)
    private Paint paintBtnShadow; // 按钮投影
    private Paint paintBtnHighlight; // 按钮高光描边

    // 平滑滤波变量
    private float smoothX = -1f;
    private float smoothY = -1f;

    // 参数
    private static final float SCALE_X = 1.6f;
    private static final float SCALE_Y = 1.2f;
    private static final float OFFSET_X = -170f;
    private static final float OFFSET_Y = -150f;
    private static final float MIN_FACTOR = 0.2f;
    private static final float MAX_FACTOR = 1.0f;
    private static final float JITTER_THRESHOLD = 2.0f;
    private static final float MOVE_THRESHOLD = 40.0f;
    private static final float FAST_SNAP_DISTANCE = 180.0f;

    // 交互状态管理
    private boolean isMicOn = false;
    private boolean isLocked = false; // 识别后锁定，不再显示进度
    private float closeProgress = 0f; // 张手关闭的进度
    private boolean isHoveringBtn = false;
    private long hoverStartTime = 0;
    private float hoverProgress = 0f;
    private static final long HOVER_TIME_MS = 1000;

    // 👇👇👇【新增：防误触冷却参数】👇👇👇
    private static final long COOLDOWN_MS = 2000; // 冷却时间 2秒
    private long lastTriggerTime = 0; // 上次触发的时间戳

    // 模式切换按钮状态（原字幕模拟按钮）
    private int currentModeIndex = 0; // 0=拍照识药, 1=实时字幕, 2=拍照追问
    private Bitmap[] modeIconBitmaps = new Bitmap[3]; // 0=photo, 1=subtitle, 2=multi
    private Bitmap micStaticBitmap; // 左上角麦克风静态底图（与 GIF 首帧一致）
    private Movie micRecordingMovie; // 左上角麦克风录音态 GIF
    private long micMovieStartTime = 0L;
    private boolean isHoveringModeBtn = false;
    private boolean isCardBlockingGestureProgress = false;

    // 左眼真实光标在整屏中的归一化坐标（用于UI层命中判定）
    private boolean hasLeftCursorPoint = false;
    private float leftCursorNormX = 0f;
    private float leftCursorNormY = 0f;

    // 预留：后续可接入拍照识药模式的麦克风出现/消失动画
    private float micVisibilityProgress = 0f; // 拍照模式为默认模式，启动时麦克风隐藏

    // 模式切换动画状态
    private static final long ICON_BOUNCE_MS = 300; // 弹跳持续时长
    private static final long RIPPLE_MS = 400; // 波纹持续时长
    private long iconScaleStartTime = -1; // -1 表示无动画
    private long rippleStartTime = -1;
    private int rippleColor = 0;
    private long modeHoverStartTime = 0;
    private float modeHoverProgress = 0f;
    private long lastModeTriggerTime = 0;

    // 回调接口
    public interface OnMicStatusListener {
        void onMicClick(boolean isOn);
    }

    private OnMicStatusListener micListener;

    public void setOnMicStatusListener(OnMicStatusListener listener) {
        this.micListener = listener;
    }

    public interface OnModeSwitchListener {
        void onModeSwitch(int modeIndex); // 0=拍照, 1=字幕, 2=多模态
    }

    private OnModeSwitchListener modeSwitchListener;

    public void setOnModeSwitchListener(OnModeSwitchListener listener) {
        this.modeSwitchListener = listener;
    }

    private RenderProcessor(Context context) {
        this.context = context;
        initPaints();
        modeIconBitmaps[0] = BitmapFactory.decodeResource(context.getResources(),
                com.narc.arclient.R.drawable.ic_mode_photo);
        modeIconBitmaps[1] = BitmapFactory.decodeResource(context.getResources(),
                com.narc.arclient.R.drawable.ic_mode_subtitle);
        modeIconBitmaps[2] = BitmapFactory.decodeResource(context.getResources(),
                com.narc.arclient.R.drawable.ic_mode_multi);
        micStaticBitmap = BitmapFactory.decodeResource(context.getResources(),
                com.narc.arclient.R.drawable.ic_mic_static);
        try {
            micRecordingMovie = Movie.decodeStream(context.getResources()
                    .openRawResource(com.narc.arclient.R.raw.mic_recording));
        } catch (Exception e) {
            micRecordingMovie = null;
        }
    }

    public static void init(Context context) {
        if (instance == null)
            instance = new RenderProcessor(context);
    }

    public static RenderProcessor getInstance() {
        return instance;
    }

    public void setRenderData(RenderData data) {
        this.renderData = data;
    }

    public void setMicState(boolean isOn) {
        this.isMicOn = isOn;
        isHoveringBtn = false;
        hoverProgress = 0f;
        micMovieStartTime = 0L;
    }

    public void setMicVisibilityProgress(float progress) {
        this.micVisibilityProgress = Math.max(0f, Math.min(1f, progress));
    }

    public float getMicVisibilityProgress() {
        return micVisibilityProgress;
    }

    // 锁定指针（识别期间不再显示绿色进度，不响应悂停触发）
    public void setLocked(boolean locked) {
        this.isLocked = locked;
        if (locked) {
            // 清除搂停状态，避免残留进度
            isHoveringBtn = false;
            hoverProgress = 0f;
            hoverStartTime = 0;
            isHoveringModeBtn = false;
            modeHoverProgress = 0f;
            modeHoverStartTime = 0;
        }
    }

    public void setCloseProgress(float progress) {
        this.closeProgress = Math.max(0f, Math.min(1f, progress));
    }

    public void setCurrentMode(int modeIndex) {
        // 记录旧模式的颜色用于波纹
        int prevColor;
        switch (this.currentModeIndex) {
            case 0:
                prevColor = Color.parseColor("#FF9500");
                break;
            case 1:
                prevColor = Color.parseColor("#00C7BE");
                break;
            default:
                prevColor = Color.parseColor("#AF52DE");
                break;
        }
        this.currentModeIndex = modeIndex;
        this.closeProgress = 0f;
        isHoveringModeBtn = false;
        modeHoverProgress = 0f;
        modeHoverStartTime = 0;
        // 启动弹跳动画
        iconScaleStartTime = SystemClock.uptimeMillis();
        // 启动波纹（用旧模式颜色）
        rippleStartTime = SystemClock.uptimeMillis();
        rippleColor = prevColor;
    }

    public boolean isHoveringMicButton() {
        return isHoveringBtn;
    }

    public boolean isHoveringModeButton() {
        return isHoveringModeBtn;
    }

    public boolean isHoveringMainUiControl() {
        return isHoveringBtn || isHoveringModeBtn;
    }

    public void setCardBlockingGestureProgress(boolean blocking) {
        isCardBlockingGestureProgress = blocking;
    }

    public boolean hasLeftCursorPoint() {
        return hasLeftCursorPoint;
    }

    public float getLeftCursorNormX() {
        return leftCursorNormX;
    }

    public float getLeftCursorNormY() {
        return leftCursorNormY;
    }

    private void initPaints() {
        // 1. 指尖光标
        paintCursor = new Paint();
        paintCursor.setColor(Color.WHITE);
        paintCursor.setStyle(Paint.Style.STROKE);
        paintCursor.setStrokeWidth(5f);
        paintCursor.setAntiAlias(true);

        paintCursorGlow = new Paint();
        paintCursorGlow.setColor(Color.WHITE);
        paintCursorGlow.setStyle(Paint.Style.FILL);
        paintCursorGlow.setAlpha(28); // 柔和光晕，不改主色
        paintCursorGlow.setAntiAlias(true);

        // 2. 指尖上的进度条
        paintCursorProgress = new Paint();
        paintCursorProgress.setColor(Color.parseColor("#FF00FF"));
        paintCursorProgress.setStyle(Paint.Style.STROKE);
        paintCursorProgress.setStrokeWidth(8f);
        paintCursorProgress.setStrokeCap(Paint.Cap.ROUND);
        paintCursorProgress.setAntiAlias(true);

        // 张手关闭的红色读条
        paintCloseProgress = new Paint();
        paintCloseProgress.setColor(Color.parseColor("#FF3B30"));
        paintCloseProgress.setStyle(Paint.Style.STROKE);
        paintCloseProgress.setStrokeWidth(10f);
        paintCloseProgress.setStrokeCap(Paint.Cap.ROUND);
        paintCloseProgress.setAntiAlias(true);

        // 3. 按钮主体红色
        paintRedFill = new Paint();
        paintRedFill.setColor(Color.parseColor("#FF3B30"));
        paintRedFill.setStyle(Paint.Style.FILL);
        paintRedFill.setAntiAlias(true);
        paintRedFill.setShadowLayer(12f, 0f, 4f, 0x33000000);

        // 4. 按钮装饰环 (白色)
        paintWhiteRing = new Paint();
        paintWhiteRing.setColor(Color.WHITE);
        paintWhiteRing.setStyle(Paint.Style.STROKE);
        paintWhiteRing.setStrokeWidth(5f);
        paintWhiteRing.setAntiAlias(true);
        paintWhiteRing.setShadowLayer(8f, 0f, 3f, 0x22000000);

        // 5. 按钮悬停读条 (赛博黄)
        paintBtnHover = new Paint();
        paintBtnHover.setColor(Color.parseColor("#FFD600"));
        paintBtnHover.setStyle(Paint.Style.STROKE);
        paintBtnHover.setStrokeWidth(6f);
        paintBtnHover.setStrokeCap(Paint.Cap.ROUND);
        paintBtnHover.setAntiAlias(true);

        // 6. 按钮投影与高光（增强质感，颜色不变）
        paintBtnShadow = new Paint();
        paintBtnShadow.setColor(Color.BLACK);
        paintBtnShadow.setStyle(Paint.Style.FILL);
        paintBtnShadow.setAlpha(32);
        paintBtnShadow.setAntiAlias(true);
        paintBtnShadow.setShadowLayer(14f, 0f, 6f, 0x33000000);

        paintBtnHighlight = new Paint();
        paintBtnHighlight.setColor(Color.WHITE);
        paintBtnHighlight.setStyle(Paint.Style.STROKE);
        paintBtnHighlight.setStrokeWidth(3f);
        paintBtnHighlight.setAlpha(60);
        paintBtnHighlight.setAntiAlias(true);
    }

    public void draw(Canvas canvas) {
        if (canvas == null)
            return;
        hasLeftCursorPoint = false;
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        int halfW = w / 2;
        drawEye(canvas, 0, halfW, h, true); // 左眼
        drawEye(canvas, halfW, halfW, h, false); // 右眼
    }

    private void drawEye(Canvas canvas, int offsetX, int w, int h, boolean isLeftEye) {
        // ================= 1. 位置定义 =================
        // 麦克风按钮 (左上角)
        float btnLocalX = w * 0.12f;
        float btnY = h * 0.25f;
        float btnRadius = 40f;
        float realBtnX = offsetX + btnLocalX;

        // 字幕模拟按钮 (右上角)
        float subtitleBtnLocalX = w * 0.88f;
        float subtitleBtnY = h * 0.25f;
        float subtitleBtnRadius = 40f;
        float realSubtitleBtnX = offsetX + subtitleBtnLocalX;

        // ================= 2. 坐标计算 =================
        float clampedLocalX = smoothX;
        float clampedLocalY = smoothY;

        if (renderData != null) {
            float normalizedX = renderData.getTipX();
            float normalizedY = renderData.getTipY();
            float inputMinX = 0.15f;
            float inputMaxX = 0.85f;
            float inputMinY = 0.15f;
            float inputMaxY = 0.85f;
            float remappedX = (normalizedX - inputMinX) / (inputMaxX - inputMinX);
            float remappedY = (normalizedY - inputMinY) / (inputMaxY - inputMinY);
            remappedX = Math.max(0.0f, Math.min(1.0f, remappedX));
            remappedY = Math.max(0.0f, Math.min(1.0f, remappedY));
            float centeredX = (remappedX - 0.5f) * SCALE_X + 0.5f;
            float centeredY = (remappedY - 0.5f) * SCALE_Y + 0.5f;
            float targetLocalX = centeredX * w + OFFSET_X;
            float targetY = centeredY * h + OFFSET_Y;

            if (smoothX < 0 || smoothY < 0) {
                smoothX = targetLocalX;
                smoothY = targetY;
            } else {
                float dx = targetLocalX - smoothX;
                float dy = targetY - smoothY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance > FAST_SNAP_DISTANCE) {
                    smoothX = targetLocalX;
                    smoothY = targetY;
                } else {
                    float currentFactor;
                    if (distance < JITTER_THRESHOLD)
                        currentFactor = MIN_FACTOR;
                    else if (distance > MOVE_THRESHOLD)
                        currentFactor = MAX_FACTOR;
                    else {
                        float progress = (distance - JITTER_THRESHOLD) / (MOVE_THRESHOLD - JITTER_THRESHOLD);
                        currentFactor = MIN_FACTOR + progress * (MAX_FACTOR - MIN_FACTOR);
                    }
                    smoothX = smoothX + dx * currentFactor;
                    smoothY = smoothY + dy * currentFactor;
                }
            }
            clampedLocalX = Math.max(30f, Math.min(w - 30f, smoothX));
            clampedLocalY = Math.max(30f, Math.min(h - 30f, smoothY));
        }

        float micVisible = Math.max(0f, Math.min(1f, micVisibilityProgress));
        boolean micInteractable = micVisible > 0.05f;

        // ================= 3. 碰撞检测 (含防误触冷却) =================
        if (!isLocked && isLeftEye && renderData != null) {
            // 麦克风按钮检测
            float dist = (float) Math.hypot(clampedLocalX - btnLocalX, clampedLocalY - btnY);
            boolean inCooldown = (System.currentTimeMillis() - lastTriggerTime) < COOLDOWN_MS;

            if (micInteractable && dist < (btnRadius + 30f + 15f) && !inCooldown) {
                if (!isHoveringBtn) {
                    isHoveringBtn = true;
                    hoverStartTime = System.currentTimeMillis();
                } else {
                    long duration = System.currentTimeMillis() - hoverStartTime;
                    hoverProgress = Math.min(1.0f, (float) duration / HOVER_TIME_MS);

                    if (duration >= HOVER_TIME_MS) {
                        Log.d(TAG, "🎤 麦克风按钮触发: " + !isMicOn);
                        if (micListener != null) {
                            micListener.onMicClick(!isMicOn);
                        } else {
                            Log.w(TAG, "⚠️ micListener 为空，无法触发回调");
                        }
                        lastTriggerTime = System.currentTimeMillis();
                        isHoveringBtn = false;
                        hoverProgress = 0f;
                        hoverStartTime = 0;
                    }
                }
            } else {
                isHoveringBtn = false;
                hoverProgress = 0f;
            }

            // 模式切换按钮检测（原字幕模拟按钮位置）
            float modeDist = (float) Math.hypot(clampedLocalX - subtitleBtnLocalX, clampedLocalY - subtitleBtnY);
            boolean modeInCooldown = (System.currentTimeMillis() - lastModeTriggerTime) < COOLDOWN_MS;

            if (modeDist < (subtitleBtnRadius + 30f + 15f) && !modeInCooldown) {
                if (!isHoveringModeBtn) {
                    isHoveringModeBtn = true;
                    modeHoverStartTime = System.currentTimeMillis();
                } else {
                    long duration = System.currentTimeMillis() - modeHoverStartTime;
                    modeHoverProgress = Math.min(1.0f, (float) duration / HOVER_TIME_MS);

                    if (duration >= HOVER_TIME_MS) {
                        // 切换到下一个模式
                        int nextMode = (currentModeIndex + 1) % 3;
                        if (modeSwitchListener != null) {
                            modeSwitchListener.onModeSwitch(nextMode);
                        }
                        lastModeTriggerTime = System.currentTimeMillis();
                        isHoveringModeBtn = false;
                        modeHoverProgress = 0f;
                        modeHoverStartTime = 0;
                    }
                }
            } else {
                isHoveringModeBtn = false;
                modeHoverProgress = 0f;
            }
        }

        // ================= 4. UI 绘制 =================

        // A. 麦克风按钮悬停黄色读条
        if (micInteractable && !isLocked && isHoveringBtn && hoverProgress > 0) {
            float ringGap = 12f;
            float progressRadius = btnRadius + ringGap;
            RectF progressRect = new RectF(
                    realBtnX - progressRadius, btnY - progressRadius,
                    realBtnX + progressRadius, btnY + progressRadius);
            int hoverAlpha = (int) (255 * micVisible);
            int oldHoverAlpha = paintBtnHover.getAlpha();
            paintBtnHover.setAlpha(hoverAlpha);
            canvas.drawArc(progressRect, -90, hoverProgress * 360, false, paintBtnHover);
            paintBtnHover.setAlpha(oldHoverAlpha);
        }

        // B. 麦克风按钮本体
        if (micVisible > 0.01f) {
            float contentSize = btnRadius * 2.05f;
            RectF micDst = new RectF(
                    realBtnX - contentSize / 2f,
                    btnY - contentSize / 2f,
                    realBtnX + contentSize / 2f,
                    btnY + contentSize / 2f);

            if (isMicOn && micRecordingMovie != null) {
                long now = SystemClock.uptimeMillis();
                if (micMovieStartTime == 0L) {
                    micMovieStartTime = now;
                }

                int duration = micRecordingMovie.duration();
                if (duration <= 0) {
                    duration = 1000;
                }
                int relTime = (int) ((now - micMovieStartTime) % duration);
                micRecordingMovie.setTime(relTime);

                int layer = canvas.saveLayerAlpha(micDst.left, micDst.top, micDst.right, micDst.bottom,
                        (int) (255 * micVisible));
                canvas.save();
                float scaleX = contentSize / Math.max(1, micRecordingMovie.width());
                float scaleY = contentSize / Math.max(1, micRecordingMovie.height());
                canvas.translate(micDst.left, micDst.top);
                canvas.scale(scaleX, scaleY);
                micRecordingMovie.draw(canvas, 0f, 0f);
                canvas.restore();
                canvas.restoreToCount(layer);
            } else if (micStaticBitmap != null) {
                Paint micPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                micPaint.setFilterBitmap(true);
                micPaint.setAlpha((int) (255 * micVisible));
                canvas.drawBitmap(micStaticBitmap, null, micDst, micPaint);
            } else {
                paintRedFill.setAlpha((int) (255 * micVisible));
                canvas.drawCircle(realBtnX, btnY, btnRadius - 4f, paintRedFill);
                paintRedFill.setAlpha(255);
            }
        }

        // C. 模式切换按钮搂停黄色读条
        if (!isLocked && isHoveringModeBtn && modeHoverProgress > 0) {
            float ringGap = 12f;
            float progressRadius = subtitleBtnRadius + ringGap;
            RectF progressRect = new RectF(
                    realSubtitleBtnX - progressRadius, subtitleBtnY - progressRadius,
                    realSubtitleBtnX + progressRadius, subtitleBtnY + progressRadius);
            canvas.drawArc(progressRect, -90, modeHoverProgress * 360, false, paintBtnHover);
        }

        // D. 模弋切换按钮本体（根据当前模式显示不同图标）
        // D. 模式切换按钮本体（根据当前模式显示不同图标）
        // 根据模式设置颜色
        Paint paintModeFill = new Paint();
        paintModeFill.setStyle(Paint.Style.FILL);
        paintModeFill.setAntiAlias(true);
        paintModeFill.setShadowLayer(10f, 0f, 4f, 0x33000000);

        switch (currentModeIndex) {
            case 0: // 拍照识药
                paintModeFill.setColor(Color.parseColor("#FF9500")); // 橙色
                break;
            case 1: // 实时字幕
                paintModeFill.setColor(Color.parseColor("#00C7BE")); // 青色
                break;
            case 2: // 拍照追问
                paintModeFill.setColor(Color.parseColor("#AF52DE")); // 紫色
                break;
        }

        // C. 波纹动画（在按钮底层先画，避免遮住按钮本体）
        if (rippleStartTime >= 0) {
            float rippleT = Math.min(1f, (float) (SystemClock.uptimeMillis() - rippleStartTime) / RIPPLE_MS);
            if (rippleT < 1f) {
                // 半径从 btnRadius 扩散到 btnRadius*2.6
                float rippleRadius = subtitleBtnRadius + (subtitleBtnRadius * 1.6f) * rippleT;
                // alpha 从 200 衰减到 0
                int rippleAlpha = (int) (200 * (1f - rippleT));
                Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                ripplePaint.setStyle(Paint.Style.STROKE);
                ripplePaint.setStrokeWidth(4f);
                ripplePaint.setColor(rippleColor);
                ripplePaint.setAlpha(rippleAlpha);
                canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, rippleRadius, ripplePaint);
            } else {
                rippleStartTime = -1;
            }
        }

        // 绘制按钮
        canvas.drawCircle(realSubtitleBtnX, subtitleBtnY + 2f, subtitleBtnRadius + 3f, paintBtnShadow);
        canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius, paintWhiteRing);
        canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius - 4f, paintModeFill);
        canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius - 7f, paintBtnHighlight);

        // 绘制模式图标（A. 弹跳缩放动画）
        Bitmap icon = modeIconBitmaps[currentModeIndex];
        if (icon != null) {
            // 计算弹跳缩放系数：0→1.18→1.0（overshoot spring）
            float scale = 1f;
            if (iconScaleStartTime >= 0) {
                float t = Math.min(1f, (float) (SystemClock.uptimeMillis() - iconScaleStartTime) / ICON_BOUNCE_MS);
                if (t < 1f) {
                    // 简单 overshoot：sin 曲线，峰值在 t≈0.5 时达到 1.18
                    scale = 1f + 0.18f * (float) Math.sin(t * Math.PI);
                } else {
                    scale = 1f;
                    iconScaleStartTime = -1;
                }
            }
            float iconSize = (subtitleBtnRadius - 8f) * 2f * scale;
            float iconLeft = realSubtitleBtnX - iconSize / 2f;
            float iconTop = subtitleBtnY - iconSize / 2f;
            RectF dst = new RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setFilterBitmap(true);
            canvas.drawBitmap(icon, null, dst, iconPaint);
        }

        // E. 指尖光标
        if (renderData != null) {
            // 未识别手势时 RecognizeProcessor 会返回默认兜底坐标 (1.0, 1.0)。
            // 保持默认位置逻辑不变，但该状态下不绘制指尖圈。
            boolean isDefaultFallbackTip = renderData.getTipX() >= 0.999f && renderData.getTipY() >= 0.999f;
            if (isDefaultFallbackTip) {
                return;
            }

            float realCursorX = offsetX + clampedLocalX;
            float realCursorY = clampedLocalY;

            if (isLeftEye) {
                // 关键：命中判定使用的是单眼UI根布局，因此这里必须基于“单眼视区”归一化。
                // 若按整屏宽度归一化，会导致按钮命中位置整体偏移（常见表现：不高亮、不触发）。
                float eyeWidth = Math.max(1f, w);
                float eyeHeight = Math.max(1f, h);
                leftCursorNormX = Math.max(0f, Math.min(1f, clampedLocalX / eyeWidth));
                leftCursorNormY = Math.max(0f, Math.min(1f, clampedLocalY / eyeHeight));
                hasLeftCursorPoint = true;
            }

            // 柔和光晕（不改变主色）
            canvas.drawCircle(realCursorX, realCursorY, 40f, paintCursorGlow);
            // 主体描边
            canvas.drawCircle(realCursorX, realCursorY, 30f, paintCursor);

            // 进度颜色：未锁定显示洋红，锁定时改为更亮的灰色提示“冻结”
            if (isLocked) {
                paintCursorProgress.setColor(Color.parseColor("#AAAAAA"));
            } else {
                paintCursorProgress.setColor(Color.parseColor("#FF00FF"));
            }

            // 物体识别进度条 (仅在不悬停任何按钮且未锁定时显示)
            if (!isLocked
                    && !isCardBlockingGestureProgress
                    && renderData.getProgress() > 0
                    && !isHoveringBtn
                    && !isHoveringModeBtn) {
                RectF rect = new RectF(realCursorX - 30, realCursorY - 30, realCursorX + 30, realCursorY + 30);
                canvas.drawArc(rect, -90, renderData.getProgress() * 360, false, paintCursorProgress);
            }

            // 张手关闭读条（锁定期间可见），优先绘制红色
            if (closeProgress > 0f) {
                RectF rect = new RectF(realCursorX - 34, realCursorY - 34, realCursorX + 34, realCursorY + 34);
                canvas.drawArc(rect, -90, closeProgress * 360, false, paintCloseProgress);
            }
        }
    }
}