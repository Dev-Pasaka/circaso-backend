import { escapeHtml as e, highlightJson, inline, render } from './markdown';
import { curl, bodyExample } from './curl';
import { STATE_CHANGING, example, fieldRows, requestSchema, resolve, responseEntries, type FieldRow, type Group, type Model, type Op, type Section } from './model';
import type { OpenApi, Schema } from './spec';

/** Every view is a pure function from the spec to an HTML string, so a change to the spec is the only thing that changes the page. */

export interface Ctx {
  origin: string;
  idempotencyKey: string;
}

export const introId = 'introduction';

export function sidebar(model: Model, active: string, query: string): string {
  const q = query.trim().toLowerCase();
  const matches = (op: Op) => !q || `${op.title} ${op.path} ${op.tag} ${op.code}`.toLowerCase().includes(q);
  const link = (href: string, label: string, on: boolean, extra = '') =>
    `<a class="nav-link${on ? ' on' : ''}" href="${href}"${on ? ' aria-current="page"' : ''}>${label}${extra}</a>`;
  let html = q ? '' : `<div class="nav-group">${link('#/introduction', 'Introduction', active === introId)}</div>`;
  for (const group of model.groups) {
    const body = group.sections
      .map((s) => {
        const hits = s.ops.filter(matches);
        if (!hits.length) return '';
        const header = link(`#/${s.slug}`, e(s.tag), active === s.slug, `<span class="count">${s.ops.length}</span>`);
        const ops = q
          ? hits.map((o) => `<a class="nav-op" href="#/${s.slug}/${o.id}"><span class="m m-${o.method}">${o.method}</span>${e(o.title)}</a>`).join('')
          : '';
        return header + ops;
      })
      .join('');
    if (body) html += `<div class="nav-group"><div class="nav-title">${e(group.name)}</div>${body}</div>`;
  }
  return html || '<p class="nav-empty">Nothing matches.</p>';
}

export function intro(spec: OpenApi): string {
  const servers = (spec.servers ?? [])
    .map((s) => `<tr><td><code>${e(s.url)}</code></td><td>${e(s.description ?? '')}</td></tr>`)
    .join('');
  return (
    `<header class="hero"><p class="eyebrow">API reference · v${e(spec.info.version)}</p><h1>${e(spec.info.title)}</h1></header>` +
    `<div class="prose">${render(spec.info.description ?? '').html}</div>` +
    (servers ? `<h2 id="servers">Servers</h2><div class="table-wrap"><table><thead><tr><th>URL</th><th>Environment</th></tr></thead><tbody>${servers}</tbody></table></div>` : '')
  );
}

export function sectionView(model: Model, s: Section, ctx: Ctx): string {
  const group = model.groups.find((g) => g.sections.includes(s));
  return (
    `<header class="hero"><p class="eyebrow">${e(group?.name ?? '')}</p><h1>${e(s.tag)}</h1></header>` +
    `<div class="prose">${render(s.description).html}</div>` +
    s.ops.map((o) => opView(model.spec, o, ctx)).join('')
  );
}

function fieldTable(rows: FieldRow[], emptyText: string): string {
  if (!rows.length) return `<p class="muted">${e(emptyText)}</p>`;
  return (
    '<div class="table-wrap"><table class="fields"><thead><tr><th>Field</th><th>Type</th><th>Description</th></tr></thead><tbody>' +
    rows
      .map((r) => {
        const enums = r.enumValues.length ? ` <span class="muted">One of ${r.enumValues.map((v) => `<code>${e(v)}</code>`).join(', ')}.</span>` : '';
        return (
          `<tr><td><code class="field" style="padding-left:${r.depth * 14}px">${e(r.path.slice(r.path.lastIndexOf('.') + 1) || r.path)}</code>` +
          `${r.required ? '<span class="req">required</span>' : ''}</td><td><code class="type">${e(r.type)}</code></td>` +
          `<td>${inline(r.description)}${enums}</td></tr>`
        );
      })
      .join('') +
    '</tbody></table></div>'
  );
}

const jsonBlock = (value: unknown) => `<pre class="code" data-lang="json"><code>${highlightJson(JSON.stringify(value, null, 2))}</code></pre>`;

function copyable(label: string, html: string, raw: string): string {
  return `<div class="snippet"><div class="snippet-bar"><span>${e(label)}</span><button type="button" class="ghost" data-copy="${e(raw)}">Copy</button></div>${html}</div>`;
}

