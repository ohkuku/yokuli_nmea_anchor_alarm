#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SIGNING_DIR="${YOKULI_SIGNING_DIR:-$REPO_ROOT/.signing}"
KEYSTORE_FILE="$SIGNING_DIR/anchor-watch-release.jks"
CONFIG_FILE="$SIGNING_DIR/config"
KEYCHAIN_SERVICE="com.yokuli.anchorwatch.release-signing"
STORE_PASSWORD_ACCOUNT="keystore-password"
KEY_PASSWORD_ACCOUNT="key-password"
DEFAULT_ALIAS="anchor-watch"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

note() {
  printf '%s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

require_macos() {
  [[ "$(uname -s)" == "Darwin" ]] || die "This signing vault uses macOS Keychain and must run on macOS."
  require_command security
}

read_alias() {
  local alias_value="$DEFAULT_ALIAS"
  if [[ -f "$CONFIG_FILE" ]]; then
    alias_value="$(sed -n 's/^alias=//p' "$CONFIG_FILE" | head -n 1)"
  fi
  [[ "$alias_value" =~ ^[A-Za-z0-9._-]+$ ]] || die "Invalid alias in $CONFIG_FILE"
  printf '%s' "$alias_value"
}

keychain_has() {
  security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$1" >/dev/null 2>&1
}

keychain_get() {
  security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$1" -w 2>/dev/null \
    || die "Password '$1' is missing from macOS Keychain. Run '$0 init' first."
}

keychain_set() {
  local account="$1"
  local secret_value="$2"
  # macOS `security` has no non-interactive stdin mode for this operation.
  # The value is never written to disk or printed, and the process is short-lived.
  security add-generic-password \
    -U \
    -a "$account" \
    -s "$KEYCHAIN_SERVICE" \
    -l "Boat Watch release signing: $account" \
    -w "$secret_value" >/dev/null
}

read_secret_twice() {
  local prompt="$1"
  local first second
  read -r -s -p "$prompt: " first
  printf '\n' >&2
  read -r -s -p "Confirm $prompt: " second
  printf '\n' >&2
  [[ "$first" == "$second" ]] || die "$prompt values did not match. Nothing was created."
  [[ ${#first} -ge 12 ]] || die "$prompt must be at least 12 characters. Nothing was created."
  printf '%s' "$first"
}

require_initialized() {
  [[ -f "$KEYSTORE_FILE" ]] || die "Signing key not initialized. Run '$0 init'."
  [[ -f "$CONFIG_FILE" ]] || die "Signing config missing: $CONFIG_FILE"
}

base64_keystore() {
  require_initialized
  /usr/bin/base64 -i "$KEYSTORE_FILE" | tr -d '\n'
}

fingerprint_output() {
  require_macos
  require_command keytool
  require_initialized
  local store_password signing_alias
  store_password="$(keychain_get "$STORE_PASSWORD_ACCOUNT")"
  signing_alias="$(read_alias)"
  export YOKULI_SIGNING_STORE_PASSWORD="$store_password"
  keytool -list -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$signing_alias" \
    -storepass:env YOKULI_SIGNING_STORE_PASSWORD
  unset YOKULI_SIGNING_STORE_PASSWORD store_password
}

init_signing() {
  require_macos
  require_command keytool
  [[ ! -e "$KEYSTORE_FILE" ]] || die "Refusing to overwrite the existing signing key: $KEYSTORE_FILE"
  [[ ! -e "$CONFIG_FILE" ]] || die "Refusing to overwrite the existing signing config: $CONFIG_FILE"

  local requested_alias signing_alias store_password key_password
  read -r -p "Signing alias [$DEFAULT_ALIAS]: " requested_alias
  signing_alias="${requested_alias:-$DEFAULT_ALIAS}"
  [[ "$signing_alias" =~ ^[A-Za-z0-9._-]+$ ]] || die "Alias may contain only letters, digits, dot, underscore and dash."

  store_password="$(read_secret_twice 'Keystore password')"
  note "The key password may be the same, but it is stored as a separate GitHub Secret."
  key_password="$(read_secret_twice 'Key password')"

  umask 077
  mkdir -p "$SIGNING_DIR"
  export YOKULI_SIGNING_STORE_PASSWORD="$store_password"
  export YOKULI_SIGNING_KEY_PASSWORD="$key_password"
  if ! keytool -genkeypair -v \
    -keystore "$KEYSTORE_FILE" \
    -storetype JKS \
    -alias "$signing_alias" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 36500 \
    -dname "CN=Boat Watch, OU=Yokuli, O=Yokuli, C=NZ" \
    -storepass:env YOKULI_SIGNING_STORE_PASSWORD \
    -keypass:env YOKULI_SIGNING_KEY_PASSWORD; then
    unset YOKULI_SIGNING_STORE_PASSWORD YOKULI_SIGNING_KEY_PASSWORD store_password key_password
    [[ ! -e "$KEYSTORE_FILE" ]] || mv "$KEYSTORE_FILE" "$KEYSTORE_FILE.incomplete"
    die "keytool failed. Any partial file was renamed with an .incomplete suffix."
  fi
  unset YOKULI_SIGNING_STORE_PASSWORD YOKULI_SIGNING_KEY_PASSWORD

  printf 'alias=%s\ncreated_at_utc=%s\n' "$signing_alias" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$CONFIG_FILE"
  chmod 600 "$KEYSTORE_FILE" "$CONFIG_FILE"
  keychain_set "$STORE_PASSWORD_ACCOUNT" "$store_password"
  keychain_set "$KEY_PASSWORD_ACCOUNT" "$key_password"
  unset store_password key_password

  note "Signing key created: $KEYSTORE_FILE"
  note "Passwords stored in macOS Keychain service: $KEYCHAIN_SERVICE"
  note "Back it up now: $0 backup /path/to/encrypted/backup-folder"
  note "Then configure GitHub: $0 github-secrets"
}

status_signing() {
  require_macos
  note "Repository: $REPO_ROOT"
  note "Local vault: $SIGNING_DIR (Git-ignored)"
  if [[ -f "$KEYSTORE_FILE" ]]; then
    note "Keystore: READY ($KEYSTORE_FILE)"
  else
    note "Keystore: NOT INITIALIZED"
  fi
  if [[ -f "$CONFIG_FILE" ]]; then
    note "Alias: $(read_alias)"
  else
    note "Alias: NOT INITIALIZED"
  fi
  if keychain_has "$STORE_PASSWORD_ACCOUNT"; then note "Keystore password: macOS Keychain READY"; else note "Keystore password: MISSING"; fi
  if keychain_has "$KEY_PASSWORD_ACCOUNT"; then note "Key password: macOS Keychain READY"; else note "Key password: MISSING"; fi
  if command -v gh >/dev/null 2>&1; then note "GitHub CLI: available"; else note "GitHub CLI: unavailable; use copy-secret and the GitHub website"; fi
}

show_fingerprint() {
  fingerprint_output | grep -E 'Owner:|所有者:|Valid from:|有效期|SHA1:|SHA256:' || true
}

copy_secret() {
  require_macos
  require_command pbcopy
  require_initialized
  local secret_name="${1:-}"
  case "$secret_name" in
    ANDROID_SIGNING_KEY_BASE64) base64_keystore | pbcopy ;;
    ANDROID_KEYSTORE_PASSWORD) keychain_get "$STORE_PASSWORD_ACCOUNT" | pbcopy ;;
    ANDROID_KEY_ALIAS) read_alias | pbcopy ;;
    ANDROID_KEY_PASSWORD) keychain_get "$KEY_PASSWORD_ACCOUNT" | pbcopy ;;
    *) die "Unknown secret. Use ANDROID_SIGNING_KEY_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS or ANDROID_KEY_PASSWORD." ;;
  esac
  note "$secret_name copied to the clipboard. Paste it into GitHub Actions Secrets, then clear the clipboard."
}

