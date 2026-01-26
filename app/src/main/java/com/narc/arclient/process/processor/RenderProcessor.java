package com.narc.arclient.process.processor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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

    // 字幕模拟按钮状态
    private boolean isSubtitleMockOn = false;
    private boolean isHoveringSubtitleBtn = false;
    private long subtitleHoverStartTime = 0;
    private float subtitleHoverProgress = 0f;
    private long lastSubtitleTriggerTime = 0;

    // 回调接口
    public interface OnMicStatusListener {
        void onMicClick(boolean isOn);
    }

    private OnMicStatusListener micListener;

    public void setOnMicStatusListener(OnMicStatusListener listener) {
        this.micListener = listener;
    }

    public interface OnSubtitleMockListener {
        void onSubtitleMockClick(boolean isOn);
    }

    private OnSubtitleMockListener subtitleMockListener;

    public void setOnSubtitleMockListener(OnSubtitleMockListener listener) {
        this.subtitleMockListener = listener;
    }

    private RenderProcessor(Context context) {
        this.context = context;
        initPaints();
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
    }

    // 锁定指针（识别期间不再显示绿色进度，不响应悬停触发）
    public void setLocked(boolean locked) {
        this.isLocked = locked;
        if (locked) {
            // 清除悬停状态，避免残留进度
            isHoveringBtn = false;
            hoverProgress = 0f;
            hoverStartTime = 0;
            isHoveringSubtitleBtn = false;
            subtitleHoverProgress = 0f;
            subtitleHoverStartTime = 0;
        }
    }

    public void setCloseProgress(float progress) {
        this.closeProgress = Math.max(0f, Math.min(1f, progress));
    }

    public void setSubtitleMockState(boolean isOn) {
        this.isSubtitleMockOn = isOn;
        isHoveringSubtitleBtn = false;
        subtitleHoverProgress = 0f;
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
        paintCursorProgress.setColor(Color.GREEN);
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

        // ================= 3. 碰撞检测 (含防误触冷却) =================
        if (!isLocked && isLeftEye && renderData != null) {
            // 麦克风按钮检测
            float dist = (float) Math.hypot(clampedLocalX - btnLocalX, clampedLocalY - btnY);
            boolean inCooldown = (System.currentTimeMillis() - lastTriggerTime) < COOLDOWN_MS;

            if (dist < (btnRadius + 30f + 15f) && !inCooldown) {
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

            // 字幕模拟按钮检测
            float subtitleDist = (float) Math.hypot(clampedLocalX - subtitleBtnLocalX, clampedLocalY - subtitleBtnY);
            boolean subtitleInCooldown = (System.currentTimeMillis() - lastSubtitleTriggerTime) < COOLDOWN_MS;

            if (subtitleDist < (subtitleBtnRadius + 30f + 15f) && !subtitleInCooldown) {
                if (!isHoveringSubtitleBtn) {
                    isHoveringSubtitleBtn = true;
                    subtitleHoverStartTime = System.currentTimeMillis();
                } else {
                    long duration = System.currentTimeMillis() - subtitleHoverStartTime;
                    subtitleHoverProgress = Math.min(1.0f, (float) duration / HOVER_TIME_MS);

                    if (duration >= HOVER_TIME_MS) {
                        if (subtitleMockListener != null)
                            subtitleMockListener.onSubtitleMockClick(!isSubtitleMockOn);
                        lastSubtitleTriggerTime = System.currentTimeMillis();
                        isHoveringSubtitleBtn = false;
                        subtitleHoverProgress = 0f;
                        subtitleHoverStartTime = 0;
                    }
                }
            } else {
                isHoveringSubtitleBtn = false;
                subtitleHoverProgress = 0f;
            }
        }

        // ================= 4. UI 绘制 =================

        // A. 麦克风按钮悬停黄色读条
        if (!isLocked && isHoveringBtn && hoverProgress > 0) {
            float ringGap = 12f;
            float progressRadius;
            if (!isMicOn) {
                progressRadius = btnRadius + ringGap;
            } else {
                progressRadius = (btnRadius * 1.3f) + ringGap;
            }
            RectF progressRect = new RectF(
                    realBtnX - progressRadius, btnY - progressRadius,
                    realBtnX + progressRadius, btnY + progressRadius);
            canvas.drawArc(progressRect, -90, hoverProgress * 360, false, paintBtnHover);
        }

        // B. 麦克风按钮本体
        if (!isMicOn) {
            // === 待机模式 ===
            canvas.drawCircle(realBtnX, btnY + 2f, btnRadius + 3f, paintBtnShadow); // 投影
            canvas.drawCircle(realBtnX, btnY, btnRadius, paintWhiteRing);
            canvas.drawCircle(realBtnX, btnY, btnRadius - 4f, paintRedFill);
            canvas.drawCircle(realBtnX, btnY, btnRadius - 7f, paintBtnHighlight);
        } else {
            // === 录音模式 ===
            float largeRingRadius = btnRadius * 1.3f;
            canvas.drawCircle(realBtnX, btnY + 2f, largeRingRadius + 3f, paintBtnShadow); // 投影
            canvas.drawCircle(realBtnX, btnY, largeRingRadius, paintWhiteRing);

            float squareSize = btnRadius * 0.9f;
            float halfSize = squareSize / 2f;
            RectF stopRect = new RectF(
                    realBtnX - halfSize, btnY - halfSize,
                    realBtnX + halfSize, btnY + halfSize);
            canvas.drawRoundRect(stopRect, squareSize * 0.2f, squareSize * 0.2f, paintRedFill);
            canvas.drawRoundRect(stopRect, squareSize * 0.2f, squareSize * 0.2f, paintBtnHighlight);
        }

        // C. 字幕模拟按钮悬停黄色读条
        if (!isLocked && isHoveringSubtitleBtn && subtitleHoverProgress > 0) {
            float ringGap = 12f;
            float progressRadius;
            if (!isSubtitleMockOn) {
                progressRadius = subtitleBtnRadius + ringGap;
            } else {
                progressRadius = (subtitleBtnRadius * 1.3f) + ringGap;
            }
            RectF progressRect = new RectF(
                    realSubtitleBtnX - progressRadius, subtitleBtnY - progressRadius,
                    realSubtitleBtnX + progressRadius, subtitleBtnY + progressRadius);
            canvas.drawArc(progressRect, -90, subtitleHoverProgress * 360, false, paintBtnHover);
        }

        // D. 字幕模拟按钮本体
        Paint paintSubtitleFill = new Paint();
        paintSubtitleFill.setColor(Color.parseColor("#00C7BE")); // 青色
        paintSubtitleFill.setStyle(Paint.Style.FILL);
        paintSubtitleFill.setAntiAlias(true);
        paintSubtitleFill.setShadowLayer(10f, 0f, 4f, 0x33000000);

        if (!isSubtitleMockOn) {
            // === 待机模式 ===
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY + 2f, subtitleBtnRadius + 3f, paintBtnShadow);
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius, paintWhiteRing);
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius - 4f, paintSubtitleFill);
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius - 7f, paintBtnHighlight);

            // 绘制 "CC" 字样
            Paint textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(28f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setAntiAlias(true);
            textPaint.setFakeBoldText(true);
            canvas.drawText("CC", realSubtitleBtnX, subtitleBtnY + 10f, textPaint);
        } else {
            // === 开启模式 ===
            float largeRingRadius = subtitleBtnRadius * 1.3f;
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY + 2f, largeRingRadius + 3f, paintBtnShadow);
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, largeRingRadius, paintWhiteRing);
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius, paintSubtitleFill);
            canvas.drawCircle(realSubtitleBtnX, subtitleBtnY, subtitleBtnRadius - 4f, paintBtnHighlight);

            // 绘制 "CC" 字样（更大）
            Paint textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(32f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setAntiAlias(true);
            textPaint.setFakeBoldText(true);
            canvas.drawText("CC", realSubtitleBtnX, subtitleBtnY + 11f, textPaint);
        }

        // E. 指尖光标
        if (renderData != null) {
            float realCursorX = offsetX + clampedLocalX;
            float realCursorY = clampedLocalY;
            // 柔和光晕（不改变主色）
            canvas.drawCircle(realCursorX, realCursorY, 40f, paintCursorGlow);
            // 主体描边
            canvas.drawCircle(realCursorX, realCursorY, 30f, paintCursor);

            // 进度颜色：未锁定显示绿色，锁定时改为更亮的灰色提示“冻结”
            if (isLocked) {
                paintCursorProgress.setColor(Color.parseColor("#AAAAAA"));
            } else {
                paintCursorProgress.setColor(Color.GREEN);
            }

            // 物体识别进度条 (仅在不悬停任何按钮且未锁定时显示)
            if (!isLocked && renderData.getProgress() > 0 && !isHoveringBtn && !isHoveringSubtitleBtn) {
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