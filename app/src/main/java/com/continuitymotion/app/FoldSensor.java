package com.continuitymotion.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

final class FoldSensor implements SensorEventListener {
    interface Listener {
        default void onHingeAngle(float angle) { }
        default void onHingeAngle(float angle, float velocity) { onHingeAngle(angle); }
    }

    private final SensorManager manager;
    private final Sensor hinge;
    private final Listener listener;
    private float filteredAngle = Float.NaN;
    private float lastFiltered = Float.NaN;
    private float filteredVelocity = 0f;
    private long lastNanos = 0L;

    FoldSensor(Context context, Listener listener) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        hinge = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        this.listener = listener;
    }

    boolean isAvailable() { return hinge != null; }
    String name() { return hinge == null ? "Unavailable" : hinge.getName(); }

    void start() {
        if (hinge != null && manager != null) manager.registerListener(this, hinge, 8_333, 0);
    }

    void stop() {
        if (manager != null) manager.unregisterListener(this);
        filteredAngle = Float.NaN;
        lastFiltered = Float.NaN;
        filteredVelocity = 0f;
        lastNanos = 0L;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) return;
        float raw = MotionMath.clamp(event.values[0], 0f, 180f);
        if (Float.isNaN(filteredAngle)) {
            filteredAngle = raw;
            lastFiltered = raw;
            lastNanos = event.timestamp;
            listener.onHingeAngle(filteredAngle, 0f);
            return;
        }

        float dt = event.timestamp > lastNanos ? (event.timestamp - lastNanos) / 1_000_000_000f : 0.016f;
        dt = MotionMath.clamp(dt, 0.001f, 0.060f);
        float alpha = 1f - (float) Math.exp(-dt / 0.014f);
        filteredAngle += alpha * (raw - filteredAngle);
        float instantaneous = (filteredAngle - lastFiltered) / dt;
        float velAlpha = 1f - (float) Math.exp(-dt / 0.028f);
        filteredVelocity += velAlpha * (instantaneous - filteredVelocity);

        lastFiltered = filteredAngle;
        lastNanos = event.timestamp;
        listener.onHingeAngle(filteredAngle, filteredVelocity);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
