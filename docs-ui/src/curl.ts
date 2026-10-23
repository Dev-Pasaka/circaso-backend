import type { Op } from './model';
import { STATE_CHANGING, example, requestSchema } from './model';
import type { OpenApi } from './spec';

/** The path with `{params}` replaced by the given values (or left as `{name}` for the reader to fill). */
export function fillPath(path: string, values: Record<string, string> = {}): string {
  return path.replace(/\{([^}]+)\}/g, (_m, name: string) => encodeURIComponent(values[name] || `{${name}}`));
}

export function bodyExample(spec: OpenApi, op: Op): string | null {
  const schema = requestSchema(op.op);
  return schema ? JSON.stringify(example(spec, schema), null, 2) : null;
}

const quote = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;

export function curl(spec: OpenApi, op: Op, origin: string, idempotencyKey: string): string {
  const lines = [`curl -X ${op.method.toUpperCase()} ${quote(origin + fillPath(op.path))}`];
  if (op.secured) lines.push(`-H 'Authorization: Bearer <access token>'`);
  if (STATE_CHANGING.has(op.method) && op.parameters.some((p) => p.in === 'header' && p.name === 'Idempotency-Key')) {
    lines.push(`-H ${quote('Idempotency-Key: ' + idempotencyKey)}`);
  }
  const body = bodyExample(spec, op);
  if (body) {
    lines.push(`-H 'Content-Type: application/json'`);
    lines.push(`-d ${quote(JSON.stringify(JSON.parse(body)))}`);
  }
  return lines.join(' \\\n  ');
}
