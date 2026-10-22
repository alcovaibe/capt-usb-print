#!/usr/bin/env bash
set -euo pipefail

# GitHub-hosted Ubuntu images contain SDKs unrelated to this Kotlin/Android
# project. Remove only those known-unused toolchains from the disposable runner.
# Runs after Gradle and APK verification, before downloading the emulator image.
echo "Disk before removing unrelated hosted-runner toolchains:"
df -h "$HOME"
for directory in /usr/share/dotnet /opt/ghc /usr/local/share/powershell /usr/share/swift; do
  if [[ -d "$directory" ]]; then
    du -sh "$directory" 2>/dev/null || true
    sudo rm -rf -- "$directory"
    echo "Removed unused toolchain: $directory"
  fi
done
echo "Disk after cleanup:"
df -h "$HOME"
