---
name: resend-api
description: Resend's REST API as Circaso uses it (sending a stored template email, auth, the required User-Agent, rate limits, status codes, template variable rules). Load when writing or changing email sending, `ResendEmail`, or an email template. Source: https://resend.com/docs/api-reference/introduction
---

# Resend API (what Circaso needs)

Source pages: https://resend.com/docs/api-reference/introduction and https://resend.com/docs/api-reference/emails/send-email. Full docs index for other endpoints (batch, webhooks, templates, domains): https://resend.com/docs/llms.txt. Fetched 2026-10-22; re-check the page before relying on a detail that matters.

Circaso's code: `integrations/impl/.../email/ResendEmail.kt` (the `EmailGateway` implementation), wired in `IntegrationsModule.kt`; template ids and variables are in `docs/email-templates.md`; tests in `ResendEmailTest` run against a local fake server.

## Basics

- Base URL `https://api.resend.com`, HTTPS only. REST, JSON.
- Auth: `Authorization: Bearer re_xxxxxxxxx` (secret `RESEND_API_KEY`, read as `resend.api-key`).
- **Every request must carry a `User-Agent` header**, or Resend answers `403` (error code `1010`) even with a valid key. We send `circaso-backend`.
- Rate limit: 10 requests per second per team, shared by all the team's keys; over it is `429`. A burst of emails (a sweep) must be paced.
- No API versioning yet (calendar-based headers are planned).
- Cursor pagination on list endpoints (see the pagination guide); we do not list anything yet.

## Status codes

| Code | Meaning | Our mapping |
|---|---|---|
| 200 | Success | `Success(EmailReceipt(id, "resend"))` |
| 400 | Bad request parameters | `Rejected` |
| 401 | API key missing | `Unavailable` (our fault, logged at error) |
| 403 | API key invalid, or no `User-Agent` | `Unavailable` (our fault, logged at error) |
| 404 | Not found (for example a template) | `Rejected` |
| 422 | Validation error (missing template variable, template combined with `html`) | `Rejected` |
| 429 | Rate limit | `Unavailable` |
| 5xx | Resend failure | `Unavailable` |

The error body format is on the Error Codes page; we never show it to a user and never log it with the address or variables.

## `POST /emails` with a stored template

Headers: `Idempotency-Key` (optional, unique per request, expires after 24 hours, up to 256 characters). `EmailGateway.send(..., idempotencyKey)` passes it on; the welcome email uses `welcome-<userId>` so a retry delivers once. Security notices send none.

Body (what we send):

```json
{
  "from": "Circaso <no-reply@circaso.com>",
  "to": ["person@example.com"],
  "template": { "id": "<published template id or alias>", "variables": { "DEVICE_MODEL": "Pixel 8" } }
}
```

- `from` (sender on a domain verified in Resend), `to` (string or array, up to 50) and `subject` are required for a plain email. With a template, `from`, `subject` and `reply_to` may be left out if the template has defaults; if the payload sets them they override the template's. If the template has no default for one of them, the payload must include it.
- `template` cannot be combined with `html`, `text` or `react`.
- Only **published** templates work. `template.id` may be the id or the alias.
- Variable keys: ASCII letters, digits and underscores only, up to 50 characters. Reserved and not usable: `FIRST_NAME`, `LAST_NAME`, `EMAIL`, `UNSUBSCRIBE_URL`. Values: a string of up to 2,000 characters, or a number no greater than 2^53 - 1. A variable the template uses but the call omits fails validation.
- Other optional fields: `cc`, `bcc`, `reply_to`, a scheduling field (natural language such as `in 1 min`, or ISO 8601), `headers`, `attachments` (40 MB total after Base64), `tags` (`name` and `value`: ASCII letters, digits, `_`, `-`; up to 256 characters), and a topic id.
- Response: `{ "id": "49a3999c-0ce1-4ea6-ab68-afcd6dc2e794" }`.

## Rules for Circaso

- The wording and layout live in the Resend dashboard; code names a template (`EmailTemplate`) and passes variables. Never build HTML in code.
- Never log the address or the variables; log the template name and status only.
- Emails are security notices: no codes, no sign-in links, no phone numbers (see `docs/email-templates.md`).
- Adding an `EmailTemplate` entry means a new `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` setting; startup stops if one is missing.

## Templates (dashboard)

- Create with **Templates → Create template**: paste or drag in an HTML or React Email file. New templates are drafts; **Publish** (review, then confirm) makes the version used for sending, and later edits stay drafts until published again.
- Variables are written with triple braces, `{{{NAME}}}` (typing `{{` in the editor opens the variable menu). Up to 50 per template, type `string` or `number`, keys conventionally uppercase. Reserved: `FIRST_NAME`, `LAST_NAME`, `EMAIL`, `UNSUBSCRIBE_URL`, `contact`, `this`. A variable with a fallback uses it when the send omits the value; one without a fallback must be sent, or the send fails validation.
- A template carries its own default `subject` (and may carry `from`, `reply_to`). Circaso sends none of them except `from`, so **every template needs a subject set in Resend**.
- Circaso's designed HTML lives in `email-templates/` (built by `bun run build.ts`).
