package com.continuitymotion.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "motion";
    private static final int PRESET_VERSION = 2;
    private Prefs() {}

    static float blur(Context c) {
        return prefs(c).getFloat("blur", 30f);
    }
    static float perspective(Context c) {
        return prefs(c).getFloat("perspective", 1.5f);
    }
    static float compression(Context c) {
        return prefs(c).getFloat("compression", 0.016f);
    }
    static float haze(Context c) {
        return prefs(c).getFloat("haze", 0.025f);
    }

    static void setBlur(Context c, float v) { prefs(c).edit().putFloat("blur", v).apply(); }
    static void setPerspective(Context c, float v) { prefs(c).edit().putFloat("perspective", v).apply(); }
    static void setCompression(Context c, float v) { prefs(c).edit().putFloat("compression", v).apply(); }
    static void setHaze(Context c, float v) { prefs(c).edit().putFloat("haze", v).apply(); }

    static void applyDuoPreset(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("preset_version", PRESET_VERSION)
                .putFloat("blur", 30f)
                .putFloat("perspective", 1.5f)
                .putFloat("compression", 0.016f)
                .putFloat("haze", 0.025f)
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (p.getInt("preset_version", 0) < PRESET_VERSION) {
            // v0.1's tuning centered blur at 90 degrees and used much stronger
            // perspective/haze. Migrate existing installs to the new reference
            // preset once so upgrading the APK actually changes the motion.
            p.edit()
                    .putInt("preset_version", PRESET_VERSION)
                    .putFloat("blur", 30f)
                    .putFloat("perspective", 1.5f)
                    .putFloat("compression", 0.016f)
                    .putFloat("haze", 0.025f)
                    .apply();
        }
        return p;
    }
}
