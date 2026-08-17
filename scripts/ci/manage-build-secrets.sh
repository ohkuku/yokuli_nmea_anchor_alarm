#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOCAL_PROPERTIES="$REPO_ROOT/local.properties"
SECRETS_URL="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/settings/secrets/actions"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

require_macos() {
  [[ "$(uname -s)" == "Darwin" ]] || die "Clipboard helpers require macOS."
  command -v pbcopy >/dev/null 2>&1 || die "Required command not found: pbcopy"
}

property_value() {
  local requested_key="$1"
  [[ -f "$LOCAL_PROPERTIES" ]] || die "Missing $LOCAL_PROPERTIES"
  awk -F= -v requested_key="$requested_key" '
    /^[[:space:]]*[A-Za-z_][A-Za-z0-9_.-]*[[:space:]]*=/ {
      key=$1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key == requested_key) {
        value=substr($0, index($0, "=") + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        print value
        exit
      }
    }
  ' "$LOCAL_PROPERTIES"
}

secret_status() {
  local name="$1" requirement="$2" value
  value="$(property_value "$name")"
  if [[ -n "$value" ]]; then
    note "$name: LOCAL VALUE READY ($requirement)"
  else
    note "$name: NOT SET LOCALLY ($requirement)"
  fi
  unset value
}

show_status() {
  note "Repository: $REPO_ROOT"
  note "GitHub Actions Secrets: $SECRETS_URL"
  secret_status MAPS_API_KEY "complete Google Map/Satellite builds"
  secret_status LINZ_API_KEY "complete LINZ chart, depth and tide features"
  secret_status LINZ_HYDRO_TILE_TEMPLATE "optional override; absence is valid"
  note "Release signing: run scripts/signing/manage-signing.sh status"
  if command -v gh >/dev/null 2>&1; then
    note "GitHub CLI: available"
  else
    note "GitHub CLI: unavailable; use copy-secret and the GitHub website"
  fi
}

copy_secret() {
  require_macos
  local name="${1:-}" value
  case "$name" in
    MAPS_API_KEY|LINZ_API_KEY|LINZ_HYDRO_TILE_TEMPLATE) ;;
    *) die "Use MAPS_API_KEY, LINZ_API_KEY or LINZ_HYDRO_TILE_TEMPLATE." ;;
  esac
  value="$(property_value "$name")"
  [[ -n "$value" ]] || die "$name is not configured locally. Do not create an empty GitHub Secret."
  printf '%s' "$value" | pbcopy
  unset value
  note "$name copied without printing it. Paste it into the same GitHub Actions Secret, then clear the clipboard."
}

clear_clipboard() {
  require_macos
  printf '' | pbcopy
  note "Clipboard cleared."
}

upload_github_secrets() {
  command -v gh >/dev/null 2>&1 || die "GitHub CLI is not installed. Use copy-secret and the website."
  gh auth status >/dev/null 2>&1 || die "GitHub CLI is not authenticated. Run 'gh auth login' first."
  local name value
  for name in MAPS_API_KEY LINZ_API_KEY LINZ_HYDRO_TILE_TEMPLATE; do
    value="$(property_value "$name")"
    if [[ -n "$value" ]]; then
      printf '%s' "$value" | gh secret set "$name" --repo ohkuku/yokuli_nmea_anchor_alarm
      note "$name uploaded."
    else
      note "$name skipped because it is not configured locally."
    fi
  done
  unset value
}

show_help() {
  printf '%s\n' \
    "Anchor Watch product-secret helper (values are never printed)" \
    "" \
    "Usage:" \
    "  scripts/ci/manage-build-secrets.sh status" \
    "  scripts/ci/manage-build-secrets.sh copy-secret SECRET_NAME" \
    "  scripts/ci/manage-build-secrets.sh clear-clipboard" \
    "  scripts/ci/manage-build-secrets.sh github-secrets" \
    "" \
    "GitHub page: $SECRETS_URL"
}

case "${1:-help}" in
  status) show_status ;;
  copy-secret) copy_secret "${2:-}" ;;
  clear-clipboard) clear_clipboard ;;
  github-secrets) upload_github_secrets ;;
  help|-h|--help) show_help ;;
  *) show_help >&2; die "Unknown command: $1" ;;
esac
