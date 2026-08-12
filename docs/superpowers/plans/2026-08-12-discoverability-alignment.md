# AdMob CMP Discoverability Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Complete the checkboxes in order and stop at every owner-approval gate.

**Goal:** Make AdMob CMP a consistent, indexable software entity across GitHub, Maven Central, `ads.avinya.dev`, search engines, and relevant Kotlin Multiplatform directories without changing the established brand or chasing unsupported keyword-volume claims.

**Architecture:** Keep one brand (`AdMob CMP`), one repository identity (`admob-compose-multiplatform`), one package identity (`dev.avinya.ads:admob-cmp`), and one documentation host (`ads.avinya.dev`). Assign a distinct search intent to each useful page, keep generated Dokka pages accessible but non-indexable, and treat conventional crawlability and source reputation as the foundation for both search-engine and AI-answer visibility.

**Tech Stack:** Kotlin Multiplatform, Gradle/Maven Publish, Astro, Starlight, Vitest, Node.js, Dokka, Cloudflare Pages, GitHub, Google Search Console, Bing Webmaster Tools.

## Global constraints

- Start from the current dirty worktree. Preserve all existing user-owned and remediation changes; do not reset, discard, or overwrite them.
- Do not rename the repository, brand, Maven coordinate, or documentation host.
- Do not change the approved top-level keyword architecture unless new first-party query data contradicts it.
- Do not add thin keyword landing pages, doorway pages, manufactured backlinks, or generic community promotion.
- Do not claim that keyword suggestions prove search volume, traffic, ranking difficulty, or ranking guarantees.
- Do not bump `VERSION_NAME` for this SEO work. Updated POM metadata ships with the next deliberate release because Maven Central coordinates and published versions are immutable.
- Do not add SDK verification jobs to `.github/workflows/release.yml`.
- Do not create tags or releases manually. The workflow owns future tags and releases; only the existing `2.0.0` release metadata may be edited in place.
- Do not commit, push, open a pull request, or mutate authenticated external services without explicit owner authorization for that action.
- Before a pull request, run the complete `./scripts/release-readiness.sh` with no `--skip-docs`, report every section, and obtain explicit owner confirmation.

---

## 1. Locked decisions and keyword map

These are the approved decisions this plan implements.

| Surface | Brand or primary intent | Approved wording or target |
|---|---|---|
| Product brand | Entity name | `AdMob CMP` |
| GitHub repository | Exact product-category tokens | `admob-compose-multiplatform` |
| Maven artifact | Stable dependency identity | `dev.avinya.ads:admob-cmp` |
| Docs host | Owned, non-trademark hostname | `ads.avinya.dev` |
| GitHub + homepage | Primary discovery intent | `compose multiplatform admob`, `admob compose multiplatform` |
| What-is guide | Secondary category intent | `kotlin multiplatform admob`, `google admob kmp` |
| Quickstart | Task intent | `compose multiplatform admob integration` |
| Installation | Dependency intent | `KMP AdMob dependency`, `dev.avinya.ads:admob-cmp` |
| Android guide | Platform intent | `AdMob Kotlin Multiplatform Android` |
| iOS guide | Platform intent | `AdMob Kotlin Multiplatform iOS` |
| Format/privacy/troubleshooting guides | Long-tail problem intent | One real implementation problem per page |

### Rationale

- `AdMob CMP` remains the compact brand, but it is not relied upon as the only acquisition phrase because “CMP” can also mean Consent Management Platform in the advertising ecosystem.
- `Compose Multiplatform AdMob` is the primary acquisition phrasing because it precisely matches the audience and product category.
- `Kotlin Multiplatform AdMob` is a complementary category phrase that covers the non-Compose core API without making the brand or homepage title unfocused.
- The existing repository slug includes all three decisive tokens: `admob`, `compose`, and `multiplatform`.
- Keyword wording should appear where it naturally explains the product. Repetition is not an acceptance criterion.

---

## 2. Scope

### In scope

