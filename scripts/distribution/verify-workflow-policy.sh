#!/usr/bin/env bash
# Workflow supply-chain policy.
#
# 1. Every `uses:` reference must be pinned to a full 40-character commit SHA. A mutable tag can be
#    moved by whoever controls the action's repository, and these workflows hand Maven signing,
#    Central and Cloudflare credentials to the jobs that run them.
# 2. The publish job must name a protected GitHub Environment, so the secret-bearing job cannot run
#    without the approval configured against that environment.
#
# Local actions (`uses: ./...`) are exempt: they are this repository's own code, already covered by
# review.
set -euo pipefail
cd "$(dirname "$0")/../.."

fail=0
workflows=$(find .github/workflows -name '*.yml' -o -name '*.yaml' | sort)

for wf in $workflows; do
  while IFS= read -r line; do
    ref=$(printf '%s' "$line" | sed -E 's/.*uses:[[:space:]]*//; s/[[:space:]]*(#.*)?$//')
    case "$ref" in
      ./*) continue ;;
    esac
    if ! printf '%s' "$ref" | grep -qE '@[0-9a-f]{40}$'; then
      echo "UNPINNED: $wf -> $ref" >&2
      echo "  Pin it to a full commit SHA, keeping the version in a trailing comment:" >&2
      echo "    uses: owner/repo@<40-char-sha> # v1" >&2
      fail=1
    fi
  done < <(grep -nE '^[[:space:]]*-?[[:space:]]*uses:' "$wf" | sed 's/^[0-9]*://')
done

if ! grep -qE '^[[:space:]]+environment:[[:space:]]*\S' .github/workflows/release.yml; then
  echo "NO PROTECTED ENVIRONMENT: release.yml has no job declaring 'environment:'." >&2
  echo "  The publish job handles signing and Central credentials and must name one." >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "Workflow policy check FAILED." >&2
  exit 1
fi

echo "Workflow policy: every action pinned to a commit SHA; publish job is environment-gated."
