#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
if command -v javac >/dev/null; then
  compiler=(javac)
else
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" --release 17 -Xlint:all -Werror -d "$classes" \
  app/src/main/java/com/cryonum/captusbprint/document/PrivateDocumentImport.java \
  tools/PrivateDocumentImportCheck.java
java -cp "$classes" com.cryonum.captusbprint.document.PrivateDocumentImportCheck