upload_github_secrets() {
  require_macos
  require_initialized
  require_command gh
  gh auth status >/dev/null 2>&1 || die "GitHub CLI is not authenticated. Run 'gh auth login' first."

  (
    cd "$REPO_ROOT"
    base64_keystore | gh secret set ANDROID_SIGNING_KEY_BASE64
    keychain_get "$STORE_PASSWORD_ACCOUNT" | gh secret set ANDROID_KEYSTORE_PASSWORD
    read_alias | gh secret set ANDROID_KEY_ALIAS
    keychain_get "$KEY_PASSWORD_ACCOUNT" | gh secret set ANDROID_KEY_PASSWORD
    note "Four release-signing secrets uploaded to the Boat Watch GitHub repository."
    gh secret list | grep -E '^ANDROID_(SIGNING_KEY_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)[[:space:]]' || true
  )
}

backup_signing() {
  require_initialized
  local destination="${1:-}"
  [[ -n "$destination" ]] || die "Usage: $0 backup /path/to/encrypted/backup-folder"
  [[ "$destination" != "$REPO_ROOT" && "$destination" != "$REPO_ROOT/"* ]] \
    || die "Backup destination must be outside the Git repository."
  mkdir -p "$destination"
  local timestamp backup_file metadata_file
  timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  backup_file="$destination/anchor-watch-release-$timestamp.jks"
  metadata_file="$destination/anchor-watch-release-$timestamp.txt"
  [[ ! -e "$backup_file" && ! -e "$metadata_file" ]] || die "Backup target already exists."
  cp -p "$KEYSTORE_FILE" "$backup_file"
  chmod 600 "$backup_file"
  {
    printf 'Boat Watch release signing backup\n'
    printf 'Created UTC: %s\n' "$timestamp"
    printf 'Alias: %s\n' "$(read_alias)"
    show_fingerprint
    printf '\nPasswords are stored separately in macOS Keychain/password manager.\n'
  } > "$metadata_file"
  chmod 600 "$metadata_file"
  note "Backup created: $backup_file"
  note "Metadata created: $metadata_file"
  note "Keep at least two encrypted/offline copies and record the two passwords in a password manager."
}

