#!/usr/bin/env node
import { readFile, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { chromium } from 'playwright';

const DIST = path.resolve('dist');
let BASE = process.env.PREVIEW_URL;
const failures = [];

function check(condition, message) {
  if (condition) {
    console.log(`  ok   ${message}`);
  } else {
    console.error(`  FAIL ${message}`);
    failures.push(message);
  }
}

async function hasDist() {
  try {
    await stat(path.join(DIST, 'index.html'));
    return true;
  } catch {
    return false;
  }
}

const MIME = {
  '.css': 'text/css',
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
};

let server;
if (!(await hasDist())) {
  console.error('dist/ is missing. Run npm run build before npm run check:theme.');
  process.exit(2);
}

if (BASE) {
  try {
    await fetch(BASE);
  } catch (error) {
    console.error(`PREVIEW_URL=${process.env.PREVIEW_URL} is unreachable: ${error.message}`);
    process.exit(2);
  }
} else {
  server = await new Promise((resolve, reject) => {
    const instance = createServer(async (request, response) => {
      const pathname = decodeURIComponent(new URL(request.url, 'http://127.0.0.1').pathname);
      let file = path.resolve(DIST, `.${pathname}`);
      if (file !== DIST && !file.startsWith(`${DIST}${path.sep}`)) {
        response.writeHead(404);
        response.end('Not found');
        return;
      }
      try {
        if ((await stat(file)).isDirectory()) file = path.join(file, 'index.html');
        response.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream' });
        response.end(await readFile(file));
      } catch {
        response.writeHead(404);
        response.end('Not found');
      }
    });
    instance.once('error', reject);
    instance.once('listening', () => resolve(instance));
    instance.listen(0, '127.0.0.1');
  });
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Unable to determine static server address.');
  BASE = `http://127.0.0.1:${address.port}`;
  console.log(`Started isolated static server on ${BASE}`);
}

function luminance(rgb) {
  const channels = rgb.match(/\d+(?:\.\d+)?/g)?.slice(0, 3).map(Number) ?? [];
  const linear = channels.map((channel) => {
    const value = channel / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrast(a, b) {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((first, second) => second - first);
  return (lighter + 0.05) / (darker + 0.05);
}

async function inspect({ theme, viewport, reducedMotion = 'no-preference' }) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport, colorScheme: theme, reducedMotion });
  const page = await context.newPage();
  await page.goto(`${BASE}/reference/troubleshooting/`, { waitUntil: 'networkidle' });
  await page.evaluate((nextTheme) => document.documentElement.setAttribute('data-theme', nextTheme), theme);
  const result = await page.evaluate(() => {
    const article = document.querySelector('.sl-markdown-content');
    const h1 = document.querySelector('h1');
    const h2 = document.querySelector('.sl-markdown-content h2');
    const sidebarLink = document.querySelector('.sidebar-content a');
    const searchButton = document.querySelector('site-search button[data-open-modal]');
    const linkCard = document.querySelector('.sl-link-card');
    const frame = document.querySelector('.expressive-code .frame');
    const computed = (element) => (element ? getComputedStyle(element) : null);
    const codeColors = [...document.querySelectorAll('.expressive-code span')]
      .map((element) => getComputedStyle(element).color)
      .filter((color) => color !== 'rgba(0, 0, 0, 0)');
    const wrapper = document.querySelector('.table-scroll');
    const table = wrapper?.querySelector('table');
    const link = document.querySelector('.sl-markdown-content a');
    const main = document.querySelector('main[data-pagefind-body]');
    return {
      article: article && {
        fontFamily: computed(article).fontFamily,
        fontSize: computed(article).fontSize,
        lineHeight: computed(article).lineHeight,
        width: article.getBoundingClientRect().width,
      },
      headings: {
        h1: computed(h1)?.fontSize,
        h1Family: computed(h1)?.fontFamily,
        h2: computed(h2)?.fontSize,
      },
      sidebar: {
        fontFamily: computed(sidebarLink)?.fontFamily,
        fontSize: computed(sidebarLink)?.fontSize,
      },
      link: link && {
        color: getComputedStyle(link).color,
        bodyColor: computed(article)?.color,
        background: getComputedStyle(document.body).backgroundColor,
      },
      code: document.querySelector('.expressive-code pre') && {
        background: computed(document.querySelector('.expressive-code pre'))?.backgroundColor,
        fontSize: computed(document.querySelector('.expressive-code code'))?.fontSize,
        lineHeight: computed(document.querySelector('.expressive-code code'))?.lineHeight,
        radius: computed(frame)?.borderRadius,
        colors: [...new Set(codeColors)],
      },
      components: {
        searchRadius: computed(searchButton)?.borderRadius,
        cardRadius: computed(linkCard)?.borderRadius,
        cardTransform: computed(linkCard)?.transform,
      },
      table: wrapper && table && {
        tabIndex: wrapper.tabIndex,
        role: wrapper.getAttribute('role'),
        label: wrapper.getAttribute('aria-label'),
        overflowX: getComputedStyle(wrapper).overflowX,
        headerBackground: computed(table.querySelector('th'))?.backgroundColor,
        cellBorder: computed(table.querySelector('td'))?.borderBottomWidth,
        scrollWidth: wrapper.scrollWidth,
        clientWidth: wrapper.clientWidth,
      },
      motion: main && {
        name: getComputedStyle(main).animationName,
        duration: getComputedStyle(main).animationDuration,
      },
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth,
    };
  });
  await context.close();
  await browser.close();
  return result;
}

const syntaxCases = [
  { language: 'kotlin', path: '/start/quickstart/', minimumColors: 5 },
  { language: 'xml', path: '/start/android-setup/', minimumColors: 5 },
  { language: 'toml', path: '/start/installation/', minimumColors: 3 },
  { language: 'bash', path: '/start/installation/', minimumColors: 4 },
];

async function inspectStyle({ theme, path: pathname, selector, property }) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: theme });
  const page = await context.newPage();
  await page.goto(`${BASE}${pathname}`, { waitUntil: 'networkidle' });
  await page.evaluate((nextTheme) => document.documentElement.setAttribute('data-theme', nextTheme), theme);
  const value = await page.evaluate(
    ({ nextSelector, nextProperty }) => {
      const element = document.querySelector(nextSelector);
      return element ? getComputedStyle(element)[nextProperty] : null;
    },
    { nextSelector: selector, nextProperty: property }
  );
  await context.close();
  await browser.close();
  return value;
}

