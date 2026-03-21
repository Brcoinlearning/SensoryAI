package com.narc.arclient.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.narc.arclient.R;

/**
 * AR眼镜专用 Toast 视图
 * - 显示在屏幕底部
 * - 支持图标和消息
 * - 带淡入淡出动画
 * - 自动隐藏
 */
public class ArToastView extends FrameLayout {

    private final TextView tvMessage;
    private final ImageView ivIcon;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;

    private static final long SHOW_DURATION_MS = 2000; // 显示时长

    public ArToastView(Context context) {
        this(context, null);
    }

    public ArToastView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ArToastView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // 使用布局膨胀器加载 Toast 布局
        View toastView = LayoutInflater.from(context).inflate(R.layout.ar_toast, this, true);

        tvMessage = toastView.findViewById(R.id.tv_toast_message);
        ivIcon = toastView.findViewById(R.id.iv_toast_icon);

        setVisibility(View.GONE);
        setAlpha(0f);
    }

    // 显示带图标的 Toast
    public void show(String message, int iconRes) {
        runOnUiThread(() -> {
            tvMessage.setText(message);
            if (iconRes != 0) {
                ivIcon.setImageResource(iconRes);
                ivIcon.setVisibility(View.VISIBLE);
            } else {
                ivIcon.setVisibility(View.GONE);
            }

            cancelHide();

            // 先停止旧动画，再重新入场
            clearAnimation();
            setVisibility(View.VISIBLE);
            setAlpha(1f);
            startAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.toast_in));

            // 延迟隐藏
            hideRunnable = this::fadeOut;
            handler.postDelayed(hideRunnable, SHOW_DURATION_MS);
        });
    }

    // 显示不带图标的 Toast
    public void show(String message) {
        show(message, 0);
    }

    private void fadeOut() {
        clearAnimation();
        Animation fadeOut = AnimationUtils.loadAnimation(getContext(), R.anim.toast_out);
        fadeOut.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                setVisibility(View.GONE);
                clearAnimation();
                setAlpha(1f);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        startAnimation(fadeOut);
    }

    private void cancelHide() {
        if (hideRunnable != null) {
            handler.removeCallbacks(hideRunnable);
        }
        clearAnimation();
    }

    private void runOnUiThread(Runnable action) {
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            action.run();
        } else {
            handler.post(action);
        }
    }
}
