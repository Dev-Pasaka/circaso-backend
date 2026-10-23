import { METHODS, type Method, type OpenApi, type Operation, type Parameter, type Schema, type Json } from './spec';
import { slugify } from './markdown';

/** One operation, flattened out of `paths` with everything the views need parsed once. */
export interface Op {
  id: string;
  method: Method;
  path: string;
  tag: string;
  /** The journey number ("I2") when the summary starts with one, else empty. */
  code: string;
  title: string;
  /** Markdown, with the Errors and Scope lines taken out (they are shown as chips). */
  description: string;
  errors: string[];
  scope: string;
  secured: boolean;
  parameters: Parameter[];
  op: Operation;
}

export interface Section {
  tag: string;
  slug: string;
  description: string;
  ops: Op[];
}

export interface Group {
  name: string;
  sections: Section[];
}

export interface Model {
  spec: OpenApi;
  groups: Group[];
  ops: Op[];
}

export const STATE_CHANGING = new Set<Method>(['post', 'put', 'patch', 'delete']);

function parseOp(path: string, method: Method, op: Operation): Op {
  const summary = op.summary ?? `${method.toUpperCase()} ${path}`;
  const numbered = summary.match(/^([A-Z]\d+)\s*·\s*(.*)$/);
  let description = op.description ?? '';
  const errors: string[] = [];
  const errorLine = description.match(/\*\*Errors:\*\*([^\n]*)/);
  if (errorLine) {
    for (const m of errorLine[1].matchAll(/`([^`]+)`/g)) errors.push(m[1]);
    description = description.replace(errorLine[0], '');
  }
  const scopeLine = description.match(/(?:^|\n)\s*Scope:\s*`([^`]+)`[^\n]*/);
  const scope = scopeLine?.[1] ?? '';
  if (scopeLine) description = description.replace(scopeLine[0], '');
  return {
    id: slugify(`${method}-${path}`),
    method,
    path,
    tag: op.tags?.[0] ?? 'Other',
    code: numbered?.[1] ?? '',
    title: numbered?.[2] ?? summary,
    description: description.trim(),
    errors,
    scope,
    secured: (op.security?.length ?? 0) > 0,
    parameters: op.parameters ?? [],
    op,
  };
}

/** Builds the navigation model. Groups follow `x-tagGroups`; tags the groups forget are collected under "Other". */
export function buildModel(spec: OpenApi): Model {
  const ops: Op[] = [];
  for (const [path, item] of Object.entries(spec.paths ?? {})) {
    for (const method of METHODS) {
      const op = (item as Record<string, Operation | undefined>)[method];
      if (op && typeof op === 'object') ops.push(parseOp(path, method, op));
    }
  }
  const byTag = new Map<string, Op[]>();
  for (const op of ops) byTag.set(op.tag, [...(byTag.get(op.tag) ?? []), op]);
  const described = new Map((spec.tags ?? []).map((t) => [t.name, t.description ?? '']));

  const section = (tag: string): Section => ({ tag, slug: slugify(tag), description: described.get(tag) ?? '', ops: byTag.get(tag) ?? [] });

  const declared = spec['x-tagGroups'] ?? [];
  const used = new Set(declared.flatMap((g) => g.tags));
  const groups: Group[] = declared
    .map((g) => ({ name: g.name, sections: g.tags.filter((t) => byTag.has(t)).map(section) }))
    .filter((g) => g.sections.length > 0);
  const rest = [...byTag.keys()].filter((t) => !used.has(t));
  if (rest.length) groups.push({ name: groups.length ? 'Other' : 'API', sections: rest.map(section) });
  return { spec, groups, ops };
}

// ---- schemas -------------------------------------------------------------------------------------------------------

export function refName(ref: string): string {
  return ref.slice(ref.lastIndexOf('/') + 1);
}

/** Follows `$ref`s (with a guard against loops) to the schema they name. */
export function resolve(spec: OpenApi, schema: Schema | undefined): Schema {
  let current = schema ?? {};
  for (let hops = 0; current.$ref && hops < 16; hops++) {
    const next = spec.components?.schemas?.[refName(current.$ref)];
    if (!next) return {};
    current = next;
  }
  return current;
}

const kinds = (schema: Schema): string[] => (Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : []);
export const isNullable = (schema: Schema) => kinds(schema).includes('null');

/** "string", "string?", "object", "Thing[]", "a | b": what a reader sees in the Type column. */
export function typeLabel(spec: OpenApi, schema: Schema | undefined): string {
  if (!schema) return 'any';
  const target = resolve(spec, schema);
  const name = schema.$ref ? refName(schema.$ref) : '';
  const base = kinds(target).filter((k) => k !== 'null');
  let label: string;
  if (base.includes('array')) label = `${typeLabel(spec, target.items)}[]`;
  else if (target.enum) label = base[0] ?? 'enum';
  else if (base.includes('object') || target.properties) label = name || 'object';
  else if (target.oneOf || target.anyOf) label = (target.oneOf ?? target.anyOf ?? []).map((s) => typeLabel(spec, s)).join(' | ');
  else label = (base.join(' | ') || name || 'any') + (target.format ? ` (${target.format})` : '');
  return isNullable(target) ? `${label}?` : label;
}

export interface FieldRow {
  path: string;
  depth: number;
  type: string;
  required: boolean;
  description: string;
  enumValues: string[];
}

/** Flattens an object schema into rows with dotted paths (`details.fields[]`), stopping at loops and at depth 4. */
export function fieldRows(spec: OpenApi, schema: Schema | undefined, prefix = '', depth = 0, seen: string[] = []): FieldRow[] {
  const target = resolve(spec, schema);
  const rows: FieldRow[] = [];
  const props = target.properties ?? {};
  const required = new Set(target.required ?? []);
  for (const [name, prop] of Object.entries(props)) {
    const resolved = resolve(spec, prop);
    const path = prefix + name;
    rows.push({
      path,
      depth,
      type: typeLabel(spec, prop),
      required: required.has(name),
      description: resolved.description ?? prop.description ?? '',
      enumValues: (resolved.enum ?? []).map(String),
    });
    const child = kinds(resolved).includes('array') ? resolve(spec, resolved.items) : resolved;
    const key = prop.$ref ?? (kinds(resolved).includes('array') && resolved.items?.$ref) ?? '';
    if (child.properties && depth < 3 && !(key && seen.includes(key))) {
      rows.push(...fieldRows(spec, kinds(resolved).includes('array') ? resolved.items : prop, path + (kinds(resolved).includes('array') ? '[].' : '.'), depth + 1, key ? [...seen, key] : seen));
    }
  }
  return rows;
}

/** A plausible example value for a schema, so the request panel and cURL are never empty. */
export function example(spec: OpenApi, schema: Schema | undefined, seen: string[] = []): Json {
  if (schema?.example !== undefined) return schema.example;
  if (schema?.$ref) {
    const name = refName(schema.$ref);
    if (seen.includes(name)) return null;
    seen = [...seen, name];
  }
  const target = resolve(spec, schema);
  if (target.example !== undefined) return target.example;
  if (target.default !== undefined) return target.default;
  if (target.enum?.length) return target.enum[0];
  const base = kinds(target).filter((k) => k !== 'null');
  if (target.properties || base.includes('object')) {
    const out: Record<string, Json> = {};
    for (const [name, prop] of Object.entries(target.properties ?? {})) out[name] = example(spec, prop, seen);
    return out;
  }
  if (base.includes('array')) return [example(spec, target.items, seen)];
  if (base.includes('integer') || base.includes('number')) return 0;
  if (base.includes('boolean')) return false;
  switch (target.format) {
    case 'uuid': return '3f2b8c1e-5a47-4d0e-9a3b-0c1d2e3f4a5b';
    case 'date-time': return '2026-01-01T09:00:00Z';
    case 'date': return '2026-01-01';
    default: return 'string';
  }
}

export function requestSchema(op: Operation): Schema | undefined {
  return op.requestBody?.content?.['application/json']?.schema;
}

export function responseEntries(op: Operation): { status: string; description: string; schema?: Schema }[] {
  return Object.entries(op.responses ?? {}).map(([status, r]) => ({
    status,
    description: r.description ?? '',
    schema: r.content?.['application/json']?.schema,
  }));
}
