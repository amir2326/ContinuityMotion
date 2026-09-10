package com.continuitymotion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

final class TransitionOverlayView extends View {
    enum Direction { OPENING, CLOSING }

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix cameraMatrix = new Matrix();
    private final RenderNode blurNode = new RenderNode("ContinuityPaneBlur");
    private final Map<Integer, RenderEffect> blurCache = new HashMap<>();

    private Bitmap source;
    private Bitmap target;
    private Direction direction = Direction.CLOSING;
    private float angle = 180f;
    private float velocity = 0f;
    private boolean handedOff = false;
    private long handoffMillis = 0L;
    private long targetArrivedMillis = 0L;
    private LinearGradient hingeToRight;
    private LinearGradient hingeToLeft;
    private LinearGradient glassToLeft;

    TransitionOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setWillNotDraw(false);
    }

    void bind(Bitmap sourceBitmap, Bitmap targetBitmap, Direction d) {
        source = sourceBitmap;
        target = targetBitmap;
        targetArrivedMillis = targetBitmap == null ? 0L : SystemClock.uptimeMillis();
        direction = d;
        handedOff = false;
        handoffMillis = 0L;
        invalidate();
    }

    void setTarget(Bitmap targetBitmap) {
        target = targetBitmap;
        targetArrivedMillis = targetBitmap == null ? 0L : SystemClock.uptimeMillis();
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

    private float targetBlend() {
        if (!handedOff || target == null || target.isRecycled()) return 0f;
        long start = Math.max(handoffMillis, targetArrivedMillis);
        float t = (SystemClock.uptimeMillis() - start)
                / (float) Math.max(120, Prefs.handoffMs(getContext()));
        return MotionMath.smootherstep(t);
    }

    private float visualAngle() {
        // Predict a small fraction of a frame ahead. Fast physical motion otherwise appears to trail the hinge.
        float lead = MotionMath.clamp(velocity * 0.018f, -3.4f, 3.4f);
        return MotionMath.clamp(angle + lead, 0f, 180f);
    }

    private float speedBoost() {
        return MotionMath.smoothstep(MotionMath.remap(Math.abs(velocity), 55f, 420f));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        blurNode.setPosition(0, 0, Math.max(1, w), Math.max(1, h));
        float cx = w * 0.5f;
        hingeToRight = new LinearGradient(cx, 0, Math.max(cx + 1f, w), 0,
                new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                new float[]{0f, .14f, 1f}, Shader.TileMode.CLAMP);
        hingeToLeft = new LinearGradient(cx, 0, 0, 0,
                new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                new float[]{0f, .14f, 1f}, Shader.TileMode.CLAMP);
        glassToLeft = new LinearGradient(cx, 0, 0, 0,
                new int[]{0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, .20f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (source == null || source.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;
        if (direction == Direction.CLOSING) drawClosing(canvas); else drawOpening(canvas);
        if (handedOff && target != null && !target.isRecycled() && targetBlend() < .999f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawClosing(Canvas c) {
        float p = MotionMath.smootherstep(MotionMath.saturate((180f - visualAngle()) / 180f));
        if (!handedOff) drawClosingOnInner(c, p);
        else drawClosingOnCover(c, p, targetBlend());
    }

    private void drawOpening(Canvas c) {
        float p = MotionMath.smootherstep(MotionMath.saturate(visualAngle() / 180f));
        if (!handedOff) drawOpeningOnCover(c, p);
        else drawOpeningOnInner(c, p, targetBlend());
    }

    private Rect closingHeroSource(float progress) {
        int leftWidth = Math.max(1,
                Math.round(source.getWidth() * Prefs.leftPanel(getContext())));
        float coverAspect = MotionMath.clamp(Prefs.coverAspect(getContext()), .30f, .72f);
        int coverWidthInSource = Math.max(1,
                Math.min(leftWidth, Math.round(source.getHeight() * coverAspect)));
        float morph = MotionMath.smootherstep(MotionMath.remap(progress, .06f, .72f));
        int width = Math.max(1,
                Math.round(MotionMath.lerp(leftWidth, coverWidthInSource, morph)));
        // Left-edge anchoring is the core continuity illusion: content disappears toward the hinge.
        return new Rect(0, 0, width, source.getHeight());
    }

    private void drawClosingOnInner(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        float split = w * Prefs.leftPanel(getContext());
        float speed = speedBoost();
        c.drawColor(Color.BLACK);

        // Surviving left surface: remains visually stable while reframing toward cover-screen aspect.
        Rect heroSrc = closingHeroSource(p);
        RectF heroDst = new RectF(0, 0, split, h);
        float heroBlur = Prefs.blur(getContext()) * (.10f + .24f * speed)
                * (float) Math.pow(MotionMath.bell(p), .78f);
        float heroScaleX = 1f - Prefs.compression(getContext()) * .30f * MotionMath.bell(p);
        float heroScaleY = 1f - .008f * MotionMath.bell(p);
        int save = c.save();
        c.scale(heroScaleX, heroScaleY, split, h * .5f);
        drawBitmap(c, source, heroSrc, heroDst, heroBlur, 1f);
        c.restoreToCount(save);

        // Folding right surface: rotates into the hinge rather than simply shrinking/fading.
        int rightStart = Math.max(1,
                Math.round(source.getWidth() * Prefs.leftPanel(getContext())));
        Rect rightSrc = new Rect(rightStart, 0, source.getWidth(), source.getHeight());
        RectF rightDst = new RectF(split, 0, w, h);
        float pageFold = MotionMath.easeOutCubic(MotionMath.remap(p, .015f, .72f));
        float rotateY = -87f * pageFold;
        float rightBlur = Prefs.blur(getContext())
                * (.14f + .94f * MotionMath.smoothstep(MotionMath.remap(p, .04f, .62f)))
                + 6f * speed;
        float rightAlpha = 1f - .36f
                * MotionMath.smoothstep(MotionMath.remap(p, .58f, .88f));
        drawPerspectivePane(c, source, rightSrc, rightDst, rotateY, split, rightBlur, rightAlpha);

        float rightBlack = Prefs.black(getContext())
                * MotionMath.smootherstep(MotionMath.remap(p, .045f, .70f));
        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(Math.round(255f * MotionMath.clamp(rightBlack, 0f, .985f)));
        c.drawRect(split, 0, w, h, dimPaint);

        // The left pane remains readable much longer and falls into black only near display handoff.
        float heroBlack = .08f * MotionMath.bell(p)
                + .24f * MotionMath.smootherstep(MotionMath.remap(p, .73f, 1f));
        dimPaint.setAlpha(Math.round(255f * heroBlack));
        c.drawRect(0, 0, split, h, dimPaint);

        drawHingeShadow(c, split, MotionMath.clamp(.12f + p * 1.08f, 0f, 1f), true);
        drawGlassSheen(c, split, p);
    }

    private void drawClosingOnCover(Canvas c, float p, float blend) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);

        Rect heroSrc = closingHeroSource(1f);
        RectF full = coverRect(heroSrc.width(), heroSrc.height(), w, h);
        float settle = target == null ? 0f : blend;
        float sourceBlur = MotionMath.lerp(Prefs.blur(getContext()) * .20f, 0f, settle);
        float sourceAlpha = 1f - MotionMath.smootherstep(MotionMath.remap(settle, .28f, 1f));
        float squashX = MotionMath.lerp(.976f, 1f, settle);
        float squashY = MotionMath.lerp(.988f, 1f, settle);
        int save = c.save();
        c.scale(squashX, squashY, w * .5f, h * .5f);
        drawBitmap(c, source, heroSrc, full, sourceBlur, sourceAlpha);
        c.restoreToCount(save);

        if (target != null && !target.isRecycled()) {
            float targetAlpha = MotionMath.smootherstep(MotionMath.remap(settle, .03f, .92f));
            float targetBlur = Prefs.blur(getContext()) * .44f * (1f - targetAlpha);
            drawBitmapCover(c, target, targetBlur, targetAlpha);
        }

        float veil = MotionMath.lerp(.18f, 0f, settle);
        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(Math.round(255f * veil));
        c.drawRect(0, 0, w, h, dimPaint);
    }

    private void drawOpeningOnCover(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        float speed = speedBoost();
        c.drawColor(Color.BLACK);

        float blur = Prefs.blur(getContext())
                * (.12f + .78f * MotionMath.smoothstep(MotionMath.remap(p, .03f, .58f)))
                + 4f * speed;
        float scaleX = 1f - Prefs.compression(getContext()) * .48f * MotionMath.easeInCubic(p);
        float scaleY = 1f - .009f * MotionMath.easeInCubic(p);
        int save = c.save();
        c.scale(scaleX, scaleY, w * .5f, h * .5f);
        drawBitmapCover(c, source, blur, 1f);
        c.restoreToCount(save);

        float black = .58f * MotionMath.smootherstep(MotionMath.remap(p, .025f, .60f));
        dimPaint.setColor(Color.BLACK);
        dimPaint.setAlpha(Math.round(255f * black));
        c.drawRect(0, 0, w, h, dimPaint);
    }

    private void drawOpeningOnInner(Canvas c, float p, float blend) {
        int w = getWidth(), h = getHeight();
        float split = w * Prefs.leftPanel(getContext());
        c.drawColor(Color.BLACK);

        // Before Android exposes the destination frame, keep the cover image locked to the left half.
        Rect sourceFull = new Rect(0, 0, source.getWidth(), source.getHeight());
        RectF leftDst = new RectF(0, 0, split, h);
        float settle = target == null ? 0f : blend;
        float sourceAlpha = 1f - MotionMath.smootherstep(MotionMath.remap(settle, .20f, .92f));
        float sourceBlur = Prefs.blur(getContext()) * .30f * MotionMath.bell(settle);
        drawBitmap(c, source, sourceFull, leftDst, sourceBlur, sourceAlpha);

        if (target != null && !target.isRecycled()) {
            int targetSplitPx = Math.max(1,
                    Math.round(target.getWidth() * Prefs.leftPanel(getContext())));
            Rect targetLeft = new Rect(0, 0, targetSplitPx, target.getHeight());
            RectF targetLeftDst = new RectF(0, 0, split, h);
            float leftAlpha = MotionMath.smootherstep(MotionMath.remap(settle, .02f, .76f));
            float leftBlur = Prefs.blur(getContext()) * .30f * (1f - leftAlpha);
            drawBitmap(c, target, targetLeft, targetLeftDst, leftBlur, leftAlpha);

            Rect targetRight = new Rect(targetSplitPx, 0, target.getWidth(), target.getHeight());
            RectF targetRightDst = new RectF(split, 0, w, h);
            float pageOpen = MotionMath.smootherstep(
                    MotionMath.remap(Math.max(settle, p), .08f, .95f));
            float rotateY = -87f * (1f - pageOpen);
            float rightAlpha = MotionMath.smootherstep(MotionMath.remap(settle, .10f, .94f));
            float rightBlur = Prefs.blur(getContext()) * .72f * (1f - pageOpen);
            drawPerspectivePane(c, target, targetRight, targetRightDst,
                    rotateY, split, rightBlur, rightAlpha);

            float rightVeil = Prefs.black(getContext())
                    * (1f - MotionMath.smootherstep(MotionMath.remap(pageOpen, .12f, .90f)));
            dimPaint.setColor(Color.BLACK);
            dimPaint.setAlpha(Math.round(255f * rightVeil));
            c.drawRect(split, 0, w, h, dimPaint);
        }

        drawHingeShadow(c, split,
                1f - MotionMath.smootherstep(MotionMath.remap(Math.max(settle, p), .08f, .96f)),
                false);
    }

    private void drawPerspectivePane(Canvas c, Bitmap b, Rect src, RectF dst,
                                     float rotationY, float pivotX,
                                     float blurRadius, float alpha) {
        int save = c.save();
        c.clipRect(dst.left, dst.top, dst.right, dst.bottom);
        cameraMatrix.reset();
        camera.save();
        camera.rotateY(rotationY);
        camera.getMatrix(cameraMatrix);
        camera.restore();
        cameraMatrix.preTranslate(-pivotX, -dst.centerY());
        cameraMatrix.postTranslate(pivotX, dst.centerY());
        c.concat(cameraMatrix);
        drawBitmap(c, b, src, dst, blurRadius, alpha);
        c.restoreToCount(save);
    }

    private void drawHingeShadow(Canvas c, float split, float strength, boolean rightHeavy) {
        if (strength <= .001f) return;
        float w = getWidth(), h = getHeight();
        float band = Math.max(22f, w * .16f);
        seamPaint.setShader(rightHeavy ? hingeToRight : hingeToLeft);
        seamPaint.setAlpha(Math.round(255f
                * MotionMath.clamp(.12f + .78f * strength, 0f, .92f)));
        if (rightHeavy) {
            c.drawRect(split, 0, Math.min(w, split + band * 2f), h, seamPaint);
        } else {
            c.drawRect(Math.max(0, split - band * 2f), 0, split, h, seamPaint);
        }
        seamPaint.setShader(null);

        seamPaint.setColor(Color.BLACK);
        seamPaint.setAlpha(Math.round(255f * .52f * strength));
        float seam = Math.max(1.5f, w * .0045f) * (.45f + strength);
        c.drawRect(split - seam, 0, split + seam, h, seamPaint);
    }

    private void drawGlassSheen(Canvas c, float split, float progress) {
        float bell = MotionMath.bell(MotionMath.remap(progress, .05f, .88f));
        if (bell <= .005f || glassToLeft == null) return;
        float band = getWidth() * .16f;
        sheenPaint.setShader(glassToLeft);
        sheenPaint.setAlpha(Math.round(255f * Prefs.haze(getContext()) * .74f * bell));
        c.drawRect(Math.max(0f, split - band), 0, split, getHeight(), sheenPaint);
        sheenPaint.setShader(null);
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
        }
        float dh = vw / Math.max(.0001f, ba);
        return new RectF(0, (vh - dh) * .5f, vw, (vh + dh) * .5f);
    }

    private RenderEffect blurEffect(float radius) {
        if (Build.VERSION.SDK_INT < 31) return null;
        int r = Math.round(MotionMath.clamp(radius, 0f, 56f) / 2f) * 2;
        if (r <= 0) return null;
        RenderEffect effect = blurCache.get(r);
        if (effect == null) {
            effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
            blurCache.put(r, effect);
        }
        return effect;
    }

    private void drawBitmap(Canvas c, Bitmap b, Rect src, RectF dst,
                            float blurRadius, float alpha) {
        imagePaint.setAlpha(Math.round(255f * MotionMath.saturate(alpha)));
        if (Build.VERSION.SDK_INT >= 31 && blurRadius > .5f
                && getWidth() > 0 && getHeight() > 0) {
            // RenderNode lets each pane own its blur; hinge/shadow overlays remain crisp.
            blurNode.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas recording = blurNode.beginRecording(getWidth(), getHeight());
            recording.drawBitmap(b, src, dst, imagePaint);
            blurNode.endRecording();
            blurNode.setRenderEffect(blurEffect(blurRadius));
            c.drawRenderNode(blurNode);
            blurNode.setRenderEffect(null);
        } else {
            c.drawBitmap(b, src, dst, imagePaint);
        }
        imagePaint.setAlpha(255);
    }

    @Override protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) blurNode.setRenderEffect(null);
        super.onDetachedFromWindow();
    }
}
