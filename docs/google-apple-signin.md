# Google and Apple sign-in

The app signs the person in with Google or Apple on the phone and sends us the **ID token**. We check it ourselves (`integrations/impl/.../social/GoogleAppleSignIn.kt`, no vendor SDK): the signature against the provider's published keys, the issuer, that the token was issued **for one of our client ids**, that it has not expired, and that it carries the one-time nonce we issued (I1). Anything else is `identity.provider_token_invalid`.

Turn it on with `INTEGRATIONS_SOCIAL_PROVIDER=google-apple` and the settings below (all in `.env`). Until then the provider stays `log`, which accepts `dev:` tokens for local work. The app will not start with `google-apple` selected and no client id set.

## Google

1. Google Cloud Console → **APIs & Services → OAuth consent screen**: set up the app (name Circaso, support email, `circaso.com`).
2. **Credentials → Create credentials → OAuth client ID**, three times:
   - **Web application**: this is the one the phone passes as its *server client id*, so it is the audience of the token. Required.
   - **Android**: package name and the SHA-1 of the signing key (debug and Play App Signing keys each).
   - **iOS**: the bundle id.
3. `INTEGRATIONS_SOCIAL_GOOGLE_CLIENT_IDS` = the Web client id, and the Android and iOS ids too if the phones do not pass the server client id (comma separated). The client ids are not secrets.

## Apple

1. Apple Developer → **Identifiers → App IDs**: the app's id (for example `com.circaso.app`) with **Sign in with Apple** enabled. For a native iOS sign-in this is the token's audience.
2. If Android signs in through the web: **Identifiers → Services IDs**, with `circaso.com` and the return URL; that Services id is a second audience.
3. `INTEGRATIONS_SOCIAL_APPLE_CLIENT_IDS` = the bundle id (and the Services id), comma separated.
4. **For revoking a grant when an account is deleted** (Apple requires it): **Keys → +**, tick Sign in with Apple, download the `.p8` once. Set `INTEGRATIONS_SOCIAL_APPLE_TEAM_ID` (top right of the developer account), `INTEGRATIONS_SOCIAL_APPLE_KEY_ID`, and `APPLE_PRIVATE_KEY` (the `.p8` text on one line, newlines as `\n`). Optional now: without them sign-in works and revoking reports `integrations.unavailable`.
5. Hidden emails: Apple's "Hide My Email" addresses only receive mail from a domain registered with Apple (Services → Sign in with Apple for Email Communication). Register `circaso.com` there so the welcome and security emails arrive.

Personal developer accounts are fine now; converting to an organisation account keeps the team id (decisions.md).

## What the app must send

- `POST /auth/nonce` first, then the provider request with that nonce: Google gets it as-is; on iOS pass Apple the SHA-256 of the nonce (the usual native flow) or the nonce itself, both are accepted.
- `POST /auth/social` with `provider`, `idToken` and the same `nonce`. Apple sends the person's name only the first time, so the app passes it as `appleName` (the token has none).

## The website's account deletion page (I17)

Google Play requires a web page where people can ask for their account to be deleted. On `circaso.com/delete-account` the person signs in with Google (Google Identity Services) or Apple (Sign in with Apple JS), picks a reason and confirms. The page's Worker then calls the API:

1. `POST /v1/auth/nonce` (with an `Idempotency-Key`), and passes the nonce to Google or Apple when the sign-in starts. Apple's JS gets the nonce as-is.
2. `POST /v1/web/account-deletion` (with an `Idempotency-Key`), body `{ "provider": "google" | "apple", "idToken", "nonce", "reason", "details"? }`. The token is checked exactly as at I2; the account is the one that owns that login. `reason` is one of `not_useful`, `privacy`, `too_many_apps`, `switching`, `problems`, `other`; `details` is at most 500 characters.
3. The answer's `data.status` is `scheduled` or `already_scheduled` (with `deleteAfter`), `no_account`, or `refused` with the refusal's `code` (`wallet.funds_remaining`, `wallet.custody_unavailable`, `identity.account_suspended`, `identity.account_blocked`, `identity.recovery_pending`). Bad token: 401 `identity.provider_token_invalid`; bad or used nonce: 400 `identity.nonce_invalid`.

Console setup it needs:

- **Google**: on the **Web application** OAuth client, add `https://circaso.com` (and `https://www.circaso.com` if used) under **Authorized JavaScript origins**. Its id is already the first in `INTEGRATIONS_SOCIAL_GOOGLE_CLIENT_IDS`, so nothing changes on the server.
- **Apple**: a **Services ID** (for example `com.circaso.web`) with Sign in with Apple enabled, `circaso.com` as a domain and the page's return URL (for example `https://circaso.com/delete-account`) registered, even when the page uses the popup. Add that Services id to `INTEGRATIONS_SOCIAL_APPLE_CLIENT_IDS`.

The reason and details go into `identity.deletion_feedback`, which has no user id, so it outlives the deletion and points at no one.

## Not done yet

Revoking an Apple grant needs the refresh token Apple returns when the authorization code is exchanged, and the app does not collect or store that yet. `revokeApple` is built and tested only as far as signing the client secret; account deletion does not call it yet. `// TODO(decision: where the Apple refresh token is kept and who exchanges the authorization code)`.
