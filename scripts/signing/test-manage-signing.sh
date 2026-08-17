#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANAGER="$SCRIPT_DIR/manage-signing.sh"
TEST_VAULT="$(mktemp -d "${TMPDIR:-/tmp}/yokuli-signing-test.XXXXXX")"
trap 'rm -rf "$TEST_VAULT"' EXIT

help_output="$(YOKULI_SIGNING_DIR="$TEST_VAULT" "$MANAGER" help)"
grep -q 'There is deliberately no delete, reset or rotate command' <<< "$help_output"
grep -q 'copy-secret SECRET_NAME' <<< "$help_output"

status_output="$(YOKULI_SIGNING_DIR="$TEST_VAULT" "$MANAGER" status)"
grep -q 'Keystore: NOT INITIALIZED' <<< "$status_output"
grep -q 'Keystore password: MISSING' <<< "$status_output"
grep -q 'Key password: MISSING' <<< "$status_output"

if YOKULI_SIGNING_DIR="$TEST_VAULT" "$MANAGER" fingerprint >/dev/null 2>&1; then
  printf 'fingerprint unexpectedly succeeded without a signing key\n' >&2
  exit 1
fi

if YOKULI_SIGNING_DIR="$TEST_VAULT" "$MANAGER" backup "$TEST_VAULT/backup" >/dev/null 2>&1; then
  printf 'backup unexpectedly succeeded without a signing key\n' >&2
  exit 1
fi

printf 'manage-signing static safety checks passed\n'
