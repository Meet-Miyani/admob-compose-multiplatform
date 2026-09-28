# Design system — ads.avinya.dev

Internal notes for anyone changing how the docs site looks. This file is not
part of the built site: `docsLoader()` reads only `src/content/docs/`, so
nothing here reaches the sitemap, `llms.txt`, or search.

Read this before editing `src/styles/tokens.css`, `landing.css`, or
`diagrams.css`. The automated gates will stop you breaking the *mechanics* of
the system; they cannot tell you what it is for.

## Why this file exists

An earlier version of the site encoded an anti-design policy directly into its
gates: `scripts/check-theme.mjs` required the `-apple-system` font stack and
explicitly forbade Space Grotesk and Inter, and asserted `border-radius <= 6px`,
`box-shadow: none` and `transform: none` on every landing element.
`test/landing.test.ts` additionally banned transforms, animations, all
gradients, and uppercase text in the landing sources.

Nothing recorded *why*, so the constraints outlived whatever reasoning produced
them, and the site ended up looking like an unstyled GitHub README. Both files
were rewritten in August 2026 to enforce the system described below instead. If
you find yourself reintroducing flat/no-motion rules, that is a regression, not
a restoration.

## Tokens

`src/styles/tokens.css` is the only file allowed to contain literal colours,
font stacks, or raw radius values. Everything else references `--admob-*`. The
header comment in that file lists the public contract; `diagrams.css`,
`landing.css`, `mermaid.css`, and `test/helpers/css-tokens.ts` all consume those
names, so renaming one is a breaking change.

### Colour

Two themes, switched by `data-theme` on `<html>`. Both are the avinya.dev
palette, so the docs and the studio site read as one family: dark is the default
and is a cool near-black (`#0e0f10`), light is paper `#f3f4f2` with ink
`#16181a`. The accent `#ee3a20` is the brand constant and is the same in both.

Some surfaces deliberately do **not** re-skin with the theme. The landing
stage and the consent band are dark in both themes (`--admob-stage*`), and the
phones inside the stage always show a light app screen (`--admob-screen*`) —
they read as physical objects, not page chrome. Anything placed on them reads
from those tokens, never from `--admob-ink`/`--admob-slate`, and
`--admob-stage-accent` is the accent on dark (the page's `--admob-accent-text`
is a dark red in the light theme and fails on the stage).

Four pairings are enforced by `test/diagram-contrast.test.ts` **in both themes**:

| Pairing | Minimum | Why |
|---|---|---|
| `--admob-ink` on `--admob-paper` and on `--admob-surface` | 4.5:1 | body text |
| `--admob-slate` on `--admob-paper` and on `--admob-surface` | 4.5:1 | secondary text and every informational boundary |
| `--admob-accent` on both | 3:1 | strokes and large text only |
| `--admob-accent-text` on `--admob-paper` | 4.5:1 | links (checked live by the theme gate) |

`--admob-accent` must never be used for body-size text — it does not clear 4.5:1
on `--admob-surface` in the light theme. `--admob-accent-text` is the text-safe
accent and is a different value per theme.

If you change any of these, run `npx vitest run test/diagram-contrast.test.ts`
before anything else; it fails fast and tells you which pairing broke.

### Type

One variable superfamily plus the mono. `@fontsource-variable/archivo` supplies
both axes in a single self-hosted file at `public/fonts/archivo-wdth.woff2`:
`wght` 100–900 and `wdth` 62–125, exposed to CSS as `font-stretch`.

- **Display** — Archivo at `--admob-stretch-display` (104%), weight 700–750.
  Headings, the site title. Never running text.
- **Display lines** — 112–115% width, weight 800, on the landing page's big
  lines only: "Ship AdMob from commonMain." (115%), the section headings
  (112%) and the closing "One line in commonMain." (115%); docs page titles
  use 112%. A single display line can afford the width that an outline of
  running headings cannot.
- **Body** — the same family at normal width.
- **Utility** — JetBrains Mono, for content that genuinely *is* code or data:
  eyebrows, column headers, API signatures, version strings, dimensions.

The width axis is the thing that makes headings read as headings. Keep it
modest: past roughly 106% the wide letterforms cost more in legibility than they
return in character, and this is a reference site.

**The landing <h1> is deliberately small.** It is the keyword title from
frontmatter ("Compose Multiplatform AdMob SDK for Android and iOS"), drawn as
the 12px mono label beside the version pill, exactly where the design canvas
puts its label. The big "Ship AdMob from commonMain." is a display paragraph,
not a heading. Search engines read the keyword title; people read the display
line. Do not promote the display line to the <h1> without re-deciding the
keyword strategy (see the public-visibility spec).

Sizes are pinned by the theme gate at fixed viewports — landing H1 12px, landing
H2 60px desktop / 38px mobile, format card titles 22px / 18px; docs H1 48px /
36px, docs H2 26px, sidebar 14px. Changing the scale means changing those
numbers in the same commit.

### Radius, elevation, motion

Scale: `--admob-radius-sm` 4px (inline code, chips) · `--admob-radius` 8px
(controls, search) · `--admob-radius-lg` 12px (panels, code frames, tables,
figures) · `--admob-radius-xl` 18px (format cards, phone screens, roadmap
cards) · `--admob-radius-2xl` 28px (the stage and the closing call to action).
The stage phones are shape declarations for one object each, so they have
their own tokens: `--admob-radius-device` 38px / `-device-screen` 30px for
Android, `-device-ios` 44px / `-device-ios-screen` 36px for iOS (iOS hardware
really is rounder).

`0`, `50%` and `999px` are shape declarations rather than points on the scale
and are allowed directly. Everything else must be a token — enforced in source
by `landing.test.ts` and at runtime, against the resolved values, by
`check-theme.mjs`.

