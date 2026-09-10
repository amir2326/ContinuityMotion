package com.continuitymotion.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public final class PreviewActivity extends Activity implements FoldSensor.Listener {
    private PreviewSurfaceView preview;
    private TextView angleText;
    private FoldSensor sensor;
    private SeekBar seek;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewSurfaceView(this);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(22), dp(12), dp(22), dp(18));
        controls.setBackgroundColor(Color.argb(205, 12, 12, 15));
        angleText = new TextView(this);
        angleText.setTextColor(Color.WHITE);
        angleText.setTextSize(15);
        angleText.setGravity(Gravity.CENTER);
        seek = new SeekBar(this);
        seek.setMax(180);
        seek.setProgress(180);
        controls.addView(angleText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        controls.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(controls, cp);
        setContentView(root);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                if (fromUser) showAngle(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        sensor = new FoldSensor(this, this);
        showAngle(180);
    }

    @Override protected void onResume() { super.onResume(); if (sensor != null) sensor.start(); }
    @Override protected void onPause() { if (sensor != null) sensor.stop(); super.onPause(); }

    @Override public void onHingeAngle(float angle, float velocity) {
        int a = Math.round(angle);
        seek.setProgress(a);
        showAngle(a);
    }

    private void showAngle(int angle) {
        preview.setAngle(angle);
        angleText.setText("Hinge " + angle + "°   •   mid-fold blur/perspective peaks near 90°");
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
