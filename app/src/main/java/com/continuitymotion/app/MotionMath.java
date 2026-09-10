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

    // 0 = completely open, 1 = completely folded.
    static float foldAmount(float angle) {
        return clamp(1f - angle / 180f, 0f, 1f);
    }

    // The visible panel morph starts almost immediately after closing begins and
    // finishes before the final hardware display handoff.
    static float panelMorph(float angle) {
        return smootherstep(remap(180f - angle, 7f, 150f));
    }

    // A soft optical blur/darkening pulse around the physical screen switch.
    static float handoffDark(float angle) {
        float rise = smootherstep(remap(180f - angle, 116f, 154f));
        float end = 1f - smootherstep(remap(180f - angle, 154f, 179f));
        return clamp(rise * end, 0f, 1f);
    }

    // Full black only in the final degrees. This hides Samsung's abrupt
    // compositor swap without making the whole fold gesture look dim.
    static float endpointBlack(float angle) {
        return 1f - smootherstep(remap(angle, 2.5f, 34f));
    }

    // Opening from the cover: darken just before the logical display expands.
    static float openingHandoff(float angle) {
        float rise = smootherstep(remap(angle, 2f, 24f));
        float fall = 1f - smootherstep(remap(angle, 24f, 70f));
        return clamp(rise * fall, 0f, 1f);
    }
}
