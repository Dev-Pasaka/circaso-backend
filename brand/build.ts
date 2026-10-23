// Builds the Circaso logo files into ./logo: `bun run build.ts`.
// The mark is the one on circaso.com (a disc with a ring cut out and an opening to the right, like a speech bubble). The SVGs are written
// here without the site's metadata; the PNGs are drawn by Chrome (headless) so they keep a transparent background.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const out = join(import.meta.dir, 'logo');
mkdirSync(join(out, 'asha'), { recursive: true });
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FONT = "-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Helvetica Neue', Arial, sans-serif";

/** The cut-out: a ring (r 48 to 20) and the opening to the right. Everything inside the mask is removed from the disc. */
const mask = (id: string) =>
  `<mask id="${id}" maskUnits="userSpaceOnUse" x="0" y="0" width="200" height="200"><rect width="200" height="200" fill="#fff"/><g fill="#000"><path fill-rule="evenodd" d="M100,52 A48,48 0 1 0 100,148 A48,48 0 1 0 100,52 Z M100,80 A20,20 0 1 1 100,120 A20,20 0 1 1 100,80 Z"/><path d="M143.6,80 L240,40 L240,160 L143.6,120 Z"/></g></mask>`;

const silver = (k: string) =>
  `<defs><linearGradient id="g${k}" x1="0.15" y1="0.05" x2="0.85" y2="0.95"><stop offset="0" stop-color="#fbfbfd"/><stop offset="0.32" stop-color="#d4d5d9"/><stop offset="0.55" stop-color="#8f9298"/><stop offset="0.78" stop-color="#dcdde1"/><stop offset="1" stop-color="#f4f4f6"/></linearGradient>` +
  `<radialGradient id="h${k}" cx="0.32" cy="0.26" r="0.55"><stop offset="0" stop-color="#fff" stop-opacity="0.75"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>` +
  `<clipPath id="c${k}"><circle cx="100" cy="100" r="92"/></clipPath>${mask('m' + k)}</defs>` +
  `<g mask="url(#m${k})"><circle cx="100" cy="100" r="92" fill="url(#g${k})"/><circle cx="100" cy="100" r="92" fill="url(#h${k})"/>` +
  `<circle cx="100" cy="100" r="83" fill="none" stroke="#fff" stroke-opacity="0.45" stroke-width="1.4"/><circle cx="100" cy="100" r="91" fill="none" stroke="#5d6066" stroke-opacity="0.35" stroke-width="1.2"/>` +
  `<g clip-path="url(#c${k})" fill="none" stroke="#3b3d42" stroke-opacity="0.35" stroke-width="2.4"><path d="M143.6,120 A48,48 0 1 1 143.6,80"/><circle cx="100" cy="100" r="20"/><path d="M143.6,80 L240,40 M143.6,120 L240,160"/></g></g>`;

const solid = (k: string, color: string) =>
  `<defs>${mask('m' + k)}</defs><g mask="url(#m${k})"><circle cx="100" cy="100" r="92" fill="${color}"/></g>`;