- GitHub About metadata, topics, community features, license detection, and the existing `2.0.0` release description.
- Root README product definition, installation/version accuracy, compatibility wording, and release-process accuracy.
- Root and Gradle-plugin POM metadata for the next release.
- Docs titles, descriptions, page intent, supported-format consistency, policy wording, roadmap accuracy, API discovery, and internal links.
- Authored and deployed crawler policy, including explicit separation of search/citation crawlers from training-oriented crawlers.
- Regression tests, a manual production SEO audit command, full local release readiness, and post-deployment checks.
- Search Console/Bing discovery, Klibs issue reporting, KMP Awesome submission, and links from owned Avinya surfaces.
- A measurement baseline for Google Search and major AI answer products.

### Out of scope

- SDK public API or ABI changes.
- A version bump or republishing `2.0.0`.
- Paid link acquisition, reciprocal-link schemes, mass directory submissions, or generic announcement spam.
- New comparison/alternative pages without Search Console evidence or genuine user need.
- Claims that the SDK guarantees GDPR, ATT, AdMob, or app-store policy compliance.
- A broad redesign of the documentation visual system.
- GitHub Actions verification jobs.

---

## 3. Current-state guardrails

Before editing, record the current state so existing remediation work is not lost.

**Files to inspect:**

- `README.md`
- `LICENSE`
- `gradle.properties`
- `admob-cmp-gradle-plugin/gradle.properties`
- `.github/workflows/release.yml`
- `docs-site/astro.config.mjs`
- `docs-site/functions/_middleware.js`
- `docs-site/public/robots.txt`
- `docs-site/scripts/audit-live-seo.mjs`
- `docs-site/scripts/audit-content.mjs`
- `docs-site/scripts/verify-build.mjs`
- `docs-site/src/content/docs/index.mdx`
- `docs-site/src/content/docs/start/what-is-admob-cmp.mdx`
- `docs-site/src/content/docs/reference/api.mdx`
- `docs-site/src/content/docs/project/ai-agents.mdx`
- `docs-site/src/content/docs/project/roadmap.mdx`
- `docs-site/test/discoverability.test.ts`
- `docs-site/test/live-seo-audit.test.ts`

### Task 3.1: Capture the baseline

- [ ] Run `git status --short`.
- [ ] Run `git diff --stat` and `git diff --check`.
- [ ] Review every existing diff in the files above before changing it.
- [ ] Confirm the working tree still contains the intended discoverability remediation and no unrelated SDK behavior changes.
- [ ] If an existing change conflicts with this plan, preserve it and report the conflict instead of replacing it silently.

---

## 4. Make the brand and keyword architecture regression-tested

The current titles are aligned. This task prevents later “SEO improvements” from repeatedly churning them.

**Files:**

- Modify: `docs-site/test/discoverability.test.ts`
- Verify: `README.md`
- Verify: `docs-site/src/content/docs/index.mdx`
- Verify: `docs-site/src/content/docs/start/what-is-admob-cmp.mdx`
- Verify: `docs-site/astro.config.mjs`

### Task 4.1: Add failing title and positioning tests

- [ ] Add a test that requires the README H1 to communicate all of:
  - `AdMob CMP`
  - `Compose Multiplatform`
  - `AdMob SDK`
  - Android and iOS support
- [ ] Add a test that requires the README opening paragraph to define the library as open source and identify the `commonMain` API.
- [ ] Add a test that requires the homepage frontmatter/title and hero to target `Compose Multiplatform AdMob SDK` without keyword repetition.
- [ ] Add a test that requires the what-is page to target `Kotlin Multiplatform AdMob SDK for Android and iOS`.
- [ ] Add a test that rejects using `AdMob CMP` alone as a page title without an explanatory product-category phrase.
- [ ] Run the focused test and confirm it fails only for genuinely missing contracts:

```bash
cd docs-site
npx vitest run test/discoverability.test.ts
```

### Task 4.2: Reconcile copy only where the tests expose a mismatch

- [ ] Keep the approved README H1:

```text
AdMob CMP — Compose Multiplatform AdMob SDK for Android and iOS
```

- [ ] Keep the homepage intent `Compose Multiplatform AdMob SDK`.
- [ ] Keep the introductory guide title:

