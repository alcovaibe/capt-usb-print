#!/usr/bin/env bash
set -euo pipefail
apk="$(find release-output -maxdepth 1 -name '*.apk' -print -quit)"
test -s "$apk"
pkg="com.cryonum.captusbprint"
if test -s upgrade-input/previous.apk; then
  adb install upgrade-input/previous.apk
  adb shell am start -W -n "$pkg/.MainActivity"
  adb shell am force-stop "$pkg"
  adb install -r "$apk"
  echo 'PASS: Android 16 upgraded an installed signed release without uninstall.' > release-output/upgrade-check.txt
else
  adb install "$apk"
  echo 'PASS: Android 16 installed the first signed APK.' > release-output/upgrade-check.txt
fi
adb shell am start -W -n "$pkg/.MainActivity"
adb shell pm path "$pkg" | grep -F 'package:'
adb shell dumpsys package "$pkg" | grep -F 'versionCode=' | head -1
