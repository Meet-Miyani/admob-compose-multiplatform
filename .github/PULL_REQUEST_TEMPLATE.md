## What does this change?

<!-- One or two sentences. Link an issue if there is one. -->

## Checklist

- [ ] Added or updated a test that fails before this change and passes after it
- [ ] Ran `./gradlew :admob-cmp-core:testAndroidHostTest` and `:admob-cmp-core:iosSimulatorArm64Test`
- [ ] If this touches the public API of `admob-cmp-core` or `admob-cmp-compose`: ran
      `updateKotlinAbi` and committed the regenerated `api/*.klib.api` dump
- [ ] Updated docs in `docs-site/src/content/docs/` in this PR, if user-facing behavior changed
- [ ] Updated [the changelog](https://ads.avinya.dev/reference/changelog/), if behavior a consumer can observe changed
- [ ] Ran `./scripts/release-readiness.sh` locally and got `READINESS: PASS` (or explained why it couldn't be run)

## Notes for the reviewer

<!-- Anything skipped, anything you're unsure about, anything that needs a second look. -->
