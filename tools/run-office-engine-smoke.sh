#!/usr/bin/env bash
# CI-only: the instrument accepts hardcoded synthetic fixtures, never user URIs.
set -euo pipefail
package=com.cryonum.captusbprint.debug
collect() {
  adb logcat -b all -d -v threadtime > office-engine-logcat.txt 2>&1 || true
  adb exec-out run-as "$package" cat files/office-probe-stage.txt > office-probe-stage.txt 2>/dev/null || true
  adb exec-out run-as "$package" cat files/office-probe-results.json > office-probe-results.json 2>/dev/null || true
}
trap collect EXIT
adb logcat -b all -c
timeout 300 adb shell am instrument -w "$package/com.cryonum.captusbprint.OfficeEngineSmoke" | tee office-engine-smoke.txt
if ! grep -F 'CAPT_OFFICE_ENGINE_CONVERSION_SMOKE_PASSED' office-engine-smoke.txt; then
  collect
  cat office-probe-stage.txt
  tail -n 250 office-engine-logcat.txt
  exit 1
fi
