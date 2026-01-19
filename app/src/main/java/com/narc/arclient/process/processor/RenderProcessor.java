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

    private boolean isMicOn = false;

    // 👇 1. 新增：平滑滤波变量
    private float smoothX = -1f; // 当前光标的显示坐标
    private float smoothY = -1f;
    // 平滑因子 (0.0 ~ 1.0)：越小越平滑但延迟越高，越大越跟手但抖动越大
    // 推荐 0.3 ~ 0.5
    private static final float SMOOTH_FACTOR = 0.4f;

    private RenderProcessor(Context context) {
        this.context = context;
        initPaints();
    }

    public static void init(Context context) {
        if (instance == null) instance = new RenderProcessor(context);
    }

    public static RenderProcessor getInstance() { return instance; }

    public void setRenderData(RenderData data) {
        this.renderData = data;
        // 注意：这里我们不再直接赋值给 coordinates，而是只更新 data 数据源
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
        if (canvas == null) return;

        int w = canvas.getWidth();
        int h = canvas.getHeight();

        // ================= 1. 绘制麦克风按钮 =================
        float btnX = w * 0.9f;
        float btnY = h * 0.85f;
        float btnRadius = 50f;

        if (isMicOn) {
            paintButton.setColor(Color.parseColor("#00AA00"));
        } else {
            paintButton.setColor(Color.parseColor("#CC0000"));
        }

        if (renderData != null && renderData.isMicHovered()) {
            btnRadius = 60f;
        }

        canvas.drawCircle(btnX, btnY, btnRadius, paintButton);
        float textY = btnY + 10;
        canvas.drawText(isMicOn ? "MIC ON" : "MIC OFF", btnX, textY, paintText);

        if (renderData != null && renderData.getMicProgress() > 0) {
            RectF btnRect = new RectF(btnX - btnRadius, btnY - btnRadius, btnX + btnRadius, btnY + btnRadius);
            btnRect.inset(-10, -10);
            canvas.drawArc(btnRect, -90, renderData.getMicProgress() * 360, false, paintProgress);
        }

        // ================= 2. 绘制指尖光标 (带平滑算法) =================
        if (renderData != null) {

            // 目标坐标 (Raw Target)
            float targetX = renderData.getTipX() * w+ 570f;
            float targetY = renderData.getTipY() * h- 130f;

            // 👇 2. 核心算法：插值平滑 (Lerp)
            // 如果是第一次绘制 (smoothX 为 -1)，直接跳过去，避免从 (0,0) 飞过来
            if (smoothX < 0 || smoothY < 0) {
                smoothX = targetX;
                smoothY = targetY;
            } else {
                // 公式：当前位置 = 当前位置 + (差距 * 因子)
                smoothX = smoothX + (targetX - smoothX) * SMOOTH_FACTOR;
                smoothY = smoothY + (targetY - smoothY) * SMOOTH_FACTOR;
            }

            // 使用平滑后的 smoothX, smoothY 进行绘制
            canvas.drawCircle(smoothX, smoothY, 30f, paintCursor);

            if (renderData.getProgress() > 0 && !renderData.isMicHovered()) {
                RectF rect = new RectF(smoothX - 30, smoothY - 30, smoothX + 30, smoothY + 30);
                canvas.drawArc(rect, -90, renderData.getProgress() * 360, false, paintProgress);
            }
        }
    }
}