const svg = (w: number, h: number, body: string, title: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" role="img" aria-label="${title}"><title>${title}</title>${body}</svg>\n`;

const INK = '#0b0b0c';

/** Asha: Circaso's ring with the same opening to the right, and a four-point spark inside it. */
const spark = (c: string) =>
  `<path fill="${c}" d="M100,64 C104.5,87 113,95.5 136,100 C113,104.5 104.5,113 100,136 C95.5,113 87,104.5 64,100 C87,95.5 95.5,87 100,64 Z"/><path fill="${c}" d="M152,28 C153.5,35 155.5,37 162.5,38.5 C155.5,40 153.5,42 152,49 C150.5,42 148.5,40 141.5,38.5 C148.5,37 150.5,35 152,28 Z"/>`;
const ring = (c: string) => `<path d="M152,120.8 A56,56 0 1 1 152,79.2" fill="none" stroke="${c}" stroke-width="9" stroke-linecap="round"/>`;
const ashaDisc = (disc: string, ink: string) => `<circle cx="100" cy="100" r="92" fill="${disc}"/>${ring(ink)}${spark(ink)}`;

const files: Record<string, { w: number; h: number; body: string; title: string }> = {
  'circaso-mark': { w: 200, h: 200, body: silver('a'), title: 'Circaso' },
  'circaso-mark-black': { w: 200, h: 200, body: solid('b', INK), title: 'Circaso' },
  'circaso-mark-white': { w: 200, h: 200, body: solid('w', '#ffffff'), title: 'Circaso' },
  // The icon: a black rounded square with the white mark, for avatars, link previews and app stores.
  'circaso-icon': { w: 200, h: 200, body: `<rect width="200" height="200" rx="46" fill="${INK}"/><g transform="translate(34 34) scale(.66)">${solid('i', '#ffffff')}</g>`, title: 'Circaso' },
};
const lockup = (id: string, color: string) =>
  `${id === 'k' ? solid('lk', color) : solid('lw', color)}<text x="214" y="132" font-family="${FONT}" font-size="112" font-weight="700" letter-spacing="-3.4" fill="${color}">Circaso</text>`;
files['asha/asha-icon'] = { w: 200, h: 200, body: ashaDisc(INK, '#ffffff'), title: 'Asha' };
files['asha/asha-icon-white'] = { w: 200, h: 200, body: `<circle cx="100" cy="100" r="91" fill="#fff" stroke="#e3e3e8" stroke-width="2"/>${ring(INK)}${spark(INK)}`, title: 'Asha' };
files['asha/asha-icon-silver'] = {
  w: 200, h: 200, title: 'Asha',
  body: `<defs><linearGradient id="gs" x1="0.15" y1="0.05" x2="0.85" y2="0.95"><stop offset="0" stop-color="#fbfbfd"/><stop offset="0.32" stop-color="#d4d5d9"/><stop offset="0.55" stop-color="#8f9298"/><stop offset="0.78" stop-color="#dcdde1"/><stop offset="1" stop-color="#f4f4f6"/></linearGradient></defs><circle cx="100" cy="100" r="92" fill="url(#gs)"/>${ring(INK)}${spark(INK)}`,
};
files['circaso-logo-black'] = { w: 600, h: 200, body: lockup('k', INK), title: 'Circaso' };
files['circaso-logo-white'] = { w: 600, h: 200, body: lockup('w', '#ffffff'), title: 'Circaso' };

for (const [name, f] of Object.entries(files)) writeFileSync(join(out, `${name}.svg`), svg(f.w, f.h, f.body, f.title));

// PNGs: [file, width in px]. The logo files are 3:1.
const pngs: [string, number][] = [
  ['asha/asha-icon', 1024], ['asha/asha-icon', 192], ['asha/asha-icon', 96], ['asha/asha-icon-white', 192], ['asha/asha-icon-silver', 1024], ['asha/asha-icon-silver', 512], ['asha/asha-icon-silver', 192], ['asha/asha-icon-silver', 96],
  ['circaso-mark', 1024], ['circaso-mark', 256], ['circaso-mark-black', 512], ['circaso-mark-white', 512],
  ['circaso-icon', 1024], ['circaso-icon', 180], ['circaso-icon', 64],
  ['circaso-logo-black', 1200], ['circaso-logo-white', 1200], ['circaso-logo-black', 480], ['circaso-logo-white', 480],
];
for (const [name, width] of pngs) {
  const f = files[name];
  const height = Math.round((width * f.h) / f.w);
  const page = join(out, `.${name.replace("/", "-")}.html`);
  writeFileSync(page, `<!doctype html><meta charset="utf-8"><style>html,body{margin:0;background:transparent}svg{display:block;width:${width}px;height:${height}px}</style>${svg(f.w, f.h, f.body, f.title)}`);
  const target = join(out, `${name}-${width}.png`);
  const r = spawnSync(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', '--default-background-color=00000000', `--window-size=${width},${height}`, `--screenshot=${target}`, `file://${page}`], { encoding: 'utf8' });
  rmSync(page);
  if (r.status !== 0) throw new Error(`Chrome failed for ${name}: ${r.stderr}`);
  console.log(`${name}-${width}.png`);
}
