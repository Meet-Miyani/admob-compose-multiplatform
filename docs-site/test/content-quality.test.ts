import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const docsDir = fileURLToPath(new URL('../src/content/docs', import.meta.url));
const quickstartPath = join(docsDir, 'start/quickstart.mdx');

const coreBuildGradleKts = join(repoRoot, 'admob-cmp-core/build.gradle.kts');
const composeBuildGradleKts = join(repoRoot, 'admob-cmp-compose/build.gradle.kts');
const versionsToml = join(repoRoot, 'gradle/libs.versions.toml');

const adConfigKt = join(repoRoot, 'admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdConfig.kt');
const adStateKt = join(repoRoot, 'admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdState.kt');
const adManagerKt = join(repoRoot, 'admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdManager.kt');
const adTrackingKt = join(repoRoot, 'admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdTrackingAuthorization.kt');
const adCompositionKt = join(repoRoot, 'admob-cmp-compose/src/commonMain/kotlin/dev/avinya/ads/AdComposition.kt');
const showcaseAdConfigKt = join(repoRoot, 'showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ShowcaseAdConfig.kt');
const adStartupControllerKt = join(repoRoot, 'showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/domain/ad/AdStartupController.kt');
const onboardingViewModelKt = join(repoRoot, 'showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/feature/onboarding/OnboardingViewModel.kt');

export interface ContentViolation {
  file: string;
  line?: number;
  match: string;
  patternName: string;
}

export interface InvalidClaimPattern {
  name: string;
  regex: RegExp;
  description: string;
}

export const INVALID_CLAIM_PATTERNS: InvalidClaimPattern[] = [
  {
    name: 'nonexistent-api-skipConsent',
    regex: /\bAdConfig\.skipConsent\b/g,
    description: 'References nonexistent public API AdConfig.skipConsent instead of ConsentMode.SkipConsent',
  },
  {
    name: 'misleading-test-ids-only',
    regex: /\bonly\s+test\s+ids\s+(?:need\s+to\s+)?change\b|\bonly\s+change\s+test\s+ids\b/gi,
    description: 'Claims only test IDs change for production without explaining control-plane and consent transition',
  },
  {
    name: 'permanent-att-non-personalized',
    regex: /\b(?:forever|permanently)\s+non-personali[sz]ed\b|\bpermanently\s+non-person\b/gi,
    description: 'Claims ATT denial permanently or forever forces requests to be non-personalised',
  },
  {
    name: 'unqualified-ecpm-claims',
    regex: /\bmaterially\s+lower\s+eCPM\b|\bguaranteed\s+eCPM\b/gi,
    description: 'Makes blanket or guaranteed eCPM promises without qualification',
  },
  {
    name: 'invalid-target-support-claims',
    regex: /\b(?:server-side\s+app(?:lication)?|CL\s+tool|CLIs?)\s+(?:is|are|use|uses)\s+(?:a\s+)?supported\s+target\b|\bsupported\s+target[s]?\s+for\s+(?:this|the)\s+library\b|\bdesktop\s+and\s+web\s+are\s+supported\s+ad-serving\s+targets\b/gi,
    description: 'Claims server-side, CLI, desktop, or web are supported library targets for admob-cmp-core/compose',
  },
  {
    name: 'leaked-authoring-instructions',
    regex: /\bThis\s+section\s+must\b|\bOne\s+short\s+paragraph\b|\bShow\s+it:\b|\bexactly\s+as\s+implemented\b/gi,
    description: 'Contains prompt leakage or editorial instruction phrases',
  },
];

export function findInvalidClaimsInContent(content: string, filePath = 'unknown'): ContentViolation[] {
  const violations: ContentViolation[] = [];
  const lines = content.split(/\r?\n/);

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    for (const pattern of INVALID_CLAIM_PATTERNS) {
      pattern.regex.lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = pattern.regex.exec(line)) !== null) {
        violations.push({
          file: filePath,
          line: i + 1,
          match: match[0],
          patternName: pattern.name,
        });
      }
    }
  }

  return violations;
}

function listMdxFilesRecursive(dir: string): string[] {
  if (!existsSync(dir)) return [];
  const files: string[] = [];
  for (const entry of readdirSync(dir)) {
    const fullPath = join(dir, entry);
    const stat = statSync(fullPath);
    if (stat.isDirectory()) {
      files.push(...listMdxFilesRecursive(fullPath));
    } else if (fullPath.endsWith('.mdx') || fullPath.endsWith('.md')) {
      files.push(fullPath);
    }
  }
  return files;
}

