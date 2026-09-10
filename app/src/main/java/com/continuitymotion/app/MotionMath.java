package com.continuitymotion.app;

final class MotionMath {
    private MotionMath() {}

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float normalizeAngle(float angle) {
        // Book-style foldables normally report ~0 closed and ~180 fully open.
        return clamp(angle / 180f, 0f, 1f);
    }

    static float smoothstep(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    static float bell(float p) {
        // Zero at both endpoints, one at mid-fold. Smoother than sin() near endpoints.
        float s = (float) Math.sin(Math.PI * clamp(p, 0f, 1f));
        return s * s;
    }
}
