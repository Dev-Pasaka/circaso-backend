// Creates and publishes templates in Resend from this folder (2026-11-24): `bun run publish.ts NAME…`
// (each NAME as in templates.json, e.g. MERCHANT_ADDRESS_ADDED; `--merchant-security` for every template added for
// merchant security). Reads RESEND_API_KEY from the environment and never prints it. Prints `NAME <template id>` for
// each one, which is what to set in the admin (Email templates), or with `PUT /v1/ops/email-templates/{NAME}`.
// A template is created as a new one each run: run it once per name.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

interface Entry {
  template: string;
  file: string;
  subject: string;
  variables: string[];
}

const NUMBERS = new Set(['MINUTES']);
const SECURITY_SET = [
  'MERCHANT_SECURITY_CODE',
  'MERCHANT_NEW_SIGN_IN',
  'MERCHANT_ADDRESS_ADDED',
  'MERCHANT_ADDRESS_REMOVED',
  'MERCHANT_WITHDRAWAL_REQUESTED',
  'MERCHANT_WITHDRAWAL_SENT',
  'MERCHANT_WITHDRAWAL_COMPLETED',
  'MERCHANT_WITHDRAWAL_FAILED',
  'MERCHANT_PIN_CHANGED',
  'MERCHANT_PAYMENT_ACCOUNT_CHANGED',
  'MERCHANT_PASSKEY_ADDED',
  'MERCHANT_PASSKEY_REMOVED',
  'MERCHANT_WITHDRAWALS_LOCKED',
  'MERCHANT_BUY_COMPLETED',
  'MERCHANT_SELL_COMPLETED',
  'MERCHANT_TOPUP_RESULT',
  'MERCHANT_DAILY_SUMMARY',
];

const key = process.env.RESEND_API_KEY;
if (!key) throw new Error('RESEND_API_KEY is not set');
const manifest: Entry[] = JSON.parse(readFileSync(join(import.meta.dir, 'templates.json'), 'utf8'));
const args = process.argv.slice(2);
const names = args.includes('--merchant-security') ? SECURITY_SET : args;
if (names.length === 0) throw new Error('name the templates to create');

async function call(path: string, body?: unknown): Promise<{ id: string }> {
  const res = await fetch(`https://api.resend.com${path}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json', 'User-Agent': 'circaso-backend' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${path}: ${res.status} ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

for (const name of names) {
  const entry = manifest.find((m) => m.template === name);
  if (!entry) throw new Error(`no template named ${name} in templates.json`);
  const html = readFileSync(join(import.meta.dir, entry.file), 'utf8');
  const created = await call('/templates', {
    name: `${name} (${entry.file})`,
    subject: entry.subject,
    html,
    variables: entry.variables.map((v) => ({ key: v, type: NUMBERS.has(v) ? 'number' : 'string' })),
  });
  await call(`/templates/${created.id}/publish`);
  console.log(name, created.id);
  await new Promise((r) => setTimeout(r, 600)); // Resend allows a few requests a second
}
