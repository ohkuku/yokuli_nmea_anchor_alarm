#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANAGER="$SCRIPT_DIR/manage-release.sh"

help_output="$("$MANAGER" help)"
grep -q 'GitHub Actions performs all tests' <<< "$help_output"
grep -q 'never deletes, moves, or force-pushes' <<< "$help_output"
grep -q 'vX.Y.Z-alpha.N' <<< "$help_output"
grep -q 'visual Release Console' <<< "$help_output"

status_output="$("$MANAGER" status)"
grep -q '^Repository:' <<< "$status_output"
grep -q '^Branch:' <<< "$status_output"
grep -q '^Allowed automatic release:' <<< "$status_output"

printf 'manage-release static safety checks passed\n'