Depth is layered surfaces plus hairlines first; `--admob-shadow` and
`--admob-shadow-lg` are for genuinely floating things (hover lift, dialogs).
`--admob-scrim` is the modal backdrop, heavier in dark because a dark dialog
over a near-black page needs more separation than the same scrim gives on white.

Motion is allowed. The single rule: **anything that animates must answer
`prefers-reduced-motion`**, in the same file. `landing.test.ts` fails a file that
declares `@keyframes` or `animation:` without a guard, and `check-theme.mjs`
verifies live that every landing element stops under `reduce`.

Two traps, both hit in practice:

1. Do not write `animation-timeline` next to the other animation longhands. The
   minifier folds them into the `animation` shorthand, which cannot carry a
   timeline — the declaration becomes invalid and is silently dropped. Keep the
   timeline in a separate rule with a different selector shape.
2. A scroll-driven animation holds its `from` state whenever the timeline does
   not resolve: printing, full-page screenshots, reader mode. Never start one at
   `opacity: 0`, or that content simply is not there. Translate only.

## Patterns

**Tables.** One treatment everywhere. Column headers (`thead th`) are the mono
eyebrow at 11px; row headers (`tbody th`) are the body face at 14px/600 — they
are content, not labels. Hairline row separators, no vertical rules, 12px/14px
padding, and the border and radius live on the wrapper, never the table.
`.dg-table` in `diagrams.css` deliberately mirrors `.table-scroll` in
`tokens.css`; if you change one, change both.

Starlight sets `display: block; overflow-x: auto` on every `<table>`. Both of
our table families are already inside their own scroll region, so we restore
`display: table` — a block-display table sizes its box like a div while its
cells lay out wider, which renders as text clipped mid-word instead of a
scrollable table.

**Figures.** `DiagramFigure.astro` provides the frame, the keyboard-focusable
scroll region, the caption and the prose link. When a figure contains a table
the frame drops its padding so it hugs, or you get a box inside a box. The
Expand-to-dialog control is created by script and never rendered server-side: a
control that cannot work without JavaScript should not exist in the markup. It
*moves* the scroll region into the dialog rather than cloning it, because the
Mermaid SVGs carry id-scoped styles that a clone would duplicate.

**The landing page** is built to the "Landing — desktop" and "Landing — phone"
boards of the design canvas, section by section, and should stay that way:
compare against the canvas when changing it, not against the previous
version. `Hero.astro` renders the whole page — `index.mdx` has no body — as
the opening (hero, stage, facts) followed by `landing/Landing*.astro`: formats,
consent, native, parity, roadmap, closing call to action, footer.

- The **stage** is the dark panel with the `commonMain` code and the two phones
  it renders on. It is `aria-hidden`: the page's text says everything it shows.
  At phone width the phones are the same drawing at `zoom: 0.62`, not a second
  simplified one, and the code swaps to a six-line compact version.
- Each **format card** has its own illustration of where that format lands
  (strip, takeover with close, countdown and reward, intro card, splash, feed
  card). The orange region is always the ad.
- The **consent band** is the page's one full-bleed element. It paints edge to
  edge with `border-image` outset, which is ink overflow: it never widens the
  document, and it needs no box-shadow.
- **Copy buttons** ship `hidden` and are revealed by `CopyScript.astro` only
  when the clipboard API exists.

Two dark panels (stage, consent band) and one tinted panel (the closing call to
action). Everything else sits on paper. A fourth big panel is the most likely
way to make the page worse.

**Chrome.** `Header.astro` is one component with two arrangements: on the
landing page the wordmark and four links sit left and search and a GitHub
button right, on the page's 80rem column; on docs pages search sits beside the
wordmark and the links move right as quiet text. The header is solid paper with
a hairline — a translucent header over the dark panels turns into a grey smear.
Below 50rem the landing page gets a disclosure menu (docs pages already have
Starlight's). `PageTitle.astro` gives docs pages a breadcrumb read from the
sidebar, the title, and the page description as a lede. Code blocks are on the
dark stage surface in both themes (one dark Expressive Code theme).

**The logo** is the Slot mark: a phone-screen outline whose ad slot is the
Avinya dot stretched into a banner. `SiteTitle.astro` draws it inline (small
master, stroke 9 on a 64-unit grid) so it takes `currentColor` and its slot can
animate from dot to bar on load and hover. `public/favicon.svg` is a separate
16-unit drawing with no tile that swaps its stroke with `prefers-color-scheme`;
`src/assets/logo.svg` is the ink tile, used by the OG cards. Never name a logo
file or class `ad-slot`, `ad-banner` or similar: content blockers hide those.

## What enforces this

| Command | Runs where | Covers |
|---|---|---|
| `npm test` | CI and locally | source-level token rules, content contracts, diagram contrast |
| `npm run check:theme` | `scripts/release-readiness.sh` only | computed styles in both themes, type scale, focus outlines, reduced motion |
| `npm run check:overflow` | `scripts/release-readiness.sh` only | every route at 375px in both themes |
| `npm run verify` | CI and locally | canonical URLs, OG images, JSON-LD, sitemap |

Three values are held by hand and nothing checks the pairing — update them
together with the palette:

- the Mermaid `themeVariables` in `astro.config.mjs` (must be the **light**
  values; `mermaid.css` re-tints for dark)
- `<meta name="theme-color">` in `astro.config.mjs` (must equal the dark
  `--admob-paper`; it had already drifted once)
- the OG image colours in `src/pages/og/[...route].ts`

OG cards stay on Noto Sans rather than Archivo on purpose: `astro-og-canvas`
rasterises through canvaskit, which renders a variable font at its default
instance only, so a SemiBold title would silently come back at weight 400.
