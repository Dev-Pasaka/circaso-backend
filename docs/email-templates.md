# Email templates (Resend)

The designed HTML for each template is in `email-templates/` at the repo root; paste each file into Resend (**Templates → Create template**), set its subject, publish it, and put its id in `.env`. Steps are in `email-templates/README.md`. The app sends only the template id and the variables below; the subject, wording and layout are yours to change in Resend without a deploy. Set `INTEGRATIONS_EMAIL_PROVIDER=resend`, `RESEND_API_KEY` and `INTEGRATIONS_EMAIL_FROM` (a sender on a domain verified in Resend) as well.

| Env var | When it is sent | Variables |
|---|---|---|
| `INTEGRATIONS_EMAIL_TEMPLATES_NEW_PHONE_SIGN_IN` | The account signs in on a new phone | `DEVICE_MODEL` (the phone's model, e.g. "Pixel 8") |
| `INTEGRATIONS_EMAIL_TEMPLATES_RECOVERY_STARTED` | Someone started recovering the account or releasing its number | none |
| `INTEGRATIONS_EMAIL_TEMPLATES_RECOVERY_COMPLETED` | Recovery finished (not sent yet) | none |
| `INTEGRATIONS_EMAIL_TEMPLATES_WELCOME` | An account first becomes active, for people who signed in with Google or Apple (the only way we hold an email). Sent once, keyed `welcome-<userId>` | none |

In a Resend template a variable is written `{{{DEVICE_MODEL}}}` (triple braces; uppercase is Resend's convention). The welcome is the one email that is not a security notice: short, plain, no campaign. Apple "Hide My Email" addresses only receive mail from a domain registered with Apple (Apple developer account, Sign in with Apple, email sources), so register `circaso.com` there or those people get nothing. The rest are security notices: keep them short, say what happened, and say what to do if it was not the owner. Emails never contain codes, links that sign in, or the phone number.
