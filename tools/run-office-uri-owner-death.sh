#!/usr/bin/env bash
# Kill only the owning main PID, not the entire package: prove Binder-owner death stops :office.
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
timeout 5 adb shell run-as "$package" kill -9 "$owner"
gone=false
for ((attempt=0; attempt<50; attempt++)); do
  if timeout 3 adb shell run-as "$package" test ! -d "/proc/$worker"; then gone=true; break; fi
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
echo 'CAPT_OFFICE_URI_OWNER_DEATH_PASSED (main PID killed, worker exited, restart cleanup; no USB)'
