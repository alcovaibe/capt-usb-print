#!/usr/bin/env bash
# Only CI synthetic state, never production signing or a user's document.
set -euo pipefail
package=com.cryonum.captusbprint.debug
baseline="${1:?baseline APK}"
current="${2:?current APK}"
report="${3:?fixed evidence file}"
adb install "$baseline"
adb shell am start -W -n "$package/com.cryonum.captusbprint.MainActivity"
adb shell run-as "$package" sh -c "'echo capt-office-upgrade-probe > files/office-upgrade-marker.txt'"
adb shell am force-stop "$package"
adb install -r "$current"
adb shell run-as "$package" cat files/office-upgrade-marker.txt | tr -d '\r' | grep -Fx capt-office-upgrade-probe
adb shell run-as "$package" rm files/office-upgrade-marker.txt
{
  echo 'CAPT_OFFICE_UPGRADE_PASSED (base main debug -> new ordinary Office APK; private synthetic state retained)'
  adb shell dumpsys package "$package" | sed -n '/versionCode=/p'
  # Own installed code only: APK and extracted native libraries, not document directories.
  code_path="$(adb shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p')"
  test -n "$code_path"
  adb shell run-as "$package" du -sk "${code_path%/*}"
} > "$report"
cat "$report"
