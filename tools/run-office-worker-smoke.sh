#!/usr/bin/env bash
# All inputs are hardcoded synthetic assets; no user URI is accepted by this instrument.
set -euo pipefail
package=com.cryonum.captusbprint.debug
case "${1:-conversion}" in
  conversion)
    entry=OfficeWorkerSmoke
    marker=CAPT_OFFICE_WORKER_CONVERSION_PASSED
    prefix=office-worker ;;
  lifecycle)
    entry=OfficeWorkerLifecycleSmoke
    marker=CAPT_OFFICE_WORKER_LIFECYCLE_PASSED
    prefix=office-worker-lifecycle ;;
  *) exit 64 ;;
esac
finish_probe() {
  local outcome=$?
  timeout 15 adb logcat -b all -d -v threadtime > "$prefix-logcat.txt" 2>&1 || true
  timeout 15 adb logcat -b crash -d -v threadtime > "$prefix-crash.txt" 2>&1 || true
  timeout 15 adb exec-out run-as "$package" cat files/office-worker-results.json > office-worker-results.json 2>/dev/null || true
  if [[ "$outcome" != 0 ]]; then
    cat "$prefix-crash.txt"
    tail -n 250 "$prefix-logcat.txt"
  fi
  timeout 15 adb shell am force-stop "$package" >/dev/null 2>&1 || true
  exit "$outcome"
}
trap finish_probe EXIT
adb logcat -b all -c
adb shell run-as "$package" rm -f files/office-worker-results.json
timeout 360 adb shell am instrument -w "$package/com.cryonum.captusbprint.$entry" | tee "$prefix-smoke.txt"
grep -F "$marker" "$prefix-smoke.txt"
