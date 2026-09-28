import { getCollection } from 'astro:content';
import { OGImageRoute } from 'astro-og-canvas';
import { normalizeEntryId } from '../../lib/seo';

const entries = await getCollection('docs');

const pages = Object.fromEntries(
  entries.map((entry) => [
    normalizeEntryId(entry.id),
    {
      title: entry.data.title,
      description: entry.data.description ?? '',
    },
  ])
);

export const { getStaticPaths, GET } = await OGImageRoute({
  pages,
  getImageOptions: (_path, page: { title: string; description: string }) => ({
    title: page.title,
    description: page.description,
    logo: { path: './src/assets/logo.svg', size: [72] },
    // Mirrors the dark theme in src/styles/tokens.css — --admob-paper warmed
    // towards --admob-accent in the far corner. Nothing checks this pairing, so
    // it has to be updated by hand whenever the palette moves.
    bgGradient: [
      [14, 15, 16], // --admob-paper
      [34, 22, 20],
    ],
    border: { color: [238, 58, 32], width: 12, side: 'inline-start' }, // --admob-accent
    padding: 60,
    // Noto Sans, not the site's Archivo: astro-og-canvas rasterises through
    // canvaskit, which renders a variable font at its default instance only —
    // the SemiBold title would silently come back at weight 400. Static faces
    // keep the weight contrast that makes these cards readable as thumbnails.
    font: {
      title: {
        families: ['Noto Sans'],
        weight: 'SemiBold',
        color: [237, 238, 236], // --admob-ink
        size: 66,
        lineHeight: 1.1,
      },
      description: {
        families: ['Noto Sans'],
        weight: 'Normal',
        color: [154, 159, 156], // --admob-slate
        size: 32,
        lineHeight: 1.4,
      },
    },
    fonts: [
      './node_modules/@fontsource/noto-sans/files/noto-sans-latin-600-normal.woff2',
      './node_modules/@fontsource/noto-sans/files/noto-sans-latin-400-normal.woff2',
    ],
    format: 'PNG',
  }),
});
