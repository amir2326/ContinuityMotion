package com.continuitymotion.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ScrollView;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

public final class MainActivity extends Activity {
    private TextView serviceStatus;
    private TextView hingeStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(10,10,12));
        getWindow().setNavigationBarColor(Color.rgb(10,10,12));
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(10,10,12));

        TextView title = text("Continuity Motion", 29, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = text("Apple-style fold / unfold continuity for Android foldables", 15, Color.rgb(184,184,195));
        sub.setPadding(0, dp(5), 0, dp(22));
        root.addView(sub);

        hingeStatus = text("", 15, Color.WHITE);
        serviceStatus = text("", 15, Color.WHITE);
        root.addView(card(hingeStatus));
        root.addView(space(10));
        root.addView(card(serviceStatus));
        root.addView(space(18));

        Button enable = button("Enable system-wide transition");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enable);
        root.addView(space(10));

        Button preview = button("Preview / calibrate with hinge");
        preview.setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class)));
        root.addView(preview);
        root.addView(space(22));

        root.addView(section("Duo-style preset"));
        addSlider(root, "Progressive blur", 0, 36, Math.round(Prefs.blur(this)), v -> Prefs.setBlur(this, v));
        addSlider(root, "Perspective", 0, 10, Math.round(Prefs.perspective(this)), v -> Prefs.setPerspective(this, v));
        addSlider(root, "Mid-fold compression", 0, 80, Math.round(Prefs.compression(this) * 1000f), v -> Prefs.setCompression(this, v/1000f));
        addSlider(root, "Glass haze", 0, 24, Math.round(Prefs.haze(this) * 100f), v -> Prefs.setHaze(this, v/100f));

        TextView privacy = text("Privacy: this app requests no internet permission. In system-wide mode, Android grants the Accessibility service permission to capture a temporary on-device frame so the current screen can be animated. The bitmap stays in memory only and is discarded at the end of the transition.", 13, Color.rgb(150,150,162));
        privacy.setPadding(0, dp(22), 0, 0);
        root.addView(privacy);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10,10,12));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        updateStatus();
    }

    private void updateStatus() {
        if (hingeStatus == null || serviceStatus == null) return;
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor hinge = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        hingeStatus.setText(hinge == null ? "Hinge sensor: not exposed by this device" : "Hinge sensor: available ✓");
        hingeStatus.setTextColor(hinge == null ? Color.rgb(255,180,90) : Color.rgb(120,235,165));
        boolean enabled = isOurAccessibilityServiceEnabled();
        serviceStatus.setText(enabled ? "System-wide overlay: enabled ✓" : "System-wide overlay: disabled");
        serviceStatus.setTextColor(enabled ? Color.rgb(120,235,165) : Color.rgb(255,180,90));
    }

    private boolean isOurAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String pkg = getPackageName();
        for (AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo() != null && TextUtils.equals(info.getResolveInfo().serviceInfo.packageName, pkg)) return true;
        }
        return false;
    }

    private interface OnIntChanged { void onChanged(int value); }

    private void addSlider(LinearLayout root, String name, int min, int max, int value, OnIntChanged cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView label = text(name, 14, Color.rgb(220,220,228));
        TextView val = text(String.valueOf(value), 12, Color.rgb(140,140,152));
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                val.setText(String.valueOf(v));
                if (fromUser) cb.onChanged(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(label);
        row.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        row.addView(val);
        root.addView(row);
    }

    private TextView section(String s) {
        TextView t = text(s, 18, Color.WHITE);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(5));
        return t;
    }

    private View card(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.rgb(27,27,33));
        gd.setCornerRadius(dp(15));
        box.setBackground(gd);
        box.addView(child, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(false);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.rgb(49,49,60));
        gd.setCornerRadius(dp(15));
        b.setBackground(gd);
        b.setMinHeight(dp(54));
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
