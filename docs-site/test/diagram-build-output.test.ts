import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import descriptions from '../src/components/diagrams/descriptions.json';

const GALLERY = new URL('../dist/dev/diagram-gallery/index.html', import.meta.url);
let html = '';
let figureSource = '';

beforeAll(() => {
  if (!existsSync(GALLERY)) {
    throw new Error('dist/dev/diagram-gallery/index.html is missing — run `npm run build` first');
  }
  html = readFileSync(GALLERY, 'utf8');
  figureSource = readFileSync(
    fileURLToPath(new URL('../src/components/diagrams/DiagramFigure.astro', import.meta.url)),
    'utf8'
  );
});

const ids = Object.keys(descriptions);
// PlatformMatrix is a <table>, not an <svg>, so it is exempt from the SVG contract.
const svgIds = ids.filter((id) => id !== 'platform-matrix');

describe('every diagram is present in the static HTML', () => {
  it.each(ids)('%s renders a figure', (id) => {
    expect(html).toContain(`data-diagram="${id}"`);
  });

  it.each(ids)('%s links to its prose equivalent', (id) => {
    expect(html).toContain(`/reference/diagrams-in-words/#${id}`);
  });
});

describe('accessibility contract', () => {
  it.each(svgIds)('%s declares role=img with a title and desc', (id) => {
    expect(html).toContain(`${id}-title ${id}-desc`);
    expect(html).toContain(`id="${id}-title"`);
    expect(html).toContain(`id="${id}-desc"`);
  });

  it('the platform matrix ships as a real table with scoped headers', () => {
    expect(html).toContain('<table class="dg-table">');
    expect(html).toContain('scope="row"');
    expect(html).toContain('scope="col"');
  });
});

describe('build-time static SVG only', () => {
  it('contains inline svg elements, not img references to svg files', () => {
    expect(html).toContain('<svg');
    expect(html).not.toMatch(/<img[^>]+\.svg/);
  });

  it('left no unprocessed mermaid fence behind', () => {
    expect(html).not.toContain('class="mermaid"');
    expect(html).not.toContain('language-mermaid');
  });

  it('ships no client-side renderer', () => {
    expect(html.toLowerCase()).not.toContain('mermaid.esm');
    expect(html.toLowerCase()).not.toContain('mermaid.min.js');
  });

  it('exposes diagram text to crawlers rather than burying it in an image', () => {
    // One representative string per hand-authored diagram.
    for (const needle of [
      'FullScreenSlotCore',
      'soft 4 / hard 6',
      'UIScreen.mainScreen',
      'NETWORK_ERROR',
      'upstream SDK gap',
    ]) {
      expect(html).toContain(needle);
    }
  });
});

describe('overflow contract', () => {
  it('every diagram sits in a scroll region carrying its own min-width', () => {
    expect(html).toContain('class="dg-scroll"');
    expect(html).toContain('--dg-min-w:');
  });
});

describe('caption text never names the internal instructions file', () => {
  it('the source component does not contain "CLAUDE.md invariants"', () => {
    expect(figureSource).not.toContain('CLAUDE.md invariants');
  });

  it('the built site does not contain "CLAUDE.md invariants" in diagram captions', () => {
    const mainIndex = readFileSync(
      fileURLToPath(new URL('../dist/index.html', import.meta.url)),
      'utf8'
    );
    expect(mainIndex).not.toContain('CLAUDE.md invariants');
  });
});

describe('expand-to-dialog is progressive enhancement', () => {
  it('renders no Expand control server-side — a control that cannot work must not exist', () => {
    // The class names DO appear in the built HTML, inside the bundled script
    // that creates them. What must not exist is a rendered element.
    expect(html).not.toMatch(/<button[^>]*class="[^"]*\bdg-expand\b/);
    expect(html).not.toMatch(/<dialog/i);
    expect(figureSource).not.toMatch(/<button[^>]*class="dg-expand"/);
  });

  it('creates the control and the dialog from script instead', () => {
    expect(figureSource).toMatch(/<script>[\s\S]*createElement\('dialog'\)[\s\S]*<\/script>/);
    expect(figureSource).toMatch(/class = 'dg-expand'|className = 'dg-expand'/);
    expect(figureSource).toMatch(/showModal\(\)/);
  });

  it('moves the scroll region rather than cloning it, so SVG ids stay unique', () => {
    expect(figureSource).not.toMatch(/cloneNode/);
    expect(figureSource).toMatch(/replaceWith/);
  });

  it('returns focus to the trigger when the dialog closes', () => {
    expect(figureSource).toMatch(/addEventListener\('close'[\s\S]*?expand\.focus\(\)/);
  });

  it('keeps the inline scroll region as the no-script baseline', () => {
    expect(html).toContain('class="dg-scroll"');
    expect(html).toContain('tabindex="0"');
  });

  it('sets aria-label starting with visible button text to satisfy WCAG 2.5.3', () => {
    expect(figureSource).toMatch(/setAttribute\(\s*['"]aria-label['"],\s*`Expand/);
  });
});
