package com.continuitymotion.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

final class PreviewSurfaceView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float angle = 180f;
    private final float density;

    PreviewSurfaceView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setAngle(float value) {
        angle = value;
        if (Build.VERSION.SDK_INT >= 31) {
            float blur = MotionMath.handoffBlur(angle);
            float radius = Prefs.blur(getContext()) * blur * density;
            setRenderEffect(radius > 0.5f
                    ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                    : null);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(15, 16, 21));

        float envelope = MotionMath.handoffOpacity(angle);
        float zoom = 1f + Prefs.compression(getContext()) * envelope;
        c.save();
        c.scale(zoom, zoom, w / 2f, h / 2f);

        // Neutral mock home screen: no Apple artwork/assets are bundled.
        p.setColor(Color.rgb(33, 35, 45));
        c.drawRoundRect(new RectF(20*density, 20*density, w-20*density, h-20*density), 34*density, 34*density, p);

        int cols = 4;
        float margin = 48*density;
        float usable = w - 2*margin;
        float step = usable/(cols-1);
        float top = 120*density;
        float rowStep = 104*density;
        for (int i=0;i<16;i++) {
            int col = i%cols, row=i/cols;
            float x = margin + col*step;
            float y = top + row*rowStep;
            float hue = (i * 29f) % 360f;
            p.setColor(Color.HSVToColor(new float[]{hue, .42f, .95f}));
            c.drawRoundRect(new RectF(x-27*density,y-27*density,x+27*density,y+27*density), 13*density,13*density,p);
        }
        p.setColor(Color.argb(150,255,255,255));
        c.drawRoundRect(new RectF(42*density,h-112*density,w-42*density,h-44*density), 30*density,30*density,p);
        c.restore();

        int veil = Math.round(255f * Prefs.haze(getContext()) * envelope);
        if (veil > 0) {
            p.setColor(Color.argb(veil,20,22,28));
            c.drawRect(0,0,w,h,p);
        }
    }
}