```text
Kotlin Multiplatform AdMob SDK for Android and iOS
```

- [ ] Do not add exact-match phrases to unrelated headings.
- [ ] Confirm the same six formats are named consistently: banner, interstitial, rewarded, rewarded interstitial, app open, and native.
- [ ] Re-run the focused test until it passes.

### Task 4.3: Preserve human copy quality

- [ ] Read the rendered opening of the README, homepage, and what-is page as a developer would.
- [ ] Remove generic phrases such as “write once, monetize anywhere,” “solves these headaches,” or unsupported superlatives if any remain.
- [ ] Ensure each page explains a distinct user need instead of restating the homepage.
- [ ] Keep the Google trademark disclaimer visible in the README and docs footer.

**Acceptance:** The brand is consistent, the category is explicit, and each primary page owns a distinct intent without keyword stuffing.

---

## 5. Correct and enforce the crawler policy

The current authored `robots.txt` has a concrete contradiction: `anthropic-ai` appears in both the allowed and blocked groups. Resolve that before deployment.

**Files:**

- Modify: `docs-site/public/robots.txt`
- Modify: `docs-site/src/content/docs/project/ai-agents.mdx`
- Modify: `docs-site/test/discoverability.test.ts`
- Modify: `docs-site/scripts/audit-live-seo.mjs`
- Modify: `docs-site/test/live-seo-audit.test.ts`

### Task 5.1: Write a disjoint, explicit policy

- [ ] Keep top-level content signals equivalent to:

```text
Content-Signal: search=yes, ai-input=yes, ai-train=no
```

- [ ] Explicitly allow ordinary crawling plus these search/citation agents:
  - `Googlebot`
  - `Bingbot`
  - `OAI-SearchBot`
  - `ChatGPT-User`
  - `Claude-SearchBot`
  - `Claude-User`
  - `PerplexityBot`
  - `Perplexity-User`
- [ ] Explicitly block these training-oriented or extended-use agents:
  - `GPTBot`
  - `ClaudeBot`
  - `anthropic-ai`
  - `CCBot`
  - `Bytespider`
  - `Applebot-Extended`
  - `Google-Extended`
- [ ] Remove `anthropic-ai` from the allow list.
- [ ] Ensure every explicitly named bot appears exactly once.
- [ ] Keep pages, ordinary assets, `/sitemap-index.xml`, `/llms.txt`, and `/llms-full.txt` crawlable.
- [ ] Keep the sitemap declaration on the canonical HTTPS host.

### Task 5.2: Document the Google-Extended tradeoff accurately

- [ ] Explain in `ai-agents.mdx` that blocking `Google-Extended` does not block Google Search crawling or Google Search AI features.
- [ ] Explain that the same control also limits use of site content for grounding in Gemini Apps; therefore the selected no-training policy trades away some Gemini Apps grounding eligibility.
- [ ] Do not claim universal “AI crawler access.” Say search/citation access is allowed for the explicitly listed agents while training access is blocked.
- [ ] Keep `llms.txt` framed as an agent convenience, not a Google ranking mechanism.

### Task 5.3: Add policy invariants

- [ ] Add a unit test that parses all `User-agent` records and fails when an explicitly named agent appears more than once.
- [ ] Add a unit test that proves the allow and block sets are disjoint.
- [ ] Add assertions for each named agent’s intended allow/block status.
- [ ] Add an assertion that `Content-Signal` contains `search=yes`, `ai-input=yes`, and `ai-train=no`.
- [ ] Add an assertion that sitemap and LLM helper files remain crawlable.
- [ ] Run:

```bash
cd docs-site
npx vitest run test/discoverability.test.ts
```

### Task 5.4: Strengthen the deployed-site audit

- [ ] Make `audit-live-seo.mjs` fail if Cloudflare-managed text is prepended to the authored file.
- [ ] Make it fail if `OAI-SearchBot`, `Claude-SearchBot`, or `PerplexityBot` is blocked.
- [ ] Make it fail if `GPTBot`, `ClaudeBot`, `anthropic-ai`, or `Google-Extended` is allowed.
- [ ] Make it verify the canonical host, sitemap URL, homepage metadata, redirects, `/reference/api/` indexability, and `/api/**` `X-Robots-Tag` behavior.
- [ ] Keep the command manual and outside `.github/workflows/release.yml`.
- [ ] Test the script with mocked HTTP responses in `live-seo-audit.test.ts`.

