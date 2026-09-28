import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import {
  authorName,
  authorUrl,
  formats,
  landingMeta,
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

  it('every format has non-empty blurb, api and call', () => {
    for (const f of formats) {
      expect(f.blurb.length, `${f.slug} blurb must not be empty`).toBeGreaterThan(0);
      expect(f.api.length, `${f.slug} api must not be empty`).toBeGreaterThan(0);
      expect(f.call.length, `${f.slug} call must not be empty`).toBeGreaterThan(0);
    }
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

const heroPath = fileURLToPath(new URL('../src/components/Hero.astro', import.meta.url));

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

  it('renders the <h1> with the correct id and data-page-title attribute', () => {
    expect(source).toMatch(/<h1\s+id="_top"\s+data-page-title[^>]*set:html=\{title\}\s*\/>/);
    expect(source).toMatch(/const\s*\{\s*title\s*=\s*data\.title/);
  });

  it('renders frontmatter actions as anchors with landing-hero__action class', () => {
    expect(source).toMatch(/actions\.map\(/);
    expect(source).toMatch(/class:list=\{\[\s*'landing-hero__action'/);
  });

  it('hero block contains exactly one <a> element (from actions.map)', () => {
    const heroBlock = source.match(/<div class="hero landing-hero">([\s\S]*?)<div class="landing-stage"/);
    expect(heroBlock, '.hero block must exist').not.toBeNull();
    const blockContent = heroBlock![1];
    const anchorCount = (blockContent.match(/<a\b/g) ?? []).length;
    expect(anchorCount).toBe(1);
  });

  it('install code element renders the mavenCoordinate without literal string', () => {
    expect(source).toMatch(/<code\s+class="admob-font-mono landing-install__code"\s+tabindex="0">\{landingMeta\.mavenCoordinate\}<\/code>/);
    expect(source).not.toMatch(/dev\.avinya\.ads:admob-cmp/);
  });

  it('stage has aria-hidden attribute', () => {
    expect(source).toMatch(/<div class="landing-stage"\s+aria-hidden="true"/);
  });

  it('renders all seven section components in the correct order', () => {
    const components = [
      'LandingFormats',
      'LandingConsent',
      'LandingNative',
      'LandingParity',
      'LandingRoadmap',
      'LandingCta',
      'LandingFooter',
    ];
    const positions = components.map((comp) => ({ comp, index: source.indexOf(`<${comp}`) }));
    for (const { comp, index } of positions) {
      expect(index, `${comp} must be rendered`).toBeGreaterThan(-1);
    }
    for (let i = 1; i < positions.length; i++) {
      expect(positions[i].index, `${positions[i].comp} must appear after ${positions[i - 1].comp}`).toBeGreaterThan(
        positions[i - 1].index
      );
    }
  });

  it('renders CopyScript component', () => {
    expect(source).toMatch(/<CopyScript\s*\/>/);
  });
});

describe('LandingFormats.astro contracts', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../src/components/landing/LandingFormats.astro', import.meta.url)),
    'utf8'
  );

  it('imports formats from the data module', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\bformats\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
  });

  it('iterates formats.map without sort, filter or slice', () => {
    expect(source).toMatch(/formats\.map\(/);
    expect(source).not.toMatch(/\.sort\s*\(/);
    expect(source).not.toMatch(/formats\.(?:filter|slice)\s*\(/);
  });

  it('renders exactly one <ol class="landing-formats">', () => {
    expect(source.match(/<ol\s+class="landing-formats"/g) ?? []).toHaveLength(1);
  });

  it('renders anchors with href and data-format from format data', () => {
    expect(source).toMatch(/<a\s+class="landing-format__card"\s+href=\{format\.href\}\s+data-format=\{format\.slug\}>/);
  });

  it('renders format name and blurb text', () => {
    expect(source).toMatch(/\{format\.name\}/);
    expect(source).toMatch(/\{format\.blurb\}/);
  });

  it('renders format.call inside a code element with admob-font-mono class', () => {
    expect(source).toMatch(/<code[^>]*class="[^"]*\badmob-font-mono\b[^"]*[^>]*>\{format\.call\}<\/code>/);
  });

  it('hides decorative phone from assistive technology', () => {
    expect(source).toMatch(/class="landing-format__phone"\s+aria-hidden="true"/);
  });

  it('renders an illustration branch for every format slug', () => {
    for (const format of formats) {
      const pattern = new RegExp(`format\\.slug\\s*===\\s*['\"]${format.slug}['"]`);
      expect(pattern.test(source), `must have slug check for ${format.slug}`).toBe(true);
    }
  });
});

describe('LandingConsent.astro contracts', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../src/components/landing/LandingConsent.astro', import.meta.url)),
    'utf8'
  );

  it('renders the three step titles in order: Gather consent, Ask for tracking, Initialize', () => {
    const gatherIndex = source.indexOf('Gather consent');
    const askIndex = source.indexOf('Ask for tracking');
    const initIndex = source.indexOf('Initialize');
    expect(gatherIndex).toBeGreaterThan(-1);
    expect(askIndex).toBeGreaterThan(gatherIndex);
    expect(initIndex).toBeGreaterThan(askIndex);
  });

  it('renders the text "In that order."', () => {
    expect(source).toMatch(/In that order\./);
  });

  it('warning mentions IDFA', () => {
    expect(source).toMatch(/IDFA/);
  });
});

describe('LandingParity.astro contracts', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../src/components/landing/LandingParity.astro', import.meta.url)),
    'utf8'
  );

  it('links to /reference/compatibility/', () => {
    expect(source).toMatch(/href="\/reference\/compatibility\/"/);
  });

  it('table wrapper has tabindex, role and aria-label', () => {
    expect(source).toMatch(/<div\s+class="landing-parity"\s+tabindex="0"\s+role="region"\s+aria-label="[^"]+"/);
  });
});

describe('LandingRoadmap.astro contracts', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../src/components/landing/LandingRoadmap.astro', import.meta.url)),
    'utf8'
  );

  it('imports roadmapItems from the data module', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\broadmapItems\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
  });

  it('renders item title and status', () => {
    expect(source).toMatch(/\{item\.title\}/);
    expect(source).toMatch(/\{item\.status\}/);
  });

  it('links to /project/roadmap/', () => {
    expect(source).toMatch(/href="\/project\/roadmap\/"/);
  });

  it('does not use pill, badge or chip classes', () => {
    expect(source).not.toMatch(/class="[^"]*\b(?:pill|badge|chip)\b/i);
  });

  it('does not use text-transform: uppercase', () => {
    expect(source).not.toMatch(/\btext-transform\s*:\s*uppercase\b/i);
  });
});

