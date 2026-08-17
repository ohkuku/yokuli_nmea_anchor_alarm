#!/usr/bin/env bash
set -euo pipefail

fail_if_found() {
  local pattern="$1"
  shift
  if rg -n --hidden --glob '!build/**' --glob '!.gradle/**' "$pattern" "$@"; then
    echo "::error::Product policy guard matched forbidden pattern: $pattern"
    exit 1
  fi
}

fail_if_found 'Anchor by Yokuli' app/src README.md docs
fail_if_found '(isPro|premiumUnlocked|supporterUnlock|subscriptionActive|supportMember)' app/src/main
fail_if_found '(com\.android\.billing|billingclient|play-billing|admob|firebase-analytics)' app gradle build.gradle.kts settings.gradle.kts

rg -q '<string name="app_name">Anchor Watch</string>' app/src/main/res/values/strings.xml
rg -q 'https://www\.youtube\.com/@yokuli_ocean_diary' app/build.gradle.kts
rg -q 'https://buymeacoffee\.com/ukus3yya8a' app/build.gradle.kts
rg -q 'kuku\.the\.developer@gmail\.com' app/build.gradle.kts

echo 'Anchor Watch identity and free-feature policy guard passed.'
