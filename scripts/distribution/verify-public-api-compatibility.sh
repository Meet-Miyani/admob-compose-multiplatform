#!/usr/bin/env bash
# Prove the public klib ABI at HEAD is ADDITIVE relative to the last published
# release tag.
#
# Why this exists alongside `checkKotlinAbi`:
#   `checkKotlinAbi` proves the committed `api/*.klib.api` dump matches the
#   source at HEAD. It cannot tell you whether that dump itself LOST a
#   declaration, because `updateKotlinAbi` rewrites it unconditionally. A
#   maintainer who deletes a public function and runs `updateKotlinAbi` gets a
#   green `checkKotlinAbi` and a silent binary-compatibility break. AGENTS.md
#   already warns that nothing in CI catches a stale dump; nothing catches a
#   REGENERATED one either. This does.
#
# How it compares:
#   Every declaration line in a klib dump ends with a trailing `// <signature>`
#   comment, and those signatures are unique within a dump (verified: 1356/1356
#   unique in admob-cmp-core at 2.1.0). We key on the signature, so reordering
#   the dump is not a diff, while a removal is. The declaration TEXT is then
#   compared per signature, so a return-type or visibility change on a
#   surviving signature is caught too.
#
# Usage:
#   verify-public-api-compatibility.sh [TAG]
#   verify-public-api-compatibility.sh --compare OLD_FILE NEW_FILE
#   verify-public-api-compatibility.sh --allow-breaking [TAG]
#
#   TAG              release tag to compare against. Default: newest semver tag.
#   --compare        compare two dump files directly; used by the self-test.
#   --allow-breaking report breaking changes but exit 0. ONLY for a deliberate
#                    major-version release with a written migration plan. A
#                    major bump does NOT permit this automatically — an
#                    accidental break must not ride along with an intentional
#                    one.
#
# Exit codes:
#   0  every previously published declaration still exists unchanged
#   1  a declaration was removed or its signature/type/visibility changed
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT}" || exit 1

MODULES="admob-cmp-core admob-cmp-compose"
ALLOW_BREAKING=0
COMPARE_MODE=0
COMPARE_OLD=""
COMPARE_NEW=""
TAG=""
FAIL=0

fail() { echo "  FAIL: $*"; FAIL=1; }
ok()   { echo "  ok:   $*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --allow-breaking) ALLOW_BREAKING=1; shift ;;
    --compare)        COMPARE_MODE=1; COMPARE_OLD="${2:-}"; COMPARE_NEW="${3:-}"; shift 3 ;;
    -h|--help)        sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)                TAG="$1"; shift ;;
  esac
done

# Emit "<signature>\t<declaration>" for every declaration line, sorted by
# signature under the C collation so `join` and `comm` agree downstream.
extract() {
  grep ' // ' "$1" \
    | grep -v '^[[:space:]]*//' \
    | while IFS= read -r line; do
        sig="${line##* // }"
        decl="${line% // *}"
        decl="$(printf '%s' "${decl}" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        printf '%s\t%s\n' "${sig}" "${decl}"
      done \
    | LC_ALL=C sort -t "$(printf '\t')" -k1,1
}

