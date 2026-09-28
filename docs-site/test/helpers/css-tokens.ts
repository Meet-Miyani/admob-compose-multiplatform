/**
 * Reads the ACTUAL shipped CSS rather than a duplicated copy of the palette, so
 * these tests cannot drift from what the site renders.
 *
 * `tokens.css` (Plan 2) holds the literal hex values per theme. `diagrams.css`
 * (this plan) maps diagram roles onto those tokens through bare `var()`
 * references. Resolving a role therefore proves two things at once: that the
 * pairing meets its WCAG threshold, and that no diagram role hardcodes a colour.
 */
import { readFileSync } from 'node:fs';

export type Declarations = Record<string, string>;

function readDocsSiteFile(relativePath: string): string {
  return readFileSync(new URL(`../../${relativePath}`, import.meta.url), 'utf8');
}

interface Block {
  selector: string;
  body: string;
}

/** Flat top-level rule blocks, comments stripped. Nested at-rules are ignored. */
function blocks(css: string): Block[] {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const found: Block[] = [];
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let match: RegExpExecArray | null;
  while ((match = rule.exec(clean)) !== null) {
    found.push({ selector: match[1].trim(), body: match[2] });
  }
  return found;
}

function parseDeclarations(body: string): Declarations {
  const out: Declarations = {};
  for (const declaration of body.split(';')) {
    const colon = declaration.indexOf(':');
    if (colon === -1) continue;
    const name = declaration.slice(0, colon).trim();
    const value = declaration.slice(colon + 1).trim();
    if (name.length > 0 && value.length > 0) out[name] = value;
  }
  return out;
}

/**
 * The `--admob-*` palette for one theme, read out of tokens.css.
 *
 * tokens.css mentions `data-theme='dark'` twice — once for the token block and
 * once for the Starlight `--sl-*` remapping — so the block is identified by the
 * presence of `--admob-paper`, never by source order.
 */
export function admobPalette(theme: 'light' | 'dark'): Declarations {
  const css = readDocsSiteFile('src/styles/tokens.css');
  const needle = theme === 'light' ? "data-theme='light'" : "data-theme='dark'";
  for (const block of blocks(css)) {
    if (!block.selector.includes(needle)) continue;
    const declarations = parseDeclarations(block.body);
    if (declarations['--admob-paper']) return declarations;
  }
  throw new Error(`tokens.css has no --admob-* palette block for the ${theme} theme`);
}

/** The theme-independent `--admob-*` tokens from :root in tokens.css. */
export function themeIndependentTokens(): Declarations {
  const css = readDocsSiteFile('src/styles/tokens.css');
  for (const block of blocks(css)) {
    if (block.selector !== ':root') continue;
    const declarations = parseDeclarations(block.body);
    // Verify this is the theme-independent block by checking for a stage token
    if (declarations['--admob-stage-ink']) return declarations;
  }
  throw new Error('tokens.css has no theme-independent --admob-* token block (expected --admob-stage-ink)');
}

/** The `--dg-*` colour-role block from diagrams.css, identified by `--dg-ink`. */
export function diagramColourRoles(): Declarations {
  const css = readDocsSiteFile('src/styles/diagrams.css');
  for (const block of blocks(css)) {
    const declarations = parseDeclarations(block.body);
    if (declarations['--dg-ink']) return declarations;
  }
  throw new Error('diagrams.css has no --dg-* colour-role block (expected one declaring --dg-ink)');
}

/** Declarations of a single rule in diagrams.css, by exact selector text. */
export function diagramRule(selector: string): Declarations {
  const css = readDocsSiteFile('src/styles/diagrams.css');
  for (const block of blocks(css)) {
    if (block.selector === selector) return parseDeclarations(block.body);
  }
  throw new Error(`diagrams.css has no rule with the exact selector "${selector}"`);
}

/** Resolves a `--dg-*` role to a literal hex, rejecting anything but a bare var(). */
export function resolveRole(role: string, roles: Declarations, palette: Declarations): string {
  const value = roles[role];
  if (!value) throw new Error(`diagrams.css does not declare ${role}`);
  const reference = /^var\(\s*(--admob-[a-z-]+)\s*\)$/.exec(value);
  if (!reference) {
    throw new Error(
      `${role} must be a bare var() reference to an --admob-* token so it re-themes, ` +
        `but diagrams.css sets it to "${value}"`
    );
  }
  const hex = palette[reference[1]];
  if (!hex) throw new Error(`${reference[1]} is not defined in this theme's tokens.css block`);
  return hex;
}

function channel(value: number): number {
  const srgb = value / 255;
  return srgb <= 0.03928 ? srgb / 12.92 : ((srgb + 0.055) / 1.055) ** 2.4;
}

function relativeLuminance(hex: string): number {
  const digits = hex.trim().replace('#', '');
  if (!/^[0-9a-fA-F]{6}$/.test(digits)) {
    throw new Error(`Expected a 6-digit hex colour, got "${hex}"`);
  }
  const r = channel(Number.parseInt(digits.slice(0, 2), 16));
  const g = channel(Number.parseInt(digits.slice(2, 4), 16));
  const b = channel(Number.parseInt(digits.slice(4, 6), 16));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.1 contrast ratio, 1..21. */
export function contrastRatio(foreground: string, background: string): number {
  const a = relativeLuminance(foreground);
  const b = relativeLuminance(background);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}
