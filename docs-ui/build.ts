// Bundles src/ into the gateway's resources: one minified script, one stylesheet, and the HTML shell.
import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = join(import.meta.dir, '..', 'gateway', 'src', 'main', 'resources', 'docs');
mkdirSync(out, { recursive: true });

const js = await Bun.build({ entrypoints: ['src/main.ts'], minify: true, target: 'browser', format: 'iife' });
if (!js.success) {
  for (const m of js.logs) console.error(m);
  process.exit(1);
}
writeFileSync(join(out, 'app.js'), await js.outputs[0].text());

const css = await Bun.build({ entrypoints: ['src/app.css'], minify: true });
if (!css.success) {
  for (const m of css.logs) console.error(m);
  process.exit(1);
}
writeFileSync(join(out, 'app.css'), await css.outputs[0].text());
cpSync('index.html', join(out, 'index.html'));

for (const f of ['app.js', 'app.css', 'index.html']) console.log(f.padEnd(11), readFileSync(join(out, f)).length, 'bytes');
