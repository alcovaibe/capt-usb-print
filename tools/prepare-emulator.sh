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
