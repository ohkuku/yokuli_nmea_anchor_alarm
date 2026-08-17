#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVER="$SCRIPT_DIR/resolve_release_metadata.sh"

stable="$(RELEASE_EVENT=push RELEASE_REF=refs/tags/v1.2.3 "$RESOLVER")"
grep -q '^channel=stable$' <<< "$stable"
grep -q '^version_name=1.2.3$' <<< "$stable"
grep -q '^version_code=102039000$' <<< "$stable"

alpha="$(RELEASE_EVENT=push RELEASE_REF=refs/tags/v1.2.4-alpha.7 "$RESOLVER")"
grep -q '^channel=alpha$' <<< "$alpha"
grep -q '^version_code=102041007$' <<< "$alpha"

beta="$(RELEASE_EVENT=workflow_dispatch RELEASE_TAG_INPUT=v1.2.4-beta.2 RELEASE_REF=refs/heads/codex/release/1.2.4 "$RESOLVER")"
grep -q '^channel=beta$' <<< "$beta"
grep -q '^version_code=102045002$' <<< "$beta"

if RELEASE_EVENT=push RELEASE_REF=refs/tags/not-a-version "$RESOLVER" >/dev/null 2>&1; then
  printf 'Invalid release tag unexpectedly resolved\n' >&2
  exit 1
fi

printf 'release metadata checks passed\n'
