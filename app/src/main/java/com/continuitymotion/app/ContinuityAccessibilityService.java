package com.continuitymotion.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

public final class ContinuityAccessibilityService extends AccessibilityService implements FoldSensor.Listener {
    private FoldSensor foldSensor;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private TransitionOverlayView overlay;

    private Bitmap sourceFrame;
    private Bitmap targetFrame;
    private TransitionOverlayView.Direction direction;
    private boolean transitionActive = false;
    private boolean sourceCaptureInFlight = false;
    private boolean targetCaptureInFlight = false;
    private boolean handoffDetected = false;
    private float pendingAngle = 180f;
    private float pendingVelocity = 0f;
    private long motionStartedAt = 0L;
    private long lastMotionAt = 0L;
    private long generation = 0L;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    private int lastViewWidth = 0;
    private int lastViewHeight = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }

        foldSensor = new FoldSensor(this, this);
        foldSensor.start();
        RuntimeState.transition = "Ready";
        RuntimeState.capture = "Waiting for fold motion";
    }

    @Override public void onDestroy() {
        generation++;
        if (foldSensor != null) foldSensor.stop();
        finishTransition("Service stopped");
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (transitionActive) {
            if (overlay != null) {
                overlay.requestLayout();
                overlay.post(() -> checkDisplayHandoff(true));
            } else {
                checkDisplayHandoff(true);
            }
        }
    }

    @Override public void onHingeAngle(float angle, float velocity) {
        pendingAngle = angle;
        pendingVelocity = velocity;
        RuntimeState.hingeAngle = angle;
        RuntimeState.hingeVelocity = velocity;
        RuntimeState.hingeEvents++;

        long now = SystemClock.uptimeMillis();
        boolean moving = Math.abs(velocity) >= 2.2f;
        if (moving) lastMotionAt = now;

        if (!transitionActive && !sourceCaptureInFlight && moving) {
            if (velocity < -2.2f && angle < 178.8f && angle > 3f) {
                beginTransition(TransitionOverlayView.Direction.CLOSING);
            } else if (velocity > 2.2f && angle > 1.2f && angle < 177f) {
                beginTransition(TransitionOverlayView.Direction.OPENING);
            }
        }

        if (transitionActive) {
            if (overlay != null) {
                overlay.setHinge(angle, velocity);
                int vw = overlay.getWidth();
                int vh = overlay.getHeight();
                if (vw > 0 && vh > 0 && (vw != lastViewWidth || vh != lastViewHeight)) {
                    lastViewWidth = vw;
                    lastViewHeight = vh;
                    RuntimeState.display = vw + "×" + vh;
                }
            }
            checkDisplayHandoff(false);

            if (direction == TransitionOverlayView.Direction.CLOSING && velocity > 32f && angle > 45f) {
                finishTransition("Direction reversed");
                return;
            }
            if (direction == TransitionOverlayView.Direction.OPENING && velocity < -32f && angle < 135f) {
                finishTransition("Direction reversed");
                return;
            }

            boolean endpoint = direction == TransitionOverlayView.Direction.CLOSING ? angle <= 4.5f : angle >= 175.5f;
            if (endpoint && SystemClock.uptimeMillis() - lastMotionAt > 45L) {
                scheduleFinish(generation, handoffDetected ? Prefs.handoffMs(this) + 90L : 520L);
            }
        }
    }

    private void beginTransition(TransitionOverlayView.Direction d) {
        direction = d;
        transitionActive = false;
        handoffDetected = false;
        targetCaptureInFlight = false;
        sourceCaptureInFlight = true;
        motionStartedAt = SystemClock.uptimeMillis();
        generation++;
        long myGeneration = generation;
        RuntimeState.transition = d == TransitionOverlayView.Direction.CLOSING ? "Closing • capturing inner frame" : "Opening • capturing cover frame";
        RuntimeState.capture = "Source capture requested";

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                if (myGeneration != generation) {
                    closeResult(result);
                    return;
                }
                sourceCaptureInFlight = false;
                Bitmap b = bitmapFrom(result);
                if (b == null) {
                    RuntimeState.capture = "Source capture returned no bitmap";
                    return;
                }
                recycle(sourceFrame);
                recycle(targetFrame);
                sourceFrame = b;
                targetFrame = null;
                sourceWidth = b.getWidth();
                sourceHeight = b.getHeight();
                transitionActive = true;
                RuntimeState.capture = "Source " + sourceWidth + "×" + sourceHeight + " captured";
                RuntimeState.display = sourceWidth + "×" + sourceHeight;
                RuntimeState.transition = d == TransitionOverlayView.Direction.CLOSING ? "Closing • left pane anchored" : "Opening • cover frame anchored";
                showOverlay();
                if (overlay != null) overlay.setHinge(pendingAngle, pendingVelocity);
                checkDisplayHandoff(false);
            }

            @Override public void onFailure(int errorCode) {
                if (myGeneration != generation) return;
                sourceCaptureInFlight = false;
                RuntimeState.capture = "Source capture failed • error " + errorCode;
                RuntimeState.transition = "Idle • capture blocked";
            }
        });
    }

    private void showOverlay() {
        if (windowManager == null || sourceFrame == null) return;
        if (overlay == null) {
            overlay = new TransitionOverlayView(this);
            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.START;
            overlayParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            overlayParams.setTitle("Continuity Motion transition");
            try {
                windowManager.addView(overlay, overlayParams);
            } catch (RuntimeException e) {
                overlay = null;
                RuntimeState.transition = "Overlay attach failed";
                RuntimeState.capture = e.getClass().getSimpleName();
                return;
            }
        }
        overlay.bind(sourceFrame, targetFrame, direction);
        overlay.setHinge(pendingAngle, pendingVelocity);
        overlay.post(() -> {
            lastViewWidth = overlay == null ? 0 : overlay.getWidth();
            lastViewHeight = overlay == null ? 0 : overlay.getHeight();
            checkDisplayHandoff(false);
        });
    }

    private void checkDisplayHandoff(boolean configHint) {
        if (!transitionActive || handoffDetected || sourceWidth <= 0 || sourceHeight <= 0) return;

        boolean angleSupportsHandoff = direction == TransitionOverlayView.Direction.CLOSING
                ? pendingAngle < 62f : pendingAngle > 7f;
        if (!angleSupportsHandoff && !configHint) return;

        int[][] candidates = new int[3][2];
        if (overlay != null) {
            candidates[0][0] = overlay.getWidth();
            candidates[0][1] = overlay.getHeight();
        }
        if (windowManager != null && Build.VERSION.SDK_INT >= 30) {
            try {
                Rect b = windowManager.getCurrentWindowMetrics().getBounds();
                candidates[1][0] = b.width();
                candidates[1][1] = b.height();
            } catch (RuntimeException ignored) { }
        }
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            candidates[2][0] = dm.widthPixels;
            candidates[2][1] = dm.heightPixels;
        } catch (RuntimeException ignored) { }

        for (int[] geometry : candidates) {
            int vw = geometry[0], vh = geometry[1];
            if (vw <= 0 || vh <= 0) continue;
            if (geometryDiffersFromSource(vw, vh)) {
                onDisplayHandoff(vw, vh);
                return;
            }
        }
    }

    private boolean geometryDiffersFromSource(int width, int height) {
        float sourceAspect = sourceWidth / (float) Math.max(1, sourceHeight);
        float candidateAspect = width / (float) Math.max(1, height);
        float aspectDelta = Math.abs((float) Math.log(Math.max(.01f, candidateAspect / sourceAspect)));
        float areaRatio = (width * (double) height) / Math.max(1d, sourceWidth * (double) sourceHeight);
        return aspectDelta > 0.14f || areaRatio < 0.72 || areaRatio > 1.39;
    }

    private void onDisplayHandoff(int width, int height) {
        if (handoffDetected || !transitionActive) return;
        handoffDetected = true;
        long now = SystemClock.uptimeMillis();
        RuntimeState.display = width + "×" + height;
        RuntimeState.transition = direction == TransitionOverlayView.Direction.CLOSING
                ? "Handoff • resolving on cover" : "Handoff • resolving on inner display";
        if (overlay != null) {
            overlay.markHandoff(now);
            overlay.requestLayout();
        }

        long myGeneration = generation;
        main.postDelayed(() -> captureDestinationWindow(myGeneration, 0), 34L);
    }

    private void captureDestinationWindow(long myGeneration, int attempt) {
        if (myGeneration != generation || !transitionActive || targetCaptureInFlight) return;
        if (Build.VERSION.SDK_INT < 34) {
            RuntimeState.capture = "Destination window capture needs Android 14+";
            return;
        }

        AccessibilityWindowInfo appWindow = findBestApplicationWindow();
        if (appWindow == null) {
            if (attempt < 4) main.postDelayed(() -> captureDestinationWindow(myGeneration, attempt + 1), 45L);
            else RuntimeState.capture = "Destination app window not exposed";
            return;
        }

        int windowId = appWindow.getId();
        targetCaptureInFlight = true;
        takeScreenshotOfWindow(windowId, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                if (myGeneration != generation) {
                    closeResult(result);
                    return;
                }
                targetCaptureInFlight = false;
                Bitmap b = bitmapFrom(result);
                if (b == null) {
                    RuntimeState.capture = "Destination capture returned no bitmap";
                    return;
                }
                recycle(targetFrame);
                targetFrame = b;
                RuntimeState.capture = "Destination " + b.getWidth() + "×" + b.getHeight() + " captured";
                if (overlay != null) {
                    overlay.setTarget(targetFrame);
                    overlay.setHinge(pendingAngle, pendingVelocity);
                }
            }

            @Override public void onFailure(int errorCode) {
                if (myGeneration != generation) return;
                targetCaptureInFlight = false;
                RuntimeState.capture = "Destination capture failed • error " + errorCode;
                if (attempt < 2) main.postDelayed(() -> captureDestinationWindow(myGeneration, attempt + 1), 60L);
            }
        });
    }

    private AccessibilityWindowInfo findBestApplicationWindow() {
        List<AccessibilityWindowInfo> windows;
        try { windows = getWindows(); } catch (RuntimeException e) { return null; }
        AccessibilityWindowInfo fallback = null;
        if (windows == null) return null;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = null;
            try { root = w.getRoot(); } catch (RuntimeException ignored) { }
            boolean ours = false;
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                ours = pkg != null && getPackageName().contentEquals(pkg);
                root.recycle();
            }
            if (ours) continue;
            if (w.isFocused() || w.isActive()) return w;
            if (fallback == null) fallback = w;
        }
        return fallback;
    }

    private Bitmap bitmapFrom(ScreenshotResult result) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        if (buffer == null) return null;
        try {
            ColorSpace cs = result.getColorSpace();
            if (cs == null) cs = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
            if (hw == null) return null;
            return hw.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException e) {
            return null;
        } finally {
            try { buffer.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void closeResult(ScreenshotResult result) {
        try {
            HardwareBuffer b = result.getHardwareBuffer();
            if (b != null) b.close();
        } catch (RuntimeException ignored) { }
    }

    private void scheduleFinish(long myGeneration, long delay) {
        main.postDelayed(() -> {
            if (myGeneration != generation || !transitionActive) return;
            boolean endpoint = direction == TransitionOverlayView.Direction.CLOSING
                    ? pendingAngle <= 6f : pendingAngle >= 174f;
            boolean idle = SystemClock.uptimeMillis() - lastMotionAt > 80L;
            if (endpoint && idle) finishTransition("Ready");
        }, delay);
    }

    private void finishTransition(String state) {
        transitionActive = false;
        sourceCaptureInFlight = false;
        targetCaptureInFlight = false;
        handoffDetected = false;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null;
        overlayParams = null;
        recycle(sourceFrame);
        recycle(targetFrame);
        sourceFrame = null;
        targetFrame = null;
        sourceWidth = sourceHeight = 0;
        lastViewWidth = lastViewHeight = 0;
        RuntimeState.transition = state;
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }
}
