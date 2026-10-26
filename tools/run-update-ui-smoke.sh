#!/usr/bin/env bash
# Synthetic CI emulator only. Never skip UpdateSmoke or recover a CAPT app ANR.
set -euo pipefail
package=com.cryonum.captusbprint.debug
mkdir -p update-ui-evidence
run_probe() {
  timeout 90 adb shell am instrument -w "$package/com.cryonum.captusbprint.UpdateSmoke" | tee /tmp/capt-update-smoke.txt
}
capture() {
  local target="$1"
  mkdir -p "$target"
  timeout 15 adb pull "/sdcard/Android/data/$package/files/updates-main.png" "$target/" >/dev/null 2>&1 || true
  timeout 15 adb pull "/sdcard/Android/data/$package/files/updates-result.png" "$target/" >/dev/null 2>&1 || true
  cp /tmp/capt-update-smoke.txt "$target/instrumentation.txt"
}
run_probe
capture update-ui-evidence/attempt-1
if ! grep -F 'CAPT_UPDATE_UI_SMOKE_PASSED' /tmp/capt-update-smoke.txt; then
  # Screenshot evidence from API 36 showed a Quickstep ANR obscuring the correct
  # CAPT dialog. Require that exact stock-launcher system dialog before recovery.
  timeout 20 adb shell uiautomator dump /sdcard/capt-ci-launcher-window.xml
  timeout 15 adb exec-out cat /sdcard/capt-ci-launcher-window.xml > update-ui-evidence/launcher-window.xml
  python3 - <<'PY'
from xml.etree import ElementTree as ET
root=ET.parse('update-ui-evidence/launcher-window.xml').getroot()
assert any(n.get('package')=='android' and n.get('text') in (
    "Quickstep isn't responding",'Quickstep isn’t responding') for n in root.iter('node')), 'No verified Quickstep ANR; preserve real test failure'
PY
  home="$(timeout 15 adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
  [[ "$home" == com.android.launcher3/* ]]
  timeout 15 adb shell am force-stop com.android.launcher3
  timeout 20 adb shell am start -W -n "$package/com.cryonum.captusbprint.MainActivity"
  run_probe
  capture update-ui-evidence/attempt-2
fi
# A recovery never counts as success. The original real UI/provider assertions must pass.
grep -F 'CAPT_UPDATE_UI_SMOKE_PASSED' /tmp/capt-update-smoke.txt
capture update-ui-evidence