**Acceptance:** No bot has contradictory instructions, search/citation access matches the written policy, and the Gemini tradeoff is disclosed rather than hidden.

---

## 6. Finalize the owned entity metadata

Most of these changes already exist in the dirty worktree. Review and complete them; do not rewrite working copy merely for stylistic variation.

**Files:**

- Modify if needed: `README.md`
- Modify if needed: `LICENSE`
- Modify if needed: `gradle.properties`
- Modify if needed: `admob-cmp-gradle-plugin/gradle.properties`
- Modify if needed: `.github/workflows/release.yml`
- Modify if needed: `docs-site/src/content/docs/index.mdx`
- Modify if needed: `docs-site/src/content/docs/start/what-is-admob-cmp.mdx`
- Modify if needed: `docs-site/src/content/docs/project/roadmap.mdx`
- Modify if needed: `docs-site/scripts/audit-content.mjs`
- Modify if needed: `docs-site/test/discoverability.test.ts`

### Task 6.1: Reconcile the README

- [ ] Keep the approved opening definition:

```text
AdMob CMP is an open-source Kotlin Multiplatform SDK for Google AdMob in Compose Multiplatform apps. Use one commonMain API for banner, interstitial, rewarded, rewarded interstitial, app-open, and native ads on Android and iOS.
```

- [ ] Ensure all installation and compatibility references agree with `VERSION_NAME=2.0.0`.
- [ ] Ensure the compatibility table includes `2.0.0` and the actual Android/iOS GMA lines used by the build.
- [ ] Replace any statement that ABI compatibility is “enforced in CI” with the accurate local release-readiness statement.
- [ ] Keep the Discussions link only after Discussions is enabled; until then, ensure it does not masquerade as a working support link.
- [ ] Ensure the disclaimer states that the project is not affiliated with or endorsed by Google.

### Task 6.2: Finish license recognition inputs

- [ ] Compare `LICENSE` with the canonical Apache License 2.0 text, including its appendix.
- [ ] Ensure the complete license is at the repository root and contains no custom preamble that prevents GitHub detection.
- [ ] Do not add a second competing license file.

### Task 6.3: Align POM metadata without releasing it

- [ ] Keep both `gradle.properties` files version-locked at `2.0.0`.
- [ ] Set the root POM description to:

```text
Open-source Kotlin Multiplatform and Compose Multiplatform SDK for Google AdMob on Android and iOS, published as dev.avinya.ads:admob-cmp.
```

- [ ] Verify the Gradle-plugin POM remains product-specific and does not falsely describe itself as the SDK facade.
- [ ] Verify POM `url`, `scm.url`, developer, license, and issue-management metadata resolve to canonical owned URLs.
- [ ] Record that Maven Central will show the new description only after the next deliberate release. Do not republish or bump for discoverability alone.

### Task 6.4: Align current and future release metadata

- [ ] Update the future release template in `.github/workflows/release.yml` so generated releases lead with:
  - a concise product definition;
  - Maven coordinate;
  - all six supported formats;
  - documentation link;
  - compatibility note;
  - the manual Central Portal warning;
  - generated change log.
- [ ] Do not create a new verification job, tag, or release.
- [ ] Prepare the same structure for the authenticated edit of the existing `2.0.0` release in Phase 10.

### Task 6.5: Remove stale or unsafe claims from docs

- [ ] Ensure the global description and homepage name all six ad formats.
- [ ] Replace any compliance guarantee with a precise statement of what the UMP and ATT APIs facilitate and what remains the publisher’s responsibility.
- [ ] Qualify lifecycle language so it does not imply controllers survive navigation-driven composition disposal.
- [ ] Correct `Medation` to `Mediation` wherever it appears.
- [ ] State that `2.0.0` has shipped.
- [ ] Describe Swift Package Manager work without assigning an uncommitted version or date.
- [ ] Preserve useful technical depth, canonical URLs, JSON-LD, and internal navigation.

