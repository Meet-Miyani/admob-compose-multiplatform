/**
 * Structured-data builders and the OpenGraph image path helper.
 *
 * Everything here is a pure function so it can be unit-tested without an Astro
 * render. `src/components/Head.astro` is the only caller.
 */

/** Repository URL. Kept in sync by hand with `REPO` in `astro.config.mjs`. */
export const REPO_URL = 'https://github.com/Meet-Miyani/admob-compose-multiplatform';

/** Human labels for the top-level IA directories. Mirrors the sidebar groups. */
const SECTION_LABELS: Record<string, string> = {
  start: 'Start here',
  formats: 'Ad formats',
  privacy: 'Privacy and consent',
  advanced: 'Advanced',
  reference: 'Reference',
  project: 'Project',
};

/**
 * Starlight's docs loader gives the root `index.mdx` an empty id. Everything
 * downstream — OG routes, breadcrumb keys — needs a stable non-empty key.
 */
export function normalizeEntryId(id: string): string {
  return id === '' || id === '/' ? 'index' : id;
}

/** Path of the generated OpenGraph card for a docs entry. */
export function ogImagePath(id: string): string {
  return `/og/${normalizeEntryId(id)}.png`;
}

function absolute(siteUrl: string, pathname: string): string {
  return new URL(pathname, siteUrl).href;
}

export function breadcrumbListJsonLd(
  pathname: string,
  pageTitle: string,
  siteUrl: string
): object {
  const segments = pathname.split('/').filter(Boolean);
  const itemListElement: object[] = [
    {
      '@type': 'ListItem',
      position: 1,
      name: 'Home',
      item: absolute(siteUrl, '/'),
    },
  ];

  let accumulated = '';
  segments.forEach((segment, index) => {
    accumulated += `/${segment}`;
    const isLast = index === segments.length - 1;
    itemListElement.push({
      '@type': 'ListItem',
      position: index + 2,
      name: isLast ? pageTitle : (SECTION_LABELS[segment] ?? segment),
      item: absolute(siteUrl, `${accumulated}/`),
    });
  });

  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement,
  };
}

export function techArticleJsonLd(args: {
  url: string;
  title: string;
  description: string;
  siteUrl: string;
  dateModified?: string;
}): object {
  const { url, title, description, siteUrl, dateModified } = args;
  return {
    '@context': 'https://schema.org',
    '@type': 'TechArticle',
    headline: title,
    description,
    inLanguage: 'en',
    mainEntityOfPage: { '@type': 'WebPage', '@id': url },
    ...(dateModified ? { dateModified } : {}),
    author: { '@type': 'Person', name: 'Meet Miyani' },
    publisher: {
      '@type': 'Organization',
      name: 'Avinya',
      url: absolute(siteUrl, '/'),
    },
    isPartOf: {
      '@type': 'WebSite',
      name: 'Compose Multiplatform AdMob documentation',
      url: absolute(siteUrl, '/'),
    },
  };
}

export function softwareSourceCodeJsonLd(siteUrl: string, repoUrl: string): object {
  return {
    '@context': 'https://schema.org',
    '@type': 'SoftwareSourceCode',
    name: 'AdMob CMP',
    alternateName: 'dev.avinya.ads:admob-cmp',
    description:
      'Open-source Kotlin Multiplatform and Compose Multiplatform SDK for Google AdMob on Android and iOS, with banner, interstitial, rewarded, rewarded interstitial, app-open, and native ads.',
    url: absolute(siteUrl, '/'),
    codeRepository: repoUrl,
    programmingLanguage: 'Kotlin',
    runtimePlatform: ['Android', 'iOS'],
    license: 'https://www.apache.org/licenses/LICENSE-2.0.txt',
    author: { '@type': 'Person', name: 'Meet Miyani' },
    maintainer: { '@type': 'Organization', name: 'Avinya' },
  };
}
