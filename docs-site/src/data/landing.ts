export interface LandingFormat {
  slug: string;
  name: string;
  href: string;
  /** One sentence for the format card: what it is and what the SDK adds. */
  blurb: string;
  /** The full public entry point, shown as the chip's tooltip. */
  api: string;
  /** The call as it fits on the card chip. */
  call: string;
}

export const formats: readonly LandingFormat[] = [
  {
    slug: 'banner',
    name: 'Banner',
    href: '/formats/banner/',
    blurb: 'Anchored, inline adaptive, fixed or collapsible — laid out like any other composable.',
    api: 'BannerAdView(placement)',
    call: 'BannerAdView(placement)',
  },
  {
    slug: 'interstitial',
    name: 'Interstitial',
    href: '/formats/interstitial/',
    blurb: "Full screen at a natural break. Preload into a TTL'd cache, show when ready.",
    api: 'adManager.interstitial(placement)',
    call: 'adManager.interstitial(p)',
  },
  {
    slug: 'rewarded',
    name: 'Rewarded',
    href: '/formats/rewarded/',
    blurb: 'Opt-in full screen ad. The reward arrives as a typed RewardEarned event.',
    api: 'adManager.rewarded(placement)',
    call: 'adManager.rewarded(p)',
  },
  {
    slug: 'rewarded-interstitial',
    name: 'Rewarded interstitial',
    href: '/formats/rewarded/#how-is-a-rewarded-interstitial-different',
    blurb: 'Rewarded, at a transition point, with an intro the user can skip. Same controller shape.',
    api: 'adManager.rewardedInterstitial(placement)',
    call: 'rewardedInterstitial(p)',
  },
  {
    slug: 'app-open',
    name: 'App-open',
    href: '/formats/app-open/',
    blurb: 'Shown when the app returns to the foreground, with cooldowns and blocking built in.',
    api: 'AppOpenAdCoordinator(manager, controller, config)',
    call: 'AppOpenAdCoordinator(…)',
  },
  {
    slug: 'native',
    name: 'Native',
    href: '/formats/native/',
    blurb: 'Rendered in Compose from a typed layout DSL, fed by a bounded session.',
    api: 'NativeAdView(session, slotKey, placement)',
    call: 'NativeAdView(session, …)',
  },
];

export interface RoadmapItem {
  title: string;
  status: string;
}

/** The landing page's summary; /project/roadmap/ has the full reasoning. */
export const roadmapItems: readonly RoadmapItem[] = [
  {
    title: 'Swift Package Manager dependency import',
    status:
      "Gated on four upstream conditions. The project won't depend on an Alpha build-tool feature.",
  },
  {
    title: 'Native video events on Android',
    status: 'Blocked on the upstream SDK, which has no equivalent of GADVideoControllerDelegate.',
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

export interface LandingMeta {
  mavenCoordinate: string;
  gradlePlugin: string;
  kotlinVersion: string;
  composeMultiplatformVersion: string;
  androidMinSdk: number;
  iosDeploymentTarget: string;
  licenseName: string;
  /** SPDX id, for the hero pill. */
  licenseSpdx: string;
}

export const landingMeta: LandingMeta = {
  mavenCoordinate: 'dev.avinya.ads:admob-cmp:2.6.0',
  gradlePlugin: 'dev.avinya.ads.admob-cmp:2.6.0',
  kotlinVersion: '2.4.20',
  composeMultiplatformVersion: '1.12.0',
  androidMinSdk: 26,
  iosDeploymentTarget: '15.0',
  licenseName: 'Apache License 2.0',
  licenseSpdx: 'Apache-2.0',
};
