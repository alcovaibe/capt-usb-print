#!/usr/bin/env bash
set -euo pipefail
apk="release-output/$(cat release-output/emulator-release-name.txt)"
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

# A user can move from the compatibility APK to the small device APK, and back.
universal="release-output/$(cat release-output/release-name.txt)"
adb shell am force-stop "$pkg"
adb install -r "$universal"
adb install -r "$apk"
adb shell am start -W -n "$pkg/.MainActivity"
echo 'PASS: signed x86_64 upgrade and universal/device APK migration without uninstall.' > release-output/upgrade-check.txt
