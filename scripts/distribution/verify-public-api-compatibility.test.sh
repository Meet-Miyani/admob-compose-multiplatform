#!/usr/bin/env bash
# Self-test for verify-public-api-compatibility.sh.
#
# Builds three synthetic dumps by mutating a copy of the real
# admob-cmp-core dump, then asserts the guard's verdict on each. Uses the real
# dump rather than a hand-written one so the fixtures exercise the actual
# klib format, including nested indentation and generic signatures.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="${ROOT}/scripts/distribution/verify-public-api-compatibility.sh"
BASE="${ROOT}/admob-cmp-core/api/admob-cmp-core.klib.api"
TMP="$(mktemp -d)"
FAIL=0

trap 'rm -rf "${TMP}"' EXIT

fail() { echo "  FAIL: $*"; FAIL=1; }
ok()   { echo "  ok:   $*"; }

expect() { # expect <expected-exit> <label> <old> <new>
  local want="$1" label="$2" old="$3" new="$4" got
  "${GUARD}" --compare "${old}" "${new}" >"${TMP}/out.txt" 2>&1
  got=$?
  if [ "${got}" -eq "${want}" ]; then
    ok "${label} (exit ${got})"
  else
    fail "${label}: expected exit ${want}, got ${got}"
    sed 's/^/        /' "${TMP}/out.txt"
  fi
}

echo "== Fixture: identical dumps =="
cp "${BASE}" "${TMP}/old.api"
cp "${BASE}" "${TMP}/same.api"
expect 0 "identical dumps pass" "${TMP}/old.api" "${TMP}/same.api"

echo "== Fixture: additive change =="
cp "${BASE}" "${TMP}/added.api"
printf '%s\n' \
  'final fun dev.avinya.ads/syntheticAddedForTest(): kotlin/Unit // dev.avinya.ads/syntheticAddedForTest|syntheticAddedForTest(){}[0]' \
  >> "${TMP}/added.api"
expect 0 "an added declaration passes" "${TMP}/old.api" "${TMP}/added.api"

echo "== Fixture: removed declaration =="
# Drop the last declaration line that carries a signature comment.
victim="$(grep -n ' // ' "${BASE}" | grep -v ':[[:space:]]*//' | tail -1 | cut -d: -f1)"
sed "${victim}d" "${BASE}" > "${TMP}/removed.api"
expect 1 "a removed declaration fails" "${TMP}/old.api" "${TMP}/removed.api"

echo "== Fixture: signature/type mutation =="
# Keep the signature comment, change the declared return type before it.
sed "${victim}s|): kotlin/Unit //|): kotlin/String //|; ${victim}s|kotlin/Boolean //|kotlin/String //|" \
  "${BASE}" > "${TMP}/mutated.api"
if cmp -s "${BASE}" "${TMP}/mutated.api"; then
  # The chosen victim had neither return type; force a visibility change instead.
  sed "${victim}s|^\([[:space:]]*\)final |\1open |" "${BASE}" > "${TMP}/mutated.api"
fi
if cmp -s "${BASE}" "${TMP}/mutated.api"; then
  fail "could not construct a mutation fixture from line ${victim}"
else
  expect 1 "a changed declaration fails" "${TMP}/old.api" "${TMP}/mutated.api"
fi

echo "== Fixture: --allow-breaking overrides a removal =="
"${GUARD}" --allow-breaking --compare "${TMP}/old.api" "${TMP}/removed.api" >"${TMP}/out.txt" 2>&1
if [ $? -eq 0 ]; then ok "--allow-breaking turns a removal into exit 0"; else
  fail "--allow-breaking did not override the removal"
  sed 's/^/        /' "${TMP}/out.txt"
fi

echo
if [ "${FAIL}" -eq 0 ]; then
  echo "ABI GUARD SELF-TEST: PASS"
  exit 0
fi
echo "ABI GUARD SELF-TEST: FAIL"
exit 1
