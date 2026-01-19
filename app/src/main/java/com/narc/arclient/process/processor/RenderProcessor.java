package com.narc.arclient.process.processor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import com.narc.arclient.entity.RenderData;

public class RenderProcessor {

    private static RenderProcessor instance;
    private Context context;
    private RenderData renderData;

    // 画笔
    private Paint paintCursor;
    private Paint paintProgress;
    private Paint paintButton;
    private Paint paintText;

    // 平滑滤波变量
    private float smoothX = -1f;
    private float smoothY = -1f;

    // 👇👇👇【优化后的参数 - 提升跟手性】👇👇👇

    // 1. 振幅放大倍数（让手指移动距离对应更大的屏幕位移）
    private static final float SCALE_X = 1.6f; // X轴放大倍数，可调 1.2~1.6
    private static final float SCALE_Y = 1.2f; // Y轴放大倍数，可调 1.0~1.4

    // 2. 偏移量（微调位置）
    private static final float OFFSET_X = -170f; // 负值向左，正值向右
    private static final float OFFSET_Y = -150f; // 负值向上，正值向下

    // 3. 平滑算法参数
    private static final float MIN_FACTOR = 0.2f; // 静止时的稳定性（降低抖动）
    private static final float MAX_FACTOR = 1.0f; // 运动时的跟手度（完全跟随）
    private static final float JITTER_THRESHOLD = 2.0f; // 手抖阈值（像素）
    private static final float MOVE_THRESHOLD = 40.0f; // 快速移动阈值（像素）

    // 4. 快速贴合阈值（大幅移动时直接跳转）
    private static final float FAST_SNAP_DISTANCE = 180.0f; // 超过此距离直接贴合

    private boolean isMicOn = false;

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
    }

    private void initPaints() {
        paintCursor = new Paint();
        paintCursor.setColor(Color.WHITE);
        paintCursor.setStyle(Paint.Style.STROKE);
        paintCursor.setStrokeWidth(5f);
        paintCursor.setAntiAlias(true);

        paintProgress = new Paint();
        paintProgress.setColor(Color.GREEN);
        paintProgress.setStyle(Paint.Style.STROKE);
        paintProgress.setStrokeWidth(8f);
        paintProgress.setAntiAlias(true);

        paintButton = new Paint();
        paintButton.setStyle(Paint.Style.FILL);
        paintButton.setAntiAlias(true);

        paintText = new Paint();
        paintText.setColor(Color.WHITE);
        paintText.setTextSize(30f);
        paintText.setTextAlign(Paint.Align.CENTER);
        paintText.setFakeBoldText(true);
        paintText.setAntiAlias(true);
    }

    public void draw(Canvas canvas) {
        if (canvas == null)
            return;

        int w = canvas.getWidth();
        int h = canvas.getHeight();

        int halfW = w / 2;

        // 双目渲染（左右眼）
        drawEye(canvas, 0, halfW, h); // 左眼
        drawEye(canvas, halfW, halfW, h); // 右眼
    }

    private void drawEye(Canvas canvas, int offsetX, int w, int h) {
        // ================= 1. 绘制麦克风按钮 =================
        float btnLocalX = w * 0.9f;
        float btnY = h * 0.85f;
        float btnRadius = 50f;
        float realBtnX = offsetX + btnLocalX;

        if (isMicOn) {
            paintButton.setColor(Color.parseColor("#00AA00"));
        } else {
            paintButton.setColor(Color.parseColor("#CC0000"));
        }

        if (renderData != null && renderData.isMicHovered()) {
            btnRadius = 60f;
        }

        canvas.drawCircle(realBtnX, btnY, btnRadius, paintButton);
        float textY = btnY + 10;
        canvas.drawText(isMicOn ? "MIC ON" : "MIC OFF", realBtnX, textY, paintText);

        if (renderData != null && renderData.getMicProgress() > 0) {
            RectF btnRect = new RectF(realBtnX - btnRadius, btnY - btnRadius, realBtnX + btnRadius, btnY + btnRadius);
            btnRect.inset(-10, -10);
            canvas.drawArc(btnRect, -90, renderData.getMicProgress() * 360, false, paintProgress);
        }

        // ================= 2. 绘制指尖光标（坐标外推版）=================
        if (renderData != null) {
            // 原始归一化坐标
            float normalizedX = renderData.getTipX();
            float normalizedY = renderData.getTipY();

            // 👇 坐标映射参数（根据实际情况调整）
            float inputMinX = 0.15f; // 手指最左时的 tipX 值
            float inputMaxX = 0.85f; // 手指最右时的 tipX 值
            float inputMinY = 0.15f; // 手指最上时的 tipY 值
            float inputMaxY = 0.85f; // 手指最下时的 tipY 值

            // 线性外推映射
            float remappedX = (normalizedX - inputMinX) / (inputMaxX - inputMinX);
            float remappedY = (normalizedY - inputMinY) / (inputMaxY - inputMinY);

            // 防止超出范围
            remappedX = Math.max(0.0f, Math.min(1.0f, remappedX));
            remappedY = Math.max(0.0f, Math.min(1.0f, remappedY));

            // 振幅调整（现在可以用较小的值）
            float centeredX = (remappedX - 0.5f) * SCALE_X + 0.5f;
            float centeredY = (remappedY - 0.5f) * SCALE_Y + 0.5f;

            // 转换为像素坐标
            float targetLocalX = centeredX * w + OFFSET_X;
            float targetY = centeredY * h + OFFSET_Y;

            // 平滑算法（保持不变）
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
                    if (distance < JITTER_THRESHOLD) {
                        currentFactor = MIN_FACTOR;
                    } else if (distance > MOVE_THRESHOLD) {
                        currentFactor = MAX_FACTOR;
                    } else {
                        float progress = (distance - JITTER_THRESHOLD) / (MOVE_THRESHOLD - JITTER_THRESHOLD);
                        currentFactor = MIN_FACTOR + progress * (MAX_FACTOR - MIN_FACTOR);
                    }
                    smoothX = smoothX + dx * currentFactor;
                    smoothY = smoothY + dy * currentFactor;
                }
            }

            // 绘制
            float clampedLocalX = Math.max(30f, Math.min(w - 30f, smoothX));
            float clampedLocalY = Math.max(30f, Math.min(h - 30f, smoothY));
            float realCursorX = offsetX + clampedLocalX;
            float realCursorY = clampedLocalY;
            canvas.drawCircle(realCursorX, realCursorY, 30f, paintCursor);

            if (renderData.getProgress() > 0 && !renderData.isMicHovered()) {
                RectF rect = new RectF(realCursorX - 30, realCursorY - 30, realCursorX + 30, realCursorY + 30);
                canvas.drawArc(rect, -90, renderData.getProgress() * 360, false, paintProgress);
            }
        }

    }
}