function parameterTable(op: Op): string {
  const params = op.parameters;
  if (!params.length) return '';
  return (
    '<h4>Parameters</h4><div class="table-wrap"><table class="fields"><thead><tr><th>Name</th><th>In</th><th>Description</th></tr></thead><tbody>' +
    params
      .map(
        (p) =>
          `<tr><td><code class="field">${e(p.name)}</code>${p.required ? '<span class="req">required</span>' : ''}</td><td><code class="type">${e(p.in)}</code></td><td>${inline(p.description ?? '')}</td></tr>`,
      )
      .join('') +
    '</tbody></table></div>'
  );
}

/** The part of a response worth a table: the `data` of the envelope when there is one, else the schema itself. */
function payload(spec: OpenApi, schema: Schema | undefined): Schema | undefined {
  const resolved = resolve(spec, schema);
  return resolved.properties?.data && resolved.properties.status ? resolved.properties.data : schema;
}

export function opView(spec: OpenApi, op: Op, ctx: Ctx): string {
  const reqSchema = requestSchema(op.op);
  const body = bodyExample(spec, op);
  const responses = responseEntries(op.op);
  const out =
    `<section class="op" id="${op.id}" data-op="${op.id}">` +
    `<div class="op-head"><span class="m m-${op.method}">${op.method}</span><code class="path">${e(op.path)}</code>` +
    `${op.scope ? `<span class="badge scope" title="Token scope required">${e(op.scope)}</span>` : op.secured ? '' : '<span class="badge open">no token</span>'}</div>` +
    `<h3>${op.code ? `<span class="code-no">${e(op.code)}</span>` : ''}${e(op.title)}</h3>` +
    `<div class="prose">${render(op.description).html}</div>` +
    parameterTable(op) +
    (reqSchema ? `<h4>Request body</h4>${fieldTable(fieldRows(spec, reqSchema), 'No fields.')}` : '') +
    (op.errors.length ? `<h4>Errors</h4><p class="chips">${op.errors.map((c) => `<code class="chip">${e(c)}</code>`).join('')}</p>` : '') +
    responses
      .map((r) => {
        const rows = fieldRows(spec, payload(spec, r.schema));
        return (
          `<h4>Response <span class="status">${e(r.status)}</span></h4><p class="muted">${inline(r.description)}</p>` +
          (rows.length ? fieldTable(rows, '') : '') +
          (r.schema ? copyable('Example response', jsonBlock(example(spec, r.schema)), JSON.stringify(example(spec, r.schema), null, 2)) : '')
        );
      })
      .join('') +
    `<h4>Try it</h4>` +
    copyable('cURL', `<pre class="code"><code>${e(curl(spec, op, ctx.origin, ctx.idempotencyKey))}</code></pre>`, curl(spec, op, ctx.origin, ctx.idempotencyKey)) +
    tryForm(op, body) +
    `</section>`;
  return out;
}

function tryForm(op: Op, body: string | null): string {
  const inputs = op.parameters
    .filter((p) => p.in === 'path' || p.in === 'query')
    .map(
      (p) =>
        `<label>${e(p.name)} <small>${e(p.in)}</small><input data-param="${e(p.in)}:${e(p.name)}" placeholder="${e(p.name)}" autocomplete="off" spellcheck="false"></label>`,
    )
    .join('');
  const key = STATE_CHANGING.has(op.method) && op.parameters.some((p) => p.name === 'Idempotency-Key')
    ? `<label>Idempotency-Key <small>header</small><input data-key autocomplete="off" spellcheck="false"></label>`
    : '';
  const token = op.secured ? `<label>Access token <small>Bearer</small><input data-token type="password" autocomplete="off" placeholder="paste an access token"></label>` : '';
  return (
    `<form class="try" data-try="${op.id}" autocomplete="off"><div class="try-grid">${token}${key}${inputs}</div>` +
    (body !== null ? `<label>Body <small>JSON</small><textarea data-body rows="${Math.min(14, body.split('\n').length + 1)}" spellcheck="false">${e(body)}</textarea></label>` : '') +
    `<div class="try-actions"><button type="submit" class="primary">Send request</button><span class="try-status" data-status></span></div>` +
    `<pre class="code try-out" data-out hidden><code></code></pre></form>`
  );
}

export const notFound = (what: string) => `<header class="hero"><h1>Not found</h1></header><p class="muted">There is nothing called <code>${e(what)}</code> in this API.</p>`;
export const failure = (message: string) =>
  `<header class="hero"><h1>The spec did not load</h1></header><p class="muted">${e(message)}</p><p><button type="button" class="primary" data-reload>Try again</button></p>`;
export type { Group };
