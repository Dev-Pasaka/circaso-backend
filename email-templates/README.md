# Circaso email templates (for Resend)

Three security emails, designed with circaso.com's look (ink on white, grey panels, black pill button, tight headings) and built to work on desktop and phone: a single 560 px column that fills the screen on a phone, 16 px text, large tap targets, and a dark version for mail apps that follow the phone's setting (Apple Mail, iOS, many others). The only image is the logo (and, in the welcome, the white one), loaded from `https://assets.circaso.com/brand/` (a light and a dark version); if a mail app blocks images, the name "Circaso" shows as text instead.

| File | Its name in the admin (Email templates) | Subject to set in Resend | Variable |
|---|---|---|---|
| `new-phone-sign-in.html` | `NEW_PHONE_SIGN_IN` | New phone signed in to your Circaso account | `DEVICE_MODEL` (fallback: `a new phone`) |
| `pin-vault-opened.html` | `PIN_VAULT_OPENED` | Your Circaso PIN backup was opened | `DEVICE_MODEL` (fallback: `your phone`) |
| `recovery-started.html` | `RECOVERY_STARTED` | A request was made to recover your Circaso account | none |
| `recovery-completed.html` | `RECOVERY_COMPLETED` | Your Circaso account recovery is complete | none |
| `merchant-sign-in-code.html` | `MERCHANT_SIGN_IN_CODE` | Your Circaso Merchants sign-in code | `CODE` (string, no fallback), `MINUTES` (number, fallback `10`) |
| `merchant-taken-offline.html` | `MERCHANT_TAKEN_OFFLINE` | You were taken offline on Circaso Merchants | none |
| `merchant-security-code.html` | `MERCHANT_SECURITY_CODE` | Your Circaso Merchants security code | `CODE`, `MINUTES`, `ACTION` |
| `merchant-new-sign-in.html` | `MERCHANT_NEW_SIGN_IN` | New sign-in to Circaso Merchants | `METHOD`, `DEVICE`, `LOCATION`, `TIME`, `LOCK_URL` |
| `merchant-address-added.html` | `MERCHANT_ADDRESS_ADDED` | A withdrawal address was added to your Circaso merchant account | `LABEL`, `ADDRESS`, `NETWORK`, `USABLE_FROM`, `TIME`, `LOCK_URL` |
| `merchant-address-removed.html` | `MERCHANT_ADDRESS_REMOVED` | A withdrawal address was removed from your Circaso merchant account | `LABEL`, `ADDRESS`, `NETWORK`, `TIME`, `LOCK_URL` |
| `merchant-withdrawal-requested.html` | `MERCHANT_WITHDRAWAL_REQUESTED` | Withdrawal requested from your Circaso float | `AMOUNT`, `LABEL`, `ADDRESS`, `NETWORK`, `TIME`, `LOCK_URL` |
| `merchant-withdrawal-sent.html` | `MERCHANT_WITHDRAWAL_SENT` | Your Circaso withdrawal is on its way | `AMOUNT`, `LABEL`, `ADDRESS`, `TX_URL`, `TIME`, `LOCK_URL` |
| `merchant-withdrawal-completed.html` | `MERCHANT_WITHDRAWAL_COMPLETED` | Your Circaso withdrawal is complete | `AMOUNT`, `LABEL`, `ADDRESS`, `TX_URL`, `TIME`, `LOCK_URL` |
| `merchant-withdrawal-failed.html` | `MERCHANT_WITHDRAWAL_FAILED` | Your Circaso withdrawal did not go through | `AMOUNT`, `LABEL`, `ADDRESS`, `REASON`, `TIME`, `LOCK_URL` |
| `merchant-pin-changed.html` | `MERCHANT_PIN_CHANGED` | Your Circaso Merchants PIN was changed | `TIME`, `LOCK_URL` |
| `merchant-payment-account-changed.html` | `MERCHANT_PAYMENT_ACCOUNT_CHANGED` | Your Circaso Merchants payment account was changed | `WHAT`, `TIME`, `LOCK_URL` |
| `merchant-passkey-added.html` | `MERCHANT_PASSKEY_ADDED` | A passkey was added to your Circaso merchant account | `LABEL`, `TIME`, `LOCK_URL` |
| `merchant-passkey-removed.html` | `MERCHANT_PASSKEY_REMOVED` | A passkey was removed from your Circaso merchant account | `LABEL`, `TIME`, `LOCK_URL` |
| `merchant-withdrawals-locked.html` | `MERCHANT_WITHDRAWALS_LOCKED` | Withdrawals are locked on your Circaso merchant account | `CANCELLED`, `TIME` |
| `merchant-buy-completed.html` | `MERCHANT_BUY_COMPLETED` | A buy order you filled is complete | `ORDER`, `KES_RECEIVED`, `USDC_SENT`, `EARNED`, `TIME` |
| `merchant-sell-completed.html` | `MERCHANT_SELL_COMPLETED` | A sell order you filled is complete | `ORDER`, `USDC_RECEIVED`, `KES_PAID`, `EARNED`, `TIME` |
| `merchant-topup-result.html` | `MERCHANT_TOPUP_RESULT` | Your Circaso Merchants top-up | `WALLET`, `AMOUNT`, `RESULT`, `TIME` |
| `merchant-daily-summary.html` | `MERCHANT_DAILY_SUMMARY` | Your Circaso Merchants daily summary | `DAY`, `ORDERS`, `BOUGHT`, `SOLD`, `EARNED` |

