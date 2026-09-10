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
    private float filteredAngle = Float.NaN;
    private float filteredVelocity = 0f;
    private float lastFilteredAngle = Float.NaN;
    private long lastNanos = 0L;

    FoldSensor(Context context, Listener listener) {
        this.manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.hinge = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        this.listener = listener;
    }

    boolean isAvailable() { return hinge != null; }

    void start() {
        if (hinge != null) {
            // Samsung foldables can expose updates faster than SENSOR_DELAY_GAME.
            // We sample as fast as the OEM allows, then filter before rendering.
            manager.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    void stop() {
        if (manager != null) manager.unregisterListener(this);
        filteredAngle = Float.NaN;
        lastFilteredAngle = Float.NaN;
        filteredVelocity = 0f;
        lastNanos = 0L;
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) return;
        float rawAngle = MotionMath.clamp(event.values[0], 0f, 180f);

        if (Float.isNaN(filteredAngle) || lastNanos == 0L || event.timestamp <= lastNanos) {
            filteredAngle = rawAngle;
            lastFilteredAngle = rawAngle;
            filteredVelocity = 0f;
            lastNanos = event.timestamp;
            listener.onHingeAngle(filteredAngle, filteredVelocity);
            return;
        }

        float dt = (event.timestamp - lastNanos) / 1_000_000_000f;
        dt = MotionMath.clamp(dt, 0.001f, 0.05f);

        // Very short time constant: remove sensor stair-stepping without making
        // the visual effect visibly lag behind the physical hinge.
        float angleAlpha = 1f - (float) Math.exp(-dt / 0.010f);
        filteredAngle += (rawAngle - filteredAngle) * angleAlpha;

        float rawVelocity = (filteredAngle - lastFilteredAngle) / dt;
        float velocityAlpha = 1f - (float) Math.exp(-dt / 0.028f);
        filteredVelocity += (rawVelocity - filteredVelocity) * velocityAlpha;

        lastFilteredAngle = filteredAngle;
        lastNanos = event.timestamp;
        listener.onHingeAngle(filteredAngle, filteredVelocity);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