**Acceptance:** README, POM inputs, docs, license, and release template describe the same product and version without overclaims.

---

## 7. Preserve the API-reference indexing split

Generated Dokka is valuable for users but its generic “All modules” page should not compete with the authored reference landing page.

**Files:**

- Verify/modify: `docs-site/src/content/docs/reference/api.mdx`
- Verify/modify: `docs-site/functions/_middleware.js`
- Verify/modify: `docs-site/astro.config.mjs`
- Verify/modify: `docs-site/scripts/verify-build.mjs`
- Verify/modify: `docs-site/test/host-guard.test.ts`
- Verify/modify: `docs-site/test/discoverability.test.ts`

### Task 7.1: Complete the authored `/reference/api/` page

- [ ] Give the page a specific title and description that identify AdMob CMP, Kotlin/Compose Multiplatform, and Android/iOS.
- [ ] Explain the facade, core, Compose, and Gradle-plugin modules briefly.
- [ ] Link to the generated module pages and relevant task guides.
- [ ] Make this page canonical, indexable, and present in the sitemap.

### Task 7.2: Keep generated `/api/**` accessible but non-indexable

- [ ] Apply `X-Robots-Tag: noindex, follow` to `/api/` and every nested generated Dokka response.
- [ ] Exclude `/api/**` from sitemap output.
- [ ] Do not block `/api/**` in `robots.txt`; crawlers must be able to see the noindex header.
- [ ] Verify direct links from the authored reference page still work.

### Task 7.3: Add build-time assertions

- [ ] Assert `/reference/api/` exists in built output and sitemap.
- [ ] Assert no generated `/api/**` URL appears in the sitemap.
- [ ] Assert middleware matches both `/api/` and nested paths.
- [ ] Assert the authored page does not inherit `noindex`.

**Acceptance:** Users and crawlers find a useful authored reference page, while generic Dokka pages remain available without competing in search.

---

## 8. Run local verification and obtain the owner gate

No external mutations or PR creation may happen before this phase is clean and reported.

### Task 8.1: Run focused docs checks

- [ ] Populate generated API docs if the local checkout does not already contain them:

```bash
./gradlew syncApiDocsToDocsSite
```

- [ ] Install exact docs dependencies if needed:

```bash
cd docs-site
npm ci
```

- [ ] Run in the required order:

```bash
npm run build
npm test
npm run verify
```

- [ ] Run any separately defined content/theme/overflow checks that are not already included by `verify`.
- [ ] Repair only failures caused by the discoverability changes; report unrelated failures without rewriting unrelated work.

### Task 8.2: Run the complete repository gate

- [ ] Return to the repository root.
- [ ] Run:

```bash
./scripts/release-readiness.sh
```

- [ ] Do not use `--skip-docs` because README, POM/version metadata, docs, and workflow inputs are in scope.
- [ ] Confirm every readiness section ran:
  1. version lockstep;
  2. Gradle plugin build;
  3. Android host tests, ABI, and publication metadata;
  4. Central task graph;
  5. iOS tests and klib ABI;
  6. Maven Local publish and shared-consumer round trip;
  7. Xcode consumer build;
  8. Dokka, Astro, visual checks, tests, and verification.
- [ ] Require the final line `READINESS: PASS`.
- [ ] Run `git diff --check` after any repair.

### Task 8.3: Stop for explicit owner confirmation

- [ ] Report each readiness section, anything initially broken, and the exact repair.
- [ ] Report remaining manual/external steps separately.
- [ ] Ask the owner whether to commit and/or open a PR.
- [ ] Do not commit, push, or open the PR until authorization is explicit.

**Acceptance:** The complete local gate passes and the owner has enough evidence to decide whether the changes may be published.

---

## 9. Publish the repository changes under existing protocol

This phase begins only after the owner explicitly authorizes the requested Git action.

### Task 9.1: Prepare a clean, scoped change

