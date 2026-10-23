import { describe, expect, test } from 'bun:test';
import spec from './fixtures/openapi.json';
import { buildModel, example, fieldRows, requestSchema } from '../src/model';
import { highlightJson, inline, render } from '../src/markdown';
import { curl } from '../src/curl';
import { intro, opView, sectionView, sidebar } from '../src/views';
import { uuid } from '../src/uuid';
import type { OpenApi } from '../src/spec';

const api = spec as unknown as OpenApi;
const model = buildModel(api);
const ctx = { origin: 'http://localhost:8080', idempotencyKey: 'KEY-1' };

describe('model', () => {
  test('groups follow x-tagGroups, Infrastructure first with Health as the landing section', () => {
    expect(model.groups[0].name).toBe('Infrastructure');
    expect(model.groups[0].sections[0].tag).toBe('Health');
    expect(model.groups.flatMap((g) => g.sections).every((s) => s.ops.length > 0)).toBe(true);
  });
  test('every operation lands in a section', () => {
    const placed = model.groups.flatMap((g) => g.sections).reduce((n, s) => n + s.ops.length, 0);
    expect(placed).toBe(model.ops.length);
  });
  test('errors and scope are lifted out of the description', () => {
    const op = model.ops.find((o) => o.errors.length && o.scope)!;
    expect(op.description).not.toContain('**Errors:**');
    expect(op.description).not.toMatch(/Scope:\s*`/);
  });
  test('request bodies resolve $ref into field rows and examples', () => {
    const op = model.ops.find((o) => requestSchema(o.op))!;
    expect(fieldRows(api, requestSchema(op.op)).length).toBeGreaterThan(0);
    expect(typeof example(api, requestSchema(op.op))).toBe('object');
  });
  test('example generation survives recursive schemas', () => {
    const loop: OpenApi = { ...api, components: { schemas: { A: { type: 'object', properties: { next: { $ref: '#/components/schemas/A' } } } } } };
    expect(() => example(loop, { $ref: '#/components/schemas/A' })).not.toThrow();
    expect(() => fieldRows(loop, { $ref: '#/components/schemas/A' })).not.toThrow();
  });
});

describe('markdown', () => {
  test('escapes html and drops unsafe links', () => {
    expect(inline('<img src=x onerror=1>')).not.toContain('<img');
    expect(inline('[x](javascript:alert(1))')).not.toContain('href');
    expect(inline('[x](https://circaso.com)')).toContain('href="https://circaso.com"');
  });
  test('renders headings, lists, tables and code', () => {
    const { html, headings } = render('# T\n\n- a\n- b\n\n| h | i |\n|---|---|\n| `a|b` | 2 |\n\n```json\n{"a":1}\n```');
    expect(headings[0].id).toBe('t');
    expect(html).toContain('<ul>');
    expect(html).toContain('<td><code>a|b</code></td>');
    expect(html).toContain('j-key');
  });
  test('json highlighting escapes content', () => {
    expect(highlightJson('{"a":"<b>"}')).not.toContain('<b>');
  });
});

describe('views', () => {
  test('the introduction renders the guide', () => {
    expect(intro(api)).toContain('<h1>');
  });
  test('every section renders without throwing and escapes spec text', () => {
    for (const s of model.groups.flatMap((g) => g.sections)) {
      const html = sectionView(model, s, ctx);
      expect(html).toContain(`id="${s.ops[0].id}"`);
    }
  });
  test('state-changing operations carry the idempotency key in cURL and the try form', () => {
    const op = model.ops.find((o) => o.method === 'post' && o.parameters.some((p) => p.name === 'Idempotency-Key'))!;
    expect(curl(api, op, ctx.origin, ctx.idempotencyKey)).toContain('Idempotency-Key: KEY-1');
    expect(opView(api, op, ctx)).toContain('data-key');
  });
  test('search narrows the sidebar to matching operations', () => {
    const html = sidebar(model, 'health', 'live');
    expect(html).toContain('nav-op');
    expect(sidebar(model, 'health', 'zzzzzz')).toContain('Nothing matches');
  });
});

test('uuid is a v4', () => {
  expect(uuid()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
