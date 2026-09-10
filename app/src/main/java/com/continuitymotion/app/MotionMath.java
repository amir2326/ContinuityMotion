package com.continuitymotion.app;

final class MotionMath {
    private MotionMath() {}

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static float saturate(float v) { return clamp(v, 0f, 1f); }
    static float normalizeAngle(float angle) { return clamp(angle / 180f, 0f, 1f); }
    static float smoothstep(float x) { x = saturate(x); return x * x * (3f - 2f * x); }
    static float smootherstep(float x) { x = saturate(x); return x * x * x * (x * (x * 6f - 15f) + 10f); }
    static float remap(float value, float in0, float in1) {
        if (Math.abs(in1 - in0) < 0.0001f) return 0f;
        return saturate((value - in0) / (in1 - in0));
    }
    static float lerp(float a, float b, float t) { return a + (b - a) * saturate(t); }
    static float bell(float p) { float s = (float) Math.sin(Math.PI * saturate(p)); return s * s; }
    static float easeOutCubic(float x) { x = saturate(x); float y = 1f - x; return 1f - y * y * y; }
    static float easeInCubic(float x) { x = saturate(x); return x * x * x; }
    static float handoffOpacity(float angle) { return bell(saturate((180f - angle) / 180f)); }
    static float handoffBlur(float angle) { return (float) Math.pow(handoffOpacity(angle), 0.68); }
}
