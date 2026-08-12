/**
 * Host guard for the AdMob CMP docs site.
 *
 * Cloudflare Pages answers on three kinds of host:
 *   1. ads.avinya.dev                — the canonical custom domain. Serve.
 *   2. <project>.pages.dev           — the production preview host. 301 away, so
 *                                      Google never has two crawlable copies of
 *                                      the site to choose between.
 *   3. <hash>.<project>.pages.dev    — per-deployment previews. Serve them (they
 *                                      exist to be reviewed) but mark them
 *                                      noindex so they cannot be indexed.
 *
 * Spec §5 documents exactly this failure on avinya.dev: every canonical pointed
 * at avinya.pages.dev, which returned HTTP 200, so Google was told to prefer the
 * throwaway host. This file makes that impossible here.
 *
 * The Pages project name is deliberately NOT hardcoded. It used to be, and it
 * had to agree with `--project-name` in .github/workflows/release.yml — two
 * files, no shared source of truth. When they disagreed the guard did not error;
 * it silently fell through to case 3 and *served* the production preview host,
 * which is the duplicate-copy defect this file exists to prevent. Cases 2 and 3
 * are distinguishable by label count alone, so the name is not needed:
 *
 *   <project>.pages.dev          -> 3 labels
 *   <hash>.<project>.pages.dev   -> 4 labels
 *
 * Renaming the Pages project therefore cannot desync this file again.
 */

const CANONICAL_HOST = 'ads.avinya.dev';
const PAGES_SUFFIX = '.pages.dev';
const PRODUCTION_PREVIEW_LABEL_COUNT = 3;
const API_PREFIX = '/api/';

/** True for `<project>.pages.dev`, false for `<hash>.<project>.pages.dev`. */
export function isProductionPreviewHost(hostname) {
  if (!hostname.endsWith(PAGES_SUFFIX)) return false;
  return hostname.split('.').length === PRODUCTION_PREVIEW_LABEL_COUNT;
}

/**
 * True for every generated Dokka page under `/api/`, including its module
 * index. The authored, indexable entry point lives at `/reference/api/`.
 *
 * The Dokka dump is ~940 HTML files against 27 authored pages, so it is the
 * overwhelming majority of the crawlable surface — and it is machine output:
 * no canonical, no meta description, a median of ~58 body words, and titles
 * like `<title>valueOf</title>` repeated two dozen times. Left indexable it
 * dilutes the host's quality signals and lets a 40-word stub outrank the guide
 * that actually answers the query.
 *
 * `noindex, follow` rather than `nofollow`: these pages stay crawlable, so link
 * equity still flows through them and tools can read the complete declaration
 * reference. The dump is generated at release time and is not in the
 * repository, so this header is the only place the rule can live.
 */
export function isGeneratedApiPage(pathname) {
  return pathname.startsWith(API_PREFIX);
}

/** Response headers are immutable, so a tagged copy is the only way to set one. */
function withRobotsTag(response, value) {
  const headers = new Headers(response.headers);
  headers.set('X-Robots-Tag', value);
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export async function onRequest(context) {
  const url = new URL(context.request.url);

  if (url.hostname === CANONICAL_HOST) {
    if (isGeneratedApiPage(url.pathname)) {
      return withRobotsTag(await context.next(), 'noindex, follow');
    }
    return context.next();
  }

  if (isProductionPreviewHost(url.hostname)) {
    url.hostname = CANONICAL_HOST;
    url.protocol = 'https:';
    url.port = '';
    return Response.redirect(url.toString(), 301);
  }

  return withRobotsTag(await context.next(), 'noindex, nofollow');
}