build_release() {
  require_macos
  require_initialized
  local version_name="${1:-}"
  local version_code="${2:-}"
  [[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.]+)?$ ]] \
    || die "Usage: $0 build-release VERSION_NAME VERSION_CODE"
  [[ "$version_code" =~ ^[1-9][0-9]*$ ]] || die "VERSION_CODE must be a positive integer."

  export ANDROID_KEYSTORE_FILE="$KEYSTORE_FILE"
  export ANDROID_KEYSTORE_PASSWORD="$(keychain_get "$STORE_PASSWORD_ACCOUNT")"
  export ANDROID_KEY_ALIAS="$(read_alias)"
  export ANDROID_KEY_PASSWORD="$(keychain_get "$KEY_PASSWORD_ACCOUNT")"
  export VERSION_NAME="$version_name"
  export VERSION_CODE="$version_code"
  (cd "$REPO_ROOT" && ./gradlew --no-daemon assembleRelease bundleRelease)
  unset ANDROID_KEYSTORE_FILE ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD VERSION_NAME VERSION_CODE
  local release_apk
  release_apk="$(find "$REPO_ROOT/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' -print -quit)"
  [[ -n "$release_apk" ]] || die "Release APK was not produced."
  note "Release APK: $release_apk"
  note "Release AAB: $REPO_ROOT/app/build/outputs/bundle/release/app-release.aab"
}

show_help() {
  cat <<'EOF'
Boat Watch release-signing manager (macOS)

Usage:
  scripts/signing/manage-signing.sh init
      Create the one permanent release keystore in .signing/ and store both
      passwords in macOS Keychain. Refuses to overwrite an existing key.

  scripts/signing/manage-signing.sh status
      Show whether the local key, config, Keychain passwords and gh are ready.

  scripts/signing/manage-signing.sh fingerprint
      Print the non-secret release certificate SHA-1/SHA-256 fingerprints.

  scripts/signing/manage-signing.sh copy-secret SECRET_NAME
      Copy one value to the macOS clipboard for manual GitHub configuration.
      Valid names:
        ANDROID_SIGNING_KEY_BASE64
        ANDROID_KEYSTORE_PASSWORD
        ANDROID_KEY_ALIAS
        ANDROID_KEY_PASSWORD

  scripts/signing/manage-signing.sh github-secrets
      Upload all four secrets using an installed and authenticated GitHub CLI.

  scripts/signing/manage-signing.sh backup DESTINATION
      Copy the keystore and fingerprint metadata to an explicit directory outside
      this repository. Passwords are not included in the backup.

  scripts/signing/manage-signing.sh build-release VERSION_NAME VERSION_CODE
      Build signed APK/AAB files locally with the permanent key.

There is deliberately no delete, reset or rotate command. Losing or replacing this
key prevents installed direct-distribution APKs from receiving future updates.
EOF
}

case "${1:-help}" in
  init) init_signing ;;
  status) status_signing ;;
  fingerprint) show_fingerprint ;;
  copy-secret) copy_secret "${2:-}" ;;
  github-secrets) upload_github_secrets ;;
  backup) backup_signing "${2:-}" ;;
  build-release) build_release "${2:-}" "${3:-}" ;;
  help|-h|--help) show_help ;;
  *) show_help >&2; die "Unknown command: $1" ;;
esac