describe('LandingCta.astro contracts', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../src/components/landing/LandingCta.astro', import.meta.url)),
    'utf8'
  );

  it('references landingMeta.mavenCoordinate without literal coordinate', () => {
    expect(source).toMatch(/landingMeta\.mavenCoordinate/);
    expect(source).not.toMatch(/dev\.avinya\.ads:admob-cmp/);
  });

  it('links to /start/quickstart/', () => {
    expect(source).toMatch(/href="\/start\/quickstart\/"/);
  });
});

describe('copy buttons contract', () => {
  const hero = readFileSync(heroPath, 'utf8');
  const cta = readFileSync(
    fileURLToPath(new URL('../src/components/landing/LandingCta.astro', import.meta.url)),
    'utf8'
  );
  const copyScript = readFileSync(
    fileURLToPath(new URL('../src/components/landing/CopyScript.astro', import.meta.url)),
    'utf8'
  );

  it('copy buttons in Hero and LandingCta carry hidden attribute', () => {
    expect((hero.match(/button[^>]*data-copy/g) ?? []).every((btn) => btn.includes('hidden'))).toBe(true);
    expect((cta.match(/button[^>]*data-copy/g) ?? []).every((btn) => btn.includes('hidden'))).toBe(true);
  });

  it('CopyScript sets hidden = false only behind navigator.clipboard check', () => {
    expect(copyScript).toMatch(/if\s*\(\s*!navigator\.clipboard/);
    expect(copyScript).toMatch(/button\.hidden\s*=\s*false/);
  });
});

const indexMdxPath = fileURLToPath(
  new URL('../src/content/docs/index.mdx', import.meta.url)
);

describe('competitor data and comparison matrix absence', () => {
  it('competitor data is absent from landing page sources', () => {
    const mdx = readFileSync(indexMdxPath, 'utf8');
    const dataTs = readFileSync(
      fileURLToPath(new URL('../src/data/landing.ts', import.meta.url)),
      'utf8'
    );
    expect(mdx).not.toMatch(/CapabilityMatrix|basic-ads|comparisonMatrix/i);
    expect(dataTs).not.toMatch(/basic-ads|comparisonMatrix|capabilityVerifiedOn|CapabilityRow/i);
  });
});

describe('index.mdx structure', () => {
  const source = readFileSync(indexMdxPath, 'utf8');

  it('frontmatter has template: splash', () => {
    expect(source).toMatch(/^template:\s*splash\s*$/m);
  });

  it('frontmatter has the canonical hero title', () => {
    expect(source).toMatch(/^hero:\s*\n\s*title:\s*Compose Multiplatform AdMob SDK for Android and iOS/m);
  });

  it('has actions linking to /start/quickstart/ and GitHub repo', () => {
    expect(source).toMatch(/link:\s*\/start\/quickstart\//);
    expect(source).toMatch(/link:\s*https:\/\/github\.com\/Meet-Miyani\/admob-compose-multiplatform/);
  });

  it('body after frontmatter has no import or component tags', () => {
    const bodyStart = source.indexOf('---', 1) + 3; // skip the opening ---
    const body = source.substring(bodyStart);
    expect(body).not.toMatch(/^import\s/m);
    expect(body).not.toMatch(/<[A-Z]/);
  });
});

const landingFooterPath = fileURLToPath(
  new URL('../src/components/landing/LandingFooter.astro', import.meta.url)
);

describe('LandingFooter.astro contracts', () => {
  const source = readFileSync(landingFooterPath, 'utf8');

  it('imports trademarkStatement and repoUrl from the data module', () => {
    expect(source).toMatch(
      /import\s*\{[^}]*\btrademarkStatement\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
    expect(source).toMatch(
      /import\s*\{[^}]*\brepoUrl\b[^}]*\}\s*from\s*['"]\.\.\/\.\.\/data\/landing(?:\.ts)?['"]/
    );
  });

  it('renders the trademark statement via the data module', () => {
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

  it('Quickstart, Installation, Compatibility, Roadmap, and API reference point at internal trailing-slash routes', () => {
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

describe('Starlight component overrides', () => {
  const config = readFileSync(
    fileURLToPath(new URL('../astro.config.mjs', import.meta.url)),
    'utf8'
  );
  const headerPath = fileURLToPath(new URL('../src/components/Header.astro', import.meta.url));
  const pageTitlePath = fileURLToPath(new URL('../src/components/PageTitle.astro', import.meta.url));
  const footerPath = fileURLToPath(new URL('../src/components/Footer.astro', import.meta.url));

  it('astro.config.mjs registers Header, PageTitle and Footer overrides', () => {
    expect(config).toMatch(/Header:\s*['"]\.\/src\/components\/Header\.astro['"]/);
    expect(config).toMatch(/PageTitle:\s*['"]\.\/src\/components\/PageTitle\.astro['"]/);
    expect(config).toMatch(/Footer:\s*['"]\.\/src\/components\/Footer\.astro['"]/);
  });

  it('Header.astro exists and contains right-group and ThemeSelect', () => {
    expect(existsSync(headerPath)).toBe(true);
    const source = readFileSync(headerPath, 'utf8');
    expect(source).toMatch(/class="right-group/);
    expect(source).toMatch(/<ThemeSelect\s*\/>/);
  });

  it('PageTitle.astro renders h1 with id and breadcrumb nav', () => {
    expect(existsSync(pageTitlePath)).toBe(true);
    const source = readFileSync(pageTitlePath, 'utf8');
    expect(source).toMatch(/<h1\s+id="_top">/);
    expect(source).toMatch(/<nav\s+aria-label="Breadcrumb"/);
  });

  it('Footer.astro renders default footer only when not landing', () => {
    expect(existsSync(footerPath)).toBe(true);
    const source = readFileSync(footerPath, 'utf8');
    expect(source).toMatch(/!isLanding\s*&&\s*<Default\s*\/>/);
  });
});

describe('attribution data', () => {
  it('derives the author profile from the canonical repo URL so it cannot drift', () => {
    expect(authorUrl).toBe('https://github.com/Meet-Miyani');
    expect(repoUrl.startsWith(authorUrl)).toBe(true);
  });

  it('points the studio at avinya.dev', () => {
    expect(studioUrl).toBe('https://avinya.dev');
    expect(studioName).toBe('Avinya');
    expect(authorName).toBe('Meet Miyani');
  });
});

describe('landing.css footer and roadmap rules', () => {
  const css = readFileSync(landingCssPath, 'utf8');

  it('defines flex display and wrap for landing-footer__links', () => {
    const block = css.match(/\.landing-footer__links\s*\{([^}]*)\}/);
    expect(block, '.landing-footer__links rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/display\s*:\s*flex/);
    expect(block![1]).toMatch(/flex-wrap\s*:\s*wrap/);
    expect(block![1]).toMatch(/list-style\s*:\s*none/);
  });

  it('gives roadmap items a top border', () => {
    const block = css.match(/\.landing-roadmap__item\s*\{([^}]*)\}/);
    expect(block, '.landing-roadmap__item rule must be present').not.toBeNull();
    expect(block![1]).toMatch(/border-top\s*:\s*1px\s+solid\s+var\(--admob-ink\)/);
  });

  it('the landing-rule token is defined as 1px solid var(--admob-hair)', () => {
    expect(css).toMatch(/--landing-rule\s*:\s*1px\s+solid\s+var\(--admob-hair\)/);
  });

  it('widens the splash container to the full content max without touching the docs reading measure', () => {
    expect(css).toMatch(/\.content-panel:has\(\.landing\)\s*\.sl-container/);
    const block = css.match(/\.content-panel:has\(\.landing\)[\s\S]*?\{([^}]*)\}/);
    expect(block![1]).toMatch(/max-width\s*:\s*var\(--admob-content-max\)/);
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
    expect(h1Matches, 'dist/index.html must contain exactly one <h1>').toHaveLength(1);
    const h1Text = h1Matches[0][1].replace(/<[^>]+>/g, '').trim();
    expect(h1Text).toBe('Compose Multiplatform AdMob SDK for Android and iOS');
  });

  it('renders the trademark statement verbatim', () => {
    expect(builtIndex).toContain(
      'Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads are trademarks of Google LLC.'
    );
  });

  it('lists the six format names in canonical order', () => {
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
      expect(index, `format name "${name}" must appear`).toBeGreaterThan(-1);
    }
    for (let i = 1; i < positions.length; i += 1) {
      expect(
        positions[i],
        `format name "${expected[i]}" must appear after "${expected[i - 1]}"`
      ).toBeGreaterThan(positions[i - 1]);
    }
  });

  it('renders the two roadmap titles in canonical order', () => {
    const expected = [
      'Swift Package Manager dependency import',
      'Native video events on Android',
    ];
    const positions = expected.map((title) => builtIndex.indexOf(title));
    for (const [index, title] of positions.map((pos, i) => [pos, expected[i]])) {
      expect(index, `roadmap title "${title}" must appear`).toBeGreaterThan(-1);
    }
    expect(
      positions[1],
      '"Native video events on Android" must appear after "Swift Package Manager dependency import"'
    ).toBeGreaterThan(positions[0]);
  });
});

const landingFormatsPath = fileURLToPath(
  new URL('../src/components/landing/LandingFormats.astro', import.meta.url)
);

describe('landing motion contracts', () => {
  const landingFormatsSource = readFileSync(landingFormatsPath, 'utf8');
  const heroSource = readFileSync(heroPath, 'utf8');
  const css = readFileSync(landingCssPath, 'utf8');

  it('LandingFormats.astro script defines STORY_MS = 4800', () => {
    expect(landingFormatsSource).toMatch(/const\s+STORY_MS\s*=\s*4800/);
  });

  it('LandingFormats.astro script checks prefers-reduced-motion and disables when matched', () => {
    expect(landingFormatsSource).toMatch(
      /const\s+reduce\s*=\s*window\.matchMedia\s*\(\s*['"]?\(prefers-reduced-motion:\s*reduce\)['"]?\s*\)/
    );
    expect(landingFormatsSource).toMatch(/if\s*\(\s*[^)]*!reduce\.matches/);
  });

  it('LandingFormats.astro script uses IntersectionObserver to track visibility', () => {
    expect(landingFormatsSource).toMatch(/new\s+IntersectionObserver/);
    expect(landingFormatsSource).toMatch(/\.observe\s*\(\s*list\s*\)/);
  });

  it('LandingFormats.astro script toggles is-playing class on cards', () => {
    expect(landingFormatsSource).toMatch(/classList\.toggle\s*\(\s*['"]is-playing['"]/);
  });

  it('LandingFormats.astro script stops on visibilitychange', () => {
    expect(landingFormatsSource).toMatch(/addEventListener\s*\(\s*['"]visibilitychange['"]/);
    expect(landingFormatsSource).toMatch(/document\.hidden\s*\?\s*stop\s*\(\)\s*:\s*start\s*\(\)/);
  });

  it('LandingFormats.astro script holds sequence on pointerenter/focusin and resumes on pointerleave/focusout', () => {
    expect(landingFormatsSource).toMatch(/addEventListener\s*\(\s*['"]pointerenter['"]/);
    expect(landingFormatsSource).toMatch(/addEventListener\s*\(\s*['"]pointerleave['"]/);
    expect(landingFormatsSource).toMatch(/addEventListener\s*\(\s*['"]focusin['"]/);
    expect(landingFormatsSource).toMatch(/addEventListener\s*\(\s*['"]focusout['"]/);
  });

  it('story duration 4.8s in CSS equals 4800ms in script', () => {
    const scriptMs = landingFormatsSource.match(/STORY_MS\s*=\s*(\d+)/);
    const cssSeconds = css.match(/4\.8s/);
    expect(scriptMs, 'STORY_MS must be defined').not.toBeNull();
    expect(cssSeconds, '4.8s animation duration must be present').not.toBeNull();
    const ms = parseInt(scriptMs![1], 10);
    const s = 4.8;
    expect(ms / 1000).toBe(s);
  });

  it('animation rules inside @media (prefers-reduced-motion: no-preference) guard', () => {
    const guardMatch = css.match(
      /@media\s*\(\s*prefers-reduced-motion:\s*no-preference\s*\)\s*\{\s*\/\*\s*stage[\s\S]*?\n\}/
    );
    expect(guardMatch, 'prefers-reduced-motion guard with animation rules must exist').not.toBeNull();
    const guardBody = guardMatch![0];

    // Check for required stage animation selectors
    expect(guardBody).toMatch(/\.landing-device__rows/);
    expect(guardBody).toMatch(/\.landing-device__sheen/);
    expect(guardBody).toMatch(/\.landing-code__line--placed::before/);
    expect(guardBody).toMatch(/\.landing-stage__bridge\s+svg/);

    // Check for required format-story animation selectors
    expect(guardBody).toMatch(/\.landing-format__card:is\([^)]*is-playing/);
  });

  it('no animation naming landing-story-, landing-feed-, landing-banner-sheen, landing-placed or landing-nudge outside no-preference guard', () => {
    // Find the main @media (prefers-reduced-motion: no-preference) block (not @supports)
    const mediaMatch = css.match(
      /@media\s*\(\s*prefers-reduced-motion:\s*no-preference\s*\)\s*\{\s*\/\*\s*stage[\s\S]*?\n\}/
    );
    expect(mediaMatch, 'prefers-reduced-motion: no-preference block must exist').not.toBeNull();

    // Get everything except @keyframes definitions and this guard
    let outside = css;

    // Remove @keyframes blocks
    outside = outside.replace(/@keyframes[\s\S]*?\{[\s\S]*?\}/g, '');

    // Remove the main prefers-reduced-motion guard
    outside = outside.replace(mediaMatch![0], '');

    // Remove other @media/@supports blocks
    outside = outside.replace(/@media[\s\S]*?\{[\s\S]*?\n\}/g, '');
    outside = outside.replace(/@supports[\s\S]*?\{[\s\S]*?\}/g, '');

    // Patterns for animations that should only appear inside guard
    const patterns = [
      /animation:\s*landing-story-/g,
      /animation:\s*landing-feed-/g,
      /animation:\s*landing-banner-sheen/g,
      /animation:\s*landing-placed/g,
      /animation:\s*landing-nudge/g
    ];

    const violations: string[] = [];
    for (const pattern of patterns) {
      const matches = outside.match(pattern) || [];
      if (matches.length > 0) {
        violations.push(`found ${matches.length} ${pattern.source}`);
      }
    }

    expect(violations, violations.length > 0 ? violations.join('; ') : undefined).toHaveLength(0);
  });

  it('banner format card has landing-story__feed--scroll and landing-story__banner', () => {
    const bannerMatch = landingFormatsSource.match(
      /\{format\.slug\s*===\s*['"]banner['"]\s*&&\s*\(([\s\S]*?)\)\s*\}\s*\{format\.slug/
    );
    expect(bannerMatch, 'banner format branch must exist').not.toBeNull();
    const bannerBranch = bannerMatch![1];
    expect(bannerBranch).toMatch(/landing-story__feed--scroll/);
    expect(bannerBranch).toMatch(/landing-story__banner/);
  });

  it('interstitial format card has landing-story__takeover--dismissible and landing-story__tap', () => {
    const interstitialMatch = landingFormatsSource.match(
      /\{format\.slug\s*===\s*['"]interstitial['"]\s*&&\s*\(([\s\S]*?)\)\s*\}\s*\{format\.slug/
    );
    expect(interstitialMatch, 'interstitial format branch must exist').not.toBeNull();
    const interstitialBranch = interstitialMatch![1];
    expect(interstitialBranch).toMatch(/landing-story__takeover--dismissible/);
    expect(interstitialBranch).toMatch(/landing-story__tap/);
  });

  it('rewarded format card has four landing-story__time-- spans, landing-story__fill and landing-story__reward', () => {
    const rewardedMatch = landingFormatsSource.match(
      /\{format\.slug\s*===\s*['"]rewarded['"]\s*&&\s*\(([\s\S]*?)\)\s*\}\s*\{format\.slug/
    );
    expect(rewardedMatch, 'rewarded format branch must exist').not.toBeNull();
    const rewardedBranch = rewardedMatch![1];
    expect(rewardedBranch).toMatch(/landing-story__time--1/);
    expect(rewardedBranch).toMatch(/landing-story__time--2/);
    expect(rewardedBranch).toMatch(/landing-story__time--3/);
    expect(rewardedBranch).toMatch(/landing-story__time--4/);
    expect(rewardedBranch).toMatch(/landing-story__fill/);
    expect(rewardedBranch).toMatch(/landing-story__reward/);
  });

  it('rewarded-interstitial format card has three landing-story__count-step-- spans, landing-story__skip and landing-story__full', () => {
    const riMatch = landingFormatsSource.match(
      /\{format\.slug\s*===\s*['"]rewarded-interstitial['"]\s*&&\s*\(([\s\S]*?)\)\s*\}\s*\{format\.slug/
    );
    expect(riMatch, 'rewarded-interstitial format branch must exist').not.toBeNull();
    const riBranch = riMatch![1];
    expect(riBranch).toMatch(/landing-story__count-step--3/);
    expect(riBranch).toMatch(/landing-story__count-step--2/);
    expect(riBranch).toMatch(/landing-story__count-step--1/);
    expect(riBranch).toMatch(/landing-story__skip/);
    expect(riBranch).toMatch(/landing-story__full/);
  });

  it('app-open format card has landing-story__home, landing-story__app and landing-story__splash', () => {
    const appOpenMatch = landingFormatsSource.match(
      /\{format\.slug\s*===\s*['"]app-open['"]\s*&&\s*\(([\s\S]*?)\)\s*\}\s*\{format\.slug/
    );
    expect(appOpenMatch, 'app-open format branch must exist').not.toBeNull();
    const appOpenBranch = appOpenMatch![1];
    expect(appOpenBranch).toMatch(/landing-story__home/);
    expect(appOpenBranch).toMatch(/landing-story__app/);
    expect(appOpenBranch).toMatch(/landing-story__splash/);
  });

  it('native format card has landing-story__feed--native and landing-story__sheen--media', () => {
    // Find native format branch
    const startIdx = landingFormatsSource.indexOf("format.slug === 'native'");
    expect(startIdx, 'native format branch must start').toBeGreaterThan(-1);

    // Check section from native start
    const nativeSection = landingFormatsSource.substring(startIdx, startIdx + 2000);
    expect(nativeSection).toMatch(/landing-story__feed--native/);
    expect(nativeSection).toMatch(/landing-story__sheen--media/);
  });

  it('Hero.astro renders landing-device__rows for feed scrolling', () => {
    expect(heroSource).toMatch(/class="landing-device__rows"/);
  });

  it('Hero.astro builds feedRows as three copies of rows for seamless looping', () => {
    expect(heroSource).toMatch(/const\s+feedRows\s*=\s*\[\.\.\.rows,\s*\.\.\.rows,\s*\.\.\.rows\]/);
  });

  it('Hero.astro marks placed code line via landing-code__line--placed from BannerAdView token', () => {
    expect(heroSource).toMatch(/['"]BannerAdView['"]/);
    expect(heroSource).toMatch(/landing-code__line--placed/);
    expect(heroSource).toMatch(/i\s*===\s*placedFull/);
  });

  it('Hero.astro renders landing-device__sheen in each device banner (two occurrences)', () => {
    const sheenMatches = heroSource.match(/class="landing-device__sheen"/g) || [];
    expect(sheenMatches.length, 'landing-device__sheen must appear twice (Android and iOS banners)').toBe(2);
  });
});
