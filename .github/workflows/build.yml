name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      # Use the Android SDK preinstalled on GitHub's Ubuntu runners
      # (ANDROID_HOME) instead of android-actions/setup-android, which
      # fails trying to install the removed legacy "tools" package.
      - name: Accept Android SDK licenses
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true

      - uses: gradle/actions/setup-gradle@v4

      - name: Build debug APK
        run: gradle assembleDebug --no-daemon

      - uses: actions/upload-artifact@v4
        with:
          name: otp-relay-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