function listAllFilesRecursive(dir: string): string[] {
  if (!existsSync(dir)) return [];
  const files: string[] = [];
  for (const entry of readdirSync(dir)) {
    const fullPath = join(dir, entry);
    const stat = statSync(fullPath);
    if (stat.isDirectory()) {
      files.push(...listAllFilesRecursive(fullPath));
    } else {
      files.push(fullPath);
    }
  }
  return files;
}

export function scanAuthoredDocsForViolations(directory: string = docsDir): ContentViolation[] {
  const files = listMdxFilesRecursive(directory);
  const violations: ContentViolation[] = [];
  for (const file of files) {
    const content = readFileSync(file, 'utf8');
    const fileViolations = findInvalidClaimsInContent(content, file);
    violations.push(...fileViolations);
  }
  return violations;
}

export function assertNoInvalidContentClaims(directory: string = docsDir): void {
  const violations = scanAuthoredDocsForViolations(directory);
  if (violations.length > 0) {
    const details = violations
      .map((v) => `  - ${v.file}:${v.line} [${v.patternName}] "${v.match}"`)
      .join('\n');
    throw new Error(`Found ${violations.length} invalid or unsafe content claim(s):\n${details}`);
  }
}

export interface QuickstartContractCheck {
  pass: boolean;
  missing: string[];
  found: string[];
}

export function verifyQuickstartContract(content: string): QuickstartContractCheck {
  const requiredTerms = [
    { key: 'AdInitializationHook', regex: /\bAdInitializationHook\b/ },
    { key: 'PreMobileAdsPhase', regex: /\b(?:BeforeMobileAdsInitialize|PreMobileAds|PreMobileAdsInit)\b/ },
    { key: 'requestAuthorization', regex: /\brequestAuthorization\b/ },
    { key: 'ConsentMode.GatherBeforeInitialize', regex: /\bConsentMode\.GatherBeforeInitialize\b/ },
  ];

  const missing: string[] = [];
  const found: string[] = [];

  for (const term of requiredTerms) {
    if (term.regex.test(content)) {
      found.push(term.key);
    } else {
      missing.push(term.key);
    }
  }

  return {
    pass: missing.length === 0,
    missing,
    found,
  };
}

export function assertQuickstartContract(path: string = quickstartPath): void {
  if (!existsSync(path)) {
    throw new Error(`Quickstart file not found at ${path}`);
  }
  const content = readFileSync(path, 'utf8');
  const result = verifyQuickstartContract(content);
  if (!result.pass) {
    throw new Error(
      `Quickstart contract failed. Missing required references:\n  - ${result.missing.join('\n  - ')}`
    );
  }
}

