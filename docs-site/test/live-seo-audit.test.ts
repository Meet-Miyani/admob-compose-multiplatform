import { describe, expect, it } from 'vitest';
import { auditProductionSeo } from '../scripts/audit-live-seo.mjs';

function response(body = '', init: ResponseInit = {}): Response {
  return new Response(body, { status: 200, ...init });
}

describe('production SEO audit', () => {
  it('accepts the canonical host, citation crawler policy, sitemap, and API noindex contract', async () => {
    const seen = new Set<string>();
    const fetchImpl = async (input: string | URL | Request) => {
      const url = String(input);
      seen.add(url);
      if (url === 'http://ads.avinya.dev/') {
        return response('', { status: 301, headers: { location: 'https://ads.avinya.dev/' } });
      }
      if (url === 'https://admob-compose-multiplatform.pages.dev/') {
        return response('', { status: 301, headers: { location: 'https://ads.avinya.dev/' } });
      }
      if (url === 'https://ads.avinya.dev/robots.txt') {
        return response([
          'User-agent: *',
          'Content-Signal: search=yes, ai-input=yes, ai-train=no, use=reference',
          'Allow: /',
          'User-agent: Googlebot',
          'Allow: /',
          'User-agent: Bingbot',
          'Allow: /',
          'User-agent: Applebot',
          'Allow: /',
          'User-agent: OAI-SearchBot',
          'Allow: /',
          'User-agent: ChatGPT-User',
          'Allow: /',
          'User-agent: Claude-SearchBot',
          'Allow: /',
          'User-agent: Claude-User',
          'Allow: /',
          'User-agent: PerplexityBot',
          'Allow: /',
          'User-agent: Perplexity-User',
          'Allow: /',
          'User-agent: GPTBot',
          'Disallow: /',
          'User-agent: ClaudeBot',
          'Disallow: /',
          'User-agent: anthropic-ai',
          'Disallow: /',
          'User-agent: CCBot',
          'Disallow: /',
          'User-agent: Bytespider',
          'Disallow: /',
          'User-agent: Applebot-Extended',
          'Disallow: /',
          'User-agent: Google-Extended',
          'Disallow: /',
          'Sitemap: https://ads.avinya.dev/sitemap-index.xml',
        ].join('\n'));
      }
      if (url === 'https://ads.avinya.dev/sitemap-index.xml') {
        return response('<loc>https://ads.avinya.dev/sitemap-0.xml</loc>');
      }
      if (url === 'https://ads.avinya.dev/sitemap-0.xml') {
        return response([
          '<loc>https://ads.avinya.dev/</loc>',
          '<loc>https://ads.avinya.dev/reference/api/</loc>',
        ].join('\n'));
      }
      if (url === 'https://ads.avinya.dev/') {
        return response('<title>Compose Multiplatform AdMob SDK | AdMob CMP</title><link rel="canonical" href="https://ads.avinya.dev/"/>');
      }
      if (url === 'https://ads.avinya.dev/reference/api/') {
        return response('<title>AdMob CMP API Reference for Kotlin Multiplatform | AdMob CMP</title><link rel="canonical" href="https://ads.avinya.dev/reference/api/"/>');
      }
      if (url === 'https://ads.avinya.dev/api/') {
        return response('', { headers: { 'X-Robots-Tag': 'noindex, follow' } });
      }
      if (url === 'https://ads.avinya.dev/api/admob-cmp-core/') {
        return response('', { headers: { 'X-Robots-Tag': 'noindex, follow' } });
      }
      throw new Error(`Unexpected URL: ${url}`);
    };

    await expect(auditProductionSeo(fetchImpl)).resolves.toEqual([]);
    expect(seen).toEqual(new Set([
      'http://ads.avinya.dev/',
      'https://admob-compose-multiplatform.pages.dev/',
      'https://ads.avinya.dev/robots.txt',
      'https://ads.avinya.dev/sitemap-index.xml',
      'https://ads.avinya.dev/sitemap-0.xml',
      'https://ads.avinya.dev/',
      'https://ads.avinya.dev/reference/api/',
      'https://ads.avinya.dev/api/',
      'https://ads.avinya.dev/api/admob-cmp-core/',
    ]));
  });

  it('reports Cloudflare managed robots content and an indexable Dokka entry point', async () => {
    const fetchImpl = async (input: string | URL | Request) => {
      const url = String(input);
      if (url === 'http://ads.avinya.dev/' || url.includes('pages.dev')) {
        return response('', { status: 301, headers: { location: 'https://ads.avinya.dev/' } });
      }
      if (url.endsWith('/robots.txt')) {
        return response([
          '# BEGIN Cloudflare Managed content',
          'User-agent: anthropic-ai',
          'Allow: /',
          'User-agent: anthropic-ai',
          'Disallow: /',
          'User-agent: GPTBot',
          'Disallow: /',
        ].join('\n'));
      }
      if (url.endsWith('/sitemap-index.xml')) {
        return response('<loc>https://ads.avinya.dev/sitemap-0.xml</loc>');
      }
      if (url === 'https://ads.avinya.dev/') {
        return response('<title>AdMob CMP</title><link rel="canonical" href="https://ads.avinya.dev/">');
      }
      return response();
    };

    const failures = await auditProductionSeo(fetchImpl);
    expect(failures).toContain('robots.txt contains Cloudflare managed content');
    expect(failures).toContain('robots.txt does not attach content signals to the wildcard policy');
    expect(failures).toContain('robots.txt gives anthropic-ai multiple policies');
    expect(failures).toContain('robots.txt does not allow Claude-SearchBot');
    expect(failures).toContain('robots.txt does not allow PerplexityBot');
    expect(failures).toContain('robots.txt does not block ClaudeBot');
    expect(failures).toContain('robots.txt does not block anthropic-ai');
    expect(failures).toContain('robots.txt does not block Google-Extended');
    expect(failures).toContain('sitemap does not include the authored API reference');
    expect(failures).toContain('authored API reference canonical link is missing or incorrect');
    expect(failures).toContain('/api/ is missing X-Robots-Tag: noindex, follow');
    expect(failures).toContain('nested Dokka page is missing X-Robots-Tag: noindex, follow');
  });
});
