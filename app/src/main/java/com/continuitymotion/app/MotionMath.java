package com.continuitymotion.app;

final class MotionMath {
    private MotionMath() {}

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float normalizeAngle(float angle) {
        return clamp(angle / 180f, 0f, 1f);
    }

    static float smoothstep(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    static float smootherstep(float x) {
        x = clamp(x, 0f, 1f);
        return x * x * x * (x * (x * 6f - 15f) + 10f);
    }

    static float remap(float value, float inMin, float inMax) {
        if (inMax <= inMin) return 0f;
        return clamp((value - inMin) / (inMax - inMin), 0f, 1f);
    }

    /**
     * Duo-style transition envelope.
     *
     * The visible software handoff is intentionally concentrated near the
     * closed end of the hinge travel instead of peaking at a 90 degree
     * half-fold.  The blur ramps in quickly as the source display approaches
     * the handoff, peaks around ~22 degrees, then dissolves by ~74 degrees.
     */
    static float handoffPulse(float angle) {
        final float peak = 22f;
        final float end = 74f;
        float rise = smootherstep(remap(angle, 0f, peak));
        float fall = 1f - smootherstep(remap(angle, peak, end));
        return clamp(rise * fall, 0f, 1f);
    }

    static float handoffBlur(float angle) {
        float p = handoffPulse(angle);
        // Keep the center broad and creamy rather than a sharp triangular peak.
        return (float) Math.pow(p, 0.72);
    }

    static float handoffOpacity(float angle) {
        float p = handoffPulse(angle);
        // Slightly narrower than blur: the live destination can show through.
        return (float) Math.pow(p, 0.92);
    }
}
