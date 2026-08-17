#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

release_event="${RELEASE_EVENT:-}"
release_ref="${RELEASE_REF:-}"
tag_input="${RELEASE_TAG_INPUT:-}"

case "$release_event" in
  push)
    [[ "$release_ref" == refs/tags/* ]] || die "Automatic releases require a tag push."
    release_tag="${release_ref#refs/tags/}"
    ;;
  workflow_dispatch)
    [[ -n "$tag_input" ]] || die "The manual release tag is required."
    release_tag="$tag_input"
    ;;
  *) die "Unsupported release event: $release_event" ;;
esac

if [[ "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-alpha\.([1-9][0-9]*)$ ]]; then
  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"
  prerelease_number="${BASH_REMATCH[4]}"
  release_channel="alpha"
  stage_code=$((1000 + 10#$prerelease_number))
elif [[ "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-beta\.([1-9][0-9]*)$ ]]; then
  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"
  prerelease_number="${BASH_REMATCH[4]}"
  release_channel="beta"
  stage_code=$((5000 + 10#$prerelease_number))
elif [[ "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"
  prerelease_number="0"
  release_channel="stable"
  stage_code=9000
else
  die "Tag must be vX.Y.Z, vX.Y.Z-alpha.N, or vX.Y.Z-beta.N (without leading zeroes)."
fi

(( 10#$major <= 20 )) || die "Major version must be 20 or lower for the Android versionCode scheme."
(( 10#$minor <= 99 )) || die "Minor version must be 99 or lower."
(( 10#$patch <= 99 )) || die "Patch version must be 99 or lower."
(( 10#$prerelease_number <= 999 )) || die "Prerelease number must be 999 or lower."

# This deterministic ordering keeps alpha < beta < stable for the same semantic
# version and keeps the next patch above the preceding stable release.
version_code=$((10#$major * 100000000 + 10#$minor * 1000000 + 10#$patch * 10000 + stage_code))
(( version_code > 0 && version_code <= 2100000000 )) || die "Derived Android versionCode is out of range."
version_name="${release_tag#v}"

emit_metadata() {
  printf 'tag=%s\n' "$release_tag"
  printf 'version_name=%s\n' "$version_name"
  printf 'version_code=%s\n' "$version_code"
  printf 'channel=%s\n' "$release_channel"
}

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  emit_metadata >> "$GITHUB_OUTPUT"
else
  emit_metadata
fi
