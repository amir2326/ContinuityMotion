package com.continuitymotion.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

final class TransitionOverlayView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint hazePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private Bitmap screenshot;
    private float angle = 180f;
    private float velocity = 0f;
    private final float density;

    TransitionOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setBackgroundColor(Color.BLACK);
    }

    void setScreenshot(Bitmap bitmap) {
        Bitmap old = screenshot;
        screenshot = bitmap;
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
        invalidate();
    }

    void setHinge(float hingeAngle, float hingeVelocity) {
        angle = MotionMath.clamp(hingeAngle, 0f, 180f);
        velocity = hingeVelocity;

        if (Build.VERSION.SDK_INT >= 31) {
            float p = MotionMath.normalizeAngle(angle);
            float bell = MotionMath.bell(p);
            float radius = Math.max(0.01f, Prefs.blur(getContext()) * bell * density);
            if (radius < 0.5f) {
                setRenderEffect(null);
            } else {
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            }
        }
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        setRenderEffect(null);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (screenshot == null || screenshot.isRecycled() || getWidth() <= 0 || getHeight() <= 0) return;

        float p = MotionMath.smoothstep(MotionMath.normalizeAngle(angle));
        float bell = MotionMath.bell(p);
        float compression = Prefs.compression(getContext()) * bell;
        float scale = 1f - compression;
        float rotation = Prefs.perspective(getContext()) * bell;
        if (velocity < 0f) rotation = -rotation;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        camera.save();
        camera.rotateY(rotation);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
        matrix.postScale(scale, scale, cx, cy);

        float sourceAspect = screenshot.getWidth() / (float) screenshot.getHeight();
        float viewAspect = getWidth() / (float) getHeight();
        RectF dst;
        if (sourceAspect > viewAspect) {
            float w = getHeight() * sourceAspect;
            dst = new RectF((getWidth() - w) / 2f, 0f, (getWidth() + w) / 2f, getHeight());
        } else {
            float h = getWidth() / sourceAspect;
            dst = new RectF(0f, (getHeight() - h) / 2f, getWidth(), (getHeight() + h) / 2f);
        }

        int save = canvas.save();
        canvas.concat(matrix);
        imagePaint.setAlpha((int) (255f * (1f - 0.05f * bell)));
        canvas.drawBitmap(screenshot, null, dst, imagePaint);
        canvas.restoreToCount(save);

        int alpha = (int) (255f * Prefs.haze(getContext()) * bell);
        hazePaint.setColor(Color.argb(alpha, 235, 235, 242));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), hazePaint);
    }
}
