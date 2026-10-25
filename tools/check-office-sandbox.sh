#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc -std=c11 -O2 -Wall -Wextra -Werror -pthread tools/office_sandbox_check.c app/src/main/cpp/office_sandbox.c -o "$work/check"
mkdir "$work/data"
timeout 20 "$work/check" "$work/data"
