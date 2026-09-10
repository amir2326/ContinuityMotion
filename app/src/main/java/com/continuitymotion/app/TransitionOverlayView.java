package com.continuitymotion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

final class TransitionOverlayView extends View {
    enum Direction { OPENING, CLOSING }

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, RenderEffect> blurCache = new HashMap<>();

    private Bitmap source;
    private Bitmap target;
    private Direction direction = Direction.CLOSING;
    private float angle = 180f;
    private float velocity = 0f;
    private boolean handedOff = false;
    private long handoffMillis = 0L;
    private LinearGradient hingeToRight;
    private LinearGradient hingeToLeft;

    TransitionOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setWillNotDraw(false);
    }

    void bind(Bitmap sourceBitmap, Bitmap targetBitmap, Direction d) {
        source = sourceBitmap;
        target = targetBitmap;
        direction = d;
        invalidate();
    }

    void setTarget(Bitmap targetBitmap) {
        target = targetBitmap;
        invalidate();
    }

    void setHinge(float hingeAngle, float hingeVelocity) {
        angle = MotionMath.clamp(hingeAngle, 0f, 180f);
        velocity = hingeVelocity;
        invalidate();
    }

    void markHandoff(long when) {
        if (!handedOff) {
            handedOff = true;
            handoffMillis = when;
        }
        invalidate();
    }

    boolean isHandedOff() { return handedOff; }

    private float timeBlend() {
        if (!handedOff) return 0f;
        float t = (SystemClock.uptimeMillis() - handoffMillis) / (float) Math.max(120, Prefs.handoffMs(getContext()));
        return MotionMath.smootherstep(t);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w * 0.5f;
        hingeToRight = new LinearGradient(cx, 0, Math.max(cx + 1f, w),
                new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                new float[]{0f, .18f, 1f}, Shader.TileMode.CLAMP);
        hingeToLeft = new LinearGradient(cx, 0, 0, 0,
                new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                new float[]{0f, .18f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (source == null || source.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;
        if (direction == Direction.CLOSING) drawClosing(canvas); else drawOpening(canvas);
        if (handedOff && timeBlend() < 0.999f) postInvalidateOnAnimation();
    }

    private void drawClosing(Canvas c) {
        float p = MotionMath.saturate((180f - angle) / 180f);
        float physical = MotionMath.smootherstep(p);
        if (!handedOff) drawClosingOnInner(c, physical);
        else drawClosingOnCover(c, physical, timeBlend());
    }

    private void drawOpening(Canvas c) {
        float p = MotionMath.saturate(angle / 180f);
        float physical = MotionMath.smootherstep(p);
        if (!handedOff) drawOpeningOnCover(c, physical);
        else drawOpeningOnInner(c, physical, timeBlend());
    }

    private void drawClosingOnInner(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        float split = w * Prefs.leftPanel(getContext());
        float midBlur = Prefs.blur(getContext()) * (float) Math.pow(MotionMath.bell(p), .68);

        Rect leftSrc = new Rect(0, 0, Math.max(1, Math.round(source.getWidth() * Prefs.leftPanel(getContext()))), source.getHeight());
        RectF leftDst = new RectF(0, 0, split, h);
        float leftScaleX = 1f - Prefs.perspective(getContext()) * MotionMath.easeInCubic(p);
        int save = c.save();
        c.scale(leftScaleX, 1f - 0.010f * MotionMath.bell(p), split, h * .5f);
        drawBitmap(c, source, leftSrc, leftDst, midBlur * .72f, 1f);
        c.restoreToCount(save);

        Rect rightSrc = new Rect(leftSrc.right, 0, source.getWidth(), source.getHeight());
        RectF rightDst = new RectF(split, 0, w, h);
        float rightScaleX = 1f - 0.11f * MotionMath.easeInCubic(p);
        save = c.save();
        c.scale(rightScaleX, 1f, split, h * .5f);
        drawBitmap(c, source, rightSrc, rightDst, midBlur * 1.18f + 2f * p, 1f);
        c.restoreToCount(save);

        float rightBlack = Prefs.black(getContext()) * MotionMath.smoothstep(MotionMath.remap(p, .06f, .78f));
        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(Math.round(255f * rightBlack));
        c.drawRect(split, 0, w, h, dimPaint);

        float leftBlack = .32f * MotionMath.bell(p) + .18f * MotionMath.easeInCubic(MotionMath.remap(p, .55f, 1f));
        dimPaint.setAlpha(Math.round(255f * leftBlack));
        c.drawRect(0, 0, split, h, dimPaint);
        drawHingeShadow(c, split, p, true);
    }

    private void drawClosingOnCover(Canvas c, float p, float t) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);

        Rect leftSrc = new Rect(0, 0, Math.max(1, Math.round(source.getWidth() * Prefs.leftPanel(getContext()))), source.getHeight());
        RectF full = coverRect(leftSrc.width(), leftSrc.height(), w, h);
        float sourceBlur = MotionMath.lerp(Prefs.blur(getContext()) * .72f, 0f, t);
        float sourceAlpha = 1f - MotionMath.smoothstep(MotionMath.remap(t, .34f, 1f));
        float squash = MotionMath.lerp(.965f, 1f, t);
        int save = c.save();
        c.scale(squash, MotionMath.lerp(.985f, 1f, t), w * .5f, h * .5f);
        drawBitmap(c, source, leftSrc, full, sourceBlur, sourceAlpha);
        c.restoreToCount(save);

        if (target != null && !target.isRecycled()) {
            float targetAlpha = MotionMath.smootherstep(MotionMath.remap(t, .08f, .96f));
            float targetBlur = Prefs.blur(getContext()) * .82f * (1f - targetAlpha);
            drawBitmapCover(c, target, targetBlur, targetAlpha);
        }

        float veil = .46f * (1f - t);
        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(Math.round(255f * veil));
        c.drawRect(0, 0, w, h, dimPaint);
    }

    private void drawOpeningOnCover(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        float blur = Prefs.blur(getContext()) * MotionMath.easeOutCubic(MotionMath.remap(p, .05f, .52f));
        float scale = 1f - .035f * MotionMath.easeInCubic(p);
        int save = c.save();
        c.scale(scale, 1f - .012f * p, w * .5f, h * .5f);
        drawBitmapCover(c, source, blur, 1f);
        c.restoreToCount(save);

        float black = .62f * MotionMath.easeInCubic(MotionMath.remap(p, .04f, .60f));
        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(Math.round(255f * black));
        c.drawRect(0, 0, w, h, dimPaint);
    }

    private void drawOpeningOnInner(Canvas c, float p, float t) {
        int w = getWidth(), h = getHeight();
        float split = w * Prefs.leftPanel(getContext());
        c.drawColor(Color.BLACK);

        if (target != null && !target.isRecycled()) {
            float angleResolve = MotionMath.smoothstep(MotionMath.remap(p, .14f, .92f));
            float resolve = Math.max(t, angleResolve);
            float targetBlur = Prefs.blur(getContext()) * .88f * (1f - resolve);
            float targetAlpha = MotionMath.smootherstep(MotionMath.remap(resolve, .02f, .98f));
            drawBitmapCover(c, target, targetBlur, targetAlpha);

            float rightVeil = Prefs.black(getContext()) * (1f - MotionMath.smoothstep(MotionMath.remap(resolve, .16f, .88f)));
            dimPaint.setColor(Color.BLACK);
            dimPaint.setAlpha(Math.round(255f * rightVeil));
            c.drawRect(split, 0, w, h, dimPaint);
        }

        Rect fullSrc = new Rect(0, 0, source.getWidth(), source.getHeight());
        RectF leftDst = new RectF(0, 0, split, h);
        float sourceFade = 1f - MotionMath.smootherstep(MotionMath.remap(Math.max(t, p), .20f, .88f));
        float sourceBlur = Prefs.blur(getContext()) * .70f * MotionMath.bell(MotionMath.remap(Math.max(t, p), .02f, .96f));
        drawBitmap(c, source, fullSrc, leftDst, sourceBlur, sourceFade);

        drawHingeShadow(c, split, 1f - Math.max(t, p), false);
    }

    private void drawHingeShadow(Canvas c, float split, float strength, boolean rightHeavy) {
        if (strength <= .001f) return;
        float w = getWidth(), h = getHeight();
        float band = Math.max(18f, w * .13f);
        seamPaint.setShader(rightHeavy ? hingeToRight : hingeToLeft);
        seamPaint.setAlpha(Math.round(255f * MotionMath.clamp(.18f + .76f * strength, 0f, .94f)));
        if (rightHeavy) c.drawRect(split, 0, Math.min(w, split + band * 2.2f), h, seamPaint);
        else c.drawRect(Math.max(0, split - band * 2.2f), 0, split, h, seamPaint);
        seamPaint.setShader(null);

        edgePaint.setColor(Color.BLACK);
        edgePaint.setAlpha(Math.round(255f * .38f * strength));
        float seam = Math.max(2f, w * .006f) * (0.35f + strength);
        c.drawRect(split - seam, 0, split + seam, h, edgePaint);
    }

    private void drawBitmapCover(Canvas c, Bitmap b, float blur, float alpha) {
        Rect src = new Rect(0, 0, b.getWidth(), b.getHeight());
        RectF dst = coverRect(b.getWidth(), b.getHeight(), getWidth(), getHeight());
        drawBitmap(c, b, src, dst, blur, alpha);
    }

    private RectF coverRect(int bw, int bh, int vw, int vh) {
        float ba = bw / (float) Math.max(1, bh);
        float va = vw / (float) Math.max(1, vh);
        if (ba > va) {
            float dw = vh * ba;
            return new RectF((vw - dw) * .5f, 0, (vw + dw) * .5f, vh);
        } else {
            float dh = vw / Math.max(.0001f, ba);
            return new RectF(0, (vh - dh) * .5f, vw, (vh + dh) * .5f);
        }
    }

    private void drawBitmap(Canvas c, Bitmap b, Rect src, RectF dst, float blurRadius, float alpha) {
        imagePaint.setAlpha(Math.round(255f * MotionMath.saturate(alpha)));
        if (Build.VERSION.SDK_INT >= 31) {
            int r = Math.round(MotionMath.clamp(blurRadius, 0f, 64f));
            if (r <= 0) {
                imagePaint.setRenderEffect(null);
            } else {
                RenderEffect effect = blurCache.get(r);
                if (effect == null) {
                    effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
                    blurCache.put(r, effect);
                }
                imagePaint.setRenderEffect(effect);
            }
        }
        c.drawBitmap(b, src, dst, imagePaint);
        if (Build.VERSION.SDK_INT >= 31) imagePaint.setRenderEffect(null);
        imagePaint.setAlpha(255);
    }

    @Override protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) imagePaint.setRenderEffect(null);
        super.onDetachedFromWindow();
    }
}
