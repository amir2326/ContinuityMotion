package com.continuitymotion.app;

final class MotionMath {
    private MotionMath() {}

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float remap(float value, float inMin, float inMax) {
        if (inMax == inMin) return 0f;
        return clamp((value - inMin) / (inMax - inMin), 0f, 1f);
    }

    static float smoothstep(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    static float smootherstep(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * x * (x * (x * 6f - 15f) + 10f);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp(t, 0f, 1f);
    }

    static float foldAmount(float angle) {
        return clamp(1f - angle / 180f, 0f, 1f);
    }

    static float panelMorph(float angle) {
        return smootherstep(remap(180f - angle, 7f, 150f));
    }

    static float handoffDark(float angle) {
        float rise = smootherstep(remap(180f - angle, 116f, 154f));
        float end = 1f - smootherstep(remap(180f - angle, 154f, 179f));
        return clamp(rise * end, 0f, 1f);
    }

    static float endpointBlack(float angle) {
        return 1f - smootherstep(remap(angle, 2.5f, 34f));
    }

    static float openingHandoff(float angle) {
        float rise = smootherstep(remap(angle, 2f, 24f));
        float fall = 1f - smootherstep(remap(angle, 24f, 70f));
        return clamp(rise * fall, 0f, 1f);
    }

    // Kept for the calibration preview screen.
    static float handoffBlur(float angle) {
        return clamp(Math.max(openingHandoff(angle), handoffDark(angle)), 0f, 1f);
    }

    static float handoffOpacity(float angle) {
        return panelMorph(angle);
    }
}