compare_dumps() { # compare_dumps <label> <old-file> <new-file>
  local label="$1" old="$2" new="$3" tmp
  tmp="$(mktemp -d)"

  extract "${old}" > "${tmp}/old.tsv"
  extract "${new}" > "${tmp}/new.tsv"
  LC_ALL=C cut -f1 "${tmp}/old.tsv" > "${tmp}/old.sig"
  LC_ALL=C cut -f1 "${tmp}/new.tsv" > "${tmp}/new.sig"

  local removed added changed
  removed="$(LC_ALL=C comm -23 "${tmp}/old.sig" "${tmp}/new.sig")"
  added="$(LC_ALL=C comm -13 "${tmp}/old.sig" "${tmp}/new.sig")"
  changed="$(LC_ALL=C join -t "$(printf '\t')" -j 1 -o 0,1.2,2.2 \
               "${tmp}/old.tsv" "${tmp}/new.tsv" \
             | awk -F'\t' '$2 != $3 { print }')"

  local n_removed n_added n_changed
  n_removed="$( [ -z "${removed}" ] && echo 0 || printf '%s\n' "${removed}" | wc -l | tr -d ' ')"
  n_added="$(   [ -z "${added}"   ] && echo 0 || printf '%s\n' "${added}"   | wc -l | tr -d ' ')"
  n_changed="$( [ -z "${changed}" ] && echo 0 || printf '%s\n' "${changed}" | wc -l | tr -d ' ')"

  if [ "${n_removed}" -eq 0 ] && [ "${n_changed}" -eq 0 ]; then
    ok "${label}: additive only (${n_added} added, 0 removed, 0 changed)"
    rm -rf "${tmp}"
    return 0
  fi

  fail "${label}: NOT additive (${n_removed} removed, ${n_changed} changed, ${n_added} added)"

  if [ "${n_removed}" -gt 0 ]; then
    echo "        --- removed declarations (present in the release, gone at HEAD) ---"
    printf '%s\n' "${removed}" | while IFS= read -r sig; do
      [ -z "${sig}" ] && continue
      LC_ALL=C awk -F'\t' -v s="${sig}" '$1 == s { print "        - " $2 }' "${tmp}/old.tsv"
    done
  fi

  if [ "${n_changed}" -gt 0 ]; then
    echo "        --- changed declarations (same signature, different type/visibility) ---"
    printf '%s\n' "${changed}" | while IFS=$(printf '\t') read -r _sig oldd newd; do
      [ -z "${oldd}" ] && continue
      echo "        - was: ${oldd}"
      echo "          now: ${newd}"
    done
  fi

  rm -rf "${tmp}"
  return 1
}

if [ "${COMPARE_MODE}" -eq 1 ]; then
  echo "== Direct dump comparison =="
  compare_dumps "$(basename "${COMPARE_NEW}")" "${COMPARE_OLD}" "${COMPARE_NEW}"
else
  if [ -z "${TAG}" ]; then
    TAG="$(git tag --sort=-v:refname | head -1)"
  fi
  if [ -z "${TAG}" ]; then
    echo "No release tag found; nothing to compare against." >&2
    exit 1
  fi
  if ! git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "Tag '${TAG}' does not exist." >&2
    exit 1
  fi

  HEAD_VERSION="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"
  echo "== Public ABI additivity: ${TAG} -> HEAD (VERSION_NAME=${HEAD_VERSION}) =="

  if [ "${TAG%%.*}" != "${HEAD_VERSION%%.*}" ]; then
    echo "  note: major version differs (${TAG%%.*} -> ${HEAD_VERSION%%.*})."
    echo "        Breaking changes still FAIL unless --allow-breaking is passed"
    echo "        alongside a written migration plan. A deliberate major bump"
    echo "        must not let an accidental break ride along with it."
  fi

  for module in ${MODULES}; do
    dump="${module}/api/${module}.klib.api"
    if [ ! -f "${dump}" ]; then
      fail "${module}: ${dump} is missing at HEAD"
      continue
    fi
    old="$(mktemp)"
    if ! git show "${TAG}:${dump}" > "${old}" 2>/dev/null; then
      ok "${module}: no dump at ${TAG} (module is newer than that release); skipped"
      rm -f "${old}"
      continue
    fi
    compare_dumps "${module}" "${old}" "${dump}"
    rm -f "${old}"
  done
fi

echo
if [ "${FAIL}" -eq 0 ]; then
  echo "PUBLIC API COMPATIBILITY: PASS"
  exit 0
fi
if [ "${ALLOW_BREAKING}" -eq 1 ]; then
  echo "PUBLIC API COMPATIBILITY: BREAKING (allowed by --allow-breaking)"
  exit 0
fi
echo "PUBLIC API COMPATIBILITY: FAIL"
echo
echo "A declaration that shipped in ${TAG:-the compared dump} is gone or changed at HEAD."
echo "Do NOT run updateKotlinAbi to make this pass — that is exactly the mistake"
echo "this gate exists to catch. Either restore the declaration, or, for a"
echo "deliberate major release, write the migration plan and re-run with"
echo "--allow-breaking."
exit 1
