package com.continuitymotion.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "motion";
    private Prefs() {}

    static float blur(Context c) {
        return prefs(c).getFloat("blur", 26f);
    }
    static float perspective(Context c) {
        return prefs(c).getFloat("perspective", 6.2f);
    }
    static float compression(Context c) {
        return prefs(c).getFloat("compression", 0.042f);
    }
    static float haze(Context c) {
        return prefs(c).getFloat("haze", 0.12f);
    }
    static void setBlur(Context c, float v) { prefs(c).edit().putFloat("blur", v).apply(); }
    static void setPerspective(Context c, float v) { prefs(c).edit().putFloat("perspective", v).apply(); }
    static void setCompression(Context c, float v) { prefs(c).edit().putFloat("compression", v).apply(); }
    static void setHaze(Context c, float v) { prefs(c).edit().putFloat("haze", v).apply(); }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
