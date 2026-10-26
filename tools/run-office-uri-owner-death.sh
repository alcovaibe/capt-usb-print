#!/usr/bin/env bash
# Instrumentation sends SIGKILL only to its main PID; independently observe both PIDs via kernel.
set -euo pipefail
package=com.cryonum.captusbprint.debug
timeout 15 adb shell run-as "$package" rm -f files/office-uri-owner-pids.txt
timeout 45 adb shell am instrument -w -e ownerDeath true "$package/com.cryonum.captusbprint.OfficeUriSmoke" > office-uri-owner-death.txt 2>&1 &
probe=$!
trap 'kill "$probe" 2>/dev/null || true' EXIT
pids=''
for ((attempt=0; attempt<100; attempt++)); do
  pids=$(timeout 3 adb exec-out run-as "$package" cat files/office-uri-owner-pids.txt 2>/dev/null || true)
  [[ "$pids" =~ ^[0-9]+\ [0-9]+$ ]] && break
  sleep 0.1
done
[[ "$pids" =~ ^[0-9]+\ [0-9]+$ ]]
read -r owner worker <<< "$pids"
[[ "$owner" != "$worker" && "$owner" -gt 1 && "$worker" -gt 1 ]]
process_state() {
  timeout 5 adb shell content call --uri content://com.cryonum.captusbprint.urifixture --method process --arg "$1"
}
# Do not mistake /proc visibility/SELinux denial for exit: the helper uses kill(pid,0)/ESRCH.
process_state "$worker" | grep -F 'gone=false'
owner_gone=false
for ((attempt=0; attempt<50; attempt++)); do
  if process_state "$owner" | grep -F 'gone=true'; then owner_gone=true; break; fi
  sleep 0.1
done
[[ "$owner_gone" == true ]]
gone=false
for ((attempt=0; attempt<50; attempt++)); do
  if process_state "$worker" | grep -F 'gone=true'; then gone=true; break; fi
  sleep 0.1
done
[[ "$gone" == true ]]
wait "$probe" || true
# A new Application cleans orphaned private work only after the killed worker is confirmed gone.
timeout 15 adb shell am start -W -n "$package/com.cryonum.captusbprint.MainActivity" >/dev/null
timeout 5 adb shell run-as "$package" test ! -e no_backup/office-work
timeout 5 adb shell content call --uri content://com.cryonum.captusbprint.urifixture --method reset >/dev/null
timeout 5 adb shell content call --uri content://com.cryonum.captusbprint.urifixture --method revoke --arg content://com.cryonum.captusbprint.urifixture/read-block >/dev/null
timeout 5 adb shell run-as "$package" rm -f files/office-uri-owner-pids.txt
adb shell am force-stop "$package"
adb shell run-as "$package" mkdir -p no_backup/office-work/cleanup-block
adb shell run-as "$package" touch no_backup/office-work/cleanup-block/synthetic
adb shell run-as "$package" chmod 500 no_backup/office-work/cleanup-block
timeout 30 adb shell am instrument -w -e startupBlocked true "$package/com.cryonum.captusbprint.OfficeUriSmoke" > office-uri-startup-cleanup.txt
adb shell run-as "$package" chmod 700 no_backup/office-work/cleanup-block
adb shell am force-stop "$package"
adb shell run-as "$package" rm -rf no_backup/office-work
grep -F 'CAPT_OFFICE_STARTUP_CLEANUP_BLOCK_PASSED' office-uri-startup-cleanup.txt
echo 'CAPT_OFFICE_URI_OWNER_DEATH_PASSED (main PID killed, worker exited, restart cleanup; no USB)'
