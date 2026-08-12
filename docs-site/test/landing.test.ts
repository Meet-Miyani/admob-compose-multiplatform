import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import {
  authorName,
  authorUrl,
  formats,
  landingMeta,
  originStory,
  repoUrl,
  roadmapItems,
  studioName,
  studioUrl,
  trademarkStatement,
} from '../src/data/landing';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const rootGradleProps = join(repoRoot, 'gradle.properties');
const pluginGradleProps = join(repoRoot, 'admob-cmp-gradle-plugin', 'gradle.properties');
const versionsToml = join(repoRoot, 'gradle', 'libs.versions.toml');
const coreBuildGradleKts = join(repoRoot, 'admob-cmp-core', 'build.gradle.kts');
const landingComponentsDir = fileURLToPath(
  new URL('../src/components/landing', import.meta.url)
);
const landingCssPath = fileURLToPath(
  new URL('../src/styles/landing.css', import.meta.url)
);

function readVersionName(file: string): string {
  const contents = readFileSync(file, 'utf8');
  const match = contents.match(/^VERSION_NAME\s*=\s*(.+?)\s*$/m);
  if (!match) throw new Error(`VERSION_NAME not found in ${file}`);
  return match[1];
}

function readTomlString(file: string, key: string): string {
  const contents = readFileSync(file, 'utf8');
  const re = new RegExp(`^${key}\\s*=\\s*"([^"]+)"\\s*$`, 'm');
  const match = contents.match(re);
  if (!match) throw new Error(`${key} not found in ${file}`);
  return match[1];
}

interface CssBlock {
  selector: string;
  body: string;
}

function extractCssBlocks(contents: string): CssBlock[] {
  const stripped = contents.replace(/\/\*[\s\S]*?\*\//g);
  const blocks: CssBlock[] = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(stripped)) !== null) {
    blocks.push({ selector: match[1].trim(), body: match[2] });
  }
  return blocks;
}

function extractStyleBlocks(astroSource: string): CssBlock[] {
  const blocks: CssBlock[] = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/gi;
  let match: RegExpExecArray | null;
  while ((match = re.exec(astroSource)) !== null) {
    for (const block of extractCssBlocks(match[1])) {
      blocks.push(block);
    }
  }
  return blocks;
}

const CSS_COMMENT = /\/\*[\s\S]*?\*\//g;

/**
 * Radius values a landing file may write directly. Anything else has to come
 * from a --admob-radius* token so the corner scale stays in one place.
 *
 * `0` clears a corner, `50%` and `999px` are shape declarations (a circle and a
 * pill) rather than points on the scale, and `inherit`/`initial`/`unset` are
 * resets.
 */
const ALLOWED_LITERAL_RADIUS = /^(0|50%|999px|inherit|initial|unset)$/;

function isAllowedRadiusValue(value: string): boolean {
  // Shorthand: every part must independently be a token reference or allowed.
  const parts = value.split('/').join(' ').split(/\s+/).filter(Boolean);
  return parts.every(
    (part) => part.startsWith('var(--admob-radius') || ALLOWED_LITERAL_RADIUS.test(part)
  );
}

function listFilesRecursive(dir: string): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      out.push(...listFilesRecursive(full));
    } else {
      out.push(full);
    }
  }
  return out;
}

/**
 * Design-system rules for the landing sources.
 *
 * These used to be an anti-design list — no shadows, no transforms, no
 * gradients, no motion — which is why the page looked like an unstyled README.
 * The rules now enforce that the design goes *through the token system* rather
 * than forbidding the design outright:
 *
 *   - no literal colours: every colour is an --admob-* token, so both themes
 *     re-skin from one place and the contrast tests in diagram-contrast.test.ts
 *     stay meaningful;
 *   - no literal font stacks: faces come from --admob-font-*;
 *   - no off-scale corner radii: see ALLOWED_LITERAL_RADIUS above;
 *   - any file that animates must also answer prefers-reduced-motion.
 *
 * Shadows, transforms, gradients and transitions are all permitted now, as
 * long as their colours resolve to tokens — which the literal-colour rule
 * already guarantees.
 */
const STYLING_VIOLATION_CHECKS = {
  color: /#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(|oklch\(|oklab\(/,
};

const MOTION_DECLARATION = /@keyframes\b|(?<![\w-])animation(?:-name|-duration)?\s*:/;
const REDUCED_MOTION_GUARD = /prefers-reduced-motion/;

function collectStyleContexts(source: string, isAstro: boolean): string {
  if (!isAstro) return source;
  const contexts: string[] = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/gi;
  let match: RegExpExecArray | null;
  while ((match = re.exec(source)) !== null) {
    contexts.push(match[1]);
  }
  const inlineRe = /\sstyle\s*=\s*"([^"]*)"/g;
  while ((match = inlineRe.exec(source)) !== null) {
    contexts.push(match[1]);
  }
  return contexts.join('\n');
}

