package com.narc.arclient;

import android.content.Context;
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
import android.widget.TextView;
import android.widget.Toast;

import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity;
import com.narc.arclient.camera.ICameraManager;
import com.narc.arclient.databinding.ActivityMainBinding;
import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.entity.RenderData;
import com.narc.arclient.process.ProcessorManager;
import com.narc.arclient.process.processor.RecognizeProcessor;
import com.narc.arclient.process.processor.RenderProcessor;

public class MainActivity extends BaseMirrorActivity<ActivityMainBinding> {

    private static final String TAG = "SilverSight";
    private CustomDrawView customDrawView;
    private TextView tvStatusLeft, tvStatusRight;

    private View cardLeft, cardRight;
    private TextView tvTitleLeft, tvContentLeft;
    private TextView tvTitleRight, tvContentRight;

    private boolean isAnalyzing = false;
    private long lastTriggerTime = 0;
    private static final long COOLDOWN_MS = 2000;

    // 👇 新增：麦克风状态
    private boolean isMicEnabled = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            RenderProcessor.init(this);
            RecognizeProcessor.init(this);
            ProcessorManager.init(this);
        } catch (Exception e) {
            Log.e(TAG, "Init Error", e);
        }

        tvStatusLeft = findViewById(R.id.tv_status_left);
        tvStatusRight = findViewById(R.id.tv_status_right);

        cardLeft = findViewById(R.id.include_ar_card_left);
        if (cardLeft != null) {
            tvTitleLeft = cardLeft.findViewById(R.id.tv_card_title);
            tvContentLeft = cardLeft.findViewById(R.id.tv_card_content);
        }
        cardRight = findViewById(R.id.include_ar_card_right);
        if (cardRight != null) {
            tvTitleRight = cardRight.findViewById(R.id.tv_card_title);
            tvContentRight = cardRight.findViewById(R.id.tv_card_content);
        }

        initCustomView();

        View debugPanel = findViewById(R.id.debug_panel);
        if (isEmulator()) {
            if (debugPanel != null)
                debugPanel.setVisibility(View.VISIBLE);
            updateStatus("模式：模拟器");
            View btnMock = findViewById(R.id.btn_mock_data);
            if (btnMock != null) {
                btnMock.setOnClickListener(v -> {
                    // 模拟数据需要适配新的构造函数
                    RenderData mockData = new RenderData(0.5f, 0.5f, 1.0f, true, false, null, false, 0f, false);
                    updateView(mockData, null);
                });
            }
        } else {
            if (debugPanel != null)
                debugPanel.setVisibility(View.GONE);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    ICameraManager.init(this);
                    updateStatus("摄像头已启动");
                } catch (Exception e) {
                }
            }, 1000);
        }
    }

    public void updateStatus(String msg) {
        runOnUiThread(() -> {
            if (tvStatusLeft != null)
                tvStatusLeft.setText(msg);
            if (tvStatusRight != null)
                tvStatusRight.setText(msg);
        });
    }

    private void initCustomView() {
        customDrawView = new CustomDrawView(this);
        addContentView(customDrawView, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void updateView(RenderData renderData, RecognizeTask recognizeTask) {
        // 1. 只更新数据源，不调用 invalidate()
        RenderProcessor.getInstance().setRenderData(renderData);

        if (renderData == null)
            return;
        if (renderData.isMicTriggered()) {
            toggleMicState();
        }

        if (isAnalyzing) {
            if (renderData.isOpenPalm()) {
                closeCard();
                return;
            }
            updateCardPosition(renderData.getTipX(), renderData.getTipY());
        } else {
            if (renderData.isTriggered() && !renderData.isMicHovered()) {
                long now = System.currentTimeMillis();
                if (now - lastTriggerTime > COOLDOWN_MS) {
                    isAnalyzing = true;
                    lastTriggerTime = now;
                    triggerVibration();
                    if (recognizeTask != null && recognizeTask.getOriginBitmap() != null) {
                        saveDebugImage(recognizeTask.getOriginBitmap(), renderData.getTipX(), renderData.getTipY());
                    }
                    startCardSequence();
                }
            }
        }
    }

    private void toggleMicState() {
        isMicEnabled = !isMicEnabled;
        RenderProcessor.getInstance().setMicState(isMicEnabled);
        triggerVibration();
        String status = isMicEnabled ? "🎙️ 字幕已开启" : "🔇 字幕已关闭";
        updateStatus(status);
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show();
    }
    // ... 下面的 updateCardPosition, startCardSequence, closeCard, setCardText
    // ... 以及 saveDebugImage, triggerVibration 保持不变，请确保它们还在代码里
    // ... 这里省略以节省篇幅，请保留你原有的实现

    private void updateCardPosition(float tipX, float tipY) {
        if (cardLeft == null || cardRight == null)
            return;
        if (cardLeft.getVisibility() != View.VISIBLE)
            return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float screenW = metrics.widthPixels;
        float screenH = metrics.heightPixels;
        float halfW = screenW / 2.0f;
        float leftBaseX = tipX * halfW;
        float rightBaseX = leftBaseX + halfW;
        float baseY = tipY * screenH;
        float offsetX = 50f;
        float offsetY = -250f;
        float tempY = baseY + offsetY;
        if (tempY < 0)
            tempY = 20;
        float finalLeftX = leftBaseX + offsetX;
        float finalRightX = rightBaseX + offsetX;
        float finalY = tempY;
        runOnUiThread(() -> {
            cardLeft.setX(finalLeftX);
            cardLeft.setY(finalY);
            cardRight.setX(finalRightX);
            cardRight.setY(finalY);
        });
    }

    private void startCardSequence() {
        runOnUiThread(() -> {
            cardLeft.setVisibility(View.VISIBLE);
            cardRight.setVisibility(View.VISIBLE);
            cardLeft.bringToFront();
            cardRight.bringToFront();
            setCardText("🔍 正在识别...", "云端分析中...", Color.YELLOW);
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isAnalyzing) {
                runOnUiThread(() -> {
                    setCardText("阿莫西林胶囊", "每日3次，每次2粒\n饭后温水服用", Color.GREEN);
                    triggerVibration();
                });
            }
        }, 1500);
    }

    private void closeCard() {
        runOnUiThread(() -> {
            if (cardLeft != null)
                cardLeft.setVisibility(View.GONE);
            if (cardRight != null)
                cardRight.setVisibility(View.GONE);
            triggerVibration();
        });
        isAnalyzing = false;
        updateStatus("卡片已关闭");
    }

    private void setCardText(String title, String content, int color) {
        if (tvTitleLeft != null) {
            tvTitleLeft.setText(title);
            tvTitleLeft.setTextColor(color);
            tvContentLeft.setText(content);
        }
        if (tvTitleRight != null) {
            tvTitleRight.setText(title);
            tvTitleRight.setTextColor(color);
            tvContentRight.setText(content);
        }
    }

    private void triggerVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null)
                v.vibrate(100);
        } catch (Exception e) {
        }
    }

    private void saveDebugImage(Bitmap originalBitmap, float x, float y) {
        new Thread(() -> {
            try {
                Bitmap mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
                Canvas canvas = new Canvas(mutableBitmap);
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStrokeWidth(5f);
                paint.setStyle(Paint.Style.STROKE);
                float pixelX = x * mutableBitmap.getWidth();
                float pixelY = y * mutableBitmap.getHeight();
                canvas.drawCircle(pixelX, pixelY, 40f, paint);
                String title = "AR_" + System.currentTimeMillis();
                MediaStore.Images.Media.insertImage(getContentResolver(), mutableBitmap, title, "Debug");
                mutableBitmap.recycle();
            } catch (Exception e) {
                Log.e(TAG, "Save Error", e);
            }
        }).start();
    }

    public class CustomDrawView extends View {
        public CustomDrawView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            RenderProcessor.getInstance().draw(canvas);

            // ✅ 关键：请求下一帧立即重绘
            // 这会创建一个 60FPS 的连续渲染循环，配合 RenderProcessor 里的 smoothX 插值算法
            // 即使 AI 每秒只返回 15 次数据，这里也会每秒画 60 次，产生丝滑的补间动画
            postInvalidateOnAnimation();
        }
    }

    private boolean isEmulator() {
        return android.os.Build.MODEL.contains("Emulator") || android.os.Build.BRAND.startsWith("generic");
    }
}