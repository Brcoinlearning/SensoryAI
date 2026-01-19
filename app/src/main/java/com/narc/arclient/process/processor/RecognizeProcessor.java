package com.narc.arclient.process.processor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer.GestureRecognizerOptions;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import com.narc.arclient.MainActivity;
import com.narc.arclient.entity.RecognizeTask;
import com.narc.arclient.entity.RenderData;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecognizeProcessor {

    private static final String TAG = "SilverSight";
    private static RecognizeProcessor instance;
    private Context context;
    private GestureRecognizer gestureRecognizer;

    // FPS 统计
    private long lastFrameTime = 0;
    private int frameCount = 0;

    private float lastX = 0f;
    private float lastY = 0f;
    private long hoverStartTime = 0;
    private long micHoverStartTime = 0;

    private static final float MOVE_THRESHOLD = 0.05f;
    private static final long HOVER_DURATION = 1000;
    private static final float BUTTON_AREA_X = 0.8f;
    private static final float BUTTON_AREA_Y = 0.75f;

    // 复用对象池
    private Bitmap resizedBitmap = null;
    private Canvas resizeCanvas = null;
    private Matrix resizeMatrix = new Matrix();
    // 关闭抗锯齿，追求极致速度
    private Paint resizePaint = new Paint();

    private AtomicBoolean isProcessing = new AtomicBoolean(false);

    private RecognizeProcessor(Context context) {
        this.context = context;
        initMediaPipe();
    }

    public static void init(Context context) {
        if (instance == null) instance = new RecognizeProcessor(context);
    }

    public static RecognizeProcessor getInstance() { return instance; }

    private void initMediaPipe() {
        try {
            // 👇👇👇 核心修复：切换回 GPU 模式 👇👇👇
            // 之前为了诊断问题切到了 CPU，现在必须切回来！
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("gesture_recognizer.task")
                    .setDelegate(Delegate.GPU) // ✅ 核动力引擎开启
                    .build();

            GestureRecognizerOptions options = GestureRecognizerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setMinHandDetectionConfidence(0.2f)
                    .setMinHandPresenceConfidence(0.2f)
                    .setMinTrackingConfidence(0.2f)
                    .build();

            gestureRecognizer = GestureRecognizer.createFromOptions(context, options);
            Log.d(TAG, "MediaPipe Init Success (GPU MODE 🚀)");
        } catch (Exception e) {
            Log.e(TAG, "MediaPipe Init Error", e);
        }
    }

    public RecognizeTask process(RecognizeTask task) {
        if (task == null || task.getOriginBitmap() == null || gestureRecognizer == null) return task;

        if (!isProcessing.compareAndSet(false, true)) {
            return task;
        }

        try {
            Bitmap origin = task.getOriginBitmap();

            // 打印输入尺寸监控 (确认是 1920x1080)
            if (frameCount % 60 == 0) {
                Log.d(TAG, "📸 输入源: " + origin.getWidth() + "x" + origin.getHeight());
            }

            // 保持 400px 的极速推理尺寸
            int targetWidth = 400;

            if (origin.getWidth() > targetWidth) {
                float scale = (float) targetWidth / origin.getWidth();
                int targetHeight = (int) (origin.getHeight() * scale);

                if (resizedBitmap == null || resizedBitmap.getWidth() != targetWidth || resizedBitmap.getHeight() != targetHeight) {
                    if (resizedBitmap != null) resizedBitmap.recycle();
                    resizedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
                    resizeCanvas = new Canvas(resizedBitmap);
                }

                resizeMatrix.reset();
                resizeMatrix.setScale(scale, scale);
                resizeCanvas.drawBitmap(origin, resizeMatrix, resizePaint);

                processBitmap(resizedBitmap);
            } else {
                processBitmap(origin);
            }

        } catch (Exception e) {
            Log.e(TAG, "Process Error", e);
        } finally {
            isProcessing.set(false);
        }
        return task;
    }

    private void processBitmap(Bitmap inputBitmap) {
        try {
            MPImage mpImage = new BitmapImageBuilder(inputBitmap).build();
            long timestamp = System.currentTimeMillis();
            GestureRecognizerResult result = gestureRecognizer.recognizeForVideo(mpImage, timestamp);
            processResult(result);
        } catch (Exception e) {
            Log.e(TAG, "MediaPipe Error", e);
        }
    }

    private void processResult(GestureRecognizerResult result) {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFrameTime >= 1000) {
            Log.i(TAG, "🚀 当前 AI 真实帧率: " + frameCount + " FPS");
            frameCount = 0;
            lastFrameTime = now;
        }

        if (result == null || result.landmarks().isEmpty()) {
            hoverStartTime = 0;
            micHoverStartTime = 0;
            updateUI(null);
            return;
        }

        String categoryName = "None";
        boolean isOpenPalm = false;
        if (!result.gestures().isEmpty() && !result.gestures().get(0).isEmpty()) {
            categoryName = result.gestures().get(0).get(0).categoryName();
            isOpenPalm = "Open_Palm".equals(categoryName);
        }

        List<NormalizedLandmark> landmarks = result.landmarks().get(0);
        if (landmarks.size() > 8) {
            NormalizedLandmark indexTip = landmarks.get(8);
            float cx = indexTip.x();
            float cy = indexTip.y();

            boolean isObjTriggered = false;
            float objProgress = 0f;
            boolean isMicHovered = false;
            boolean isMicTriggered = false;
            float micProgress = 0f;

            if (isOpenPalm) {
                hoverStartTime = 0;
                micHoverStartTime = 0;
            } else {
                if (cx > BUTTON_AREA_X && cy > BUTTON_AREA_Y) {
                    isMicHovered = true;
                    hoverStartTime = 0;
                    if (micHoverStartTime == 0) {
                        micHoverStartTime = System.currentTimeMillis();
                    } else {
                        long duration = System.currentTimeMillis() - micHoverStartTime;
                        micProgress = Math.min(1.0f, (float) duration / HOVER_DURATION);
                        if (duration >= HOVER_DURATION) {
                            isMicTriggered = true;
                            micHoverStartTime = 0;
                        }
                    }
                } else {
                    isMicHovered = false;
                    micHoverStartTime = 0;
                    double distance = Math.sqrt(Math.pow(cx - lastX, 2) + Math.pow(cy - lastY, 2));
                    if (distance < MOVE_THRESHOLD) {
                        if (hoverStartTime == 0) {
                            hoverStartTime = System.currentTimeMillis();
                        } else {
                            long duration = System.currentTimeMillis() - hoverStartTime;
                            objProgress = Math.min(1.0f, (float) duration / HOVER_DURATION);
                            if (duration >= HOVER_DURATION) {
                                isObjTriggered = true;
                                hoverStartTime = System.currentTimeMillis() + 2000;
                            }
                        }
                    } else {
                        hoverStartTime = 0;
                    }
                }
            }

            lastX = cx;
            lastY = cy;

            RenderData data = new RenderData(
                    cx, cy, objProgress, isObjTriggered,
                    isOpenPalm, categoryName,
                    isMicHovered, micProgress, isMicTriggered
            );
            updateUI(data);
        }
    }

    private void updateUI(RenderData data) {
        if (context instanceof MainActivity) {
            ((MainActivity) context).runOnUiThread(() -> {
                ((MainActivity) context).updateView(data, null);
            });
        }
    }
}