async function inspectSyntax({ theme, language, path: pathname }) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: theme });
  const page = await context.newPage();
  await page.goto(`${BASE}${pathname}`, { waitUntil: 'networkidle' });
  await page.evaluate((nextTheme) => document.documentElement.setAttribute('data-theme', nextTheme), theme);
  const colors = await page.evaluate((nextLanguage) => [
    ...new Set(
      [...document.querySelectorAll(`.expressive-code pre[data-language="${nextLanguage}"] span`)]
        .map((element) => getComputedStyle(element).color)
        .filter((color) => color !== 'rgba(0, 0, 0, 0)')
    ),
  ], language);
  await context.close();
  await browser.close();
  return colors;
}

async function inspectThemeSelector(theme) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: theme });
  const page = await context.newPage();
  await page.goto(`${BASE}/reference/troubleshooting/`, { waitUntil: 'networkidle' });
  await page.evaluate((nextTheme) => document.documentElement.setAttribute('data-theme', nextTheme), theme);

  const selector = page.locator('.right-group starlight-theme-select button, .right-group starlight-theme-select select');
  const visible = await selector.first().isVisible();
  await selector.first().focus();
  const focus = await selector.first().evaluate((element) => {
    const styles = getComputedStyle(element);
    return {
      visible: element.matches(':focus-visible'),
      outlineStyle: styles.outlineStyle,
      outlineWidth: Number.parseFloat(styles.outlineWidth),
    };
  });

  const opposite = theme === 'light' ? 'dark' : 'light';
  await selector.first().click();
  await page.waitForFunction((nextTheme) => document.documentElement.dataset.theme === nextTheme, opposite);
  const selectedTheme = await page.evaluate(() => document.documentElement.dataset.theme);

  await context.close();
  await browser.close();
  return { visible, focus, selectedTheme, opposite };
}

const NATIVELY_FOCUSABLE_TAGS = ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'];

async function focusAndRead(page, locator) {
  await locator.focus();
  return locator.evaluate((el) => {
    const styles = getComputedStyle(el);
    return {
      visible: el.matches(':focus-visible'),
      outlineStyle: styles.outlineStyle,
      outlineWidth: Number.parseFloat(styles.outlineWidth),
      href: el.getAttribute('href'),
    };
  });
}