describe('Source-of-truth factual contracts', () => {
  it('supported Kotlin targets in module gradle files are android, iosArm64, and iosSimulatorArm64', () => {
    const coreBuild = readFileSync(coreBuildGradleKts, 'utf8');
    const composeBuild = readFileSync(composeBuildGradleKts, 'utf8');

    for (const buildFile of [coreBuild, composeBuild]) {
      expect(buildFile).toContain('android {');
      expect(buildFile).toContain('iosArm64()');
      expect(buildFile).toContain('iosSimulatorArm64()');

      expect(buildFile).not.toContain('jvm()');
      expect(buildFile).not.toContain('js()');
      expect(buildFile).not.toContain('wasmJs()');
      expect(buildFile).not.toContain('desktop(');
    }
  });

  it('dependency and platform versions in gradle/libs.versions.toml match authoritative values', () => {
    const toml = readFileSync(versionsToml, 'utf8');

    expect(toml).toMatch(/^kotlin\s*=\s*"2\.4\.20"(?:\s*#.*)?$/m);
    expect(toml).toMatch(/^composeMultiplatform\s*=\s*"1\.12\.0"(?:\s*#.*)?$/m);
    expect(toml).toMatch(/^gmaNextGen\s*=\s*"1\.4\.0"(?:\s*#.*)?$/m);
    expect(toml).toMatch(/^gmaUmp\s*=\s*"4\.0\.0"(?:\s*#.*)?$/m);
    expect(toml).toMatch(/^gmaIos\s*=\s*"13\.9\.0"(?:\s*#.*)?$/m);
    expect(toml).toMatch(/^gmaUmpIos\s*=\s*"3\.1\.0"(?:\s*#.*)?$/m);
  });

  it('AdConfig does not have a skipConsent property and exports valid configuration structures', () => {
    const adConfig = readFileSync(adConfigKt, 'utf8');

    expect(adConfig).toContain('public data class AdConfig');
    expect(adConfig).toContain('public enum class AdInitializationPhase');
    expect(adConfig).toContain('BeforeConsentRequest');
    expect(adConfig).toContain('BeforeMobileAdsInitialize');
    expect(adConfig).toContain('AfterMobileAdsInitialize');
    expect(adConfig).toContain('public interface AdInitializationHook');

    expect(adConfig).not.toContain('val skipConsent');
    expect(adConfig).not.toContain('var skipConsent');
  });

  it('ConsentMode enum declares GatherBeforeInitialize, InitializeOnlyIfAlreadyAllowed, and SkipConsent', () => {
    const adState = readFileSync(adStateKt, 'utf8');

    expect(adState).toContain('public enum class ConsentMode');
    expect(adState).toContain('GatherBeforeInitialize');
    expect(adState).toContain('InitializeOnlyIfAlreadyAllowed');
    expect(adState).toContain('SkipConsent');
  });

  it('AdTrackingController exposes status() and requestAuthorization()', () => {
    const adTracking = readFileSync(adTrackingKt, 'utf8');

    expect(adTracking).toContain('public interface AdTrackingController');
    expect(adTracking).toContain('public fun status(): AdTrackingAuthorization');
    expect(adTracking).toContain('public suspend fun requestAuthorization(): AdTrackingAuthorization');
    expect(adTracking).toContain('public enum class AdTrackingAuthorization');
  });

  it('AdManager exposes initialize, consent, and tracking', () => {
    const adManager = readFileSync(adManagerKt, 'utf8');

    expect(adManager).toContain('public interface AdManager');
    expect(adManager).toContain('public suspend fun initialize(');
    expect(adManager).toContain('public val consent: ConsentController');
    expect(adManager).toContain('public val tracking: AdTrackingController');
  });

  it('AdComposition exports rememberAdManager expect composable', () => {
    const adComposition = readFileSync(adCompositionKt, 'utf8');

    expect(adComposition).toContain('public expect fun rememberAdManager(): AdManager');
  });

  it('showcase sample startup uses AdInitializationHook and TrackingAuthorizationHook', () => {
    const showcaseConfig = readFileSync(showcaseAdConfigKt, 'utf8');

    expect(showcaseConfig).toContain('class TrackingAuthorizationHook');
    expect(showcaseConfig).toContain('AdInitializationPhase.BeforeMobileAdsInitialize');
    expect(showcaseConfig).toContain('requestAuthorization()');
  });

  it('showcase onboarding gathers consent before initialising', () => {
    const startup = readFileSync(adStartupControllerKt, 'utf8');

    expect(startup).toContain('consent.gatherConsent(config)');
    expect(startup).toContain('ConsentMode.InitializeOnlyIfAlreadyAllowed');
  });
});

describe('Content Quality Scanner & Contract Helpers', () => {
  it('detects AdConfig.skipConsent in sample prose', () => {
    const sample = 'You should set `AdConfig.skipConsent = true` if you want to bypass UMP.';
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(1);
    expect(violations[0].patternName).toBe('nonexistent-api-skipConsent');
    expect(violations[0].match).toBe('AdConfig.skipConsent');
  });

  it('detects "only test IDs change" claims in sample prose', () => {
    const sample = 'When moving to production, only test IDs change in your config.';
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(1);
    expect(violations[0].patternName).toBe('misleading-test-ids-only');
  });

  it('detects permanent ATT non-personalization claims in sample prose', () => {
    const sample = 'If the user denies ATT, the user is forever non-personalized on that install.';
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(1);
    expect(violations[0].patternName).toBe('permanent-att-non-personalized');
  });

  it('detects blanket eCPM claims in sample prose', () => {
    const sample = 'Denied ATT requests serve ads at materially lower eCPM.';
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(1);
    expect(violations[0].patternName).toBe('unqualified-ecpm-claims');
  });

  it('detects server-side / CLI target support claims for the Android/iOS library', () => {
    const sample = 'A server-side app is a supported target for this library.';
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(1);
    expect(violations[0].patternName).toBe('invalid-target-support-claims');
  });

  it('detects leaked authoring instructions in sample prose', () => {
    const sample = 'This section must explain the setup process.';
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(1);
    expect(violations[0].patternName).toBe('leaked-authoring-instructions');
  });

  it('returns no violations for factual, technical prose', () => {
    const sample = `
      Call \`adManager.initialize(config, ConsentMode.GatherBeforeInitialize)\` on startup.
      Use UMP to gather user consent in EEA regions before loading ads.
    `;
    const violations = findInvalidClaimsInContent(sample, 'sample.mdx');

    expect(violations).toHaveLength(0);
  });

  it('verifyQuickstartContract detects missing contract elements', () => {
    const incomplete = 'Initialize with AdManager.initialize()';
    const check = verifyQuickstartContract(incomplete);

    expect(check.pass).toBe(false);
    expect(check.missing).toContain('AdInitializationHook');
    expect(check.missing).toContain('PreMobileAdsPhase');
    expect(check.missing).toContain('requestAuthorization');
    expect(check.missing).toContain('ConsentMode.GatherBeforeInitialize');
  });

  it('verifyQuickstartContract passes when all required terms are present', () => {
    const complete = `
      Use \`AdInitializationHook\` during the \`BeforeMobileAdsInitialize\` phase
      to call \`requestAuthorization\` before Mobile Ads init.
      Set \`ConsentMode.GatherBeforeInitialize\` to gather consent and initialise.
    `;
    const check = verifyQuickstartContract(complete);

    expect(check.pass).toBe(true);
    expect(check.missing).toHaveLength(0);
  });
});

describe('Authored MDX Content Audit', () => {
  it('scans authored MDX files and asserts zero content quality violations', () => {
    assertNoInvalidContentClaims(docsDir);
  });

  it('verifies quickstart page contract', () => {
    assertQuickstartContract(quickstartPath);
  });

  it('file traversal recurses through docs-site/src/content/docs only and excludes public/api/ and dist/', () => {
    const files = listMdxFilesRecursive(docsDir);
    expect(files.length).toBeGreaterThan(0);
    for (const file of files) {
      expect(file).toContain('src/content/docs');
      expect(file).not.toContain('public/api');
      expect(file).not.toContain('/dist/');
    }
  });

  it('sidebar links in astro.config.mjs point to valid existing MDX files', () => {
    const astroConfigPath = join(repoRoot, 'docs-site/astro.config.mjs');
    const astroConfig = readFileSync(astroConfigPath, 'utf8');
    const slugMatches = [...astroConfig.matchAll(/slug:\s*['"]([^'"]+)['"]/g)];
    expect(slugMatches.length).toBeGreaterThan(0);

    for (const match of slugMatches) {
      const slug = match[1];
      const mdxPath = join(docsDir, `${slug}.mdx`);
      const mdPath = join(docsDir, `${slug}.md`);
      const exists = existsSync(mdxPath) || existsSync(mdPath);
      expect(exists, `Sidebar slug "${slug}" does not map to an existing file in ${docsDir}`).toBe(true);
    }
  });

  it('no source file under src/ references obsolete symbols (CapabilityMatrix, basicAdsRepo, capabilityVerifiedOn, FAQPage)', () => {
    const srcDir = fileURLToPath(new URL('../src', import.meta.url));
    const allSrcFiles = listAllFilesRecursive(srcDir);
    const obsoleteRegex = /CapabilityMatrix|basicAdsRepo|capabilityVerifiedOn|FAQPage/;

    const matches: string[] = [];
    for (const file of allSrcFiles) {
      const content = readFileSync(file, 'utf8');
      if (obsoleteRegex.test(content)) {
        matches.push(file);
      }
    }
    expect(matches, `Found obsolete references in source files: ${matches.join(', ')}`).toEqual([]);
  });
});

describe('Store Data Disclosure Guidance Contracts', () => {
  const playDataSafetyPath = join(docsDir, 'privacy/play-data-safety.mdx');
  const appStoreDataDisclosurePath = join(docsDir, 'privacy/app-store-data-disclosure.mdx');
  const astroConfigPath = join(repoRoot, 'docs-site/astro.config.mjs');

  it('play-data-safety.mdx contains required categories (diagnostics, app set ID, fraud prevention) and primary source link', () => {
    expect(existsSync(playDataSafetyPath)).toBe(true);
    const content = readFileSync(playDataSafetyPath, 'utf8');

    expect(content).toMatch(/diagnostic/i);
    expect(content).toMatch(/app set id/i);
    expect(content).toMatch(/fraud prevention/i);
    expect(content).toContain('https://developers.google.com/admob/android/next-gen/privacy/play-data-disclosure');
  });

  it('app-store-data-disclosure.mdx exists, is in sidebar, links to primary guidance, and contains publisher qualification', () => {
    expect(existsSync(appStoreDataDisclosurePath)).toBe(true);
    const content = readFileSync(appStoreDataDisclosurePath, 'utf8');

    expect(content).toContain('https://developers.google.com/admob/ios/privacy/data-disclosure');
    expect(content).toContain('https://developer.apple.com/app-store/app-privacy-details/');
    expect(content).toMatch(/publisher/i);
    expect(content).toMatch(/legal advice/i);
    const astroConfig = readFileSync(astroConfigPath, 'utf8');
    expect(astroConfig).toContain('privacy/app-store-data-disclosure');
  });
});
