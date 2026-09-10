package com.continuitymotion.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "motion_v1";
    private Prefs() {}

    static float blur(Context c) { return prefs(c).getFloat("blurPx", 42f); }
    static float perspective(Context c) { return prefs(c).getFloat("perspective", 0.055f); }
    static float black(Context c) { return prefs(c).getFloat("black", 0.88f); }
    static int handoffMs(Context c) { return prefs(c).getInt("handoffMs", 230); }
    static float leftPanel(Context c) { return prefs(c).getFloat("leftPanel", 0.50f); }
    static float compression(Context c) { return prefs(c).getFloat("compression", 0.035f); }
    static float haze(Context c) { return prefs(c).getFloat("haze", 0.10f); }

    static void setBlur(Context c, float v) { prefs(c).edit().putFloat("blurPx", v).apply(); }
    static void setPerspective(Context c, float v) { prefs(c).edit().putFloat("perspective", v).apply(); }
    static void setBlack(Context c, float v) { prefs(c).edit().putFloat("black", v).apply(); }
    static void setHandoffMs(Context c, int v) { prefs(c).edit().putInt("handoffMs", v).apply(); }
    static void setLeftPanel(Context c, float v) { prefs(c).edit().putFloat("leftPanel", v).apply(); }
    static void setCompression(Context c, float v) { prefs(c).edit().putFloat("compression", v).apply(); }
    static void setHaze(Context c, float v) { prefs(c).edit().putFloat("haze", v).apply(); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }
}
