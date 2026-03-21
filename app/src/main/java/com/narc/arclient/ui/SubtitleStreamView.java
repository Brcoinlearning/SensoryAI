package com.narc.arclient.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.narc.arclient.R;

/**
 * A lightweight AR-friendly subtitle view with streaming-style updates.
 * - Partial text appears with softer contrast; final text with higher contrast
 * - Gentle type-in animation for incremental updates
 * - Auto fade after final text stays for a short duration
 */
public class SubtitleStreamView extends FrameLayout {
    private final TextView textView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String targetText = "";
    private String displayedText = "";
    private boolean isFinalState = false;
    private ObjectAnimator fadeOutAnimator;
    private Runnable fadeOutRunnable;
    private Runnable typeTick;

    // Config
    private static final int MAX_WIDTH_DP_FALLBACK = 300; // keep narrow to reduce occlusion
    private static final long TYPE_INTERVAL_MS = 33; // ~30fps (AR spec: keep refresh <= 30fps)
    private static final long MAX_TYPE_DURATION_MS = 400; // cap typing animation duration
    private static final long FINAL_STAY_MS = 2200; // stay time before fade
    private final long fadeDurationMs;
    private final long appearDurationMs;

    public SubtitleStreamView(Context context) {
        this(context, null);
    }

    public SubtitleStreamView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SubtitleStreamView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipToPadding(false);
        setClipChildren(false);

        textView = new TextView(context);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        textView.setLayoutParams(lp);
        textView.setMaxLines(getIntSafely(R.integer.subtitle_max_lines, 3));
        textView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textView.setTextAppearance(R.style.TextAppearance_AR_Subtitle);
        // Ensure max width is applied even if OEM ignores style maxWidth.
        try {
            textView.setMaxWidth((int) getResources().getDimension(R.dimen.max_width_subtitle));
        } catch (Exception ignored) {
            textView.setMaxWidth((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MAX_WIDTH_DP_FALLBACK,
                    getResources().getDisplayMetrics()));
        }

        // Background with premium design per AR guideline
        setBackgroundResource(R.drawable.bg_subtitle_premium);

        // Padding is expected to come from XML (activity_main.xml) so it can follow
        // tokens.
        // Keep code-side default minimal to avoid overriding layout-driven spacing.
        if (getPaddingLeft() == 0 && getPaddingTop() == 0 && getPaddingRight() == 0 && getPaddingBottom() == 0) {
            int padH = (int) getResources().getDimension(R.dimen.spacing_md);
            int padV = (int) getResources().getDimension(R.dimen.spacing_sm);
            setPadding(padH, padV, padH, padV);
        }

        setAlpha(0f);
        setVisibility(View.GONE);

        // Align with design system animation tokens
        appearDurationMs = getIntegerSafely(R.integer.anim_duration_fast, 150);
        fadeDurationMs = getIntegerSafely(R.integer.anim_duration_normal, 250);

        addView(textView);
    }

    public void updateSubtitle(String text, boolean isFinal) {
        if (text == null)
            text = "";
        // Show if hidden
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
            animate().alpha(1f).setDuration(appearDurationMs).start();
        }

        cancelTyping();
        cancelFadeOut();

        this.isFinalState = isFinal;
        this.targetText = text;

        // Styling for partial vs final - using design system colors
        if (isFinal) {
            textView.setTextColor(getContext().getResources().getColor(R.color.text_primary, null));
        } else {
            textView.setTextColor(getContext().getResources().getColor(R.color.text_secondary, null));
        }

        // Streaming style: type-in only the delta when extending
        final String oldText = displayedText;
        final String newText = targetText;

        if (newText.startsWith(oldText) && newText.length() > oldText.length()) {
            startTyping(oldText, newText.substring(oldText.length()));
        } else {
            displayedText = newText;
            textView.setText(displayedText);
        }

        if (isFinal)
            scheduleFadeOut();
    }

    public void clearImmediate() {
        cancelTyping();
        cancelFadeOut();
        displayedText = "";
        targetText = "";
        isFinalState = false;
        textView.setText("");
        setAlpha(0f);
        setVisibility(View.GONE);
    }

    private void startTyping(final String prefix, final String delta) {
        displayedText = prefix;
        textView.setText(displayedText);

        final long start = System.currentTimeMillis();
        final int[] idx = { 0 };
        typeTick = new Runnable() {
            @Override
            public void run() {
                if (idx[0] >= delta.length()) {
                    typeTick = null;
                    // ensure final text
                    displayedText = prefix + delta;
                    textView.setText(displayedText);
                    return;
                }
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > MAX_TYPE_DURATION_MS) {
                    // fast-forward if too long
                    displayedText = prefix + delta;
                    textView.setText(displayedText);
                    typeTick = null;
                    return;
                }
                displayedText = prefix + delta.substring(0, idx[0] + 1);
                textView.setText(displayedText);
                idx[0]++;
                handler.postDelayed(this, TYPE_INTERVAL_MS);
            }
        };
        handler.postDelayed(typeTick, TYPE_INTERVAL_MS);
    }

    private void scheduleFadeOut() {
        cancelFadeOut();
        fadeOutAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f);
        fadeOutAnimator.setDuration(fadeDurationMs);
        fadeOutAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(View.GONE);
                displayedText = targetText;
            }
        });

        fadeOutRunnable = new Runnable() {
            @Override
            public void run() {
                if (getVisibility() == View.VISIBLE && isFinalState) {
                    fadeOutAnimator.start();
                }
            }
        };
        handler.postDelayed(fadeOutRunnable, FINAL_STAY_MS);
    }

    private void cancelFadeOut() {
        if (fadeOutRunnable != null) {
            handler.removeCallbacks(fadeOutRunnable);
            fadeOutRunnable = null;
        }
        if (fadeOutAnimator != null) {
            fadeOutAnimator.cancel();
            fadeOutAnimator = null;
        }
        // keep visible when cancelling
        if (getVisibility() == View.VISIBLE)
            setAlpha(1f);
    }

    private void cancelTyping() {
        if (typeTick != null) {
            handler.removeCallbacks(typeTick);
            typeTick = null;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private long getIntegerSafely(int resId, long fallback) {
        try {
            return getResources().getInteger(resId);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int getIntSafely(int resId, int fallback) {
        try {
            return getResources().getInteger(resId);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
