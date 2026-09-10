package com.continuitymotion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

final class TransitionOverlayView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();

    private Bitmap screenshot;
    private Bitmap mediumBlur;
    private Bitmap heavyBlur;

    private float targetAngle = 180f;
    private float displayedAngle = 180f;
    private float velocity = 0f;
    private float direction = -1f;
    private boolean initialized;
    private boolean framePosted;
    private long lastFrameNanos;
    private long sizeSwitchMillis;
    private long finalRevealMillis;
    private float sourceAspect = 1f;
    private boolean sourceIsInner = true;
    private float previousAspect = 0f;

    TransitionOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setBackgroundColor(Color.TRANSPARENT);
    }

    void setScreenshot(Bitmap bitmap) {
        releaseBlurLayers();
        screenshot = bitmap;
        sourceAspect = bitmap.getWidth() / (float) bitmap.getHeight();
        sourceIsInner = sourceAspect > 0.62f;
        mediumBlur = makeScaledLayer(bitmap, 520);
        heavyBlur = makeScaledLayer(bitmap, 160);
        invalidate();
    }

    void setHinge(float hingeAngle, float hingeVelocity) {
        targetAngle = MotionMath.clamp(hingeAngle, 0f, 180f);
        velocity = hingeVelocity;
        if (Math.abs(hingeVelocity) > 1.2f) direction = hingeVelocity >= 0f ? 1f : -1f;
        if (!initialized) {
            displayedAngle = targetAngle;
            initialized = true;
        }
        requestFrame();
    }

    void beginFinalReveal() {
        if (finalRevealMillis == 0L) {
            finalRevealMillis = SystemClock.uptimeMillis();
            requestFrame();
        }
    }

    private void requestFrame() {
        if (framePosted) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        framePosted = false;
        if (!isAttachedToWindow()) return;

        if (lastFrameNanos == 0L) {
            displayedAngle = targetAngle;
        } else {
            float dt = MotionMath.clamp((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.001f, 0.05f);
            float predicted = MotionMath.clamp(targetAngle + velocity * 0.012f, 0f, 180f);
            float alpha = 1f - (float) Math.exp(-dt / 0.010f);
            displayedAngle += (predicted - displayedAngle) * alpha;
        }
        lastFrameNanos = frameTimeNanos;
        postInvalidateOnAnimation();

        boolean smoothing = Math.abs(displayedAngle - targetAngle) > 0.025f;
        boolean switching = sizeSwitchMillis != 0L && SystemClock.uptimeMillis() - sizeSwitchMillis < 360L;
        boolean revealing = finalRevealMillis != 0L && SystemClock.uptimeMillis() - finalRevealMillis < 340L;
        if (smoothing || switching || revealing) requestFrame();
    };

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        float aspect = w / (float) h;
        if (previousAspect > 0f) {
            float ratio = Math.max(aspect, previousAspect) / Math.max(0.01f, Math.min(aspect, previousAspect));
            if (ratio > 1.22f) {
                sizeSwitchMillis = SystemClock.uptimeMillis();
                requestFrame();
            }
        }
        previousAspect = aspect;
        panelShadePaint.setShader(new LinearGradient(0f, 0f, w * 0.5f, 0f,
                new int[]{Color.BLACK, Color.rgb(12, 12, 14)}, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onDetachedFromWindow() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback);
        framePosted = false;
        lastFrameNanos = 0L;
        releaseBlurLayers();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (screenshot == null || screenshot.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;

        float currentAspect = getWidth() / (float) getHeight();
        boolean nowInner = currentAspect > 0.62f;
        float revealFade = finalRevealFactor();

        if (sourceIsInner) {
            drawClosingMorph(canvas, nowInner, revealFade);
        } else {
            drawOpeningMorph(canvas, nowInner, revealFade);
        }
    }

    private void drawClosingMorph(Canvas c, boolean nowInner, float finalReveal) {
        float a = displayedAngle;
        float morph = MotionMath.panelMorph(a);
        float blur = MotionMath.clamp((float) Math.pow(morph, 0.82f) * (Prefs.blur(getContext()) / 30f), 0f, 1f);
        float black = MotionMath.endpointBlack(a);
        float switchedAge = switchAgeFactor(235f);

        if (!nowInner) {
            float fade = Math.max(switchedAge, finalReveal);
            drawRegionCrossBlur(c, 0.50f, 1f, new RectF(0f, 0f, getWidth(), getHeight()),
                    blur, 255f * (1f - fade));
            blackPaint.setColor(Color.BLACK);
            blackPaint.setAlpha(Math.round(255f * (0.88f * (1f - fade))));
            c.drawRect(0f, 0f, getWidth(), getHeight(), blackPaint);
            return;
        }

        float w = getWidth();
        float h = getHeight();
        float cx = w * 0.5f;

        float survivor = MotionMath.smootherstep(MotionMath.remap(morph, 0.10f, 0.98f));
        float rightLeft = MotionMath.lerp(cx, 0f, survivor * 0.92f);
        RectF rightDst = new RectF(rightLeft, 0f, w, h);
        drawRegionCrossBlur(c, 0.50f, 1f, rightDst, blur * 0.38f, 255f);

        float leftInset = cx * 0.42f * survivor;
        RectF leftDst = new RectF(leftInset, 0f, cx, h);
        float leftAlpha = 255f * (1f - 0.84f * MotionMath.smootherstep(morph));
        drawRegionCrossBlur(c, 0f, 0.50f, leftDst, blur, leftAlpha);

        panelShadePaint.setAlpha(Math.round(255f * 0.82f * MotionMath.smootherstep(morph)));
        c.drawRect(leftDst, panelShadePaint);

        blackPaint.setColor(Color.BLACK);
        blackPaint.setAlpha(Math.round(255f * 0.28f * morph));
        float shadow = Math.max(3f, w * 0.018f);
        c.drawRect(cx - shadow, 0f, cx + shadow * 0.15f, h, blackPaint);

        if (black > 0f) {
            blackPaint.setAlpha(Math.round(255f * 0.96f * black * (1f - finalReveal)));
            c.drawRect(0f, 0f, w, h, blackPaint);
        }
    }

    private void drawOpeningMorph(Canvas c, boolean nowInner, float finalReveal) {
        float a = displayedAngle;
        float optical = MotionMath.openingHandoff(a);
        float blur = MotionMath.clamp(optical * (Prefs.blur(getContext()) / 30f), 0f, 1f);

        if (!nowInner) {
            drawRegionCrossBlur(c, 0f, 1f, new RectF(0f, 0f, getWidth(), getHeight()), blur, 255f);
            blackPaint.setColor(Color.BLACK);
            blackPaint.setAlpha(Math.round(255f * 0.88f * optical));
            c.drawRect(0f, 0f, getWidth(), getHeight(), blackPaint);
            return;
        }

        float ageReveal = switchAgeFactor(285f);
        float angleReveal = MotionMath.smootherstep(MotionMath.remap(a, 25f, 104f));
        float reveal = Math.max(Math.max(ageReveal, angleReveal), finalReveal);
        float remain = 1f - reveal;
        float w = getWidth();
        float h = getHeight();
        float cx = w * 0.5f;

        RectF coverDst = new RectF(cx, 0f, w, h);
        drawRegionCrossBlur(c, 0f, 1f, coverDst, blur * remain, 255f * remain);

        panelShadePaint.setAlpha(Math.round(255f * 0.94f * remain));
        c.drawRect(0f, 0f, cx, h, panelShadePaint);

        float bridge = 1f - MotionMath.smootherstep(MotionMath.remap(ageReveal, 0f, 0.34f));
        blackPaint.setColor(Color.BLACK);
        blackPaint.setAlpha(Math.round(255f * 0.52f * bridge * remain));
        c.drawRect(0f, 0f, w, h, blackPaint);
    }

    private void drawRegionCrossBlur(Canvas c, float leftFrac, float rightFrac, RectF dst, float blurMix, float alpha) {
        blurMix = MotionMath.clamp(blurMix, 0f, 1f);
        alpha = MotionMath.clamp(alpha, 0f, 255f);
        if (alpha <= 0.5f) return;

        if (blurMix < 0.52f) {
            float t = blurMix / 0.52f;
            drawRegion(c, screenshot, leftFrac, rightFrac, dst, alpha * (1f - t));
            drawRegion(c, mediumBlur, leftFrac, rightFrac, dst, alpha * t);
        } else {
            float t = (blurMix - 0.52f) / 0.48f;
            drawRegion(c, mediumBlur, leftFrac, rightFrac, dst, alpha * (1f - t));
            drawRegion(c, heavyBlur, leftFrac, rightFrac, dst, alpha * t);
        }
    }

    private void drawRegion(Canvas c, Bitmap b, float leftFrac, float rightFrac, RectF dst, float alpha) {
        if (b == null || b.isRecycled() || alpha <= 0.5f) return;
        int left = Math.round(b.getWidth() * leftFrac);
        int right = Math.round(b.getWidth() * rightFrac);
        if (right <= left) return;
        srcRect.set(left, 0, right, b.getHeight());
        imagePaint.setAlpha(Math.round(MotionMath.clamp(alpha, 0f, 255f)));
        c.drawBitmap(b, srcRect, dst, imagePaint);
    }

    private float switchAgeFactor(float durationMs) {
        if (sizeSwitchMillis == 0L) return 0f;
        return MotionMath.smootherstep(MotionMath.clamp((SystemClock.uptimeMillis() - sizeSwitchMillis) / durationMs, 0f, 1f));
    }

    private float finalRevealFactor() {
        if (finalRevealMillis == 0L) return 0f;
        return MotionMath.smootherstep(MotionMath.clamp((SystemClock.uptimeMillis() - finalRevealMillis) / 260f, 0f, 1f));
    }

    private Bitmap makeScaledLayer(Bitmap source, int maxLongSide) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        float s = Math.min(1f, maxLongSide / (float) longest);
        int w = Math.max(24, Math.round(source.getWidth() * s));
        int h = Math.max(24, Math.round(source.getHeight() * s));
        return Bitmap.createScaledBitmap(source, w, h, true);
    }

    private void releaseBlurLayers() {
        if (mediumBlur != null && mediumBlur != screenshot && !mediumBlur.isRecycled()) mediumBlur.recycle();
        if (heavyBlur != null && heavyBlur != screenshot && heavyBlur != mediumBlur && !heavyBlur.isRecycled()) heavyBlur.recycle();
        mediumBlur = null;
        heavyBlur = null;
    }
}
