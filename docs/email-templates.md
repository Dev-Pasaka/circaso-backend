# Email templates (Resend)

The designed HTML for each template is in `email-templates/` at the repo root; paste each file into Resend (**Templates → Create template**), set its subject, publish it, and **set its id in the admin** (Email templates). Steps are in `email-templates/README.md`. The app sends only the template id and the variables below; the subject, wording and layout are yours to change in Resend without a deploy. Set `INTEGRATIONS_EMAIL_PROVIDER=resend`, `RESEND_API_KEY` and `INTEGRATIONS_EMAIL_FROM` (a sender on a domain verified in Resend) in the environment: those are the provider, a secret and the sender.

**Template ids live in the database, not in `.env` (decision 2026-11-22).** The environment holds keys and secrets only. Each email's template id is a row in `ops.email_templates`, set by an ADMIN on the ops gateway (`GET /v1/ops/email-templates`, `PUT /v1/ops/email-templates/{TEMPLATE}` with `{ providerTemplateId, version, reason }`; audited) and in force on both servers within about ten seconds, with no restart. An email with no id is not sent (that one send fails; the use case, which sends it best effort, carries on) and **the server starts with no template id at all**. The old `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` variables are import-once seeds: the first time an email has no row, the variable's value is copied in if it is still set, and it is never read for that email again; once the admin shows the id, the line can be deleted. The admin portal has no page for these routes yet: until it does, call them directly.

| Template (the name in the admin; its old seed variable is `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>`) | When it is sent | Variables |
|---|---|---|
| `NEW_PHONE_SIGN_IN` | The account signs in on a new phone | `DEVICE_MODEL` (the phone's model, e.g. "Pixel 8") |
| `RECOVERY_STARTED` | Someone started recovering the account or releasing its number | none |
| `RECOVERY_COMPLETED` | Recovery finished (not sent yet) | none |
| `PIN_VAULT_OPENED` | The account's PIN Vault was opened (its ciphertext fetched to be decrypted); payments pause for 24 hours. Keyed `vault-opened-<eventId>`, so one email per opening. Until its id is set no such email goes out (the opening itself still works) | `DEVICE_MODEL` (the phone that opened it) |
| `WELCOME` | An account first becomes active, for people who signed in with Google or Apple (the only way we hold an email). Sent once, keyed `welcome-<userId>` | none |
| `MERCHANT_SIGN_IN_CODE` | A ramp merchant asks to sign in to the merchant portal (MA1). Until its id is set, asking for a code answers `ramp.email_unavailable` | `CODE` (the 6-digit code), `MINUTES` (how long it lasts) |
| `MERCHANT_TAKEN_OFFLINE` | A ramp merchant was online (until stopped, or in a timed window) when the app server restarted, so their unlocked keys went with it and `ramp.end-sessions` took them offline (2026-11-23). Keyed `merchant-offline-<merchantId>-<session start>`, so one email per session. Until its id is set nothing is sent; the session still ends | none |
| `MERCHANT_SECURITY_CODE` | A merchant asks for a code to add or remove a passkey (2026-11-24) | `CODE`, `MINUTES` (number), `ACTION` |
| `MERCHANT_NEW_SIGN_IN` | Every merchant portal sign-in (email code or passkey) | `METHOD`, `DEVICE`, `LOCATION`, `TIME`, `LOCK_URL` |
| `MERCHANT_ADDRESS_ADDED` / `MERCHANT_ADDRESS_REMOVED` | A withdrawal address is saved / removed | `LABEL`, `ADDRESS`, `NETWORK`, (`USABLE_FROM` on added), `TIME`, `LOCK_URL` |
| `MERCHANT_WITHDRAWAL_REQUESTED` | A float withdrawal is requested | `AMOUNT`, `LABEL`, `ADDRESS`, `NETWORK`, `TIME`, `LOCK_URL` |
| `MERCHANT_WITHDRAWAL_SENT` / `MERCHANT_WITHDRAWAL_COMPLETED` | It is on chain / confirmed | `AMOUNT`, `LABEL`, `ADDRESS`, `TX_URL`, `TIME`, `LOCK_URL` |
| `MERCHANT_WITHDRAWAL_FAILED` | It failed (nothing sent) | `AMOUNT`, `LABEL`, `ADDRESS`, `REASON`, `TIME`, `LOCK_URL` |
| `MERCHANT_PIN_CHANGED` | The merchant PIN changed | `TIME`, `LOCK_URL` |
| `MERCHANT_PAYMENT_ACCOUNT_CHANGED` | The payment account is replaced or the payout account changed | `WHAT`, `TIME`, `LOCK_URL` |
| `MERCHANT_PASSKEY_ADDED` / `MERCHANT_PASSKEY_REMOVED` | A passkey is added / removed | `LABEL`, `TIME`, `LOCK_URL` |
| `MERCHANT_WITHDRAWALS_LOCKED` | "This wasn't me" locked withdrawals | `CANCELLED`, `TIME` |
| `MERCHANT_BUY_COMPLETED` | Each completed buy (activity; the merchant can turn it off) | `ORDER`, `KES_RECEIVED`, `USDC_SENT`, `EARNED`, `TIME` |
| `MERCHANT_SELL_COMPLETED` | Each completed sale (activity) | `ORDER`, `USDC_RECEIVED`, `KES_PAID`, `EARNED`, `TIME` |
| `MERCHANT_TOPUP_RESULT` | Each top-up that settles (activity) | `WALLET`, `AMOUNT`, `RESULT`, `TIME` |
| `MERCHANT_DAILY_SUMMARY` | Once a day, for merchants who chose it | `DAY`, `ORDERS`, `BOUGHT`, `SOLD`, `EARNED` |

In a Resend template a variable is written `{{{DEVICE_MODEL}}}` (triple braces; uppercase is Resend's convention). The welcome is the one email that is not a security notice: short, plain, no campaign. Apple "Hide My Email" addresses only receive mail from a domain registered with Apple (Apple developer account, Sign in with Apple, email sources), so register `circaso.com` there or those people get nothing. The rest are security notices: keep them short, say what happened, and say what to do if it was not the owner. Emails never contain codes, links that sign in, or the phone number.

**Merchant alerts (2026-11-24).** The merchant templates above are sent by the ramp's `ramp.merchant-alerts` job, one email per event; until a template has an id, that alert is marked `SKIPPED` (`no_template`) and not retried. Every security alert's `LOCK_URL` is a one-time "This wasn't me" link to the portal's `/lock` page (the one deliberate exception to "no links that act on the account": it can only lock, never sign in or unlock). `email-templates/templates.json` lists each template's name, file, subject and variables; `bun run publish.ts --merchant-security` creates and publishes the 17 merchant templates in Resend with a full-access API key (the server's key is send-only, so it can't) and prints each name with its id to set in the admin.