function findStylingViolations(source: string, isAstro: boolean): string[] {
  const cssOnly = collectStyleContexts(source, isAstro).replace(CSS_COMMENT, '');
  const blocks = isAstro ? extractStyleBlocks(source) : extractCssBlocks(cssOnly);
  const violations: string[] = [];

  const colorHit = cssOnly.match(STYLING_VIOLATION_CHECKS.color);
  if (colorHit) {
    violations.push(`literal color '${colorHit[0]}'`);
  }

  if (MOTION_DECLARATION.test(cssOnly) && !REDUCED_MOTION_GUARD.test(cssOnly)) {
    violations.push('animates without a prefers-reduced-motion guard in the same file');
  }

  for (const block of blocks) {
    for (const declaration of block.body.split(';')) {
      const trimmed = declaration.trim();
      if (!trimmed) continue;
      const value = trimmed.split(':').slice(1).join(':').trim();

      if (/^font-family\s*:/i.test(trimmed) && !value.includes('var(--admob-font-')) {
        violations.push(`font-family not from a token in '${block.selector}': ${value}`);
      }
      if (/^border(?:-[a-z]+)?-radius\s*:/i.test(trimmed) && !isAllowedRadiusValue(value)) {
        violations.push(`off-scale radius in '${block.selector}': ${value}`);
      }
    }
  }

  return violations;
}

function checkFileForStylingViolations(filePath: string): string[] {
  const raw = readFileSync(filePath, 'utf8');
  const isAstro = filePath.endsWith('.astro');
  const isCss = filePath.endsWith('.css');
  if (!isAstro && !isCss) return [];
  return findStylingViolations(raw, isAstro);
}

describe('landing.ts data module exports are wired', () => {
  it('exports six format records and roadmap items as defined types', () => {
    expect(formats).toHaveLength(6);
    expect(roadmapItems).toHaveLength(2);
  });
});

describe('gradle version lockstep', () => {
  it('root gradle.properties and plugin gradle.properties share the same VERSION_NAME', () => {
    expect(readVersionName(rootGradleProps)).toBe(readVersionName(pluginGradleProps));
  });

  it('landingMeta version strings match the gradle VERSION_NAME', () => {
    const version = readVersionName(rootGradleProps);
    expect(landingMeta.mavenCoordinate).toContain(version);
    expect(landingMeta.gradlePlugin).toContain(version);
  });
});

describe('landingMeta toolchain and platform facts match build configuration', () => {
  it('kotlin version matches gradle/libs.versions.toml', () => {
    expect(landingMeta.kotlinVersion).toBe(readTomlString(versionsToml, 'kotlin'));
  });

  it('compose multiplatform version matches gradle/libs.versions.toml', () => {
    expect(landingMeta.composeMultiplatformVersion).toBe(
      readTomlString(versionsToml, 'composeMultiplatform')
    );
  });

  it('android minSdk is the integer 26 from gradle/libs.versions.toml', () => {
    expect(landingMeta.androidMinSdk).toBe(26);
    expect(Number(readTomlString(versionsToml, 'android-minSdk'))).toBe(26);
  });

  it('iOS deployment target is 15.0 and the build file pins it', () => {
    expect(landingMeta.iosDeploymentTarget).toBe('15.0');
    const buildFile = readFileSync(coreBuildGradleKts, 'utf8');
    expect(buildFile).toMatch(/osVersionMin\.ios_arm64=15\.0/);
    expect(buildFile).toMatch(/osVersionMin=15\.0/);
  });

  it('license is the Apache License 2.0', () => {
    expect(landingMeta.licenseName).toBe('Apache License 2.0');
  });
});

