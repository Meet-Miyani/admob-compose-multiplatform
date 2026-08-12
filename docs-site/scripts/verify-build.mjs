#!/usr/bin/env node
/**
 * Asserts that `dist/` carries every SEO artefact this plan promised.
 * Run by `npm run verify`, which the docs-site workflow gates the deploy on.
 */
import { readFile, readdir, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

const DIST = path.resolve('dist');
const SITE = 'https://ads.avinya.dev';
const failures = [];

function check(condition, message) {
  if (condition) {
    console.log(`  ok   ${message}`);
  } else {
    console.error(`  FAIL ${message}`);
    failures.push(message);
  }
}

async function listHtmlPages(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    // The Dokka dump under /api/ is generated, not authored by Starlight.
    if (entry.isDirectory()) {
      if (entry.name === 'api' || entry.name === 'pagefind' || entry.name === '_astro') continue;
      out.push(...(await listHtmlPages(full)));
    } else if (entry.name === 'index.html') {
      out.push(full);
    }
  }
  return out;
}

console.log('sitemap');
const sitemapIndex = await readFile(path.join(DIST, 'sitemap-index.xml'), 'utf8');
check(sitemapIndex.includes(`${SITE}/sitemap-0.xml`), 'sitemap-index references sitemap-0.xml on the canonical host');
const sitemap = await readFile(path.join(DIST, 'sitemap-0.xml'), 'utf8');
const locs = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
check(locs.length >= 26, `sitemap lists ${locs.length} URLs (expected at least 26)`);
check(locs.every((l) => l.startsWith(`${SITE}/`)), 'every sitemap URL is on https://ads.avinya.dev');
check(!sitemap.includes('pages.dev'), 'sitemap contains no *.pages.dev URL');
check(locs.includes(`${SITE}/reference/api/`), 'sitemap includes the authored API reference guide');
check(!locs.some((l) => new URL(l).pathname.startsWith('/api/')), 'sitemap excludes generated Dokka URLs');
check(!locs.some((l) => l.includes('/og/')), 'sitemap excludes the /og/ image endpoints');
check(!locs.some((l) => l.includes('/docs/')), 'no URL carries a /docs/ prefix');

console.log('robots.txt');
const robots = await readFile(path.join(DIST, 'robots.txt'), 'utf8');
check(
  /^User-agent: \*\nContent-Signal: search=yes, ai-input=yes, ai-train=no, use=reference\nAllow: \/$/m.test(robots),
  'robots.txt attaches content signals to an allow-all wildcard policy'
);
check(robots.includes('search=yes, ai-input=yes, ai-train=no, use=reference'), 'robots.txt permits search and citation but not AI training');
check(/User-agent: OAI-SearchBot\nAllow: \//.test(robots), 'robots.txt allows OAI-SearchBot');
check(/User-agent: GPTBot\nDisallow: \//.test(robots), 'robots.txt blocks GPTBot training');
check(/User-agent: ClaudeBot\nDisallow: \//.test(robots), 'robots.txt blocks ClaudeBot training');
check(robots.includes(`Sitemap: ${SITE}/sitemap-index.xml`), 'robots.txt points at the sitemap index');

console.log('llms.txt');
for (const file of ['llms.txt', 'llms-full.txt', 'llms-small.txt']) {
  const full = path.join(DIST, file);
  check(existsSync(full), `${file} exists`);
  if (existsSync(full)) {
    const { size } = await stat(full);
    check(size > 500, `${file} is ${size} bytes (expected > 500)`);
  }
}
const llms = await readFile(path.join(DIST, 'llms.txt'), 'utf8');
check(llms.includes('dev.avinya.ads:admob-cmp'), 'llms.txt states the Maven coordinate');
check(llms.includes('trademarks of Google LLC'), 'llms.txt carries the trademark disclaimer');

console.log('api reference');
check(existsSync(path.join(DIST, 'api', 'index.html')), 'the Dokka reference is published at /api/');
check(existsSync(path.join(DIST, 'reference', 'api', 'index.html')), 'the authored API reference guide is published at /reference/api/');

console.log('per-page SEO');
const pages = await listHtmlPages(DIST);
check(pages.length >= 25, `${pages.length} authored pages built (expected at least 25)`);
for (const page of pages) {
  const rel = path.relative(DIST, page).replace(/index\.html$/, '');
  const html = await readFile(page, 'utf8');
  const canonicals = [...html.matchAll(/<link rel="canonical" href="([^"]+)"/g)].map((m) => m[1]);
  check(canonicals.length === 1, `${rel || '/'} has exactly one canonical link`);
  check(
    canonicals[0] === `${SITE}/${rel}`,
    `${rel || '/'} canonical is ${SITE}/${rel} (got ${canonicals[0]})`
  );
  const ogImage = html.match(/<meta property="og:image" content="([^"]+)"/)?.[1];
  check(Boolean(ogImage?.startsWith(`${SITE}/og/`)), `${rel || '/'} has an absolute og:image`);
  if (ogImage) {
    const imgPath = path.join(DIST, new URL(ogImage).pathname);
    check(existsSync(imgPath), `${rel || '/'} og:image file exists at ${path.relative(DIST, imgPath)}`);
  }
  check(html.includes('"@type":"BreadcrumbList"'), `${rel || '/'} emits BreadcrumbList JSON-LD`);
  const wantsSoftware = rel === '';
  check(
    html.includes('"@type":"SoftwareSourceCode"') === wantsSoftware,
    `${rel || '/'} ${wantsSoftware ? 'emits' : 'does not emit'} SoftwareSourceCode JSON-LD`
  );
  check(
    html.includes('"@type":"TechArticle"') === !wantsSoftware,
    `${rel || '/'} ${wantsSoftware ? 'does not emit' : 'emits'} TechArticle JSON-LD`
  );
  for (const script of [...html.matchAll(/<script type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g)]) {
    let parsed = false;
    try {
      JSON.parse(script[1]);
      parsed = true;
    } catch {
      parsed = false;
    }
    check(parsed, `${rel || '/'} JSON-LD block parses as JSON`);
  }
  check(!html.includes('Meet-Miyani/Admob-CMP'), `${rel || '/'} carries no stale repo URL`);
  check(!html.includes('class="language-mermaid"'), `${rel || '/'} has no unrendered mermaid fence`);
}

console.log('search');
check(existsSync(path.join(DIST, 'pagefind', 'pagefind.js')), 'Pagefind index was generated');

if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed.`);
  process.exit(1);
}
console.log('\nAll checks passed.');
