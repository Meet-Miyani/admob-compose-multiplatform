#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

const SITE = 'https://ads.avinya.dev';
const PREVIEW = 'https://admob-compose-multiplatform.pages.dev';

async function get(fetchImpl, url, redirect = 'follow') {
  return fetchImpl(url, { redirect, headers: { 'user-agent': 'AdMob-CMP-SEO-Audit/1.0' } });
}

function namedAgentPolicies(robots) {
  const policies = new Map();
  const lines = robots.split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const match = lines[index].match(/^User-agent:\s*(\S+)$/i);
    if (!match || match[1] === '*') continue;
    const directives = [];
    for (let next = index + 1; next < lines.length && !/^User-agent:/i.test(lines[next]); next += 1) {
      if (/^(?:Allow|Disallow):/i.test(lines[next])) directives.push(lines[next]);
    }
    policies.set(match[1], [...(policies.get(match[1]) ?? []), ...directives]);
  }
  return policies;
}

function hasCanonical(html, url) {
  const escaped = url.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`<link\\s+rel=["']canonical["']\\s+href=["']${escaped}["']\\s*/?>`, 'i').test(html);
}

export async function auditProductionSeo(fetchImpl = fetch) {
  const failures = [];
  const check = (condition, message) => {
    if (!condition) failures.push(message);
  };

  const [
    httpHome,
    previewHome,
    robotsResponse,
    sitemapResponse,
    sitemapPagesResponse,
    homeResponse,
    referenceApiResponse,
    apiResponse,
    nestedApiResponse,
  ] =
    await Promise.all([
      get(fetchImpl, 'http://ads.avinya.dev/', 'manual'),
      get(fetchImpl, `${PREVIEW}/`, 'manual'),
      get(fetchImpl, `${SITE}/robots.txt`),
      get(fetchImpl, `${SITE}/sitemap-index.xml`),
      get(fetchImpl, `${SITE}/sitemap-0.xml`),
      get(fetchImpl, `${SITE}/`),
      get(fetchImpl, `${SITE}/reference/api/`),
      get(fetchImpl, `${SITE}/api/`),
      get(fetchImpl, `${SITE}/api/admob-cmp-core/`),
    ]);

  check([301, 308].includes(httpHome.status), 'HTTP homepage does not permanently redirect');
  check(httpHome.headers.get('location') === `${SITE}/`, 'HTTP homepage redirect does not target the canonical HTTPS URL');
  check([301, 308].includes(previewHome.status), 'pages.dev production host does not permanently redirect');
  check(previewHome.headers.get('location') === `${SITE}/`, 'pages.dev redirect does not target the canonical host');

  const robots = await robotsResponse.text();
  check(robotsResponse.ok, 'robots.txt did not return 200');
  check(!/Cloudflare Managed|BEGIN managed content/i.test(robots), 'robots.txt contains Cloudflare managed content');
  check(
    robots.includes('Content-Signal: search=yes, ai-input=yes, ai-train=no, use=reference'),
    'robots.txt does not declare the citation-allowed, training-blocked policy'
  );
  check(
    /User-agent: \*\nContent-Signal: search=yes, ai-input=yes, ai-train=no, use=reference\nAllow: \//i.test(robots),
    'robots.txt does not attach content signals to the wildcard policy'
  );
  const policies = namedAgentPolicies(robots);
  const allowedAgents = [
    'Googlebot',
    'Bingbot',
    'Applebot',
    'OAI-SearchBot',
    'ChatGPT-User',
    'Claude-SearchBot',
    'Claude-User',
    'PerplexityBot',
    'Perplexity-User',
  ];
  const blockedAgents = [
    'GPTBot',
    'ClaudeBot',
    'anthropic-ai',
    'CCBot',
    'Bytespider',
    'Applebot-Extended',
    'Google-Extended',
  ];
  for (const agent of allowedAgents) {
    check(policies.get(agent)?.length === 1, `robots.txt gives ${agent} multiple policies`);
    check(policies.get(agent)?.[0] === 'Allow: /', `robots.txt does not allow ${agent}`);
  }
  for (const agent of blockedAgents) {
    check(policies.get(agent)?.length === 1, `robots.txt gives ${agent} multiple policies`);
    check(policies.get(agent)?.[0] === 'Disallow: /', `robots.txt does not block ${agent}`);
  }
  check(robots.includes(`Sitemap: ${SITE}/sitemap-index.xml`), 'robots.txt does not advertise the canonical sitemap');

  const sitemapIndex = await sitemapResponse.text();
  check(sitemapResponse.ok, 'sitemap index did not return 200');
  check(sitemapIndex.includes(`${SITE}/sitemap-0.xml`), 'sitemap index does not reference the canonical sitemap');
  const sitemapPages = await sitemapPagesResponse.text();
  check(sitemapPagesResponse.ok, 'page sitemap did not return 200');
  check(sitemapPages.includes(`${SITE}/reference/api/`), 'sitemap does not include the authored API reference');
  check(!/<loc>https:\/\/ads\.avinya\.dev\/api\//.test(sitemapPages), 'sitemap includes a generated Dokka URL');

  const home = await homeResponse.text();
  check(homeResponse.ok, 'canonical homepage did not return 200');
  check(/<title>[^<]*Compose Multiplatform AdMob SDK[^<]*<\/title>/i.test(home), 'homepage title is missing the primary Compose Multiplatform AdMob keyword');
  check(
    hasCanonical(home, `${SITE}/`),
    'homepage canonical link is missing or incorrect'
  );

  const referenceApi = await referenceApiResponse.text();
  check(referenceApiResponse.ok, 'authored API reference did not return 200');
  check(
    hasCanonical(referenceApi, `${SITE}/reference/api/`),
    'authored API reference canonical link is missing or incorrect'
  );

  check(apiResponse.ok, '/api/ did not return 200');
  check(apiResponse.headers.get('X-Robots-Tag') === 'noindex, follow', '/api/ is missing X-Robots-Tag: noindex, follow');
  check(nestedApiResponse.ok, 'nested Dokka page did not return 200');
  check(
    nestedApiResponse.headers.get('X-Robots-Tag') === 'noindex, follow',
    'nested Dokka page is missing X-Robots-Tag: noindex, follow'
  );

  return failures;
}

async function main() {
  const failures = await auditProductionSeo();
  if (failures.length > 0) {
    for (const failure of failures) console.error(`FAIL ${failure}`);
    console.error(`\nLIVE SEO AUDIT: FAIL (${failures.length} checks)`);
    process.exitCode = 1;
    return;
  }
  console.log('LIVE SEO AUDIT: PASS');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
