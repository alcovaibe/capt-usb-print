#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
# Remove only this script's temporary compiled classes.
trap 'find "$classes" -type f -delete; find "$classes" -depth -type d -empty -delete' EXIT
if command -v javac >/dev/null; then
  compiler=(javac)
else
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" --release 17 -Xlint:all -Werror -d "$classes" \
  app/src/main/java/com/cryonum/captusbprint/office/OfficeWorkBudget.java \
  tools/OfficeWorkBudgetCheck.java
java -cp "$classes" com.cryonum.captusbprint.office.OfficeWorkBudgetCheck
