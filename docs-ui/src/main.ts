import { buildModel, STATE_CHANGING, type Model } from './model';
import { fillPath } from './curl';
import { highlightJson } from './markdown';
import type { OpenApi } from './spec';
import { uuid } from './uuid';
import { failure, intro, introId, notFound, sectionView, sidebar } from './views';

const SPEC_URL = '/docs/openapi.json';
const $ = <T extends HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<T>(sel);

let model: Model | null = null;
let etag = '';
let key = uuid();
let query = '';

const store = {
  get: (k: string) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

// ---- loading the spec: a conditional request, so the page only re-renders when the document changed ------------------

async function load(force = false): Promise<void> {
  try {
    const res = await fetch(SPEC_URL, { cache: 'no-cache', headers: etag && !force ? { 'If-None-Match': etag } : {} });
    if (res.status === 304) return;
    if (!res.ok) throw new Error(`The server answered ${res.status} for ${SPEC_URL}.`);
    const next = res.headers.get('ETag') ?? '';
    if (next && next === etag && model) return;
    etag = next;
    model = buildModel((await res.json()) as OpenApi);
    document.title = `${model.spec.info.title} · API reference`;
    paint();
  } catch (err) {
    if (!model) $('#main')!.innerHTML = failure(err instanceof Error ? err.message : String(err));
  }
}

// ---- routing: #/<section>[/<operation>] ------------------------------------------------------------------------------

function route(): { slug: string; op: string } {
  const [slug = '', op = ''] = location.hash.replace(/^#\/?/, '').split('/');
  return { slug: slug || (model?.groups[0]?.sections[0]?.slug ?? introId), op };
}

function paint(): void {
  if (!model) return;
  const { slug, op } = route();
  const ctx = { origin: location.origin, idempotencyKey: key };
  const main = $('#main')!;
  const section = model.groups.flatMap((g) => g.sections).find((s) => s.slug === slug);
  main.innerHTML = slug === introId ? intro(model.spec) : section ? sectionView(model, section, ctx) : notFound(slug);
  $('#nav')!.innerHTML = sidebar(model, section?.slug ?? slug, query);
  main.querySelectorAll<HTMLInputElement>('[data-key]').forEach((i) => (i.value = key));
  document.body.classList.remove('loading', 'menu-open');
  const target = op ? document.getElementById(op) : null;
  if (target) target.scrollIntoView({ block: 'start' });
  else window.scrollTo(0, 0);
}

// ---- the Idempotency-Key generator -----------------------------------------------------------------------------------

function showKey(next: string): void {
  const old = key;
  key = next;
  $('#uuid')!.textContent = key;
  document.querySelectorAll<HTMLInputElement>('[data-key]').forEach((i) => { if (!i.value || i.value === old) i.value = key; });
  document.querySelectorAll<HTMLElement>('.snippet pre code').forEach((c) => { if (c.textContent?.includes(old)) c.textContent = c.textContent.split(old).join(key); });
  document.querySelectorAll<HTMLElement>('[data-copy]').forEach((b) => { const v = b.dataset.copy; if (v?.includes(old)) b.dataset.copy = v.split(old).join(key); });
}

async function copy(text: string, button: HTMLElement): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const t = document.createElement('textarea');
    t.value = text;
    document.body.append(t);
    t.select();
    document.execCommand('copy');
    t.remove();
  }
  const label = button.textContent;
  button.textContent = 'Copied';
  button.classList.add('done');
  setTimeout(() => { button.textContent = label; button.classList.remove('done'); }, 1400);
}

// ---- try it ------------------------------------------------------------------------------------------------------------

async function send(form: HTMLFormElement): Promise<void> {
  const op = model?.ops.find((o) => o.id === form.dataset.try);
  if (!op) return;
  const values: Record<string, string> = {};
  const search = new URLSearchParams();
  form.querySelectorAll<HTMLInputElement>('[data-param]').forEach((i) => {
    const [where, name] = i.dataset.param!.split(':');
    if (where === 'path') values[name] = i.value;
    else if (i.value) search.set(name, i.value);
  });
  const headers: Record<string, string> = {};
  const token = $<HTMLInputElement>('[data-token]', form)?.value;
  if (token) headers.Authorization = `Bearer ${token}`;
  const idem = $<HTMLInputElement>('[data-key]', form)?.value;
  if (idem && STATE_CHANGING.has(op.method)) headers['Idempotency-Key'] = idem;
  const bodyText = $<HTMLTextAreaElement>('[data-body]', form)?.value;
  if (bodyText) headers['Content-Type'] = 'application/json';

  const status = $('[data-status]', form)!;
  const out = $<HTMLElement>('[data-out]', form)!;
  status.textContent = 'Sending…';
  const started = performance.now();
  try {
    const res = await fetch(fillPath(op.path, values) + (search.size ? `?${search}` : ''), { method: op.method.toUpperCase(), headers, body: bodyText || undefined });
    const text = await res.text();
    let shown = text;
    try { shown = JSON.stringify(JSON.parse(text), null, 2); } catch { /* not JSON */ }
    status.textContent = `${res.status} ${res.statusText} · ${Math.round(performance.now() - started)} ms`;
    status.className = `try-status ${res.ok ? 'ok' : 'bad'}`;
    $('code', out)!.innerHTML = highlightJson(shown);
  } catch (err) {
    status.textContent = 'The request did not complete.';
    status.className = 'try-status bad';
    $('code', out)!.textContent = err instanceof Error ? err.message : String(err);
  }
  out.hidden = false;
}

// ---- wiring -------------------------------------------------------------------------------------------------------------

function applyTheme(theme: string | null): void {
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
}

function start(): void {
  applyTheme(store.get('cx-theme'));
  $('#uuid')!.textContent = key;

  document.addEventListener('click', (ev) => {
    const el = ev.target as HTMLElement;
    const copyBtn = el.closest<HTMLElement>('[data-copy]');
    if (copyBtn) return void copy(copyBtn.dataset.copy ?? '', copyBtn);
    if (el.closest('#uuid-copy')) {
      const btn = $('#uuid-copy')!;
      void copy(key, btn).then(() => setTimeout(() => showKey(uuid()), 1400));
    } else if (el.closest('#uuid-new')) showKey(uuid());
    else if (el.closest('#theme')) {
      const dark = document.documentElement.dataset.theme === 'dark' || (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
      const next = dark ? 'light' : 'dark';
      store.set('cx-theme', next);
      applyTheme(next);
    } else if (el.closest('#menu')) document.body.classList.toggle('menu-open');
    else if (el.closest('[data-reload]')) void load(true);
    else if (el.closest('.nav-link, .nav-op')) document.body.classList.remove('menu-open');
  });
  document.addEventListener('submit', (ev) => {
    const form = (ev.target as HTMLElement).closest<HTMLFormElement>('form.try');
    if (form) { ev.preventDefault(); void send(form); }
  });
  $<HTMLInputElement>('#search')!.addEventListener('input', (ev) => {
    query = (ev.target as HTMLInputElement).value;
    if (model) $('#nav')!.innerHTML = sidebar(model, route().slug, query);
  });
  addEventListener('hashchange', paint);
  // The document is only fetched again when the tab comes back to the front, and only re-rendered if its ETag changed.
  document.addEventListener('visibilitychange', () => { if (!document.hidden) void load(); });
  addEventListener('keydown', (ev) => {
    if (ev.key === '/' && !(ev.target instanceof HTMLInputElement) && !(ev.target instanceof HTMLTextAreaElement)) { ev.preventDefault(); $('#search')!.focus(); }
  });
  void load();
}

start();
