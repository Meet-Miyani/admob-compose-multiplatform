#!/usr/bin/env bash
# Builds a review/source bundle from TRACKED FILES ONLY, and refuses to produce one containing
# credentials, browser state, or build detritus.
#
# This exists because a hand-made archive of the working tree (Finder "Compress", `zip -r`) sweeps
# up everything git deliberately ignores. One such bundle contained local.properties with a
# plaintext Maven Central token, a headless Chrome profile with history and session state, and
# node_modules. `git archive` cannot do that: it serialises a commit, so an ignored file is not
# eligible in the first place. The scan afterwards is defence in depth, in case something
# credential-shaped ever gets committed.
#
# Usage: scripts/distribution/make-source-bundle.sh [ref] [output.zip]
set -euo pipefail
cd "$(dirname "$0")/../.."

REF="${1:-HEAD}"
OUT="${2:-build/source-bundle-$(git rev-parse --short "$REF").zip}"

# Path classes that must never appear in a distributed bundle.
FORBIDDEN_PATHS='(^|/)(local\.properties|\.env(\..*)?|scratch/|node_modules/|\.gradle/|\.kotlin/|__MACOSX/|\.DS_Store|build/|\.idea/|.*\.jks|.*\.keystore|.*\.p12|id_rsa.*)'
# Browser profile databases, which carry history/session/local-storage state.
FORBIDDEN_NAMES='(^|/)(Cookies|History|Login Data|Web Data|Preferences|first_party_sets\.db)$'

mkdir -p "$(dirname "$OUT")"

echo "==> Building bundle from tracked files at $REF"
git archive --format=zip --output "$OUT" "$REF"

echo "==> Verifying bundle contents"
entries=$(unzip -Z1 "$OUT")

violations=$(printf '%s\n' "$entries" | grep -E "$FORBIDDEN_PATHS" || true)
name_violations=$(printf '%s\n' "$entries" | grep -E "$FORBIDDEN_NAMES" || true)
violations=$(printf '%s\n%s\n' "$violations" "$name_violations" | grep -v '^$' | sort -u || true)

if [ -n "$violations" ]; then
  echo "REFUSING TO SHIP: prohibited paths present in $OUT" >&2
  # Path classes only -- never the file contents, so a secret is not echoed into a log.
  printf '%s\n' "$violations" | sed 's/^/  /' >&2
  rm -f "$OUT"
  exit 1
fi

# Last-resort content scan for credential-shaped material.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q "$OUT" -d "$tmp"
if grep -rIlE '(BEGIN [A-Z ]*PRIVATE KEY|aws_secret_access_key|-----BEGIN OPENSSH)' "$tmp" >/dev/null 2>&1; then
  echo "REFUSING TO SHIP: credential-shaped content found in $OUT" >&2
  grep -rIlE '(BEGIN [A-Z ]*PRIVATE KEY|aws_secret_access_key|-----BEGIN OPENSSH)' "$tmp" \
    | sed "s|$tmp/|  |" >&2
  rm -f "$OUT"
  exit 1
fi

echo "Bundle OK: $OUT ($(printf '%s\n' "$entries" | wc -l | tr -d ' ') entries)"
