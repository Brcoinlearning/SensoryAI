package com.narc.arclient.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

import com.narc.arclient.R;

import java.util.ArrayList;
import java.util.List;

/**
 * One-shot card transition overlay.
 * - materialize: fragments converge to reveal card
 * - dissolve: fragments drift out to hide card
 */
public class CardMaterializeOverlayView extends View {

    private static final long MATERIALIZE_DURATION_MS = 380L;
    private static final long DISSOLVE_DURATION_MS = 340L;
    private static final int GRID_COLS = 16;
    private static final int GRID_ROWS = 12;
    private static final int PARTICLE_COUNT = 28;

    private final Paint fragmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF targetBounds = new RectF();

    private final List<Fragment> fragments = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();

    private ValueAnimator animator;
    private Bitmap snapshot;
    private View targetView;
    private Runnable endAction;
    private boolean materializeMode = true;
    private float progress = 0f;

    private static final class Fragment {
        final Rect src;
        final float delay;
        final float dirX;
        final float dirY;

        Fragment(Rect src, float delay, float dirX, float dirY) {
            this.src = src;
            this.delay = delay;
            this.dirX = dirX;
            this.dirY = dirY;
        }
    }

    private static final class Particle {
        final float startX;
        final float startY;
        final float endX;
        final float endY;
        final float radius;
        final float delay;

