# Circaso email templates (for Resend)

Three security emails, designed with circaso.com's look (ink on white, grey panels, black pill button, tight headings) and built to work on desktop and phone: a single 560 px column that fills the screen on a phone, 16 px text, large tap targets, and a dark version for mail apps that follow the phone's setting (Apple Mail, iOS, many others). The only image is the logo (and, in the welcome, the white one), loaded from `https://assets.circaso.com/brand/` (a light and a dark version); if a mail app blocks images, the name "Circaso" shows as text instead.

| File | Env var for its template id | Subject to set in Resend | Variable |
|---|---|---|---|
| `new-phone-sign-in.html` | `INTEGRATIONS_EMAIL_TEMPLATES_NEW_PHONE_SIGN_IN` | New phone signed in to your Circaso account | `DEVICE_MODEL` (fallback: `a new phone`) |
| `pin-vault-opened.html` | `INTEGRATIONS_EMAIL_TEMPLATES_PIN_VAULT_OPENED` | Your Circaso PIN backup was opened | `DEVICE_MODEL` (fallback: `your phone`) |
| `recovery-started.html` | `INTEGRATIONS_EMAIL_TEMPLATES_RECOVERY_STARTED` | A request was made to recover your Circaso account | none |
| `recovery-completed.html` | `INTEGRATIONS_EMAIL_TEMPLATES_RECOVERY_COMPLETED` | Your Circaso account recovery is complete | none |

## Create each one in Resend

1. In the Resend dashboard open **Templates → Create template**, then paste in (or drag in) the whole `.html` file.
2. **Set the Subject** in the template's settings, using the table above. Circaso sends only the template id and variables, so the template must carry its own subject (and Resend rejects the send without one). The sender comes from `INTEGRATIONS_EMAIL_FROM`.
3. For `new-phone-sign-in` and `pin-vault-opened`, check that `DEVICE_MODEL` appears in the variables list (type `string`) and give it the fallback `a new phone`. Typing `{{` in the editor opens the variable menu if it has to be added by hand.
4. Send yourself a test from the dashboard (it asks for a value for `DEVICE_MODEL`), and look at it on a phone and in dark mode.
5. Click **Publish**. Only published templates can be sent. Copy the template id into `.env` (see `docs/email-templates.md`). Edits after publishing stay a draft until you publish again.

If Resend's editor drops the `<style>` block when it imports, the emails still read fine: the layout and colours are inline, and only the dark version and the tighter phone spacing come from that block.

## Changing them

Edit `build.ts` (wording is at the top, layout below), then run `bun run build.ts` here. It rewrites the three `.html` files and checks that the variables used in each file are exactly the ones declared, and that each file stays far below the size where Gmail clips mail. The support address (`support@circaso.com`) is a constant at the top of `build.ts`.

## Rules these follow

Tables and inline styles for layout, because mail clients ignore most modern CSS (no flex, grid, blur or custom fonts); the system font; contrast at least AA in both light and dark; a hidden preheader line for the inbox preview; no codes, sign-in links or phone numbers in any email.
