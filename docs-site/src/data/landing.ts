export interface LandingFormat {
  slug: string;
  name: string;
  href: string;
  blurb: string;
  api: string;
  /**
   * The format's on-screen size, as a developer would state it. Shown beside
   * the API signature on the landing page.
   *
   * The matching *geometry* — where the ad region sits inside the phone
   * viewport that the placement plate draws — lives in landing.css keyed by
   * slug, because it is presentation. Only the wording lives here.
   */
  dimension: string;
}

export const formats: readonly LandingFormat[] = [
  {
    slug: 'banner',
    name: 'Banner',
    href: '/formats/banner/',
    blurb: 'Inline rectangular ad anchored inside Compose layout.',
    api: 'BannerAdView(placement)',
    dimension: '320 × 50 dp',
  },
  {
    slug: 'interstitial',
    name: 'Interstitial',
    href: '/formats/interstitial/',
    blurb: 'Full-screen ad shown at a natural transition point.',
    api: 'adManager.interstitial(placement)',
    dimension: 'full screen',
  },
  {
    slug: 'rewarded',
    name: 'Rewarded',
    href: '/formats/rewarded/',
    blurb: 'Full-screen ad that grants a reward on completion.',
    api: 'adManager.rewarded(placement)',
    dimension: 'full screen',
  },
  {
    slug: 'rewarded-interstitial',
    name: 'Rewarded interstitial',
    href: '/formats/rewarded/#how-is-a-rewarded-interstitial-different',
    blurb: 'Full-screen rewarded ad shown at a natural transition point.',
    api: 'adManager.rewardedInterstitial(placement)',
    dimension: 'full screen',
  },
  {
    slug: 'app-open',
    name: 'App-open',
    href: '/formats/app-open/',
    blurb: 'Full-screen ad shown when the app returns to the foreground.',
    api: 'AppOpenAdCoordinator(manager, controller, config)',
    dimension: 'full screen',
  },
  {
    slug: 'native',
    name: 'Native',
    href: '/formats/native/',
    blurb: 'Compose-rendered ad built from a typed layout DSL and a bounded feed session.',
    api: 'NativeAdView(session, slotKey, placement)',
    dimension: 'sized by your layout',
  },
];

export interface RoadmapItem {
  title: string;
  status: string;
}

export const roadmapItems: readonly RoadmapItem[] = [
  {
    title: 'Swift Package Manager dependency import',
    status:
      'Gated on four unmet upstream conditions (swiftPMDependencies); Maven-consumer propagation remains an open unknown and the project refuses to depend on an Alpha build-tool feature.',
  },
  {
    title: 'Native video events on Android',
    status:
      'Blocked on the upstream SDK. iOS exposes GADVideoControllerDelegate with five video events; no equivalent Android callback surface is available.',
  },
];

export const repoUrl = 'https://github.com/Meet-Miyani/admob-compose-multiplatform';
export const trademarkStatement =
  'Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads are trademarks of Google LLC.';

export const authorName = 'Meet Miyani';
export const studioName = 'Avinya';
export const studioUrl = 'https://avinya.dev';
/** The repo owner's profile, derived so it cannot drift from `repoUrl`. */
export const authorUrl = repoUrl.split('/').slice(0, 4).join('/');

/**
 * Why the library exists. Kept here rather than in the component so the wording
 * is reviewable in one place alongside the other public copy.
 *
 * TODO(origin-app): the app this was extracted from is not live yet, so the
 * third paragraph describes it generically. When it ships, name and link it.
 */
export const originStory = {
  paragraphs: [
    'Integrating Google Mobile Ads into a Compose Multiplatform application traditionally required writing platform-specific glue and managing consent flows separately on Android and iOS.',
    'This library provides composable surfaces, suspend functions, and StateFlow lifecycle handling designed for Compose Multiplatform codebases, with UMP consent integration structured into initialization.',
    'Extracted from real application production requirements, it provides bounded native-ad sessions, cache and retry policies, and pre-initialization hooks for ordering ATT and UMP consent on iOS.',
  ],
} as const;

export interface LandingMeta {
  mavenCoordinate: string;
  gradlePlugin: string;
  kotlinVersion: string;
  composeMultiplatformVersion: string;
  androidMinSdk: number;
  iosDeploymentTarget: string;
  licenseName: string;
}

export const landingMeta: LandingMeta = {
  mavenCoordinate: 'dev.avinya.ads:admob-cmp:2.0.0',
  gradlePlugin: 'dev.avinya.ads.admob-cmp:2.0.0',
  kotlinVersion: '2.3.20',
  composeMultiplatformVersion: '1.11.1',
  androidMinSdk: 26,
  iosDeploymentTarget: '15.0',
  licenseName: 'Apache License 2.0',
};
