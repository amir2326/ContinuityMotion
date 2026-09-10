# Continuity Motion

Experimental Android app that uses a foldable's hinge-angle sensor and an Accessibility overlay to create a hinge-synchronized fold/unfold continuity transition.

## Build

This repository builds a debug APK with GitHub Actions on every push to `main`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Platform note

A normal third-party APK cannot replace Samsung/Android's compositor-level fold transition globally. This project overlays the transition above apps using supported Android APIs. Secure/protected surfaces may block capture.
