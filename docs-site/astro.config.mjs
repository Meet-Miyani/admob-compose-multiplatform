// @ts-check
import { defineConfig } from 'astro/config';
import { unified } from '@astrojs/markdown-remark';
import starlight from '@astrojs/starlight';
import sitemap from '@astrojs/sitemap';
import starlightLlmsTxt from 'starlight-llms-txt';
import rehypeMermaid from 'rehype-mermaid';
import rehypeTableScroll from './src/lib/rehype-table-scroll.mjs';


/**
 * Canonical origin. `site` MUST be the custom domain, never the *.pages.dev
 * preview host — that mistake is exactly the defect documented in spec §5 for
 * the studio site, and `functions/_middleware.js` is the second line of defence.
 */
const SITE = 'https://ads.avinya.dev';
const REPO = 'https://github.com/Meet-Miyani/admob-compose-multiplatform';
const SITE_TITLE = 'AdMob CMP';
const SITE_DESCRIPTION =
  'Open-source AdMob SDK for Kotlin and Compose Multiplatform: banner, interstitial, rewarded, rewarded interstitial, app-open and native ads on Android and iOS.';

// Both inert until the corresponding Cloudflare Pages build env var is set —
// no placeholder tags ship to production without a real token.
const GSC_VERIFICATION_TOKEN = process.env.PUBLIC_GSC_VERIFICATION;
const CF_BEACON_TOKEN = process.env.PUBLIC_CF_BEACON_TOKEN;

