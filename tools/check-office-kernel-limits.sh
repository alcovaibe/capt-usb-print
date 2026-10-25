#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output="$(mktemp -d)"
trap 'find "$output" -type f -delete; find "$output" -depth -type d -empty -delete' EXIT
cc -std=c11 -O2 -Wall -Wextra -Werror -o "$output/check" tools/office_limits_check.c
"$output/check"
