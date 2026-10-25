#!/usr/bin/env bash
# All inputs are hardcoded synthetic assets; no user URI is accepted by this instrument.
set -euo pipefail
package=com.cryonum.captusbprint.debug
case "${1:-conversion}" in
  sandbox)
    entry=OfficeSandboxSmoke
    marker=CAPT_OFFICE_SANDBOX_ANDROID_PASSED
    prefix=office-sandbox ;;
  conversion)
    entry=OfficeWorkerSmoke
    marker=CAPT_OFFICE_WORKER_CONVERSION_PASSED
    prefix=office-worker ;;
  ui)
    entry=OfficePreviewUiSmoke
    marker=CAPT_OFFICE_PREVIEW_UI_PASSED
    prefix=office-ui ;;
  native-ui)
    entry=OfficeNativeUiSmoke
    marker=CAPT_OFFICE_NATIVE_UI_PASSED
    prefix=office-native-ui ;;
  kernel)
    entry=OfficeKernelLimitsSmoke
    marker=CAPT_OFFICE_KERNEL_LIMITS_PASSED
    prefix=office-kernel ;;
  lifecycle)
    entry=OfficeWorkerLifecycleSmoke
    marker=CAPT_OFFICE_WORKER_LIFECYCLE_PASSED
    prefix=office-worker-lifecycle ;;
  *) exit 64 ;;
esac
finish_probe() {
  local outcome=$?
  timeout 15 adb logcat -b all -d -v threadtime > "$prefix-logcat.txt" 2>&1 || true
  if [[ "$entry" == OfficeSandboxSmoke ]]; then
    if grep -F 'CAPT_OFFICE_PRIVATE_LOG_SENTINEL_933D' "$prefix-logcat.txt"; then outcome=1; fi
    if grep -F 'CAPT_PRIVATE_DOCUMENT_SENTINEL_63B1' "$prefix-logcat.txt"; then outcome=1; fi
  fi
  timeout 15 adb logcat -b crash -d -v threadtime > "$prefix-crash.txt" 2>&1 || true
  if [[ "$entry" == OfficeWorkerSmoke ]]; then
    timeout 15 adb exec-out run-as "$package" cat files/office-worker-results.json > office-worker-results.json 2>/dev/null || true
    # Only fixed synthetic fixture PDFs, never an external URI or user workspace.
    timeout 15 adb exec-out run-as "$package" tar -C files -cf - office-layout-smoke > office-layout-synthetic.tar 2>/dev/null || true
  fi
  if [[ "$outcome" != 0 ]]; then
    cat "$prefix-crash.txt"
    tail -n 250 "$prefix-logcat.txt"
  fi
  timeout 15 adb shell am force-stop "$package" >/dev/null 2>&1 || true
  exit "$outcome"
}
trap finish_probe EXIT
adb logcat -b all -c
if [[ "$entry" == OfficeWorkerSmoke ]]; then
  adb shell run-as "$package" rm -f files/office-worker-results.json
fi
timeout 360 adb shell am instrument -w "$package/com.cryonum.captusbprint.$entry" | tee "$prefix-smoke.txt"
grep -F "$marker" "$prefix-smoke.txt"
