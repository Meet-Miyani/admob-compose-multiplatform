import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import * as seo from '../src/lib/seo';
import {
  breadcrumbListJsonLd,
  normalizeEntryId,
  ogImagePath,
  softwareSourceCodeJsonLd,
  techArticleJsonLd,
} from '../src/lib/seo';

const SITE = 'https://ads.avinya.dev/';
const REPO = 'https://github.com/Meet-Miyani/admob-compose-multiplatform';

describe('normalizeEntryId', () => {
  it('maps the root entry to "index"', () => {
    expect(normalizeEntryId('')).toBe('index');
    expect(normalizeEntryId('index')).toBe('index');
  });

  it('leaves nested ids untouched', () => {
    expect(normalizeEntryId('start/quickstart')).toBe('start/quickstart');
  });
});

describe('ogImagePath', () => {
  it('builds a .png path under /og/', () => {
    expect(ogImagePath('start/quickstart')).toBe('/og/start/quickstart.png');
    expect(ogImagePath('')).toBe('/og/index.png');
  });
});

describe('breadcrumbListJsonLd', () => {
  it('emits Home only for the landing page', () => {
    const ld = breadcrumbListJsonLd('/', 'AdMob CMP', SITE) as any;
    expect(ld['@type']).toBe('BreadcrumbList');
    expect(ld.itemListElement).toHaveLength(1);
    expect(ld.itemListElement[0]).toEqual({
      '@type': 'ListItem',
      position: 1,
      name: 'Home',
      item: 'https://ads.avinya.dev/',
    });
  });

  it('expands section directories into readable crumbs', () => {
    const ld = breadcrumbListJsonLd('/formats/app-open/', 'App-open ads', SITE) as any;
    expect(ld.itemListElement).toEqual([
      { '@type': 'ListItem', position: 1, name: 'Home', item: 'https://ads.avinya.dev/' },
      {
        '@type': 'ListItem',
        position: 2,
        name: 'Ad formats',
        item: 'https://ads.avinya.dev/formats/',
      },
      {
        '@type': 'ListItem',
        position: 3,
        name: 'App-open ads',
        item: 'https://ads.avinya.dev/formats/app-open/',
      },
    ]);
  });
});

describe('techArticleJsonLd', () => {
  it('describes the page and names the publisher', () => {
    const ld = techArticleJsonLd({
      url: 'https://ads.avinya.dev/formats/native/',
      title: 'Native ads',
      description: 'The adLayout DSL.',
      siteUrl: SITE,
      dateModified: '2026-07-31T00:00:00.000Z',
    }) as any;
    expect(ld['@type']).toBe('TechArticle');
    expect(ld.headline).toBe('Native ads');
    expect(ld.mainEntityOfPage['@id']).toBe('https://ads.avinya.dev/formats/native/');
    expect(ld.dateModified).toBe('2026-07-31T00:00:00.000Z');
    expect(ld.publisher.name).toBe('Avinya');
  });

  it('omits dateModified when it is unknown', () => {
    const ld = techArticleJsonLd({
      url: 'https://ads.avinya.dev/formats/native/',
      title: 'Native ads',
      description: 'The adLayout DSL.',
      siteUrl: SITE,
    }) as any;
    expect('dateModified' in ld).toBe(false);
  });
});

describe('softwareSourceCodeJsonLd', () => {
  it('declares the Kotlin library and its repository', () => {
    const ld = softwareSourceCodeJsonLd(SITE, REPO) as any;
    expect(ld['@type']).toBe('SoftwareSourceCode');
    expect(ld.programmingLanguage).toBe('Kotlin');
    expect(ld.codeRepository).toBe(REPO);
    expect(ld.runtimePlatform).toEqual(['Android', 'iOS']);
    expect(ld.license).toBe('https://www.apache.org/licenses/LICENSE-2.0.txt');
  });
});

describe('FAQPage structured data removal', () => {
  it('does not export faqPageJsonLd from seo module', () => {
    expect((seo as any).faqPageJsonLd).toBeUndefined();
  });
});

/**
 * Needs `npm run build` first, like the rendered guards in landing.test.ts.
 *
 * `title` carries the SERP keywords and is deliberately long, so the breadcrumb
 * trail must fall back to the short `sidebar.label` instead. Without this,
 * a trail reads "Home > Start here > AdMob Quickstart for Compose Multiplatform".
 */
describe('breadcrumb labels stay short while headlines keep the keywords', () => {
  const cases = [
    { page: 'start/quickstart', crumb: 'Quickstart', headline: 'Quickstart: Compose Multiplatform AdMob Integration' },
    { page: 'privacy/consent', crumb: 'UMP consent', headline: 'UMP consent for AdMob on Kotlin Multiplatform' },
    { page: 'reference/diagrams-in-words', crumb: 'Diagrams in words', headline: 'Architecture diagrams described in words' },
  ];

  function structuredData(page: string): Record<string, any>[] {
    const html = readFileSync(
      fileURLToPath(new URL(`../dist/${page}/index.html`, import.meta.url)),
      'utf8'
    );
    return [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map(
      (match) => JSON.parse(match[1])
    );
  }

  for (const { page, crumb, headline } of cases) {
    it(`${page} uses the sidebar label in its breadcrumb`, () => {
      const blocks = structuredData(page);
      const breadcrumb = blocks.find((block) => block['@type'] === 'BreadcrumbList');
      expect(breadcrumb, `${page} must emit a BreadcrumbList`).toBeDefined();

      const names = breadcrumb!.itemListElement.map((item: { name: string }) => item.name);
      expect(names.at(-1)).toBe(crumb);
    });

    it(`${page} keeps the full keyword title as the TechArticle headline`, () => {
      const article = structuredData(page).find((block) => block['@type'] === 'TechArticle');
      expect(article, `${page} must emit a TechArticle`).toBeDefined();
      expect(article!.headline).toBe(headline);
    });
  }
});
