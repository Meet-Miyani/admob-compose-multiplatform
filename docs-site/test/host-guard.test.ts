/**
 * The Cloudflare Pages host guard had no tests, and it is the only thing
 * stopping Google from indexing a second, crawlable copy of the site on
 * *.pages.dev — the exact defect spec §5 records happening on avinya.dev.
 *
 * It also used to hardcode the Pages project name, which had to agree with
 * `--project-name` in .github/workflows/release.yml. When those disagreed the
 * guard did not throw: it silently fell through to the branch-preview path and
 * served the production preview host. These tests pin the behaviour that
 * replaced that coupling.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { isGeneratedApiPage, isProductionPreviewHost, onRequest } from '../functions/_middleware.js';

const CANONICAL = 'ads.avinya.dev';

/** Minimal Pages context: `next()` returns a plain 200 so headers are testable. */
function contextFor(url: string, body = '<!doctype html>') {
  return {
    request: new Request(url),
    next: async () => new Response(body, { status: 200, headers: { 'content-type': 'text/html' } }),
  };
}

describe('production preview host detection', () => {
  it('matches <project>.pages.dev for any project name', () => {
    for (const host of [
      'admob-compose-multiplatform.pages.dev',
      'admob-cmp-docs.pages.dev',
      'something-else-entirely.pages.dev',
    ]) {
      expect(isProductionPreviewHost(host), host).toBe(true);
    }
  });

  it('does not match per-deployment previews', () => {
    for (const host of [
      'abc123.admob-compose-multiplatform.pages.dev',
      'deadbeef.admob-cmp-docs.pages.dev',
      'branch-name.project.pages.dev',
    ]) {
      expect(isProductionPreviewHost(host), host).toBe(false);
    }
  });

  it('does not match the canonical domain or unrelated hosts', () => {
    for (const host of [CANONICAL, 'avinya.dev', 'example.com', 'pages.dev', 'localhost']) {
      expect(isProductionPreviewHost(host), host).toBe(false);
    }
  });
});

describe('host guard routing', () => {
  it('serves the canonical domain untouched', async () => {
    const response = await onRequest(contextFor(`https://${CANONICAL}/start/quickstart/`));
    expect(response.status).toBe(200);
    expect(response.headers.get('X-Robots-Tag')).toBeNull();
  });

  it('301s the production preview host to the canonical one, preserving the path', async () => {
    const response = await onRequest(
      contextFor('https://admob-compose-multiplatform.pages.dev/start/quickstart/')
    );
    expect(response.status).toBe(301);
    expect(response.headers.get('location')).toBe(`https://${CANONICAL}/start/quickstart/`);
  });

  it('301s regardless of what the project is named', async () => {
    const response = await onRequest(contextFor('https://renamed-later.pages.dev/formats/banner/'));
    expect(response.status).toBe(301);
    expect(response.headers.get('location')).toBe(`https://${CANONICAL}/formats/banner/`);
  });

  it('preserves the query string across the redirect', async () => {
    const response = await onRequest(contextFor('https://project.pages.dev/?q=consent'));
    expect(response.headers.get('location')).toBe(`https://${CANONICAL}/?q=consent`);
  });

  it('serves per-deployment previews but marks them noindex', async () => {
    const response = await onRequest(
      contextFor('https://abc123.admob-compose-multiplatform.pages.dev/')
    );
    expect(response.status).toBe(200);
    expect(response.headers.get('X-Robots-Tag')).toBe('noindex, nofollow');
    expect(await response.text()).toContain('<!doctype html>');
  });

  it('noindexes any other host rather than serving it bare', async () => {
    const response = await onRequest(contextFor('https://unexpected.example.com/'));
    expect(response.status).toBe(200);
    expect(response.headers.get('X-Robots-Tag')).toBe('noindex, nofollow');
  });
});

describe('generated Dokka pages are kept out of the index', () => {
  it('treats the entire /api/ tree as generated', () => {
    for (const path of ['/api/', '/api/index.html']) {
      expect(isGeneratedApiPage(path), path).toBe(true);
    }
  });

  it('treats everything below /api/ as generated', () => {
    for (const path of [
      '/api/admob-cmp-core/index.html',
      '/api/admob-cmp-compose/dev.avinya.ads.debug/-ad-debug-catalog/banner.html',
      '/api/admob-cmp/dev.avinya.ads/-ad-manager/',
    ]) {
      expect(isGeneratedApiPage(path), path).toBe(true);
    }
  });

  it('does not match authored pages, including ones merely starting with "api"', () => {
    for (const path of ['/', '/start/quickstart/', '/reference/architecture/', '/apiary/']) {
      expect(isGeneratedApiPage(path), path).toBe(false);
    }
  });

  it('serves generated pages with noindex but keeps them crawlable', async () => {
    const response = await onRequest(
      contextFor(`https://${CANONICAL}/api/admob-cmp-core/dev.avinya.ads/-ad-manager/index.html`)
    );
    expect(response.status).toBe(200);
    // `follow`, not `nofollow`: link equity must still flow, and the API
    // reference is advertised to AI crawlers in robots.txt and /llms.txt.
    expect(response.headers.get('X-Robots-Tag')).toBe('noindex, follow');
    expect(await response.text()).toContain('<!doctype html>');
  });

  it('noindexes the /api/ entry point while keeping links crawlable', async () => {
    const response = await onRequest(contextFor(`https://${CANONICAL}/api/`));
    expect(response.status).toBe(200);
    expect(response.headers.get('X-Robots-Tag')).toBe('noindex, follow');
  });
});

describe('the project name has exactly one source of truth', () => {
  const workflow = readFileSync(
    fileURLToPath(new URL('../../.github/workflows/release.yml', import.meta.url)),
    'utf8'
  );
  const middleware = readFileSync(
    fileURLToPath(new URL('../functions/_middleware.js', import.meta.url)),
    'utf8'
  );

  it('release.yml names the Pages project', () => {
    expect(workflow).toMatch(/--project-name=[a-z0-9-]+/);
  });

  it('the middleware never hardcodes a project name or a *.pages.dev host', () => {
    const code = middleware.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '');
    expect(code).not.toMatch(/[a-z0-9-]+\.pages\.dev/);
  });

  it('the canonical host still matches the configured Astro site', () => {
    const config = readFileSync(
      fileURLToPath(new URL('../astro.config.mjs', import.meta.url)),
      'utf8'
    );
    expect(config).toContain(`https://${CANONICAL}`);
    expect(middleware).toContain(`CANONICAL_HOST = '${CANONICAL}'`);
  });
});
