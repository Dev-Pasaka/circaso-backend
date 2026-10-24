// The API reference for local development: `bun run serve`, then open http://localhost:8090/docs.
// It serves the built files (run `bun run build` after changing src/) and sends /docs/openapi.json to the app, which
// builds the spec from its routes. In the dev server and in production the docs image and nginx do the same job.
import { join } from 'node:path';

const root = join(import.meta.dir, 'dist', 'docs');
const app = process.env.APP_URL ?? 'http://localhost:8080';
const port = Number(process.env.DOCS_PORT ?? 8090);

const files: Record<string, string> = {
  '/docs': 'index.html',
  '/docs/': 'index.html',
  '/docs/app.js': 'app.js',
  '/docs/app.css': 'app.css',
};

Bun.serve({
  port,
  async fetch(request) {
    const { pathname, search } = new URL(request.url);
    if (pathname === '/docs/openapi.json') {
      return fetch(`${app}${pathname}${search}`, { headers: request.headers }).catch(
        () => new Response(`The app is not answering at ${app}. Start it with ./gradlew :app:run.`, { status: 502 }),
      );
    }
    const name = files[pathname];
    if (!name) return new Response('Not found', { status: 404 });
    return new Response(Bun.file(join(root, name)), { headers: { 'Cache-Control': 'no-cache' } });
  },
});
console.log(`API reference on http://localhost:${port}/docs  (spec from ${app})`);
