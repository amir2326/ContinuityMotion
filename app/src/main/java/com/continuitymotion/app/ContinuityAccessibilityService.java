package com.continuitymotion.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public final class ContinuityAccessibilityService extends AccessibilityService implements FoldSensor.Listener {
    private FoldSensor foldSensor;
    private WindowManager windowManager;
    private TransitionOverlayView overlay;
    private boolean captureInFlight;
    private float pendingAngle = 180f;
    private float pendingVelocity = 0f;
    private long lastMotionMillis = 0L;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        foldSensor = new FoldSensor(this, this);
        foldSensor.start();
    }

    @Override public void onDestroy() {
        if (foldSensor != null) foldSensor.stop();
        removeOverlay();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onHingeAngle(float angle, float velocity) {
        pendingAngle = angle;
        pendingVelocity = velocity;
        long now = android.os.SystemClock.uptimeMillis();
        boolean moving = Math.abs(velocity) > 5f;
        if (moving) lastMotionMillis = now;

        boolean inTransition = angle > 6f && angle < 174f;
        if (inTransition && moving) {
            if (overlay == null && !captureInFlight) captureFrame();
            if (overlay != null) overlay.setHinge(angle, velocity);
        }

        if (!inTransition) {
            main.postDelayed(() -> {
                long idle = android.os.SystemClock.uptimeMillis() - lastMotionMillis;
                if ((pendingAngle <= 6f || pendingAngle >= 174f) && idle >= 70L) removeOverlay();
            }, 80L);
        }
    }

    private void captureFrame() {
        captureInFlight = true;
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                captureInFlight = false;
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap software = null;
                try {
                    ColorSpace colorSpace = result.getColorSpace();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                    if (hw != null) software = hw.copy(Bitmap.Config.ARGB_8888, false);
                } finally {
                    buffer.close();
                }
                if (software != null && pendingAngle > 6f && pendingAngle < 174f) {
                    showOverlay(software);
                    overlay.setHinge(pendingAngle, pendingVelocity);
                } else if (software != null) {
                    software.recycle();
                }
            }

            @Override public void onFailure(int errorCode) {
                captureInFlight = false;
            }
        });
    }

    private void showOverlay(Bitmap bitmap) {
        if (windowManager == null) return;
        if (overlay == null) {
            overlay = new TransitionOverlayView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            windowManager.addView(overlay, lp);
        }
        overlay.setScreenshot(bitmap);
    }

    private void removeOverlay() {
        if (overlay != null && windowManager != null) {
            try { windowManager.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
            overlay = null;
        }
    }
}
