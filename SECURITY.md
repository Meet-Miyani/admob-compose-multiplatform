# Security Policy

## Supported versions

AdMob CMP publishes a single line of releases; only the **latest published
version** on [Maven Central](https://central.sonatype.com/artifact/dev.avinya.ads/admob-cmp)
is supported. There are no maintained older major versions.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for a security vulnerability.

Instead, use
[GitHub's private vulnerability reporting](https://github.com/Meet-Miyani/admob-compose-multiplatform/security/advisories/new)
for this repository, or email **miyanimeet02@gmail.com** with:

- A description of the vulnerability and its potential impact.
- Steps to reproduce, or a minimal reproduction project if possible.
- The `admob-cmp` version(s) affected.

This is a single-maintainer open-source project; there is no formal SLA, but
reports are reviewed as soon as practical and a fix or mitigation is
prioritized once confirmed. Credit is given in the release notes unless you
ask to remain anonymous.

## Scope

`admob-cmp` is a thin Kotlin Multiplatform wrapper around Google Mobile Ads
and User Messaging Platform. Vulnerabilities in the underlying Google SDKs
themselves should be reported to Google, not here — see the
[compatibility page](https://ads.avinya.dev/reference/compatibility/) for
the exact GMA/UMP versions each release binds.
