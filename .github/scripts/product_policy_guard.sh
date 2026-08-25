#!/usr/bin/env bash
set -euo pipefail

search_pattern() {
  local pattern="$1"
  shift
  if command -v rg >/dev/null 2>&1; then
    rg -n --hidden --glob '!build/**' --glob '!.gradle/**' --glob '!.git/**' "$pattern" "$@"
  else
    grep -RInE --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git -- "$pattern" "$@"
  fi
}

contains_pattern() {
  local pattern="$1"
  shift
  if command -v rg >/dev/null 2>&1; then
    rg -q "$pattern" "$@"
  else
    grep -RqE -- "$pattern" "$@"
  fi
}

fail_if_found() {
  local pattern="$1"
  shift
  if search_pattern "$pattern" "$@"; then
    echo "::error::Product policy guard matched forbidden pattern: $pattern"
    exit 1
  fi
}

require_pattern() {
  local pattern="$1"
  shift
  if ! contains_pattern "$pattern" "$@"; then
    echo "::error::Product policy guard could not find required pattern: $pattern"
    exit 1
  fi
}

fail_if_found 'Anchor by Yokuli' app/src README.md docs
fail_if_found '(^|[^[:alnum:]_])(isPro|premiumUnlocked|supporterUnlock|subscriptionActive|supportMember)([^[:alnum:]_]|$)' app/src/main
fail_if_found '(com\.android\.billing|billingclient|play-billing|admob|firebase-analytics)' app gradle build.gradle.kts settings.gradle.kts

require_pattern '<string name="app_name">Boat Watch</string>' app/src/main/res/values/strings.xml
require_pattern 'PRODUCT_NAME = "Boat Watch"' app/src/main/java/com/yokuli/anchorwatch/brand/ProductBrand.kt
fail_if_found '<string name="app_name">Anchor Watch</string>' app/src/main/res
require_pattern 'https://www\.youtube\.com/@yokuli_ocean_diary' app/build.gradle.kts
require_pattern 'https://buymeacoffee\.com/ukus3yya8a' app/build.gradle.kts
require_pattern 'kuku\.the\.developer@gmail\.com' app/build.gradle.kts

echo 'Boat Watch identity and free-feature policy guard passed.'
