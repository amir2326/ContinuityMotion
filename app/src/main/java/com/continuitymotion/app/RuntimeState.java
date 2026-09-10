package com.continuitymotion.app;

final class RuntimeState {
    private RuntimeState() {}
    static volatile float hingeAngle = Float.NaN;
    static volatile float hingeVelocity = 0f;
    static volatile long hingeEvents = 0L;
    static volatile String transition = "Idle";
    static volatile String capture = "No capture yet";
    static volatile String display = "Unknown";
}
