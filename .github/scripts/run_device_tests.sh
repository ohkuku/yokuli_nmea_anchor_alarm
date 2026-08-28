#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-}"

gradle_args=(--no-daemon connectedDebugAndroidTest --stacktrace)

case "$MODE" in
  all)
    ;;
  shard)
    shard_count="${2:-}"
    shard_index="${3:-}"
    [[ "$shard_count" =~ ^[1-9][0-9]*$ ]] || { printf 'Invalid shard count: %s\n' "$shard_count" >&2; exit 2; }
    [[ "$shard_index" =~ ^[0-9]+$ ]] || { printf 'Invalid shard index: %s\n' "$shard_index" >&2; exit 2; }
    (( shard_index < shard_count )) || { printf 'Shard index must be lower than shard count.\n' >&2; exit 2; }
    gradle_args+=(
      "-Pandroid.testInstrumentationRunnerArguments.numShards=$shard_count"
      "-Pandroid.testInstrumentationRunnerArguments.shardIndex=$shard_index"
    )
    ;;
  smoke)
    gradle_args+=(
      '-Pandroid.testInstrumentationRunnerArguments.class=com.yokuli.anchorwatch.AppLaunchTest,com.yokuli.anchorwatch.AccessibilityLayoutTest'
    )
    ;;
  soak)
    gradle_args+=(
      '-Pandroid.testInstrumentationRunnerArguments.class=com.yokuli.anchorwatch.BackupRestoreStoryTest,com.yokuli.anchorwatch.BackupHighVolumeStoryTest,com.yokuli.anchorwatch.Migration5To6Test,com.yokuli.anchorwatch.AnchorSafetyFlowTest,com.yokuli.anchorwatch.SonarIncrementalGridTest,com.yokuli.anchorwatch.SonarTileScaleTest,com.yokuli.anchorwatch.AccessibilityLayoutTest'
    )
    ;;
  *)
    printf 'Usage: %s all | shard SHARD_COUNT SHARD_INDEX | smoke | soak\n' "$0" >&2
    exit 2
    ;;
esac

cd "$REPO_ROOT"
mkdir -p "$REPO_ROOT/build"

# `adb emu geo fix` is a point-in-time injection. A shard can spend several
# minutes installing/building before the System-GNSS story executes, so the
# originally injected Location.elapsedRealtime is no longer a fresh provider
# sample. Keep the emulator's configured position current for the duration of
# the device gate. This is test-fixture provisioning, not an App retry/wait.
gnss_heartbeat_pid=""
stop_gnss_heartbeat() {
  if [[ -n "$gnss_heartbeat_pid" ]]; then
    kill "$gnss_heartbeat_pid" 2>/dev/null || true
    wait "$gnss_heartbeat_pid" 2>/dev/null || true
  fi
}
if command -v adb >/dev/null 2>&1 && adb emu geo fix 174.7633 -36.8485 >/dev/null 2>&1; then
  (
    while adb emu geo fix 174.7633 -36.8485 >/dev/null 2>&1; do
      sleep 5
    done
  ) &
  gnss_heartbeat_pid="$!"
  trap stop_gnss_heartbeat EXIT
fi

set +e
./gradlew "${gradle_args[@]}" 2>&1 | tee "$REPO_ROOT/build/ci-device-tests.log"
gradle_status="${PIPESTATUS[0]}"
set -e
exit "$gradle_status"
