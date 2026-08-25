#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REMOTE="${YOKULI_RELEASE_REMOTE:-origin}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

current_branch() {
  git -C "$REPO_ROOT" branch --show-current
}

channel_for_tag() {
  local tag="$1"
  if [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-alpha\.([1-9][0-9]*)$ ]]; then
    printf 'alpha'
  elif [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-beta\.([1-9][0-9]*)$ ]]; then
    printf 'beta'
  elif [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    printf 'stable'
  else
    die "Tag must be vX.Y.Z, vX.Y.Z-alpha.N, or vX.Y.Z-beta.N."
  fi
}

validate_branch_channel() {
  local branch="$1"
  local channel="$2"
  case "$channel" in
    alpha)
      [[ "$branch" == "codex/develop" || "$branch" == codex/release/* ]] \
        || die "Alpha releases must come from codex/develop or codex/release/*."
      ;;
    beta)
      [[ "$branch" == "main" || "$branch" == codex/release/* ]] \
        || die "Beta releases must come from main or codex/release/*."
      ;;
    stable)
      [[ "$branch" == "main" ]] || die "Stable releases must come from main."
      ;;
  esac
}

show_status() {
  local branch
  branch="$(current_branch)"
  printf 'Repository: %s\n' "$REPO_ROOT"
  printf 'Branch: %s\n' "${branch:-DETACHED}"
  printf 'Commit: %s\n' "$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
    printf 'Worktree: DIRTY (commit changes before publishing)\n'
  else
    printf 'Worktree: CLEAN\n'
  fi
  case "$branch" in
    codex/develop) printf 'Allowed automatic release: alpha\n' ;;
    codex/release/*) printf 'Allowed automatic release: alpha or beta\n' ;;
    main) printf 'Allowed automatic release: beta or stable\n' ;;
    *) printf 'Allowed automatic release: none\n' ;;
  esac
}

publish_tag() {
  require_command git
  local tag="${1:-}"
  [[ -n "$tag" ]] || die "Usage: $0 publish TAG"
  local branch channel local_sha remote_sha
  branch="$(current_branch)"
  [[ -n "$branch" ]] || die "A release cannot be published from detached HEAD."
  channel="$(channel_for_tag "$tag")"
  RELEASE_EVENT=push RELEASE_REF="refs/tags/$tag" \
    "$REPO_ROOT/.github/scripts/resolve_release_metadata.sh" >/dev/null
  validate_branch_channel "$branch" "$channel"
  [[ -z "$(git -C "$REPO_ROOT" status --porcelain)" ]] \
    || die "Commit or stash every change before publishing a release tag."

  git -C "$REPO_ROOT" fetch "$REMOTE" "$branch"
  local_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  remote_sha="$(git -C "$REPO_ROOT" rev-parse FETCH_HEAD)"
  [[ "$local_sha" == "$remote_sha" ]] \
    || die "Local $branch must exactly match $REMOTE/$branch. Push or reconcile it first."

  if git -C "$REPO_ROOT" ls-remote --exit-code --tags "$REMOTE" "refs/tags/$tag" >/dev/null 2>&1; then
    die "Remote tag already exists and will not be replaced: $tag"
  fi

  if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    [[ "$(git -C "$REPO_ROOT" rev-list -n 1 "$tag")" == "$local_sha" ]] \
      || die "Local tag $tag points at another commit and will not be changed."
  else
    git -C "$REPO_ROOT" tag -a "$tag" -m "Boat Watch $tag"
  fi

  git -C "$REPO_ROOT" push "$REMOTE" "refs/tags/$tag"
  printf 'Tag %s pushed. GitHub Actions will build and publish the signed %s release online.\n' "$tag" "$channel"
}

show_help() {
  cat <<'EOF'
Boat Watch online-release manager

Usage:
  scripts/release/manage-release.sh status
      Show the current branch, commit, worktree state and permitted channel.

  scripts/release/manage-release.sh console
      Open the loopback-only visual Release Console in the default browser.

  scripts/release/manage-release.sh publish TAG
      Validate the clean and fully pushed branch, create an immutable annotated
      version tag, and push only that tag. GitHub Actions performs all tests,
      signing, APK/AAB compilation and GitHub Release publication online.

Tag/channel rules:
  vX.Y.Z-alpha.N   codex/develop or codex/release/*
  vX.Y.Z-beta.N    codex/release/* or main
  vX.Y.Z           main only

The script never deletes, moves, or force-pushes a release tag.
EOF
}

case "${1:-help}" in
  status) show_status ;;
  console)
    require_command python3
    exec python3 "$SCRIPT_DIR/release_console.py" "${@:2}"
    ;;
  publish) publish_tag "${2:-}" ;;
  help|-h|--help) show_help ;;
  *) show_help >&2; die "Unknown command: $1" ;;
esac
