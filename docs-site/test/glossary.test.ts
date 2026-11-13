import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const readRepoFile = (path: string) => readFileSync(`${repoRoot}${path}`, 'utf8');
const lineContaining = (text: string, needle: string) =>
  text.split('\n').find((line) => line.includes(needle));

const glossaryPath = 'docs-site/src/content/docs/reference/glossary.mdx';

describe('API glossary', () => {
  it('is published in the Reference sidebar', () => {
    expect(existsSync(`${repoRoot}${glossaryPath}`)).toBe(true);
    expect(readRepoFile('docs-site/astro.config.mjs')).toContain("slug: 'reference/glossary'");
  });

  it('distinguishes the two placement IDs', () => {
    const glossary = readRepoFile(glossaryPath);

    expect(lineContaining(glossary, 'AdRequestOptions.placementId')).toContain('Long');
    expect(glossary).toContain('AdEvent.placementId');
  });

  it('says inactiveSessionLimit counts ads and maxInactiveSessions counts sessions', () => {
    const glossary = readRepoFile(glossaryPath);

    expect(glossary).toMatch(/^\| `inactiveSessionLimit` \| Ads \|/m);
    expect(glossary).toMatch(/^\| `maxInactiveSessions` \| Sessions \|/m);
  });

  it('maps adapterStartupWait to Google\'s "Initialization timeout"', () => {
    const line = lineContaining(readRepoFile(glossaryPath), 'adManager.adapterStartupWait');

    expect(line).toContain('Initialization timeout');
  });

  it('maps the deprecated preload() to load() in its Deprecated names table', () => {
    const line = lineContaining(readRepoFile(glossaryPath), '`FullScreenAdController.preload()`');

    expect(line).toMatch(/^\|.*`load\(\)`/);
  });
});

describe('interstitial format page', () => {
  it('does not list preload() in its surface sentence', () => {
    const line = lineContaining(
      readRepoFile('docs-site/src/content/docs/formats/interstitial.mdx'),
      '`show()`, `isReady()`'
    );

    expect(line).toBeDefined();
    expect(line).not.toContain('preload()');
  });
});

/** The body of the changelog's `## <heading>` section, up to the next `## ` heading. */
const changelogSection = (heading: string) => {
  const changelog = readRepoFile('docs-site/src/content/docs/reference/changelog.mdx');
  const marker = `\n## ${heading}\n`;
  const start = changelog.indexOf(marker);
  expect(start, `no "## ${heading}" section in the changelog`).toBeGreaterThanOrEqual(0);
  const rest = changelog.slice(start + marker.length);
  const next = rest.search(/^## /m);
  return next === -1 ? rest : rest.slice(0, next);
};

describe('changelog 2.8.0 section', () => {
  // The deprecation shipped in 2.8.0, so that is where its entry lives for good.
  it('records the preload() deprecation under ### Deprecated', () => {
    const section = changelogSection('2.8.0');

    expect(section).toContain('### Deprecated');
    expect(section).toContain('FullScreenAdController.preload()');
  });
});
