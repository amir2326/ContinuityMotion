package com.continuitymotion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

final class TransitionOverlayView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix transform = new Matrix();
    private final RectF dst = new RectF();

    private Bitmap screenshot;
    private Bitmap mediumBlur;
    private Bitmap heavyBlur;

    private float targetAngle = 180f;
    private float displayedAngle = 180f;
    private float sensorVelocity = 0f;
    private float direction = 1f;
    private boolean angleInitialized = false;
    private boolean framePosted = false;
    private long lastFrameNanos = 0L;

    TransitionOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setBackgroundColor(Color.TRANSPARENT);
    }

    void setScreenshot(Bitmap bitmap) {
        recycleBitmaps();
        screenshot = bitmap;

        // Progressive blur is generated only once per fold gesture. Drawing
        // these tiny cached layers is much cheaper than rebuilding a full-screen
        // RenderEffect every time the hinge sensor reports a new angle.
        mediumBlur = makeScaledBlurLayer(bitmap, 420);
        heavyBlur = makeScaledBlurLayer(bitmap, 118);
        invalidate();
    }

    void setHinge(float hingeAngle, float hingeVelocity) {
        targetAngle = MotionMath.clamp(hingeAngle, 0f, 180f);
        sensorVelocity = hingeVelocity;
        if (Math.abs(hingeVelocity) > 2f) direction = hingeVelocity >= 0f ? 1f : -1f;

        if (!angleInitialized) {
            displayedAngle = targetAngle;
            angleInitialized = true;
        }
        requestFrame();
    }

    private void requestFrame() {
        if (framePosted) return;
        framePosted = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        framePosted = false;
        if (!isAttachedToWindow()) {
            lastFrameNanos = 0L;
            return;
        }

        if (lastFrameNanos == 0L) {
            displayedAngle = targetAngle;
        } else {
            float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            dt = MotionMath.clamp(dt, 0.001f, 0.05f);

            // A tiny predictive lead offsets sensor/display pipeline latency,
            // while the exponential follower suppresses hinge quantization.
            float predicted = MotionMath.clamp(targetAngle + sensorVelocity * 0.010f, 0f, 180f);
            float alpha = 1f - (float) Math.exp(-dt / 0.014f);
            displayedAngle += (predicted - displayedAngle) * alpha;
        }
        lastFrameNanos = frameTimeNanos;
        postInvalidateOnAnimation();

        if (Math.abs(displayedAngle - targetAngle) > 0.04f) requestFrame();
    };

    @Override protected void onDetachedFromWindow() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback);
        framePosted = false;
        lastFrameNanos = 0L;
        recycleBitmaps();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (screenshot == null || screenshot.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;

        float envelope = MotionMath.handoffOpacity(displayedAngle);
        if (envelope < 0.002f) return;

        float blurStrength = MotionMath.clamp(Prefs.blur(getContext()) / 30f, 0f, 1.2f);
        float blurMix = MotionMath.clamp(MotionMath.handoffBlur(displayedAngle) * blurStrength, 0f, 1f);

        // Unlike v0.1, never shrink away from the edges. A tiny zoom lets the
        // source crop naturally morph as Samsung resizes the overlay from cover
        // to inner display, while avoiding black borders or a visible rectangle.
        float zoom = 1f + Prefs.compression(getContext()) * envelope;
        float perspectiveSqueeze = Prefs.perspective(getContext()) * 0.0010f * envelope;
        float scaleX = zoom * (1f - perspectiveSqueeze);
        float scaleY = zoom;
        float driftX = direction * getWidth() * 0.0028f * envelope;

        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        transform.reset();
        transform.setScale(scaleX, scaleY, cx, cy);
        transform.postTranslate(driftX, 0f);

        centerCropRect(screenshot, dst);

        // The destination screen remains live underneath. At peak handoff we
        // intentionally leave ~12% visible, producing the translucent
        // "see-through" continuity seen in Duo demonstrations.
        int overlayAlpha = Math.round(255f * 0.88f * envelope);

        int save = canvas.save();
        canvas.concat(transform);
        if (blurMix < 0.52f) {
            float t = blurMix / 0.52f;
            drawLayer(canvas, screenshot, dst, overlayAlpha * (1f - t));
            drawLayer(canvas, mediumBlur, dst, overlayAlpha * t);
        } else {
            float t = (blurMix - 0.52f) / 0.48f;
            drawLayer(canvas, mediumBlur, dst, overlayAlpha * (1f - t));
            drawLayer(canvas, heavyBlur, dst, overlayAlpha * t);
        }
        canvas.restoreToCount(save);

        // Very subtle neutral veil only at the handoff. The old bright haze was
        // deliberately removed because it looked milky rather than optical.
        int veilAlpha = Math.round(255f * Prefs.haze(getContext()) * envelope);
        if (veilAlpha > 0) {
            veilPaint.setColor(Color.argb(veilAlpha, 20, 22, 28));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), veilPaint);
        }
    }

    private void centerCropRect(Bitmap source, RectF out) {
        float sourceAspect = source.getWidth() / (float) source.getHeight();
        float viewAspect = getWidth() / (float) getHeight();
        if (sourceAspect > viewAspect) {
            float width = getHeight() * sourceAspect;
            out.set((getWidth() - width) * 0.5f, 0f, (getWidth() + width) * 0.5f, getHeight());
        } else {
            float height = getWidth() / sourceAspect;
            out.set(0f, (getHeight() - height) * 0.5f, getWidth(), (getHeight() + height) * 0.5f);
        }
    }

    private void drawLayer(Canvas canvas, Bitmap bitmap, RectF rect, float alpha) {
        if (bitmap == null || bitmap.isRecycled() || alpha <= 0.5f) return;
        imagePaint.setAlpha(Math.round(MotionMath.clamp(alpha, 0f, 255f)));
        canvas.drawBitmap(bitmap, null, rect, imagePaint);
    }

    private Bitmap makeScaledBlurLayer(Bitmap source, int maxLongSide) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        int longest = Math.max(sw, sh);
        float scale = Math.min(1f, maxLongSide / (float) longest);
        int w = Math.max(20, Math.round(sw * scale));
        int h = Math.max(20, Math.round(sh * scale));
        return Bitmap.createScaledBitmap(source, w, h, true);
    }

    private void recycleBitmaps() {
        Bitmap oldScreenshot = screenshot;
        Bitmap oldMedium = mediumBlur;
        Bitmap oldHeavy = heavyBlur;
        screenshot = null;
        mediumBlur = null;
        heavyBlur = null;
        if (oldMedium != null && oldMedium != oldScreenshot && !oldMedium.isRecycled()) oldMedium.recycle();
        if (oldHeavy != null && oldHeavy != oldScreenshot && oldHeavy != oldMedium && !oldHeavy.isRecycled()) oldHeavy.recycle();
        if (oldScreenshot != null && !oldScreenshot.isRecycled()) oldScreenshot.recycle();
    }
}