async function inspectLanding({ theme, viewport, reducedMotion = 'no-preference' }) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport, colorScheme: theme, reducedMotion });
  const page = await context.newPage();
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  await page.evaluate((nextTheme) => document.documentElement.setAttribute('data-theme', nextTheme), theme);

  const data = await page.evaluate(() => {
    const main = document.querySelector('main');
    const h1 = document.querySelector('h1');
    const h2 = document.querySelector('h2');
    const h3 = document.querySelector('.landing-compatibility h3');
    const section = document.querySelector('.landing-section');

    // Resolve the design tokens the way the browser will serialise them, by
    // reading them back off a throwaway element. Comparing raw token text to a
    // computed value never matches: `0 1px 2px #0006` computes to
    // `rgba(0, 0, 0, 0.4) 0px 1px 2px 0px`.
    const probe = document.createElement('div');
    probe.style.position = 'absolute';
    probe.style.visibility = 'hidden';
    document.body.appendChild(probe);
    // setProperty needs the kebab-case name; the computed-style object is read
    // with the camelCase one. Passing camelCase to setProperty silently no-ops.
    const resolve = (kebab, camel, token) => {
      probe.style.setProperty(kebab, `var(${token})`);
      const value = getComputedStyle(probe)[camel];
      probe.style.removeProperty(kebab);
      return value;
    };
    // `0px` is "no radius"; 50% and 999px are shapes (circle, pill) rather than
    // points on the scale. Everything else must be a --admob-radius* token.
    const allowedRadii = new Set([
      '0px',
      '50%',
      '999px',
      ...['--admob-radius-sm', '--admob-radius', '--admob-radius-lg', '--admob-radius-xl'].map(
        (token) => resolve('border-top-left-radius', 'borderTopLeftRadius', token)
      ),
    ]);
    const allowedShadows = new Set([
      'none',
      ...['--admob-shadow', '--admob-shadow-lg'].map((token) =>
        resolve('box-shadow', 'boxShadow', token)
      ),
    ]);
    probe.remove();

    const landingElements = [...document.querySelectorAll('[class*="landing-"]')].map((el) => {
      const s = getComputedStyle(el);
      return {
        tag: el.tagName.toLowerCase(),
        className: el.className,
        radii: [
          s.borderTopLeftRadius,
          s.borderTopRightRadius,
          s.borderBottomRightRadius,
          s.borderBottomLeftRadius,
        ],
        boxShadow: s.boxShadow,
        animationName: s.animationName,
      };
    });

    const tableWrapper = document.querySelector('.table-scroll.table-scroll--wide');
    const table = tableWrapper?.querySelector('table');

    const codeBlocks = [...document.querySelectorAll('.expressive-code pre')].map((block) => ({
      background: getComputedStyle(block).backgroundColor,
    }));

    const metaCode = document.querySelector('.landing-meta code.admob-font-mono');
    const metaCodeFocusable = metaCode
      ? metaCode.tabIndex >= 0 || ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'].includes(metaCode.tagName)
      : false;

    return {
      main: main
        ? {
            fontFamily: getComputedStyle(main).fontFamily,
            animationName: getComputedStyle(main).animationName,
          }
        : null,
      section: section
        ? { fontFamily: getComputedStyle(section).fontFamily }
        : null,
      headings: {
        h1: h1 ? getComputedStyle(h1).fontSize : null,
        h1Family: h1 ? getComputedStyle(h1).fontFamily : null,
        h2: h2 ? getComputedStyle(h2).fontSize : null,
        h3: h3 ? getComputedStyle(h3).fontSize : null,
      },
      landing: landingElements,
      allowedRadii: [...allowedRadii],
      allowedShadows: [...allowedShadows],
      table: tableWrapper
        ? {
            tabIndex: tableWrapper.tabIndex,
            role: tableWrapper.getAttribute('role'),
            ariaLabel: tableWrapper.getAttribute('aria-label'),
            scrollWidth: tableWrapper.scrollWidth,
            clientWidth: tableWrapper.clientWidth,
            rowCount: table ? table.querySelectorAll('tbody tr').length : 0,
          }
        : null,
      codeBlocks,
      metaCodeFocusable,
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth,
    };
  });

  const focusChecks = {};

  focusChecks.heroActions = [];
  for (const link of await page.locator('.hero a').all()) {
    focusChecks.heroActions.push(await focusAndRead(page, link));
  }

  const metaCode = page.locator('.landing-meta code.admob-font-mono');
  if (await metaCode.count() > 0) {
    if (data.metaCodeFocusable) {
      focusChecks.metaCode = await focusAndRead(page, metaCode);
    } else {
      focusChecks.metaCode = { focusable: false };
    }
  } else {
    focusChecks.metaCode = { found: false };
  }

  focusChecks.formatLinks = [];
  for (const link of await page.locator('.landing-formats a').all()) {
    focusChecks.formatLinks.push(await focusAndRead(page, link));
  }

  const tableRegion = page.locator('.table-scroll.table-scroll--wide');
  if (await tableRegion.count() > 0) {
    focusChecks.tableRegion = await focusAndRead(page, tableRegion);
  }

  // Counted as two groups, not one total: the seven reference links are a
  // content contract, and the attribution is a separate, smaller one. A single
  // total would let one group grow while the other shrank.
  focusChecks.footerLinks = [];
  for (const link of await page.locator('.landing-footer__links a').all()) {
    focusChecks.footerLinks.push(await focusAndRead(page, link));
  }

  focusChecks.footerAuthorLinks = [];
  for (const link of await page.locator('.landing-footer__author a').all()) {
    focusChecks.footerAuthorLinks.push(await focusAndRead(page, link));
  }

  data.focus = focusChecks;

  await context.close();
  await browser.close();
  return data;
}

