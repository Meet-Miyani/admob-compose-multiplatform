#!/usr/bin/env bash
# Produces the provenance assets that accompany a GitHub release: a checksum manifest and an SPDX
# SBOM describing exactly which artifacts were published, at which version, from which commit.
#
# Maven Central signatures prove the artifacts came from this project's key. They say nothing about
# WHICH source commit produced them, or what a consumer should expect to find in the release. These
# two files close that gap without adding a build dependency: the SBOM is assembled from the
# publication itself, so it cannot drift from what was actually built.
#
# Usage: scripts/distribution/generate-release-provenance.sh <output-dir> [commit-sha]
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT_DIR="${1:?usage: generate-release-provenance.sh <output-dir> [commit-sha]}"
COMMIT="${2:-$(git rev-parse HEAD)}"
VERSION="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"
[ -n "$VERSION" ] || { echo "VERSION_NAME missing from gradle.properties" >&2; exit 1; }

mkdir -p "$OUT_DIR"
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

echo "==> Publishing $VERSION into a throwaway repository to enumerate artifacts"
# Unsigned, and into a redirected Maven local so this describes the publication rather than
# performing one. maven.repo.local keeps it out of the developer's real ~/.m2.
#
# Two invocations, mirroring `admob-cmp/scripts/publish-maven-central.sh`: the Gradle plugin is a
# separate included build, so a single root publish enumerates the library alone. Describing half a
# release in a manifest that claims to describe all of it is worse than not shipping one, because a
# consumer verifying the plugin's artifacts would find them simply absent.
./gradlew publishToMavenLocal \
  -PsignAllPublications=false \
  -Dmaven.repo.local="$STAGING" \
  --no-configuration-cache >/dev/null

./gradlew -p admob-cmp-gradle-plugin publishToMavenLocal \
  -PsignAllPublications=false \
  -Dmaven.repo.local="$STAGING" \
  --no-configuration-cache >/dev/null

# Fail loudly rather than silently describing an empty release if the redirect is ever ignored.
if [ -z "$(find "$STAGING" -name '*.pom' -o -name '*.module' 2>/dev/null | head -1)" ]; then
  echo "publishToMavenLocal produced nothing under $STAGING — did maven.repo.local get ignored?" >&2
  exit 1
fi

# `maven-metadata-local.xml` is bookkeeping the local repository writes for itself; it carries a
# build timestamp and is never uploaded to Central. Including it made the manifest describe files
# that do not exist in the release, and made it non-deterministic: two runs of the same commit
# produced different checksums for those entries alone. Every real artifact -- jar, klib, pom,
# module -- is byte-identical between runs, so excluding this makes the whole manifest reproducible.
artifacts=$(find "$STAGING" -type f \
  ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' ! -name '*.md5' ! -name '*.asc' \
  ! -name 'maven-metadata-local.xml' \
  | sort)

[ -n "$artifacts" ] || { echo "No artifacts were produced — cannot generate provenance." >&2; exit 1; }

echo "==> Writing checksums.txt"
: > "$OUT_DIR/checksums.txt"
while IFS= read -r file; do
  printf '%s  %s\n' "$(shasum -a 256 "$file" | cut -d' ' -f1)" "${file#"$STAGING"/}" >> "$OUT_DIR/checksums.txt"
done <<< "$artifacts"

echo "==> Writing sbom.spdx.json"
STAGING="$STAGING" VERSION="$VERSION" COMMIT="$COMMIT" OUT_DIR="$OUT_DIR" python3 - <<'PY'
import hashlib, json, os, pathlib, datetime

staging = pathlib.Path(os.environ["STAGING"])
version, commit, out_dir = os.environ["VERSION"], os.environ["COMMIT"], os.environ["OUT_DIR"]
skip = {".sha1", ".sha256", ".sha512", ".md5", ".asc"}

packages, relationships = [], []
for path in sorted(p for p in staging.rglob("*") if p.is_file() and p.suffix not in skip):
    rel = path.relative_to(staging)
    # Maven layout: group/as/path/artifact/version/file
    parts = rel.parts
    artifact_id = parts[-3] if len(parts) >= 3 else path.stem
    group_id = ".".join(parts[:-3]) if len(parts) >= 3 else ""
    spdx_id = "SPDXRef-" + str(rel).replace("/", "-").replace(".", "-").replace("_", "-")
    packages.append({
        "SPDXID": spdx_id,
        "name": f"{group_id}:{artifact_id}" if group_id else artifact_id,
        "versionInfo": version,
        "downloadLocation": "https://repo1.maven.org/maven2/" + str(rel.parent),
        "filesAnalyzed": False,
        "licenseConcluded": "Apache-2.0",
        "licenseDeclared": "Apache-2.0",
        "supplier": "Organization: Avinya",
        "externalRefs": ([{
            "referenceCategory": "PACKAGE-MANAGER",
            "referenceType": "purl",
            "referenceLocator": f"pkg:maven/{group_id}/{artifact_id}@{version}",
        }] if group_id else []),
        "checksums": [{
            "algorithm": "SHA256",
            "checksumValue": hashlib.sha256(path.read_bytes()).hexdigest(),
        }],
        "comment": f"file={rel.name}",
    })
    relationships.append({
        "spdxElementId": "SPDXRef-DOCUMENT",
        "relatedSpdxElement": spdx_id,
        "relationshipType": "DESCRIBES",
    })

doc = {
    "spdxVersion": "SPDX-2.3",
    "dataLicense": "CC0-1.0",
    "SPDXID": "SPDXRef-DOCUMENT",
    "name": f"admob-cmp-{version}",
    "documentNamespace": f"https://ads.avinya.dev/spdx/admob-cmp/{version}/{commit}",
    "creationInfo": {
        "created": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "creators": ["Tool: admob-cmp-release-provenance", "Organization: Avinya"],
    },
    "comment": f"Built from commit {commit} of https://github.com/Meet-Miyani/admob-compose-multiplatform",
    "packages": packages,
    "relationships": relationships,
}
pathlib.Path(out_dir, "sbom.spdx.json").write_text(json.dumps(doc, indent=2) + "\n")
print(f"    {len(packages)} artifacts described")
PY

echo "Provenance assets written to $OUT_DIR:"
ls -1 "$OUT_DIR"
