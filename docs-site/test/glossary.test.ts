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

describe('changelog Unreleased section', () => {
  it('records the preload() deprecation under ### Deprecated', () => {
    const changelog = readRepoFile('docs-site/src/content/docs/reference/changelog.mdx');
    const start = changelog.indexOf('\n## Unreleased');
    expect(start).toBeGreaterThanOrEqual(0);
    const rest = changelog.slice(start + 1);
    const next = rest.slice('## Unreleased'.length).search(/^## /m);
    const section = next === -1 ? rest : rest.slice(0, next + '## Unreleased'.length);

    expect(section).toContain('### Deprecated');
    expect(section).toContain('FullScreenAdController.preload()');
  });
});
