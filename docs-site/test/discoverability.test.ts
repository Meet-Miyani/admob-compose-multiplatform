import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const readRepoFile = (path: string) => readFileSync(`${repoRoot}${path}`, 'utf8');

function versionName(): string {
  const match = readRepoFile('gradle.properties').match(/^VERSION_NAME=(.+)$/m);
  if (!match) throw new Error('VERSION_NAME missing from gradle.properties');
  return match[1].trim();
}

describe('repository discovery metadata', () => {
  it('keeps README installation and compatibility examples on the current release', () => {
    const readme = readRepoFile('README.md');
    const currentVersion = versionName();
    const version = currentVersion.replaceAll('.', '\\.');

    expect(readme).toMatch(new RegExp(`admob-cmp:${version}`));
    expect(readme).toMatch(new RegExp(`admob-cmp\"\\) version \"${version}\"`));
    expect(readme).toMatch(new RegExp(`\\| ${version} \\|`));
    expect(readme).toContain(`Underlying Google SDKs bound by ${currentVersion}:`);
  });

  it('does not claim that SDK ABI checks run in CI', () => {
    const readme = readRepoFile('README.md');
    expect(readme).not.toMatch(/public ABI[^\n]*enforced in CI/i);
    expect(readme).toMatch(/public ABI[^\n]*release-readiness/i);
  });

  it('uses the searchable product description in Maven metadata', () => {
    const properties = readRepoFile('gradle.properties');
    expect(properties).toContain(
      'POM_DESCRIPTION=Open-source Kotlin Multiplatform and Compose Multiplatform SDK for Google AdMob on Android and iOS, published as dev.avinya.ads:admob-cmp.'
    );
  });

  it('ships the complete Apache-2.0 license appendix', () => {
    const license = readRepoFile('LICENSE');
    expect(license).toContain('APPENDIX: How to apply the Apache License to your work.');
    expect(license).toContain('Copyright [yyyy] [name of copyright owner]');
  });
});

describe('crawler and AI citation policy', () => {
  const robots = readRepoFile('docs-site/public/robots.txt');

  function namedAgentPolicies(): Map<string, string[]> {
    const policies = new Map<string, string[]>();
    const lines = robots.split(/\r?\n/);
    for (let index = 0; index < lines.length; index += 1) {
      const match = lines[index].match(/^User-agent:\s*(\S+)$/);
      if (!match || match[1] === '*') continue;
      const directives: string[] = [];
      for (let next = index + 1; next < lines.length && !/^User-agent:/i.test(lines[next]); next += 1) {
        if (/^(?:Allow|Disallow):/i.test(lines[next])) directives.push(lines[next]);
      }
      policies.set(match[1], [...(policies.get(match[1]) ?? []), ...directives]);
    }
    return policies;
  }

  it('allows search and answer retrieval while blocking model training', () => {
    expect(robots).toContain('Content-Signal: search=yes, ai-input=yes, ai-train=no, use=reference');
    expect(robots).toMatch(
      /User-agent: \*\nContent-Signal: search=yes, ai-input=yes, ai-train=no, use=reference\nAllow: \//
    );
    expect(robots).toMatch(/User-agent: OAI-SearchBot\nAllow: \//);
    expect(robots).toMatch(/User-agent: Claude-SearchBot\nAllow: \//);
    expect(robots).toMatch(/User-agent: PerplexityBot\nAllow: \//);
  });

  it('blocks training-oriented crawlers without blocking general search', () => {
    for (const bot of ['GPTBot', 'ClaudeBot', 'anthropic-ai', 'CCBot', 'Bytespider', 'Applebot-Extended', 'Google-Extended']) {
      expect(robots).toMatch(new RegExp(`User-agent: ${bot}\\nDisallow: /`));
    }
    expect(robots).toMatch(/User-agent: \*[\s\S]*?Allow: \//);
  });

  it('gives every named crawler one unambiguous policy', () => {
    const policies = namedAgentPolicies();
    const expected = new Map([
      ['Googlebot', ['Allow: /']],
      ['Bingbot', ['Allow: /']],
      ['Applebot', ['Allow: /']],
      ['OAI-SearchBot', ['Allow: /']],
      ['ChatGPT-User', ['Allow: /']],
      ['Claude-SearchBot', ['Allow: /']],
      ['Claude-User', ['Allow: /']],
      ['PerplexityBot', ['Allow: /']],
      ['Perplexity-User', ['Allow: /']],
      ['GPTBot', ['Disallow: /']],
      ['ClaudeBot', ['Disallow: /']],
      ['anthropic-ai', ['Disallow: /']],
      ['CCBot', ['Disallow: /']],
      ['Bytespider', ['Disallow: /']],
      ['Applebot-Extended', ['Disallow: /']],
      ['Google-Extended', ['Disallow: /']],
    ]);

    expect(policies).toEqual(expected);
  });
});

describe('search intent copy', () => {
  it('names all six formats in the global site description', () => {
    const config = readRepoFile('docs-site/astro.config.mjs');
    expect(config).toMatch(/banner, interstitial, rewarded, rewarded interstitial, app-open and native ads/);
  });

  it('keeps the introductory guide factual and current', () => {
    const guide = readRepoFile('docs-site/src/content/docs/start/what-is-admob-cmp.mdx');
    expect(guide).toContain('title: "Kotlin Multiplatform AdMob SDK for Android and iOS"');
    expect(guide).not.toMatch(/Write Once, Monetize Anywhere|solves these headaches|preventing policy violations|Medation/);
  });

  it('does not promise a stale version for future SwiftPM work', () => {
    const roadmap = readRepoFile('docs-site/src/content/docs/project/roadmap.mdx');
    expect(roadmap).not.toMatch(/ship(?:s|ped)? as `?2\.0\.0`?|when 2\.0\.0 ships/i);
  });
});

describe('integration recommendations', () => {
  it('recommends Strict AppIdVerificationPolicy in all setup guides', () => {
    const expectedLine = 'AdAppIdVerification.policy = AppIdVerificationPolicy.Strict';
    expect(readRepoFile('docs-site/src/content/docs/start/quickstart.mdx')).toContain(expectedLine);
    expect(readRepoFile('docs-site/src/content/docs/start/installation.mdx')).toContain(expectedLine);
    expect(readRepoFile('docs-site/src/content/docs/start/android-setup.mdx')).toContain(expectedLine);
    expect(readRepoFile('docs-site/src/content/docs/start/ios-setup.mdx')).toContain(expectedLine);
  });
});
