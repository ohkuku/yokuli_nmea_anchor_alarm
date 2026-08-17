#!/usr/bin/env bash
set -euo pipefail

if [[ ! -e /dev/kvm ]]; then
  printf '::error::/dev/kvm is unavailable on this runner. Android emulator hardware acceleration cannot start.\n' >&2
  exit 1
fi

# GitHub-hosted Linux runners expose KVM, but the runner account is not always
# a member of the kvm group. The permission is limited to this disposable VM.
sudo chmod 0666 /dev/kvm

if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  printf '::error::The runner still cannot read and write /dev/kvm after permission setup.\n' >&2
  ls -l /dev/kvm >&2 || true
  exit 1
fi

printf 'KVM hardware acceleration is available.\n'