- [ ] Confirm the diff contains only approved discoverability/documentation changes.
- [ ] Confirm neither `docs-site/public/api/` nor `docs-site/dist/` is staged.
- [ ] Confirm `VERSION_NAME` remains `2.0.0` in both files.
- [ ] Commit only if authorized, using the repository’s normal commit convention.
- [ ] Push/open a PR only if separately authorized by the owner after the readiness report.

### Task 9.2: Review deployment behavior

- [ ] Confirm docs-path changes trigger the existing `api-reference` and `docs-site` workflow paths.
- [ ] Confirm the change does not trigger a release because the version is unchanged.
- [ ] Confirm no hand-created tag or release exists.
- [ ] Wait for owner-controlled merge before production verification.

---

## 10. Align authenticated GitHub metadata

These are external state changes. Preview the exact mutations and obtain authorization immediately before applying them.

### Task 10.1: Update GitHub About and repository features

- [ ] Set the About description exactly to:

```text
AdMob CMP — open-source Kotlin Multiplatform and Compose Multiplatform SDK for Google AdMob on Android and iOS. Maven: dev.avinya.ads:admob-cmp.
```

- [ ] Keep the homepage `https://ads.avinya.dev`.
- [ ] Retain relevant existing topics and add:
  - `google-mobile-ads`
  - `admob-sdk`
- [ ] Keep topic wording focused; do not add dozens of marginal synonyms.
- [ ] Disable the empty Wiki.
- [ ] Enable GitHub Discussions.
- [ ] Configure `Announcements`, `Q&A`, and `Show and tell` categories if GitHub does not provide suitable defaults.
- [ ] Verify the README Discussions link now resolves.

### Task 10.2: Verify license recognition after merge

- [ ] Confirm the repository API and GitHub UI report `Apache-2.0`, not `Other` or `NOASSERTION`.
- [ ] If recognition is delayed, wait for GitHub’s license scan and recheck before altering canonical license text.
- [ ] If it still fails, compare the merged file byte-for-byte with the canonical Apache-2.0 text and diagnose before editing.

### Task 10.3: Edit the existing `2.0.0` release

- [ ] Change the release title to:

```text
AdMob CMP 2.0.0 — Compose Multiplatform AdMob SDK
```

- [ ] Prepend a concise product definition, `dev.avinya.ads:admob-cmp`, all six formats, the docs URL, and compatibility notes.
- [ ] Preserve the manual Maven Central release warning and generated change log.
- [ ] Do not create a replacement release or tag.

### Task 10.4: Read back the exact public state

- [ ] Verify repository name, description, homepage, topics, Wiki state, Discussions state, and license through the public GitHub API/UI.
- [ ] Verify the release title/body through the public release page/API.
- [ ] Record the URLs and response values in the completion report.

**Acceptance:** GitHub presents one complete, searchable entity with a recognized license, accurate current release, and working support surface.

---

## 11. Deploy and normalize the Cloudflare crawler response

Order matters: deploy the corrected authored policy first, then disable Cloudflare’s managed prepend. This avoids temporarily exposing training crawlers to the older authored policy.

### Task 11.1: Confirm the corrected site is deployed

- [ ] Wait for the merged docs deployment to complete successfully.
- [ ] Confirm the new authored `robots.txt` is present behind Cloudflare even if the managed block is still prepended.
- [ ] Confirm `/reference/api/` is live and `/api/` returns `X-Robots-Tag: noindex, follow`.

### Task 11.2: Disable Cloudflare managed `robots.txt`

- [ ] Obtain explicit approval for the Cloudflare setting mutation.
- [ ] Disable the managed robots feature for `ads.avinya.dev` so Cloudflare no longer prepends contradictory directives.
- [ ] Do not change bot/security settings unrelated to the managed robots feature.

### Task 11.3: Run the production audit

- [ ] Run:

```bash
cd docs-site
npm run audit:live-seo -- https://ads.avinya.dev
```

- [ ] Independently inspect the response headers and body for:
  - canonical HTTPS host;
  - no Cloudflare-managed preamble;
  - one disjoint authored policy;
  - valid sitemap;
  - indexable useful pages;
  - non-indexable generated API pages;
  - expected redirects.
