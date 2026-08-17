#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow_files=(
  "$REPO_ROOT/.github/workflows/android.yml"
  "$REPO_ROOT/.github/workflows/release.yml"
  "$REPO_ROOT/.github/workflows/soak.yml"
)

runner_count="$(grep -h -c 'uses: reactivecircus/android-emulator-runner' "${workflow_files[@]}" | awk '{ total += $1 } END { print total + 0 }')"
kvm_count="$(grep -h -c 'run: bash .github/scripts/enable_kvm.sh' "${workflow_files[@]}" | awk '{ total += $1 } END { print total + 0 }')"
device_script_count="$(grep -h -c 'bash .github/scripts/run_device_tests.sh' "${workflow_files[@]}" | awk '{ total += $1 } END { print total + 0 }')"

[[ "$runner_count" -eq 5 ]] || { printf 'Expected 5 emulator-runner jobs, found %s\n' "$runner_count" >&2; exit 1; }
[[ "$kvm_count" -eq "$runner_count" ]] || { printf 'Every emulator job must enable KVM.\n' >&2; exit 1; }
[[ "$device_script_count" -eq "$runner_count" ]] || { printf 'Every emulator job must use the device-test wrapper.\n' >&2; exit 1; }

if grep -nE '^ +\./gradlew .*\\$' "${workflow_files[@]}"; then
  printf 'Gradle line continuations are forbidden inside emulator-runner scripts.\n' >&2
  exit 1
fi

release_workflow="$REPO_ROOT/.github/workflows/release.yml"
grep -Fq 'group: android-release' "$release_workflow" \
  || { printf 'Release workflow must serialize immutable publication.\n' >&2; exit 1; }
grep -Fq 'cancel-in-progress: true' "$release_workflow" \
  || { printf 'A superseded release must be cancelled.\n' >&2; exit 1; }

printf 'emulator workflow contract checks passed\n'
