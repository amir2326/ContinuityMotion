package com.continuitymotion.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

final class FoldSensor implements SensorEventListener {
    interface Listener { void onHingeAngle(float angle, float velocity); }

    private final SensorManager manager;
    private final Sensor hinge;
    private final Listener listener;
    private float lastAngle = Float.NaN;
    private long lastNanos = 0L;

    FoldSensor(Context context, Listener listener) {
        this.manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.hinge = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        this.listener = listener;
    }

    boolean isAvailable() { return hinge != null; }

    void start() {
        if (hinge != null) manager.registerListener(this, hinge, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (manager != null) manager.unregisterListener(this);
        lastAngle = Float.NaN;
        lastNanos = 0L;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) return;
        float angle = MotionMath.clamp(event.values[0], 0f, 180f);
        float velocity = 0f;
        if (!Float.isNaN(lastAngle) && lastNanos != 0L && event.timestamp > lastNanos) {
            float dt = (event.timestamp - lastNanos) / 1_000_000_000f;
            if (dt > 0.0001f) velocity = (angle - lastAngle) / dt;
        }
        lastAngle = angle;
        lastNanos = event.timestamp;
        listener.onHingeAngle(angle, velocity);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