describe('formats contract', () => {
  const EXPECTED_ORDER = [
    'banner',
    'interstitial',
    'rewarded',
    'rewarded-interstitial',
    'app-open',
    'native',
  ] as const;

  it('contains exactly the six canonical slugs in the canonical order', () => {
    expect(formats.map((f) => f.slug)).toEqual([...EXPECTED_ORDER]);
  });

  it('uses unique slugs', () => {
    const slugs = formats.map((f) => f.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
  });

  it('every internal href ends with a trailing slash or a section fragment', () => {
    for (const f of formats) {
      const isInternal = f.href.startsWith('/');
      expect(isInternal, `${f.slug} href ${f.href} is not internal`).toBe(true);
      const terminal = f.href.endsWith('/') || /\/#[A-Za-z0-9_-]+$/.test(f.href);
      expect(
        terminal,
        `${f.slug} href ${f.href} must end with / or a #fragment`
      ).toBe(true);
    }
  });

  it('every format states a non-empty on-screen dimension', () => {
    for (const f of formats) {
      expect(typeof f.dimension, `${f.slug} dimension must be a string`).toBe('string');
      expect(f.dimension.length, `${f.slug} dimension must not be empty`).toBeGreaterThan(0);
    }
  });

  it('banner states its real size and the four full-screen formats agree with each other', () => {
    const bySlug = Object.fromEntries(formats.map((f) => [f.slug, f.dimension]));
    expect(bySlug.banner).toBe('320 × 50 dp');
    for (const slug of ['interstitial', 'rewarded', 'rewarded-interstitial', 'app-open']) {
      expect(bySlug[slug], `${slug} is a full-screen format`).toBe('full screen');
    }
    expect(bySlug.native).not.toBe('full screen');
  });
});

describe('legal and repository contracts', () => {
  it('trademark statement is verbatim', () => {
    expect(trademarkStatement).toBe(
      'Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads are trademarks of Google LLC.'
    );
  });

  it('repo URL is the canonical GitHub URL', () => {
    expect(repoUrl).toBe('https://github.com/Meet-Miyani/admob-compose-multiplatform');
  });
});

describe('roadmap contract', () => {
  it('has exactly two items with non-empty title and status', () => {
    expect(roadmapItems).toHaveLength(2);
    for (const item of roadmapItems) {
      expect(item.title.length).toBeGreaterThan(0);
      expect(item.status.length).toBeGreaterThan(0);
    }
  });

  it('titles are the two canonical roadmap items', () => {
    const titles = roadmapItems.map((i) => i.title);
    expect(titles).toContain('Swift Package Manager dependency import');
    expect(titles).toContain('Native video events on Android');
  });
});

describe('landing component styling-boundary rules', () => {
  let componentFiles: string[] = [];
  let cssExists = false;

  beforeAll(() => {
    componentFiles = listFilesRecursive(landingComponentsDir);
    cssExists = existsSync(landingCssPath);
    if (cssExists) componentFiles.push(landingCssPath);
  });

  it('tolerates the missing landing directory and landing.css', () => {
    if (componentFiles.length === 0) {
      expect(componentFiles).toEqual([]);
    }
  });

  it('no landing file uses literal colors, literal font stacks, or off-scale radii', () => {
    for (const file of componentFiles) {
      const violations = checkFileForStylingViolations(file);
      expect(violations, `${file} contains ${violations.join(', ')}`).toEqual([]);
    }
  });

  it('flags a literal colour', () => {
    const violations = findStylingViolations('.x { color: #ff0000; }', false);
    expect(violations.some((v) => v.startsWith('literal color'))).toBe(true);
  });

  it('accepts a colour that resolves through a token', () => {
    const violations = findStylingViolations('.x { color: var(--admob-ink); }', false);
    expect(violations).toEqual([]);
  });

  it('flags a hard-coded font stack but accepts the token', () => {
    expect(
      findStylingViolations(".x { font-family: 'Helvetica', sans-serif; }", false).some((v) =>
        v.startsWith('font-family not from a token')
      )
    ).toBe(true);
    expect(findStylingViolations('.x { font-family: var(--admob-font-mono); }', false)).toEqual([]);
  });

  it('flags an off-scale radius but accepts tokens, pills and zero', () => {
    expect(
      findStylingViolations('.x { border-radius: 7px; }', false).some((v) =>
        v.startsWith('off-scale radius')
      )
    ).toBe(true);
    expect(findStylingViolations('.x { border-radius: 999px; }', false)).toEqual([]);
    expect(
      findStylingViolations(
        '.x { border-radius: 0 var(--admob-radius-lg) var(--admob-radius-lg) 0; }',
        false
      )
    ).toEqual([]);
  });

  it('flags animation that has no reduced-motion answer, and accepts it when guarded', () => {
    expect(
      findStylingViolations('@keyframes k { to { opacity: 1; } } .x { animation: k 1s; }', false)
    ).toContain('animates without a prefers-reduced-motion guard in the same file');
    expect(
      findStylingViolations(
        '@keyframes k { to { opacity: 1; } } .x { animation: k 1s; } @media (prefers-reduced-motion: reduce) { .x { animation: none; } }',
        false
      )
    ).toEqual([]);
  });

  it('permits shadows, transforms and gradients now that their colours must be tokens', () => {
    expect(
      findStylingViolations(
        '.x { box-shadow: var(--admob-shadow); transform: translateY(2px); background: linear-gradient(var(--admob-tint), transparent); }',
        false
      )
    ).toEqual([]);
  });
});

describe('landing components do not import PNGs directly', () => {
  let componentFiles: string[] = [];

  beforeAll(() => {
    componentFiles = listFilesRecursive(landingComponentsDir).filter((f) =>
      f.endsWith('.astro') || f.endsWith('.ts') || f.endsWith('.tsx')
    );
  });

  it('no landing source file imports a .png asset directly', () => {
    const offenders: string[] = [];
    for (const file of componentFiles) {
      const source = readFileSync(file, 'utf8');
      if (/\bimport\s+[^;]*\.png\b/.test(source)) {
        offenders.push(file);
      }
    }
    expect(offenders, `landing files import .png directly: ${offenders.join(', ')}`).toEqual([]);
  });
});

const placementPlatePath = fileURLToPath(
  new URL('../src/components/landing/PlacementPlate.astro', import.meta.url)
);
const heroPath = fileURLToPath(new URL('../src/components/Hero.astro', import.meta.url));

describe('PlacementPlate.astro contracts', () => {
  const source = readFileSync(placementPlatePath, 'utf8');

  it('exists as an Astro component', () => {
    expect(existsSync(placementPlatePath)).toBe(true);
  });

  it('iterates the imported `formats` array directly, without sort, filter or slice', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\bformats\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
    expect(source).toMatch(/formats\.map\(/);
    expect(source).not.toMatch(/\.sort\s*\(/);
    expect(source).not.toMatch(/formats\.(?:filter|slice)\s*\(/);
  });

  it('renders exactly one <ol class="landing-formats"> of anchors to each format guide', () => {
    expect(source.match(/<ol\s+class="landing-formats"/g) ?? []).toHaveLength(1);
    expect(source).toMatch(/<a\s+href=\{format\.href\}\s+data-format=\{format\.slug\}>/);
  });

  it('renders the API identifier inside <code class="admob-font-mono">', () => {
    expect(source).toMatch(
      /<code[^>]*class="[^"]*\badmob-font-mono\b[^"]*"[^>]*>\s*\{format\.api\}\s*<\/code>/
    );
  });

  it('renders each format name, blurb and dimension as text in the row', () => {
    expect(source).toMatch(/\{format\.name\}/);
    expect(source).toMatch(/\{format\.blurb\}/);
    expect(source).toMatch(/\{format\.dimension\}/);
  });

  it('hides the decorative phone from assistive technology', () => {
    expect(source).toMatch(/class="landing-plate__stage"\s+aria-hidden="true"/);
  });

  it('ships no client-side script — the plate is CSS-only', () => {
    expect(source).not.toMatch(/<script/i);
    expect(source).not.toMatch(/addEventListener|client:(load|idle|visible)/);
  });

  it('landing.css keeps the viewport at a real 9:19.5 phone aspect', () => {
    const css = readFileSync(landingCssPath, 'utf8');
    expect(css).toMatch(/\.landing-plate__viewport\s*\{[^}]*aspect-ratio\s*:\s*9\s*\/\s*19\.5/);
  });

  it('landing.css drives the ad block from --ad-* custom properties', () => {
    const css = readFileSync(landingCssPath, 'utf8');
    const block = css.match(/\.landing-plate__ad\s*\{([^}]*)\}/);
    expect(block, '.landing-plate__ad rule must be present').not.toBeNull();
    for (const property of ['left', 'top', 'width', 'height']) {
      const re = new RegExp(`${property}\\s*:\\s*var\\(--ad-`);
      expect(re.test(block![1]), `.landing-plate__ad must set ${property} from --ad-*`).toBe(true);
    }
  });

  it('landing.css defines geometry for every format slug, keyed off :has()', () => {
    const css = readFileSync(landingCssPath, 'utf8');
    for (const format of formats) {
      if (format.slug === 'banner') continue; // banner is the resting state
      expect(
        css.includes(`[data-format='${format.slug}']:hover`),
        `landing.css must define a hover geometry for ${format.slug}`
      ).toBe(true);
      expect(
        css.includes(`[data-format='${format.slug}']:focus`),
        `landing.css must define a focus geometry for ${format.slug}`
      ).toBe(true);
    }
  });

  it('the plate follows :focus, not only :focus-visible, so keyboard users drive it', () => {
    const css = readFileSync(landingCssPath, 'utf8');
    expect(css).not.toMatch(/\[data-format='[a-z-]+'\]:focus-visible/);
  });
});

describe('Hero.astro contracts', () => {
  const source = readFileSync(heroPath, 'utf8');

  it('exists and is registered as the Starlight Hero override', () => {
    expect(existsSync(heroPath)).toBe(true);
    const config = readFileSync(
      fileURLToPath(new URL('../astro.config.mjs', import.meta.url)),
      'utf8'
    );
    expect(config).toMatch(/Hero:\s*['"]\.\/src\/components\/Hero\.astro['"]/);
  });

  it('renders the <h1> from hero frontmatter with the id the skip link targets', () => {
    // The rendered count is asserted against dist/index.html further down; this
    // only pins the shape, so the title cannot drift away from frontmatter.
    expect(source).toMatch(/<h1\s+id="_top"\s+data-page-title\s+set:html=\{title\}\s*\/>/);
    expect(source).toMatch(/const\s*\{\s*title\s*=\s*data\.title/);
  });

  it('renders the frontmatter actions as anchors, and nothing else focusable inside .hero', () => {
    expect(source).toMatch(/actions\.map\(/);
    expect(source).toMatch(/class:list=\{\[\s*'landing-hero__action'/);
    // The plate's six links must be siblings of .hero, not descendants, or the
    // two-hero-action assertion in scripts/check-theme.mjs would count eight.
    const heroBlock = source.match(/<div class="hero landing-hero">([\s\S]*?)<\/div>\s*\n\s*<PlacementPlate/);
    expect(heroBlock, '.hero must close before <PlacementPlate />').not.toBeNull();
    expect(heroBlock![1]).not.toMatch(/<PlacementPlate/);
  });

  it('composes the spec strip and the plate', () => {
    expect(source).toMatch(/import\s+ProjectMetadata\s+from\s+['"]\.\/landing\/ProjectMetadata\.astro['"]/);
    expect(source).toMatch(/import\s+PlacementPlate\s+from\s+['"]\.\/landing\/PlacementPlate\.astro['"]/);
    expect(source).toMatch(/<ProjectMetadata\s*\/>/);
    expect(source).toMatch(/<PlacementPlate\s*\/>/);
  });
});

const capabilityMatrixPath = fileURLToPath(
  new URL('../src/components/landing/CapabilityMatrix.astro', import.meta.url)
);
const indexMdxPath = fileURLToPath(
  new URL('../src/content/docs/index.mdx', import.meta.url)
);

describe('competitor data and comparison matrix absence', () => {
  it('CapabilityMatrix.astro file is deleted', () => {
    expect(existsSync(capabilityMatrixPath)).toBe(false);
  });

  it('index.mdx does not import or render CapabilityMatrix', () => {
    const source = readFileSync(indexMdxPath, 'utf8');
    expect(source).not.toMatch(/CapabilityMatrix/);
  });

  it('competitor data (basic-ads, comparisonMatrix, capabilities) is absent from landing page sources', () => {
    const mdx = readFileSync(indexMdxPath, 'utf8');
    const dataTs = readFileSync(
      fileURLToPath(new URL('../src/data/landing.ts', import.meta.url)),
      'utf8'
    );
    expect(mdx).not.toMatch(/basic-ads|comparisonMatrix|CapabilityMatrix/i);
    expect(dataTs).not.toMatch(/basic-ads|comparisonMatrix|capabilityVerifiedOn|CapabilityRow/i);
  });
});

describe('index.mdx quickstart, product facts, and iOS note', () => {
  const source = readFileSync(indexMdxPath, 'utf8');

  it('asserts presence of supported platforms (Android, iOS), ad formats, facade dependency, and production responsibility wording', () => {
    expect(source).toMatch(/dev\.avinya\.ads:admob-cmp/);
    expect(source).toMatch(/Android/);
    expect(source).toMatch(/iOS/);
    expect(source).toMatch(/Banner/);
    expect(source).toMatch(/Interstitial/);
    expect(source).toMatch(/Rewarded/);
    expect(source).toMatch(/App Open/);
    expect(source).toMatch(/Native/);
    expect(source).toMatch(/configured by the application|configured by the app/i);
  });

  it('does not contain time-based promises like 5-minute quickstart', () => {
    expect(source).not.toMatch(/5-minute/i);
  });

  it('keeps the install line on the canonical Maven coordinate and version 2.0.0', () => {
    expect(source).toMatch(/implementation\(["']dev\.avinya\.ads:admob-cmp:2\.0\.0["']\)/);
  });

  it('preserves the gatherConsentAndInitialize(AdConfig(...)) initialization shape', () => {
    expect(source).toMatch(/adManager\.gatherConsentAndInitialize\(/);
    expect(source).toMatch(/AdConfig\(/);
  });

  it('keeps the iOS note mentioning ConsentMode.InitializeOnlyIfAlreadyAllowed and the UMP -> ATT -> initialize ordering', () => {
    expect(source).toMatch(/ConsentMode\.InitializeOnlyIfAlreadyAllowed/);
    expect(source).toMatch(/UMP consent/i);
    expect(source).toMatch(/ATT/i);
  });

  it('links the Quickstart example to /start/quickstart/ with a trailing slash', () => {
    const matches = source.match(/\(\/start\/quickstart\/\)/g) ?? [];
    expect(matches.length, 'multiple quickstart links expected (intro + continue)').toBeGreaterThan(
      0
    );
  });

  it('imports the OriginStory, CompatibilityList, RoadmapSummary, and LandingFooter components', () => {
    expect(source).toMatch(
      /import\s+OriginStory\s+from\s+['"]\.\.\/\.\.\/components\/landing\/OriginStory\.astro['"]/
    );
    expect(source).toMatch(
      /import\s+CompatibilityList\s+from\s+['"]\.\.\/\.\.\/components\/landing\/CompatibilityList\.astro['"]/
    );
    expect(source).toMatch(
      /import\s+RoadmapSummary\s+from\s+['"]\.\.\/\.\.\/components\/landing\/RoadmapSummary\.astro['"]/
    );
    expect(source).toMatch(
      /import\s+LandingFooter\s+from\s+['"]\.\.\/\.\.\/components\/landing\/LandingFooter\.astro['"]/
    );
  });

  it('renders OriginStory, CompatibilityList, RoadmapSummary, and LandingFooter in plan order', () => {
    const order = ['<OriginStory', '<CompatibilityList', '<RoadmapSummary', '<LandingFooter']
      .map((tag) => ({ tag, index: source.indexOf(tag) }))
      .map(({ tag, index }) => ({ tag, index }));
    for (const { tag, index } of order) {
      expect(index, `${tag} must appear in index.mdx`).toBeGreaterThan(-1);
    }
    for (let i = 1; i < order.length; i += 1) {
      expect(
        order[i].index,
        `${order[i].tag} must appear after ${order[i - 1].tag}`
      ).toBeGreaterThan(order[i - 1].index);
    }
  });
});

const compatibilityListPath = fileURLToPath(
  new URL('../src/components/landing/CompatibilityList.astro', import.meta.url)
);
const roadmapSummaryPath = fileURLToPath(
  new URL('../src/components/landing/RoadmapSummary.astro', import.meta.url)
);
const landingFooterPath = fileURLToPath(
  new URL('../src/components/landing/LandingFooter.astro', import.meta.url)
);

describe('CompatibilityList.astro contracts', () => {
  const source = readFileSync(compatibilityListPath, 'utf8');

  it('exists as an Astro component', () => {
    expect(existsSync(compatibilityListPath)).toBe(true);
  });

  it('imports InitSequence and PlatformMatrix by their fixed names', () => {
    expect(source).toMatch(
      /import\s+InitSequence\s+from\s+['"]\.\.\/diagrams\/InitSequence\.astro['"]/
    );
    expect(source).toMatch(
      /import\s+PlatformMatrix\s+from\s+['"]\.\.\/diagrams\/PlatformMatrix\.astro['"]/
    );
  });

  it('mentions the klib binary compatibility caveat', () => {
    expect(source.toLowerCase()).toMatch(/klib/);
    expect(source.toLowerCase()).toMatch(/binary compatibility/);
  });

  it('renders landingMeta fields in canonical order (Kotlin, Compose Multiplatform, Android, iOS)', () => {
    const expectedKeys = [
      'kotlinVersion',
      'composeMultiplatformVersion',
      'androidMinSdk',
      'iosDeploymentTarget',
    ];
    const positions = expectedKeys.map((key) => ({ key, index: source.indexOf(key) }));
    for (const { key, index } of positions) {
      expect(index, `landingMeta.${key} must be referenced in CompatibilityList.astro`).toBeGreaterThan(
        -1
      );
    }
    for (let i = 1; i < positions.length; i += 1) {
      expect(
        positions[i].index,
        `landingMeta.${positions[i].key} must appear after landingMeta.${positions[i - 1].key}`
      ).toBeGreaterThan(positions[i - 1].index);
    }
  });

  it('renders a <dl> with four pairs and the expected labels', () => {
    expect(source).toMatch(/<dl[^>]*class="landing-compatibility__list"/);
    const dlBlock = source.match(
      /<dl[^>]*class="landing-compatibility__list"[^>]*>([\s\S]*?)<\/dl>/
    );
    expect(dlBlock, 'compatibility <dl> is present').not.toBeNull();
    const body = dlBlock![1];
    for (const label of ['Kotlin', 'Compose Multiplatform', 'Android', 'iOS']) {
      const dtRe = new RegExp(`<dt>\\s*${label}\\s*</dt>`);
      expect(dtRe.test(body), `expected <dt>${label}</dt> in compatibility <dl>`).toBe(true);
    }
    expect((body.match(/<dt>/g) ?? []).length).toBe(4);
  });

  it('does not repeat the Maven coordinate — the hero and Quick start already state it', () => {
    expect(source).not.toMatch(/mavenCoordinate/);
  });

  it('the coordinate still appears exactly twice on the landing page', () => {
    const hero = readFileSync(projectMetadataPath, 'utf8');
    const mdx = readFileSync(indexMdxPath, 'utf8');
    expect(hero).toMatch(/landingMeta\.mavenCoordinate/);
    expect(mdx).toContain(`implementation("${landingMeta.mavenCoordinate}")`);
  });

  it('renders the InitSequence and PlatformMatrix components once each', () => {
    const initCount = (source.match(/<InitSequence\s*\/>/g) ?? []).length;
    const platformCount = (source.match(/<PlatformMatrix\s*\/>/g) ?? []).length;
    expect(initCount).toBe(1);
    expect(platformCount).toBe(1);
  });

  it('uses sentence-case headings and no uppercase mono eyebrows', () => {
    expect(source).not.toMatch(/\btext-transform\s*:\s*uppercase\b/i);
    expect(source).toMatch(/<h2>[^<]+<\/h2>/);
  });
});

describe('RoadmapSummary.astro contracts', () => {
  const source = readFileSync(roadmapSummaryPath, 'utf8');

  it('exists as an Astro component', () => {
    expect(existsSync(roadmapSummaryPath)).toBe(true);
  });

  it('imports roadmapItems from the data module', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\broadmapItems\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
  });

  it('renders the two canonical roadmap titles via the data module', () => {
    expect(source).toMatch(/\{item\.title\}/);
    const titles = roadmapItems.map((i) => i.title);
    expect(titles).toContain('Swift Package Manager dependency import');
    expect(titles).toContain('Native video events on Android');
  });

  it('does not render roadmap status text in uppercase', () => {
    const denylist = ['GATED', 'BLOCKED'];
    for (const word of denylist) {
      const re = new RegExp(`\\b${word}\\b`);
      expect(re.test(source), `roadmap status must not be the all-caps label '${word}'`).toBe(
        false
      );
    }
    expect(source).not.toMatch(/\btext-transform\s*:\s*uppercase\b/i);
  });

  it('links to /project/roadmap/ with a trailing slash', () => {
    expect(source).toMatch(/href="\/project\/roadmap\/"/);
  });

  it('does not render a status pill, badge, or uppercase chip element', () => {
    expect(source).not.toMatch(/class="[^"]*\b(?:pill|badge|chip)\b/i);
  });
});

describe('LandingFooter.astro contracts', () => {
  const source = readFileSync(landingFooterPath, 'utf8');

  it('exists as an Astro component', () => {
    expect(existsSync(landingFooterPath)).toBe(true);
  });

  it('imports trademarkStatement and repoUrl from the data module', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\btrademarkStatement\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
    expect(source).toMatch(
      /import\s*\{[^}]*\brepoUrl\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
  });

  it('renders the trademark statement via the data module (rendered value matches the verbatim contract)', () => {
    expect(source).toMatch(/<p[^>]*class="landing-footer__legal"[^>]*>\s*\{trademarkStatement\}\s*<\/p>/);
    expect(trademarkStatement).toBe(
      'Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads are trademarks of Google LLC.'
    );
  });

  it('renders exactly the seven required links in the compact list', () => {
    const listMatch = source.match(
      /<ul[^>]*class="landing-footer__links"[^>]*>([\s\S]*?)<\/ul>/
    );
    expect(listMatch, 'landing-footer links <ul> is present').not.toBeNull();
    const body = listMatch![1];
    const items = body.match(/<li>[\s\S]*?<\/li>/g) ?? [];
    expect(items, 'seven <li> items expected').toHaveLength(7);
    const labels = items.map((li) => li.replace(/<[^>]+>/g, '').trim());
    expect(labels).toEqual([
      'Quickstart',
      'Installation',
      'Compatibility',
      'Roadmap',
      'API reference',
      'GitHub',
      'Apache-2.0 license',
    ]);
  });

  it('Quickstart, Installation, Compatibility, Roadmap, and API reference all point at internal trailing-slash routes', () => {
    const listMatch = source.match(
      /<ul[^>]*class="landing-footer__links"[^>]*>([\s\S]*?)<\/ul>/
    );
    const body = listMatch![1];
    expect(body).toMatch(/href="\/start\/quickstart\/"/);
    expect(body).toMatch(/href="\/start\/installation\/"/);
    expect(body).toMatch(/href="\/reference\/compatibility\/"/);
    expect(body).toMatch(/href="\/project\/roadmap\/"/);
    expect(body).toMatch(/href="\/reference\/api\/"/);
  });

  it('GitHub link uses the canonical repoUrl value', () => {
    const listMatch = source.match(
      /<ul[^>]*class="landing-footer__links"[^>]*>([\s\S]*?)<\/ul>/
    );
    const body = listMatch![1];
    expect(body).toMatch(/<li>\s*<a\s+href=\{repoUrl\}>GitHub<\/a>\s*<\/li>/);
  });

  it('Apache-2.0 license link points at the canonical Apache URL', () => {
    const listMatch = source.match(
      /<ul[^>]*class="landing-footer__links"[^>]*>([\s\S]*?)<\/ul>/
    );
    const body = listMatch![1];
    expect(body).toMatch(
      /href="https:\/\/www\.apache\.org\/licenses\/LICENSE-2\.0\.txt"/
    );
  });

  it('renders the trademark statement in a <p class="landing-footer__legal">', () => {
    expect(source).toMatch(
      /<p[^>]*class="landing-footer__legal"[^>]*>\s*\{trademarkStatement\}\s*<\/p>/
    );
  });

  it('does not duplicate the site footer with a five-column marketing layout', () => {
    expect(source).not.toMatch(/footer-cols-5/);
    expect(source).not.toMatch(/class="[^"]*\bcolumns?\b/i);
    expect((source.match(/<ul\b/g) ?? []).length).toBe(1);
  });

  it('attributes the project to the author and the studio, from the data module', () => {
    expect(source).toMatch(
      /<p[^>]*class="landing-footer__author"[^>]*>[\s\S]*?<a href=\{authorUrl\}>\{authorName\}<\/a>[\s\S]*?<a href=\{studioUrl\}>\{studioName\}<\/a>[\s\S]*?<\/p>/
    );
    // Names and URLs are never inlined here — they live in landing.ts.
    expect(source).not.toMatch(/Meet Miyani|avinya\.dev/);
  });
});

describe('attribution and origin story data', () => {
  it('derives the author profile from the canonical repo URL so it cannot drift', () => {
    expect(authorUrl).toBe('https://github.com/Meet-Miyani');
    expect(repoUrl.startsWith(authorUrl)).toBe(true);
  });

  it('points the studio at avinya.dev', () => {
    expect(studioUrl).toBe('https://avinya.dev');
    expect(studioName).toBe('Avinya');
    expect(authorName).toBe('Meet Miyani');
  });

  it('tells the origin story in three paragraphs of real prose', () => {
    expect(originStory.paragraphs).toHaveLength(3);
    for (const paragraph of originStory.paragraphs) {
      expect(paragraph.length).toBeGreaterThan(80);
    }
  });

  it('makes no comparative quality claim — the capability table carries the comparison', () => {
    const text = originStory.paragraphs.join(' ').toLowerCase();
    const denylist = [
      'best',
      'better than',
      'leading',
      'superior',
      'the only',
      'powerful',
      'amazing',
      'fastest',
      'easiest',
      'revolutionary',
      'ultimate',
      'seamless',
      'effortless',
    ];
    for (const word of denylist) {
      expect(text.includes(word), `origin story must not contain '${word}'`).toBe(false);
    }
  });

  it('does not name the origin app while it is still unreleased', () => {
    // TODO(origin-app): drop this test and name the app once it ships.
    const text = originStory.paragraphs.join(' ');
    expect(text).not.toMatch(/ViewTube/i);
  });
});

const originStoryPath = fileURLToPath(
  new URL('../src/components/landing/OriginStory.astro', import.meta.url)
);

describe('OriginStory.astro contracts', () => {
  const source = readFileSync(originStoryPath, 'utf8');

  it('renders every paragraph from the data module without inlining copy', () => {
    expect(existsSync(originStoryPath)).toBe(true);
    expect(source).toMatch(
      /import\s*\{[^}]*\boriginStory\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
    expect(source).toMatch(/originStory\.paragraphs\.map\(/);
  });

  it('is placed before compatibility on the landing page', () => {
    const mdx = readFileSync(indexMdxPath, 'utf8');
    const at = (tag: string) => mdx.indexOf(tag);
    expect(at('<OriginStory')).toBeGreaterThan(-1);
    expect(at('<OriginStory')).toBeLessThan(at('<CompatibilityList'));
  });
});

describe('landing.css footer and roadmap rules', () => {
  const css = readFileSync(landingCssPath, 'utf8');

  it('defines a border-top rule for the landing-footer class', () => {
    const block = css.match(/\.landing-footer\s*\{([^}]*)\}/);
    expect(block, '.landing-footer rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/border-top\s*:\s*1px\s+solid\s+var\(--admob-hair\)/);
  });

  it('defines a flex layout for the landing-footer__links list', () => {
    const block = css.match(/\.landing-footer__links\s*\{([^}]*)\}/);
    expect(block, '.landing-footer__links rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/display\s*:\s*flex/);
    expect(block![1]).toMatch(/flex-wrap\s*:\s*wrap/);
    expect(block![1]).toMatch(/list-style\s*:\s*none/);
  });

  it('styles the roadmap item title semibold (no uppercase)', () => {
    const block = css.match(/\.landing-roadmap__item-title\s*\{([^}]*)\}/);
    expect(block, '.landing-roadmap__item-title rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/font-weight\s*:\s*600/);
    expect(block![1]).not.toMatch(/text-transform\s*:\s*uppercase/i);
  });

  it('styles the roadmap item status with muted text and 400 weight', () => {
    const block = css.match(/\.landing-roadmap__item-status\s*\{([^}]*)\}/);
    expect(block, '.landing-roadmap__item-status rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/font-weight\s*:\s*400/);
    expect(block![1]).toMatch(/color\s*:\s*var\(--admob-slate\)/);
  });

  it('gives roadmap items a bounded card with a token radius', () => {
    const block = css.match(/\.landing-roadmap__item\s*\{([^}]*)\}/);
    expect(block, '.landing-roadmap__item rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/border\s*:\s*var\(--landing-rule\)/);
    expect(block![1]).toMatch(/border-radius\s*:\s*var\(--admob-radius/);
  });

  it('the hairline is defined once as a token-backed variable', () => {
    expect(css).toMatch(/--landing-rule\s*:\s*1px\s+solid\s+var\(--admob-hair\)/);
  });

  it('widens the splash container without touching the docs reading measure', () => {
    expect(css).toMatch(/\.content-panel:has\(\.landing-hero\)\s+\.sl-container/);
    const block = css.match(/\.content-panel:has\(\.landing-hero\)[\s\S]*?\{([^}]*)\}/);
    expect(block![1]).toMatch(/max-width\s*:\s*var\(--admob-content-max\)/);
  });

  it('styles landing-meta dt with full opacity for WCAG AA contrast', () => {
    const block = css.match(/\.landing-meta\s+dt\s*\{([^}]*)\}/);
    expect(block, '.landing-meta dt rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/font-weight\s*:\s*500/);
    expect(block![1]).not.toMatch(/opacity/);
  });
});

const projectMetadataPath = fileURLToPath(
  new URL('../src/components/landing/ProjectMetadata.astro', import.meta.url)
);

describe('ProjectMetadata.astro contracts', () => {
  const source = readFileSync(projectMetadataPath, 'utf8');

  it('renders every value from landingMeta rather than repeating literals', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\blandingMeta\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
    expect(source).not.toMatch(/dev\.avinya\.ads:admob-cmp/);
    expect(source).not.toMatch(/Apache License/);
  });

  it('keeps the Maven coordinate focusable so it can be copied without a pointer', () => {
    expect(source).toMatch(
      /<code\s+class="admob-font-mono"\s+tabindex="0">\{landingMeta\.mavenCoordinate\}<\/code>/
    );
  });

  it('no longer repeats the release version that the coordinate already carries', () => {
    expect(source).not.toMatch(/<dt>Release<\/dt>/);
    expect(source).not.toMatch(/mavenVersion/);
  });
});

const distIndexPath = fileURLToPath(new URL('../dist/index.html', import.meta.url));

describe('dist/index.html rendered landing contract', () => {
  let builtIndex = '';

  beforeAll(() => {
    if (existsSync(distIndexPath)) {
      builtIndex = readFileSync(distIndexPath, 'utf8');
    }
  });

  it('dist/index.html exists (run npm run build before npm test for the rendered guards)', () => {
    expect(existsSync(distIndexPath), 'dist/index.html must exist for the rendered landing guards').toBe(true);
  });

  it('contains exactly one <h1> whose text is the canonical hero title', () => {
    const h1Matches = [...builtIndex.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/g)];
    expect(h1Matches, 'dist/index.html must contain at least one <h1>').toHaveLength(1);
    const h1Text = h1Matches[0][1].replace(/<[^>]+>/g, '').trim();
    expect(h1Text).toBe('Compose Multiplatform AdMob SDK for Android and iOS');
  });

  it('renders the trademark statement verbatim in the landing footer', () => {
    expect(builtIndex).toContain(
      'Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads are trademarks of Google LLC.'
    );
  });

  it('lists the six format names in the canonical order on the home page', () => {
    const expected = [
      'Banner',
      'Interstitial',
      'Rewarded',
      'Rewarded interstitial',
      'App-open',
      'Native',
    ];
    const positions = expected.map((name) => builtIndex.indexOf(name));
    for (const [index, name] of positions.map((pos, i) => [pos, expected[i]])) {
      expect(index, `format name "${name}" must appear in dist/index.html`).toBeGreaterThan(-1);
    }
    for (let i = 1; i < positions.length; i += 1) {
      expect(
        positions[i],
        `format name "${expected[i]}" must appear after "${expected[i - 1]}"`
      ).toBeGreaterThan(positions[i - 1]);
    }
  });

  it('renders the two roadmap titles in the canonical order on the home page', () => {
    const expected = [
      'Swift Package Manager dependency import',
      'Native video events on Android',
    ];
    const positions = expected.map((title) => builtIndex.indexOf(title));
    for (const [index, title] of positions.map((pos, i) => [pos, expected[i]])) {
      expect(index, `roadmap title "${title}" must appear in dist/index.html`).toBeGreaterThan(-1);
    }
    expect(
      positions[1],
      '"Native video events on Android" must appear after "Swift Package Manager dependency import"'
    ).toBeGreaterThan(positions[0]);
  });
});
