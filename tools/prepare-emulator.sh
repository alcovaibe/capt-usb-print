#!/usr/bin/env bash
set -euo pipefail

# android-emulator-runner@v2 appends disk.dataPartition.size to config.ini.
# A device profile can already supply this key; emulator 37.2.12 may read
# the first occurrence, ignoring our appended 2048M override (build 56).
# Canonicalize the generated AVD BEFORE starting the emulator.
avd_dir="${ANDROID_AVD_HOME:-${HOME}/.android/avd}/test.avd"
config="${avd_dir}/config.ini"
test -s "$config"

echo "AVD disk settings before normalization:"
grep -E '^[[:space:]]*disk[.]dataPartition[.]size[[:space:]]*=' "$config" || true

tmp="$(mktemp "${config}.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
awk '!/^[[:space:]]*disk[.]dataPartition[.]size[[:space:]]*=/' "$config" > "$tmp"
printf 'disk.dataPartition.size=2048M\n' >> "$tmp"
mv "$tmp" "$config"

test "$(grep -Ec '^disk[.]dataPartition[.]size=' "$config")" -eq 1
grep -Fx 'disk.dataPartition.size=2048M' "$config"
echo "AVD data partition normalized to one 2048M entry."
df -h "$avd_dir"

# Emulator 37.2.12 additionally needs roughly 7.37 GB of free host disk
# even with a 2 GiB configured userdata partition (confirmed in runs 56/58).
# Guard the actual post-system-image free space, not just the AVD config.
if [[ "${CAPT_SKIP_DISK_CHECK:-0}" != "1" ]]; then
  available_kib="$(df -Pk "$avd_dir" | awk 'NR == 2 { print $4 }')"
  required_kib="$((9 * 1024 * 1024))"
  if (( available_kib < required_kib )); then
    echo "::error::Insufficient host disk for Android 16 emulator: ${available_kib} KiB free, ${required_kib} KiB required including headroom." >&2
    exit 1
  fi
  echo "Emulator host disk check passed: ${available_kib} KiB free."
fi