export default defineConfig({
  site: SITE,
  build: {
    format: 'directory',
    inlineStylesheets: 'always',
  },
  markdown: {
    processor: unified({
      rehypePlugins: [
        [
          rehypeMermaid,
          {
            strategy: 'inline-svg',
            mermaidConfig: {
              theme: 'base',
              fontFamily:
                'Archivo Variable, Archivo, -apple-system, BlinkMacSystemFont, Segoe UI, Helvetica Neue, Arial, sans-serif',
              // Baked in at build time, so these must be the LIGHT theme values
              // from tokens.css; src/styles/mermaid.css re-tints for dark.
              // Keep them in sync by hand — nothing checks this pairing.
              themeVariables: {
                background: '#ffffff',
                primaryColor: '#f7f5f3',
                primaryTextColor: '#171310',
                primaryBorderColor: '#e3deda',
                secondaryColor: '#ffffff',
                tertiaryColor: '#f7f5f3',
                lineColor: '#6b625c',
                textColor: '#171310',
              },
            },
          },
        ],
        rehypeTableScroll,
      ],
    }),
  },
  integrations: [
    starlight({
      title: SITE_TITLE,
      description: SITE_DESCRIPTION,
      logo: { src: './src/assets/logo.svg', alt: 'AdMob CMP' },
      favicon: '/favicon.svg',
      credits: false,
      pagefind: true,
      lastUpdated: true,
      tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
      social: [{ icon: 'github', label: 'GitHub', href: REPO }],
      // Starlight appends the entry path relative to the Astro project root,
      // which is `docs-site/`, so the base URL has to include that segment.
      editLink: { baseUrl: `${REPO}/edit/master/docs-site/` },
      customCss: ['./src/styles/tokens.css', './src/styles/mermaid.css'],
      components: {
        Head: './src/components/Head.astro',
        Hero: './src/components/Hero.astro',
        ThemeSelect: './src/components/ThemeSelect.astro',
      },
      expressiveCode: {
        themes: ['github-dark', 'github-light'],
        minSyntaxHighlightingColorContrast: 5.5,
        useStarlightUiThemeColors: true,
        useStarlightDarkModeSwitch: true,
        styleOverrides: {
          borderRadius: 'var(--admob-radius-lg)',
          codeFontFamily: 'var(--admob-font-mono)',
          codeFontSize: '0.875rem',
          codeLineHeight: '1.6',
          uiFontFamily: 'var(--admob-font-body)',
          frames: {
            shadowColor: 'transparent',
            tooltipSuccessBackground: 'var(--admob-accent-text)',
            tooltipSuccessForeground: 'var(--admob-accent-contrast)',
          },
        },
      },
      head: [
        {
          tag: 'link',
          attrs: {
            rel: 'preload',
            // The display and body faces are one variable file (wght + wdth),
            // so this single preload covers every non-code glyph on the page.
            href: '/fonts/archivo-wdth.woff2',
            as: 'font',
            type: 'font/woff2',
            crossorigin: 'anonymous',
          },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'preload',
            href: '/fonts/jetbrains-mono-400.woff2',
            as: 'font',
            type: 'font/woff2',
            crossorigin: 'anonymous',
          },
        },
        {
          tag: 'link',
          attrs: {
            rel: 'preload',
            href: '/fonts/jetbrains-mono-500.woff2',
            as: 'font',
            type: 'font/woff2',
            crossorigin: 'anonymous',
          },
        },
        // Must track --admob-paper in tokens.css. It drifted once already.
        { tag: 'meta', attrs: { name: 'theme-color', content: '#0c0a09' } },
        // Google Search Console ownership verification. Inert until
        // PUBLIC_GSC_VERIFICATION is set as a Cloudflare Pages env var.
        ...(GSC_VERIFICATION_TOKEN
          ? [{ tag: 'meta', attrs: { name: 'google-site-verification', content: GSC_VERIFICATION_TOKEN } }]
          : []),
        // Cloudflare Web Analytics beacon — zero-cookie, no npm dependency.
        // Inert until PUBLIC_CF_BEACON_TOKEN is set.
        ...(CF_BEACON_TOKEN
          ? [{
              tag: 'script',
              attrs: {
                defer: true,
                src: 'https://static.cloudflareinsights.com/beacon.min.js',
                'data-cf-beacon': JSON.stringify({ token: CF_BEACON_TOKEN }),
              },
            }]
          : []),
      ],
      plugins: [
        starlightLlmsTxt({
          projectName: 'AdMob CMP',
          description:
            'A Kotlin Multiplatform / Compose Multiplatform AdMob SDK. Six ad formats (banner, interstitial, rewarded, rewarded interstitial, app-open, native), UMP consent wired into the initialization flow, mediation, paid/revenue events, and a Gradle plugin that fixes Kotlin/Native iOS test linking. Published to Maven Central as dev.avinya.ads:admob-cmp.',
          details: [
            '## Coordinates',
            '',
            '- Maven coordinate: `dev.avinya.ads:admob-cmp`',
            '- Gradle plugin id: `dev.avinya.ads.admob-cmp`',
            '- Modules: `admob-cmp` (facade), `admob-cmp-core`, `admob-cmp-compose`',
            '- Platforms: Android and iOS. There is no desktop or web ad implementation.',
            '- Licence: Apache-2.0',
            '',
            '## Reading order',
            '',
            'Start with `/start/what-is-admob-cmp/`, then `/start/quickstart/`.',
            'iOS integrations should read `/start/ios-setup/` and',
            '`/privacy/app-tracking-transparency/` before writing any ad code:',
            'the required ordering is consent, then ATT, then initialize.',
            '',
            '## Caveats',
            '',
            'The public ABI is frozen and validated locally via `./scripts/release-readiness.sh`.',
            'CI does not run SDK or ABI checks. Suggestions that change',
            'a public signature in `admob-cmp-core` or `admob-cmp-compose` will fail',
            '`checkKotlinAbi`.',
            '',
            'Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads',
            'are trademarks of Google LLC.',
          ].join('\n'),
          optionalLinks: [
            {
              label: 'GitHub repository',
              url: 'https://github.com/Meet-Miyani/admob-compose-multiplatform',
              description: 'Source, issues, and the AGENTS.md instructions file.',
            },
            {
              label: 'Maven Central',
              url: 'https://central.sonatype.com/artifact/dev.avinya.ads/admob-cmp',
              description: 'Published artifacts and the current release version.',
            },
            {
              label: 'API reference',
              url: 'https://ads.avinya.dev/reference/api/',
              description: 'Authored API reference guide with links to the generated Dokka declarations.',
            },
          ],
          promote: ['index*', 'start/what-is-admob-cmp*', 'start/quickstart*', 'start/installation*'],
          demote: ['project/*', 'reference/changelog*'],
          // `exclude` only trims llms-small.txt, the small-context bundle.
          exclude: ['project/contributing*', 'reference/changelog*'],
          customSets: [
            {
              label: 'Ad formats',
              description: 'Every ad format guide: banner, interstitial, rewarded, rewarded interstitial, app-open, native.',
              paths: ['formats/**'],
            },
            {
              label: 'Privacy and consent',
              description: 'UMP consent, App Tracking Transparency ordering, and Play Data safety.',
              paths: ['privacy/**'],
            },
            {
              label: 'Setup',
              description: 'Installation, Android setup, and iOS setup including the Gradle plugin.',
              paths: ['start/**'],
            },
          ],
          pageSeparator: '\n\n---\n\n',
        }),
      ],
      // Information architecture — spec §8. Every path segment is a keyword and
      // there is deliberately no `/docs/` prefix.
      sidebar: [
        {
          label: 'Start here',
          items: [
            { slug: 'start/what-is-admob-cmp' },
            { slug: 'start/quickstart' },
            { slug: 'start/installation' },
            { slug: 'start/android-setup' },
            { slug: 'start/ios-setup' },
            { slug: 'start/migrate-from-expect-actual' },
          ],
        },
        {
          label: 'Ad formats',
          items: [
            { slug: 'formats/banner' },
            { slug: 'formats/interstitial' },
            { slug: 'formats/rewarded' },
            { slug: 'formats/app-open' },
            { slug: 'formats/native' },
          ],
        },
        {
          label: 'Privacy and consent',
          items: [
            { slug: 'privacy/consent' },
            { slug: 'privacy/app-tracking-transparency' },
            { slug: 'privacy/play-data-safety' },
            { slug: 'privacy/app-store-data-disclosure' },
          ],
        },
        {
          label: 'Advanced',
          items: [
            { slug: 'advanced/mediation' },
            { slug: 'advanced/revenue-events' },
            { slug: 'advanced/caching-retry-timeouts' },
            { slug: 'advanced/test-safety' },
            { slug: 'advanced/compose-stability' },
          ],
        },
        {
          label: 'Reference',
          items: [
            { slug: 'reference/architecture' },
            { slug: 'reference/compatibility' },
            { slug: 'reference/troubleshooting' },
            { slug: 'reference/changelog' },
            { slug: 'reference/api' },
            // Previously reachable only through the per-diagram "described in
            // words" links in DiagramFigure.astro, so it had no navigational
            // entry point at all despite being one of the longest pages.
            { slug: 'reference/diagrams-in-words' },
          ],
        },
        {
          label: 'Project',
          items: [
            { slug: 'project/roadmap' },
            { slug: 'project/showcase' },
            { slug: 'project/contributing' },
            // Was a true orphan: in the sitemap, but with no inbound link from
            // any page, component or sidebar entry in the repository.
            { slug: 'project/ai-agents' },
          ],
        },
      ],
    }),
    sitemap({
      // `/og/*.png` are image endpoints, not pages. `/api/**` is generated
      // Dokka output; the authored `/reference/api/` guide is indexed instead.
      // `/dev/` is the noindex diagram review gallery (Plan 4). It must not
      // appear in the sitemap of an SEO-focused host.
      filter: (page) =>
        !page.includes('/og/') &&
        !new URL(page).pathname.startsWith('/api/') &&
        !page.includes('/dev/'),
      changefreq: 'weekly',
    }),
  ],
});
