#!/usr/bin/env node
/**
 * Loads every built page at 375 px in both themes and fails if the document
 * scrolls horizontally. Uses an explicit PREVIEW_URL or an isolated `dist/` server.
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { chromium } from 'playwright';

const DIST = path.resolve('dist');
let BASE = process.env.PREVIEW_URL;
const failures = [];

async function listRoutes(dir, prefix = '/') {
  const routes = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const generatedRootDirectory = prefix === '/' && ['api', 'pagefind', '_astro', 'og', 'fonts'].includes(entry.name);
      if (generatedRootDirectory) continue;
      routes.push(...(await listRoutes(full, `${prefix}${entry.name}/`)));
    } else if (entry.name === 'index.html') {
      routes.push(prefix);
    }
  }
  return routes;
}

let server;
if (BASE) {
  try {
    await fetch(BASE);
  } catch (err) {
    console.error(`PREVIEW_URL=${process.env.PREVIEW_URL} is unreachable: ${err.message}`);
    console.error('Fix the URL or unset PREVIEW_URL to let this script auto-serve dist/.');
    process.exit(2);
  }
} else {
  const MIME = {
    '.html': 'text/html',
    '.js': 'text/javascript',
    '.css': 'text/css',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.json': 'application/json',
    '.woff2': 'font/woff2',
  };
  const handler = async (req, res) => {
    const urlPath = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    let filePath = path.resolve(DIST, `.${urlPath}`);
    if (filePath !== DIST && !filePath.startsWith(`${DIST}${path.sep}`)) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    try {
      let st = await stat(filePath);
      if (st.isDirectory()) filePath = path.join(filePath, 'index.html');
      const content = await readFile(filePath);
      const ext = path.extname(filePath);
      res.writeHead(200, { 'Content-Type': MIME[ext] ?? 'application/octet-stream' });
      res.end(content);
    } catch {
      res.writeHead(404);
      res.end('Not found');
    }
  };
  server = await new Promise((resolve, reject) => {
    const s = createServer(handler);
    s.once('error', reject);
    s.once('listening', () => resolve(s));
    s.listen(0, '127.0.0.1');
  });
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Unable to determine static server address.');
  BASE = `http://127.0.0.1:${address.port}`;
  console.log(`Started isolated static server on ${BASE}`);
}

try {
  const routes = await listRoutes(DIST);
  const browser = await chromium.launch();

  for (const theme of ['light', 'dark']) {
    const context = await browser.newContext({
      viewport: { width: 375, height: 812 },
      deviceScaleFactor: 2,
      colorScheme: theme,
    });
    const page = await context.newPage();
    for (const route of routes) {
      await page.goto(`${BASE}${route}`, { waitUntil: 'networkidle' });
      await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme);
      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
        culprits: [...document.querySelectorAll('body *')]
          .filter((el) => el.getBoundingClientRect().right > window.innerWidth + 1)
          .slice(0, 3)
          .map((el) => `${el.tagName.toLowerCase()}.${el.className || '(no class)'}`),
      }));
      if (overflow.scrollWidth > overflow.innerWidth + 1) {
        const message = `${theme} ${route}: scrollWidth ${overflow.scrollWidth} > ${overflow.innerWidth} — ${overflow.culprits.join(', ')}`;
        console.error(`  FAIL ${message}`);
        failures.push(message);
      } else {
        console.log(`  ok   ${theme} ${route}`);
      }
    }
    await context.close();
  }

  await browser.close();
} finally {
  if (server) {
    server.close();
  }
}

if (failures.length > 0) {
  console.error(`\n${failures.length} page(s) overflow horizontally at 375px.`);
  process.exit(1);
}
console.log('\nNo horizontal overflow at 375px in either theme.');
