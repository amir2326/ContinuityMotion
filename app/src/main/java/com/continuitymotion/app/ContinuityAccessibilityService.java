package com.continuitymotion.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private long lastCaptureAttemptMillis = 0L;
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
        long now = SystemClock.uptimeMillis();
        boolean moving = Math.abs(velocity) > 1.5f;
        if (moving) lastMotionMillis = now;

        // Start capturing almost immediately after the hinge leaves an endpoint.
        // v0.1 waited until 6 degrees, so the first rendered frame often arrived
        // visibly late. Keeping the overlay transparent outside the handoff band
        // means it can safely be prepared well in advance.
        boolean gestureActive = angle > 0.6f && angle < 179.4f;
        if (gestureActive && moving) {
            if (overlay == null && !captureInFlight && now - lastCaptureAttemptMillis > 280L) {
                captureFrame();
            }
        }

        if (overlay != null) overlay.setHinge(angle, velocity);

        boolean atEndpoint = angle <= 1.8f || angle >= 178.2f;
        if (atEndpoint) {
            main.postDelayed(() -> {
                long idle = SystemClock.uptimeMillis() - lastMotionMillis;
                boolean stillAtEndpoint = pendingAngle <= 1.8f || pendingAngle >= 178.2f;
                if (stillAtEndpoint && idle >= 130L) removeOverlay();
            }, 145L);
        }
    }

    private void captureFrame() {
        captureInFlight = true;
        lastCaptureAttemptMillis = SystemClock.uptimeMillis();
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

                if (software != null && pendingAngle > 0.4f && pendingAngle < 179.6f) {
                    showOverlay(software);
                    if (overlay != null) overlay.setHinge(pendingAngle, pendingVelocity);
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
        if (windowManager == null) {
            bitmap.recycle();
            return;
        }
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
            lp.setTitle("Continuity Motion handoff");
            try {
                windowManager.addView(overlay, lp);
            } catch (RuntimeException e) {
                overlay = null;
                bitmap.recycle();
                return;
            }
        }
        overlay.setScreenshot(bitmap);
    }

    private void removeOverlay() {
        captureInFlight = false;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
            overlay = null;
        }
    }
}