        Particle(float startX, float startY, float endX, float endY, float radius, float delay) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.radius = radius;
            this.delay = delay;
        }
    }

    public CardMaterializeOverlayView(Context context) {
        this(context, null);
    }

    public CardMaterializeOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CardMaterializeOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        particlePaint.setColor(getResources().getColor(R.color.primary_teal_light, null));
    }

    public void playMaterialize(View cardView, @Nullable Runnable onEnd) {
        if (!prepare(cardView, true)) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }

        cardView.setAlpha(0f);
        this.endAction = () -> {
            cardView.setAlpha(1f);
            if (onEnd != null) {
                onEnd.run();
            }
        };
        startAnimator(MATERIALIZE_DURATION_MS);
    }

    public void playDissolve(View cardView, @Nullable Runnable onEnd) {
        if (!prepare(cardView, false)) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }

        cardView.setAlpha(0f);
        this.endAction = onEnd;
        startAnimator(DISSOLVE_DURATION_MS);
    }

    private boolean prepare(View cardView, boolean materialize) {
        if (cardView == null || cardView.getWidth() <= 0 || cardView.getHeight() <= 0) {
            return false;
        }

        cancelAnimator();
        recycleSnapshot();

        this.targetView = cardView;
        this.materializeMode = materialize;
        this.progress = 0f;

        snapshot = captureCardSnapshot(cardView);
        if (snapshot == null) {
            return false;
        }

        targetBounds.set(cardView.getX(), cardView.getY(), cardView.getX() + cardView.getWidth(),
                cardView.getY() + cardView.getHeight());

        buildFragments(snapshot.getWidth(), snapshot.getHeight());
        buildParticles();

        setVisibility(VISIBLE);
        bringToFront();
        invalidate();
        return true;
    }

    private Bitmap captureCardSnapshot(View cardView) {
        try {
            float oldAlpha = cardView.getAlpha();
            cardView.setAlpha(1f);
            Bitmap bitmap = Bitmap.createBitmap(cardView.getWidth(), cardView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            cardView.draw(canvas);
            cardView.setAlpha(oldAlpha);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void buildFragments(int width, int height) {
        fragments.clear();

        int cellW = Math.max(1, width / GRID_COLS);
        int cellH = Math.max(1, height / GRID_ROWS);
        float centerX = width / 2f;
        float centerY = height / 2f;

        for (int row = 0; row < GRID_ROWS; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int left = col * cellW;
                int top = row * cellH;
                int right = Math.min(width, left + cellW);
                int bottom = Math.min(height, top + cellH);
                if (right <= left || bottom <= top) {
                    continue;
                }

                float cellCx = (left + right) * 0.5f;
                float cellCy = (top + bottom) * 0.5f;
                float dx = cellCx - centerX;
                float dy = cellCy - centerY;
                float dist = (float) Math.hypot(dx, dy);
                float maxDist = (float) Math.hypot(centerX, centerY);
                float ringDelay = maxDist <= 0f ? 0f : dist / maxDist;
                float noise = pseudoNoise(col, row);
                float delay = clamp(0.05f + ringDelay * 0.45f + noise * 0.12f, 0f, 0.75f);

                float len = Math.max(1f, dist);
                float dirX = dx / len;
                float dirY = dy / len;

                fragments.add(new Fragment(new Rect(left, top, right, bottom), delay, dirX, dirY));
            }
        }
    }

    private void buildParticles() {
        particles.clear();

        float cx = targetBounds.centerX();
        float cy = targetBounds.centerY();
        float rx = targetBounds.width() * 0.62f;
        float ry = targetBounds.height() * 0.62f;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float t = i / (float) PARTICLE_COUNT;
            float angle = (float) (t * Math.PI * 2.0);
            float jitter = (pseudoNoise(i, PARTICLE_COUNT - i) - 0.5f) * 22f;

            float endX = cx + (float) Math.cos(angle) * (rx * 0.78f);
            float endY = cy + (float) Math.sin(angle) * (ry * 0.78f);

            float startX = cx + (float) Math.cos(angle) * (rx + 18f + jitter);
            float startY = cy + (float) Math.sin(angle) * (ry + 18f + jitter);

            float radius = 1.2f + pseudoNoise(i * 3, i * 7) * 1.5f;
            float delay = 0.08f + pseudoNoise(i, i + 11) * 0.45f;
            particles.add(new Particle(startX, startY, endX, endY, radius, delay));
        }
    }

    private void startAnimator(long durationMs) {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(durationMs);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(valueAnimator -> {
            progress = (float) valueAnimator.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishTransition();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finishTransition();
            }
        });
        animator.start();
    }

    private void finishTransition() {
        cancelAnimator();
        setVisibility(GONE);
        recycleSnapshot();
        fragments.clear();
        particles.clear();

        if (endAction != null) {
            Runnable action = endAction;
            endAction = null;
            action.run();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (snapshot == null || fragments.isEmpty()) {
            return;
        }

        drawFragments(canvas);
        drawParticles(canvas);
    }

    private void drawFragments(Canvas canvas) {
        float travel = materializeMode ? 12f : 16f;

        for (Fragment fragment : fragments) {
            float local = normalizedProgress(progress, fragment.delay, 0.45f);
            float visibility = materializeMode ? local : (1f - local);
            if (visibility <= 0f) {
                continue;
            }

            float drift = materializeMode ? (1f - visibility) * travel : (1f - visibility) * travel;
            float offsetX = fragment.dirX * drift;
            float offsetY = fragment.dirY * drift;

            srcRect.set(fragment.src);
            dstRect.set(targetBounds.left + fragment.src.left + offsetX,
                    targetBounds.top + fragment.src.top + offsetY,
                    targetBounds.left + fragment.src.right + offsetX,
                    targetBounds.top + fragment.src.bottom + offsetY);

            fragmentPaint.setAlpha((int) (visibility * 255f));
            canvas.drawBitmap(snapshot, srcRect, dstRect, fragmentPaint);
        }
    }

    private void drawParticles(Canvas canvas) {
        for (Particle particle : particles) {
            float local = normalizedProgress(progress, particle.delay, 0.45f);
            float alpha = materializeMode ? (1f - local) : (1f - local);
            if (alpha <= 0f) {
                continue;
            }

            float eased = easeOut(local);
            float x = materializeMode
                    ? lerp(particle.startX, particle.endX, eased)
                    : lerp(particle.endX, particle.startX, eased);
            float y = materializeMode
                    ? lerp(particle.startY, particle.endY, eased)
                    : lerp(particle.endY, particle.startY, eased);

            particlePaint.setAlpha((int) (alpha * 180f));
            canvas.drawCircle(x, y, particle.radius, particlePaint);
        }
    }

    private float normalizedProgress(float value, float start, float span) {
        if (span <= 0f) {
            return value >= start ? 1f : 0f;
        }
        return clamp((value - start) / span, 0f, 1f);
    }

    private float easeOut(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float pseudoNoise(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >>> 13)) * 1274126177;
        n = n ^ (n >>> 16);
        int positive = n & 0x7fffffff;
        return positive / (float) 0x7fffffff;
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    private void recycleSnapshot() {
        if (snapshot != null && !snapshot.isRecycled()) {
            snapshot.recycle();
        }
        snapshot = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        recycleSnapshot();
        super.onDetachedFromWindow();
    }
}