- [ ] If CDN caching preserves the old file, purge only the affected URL/cache scope and rerun the audit.

**Acceptance:** Production serves only the repository-authored policy and the live audit passes without contradictory bot rules.

---

## 12. Submit the owned pages for discovery

Indexing requests accelerate discovery but do not guarantee indexing or ranking.

### Task 12.1: Google Search Console

- [ ] Verify the `ads.avinya.dev` property and sitemap are active.
- [ ] Submit or resubmit `https://ads.avinya.dev/sitemap-index.xml`.
- [ ] Inspect and request indexing for:
  - `https://ads.avinya.dev/`
  - `https://ads.avinya.dev/start/what-is-admob-cmp/`
  - the canonical quickstart URL;
  - the canonical installation URL;
  - `https://ads.avinya.dev/reference/api/`
- [ ] Record whether each URL is discovered, crawled, indexed, or excluded and the reported reason.
- [ ] Do not request indexing for `/api/**`.

### Task 12.2: Bing Webmaster Tools

- [ ] Verify/import the site and submit the same sitemap.
- [ ] Inspect the homepage and the four priority documentation pages.
- [ ] Use IndexNow only if the existing deployment stack can support it cleanly; do not introduce a new service merely for this task.

### Task 12.3: Manual search-result sanity check

- [ ] Search in a logged-out/private context for:
  - `admob-compose-multiplatform`
  - `"AdMob CMP" "Compose Multiplatform"`
  - `site:github.com/Meet-Miyani/admob-compose-multiplatform`
  - `site:ads.avinya.dev AdMob CMP`
- [ ] Record observations as a dated baseline, not as proof of universal rank position.

**Acceptance:** Search engines know the canonical sitemap and the five priority pages have explicit inspection records.

---

## 13. Repair ecosystem discovery paths

Do this only after the canonical GitHub, Maven, and docs metadata is live so external directories ingest stable data.

### Task 13.1: Report the broken Klibs listing

- [ ] Capture the current application error and exact listing URL.
- [ ] Open a focused issue through the Klibs issue tracker with:
  - Maven coordinate `dev.avinya.ads:admob-cmp`;
  - canonical GitHub URL;
  - canonical Maven Central URL;
  - canonical documentation URL;
  - evidence that it is open source and Kotlin Multiplatform;
  - the observed error and reproduction date.
- [ ] Mention that corrected POM metadata will appear with the next deliberate release if the live `2.0.0` POM still has stale metadata.
- [ ] Do not ask for artificial ranking or special treatment; ask only for listing repair.

### Task 13.2: Submit to KMP Awesome

- [ ] Re-read the current contribution instructions.
- [ ] Add one concise entry in the appropriate ads/monetization category.
- [ ] Use the brand plus explanatory category wording, canonical GitHub URL, and a factual six-format summary.
- [ ] Follow the project’s alphabetical/formatting conventions exactly.
- [ ] Open the external PR only after owner approval.

### Task 13.3: Strengthen owned links

- [ ] Verify the Avinya open-source page uses the renamed repository URL.
- [ ] Add or update a factual card/link to the docs and GitHub repository in the Avinya site’s own repository.
- [ ] Ensure any `pages.dev` preview host redirects or canonicals to `avinya.dev`; do not send authority to a preview hostname.
- [ ] Treat this as a separate repository change with its own review and authorization.

**Acceptance:** The project appears on relevant, authentic ecosystem surfaces without manufactured backlink activity.

---

## 14. Measurement and review cadence

The purpose of measurement is to learn which wording and pages earn impressions, not to promise a timetable.

### Task 14.1: Establish the baseline

- [ ] Record the deployment date, GitHub public-state snapshot, sitemap submission date, indexed-page count, and exact-name search observation.
- [ ] In Search Console, track impressions, clicks, average position, pages, and countries for the approved query families.
- [ ] Separate branded queries (`AdMob CMP`, repository slug) from category queries (`Compose Multiplatform AdMob`, `Kotlin Multiplatform AdMob`).
- [ ] Record the top landing page for each query family to detect cannibalization.

### Task 14.2: Review at 7–14 days