## Create each one in Resend

1. In the Resend dashboard open **Templates → Create template**, then paste in (or drag in) the whole `.html` file.
2. **Set the Subject** in the template's settings, using the table above. Circaso sends only the template id and variables, so the template must carry its own subject (and Resend rejects the send without one). The sender comes from `INTEGRATIONS_EMAIL_FROM`.
3. For `new-phone-sign-in` and `pin-vault-opened`, check that `DEVICE_MODEL` appears in the variables list (type `string`) and give it the fallback `a new phone`. Typing `{{` in the editor opens the variable menu if it has to be added by hand.
4. Send yourself a test from the dashboard (it asks for a value for `DEVICE_MODEL`), and look at it on a phone and in dark mode.
5. Click **Publish**. Only published templates can be sent. Copy the template id and **set it in the admin** (Email templates; on the ops gateway it is `PUT /v1/ops/email-templates/{NAME}`, see `docs/email-templates.md`). It is not a line of `.env` any more (decision 2026-11-22), and no restart is needed: both servers send with it within about ten seconds. Edits after publishing stay a draft until you publish again.

If Resend's editor drops the `<style>` block when it imports, the emails still read fine: the layout and colours are inline, and only the dark version and the tighter phone spacing come from that block.

## The merchant security templates (2026-11-24)

The 17 `merchant-*` templates after `merchant-taken-offline.html` are the merchant portal's security alerts, activity emails and security code. Every security alert ends with a **"This wasn't me: lock withdrawals"** button whose link is `{{{LOCK_URL}}}`. `MINUTES` is a number; every other variable is a string. To create them in Resend in one go (instead of pasting each): `RESEND_API_KEY=<a full-access key> bun run publish.ts --merchant-security` prints `NAME <id>` for each; set each id in the admin. A send-only key (the server's) is refused by Resend's template API.

## Changing them

Edit `build.ts` (wording is at the top, layout below), then run `bun run build.ts` here. It rewrites the three `.html` files and checks that the variables used in each file are exactly the ones declared, and that each file stays far below the size where Gmail clips mail. The support address (`support@circaso.com`) is a constant at the top of `build.ts`.

## Rules these follow

Tables and inline styles for layout, because mail clients ignore most modern CSS (no flex, grid, blur or custom fonts); the system font; contrast at least AA in both light and dark; a hidden preheader line for the inbox preview; no codes, sign-in links or phone numbers in any email, with one exception: `merchant-sign-in-code.html`, the ramp merchant portal's one-time sign-in code (decision 2026-11-22).
