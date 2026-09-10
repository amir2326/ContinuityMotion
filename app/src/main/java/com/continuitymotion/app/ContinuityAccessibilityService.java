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
    private Bitmap capturedFrame;
    private boolean captureInFlight;
    private boolean endpointRevealStarted;
    private float pendingAngle = 180f;
    private float pendingVelocity = 0f;
    private long lastMotionMillis;
    private long lastCaptureAttemptMillis;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        foldSensor = new FoldSensor(this, this);
        foldSensor.start();
    }

    @Override public void onDestroy() {
        if (foldSensor != null) foldSensor.stop();
        removeOverlayAndFrame();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window/display changes are intentionally allowed to resize the same
        // accessibility overlay. TransitionOverlayView detects that geometry
        // change and performs the second half of the handoff animation.
    }

    @Override public void onInterrupt() { }

    @Override public void onHingeAngle(float angle, float velocity) {
        pendingAngle = angle;
        pendingVelocity = velocity;
        long now = SystemClock.uptimeMillis();
        boolean moving = Math.abs(velocity) > 1.0f;
        boolean betweenEndpoints = angle > 0.35f && angle < 179.65f;

        if (moving) {
            lastMotionMillis = now;
            if (betweenEndpoints) endpointRevealStarted = false;
        }

        // Capture at the very beginning of the physical gesture. The overlay
        // itself is visually identical to the live screen at first, so there is
        // no flash while the morph engine is being prepared.
        if (betweenEndpoints && moving && overlay == null && !captureInFlight
                && now - lastCaptureAttemptMillis > 220L) {
            captureFrame();
        }

        if (overlay != null) overlay.setHinge(angle, velocity);

        boolean endpoint = angle <= 1.4f || angle >= 178.6f;
        if (endpoint && overlay != null && !endpointRevealStarted) {
            endpointRevealStarted = true;
            overlay.beginFinalReveal();
            main.postDelayed(() -> {
                boolean stillEndpoint = pendingAngle <= 2.2f || pendingAngle >= 177.8f;
                if (stillEndpoint && SystemClock.uptimeMillis() - lastMotionMillis > 120L) {
                    removeOverlayAndFrame();
                }
            }, 330L);
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

                if (software == null) return;
                if (pendingAngle <= 0.2f || pendingAngle >= 179.8f) {
                    software.recycle();
                    return;
                }

                if (capturedFrame != null && !capturedFrame.isRecycled()) capturedFrame.recycle();
                capturedFrame = software;
                showOverlay();
                if (overlay != null) overlay.setHinge(pendingAngle, pendingVelocity);
            }

            @Override public void onFailure(int errorCode) {
                captureInFlight = false;
            }
        });
    }

    private void showOverlay() {
        if (windowManager == null || capturedFrame == null || capturedFrame.isRecycled()) return;
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
            lp.setTitle("Continuity Motion panel handoff");
            try {
                windowManager.addView(overlay, lp);
            } catch (RuntimeException e) {
                overlay = null;
                return;
            }
        }
        overlay.setScreenshot(capturedFrame);
    }

    private void removeOverlayAndFrame() {
        captureInFlight = false;
        endpointRevealStarted = false;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
            overlay = null;
        }
        if (capturedFrame != null && !capturedFrame.isRecycled()) capturedFrame.recycle();
        capturedFrame = null;
    }
}
