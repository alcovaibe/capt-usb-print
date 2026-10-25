#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'find "$classes" -type f -delete; find "$classes" -depth -type d -empty -delete' EXIT
if command -v javac >/dev/null; then compiler=(javac); else compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${compiler[@]}" --release 17 -Xlint:all -Werror -d "$classes" \
  app/src/main/java/com/cryonum/captusbprint/office/OfficePrivateFiles.java tools/OfficePrivateFilesCheck.java
java -cp "$classes" com.cryonum.captusbprint.office.OfficePrivateFilesCheck
