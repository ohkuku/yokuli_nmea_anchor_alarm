#!/usr/bin/env bash
set -euo pipefail

keystore_file="${1:-}"
store_password="${ANDROID_KEYSTORE_PASSWORD:-}"
key_alias="${ANDROID_KEY_ALIAS:-}"
configured_key_password="${ANDROID_KEY_PASSWORD:-}"

fail() {
  printf '::error::%s\n' "$*" >&2
  exit 1
}

[[ -f "$keystore_file" ]] || fail "Decoded release keystore is missing. Check ANDROID_SIGNING_KEY_BASE64."
[[ -n "$store_password" ]] || fail "ANDROID_KEYSTORE_PASSWORD is empty."
[[ -n "$key_alias" ]] || fail "ANDROID_KEY_ALIAS is empty."
[[ -n "$configured_key_password" ]] || fail "ANDROID_KEY_PASSWORD is empty."
command -v keytool >/dev/null 2>&1 || fail "keytool is unavailable on the release runner."

export YOKULI_RELEASE_STORE_PASSWORD="$store_password"
if ! keytool -list \
  -keystore "$keystore_file" \
  -storepass:env YOKULI_RELEASE_STORE_PASSWORD \
  -alias "$key_alias" >/dev/null 2>&1; then
  fail "The keystore or alias cannot be opened. Re-copy ANDROID_SIGNING_KEY_BASE64, ANDROID_KEYSTORE_PASSWORD and ANDROID_KEY_ALIAS from the same local signing vault."
fi

can_recover_private_key() {
  local candidate="$1" verification_dir destination
  verification_dir="$(mktemp -d)"
  destination="$verification_dir/key-verification.p12"
  export YOKULI_RELEASE_KEY_PASSWORD="$candidate"
  export YOKULI_VERIFICATION_PASSWORD="anchor-watch-verification-only"
  keytool -importkeystore -noprompt \
    -srckeystore "$keystore_file" \
    -srcstoretype JKS \
    -srcstorepass:env YOKULI_RELEASE_STORE_PASSWORD \
    -srcalias "$key_alias" \
    -srckeypass:env YOKULI_RELEASE_KEY_PASSWORD \
    -destkeystore "$destination" \
    -deststoretype PKCS12 \
    -deststorepass:env YOKULI_VERIFICATION_PASSWORD >/dev/null 2>&1
}

effective_key_password="$configured_key_password"
if ! can_recover_private_key "$configured_key_password"; then
  if [[ "$configured_key_password" != "$store_password" ]] && can_recover_private_key "$store_password"; then
    effective_key_password="$store_password"
    printf '::warning::ANDROID_KEY_PASSWORD did not match the private key; this keystore uses the same password for the key and store. The release build will use ANDROID_KEYSTORE_PASSWORD.\n'
  else
    fail "ANDROID_KEY_PASSWORD cannot recover alias '$key_alias'. Re-copy that Secret from the local signing vault; the store password is valid but the private-key password is not."
  fi
fi

printf '::add-mask::%s\n' "$effective_key_password"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'ANDROID_KEY_PASSWORD=%s\n' "$effective_key_password" >> "$GITHUB_ENV"
fi
printf 'Release keystore, alias and private key are valid.\n'
