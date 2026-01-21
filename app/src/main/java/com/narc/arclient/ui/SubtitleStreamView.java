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
    private Runnable typeTick;

    // Config
    private static final int MAX_LINES = 3;
    private static final int MAX_WIDTH_DP = 300; // keep narrow to reduce occlusion
    private static final long TYPE_INTERVAL_MS = 16; // ~60fps
    private static final long MAX_TYPE_DURATION_MS = 400; // cap typing animation duration
    private static final long FINAL_STAY_MS = 2200; // stay time before fade
    private static final long FADE_DURATION_MS = 250;

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
        textView.setMaxLines(MAX_LINES);
        textView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        textView.setTextColor(0xFFFFFFFF);
        textView.setShadowLayer(2f, 0f, 1f, 0x80000000);

        int maxWidthPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MAX_WIDTH_DP,
                getResources().getDisplayMetrics());
        textView.setMaxWidth(maxWidthPx);

        // Background with soft transparency and rounded corners per AR guideline
        setBackgroundResource(R.drawable.bg_subtitle_bubble);

        int padH = dp(12);
        int padV = dp(8);
        setPadding(padH, padV, padH, padV);

        setAlpha(0f);
        setVisibility(View.GONE);

        addView(textView);
    }

    public void updateSubtitle(String text, boolean isFinal) {
        if (text == null)
            text = "";
        // Show if hidden
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
            animate().alpha(1f).setDuration(150).start();
        }

        cancelTyping();
        cancelFadeOut();

        this.isFinalState = isFinal;
        this.targetText = text;

        // Styling for partial vs final
        if (isFinal) {
            textView.setTextColor(0xFFFFFFFF); // higher contrast
        } else {
            textView.setTextColor(0xCCFFFFFF); // softer for partial
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
        fadeOutAnimator = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f);
        fadeOutAnimator.setDuration(FADE_DURATION_MS);
        fadeOutAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(View.GONE);
                displayedText = targetText;
            }
        });

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getVisibility() == View.VISIBLE && isFinalState) {
                    fadeOutAnimator.start();
                }
            }
        }, FINAL_STAY_MS);
    }

    private void cancelFadeOut() {
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
}
