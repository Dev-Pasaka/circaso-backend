import { beforeAll, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// What the docs image serves: the built reader. These checks used to live in the server's end-to-end test, back when the
// app served the reader itself.
const dist = join(import.meta.dir, '..', 'dist', 'docs');
const read = (name: string) => readFileSync(join(dist, name), 'utf8');

beforeAll(async () => {
  const build = Bun.spawn(['bun', 'run', 'build.ts'], { cwd: join(import.meta.dir, '..'), stdout: 'ignore' });
  expect(await build.exited).toBe(0);
});

test('the page is Circaso’s own reader, with the key generator and a loading state', () => {
  const html = read('index.html');
  expect(html.toLowerCase()).not.toContain('swagger');
  expect(html.toLowerCase()).not.toContain('scalar');
  expect(html).toContain('/docs/app.js');
  expect(html).toContain('/docs/app.css');
  expect(html).toContain('defer');
  expect(html).toContain('id="uuid-copy"');
  expect(html).toContain('Idempotency-Key');
  expect(html).toContain('rel="preload"');
  expect(html).toContain('/docs/openapi.json');
  expect(html).toContain('class="skeleton"');
});

test('the script is a small bundle, not a framework', () => {
  const size = read('app.js').length;
  expect(size).toBeGreaterThan(5_000);
  expect(size).toBeLessThan(200_000);
});

test('the style sheet is drawn in circaso.com’s tokens and adapts to the reader’s settings', () => {
  const css = read('app.css');
  for (const token of ['#0b0b0c', '#f2f2f7', '-apple-system']) expect(css).toContain(token);
  for (const feature of ['backdrop-filter', 'prefers-reduced-motion', 'prefers-contrast']) expect(css).toContain(feature);
});