async function inspectLandingThemeSelector(theme) {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: theme });
  const page = await context.newPage();
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
  await page.evaluate((nextTheme) => document.documentElement.setAttribute('data-theme', nextTheme), theme);

  const selector = page.locator('.right-group starlight-theme-select button, .right-group starlight-theme-select select');
  const visible = await selector.first().isVisible();
  await selector.first().focus();
  const focus = await selector.first().evaluate((element) => {
    const styles = getComputedStyle(element);
    return {
      visible: element.matches(':focus-visible'),
      outlineStyle: styles.outlineStyle,
      outlineWidth: Number.parseFloat(styles.outlineWidth),
    };
  });

  const opposite = theme === 'light' ? 'dark' : 'light';
  await selector.first().click();
  await page.waitForFunction((nextTheme) => document.documentElement.dataset.theme === nextTheme, opposite);
  const selectedTheme = await page.evaluate(() => document.documentElement.dataset.theme);

  await context.close();
  await browser.close();
  return { visible, focus, selectedTheme, opposite };
}

try {
  for (const theme of ['light', 'dark']) {
    const desktop = await inspect({ theme, viewport: { width: 1440, height: 1000 } });
    const roadmapH3 = await inspectStyle({
      theme,
      path: '/project/roadmap/',
      selector: '.sl-markdown-content h3',
      property: 'fontSize',
    });
    // --admob-surface, per theme, in tokens.css.
    const codeBackground = {
      light: 'rgb(247, 245, 243)',
      dark: 'rgb(21, 17, 15)',
    }[theme];
    // The display and body faces are one variable family; the display role is
    // the same font pushed along its width axis, so both must resolve to it.
    const usesArchivo = (fontFamily) => /Archivo Variable/.test(fontFamily ?? '');
    check(desktop.article?.fontSize === '16px', `${theme} body is 16px`);
    check(desktop.article?.lineHeight === '26.4px', `${theme} body uses 1.65 rhythm`);
    check(desktop.article?.width <= 928, `${theme} content width is at most 58rem`);
    check(desktop.headings?.h1 === '36px', `${theme} desktop H1 is 36px`);
    check(desktop.headings?.h2 === '24px', `${theme} H2 is 24px`);
    check(roadmapH3 === '19px', `${theme} H3 is 19px`);
    check(
      usesArchivo(desktop.article?.fontFamily) && usesArchivo(desktop.headings?.h1Family),
      `${theme} prose and headings share the Archivo family`
    );
    check(usesArchivo(desktop.sidebar?.fontFamily), `${theme} sidebar uses the Archivo family`);
    check(desktop.sidebar?.fontSize === '13px', `${theme} sidebar is 13px`);
    check(desktop.link?.color !== desktop.link?.bodyColor && contrast(desktop.link?.color ?? '', desktop.link?.background ?? '') >= 4.5, `${theme} links use a distinct semantic accent`);
    check(desktop.code?.background === codeBackground, `${theme} code follows the site theme`);
    check(desktop.code?.fontSize === '14px', `${theme} code is 14px`);
    check(desktop.code?.radius === '12px', `${theme} code frame radius is --admob-radius-lg`);
    check((desktop.code?.colors.length ?? 0) >= 5, `${theme} code exposes at least five syntax colors`);
    check(desktop.components?.searchRadius === '8px', `${theme} search is --admob-radius, not a pill`);
    check(desktop.components?.cardRadius === '12px', `${theme} cards use --admob-radius-lg`);
    check(desktop.components?.cardTransform === 'none', `${theme} cards have no spatial transform`);
    check(desktop.table?.tabIndex === 0 && desktop.table.role === 'region' && desktop.table.label === 'Scrollable data table', `${theme} tables use a focusable labelled scroll region`);
    check(desktop.table?.overflowX === 'auto', `${theme} table wrapper scrolls horizontally when needed`);
    check(desktop.table?.headerBackground !== 'rgba(0, 0, 0, 0)' && desktop.table?.cellBorder === '1px', `${theme} table headers and rows are structured`);
    check(desktop.motion?.name === 'none', `${theme} articles do not animate on entry`);

    const landingDesktop = await inspectLanding({ theme, viewport: { width: 1440, height: 1000 } });
    check(landingDesktop.headings?.h1 === '56px', `${theme} landing H1 is 56px desktop`);
    check(landingDesktop.headings?.h2 === '30px', `${theme} landing H2 is 30px desktop`);
    check(landingDesktop.headings?.h3 === '19px', `${theme} landing H3 is 19px desktop`);
    check(
      usesArchivo(landingDesktop.headings?.h1Family) && usesArchivo(landingDesktop.section?.fontFamily),
      `${theme} landing body and H1 share the Archivo family`
    );
    // Token conformance, not flatness. Corners and depth may exist; they just
    // have to come off the scale in tokens.css rather than being invented here.
    const allowedRadii = new Set(landingDesktop.allowedRadii ?? []);
    const allowedShadows = new Set(landingDesktop.allowedShadows ?? []);
    check(allowedRadii.size >= 5, `${theme} landing resolved the radius scale from tokens`);
    check(allowedShadows.size >= 3, `${theme} landing resolved the elevation scale from tokens`);
    for (const el of landingDesktop.landing ?? []) {
      const offScale = (el.radii ?? []).filter((radius) => !allowedRadii.has(radius));
      check(
        offScale.length === 0,
        `${theme} landing ${el.className} uses only scale radii (off-scale: ${offScale.join(', ')})`
      );
      check(
        allowedShadows.has(el.boxShadow),
        `${theme} landing ${el.className} shadow is none or a token (got ${el.boxShadow})`
      );
    }
    if (landingDesktop.table) {
      check(landingDesktop.table?.tabIndex === 0, `${theme} landing capability table is keyboard-focusable`);
      check(landingDesktop.table?.role === 'region', `${theme} landing capability table has role=region`);
      check(
        typeof landingDesktop.table?.ariaLabel === 'string' && landingDesktop.table.ariaLabel.length > 0,
        `${theme} landing capability table has a non-empty aria-label`
      );
      check(
        (landingDesktop.table?.scrollWidth ?? 0) >= (landingDesktop.table?.clientWidth ?? 0),
        `${theme} landing capability table is structured for horizontal scroll when needed`
      );
      check((landingDesktop.table?.rowCount ?? 0) > 0, `${theme} landing capability table has rows`);
    }
    for (const block of landingDesktop.codeBlocks ?? []) {
      check(block.background === codeBackground, `${theme} landing code block uses ${codeBackground}`);
    }
    check(landingDesktop.main?.animationName === 'none', `${theme} landing main has no entrance animation`);
    check(
      landingDesktop.documentWidth <= landingDesktop.viewportWidth + 1,
      `${theme} landing desktop does not overflow horizontally`
    );
    check(
      landingDesktop.focus?.heroActions?.length === 2,
      `${theme} landing exposes exactly two hero actions (got ${landingDesktop.focus?.heroActions?.length})`
    );
    for (const focus of landingDesktop.focus?.heroActions ?? []) {
      check(
        focus.visible && focus.outlineWidth >= 2,
        `${theme} hero action ${focus.href} has visible focus outline >= 2px`
      );
    }
    if (landingDesktop.focus?.metaCode && landingDesktop.focus.metaCode.focusable !== false) {
      check(
        landingDesktop.focus.metaCode.visible && landingDesktop.focus.metaCode.outlineWidth >= 2,
        `${theme} landing meta code has visible focus outline >= 2px`
      );
    }
    check(
      landingDesktop.focus?.formatLinks?.length === 6,
      `${theme} landing exposes six format links (got ${landingDesktop.focus?.formatLinks?.length})`
    );
    for (const focus of landingDesktop.focus?.formatLinks ?? []) {
      check(
        focus.visible && focus.outlineWidth >= 2,
        `${theme} format link ${focus.href} has visible focus outline >= 2px`
      );
    }
    if (landingDesktop.focus?.tableRegion) {
      check(
        landingDesktop.focus?.tableRegion?.visible && landingDesktop.focus?.tableRegion?.outlineWidth >= 2,
        `${theme} landing capability table region has visible focus outline >= 2px`
      );
    }
    check(
      landingDesktop.focus?.footerLinks?.length === 7,
      `${theme} landing exposes seven footer reference links (got ${landingDesktop.focus?.footerLinks?.length})`
    );
    check(
      landingDesktop.focus?.footerAuthorLinks?.length === 2,
      `${theme} landing attribution links the author and the studio (got ${landingDesktop.focus?.footerAuthorLinks?.length})`
    );
    for (const focus of [
      ...(landingDesktop.focus?.footerLinks ?? []),
      ...(landingDesktop.focus?.footerAuthorLinks ?? []),
    ]) {
      check(
        focus.visible && focus.outlineWidth >= 2,
        `${theme} footer link ${focus.href} has visible focus outline >= 2px`
      );
    }

    const themeSelector = await inspectThemeSelector(theme);
    check(themeSelector.visible, `${theme} desktop theme selector is visible`);
    check(
      themeSelector.focus.visible &&
        themeSelector.focus.outlineStyle !== 'none' &&
        themeSelector.focus.outlineWidth >= 2,
      `${theme} theme selector has a visible focus outline of at least 2px`
    );
    check(
      themeSelector.selectedTheme === themeSelector.opposite,
      `${theme} theme selector updates the document theme`
    );

    const landingThemeSelector = await inspectLandingThemeSelector(theme);
    check(landingThemeSelector.visible, `${theme} landing theme selector is visible`);
    check(
      landingThemeSelector.focus.visible &&
        landingThemeSelector.focus.outlineStyle !== 'none' &&
        landingThemeSelector.focus.outlineWidth >= 2,
      `${theme} landing theme selector has a visible focus outline of at least 2px`
    );
    check(
      landingThemeSelector.selectedTheme === landingThemeSelector.opposite,
      `${theme} landing theme selector updates the document theme`
    );

    const mobile = await inspect({ theme, viewport: { width: 390, height: 844 } });
    check(mobile.headings?.h1 === '28px', `${theme} mobile H1 is 28px`);
    check(mobile.documentWidth <= mobile.viewportWidth + 1, `${theme} mobile document does not overflow horizontally`);
    check((mobile.table?.scrollWidth ?? 0) >= (mobile.table?.clientWidth ?? 0), `${theme} mobile table remains contained in its scroll region`);

    const landingMobile = await inspectLanding({ theme, viewport: { width: 390, height: 844 } });
    check(landingMobile.headings?.h1 === '36px', `${theme} landing H1 is 36px mobile`);
    check(landingMobile.headings?.h2 === '26px', `${theme} landing H2 is 26px mobile`);
    check(landingMobile.headings?.h3 === '19px', `${theme} landing H3 is 19px mobile`);
    check(
      landingMobile.documentWidth <= landingMobile.viewportWidth + 1,
      `${theme} landing mobile does not overflow horizontally`
    );

    for (const syntaxCase of syntaxCases) {
      const colors = await inspectSyntax({ theme, ...syntaxCase });
      check(
        colors.length >= syntaxCase.minimumColors,
        `${theme} ${syntaxCase.language} code exposes at least ${syntaxCase.minimumColors} syntax colors`
      );
    }

    const reduced = await inspect({ theme, viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    check(reduced.motion?.name === 'none', `${theme} reduced-motion disables article entrance`);

    // The landing page is allowed to animate; what it is not allowed to do is
    // ignore the opt-out. This is the assertion that replaced the old blanket
    // "no transforms, no animation" rule.
    const reducedLanding = await inspectLanding({ theme, viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    check(reducedLanding.main?.animationName === 'none', `${theme} landing reduced-motion disables entrance animation`);
    const stillAnimating = (reducedLanding.landing ?? []).filter(
      (el) => el.animationName !== 'none'
    );
    check(
      stillAnimating.length === 0,
      `${theme} landing reduced-motion stops every landing element (still animating: ${stillAnimating
        .map((el) => el.className)
        .join(', ')})`
    );
  }
} finally {
  if (server) server.close();
}

if (failures.length > 0) {
  console.error(`\n${failures.length} theme check(s) failed.`);
  process.exit(1);
}

console.log('\nTheme and rendering checks passed.');