- [ ] Check whether the five priority URLs are known, crawled, or indexed.
- [ ] Investigate exclusions using Search Console evidence before changing titles or adding pages.
- [ ] Re-run the production SEO audit.
- [ ] Do not rewrite copy merely because an exact-name result has not appeared yet.

### Task 14.3: Review at 2–6 weeks

- [ ] Compare exact-name and category-query impressions with the baseline.
- [ ] Identify genuine long-tail queries that earned impressions.
- [ ] Improve existing pages for those intents before proposing new landing pages.
- [ ] Consider a neutral comparison page only if users/search data demonstrate real demand and the content can be technically substantive.

### Task 14.4: Monthly AI-answer audit

- [ ] Use a fixed set of ten representative integration questions across ChatGPT, Perplexity, Claude, and Gemini.
- [ ] Record whether AdMob CMP is mentioned, described accurately, and cited to GitHub/docs/Maven.
- [ ] Distinguish missing citation from incorrect product information.
- [ ] Interpret Gemini results in light of the explicit `Google-Extended` no-training/limited-grounding choice.
- [ ] Correct source content when answers are inaccurate; do not optimize for a single transient answer.

**Acceptance:** Future content decisions are driven by first-party evidence, and success is reported as observations rather than ranking guarantees.

---

## 15. Final acceptance checklist

### Repository and content

- [ ] Brand remains `AdMob CMP`.
- [ ] Repository remains `admob-compose-multiplatform`.
- [ ] Maven coordinate remains `dev.avinya.ads:admob-cmp`.
- [ ] Homepage targets Compose Multiplatform AdMob intent.
- [ ] What-is page targets Kotlin Multiplatform AdMob intent.
- [ ] README and docs name all six formats consistently.
- [ ] README version/compatibility matches `2.0.0`.
- [ ] No inaccurate CI, compliance, lifecycle, or roadmap claims remain.
- [ ] Root license is complete Apache-2.0 text.
- [ ] No version bump was introduced.

### Technical SEO and AI access

- [ ] Authored robots rules are disjoint and each named bot appears once.
- [ ] Search/citation agents are allowed according to policy.
- [ ] Training-oriented agents are blocked according to policy.
- [ ] The `Google-Extended` Gemini tradeoff is documented accurately.
- [ ] `/reference/api/` is indexable and in the sitemap.
- [ ] `/api/**` is accessible, `noindex, follow`, and absent from the sitemap.
- [ ] Canonicals, redirects, sitemap, metadata, and JSON-LD remain valid.
- [ ] Production has no Cloudflare-managed robots preamble.

### Verification and public state

- [ ] `npm run build`, `npm test`, and `npm run verify` pass in order.
- [ ] Full `./scripts/release-readiness.sh` ends in `READINESS: PASS`.
- [ ] The owner approved any commit/PR action after receiving the readiness report.
- [ ] GitHub description, topics, homepage, Discussions, Wiki, license, and release metadata match the plan.
- [ ] Search Console and Bing have the sitemap and priority URL inspections.
- [ ] Klibs has a focused repair report.
- [ ] KMP Awesome submission follows its current contribution rules.
- [ ] A dated search and AI-answer baseline exists.

---

## 16. Primary references

- [Google Search Essentials](https://developers.google.com/search/docs/essentials)
- [Google SEO Starter Guide](https://developers.google.com/search/docs/fundamentals/seo-starter-guide)
- [Google guidance for AI features and your website](https://developers.google.com/search/docs/fundamentals/ai-optimization-guide)
- [Google common crawlers and special-case crawlers](https://developers.google.com/search/docs/crawling-indexing/overview-google-crawlers)
- [OpenAI publishers and developers FAQ](https://help.openai.com/en/articles/12627856-publishers-and-developers-faq)
- [Cloudflare managed `robots.txt`](https://developers.cloudflare.com/bots/additional-configurations/managed-robots-txt/)
- [GitHub repository topics](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/classifying-your-repository-with-topics)
- [Klibs FAQ](https://klibs.io/faq)
- [KMP Awesome repository](https://github.com/terrakok/kmp-awesome)
