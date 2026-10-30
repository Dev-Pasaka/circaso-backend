# Circaso mobile integration guide

For **Claude Code working in the Circaso mobile app** (Kotlin Multiplatform: one shared module, Android and iOS targets). It says how to connect the app to the backend, route by route, **what is finished on the backend, and what is not**. Updated 2026-10-30 against the backend at `github.com/Dev-Pasaka/circaso-backend` (dev server `https://sandbox.circaso.com`).

## 1. How to use this file

- **The API reference is the source of truth for exact shapes**: `/docs` on the dev server (behind Cloudflare Access; ask Pasaka for the `openapi.json` file if you cannot open it). This file explains behaviour and order; section 11 repeats every request and response type so you can start offline.
- **Build against the real server, not mocks.** `https://sandbox.circaso.com` is a deployed dev server with real vendors: Google and Apple sign-in, SMS (Twilio Verify), email (Resend), push (FCM), file storage (R2) and Asha's model, speech-to-text and voice (Cloudflare Workers AI). Only the Android carrier check and device attestation are faked there (marked **DEV STUB** below).
- **Status words used below:** **READY** (backend finished, safe to build on), **DEV STUB** (the HTTP contract is final but a vendor behind it is faked on dev), **PARTIAL** (works, with a named gap), **MISSING** (not built in the backend; do not invent an endpoint).
- If the backend says something different from this file, the backend wins: tell Pasaka and fix the file.

> **Changed on 2026-10-30, encrypted chat attachments (new routes):**
> 1. **Attachments are in** (section 7, Attachments): AT1 `POST /v1/chats/{chatId}/attachments`, AT2 `POST /v1/attachments/{attachmentId}/complete`, AT3 `GET /v1/attachments/{attachmentId}`. Signal-style: encrypt each file on the phone with a fresh AES-256-GCM key, PUT the ciphertext to the pre-signed URL, complete it, then send an ordinary M12 message whose ciphertext carries the `attachmentId`, key, digest and real type. Up to 32 MiB; kept 30 days. No new frames.
> 2. This replaces the M15–M17 media design (500 MB multipart, `/v1/media/…`), which will not be built.

> **Changed on 2026-10-29, chamas (new routes and frames):**
> 1. **A group's shared goal is in** (section 7, Chama): CH1–CH6 under `/v1/chama`, with `chama` / `goal.updated`, `contribution.added`, `request.updated` frames (section 8). Send `{"module":"chama","seq":…}` in `last`. The money sits in the treasurer's wallet: contributions are W4 payments to the treasurer with `chatId`.
> 2. **`wallet` / `transfer.updated` is unchanged**; `TransferConfirmed` (an event, not a frame) now carries `chatId`.

> **Changed on 2026-10-28, wallet payments (new routes and frames; W12 and W1 gain a field):**
> 1. **Payments are in** (section 7, Wallet): W4 `POST /v1/wallet/transfers`, W5 `GET /v1/wallet/transfers/{transferId}`, W9 `GET /v1/wallet/recipients/{userId}`, W10 `POST /v1/wallet/recipients/{userId}/remind`, W11 `POST /v1/wallet/addresses/validate`. **A payment names its chain** (`blockchain`) and leaves from your wallet's address on that chain; a person must have a wallet on that chain (W9 says `NO_WALLET_ON_CHAIN` otherwise), an outside address must be valid on it. Amounts are **strings** everywhere in the wallet: send `amount: { asset: "USDC", amountMinor: "20000000" }` or `{ asset, amount: "20" }`.
> 2. **Wallet frames and pushes** (section 8): `wallet` / `transfer.updated`, `payment.received`, `wallet.ready`, `setup.requested`. Send `{"module":"wallet","seq":…}` in `last` like the others.
> 3. **Retired chains:** W1's chains and W12's list carry `receiveEnabled`. `false` means the chain is retired from receiving: don't show its address to be paid; it can still send out. W12 no longer offers a retired chain to add.
> 4. **Deleting the account** is now refused when your US dollar stablecoins **added up over every chain** come to 0.01 or more (0.009 USDC on each of four chains is refused).
>
> **Changed on 2026-10-28, wallet (new routes; one error code renamed):**
> 1. **The wallet's first routes are in** (section 7, Wallet): W1 `GET /v1/wallet`, W2 `POST /v1/wallet`, W3 `POST /v1/wallet/session`, and W12 `GET`/`POST /v1/wallet/chains`. A wallet can be on **several chains** (Base, Polygon, Ethereum and Solana, USDC on each; the list is server configuration): it starts on Base, and the user adds others from wallet management. Each chain has its own address. Amounts are **strings** (`amountMinor` in the smallest unit, `amount` in whole units), because some tokens pass what a JSON number holds.
> 2. **Fiat balance:** `GET /v1/wallet?currency=KES` (the person's country-of-origin currency, any ISO 4217 code) adds `fiat { currency, amount, rate, asOf, partial }` at USDC = 1 USD. `fiat` is null when no rate is known (never an error); a code that isn't ISO 4217 answers `request.malformed`. Show "Rates by Exchange Rate API" wherever the fiat value appears (the source's terms).
> 3. **Deleting the account** (I14) now answers **`wallet.funds_remaining`** (409) while the wallet holds 0.01 or more of a US dollar stablecoin (USDC), or any amount of any other asset, on any chain, and `wallet.custody_unavailable` (502) when the wallet can't check (Circle down, or a wallet on a chain no longer supported); **`identity.wallet_not_empty` is gone** (it was never sent).
>
> **Changed on 2026-10-28, later (update the app):** `PUT /v1/keys` now **requires** `kyberPreKey { keyId, publicKey, signature }` (a signed ML-KEM 1024 prekey, about 1.5 KB base64, signed by the identity key and rotated with the signed prekey) and `GET /v1/keys/{userId}` returns it. libsignal 0.106 opens sessions with PQXDH only and refuses a bundle without one. Every bundle published before this change was removed: publish again.
>
> **Changed on 2026-10-28 (update the app):**
> 1. **The dev server's data was reset.** The backend moved to a new database: every account, profile, contact, Asha chat, key bundle and message on `sandbox.circaso.com` is gone. Clear the app's local data (or reinstall), sign up again, and publish encryption keys again (`PUT /v1/keys`). Old tokens answer `401`/`identity.session_revoked`: treat that as signed out.
> 2. **Real vendors on dev.** Asha's replies, voice clips, spoken replies and live voice now run on the real models (no more canned replies or text-as-audio); email and push are really sent. Live voice now needs real Opus audio; the old "binary frame of UTF-8 words" trick no longer works.
> 3. **New answers to handle:**
>    - any route can answer `503` `service.unavailable`: retry with backoff and the **same** `Idempotency-Key` (section 3);
>    - I3 and I15 answer `identity.phone_invalid` (was `request.malformed`) for a number that isn't E.164;
>    - I6 can answer `409` `request.in_progress` when two registrations race: keep the other response, don't retry;
>    - U5 (handle availability) now counts toward the 30-per-hour handle-lookup limit and can answer `request.rate_limited`;
>    - U14 answers `request.not_found` for an id that is no account;
>    - U11 through the invite of a deleted or suspended person answers `users.invite_invalid`.
> 4. **Deleted and suspended people disappear.** Their profile looks like not found (U9, U11 by @handle), they leave your contacts list and block list, U10 answers `users.not_reachable`, and other screens get no name, handle or avatar for them. They come back if the account becomes active again (section 7).
> 5. **Account deletion now completes:** 30 days after `DELETE /v1/account` the account and all its data are erased (section 7, I14). There is no restore route yet.
> 6. **Messaging details** (section 7): `GET /v1/chats` `lastMessageId` is null for a chat with no message in the last 30 days; a retried group create returns the group as it is (and answers `messaging.chat_not_found` if you left it meanwhile); after a refused prekey top-up you may delete that request's private keys at once (section 5); the `read` frame's `upToMessageId` must be the newest `MESSAGE` from someone else; a negative `seq` in the socket's `last` closes with `invalid last`.
> 7. **Push now arrives** for new messages (`pushable: true`) and for being added to a group: data-only on Android, an alert through APNs on iOS (section 8).
>
> **Changed on 2026-10-27 (breaking, update the app):**
> 1. `details.fields` in the error envelope is now a **list** `[{ "field": "displayName", "problem": "required" }]`, no longer an object of field name to problem (section 3).
> 2. The realtime auth frame's `last` is now a **list** `[{ "module": "users", "seq": 1042 }]`, no longer an object `{ "users": 1042 }`; the old form is refused with `invalid last` (section 8).
> 3. `profile.updated`'s `version` is now a JSON **number**, not a string (section 8).

## 2. Status at a glance

Of the **86** routes the phone calls: **59 READY**, **26 PARTIAL**, **1 DEV STUB** (the carrier check).

| Area | Status | What it means for the app |
| --- | --- | --- |
| Sign in with Google (Android, iOS) | **READY** | Use the Web client id as `serverClientId`. Works against dev today. |
| Sign in with Apple (iOS) | **READY** (untested with a real token) | Bundle id `com.circaso.app` is configured on the server. |
| Phone number by SMS code | **PARTIAL** | Built on Twilio Verify; a real end-to-end send is not confirmed yet. Test and report the error `code`. |
| Phone number by carrier (Android one-tap) | **DEV STUB** | Real Firebase check not built. Keep SMS as the fallback. |
| Device registration and device key | **PARTIAL** | Works on dev. In production, Play Integrity / App Attest checks are not built, so no device could register yet. |
| Session refresh, sign out, forget device | **READY** | Needs the device-key signature (section 5). |
| Profile, @handle, avatar | **READY** | |
| Contacts sync, connections, invites, blocks, reach | **READY** | |
| Realtime socket | **READY**, few frames | Only the frame types in section 8 exist. |
| Asha chat (text), voice, speech | **READY** | Real models on dev (Cloudflare Workers AI). The free allowance numbers and the paid plan are not decided: show what the server returns. |
| Asha live voice (socket) | **READY** | Section 9. Real speech-to-text (Opus in) and voice (mulaw 8 kHz out) on dev; English only. |
| Push notifications | **PARTIAL** | FCM sends for new messages (`pushable`), for being added to a group, for wallet payments (sent, failed, received) and wallet reminders, and for calls (an incoming call, data-only and high priority; a missed call). iOS needs the APNs key uploaded in the Firebase console. Nothing else pushes yet. |
| Account security, linked logins, security log | **READY** | |
| Account deletion | **PARTIAL** | Completes 30 days after the request. No route restores an account during those 30 days yet (undecided); Apple access is not revoked. |
| Account recovery | **PARTIAL** | A case can be opened and read; no support tool resolves it yet. |
| Encryption keys (publish, top up, status, fetch a bundle) | **READY** | Uploads are signed with the device key in `X-Device-Timestamp` / `X-Device-Signature` headers over the raw, uncompressed body (section 5). A bundle can be fetched by someone who can reach the person or shares a group with them. |
| Chats and members (direct chats, groups, roles) | **READY** | |
| Messages between people (sending, inbox, receipts, typing, delete for everyone) | **READY** | End-to-end encrypted on the phone; the server stores ciphertext until it is acknowledged. |
| Chat attachments (photos, voice notes, video, files) | **READY** | AT1–AT3: encrypted on the phone, ciphertext up to 32 MiB straight to R2 through pre-signed URLs, kept 30 days. On a local server (`STORAGE_PROVIDER=local`) the upload URL is a placeholder, so test against the sandbox. |
| Chat history and its key backup | **MISSING** | Designed; being built in slices. `mediaIds` on a message are stored as given. When an account is deleted, its messaging data (keys, memberships, inbox) is not cleaned up until chat history ships. |
| Wallet (create, PIN session, balance per chain, fiat balance, add a chain) | **PARTIAL** | W1–W3 and W12 work. On dev the custody provider is Circle on testnet (Base Sepolia, Polygon Amoy, Ethereum Sepolia, Solana Devnet): the challenges are real, so run them in the Circle SDK, where the user sets the PIN and the wallet appears on Circle's testnet. Test USDC comes from Circle's testnet faucet (faucet.circle.com), sent to the chain's address from W1. |
| Payments (prepare, follow, who can be paid, remind, check an address) | **PARTIAL** | W4, W5, W9–W11 and the wallet frames work against Circle's testnet: complete the payment's challenge in the Circle SDK with the PIN, and `transfer.updated` and `payment.received` arrive as Circle's webhooks report the transaction (testnet USDC from Circle's faucet). Limits are placeholders (500 USDC a payment, 1 000 a day). |
| Transaction history, statements, PIN change and restore, chamas | **MISSING** | Designed (wallet W6–W8); being built in slices. |
| Calls (voice and video, up to 8 people) | **PARTIAL** | CL1–CL9 and the `calls` frames work. On dev the SFU is Cloudflare Realtime once `INTEGRATIONS_CALLS_PROVIDER=cloudflare` is set on the sandbox; with the `log` provider the routes answer with made-up SDP, so no media flows. Show "Secure call", never "end-to-end encrypted" (decision 2026-10-29). |
| Notification inbox, identity verification (KYC) | **MISSING** | Not designed yet. |

## 3. Conventions every call follows

| | |
| --- | --- |
| Base URL | Dev `https://sandbox.circaso.com`; production `https://api.circaso.com` (not live yet). Make it a build setting. Every route is under `/v1`. |
| `Authorization` | `Bearer <accessToken>` on every route whose scope is not `none`. |
| `X-Client-Version` | **On every request**: `android/1.4.0` or `ios/1.4.0` (platform, slash, app version). Below the server's minimum it answers `426` `request.client_outdated`: show a force-update screen. |
| `Idempotency-Key` | A fresh UUID v4 on **every `POST`, `PUT`, `PATCH`, `DELETE`**. Missing → `400` `request.malformed`. The first answer is kept 24 hours. |
| Content | JSON, camelCase, unknown fields ignored (`Json { ignoreUnknownKeys = true }`). Times ISO-8601 UTC, ids UUIDs, phone numbers E.164 (`+254712345678`). |

**The envelope.** Every answer (except health, the Asha event stream and speech audio) is:
```json
{ "httpStatusCode": 200, "status": true, "message": "Success", "code": null,
  "details": null, "requestId": "…", "data": { } }
```
On failure `status` is `false`, `data` is `null`, and `code` is a stable string such as `identity.code_invalid`. **Key your UI text on `code`, never on `message`.** `details.retryAfterSeconds` (also the `Retry-After` header) and `details.fields` may be set: a list of the fields that are wrong, each `{ "field": "displayName", "problem": "required" }` (changed on 2026-10-27; it was an object of field name to problem). Log `requestId` and show it on error screens; Pasaka can find the exact trace from it.

**What the app does with the common failures**

| Answer | Do |
| --- | --- |
| `401` `auth.token_invalid` / `auth.token_missing` | Refresh once (section 5), retry the call once. If refresh fails, sign out locally. |
| `401` `identity.session_revoked` | The session ended (signed out elsewhere, replaced by a new phone). Clear tokens, show sign-in. |
| `403` `auth.scope_insufficient` | A bug in the app: the wrong token for this route. |
| `426` `request.client_outdated` | Force-update screen. |
| `429` `request.rate_limited` | Wait `Retry-After` seconds; show the wait, do not loop. |
| `409` `request.in_progress` | The same `Idempotency-Key` is still running: wait a moment and retry **with the same key**. |
| `503` `service.unavailable` | The server is briefly overloaded (no database connection came free in time). Retry after a second or two, with backoff, using the **same** `Idempotency-Key`. |
| `5xx`, timeouts | Retry only with the **same** `Idempotency-Key`; never mint a new key for a repeat of the same action. |

**Tokens.** Access tokens are JWTs valid **15 minutes**. Scopes: `onboarding` (after social sign-in, before the phone and device are verified: phone verification, device registration, recovery only), `app` (everything about your own account), `agent` (read-only, issued with `app`; only used for Asha, see section 9). Refresh before expiry (for example at 12–13 minutes) and on a `401`, **one refresh at a time** (single-flight): the refresh token rotates, so two parallel refreshes would revoke each other.

**Pages.** Lists take `?cursor=<opaque>&limit=<n>` and answer `nextCursor` (`null` at the end). Never build a cursor yourself.

**Rate limits worth designing around:** SMS codes 3 per number per 10 minutes (10 per phone per day); 5 wrong codes lock the account for 10 minutes; contact sync 5 per hour; handle lookups 30 per hour; everything else 120 per minute.

## 4. Shape of the KMP app

Follow the same standards as the backend: **Ktor client** for all HTTP, kotlinx.serialization, one version catalog, no second networking library.

**`commonMain`** (shared by both platforms):
- `CircasoApi`: a Ktor `HttpClient` with `ContentNegotiation(json)`, `WebSockets`, and one request interceptor that adds `X-Client-Version`, `Authorization` (when a session exists) and a new `Idempotency-Key` for every non-`GET`.
- `@Serializable` DTOs named exactly like the schemas in section 11 (`SocialSignInRequest`, `SessionDto`, …), and an `Envelope<T>` wrapper.
- `AuthManager`: the state machine of section 6 (`next` values), single-flight refresh, session storage.
- Repositories per area (profile, contacts, connections, assistant, account).
- A realtime client (section 8) and an SSE reader for Asha (section 9).

**Platform pieces behind `expect`/`actual` (or interfaces injected per platform):**

| Interface | Android | iOS |
| --- | --- | --- |
| `DeviceKeyStore` (section 5) | Android Keystore, EC P-256, StrongBox when available | Secure Enclave via Security.framework |
| `SocialSignIn` (`google(nonce)`, `apple(nonce)`) | Credential Manager + Google ID option | GoogleSignIn SDK, AuthenticationServices |
| `SecureStorage` (tokens, deviceId) | Keystore-backed encrypted storage | Keychain |
| `PushTokenProvider` | Firebase Cloud Messaging | APNs |
| `ContactsReader` | ContactsContract | Contacts.framework |
| `AudioRecorder`, `AudioPlayer` | MediaRecorder / ExoPlayer | AVFoundation |
| `ClientInfo` | platform = `android`, model, OS version, app version | platform = `ios`, … |

Ktor engines: OkHttp on Android, Darwin on iOS.

## 5. The device key (get this exactly right)

Each phone holds one **hardware-backed ECDSA P-256 key**. Its public half is registered once (`POST /v1/devices`); the private half never leaves the phone. It signs three things: the **refresh** request, the **same-phone sign-in** proof, and **encryption-key uploads**. A wrong format fails with no hint, `identity.device_proof_invalid` for sign-in and refresh and `messaging.keys_proof_invalid` for key uploads, so follow this literally.

- **Algorithm:** `ES256` (P-256, SHA-256). Send `keyAlgorithm: "ES256"`.
- **`publicKey`:** standard Base64 (with padding) of the **DER `SubjectPublicKeyInfo`** (the X.509 public key encoding).
  - Android: `keyStore.getCertificate(alias).publicKey.encoded` is already SPKI.
  - **iOS gotcha:** `SecKeyCopyExternalRepresentation` returns only the raw 65-byte point `04‖X‖Y`. Prepend this fixed 26-byte header, then Base64 the 91 bytes: `3059301306072A8648CE3D020106082A8648CE3D030107034200` (hex).
- **Signatures:** standard Base64 of the **DER-encoded ECDSA signature** (an ASN.1 sequence of r and s), over the UTF-8 bytes of the payload below.
  - Android `Signature.getInstance("SHA256withECDSA")` already produces DER.
  - iOS: `SecKeyCreateSignature` with `.ecdsaSignatureMessageX962SHA256` produces DER.
  - If a library hands you raw 64-byte `r‖s`, convert it to DER first.
- **Payloads (concatenate exactly; no spaces or newlines, except the single `\n` in key uploads):**
  - Same-phone sign-in (`deviceProof` on `POST /v1/auth/social`): `nonce + timestamp`.
  - Refresh (`POST /v1/auth/refresh`): `refreshToken + "|" + timestamp`.
  - Key uploads (`PUT /v1/keys` and `POST /v1/keys/one-time`): `timestamp + "\n" + hex(SHA-256(body))`, where `body` is the **exact bytes you send** as the request body and the hash is written as 64 lowercase hex characters. This is the one payload with a newline: exactly one `\n` byte (0x0A), nothing else between the parts. The proof goes in two headers, never in the body: `X-Device-Timestamp: <timestamp>` and `X-Device-Signature: <base64 DER signature>`. Serialise the body once, keep those bytes, hash them, sign, and send the same bytes (any re-serialisation after signing breaks it). **Send key uploads uncompressed** (no `Content-Encoding`): the server hashes the body after any decoding. Missing headers, a bad signature or a timestamp more than 5 minutes off answer `messaging.keys_proof_invalid` (403).
    - Example (`POST /v1/keys/one-time` at `2026-10-25T09:00:00Z`), body of 93 bytes: `{"oneTimePreKeys":[{"keyId":101,"publicKey":"BQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g"}]}`
    - Its SHA-256 in hex: `94a80dfab795f31ad61df91575b9a16e6c60ba7e0b056b80b7ed87536b08660a`
    - The 85-byte string to sign (the first line, one 0x0A byte, the second line):

      ```
      2026-10-25T09:00:00Z
      94a80dfab795f31ad61df91575b9a16e6c60ba7e0b056b80b7ed87536b08660a
      ```

    - Headers: `X-Device-Timestamp: 2026-10-25T09:00:00Z` and `X-Device-Signature: <base64 DER signature over those 85 bytes>`.
  - **After a refused top-up** (`messaging.prekey_id_exists` or `messaging.too_many_prekeys`), nothing from that request was ever stored or handed out: the server checks and inserts in one transaction and refuses it whole. You can delete that request's private keys at once, and send a new top-up with new ids.
- **`timestamp`:** an ISO-8601 UTC string, for example `2026-10-24T18:58:25Z`. **Send exactly the string you signed.** The server rejects a proof more than **5 minutes** away from its own clock, so a phone with a wrong clock cannot sign in or upload keys: show a "check your date and time" message for `identity.device_proof_invalid` and `messaging.keys_proof_invalid`.
- **`deviceId`:** a UUID the app generates **once per install** and stores next to the key. Reuse it in every request. On Android it is lost with the app data; on iOS the Keychain may keep it across reinstall, and that is fine.
- A lost or replaced key is not an error to fix in code: the next social sign-in answers `next: REVERIFY_PHONE` and the person verifies the number again.

## 6. Flows, step by step

All calls need `X-Client-Version`; every non-`GET` needs `Idempotency-Key`.

### A. First sign-in (new account)

1. `POST /v1/auth/nonce` → `nonce` (valid 5 minutes, single use).
2. Run the platform sign-in with that nonce (Google: nonce as given, `serverClientId` = the **Web** client id; Apple: pass Apple the SHA-256 hex of the nonce, the raw nonce is also accepted by the server).
3. `POST /v1/auth/social` with:
   ```json
   { "provider": "google",
     "idToken": "…", "nonce": "…",
     "appleName": { "given": "Amina", "family": "Wanjiru" },
     "device": { "deviceId": "<uuid>", "platform": "android", "model": "Pixel 8", "osVersion": "15", "appVersion": "1.4.0" } }
   ```
   `provider` is `google` or `apple`. Apple gives the person's name **only the first time**: send it as `appleName` immediately. The answer holds `next`, an **onboarding** session and `maskedPhone`.
4. Branch on `next`:

| `next` | Do |
| --- | --- |
| `VERIFY_PHONE` | Section B, then C. |
| `REVERIFY_PHONE` | A known account on an unknown phone: section B with the account's own number, then C. The old phone is signed out. |
| `REGISTER_DEVICE` | The number is verified on this phone but it has no key: section C. |
| `DONE` | This phone is registered and you proved it with its key (section D): you already have an **app** session. |

### B. Verify the phone

1. `POST /v1/auth/phone/start` `{ "phone": "+254712345678" }` → `verificationId`, `codeLength` (6), `expiresInSeconds` (300), `resendAfterSeconds`. Disable the resend button until that many seconds pass.
2. `POST /v1/auth/phone/verify` `{ "verificationId", "code" }` → `phoneVerified`, `next`.
   - `identity.code_invalid` (message says tries left), `identity.code_expired`, `identity.code_locked` (wait 10 minutes), `identity.phone_taken`, `identity.phone_mismatch`, `identity.sms_unavailable` (offer retry).
3. Android only, optional: `POST /v1/auth/phone/carrier` `{ "carrierToken" }` instead of SMS. Fall back to SMS on `identity.carrier_unsupported`.

### C. Register the device (activates the account)

`POST /v1/devices` with the **onboarding** token:
```json
{ "deviceId": "<uuid>", "publicKey": "<base64 SPKI>", "keyAlgorithm": "ES256",
  "pushToken": "<fcm or apns token, if you have it>", "attestation": null, "model": "Pixel 8" }
```
The answer holds the **app** session, the **agent** session and the user state. Store the refresh token securely. The account is now active.

### D. Signing back in on the same phone

Same as A, but add the proof to step 3: `"deviceProof": { "timestamp": "<iso>", "signature": "<base64 DER over nonce + timestamp>" }`. If the key matches this account, `next` is `DONE` and the response already carries the app session: no phone step.

### E. Keeping the session alive

Before the access token ends (and on a `401`), `POST /v1/auth/refresh`:
```json
{ "refreshToken": "…", "deviceId": "<uuid>", "timestamp": "<iso>", "signature": "<base64 DER over refreshToken|timestamp>" }
```
→ new `session` (access and refresh) and `agentSession`. The old refresh token is dead the moment this succeeds: **store the new one before using it**, and refresh single-flight.

### F. Ending a session

- `POST /v1/auth/signout`: ends the session, the phone stays registered (the next sign-in is one tap, flow D).
- `DELETE /v1/devices/current`: forget this phone entirely; the next sign-in verifies the number again.
- A `session.revoked` realtime frame or `identity.session_revoked` means the server ended it: clear tokens and show sign-in.

### G. Profile picture

1. `POST /v1/me/avatar/upload-url` `{ "contentType": "image/jpeg", "sizeBytes": 204800 }` (JPEG, PNG or WebP, up to 5 MB) → a pre-signed `PUT` URL and an `objectKey`.
2. `PUT` the image bytes straight to that URL (not to the API; send the `Content-Type` you declared).
3. `POST /v1/me/avatar` `{ "objectKey" }` → the updated profile. The picture URL in profiles is a pre-signed link valid **1 hour**: reload the profile instead of caching the URL.

### H. Contacts

- `POST /v1/contacts/sync` with `{ "syncId": "<uuid for this whole sync>", "batch": 1, "final": false, "phones": ["+2547…", …] }` in batches of up to **1,000** numbers; keep the same `syncId`, count `batch`, and set `final: true` on the last one. Send E.164 numbers only; names never leave the phone. The first full sync adds contacts; later syncs only send changes. 5 syncs per hour.
- Show people with `GET /v1/contacts` (paged). Connect with someone outside the phone book through `POST /v1/connections` (`via`: `handle`, `qr` or `invite`; `value`).


## 7. Endpoint catalogue

Every route the phone calls, in the order a person meets them. **Scope** is the token the call needs (`none` = no token). The status says whether the backend behind it is finished. Exact field lists for every request and response are in the schema appendix (section 11); the live source of truth is `/docs/openapi.json`.


### Sign in

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/auth/nonce`<br>**I1** One-time nonce for the Google or Apple request, valid 5 minutes | `none` | — → NonceResponse | **READY** | Valid 5 minutes, single use. |
| `POST /v1/auth/social`<br>**I2** Verify the Google or Apple ID token and learn the next step | `none` | SocialSignInRequest → SignInResponse | **READY** | `next` is `VERIFY_PHONE`, `REVERIFY_PHONE`, `REGISTER_DEVICE` or `DONE`; the session is an `onboarding` session unless `next` is `DONE`. Google verified on the dev server. Apple: the iOS bundle id `com.circaso.app` is configured, not yet tried with a real token. Errors: `identity.nonce_invalid`, `identity.provider_token_invalid`, `identity.recovery_pending`, `identity.account_suspended`, `identity.account_blocked`, `identity.account_deleted`. |
| `POST /v1/auth/refresh`<br>**I7** New token pair for a signed refresh request; the old refresh token dies | `none` | RefreshRequest → RefreshResponse | **READY** | No bearer token; the phone proves itself by signing `refreshToken\|timestamp` with its device key. Needs the device-key signature (section 5). Errors: `identity.session_revoked`, `identity.device_proof_invalid`. |
| `POST /v1/auth/signout`<br>**I8** End this session; the phone stays registered | `app` | — → NoBody | **READY** | The phone stays registered. The server then sends `system` / `session.revoked` on this session's socket and closes it (section 8); no push follows. |
| `GET /v1/.well-known/jwks.json`<br>Public keys the access tokens are signed with | `none` | — → JwksResponse | **READY** | The app does not need it; the server verifies its own tokens. |

### Phone verification

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/auth/phone/start`<br>**I3** Send a 6-digit SMS code | `onboarding` | StartPhoneRequest → StartPhoneResponse | **PARTIAL** | 3 codes per number per 10 minutes, 10 per phone per day. Built on Twilio Verify. A real SMS send has not been confirmed end to end yet (the first attempts failed provider-side), so test with a real number and report the `code` you get. Errors: `identity.phone_invalid` (not an E.164 number), `identity.phone_mismatch`, `identity.code_locked`, `identity.sms_unavailable`, `request.rate_limited`. |
| `POST /v1/auth/phone/verify`<br>**I4** Check the SMS code; claims the number on sign-up | `onboarding` | VerifyCodeRequest → PhoneVerifiedResponse | **PARTIAL** | Five wrong codes lock the account for 10 minutes. Same as above: `code` is checked by Twilio on the dev server. Errors: `identity.code_invalid`, `identity.code_expired`, `identity.code_locked`, `identity.phone_taken`, `identity.phone_mismatch`. |
| `POST /v1/auth/phone/carrier`<br>**I5** Same as I4 from a carrier token, with no SMS | `onboarding` | CarrierRequest → PhoneVerifiedResponse | **DEV STUB** | Android-only one-tap check. On the dev server the provider is `log`: the `carrierToken` is simply the phone number in E.164. The real Firebase phone-number verification is not built, so keep SMS as the fallback. Errors: `identity.carrier_unsupported`, `identity.phone_taken`, `identity.phone_mismatch`. |
| `POST /v1/auth/phone/release`<br>**I16** Free a number held by a lost, unverified account after a 7-day notice | `onboarding` | ReleaseRequest → ReleaseResponse | **READY** | Needs a verification that proved the SIM (I3 and I4). Rare path. There is no route yet for the old phone to cancel a release. Errors: `identity.release_not_allowed`, `identity.code_expired`. |

### Register device

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/devices`<br>**I6** Register this phone's key; activates the account on sign-up and issues the app session | `onboarding` | RegisterDeviceRequest → RegisterDeviceResponse | **PARTIAL** | Send `X-Client-Version` (`android/1.0.0`): it carries the platform and app version. Works on dev (attestation is only logged). In production no device can register until Play Integrity / App Attest verification is built; the format of `attestation` is not decided yet, so send the platform token if you have one and expect it to be ignored for now. Errors: `identity.device_attestation_failed`, `request.malformed`, `request.in_progress` (409: a second registration racing the first; keep the other response; don't retry this one). |

### Profile

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/me/profile`<br>**U1** Your full profile, privacy settings included | `app` | — → MyProfileResponse | **READY** | `avatarUrl` is a pre-signed link valid for 1 hour: do not cache it longer, reload the profile. |
| `PATCH /v1/me/profile`<br>**U2** Change name, about and privacy | `app` | UpdateProfileRequest → MyProfileResponse | **READY** | Send only the fields being changed, plus the `version` you last read. An `about` of `""` clears it. Send only changed fields plus the `version` you last read (409 `users.version_conflict` means reload). Errors: `users.profile_invalid`, `users.version_conflict`. |
| `POST /v1/me/avatar/upload-url`<br>**U3** A pre-signed URL to upload a JPEG, PNG or WebP up to 5 MB | `app` | UploadUrlRequest → UploadUrlResponse | **READY** | Upload the bytes straight to the returned URL with `PUT`, then confirm. Errors: `users.avatar_invalid`. |
| `POST /v1/me/avatar`<br>**U4** Confirm an uploaded picture | `app` | SetAvatarRequest → MyProfileResponse | **READY** | Confirms the upload with the `objectKey` from the upload-url step. Errors: `users.avatar_invalid`. |
| `DELETE /v1/me/avatar`<br>**U4** Remove your picture | `app` | — → MyProfileResponse | **READY** |  |
| `GET /v1/users/{userId}`<br>**U10** The public profile of someone you can reach | `app` | — → PublicProfileDto | **READY** | Only people you can reach. Errors: `users.not_reachable`. |

### Handles

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/handles/{handle}/availability`<br>**U5** Whether a @handle is free, taken or invalid, with a suggestion | `app` | — → HandleAvailabilityResponse | **READY** | 120 per hour, its own count (checking as you type). Errors: `request.rate_limited`. |
| `PUT /v1/me/handle`<br>**U6** Claim or change your @handle whenever the new one is free | `app` | ClaimHandleRequest → MyProfileResponse | **READY** | Whenever the new handle is free; at most 10 changes a day (`request.rate_limited`). The old handle stays held for you for 30 days. Errors: `users.handle_invalid`, `users.handle_taken`, `request.rate_limited`. |
| `GET /v1/users/by-handle/{handle}`<br>**U9** Exact-match @handle lookup | `app` | — → PublicProfileDto | **READY** | 30 lookups per user per hour. Exact match; an owner who turned lookup off, or whose account is deleted or suspended, looks like not found. Errors: `users.handle_not_found`, `request.rate_limited`. |

### Contacts

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/contacts/sync`<br>**U7** Upload a batch of phone-book numbers (up to 1,000) and get back the ones on Circaso | `app` | SyncRequest → SyncResponse | **READY** | 5 syncs per user per hour. Names from the phone book never leave the phone. Up to 1,000 numbers per batch, 5 syncs per hour. Send E.164 numbers only; names never leave the phone. Errors: `users.sync_too_large`, `request.rate_limited`. |
| `GET /v1/contacts`<br>**U8** Your contacts with public profiles | `app` | — → ContactsResponse | **READY** | Query: `cursor`, `limit` (default 50, at most 200). Paged with `cursor` and `limit`. A contact whose account is deleted or suspended is left out until it is active again. |
| `DELETE /v1/contacts/{userId}`<br>**U16** Remove a contact; a sync won't add them back | `app` | — → RemovedResponse | **READY** | Errors: `users.contact_not_found`. |

### Connections and invites

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/connections`<br>**U11** Connect through a @handle, QR code or invite link | `app` | ConnectRequest → ConnectResponse | **READY** | `via` is `handle`, `qr` or `invite`. A QR code or invite connects both ways; a typed @handle only one way. Handle, QR payload or invite link. Errors: `users.handle_not_found`, `users.invite_invalid`, `users.not_reachable`. |
| `POST /v1/invites`<br>**U12** A shareable link and QR payload, valid 7 days | `app` | — → InviteResponse | **READY** | Valid 7 days. |
| `DELETE /v1/invites/{token}`<br>**U12** Revoke an invite you made | `app` | — → NoBody | **READY** | Errors: `users.invite_invalid`. |

### Blocks and reach

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/blocks`<br>**U13** People you have blocked | `app` | — → BlocksResponse | **READY** |  |
| `PUT /v1/blocks/{userId}`<br>**U14** Block someone; a block overrides every other way of reaching a person | `app` | — → NoBody | **READY** | Errors: `users.cannot_block_self`, `request.not_found` (no such account). |
| `DELETE /v1/blocks/{userId}`<br>**U14** Lift a block | `app` | — → NoBody | **READY** |  |
| `GET /v1/me/reach/{userId}`<br>**U15** Whether you can reach that person | `app` | — → ReachResponse | **READY** | Never says why not. |

### Encryption keys

Chats are end-to-end encrypted on the phone with libsignal (PQXDH, double ratchet, sender keys); the server only stores public keys and ciphertext. Keys are standard base64.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `PUT /v1/keys`<br>**M1** Publish this phone's identity key, signed prekey, signed Kyber prekey and up to 100 one-time prekeys | `app` | PublishKeysRequest → OneTimePreKeysLeftResponse | **READY** | Device-signed in the `X-Device-Timestamp` and `X-Device-Signature` headers (section 5, key uploads). Replaces the whole set: one-time prekeys from an earlier upload are deleted, so send fresh ones with it. Publish after registering the device, and again every 7 days to rotate the signed prekey. Errors: `messaging.keys_proof_invalid`, `messaging.too_many_prekeys` (more than 100 keys), `request.malformed`. |
| `POST /v1/keys/one-time`<br>**M1** Top up one-time prekeys | `app` | AddOneTimeKeysRequest → OneTimePreKeysLeftResponse | **READY** | Device-signed in the same headers. Top up when `GET /v1/keys/me` shows fewer than 20. At most 100 are kept at once; an upload that would pass that is refused whole with `messaging.too_many_prekeys`. Resending a key id that is already stored is refused whole with `messaging.prekey_id_exists` (409): use new ids. The 100 cap is exact, also for top-ups sent at the same moment (they run one after another on the server). Errors: `messaging.keys_proof_invalid`, `messaging.keys_not_found` (this phone has not published with `PUT /v1/keys`), `messaging.too_many_prekeys`, `messaging.prekey_id_exists`, `request.malformed`. |
| `GET /v1/keys/me`<br>**M2** One-time prekeys left and when the signed prekey was set | `app` | — → KeyStatusResponse | **READY** | `messaging.keys_not_found` until this phone has published its own keys (also after a new phone, whose keys must be published again). |
| `GET /v1/keys/{userId}`<br>**M3** A person's key bundle, to start an encrypted session | `app` | — → KeyBundleResponse | **READY** | Uses up one of their one-time prekeys; `oneTimePreKey` is missing or null when none are left, which PQXDH allows; `kyberPreKey` is always present. You must be able to reach the person, or share a group with them (group members start sessions with each other to hand over sender keys). 60 bundles per user per hour. Errors: `users.not_reachable`, `messaging.keys_not_found`, `messaging.rate_limited`. |

### Chats and members

A direct chat is exactly two people, one chat per pair. A group holds up to 1,000 people with one `OWNER`, any number of `ADMIN`s and `MEMBER`s. The group's name and avatar are encrypted on the phone under the group metadata key and sent as `sealedMeta` (base64, at most 4 KB decoded); the server stores it and never reads it. Membership changes arrive as `member.added` / `member.removed` frames (section 8), role changes as `chat.updated`: on `member.removed`, every remaining phone makes a new sender key before its next group message; on `member.added`, send the new member your current sender key. Every response is a ChatDto: `members` carry public profiles and roles, `version` is what `PATCH` sends back, `metaVersion` rises each time the name is re-sealed.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/chats/direct`<br>**M4** Open the direct chat with a person, or get the one you already have | `app` | OpenDirectChatRequest → ChatDto | **READY** | You must be able to reach the person. `created` is true on the first open, false after; two people opening it at the same moment get the same chat. `sealedMeta` is null. Errors: `users.not_reachable`, `request.malformed` (your own id). |
| `POST /v1/chats/groups`<br>**M5** Create a group | `app` | CreateGroupRequest → ChatDto | **READY** | You become the owner. People you can't reach are left out and listed in `notAdded`, with no reason (don't show one). At most 1,000 people, you included, counting everyone you asked for. Admins add members by default (`settings.membersCanAdd` false). The group's id is made from your `Idempotency-Key`: if the call fails or times out, retry with the **same** key and it carries on with the same group (never a second one). If the group changed in the meantime (someone left, say), the retry returns it as it is and adds nobody. Errors: `messaging.group_invalid`, `messaging.group_full`, `request.malformed`. |
| `GET /v1/chats`<br>**M6** Every chat you are in | `app` | — → ChatsResponse | **READY** | With members' public profiles and `lastMessageId`: the newest `MESSAGE` sent in the chat in the last 30 days, null before the first and for a chat with no message in the last 30 days (keep the last id you saw). Sort and count unread on the phone. |
| `GET /v1/chats/{chatId}`<br>**M7** One chat with members and roles | `app` | — → ChatDto | **READY** | Errors: `messaging.chat_not_found` (also when you're not in it). |
| `PATCH /v1/chats/{chatId}`<br>**M8** Change the sealed name and avatar, or `settings.membersCanAdd` | `app` | UpdateGroupRequest → ChatDto | **READY** | Admins only. Send the `version` you last read; any change to the group since (members included) answers `messaging.version_conflict`: reload with `GET` and retry. A new `sealedMeta` raises `metaVersion`. Members get `chat.updated`. Errors: `messaging.chat_not_found`, `messaging.not_admin`, `messaging.group_invalid`, `messaging.version_conflict`. |
| `POST /v1/chats/{chatId}/members`<br>**M9** Add people to a group | `app` | AddMembersRequest → ChatDto | **READY** | Admins, or anyone when `settings.membersCanAdd` is on. People you can't reach are listed in `notAdded`; people already in are skipped. Every member gets one `member.added` frame listing everyone added in `userIds`. Errors: `messaging.chat_not_found`, `messaging.not_admin`, `messaging.group_full`. |
| `DELETE /v1/chats/{chatId}/members/{userId}`<br>**M10** Remove someone, or leave (your own id) | `app` | — → MemberRemovedResponse | **READY** | Admins remove admins and members; nobody removes the owner. When the owner leaves, ownership passes to the longest-serving admin, or else the longest-serving member, and the others get `chat.updated`. The last member leaving deletes the group. After a removal, the admin's phone makes a new group metadata key and re-seals the name (`PATCH`); after a leave, the owner's phone does (the new owner's, when the owner left). A direct chat can't be left and has nobody to remove (`messaging.not_a_group`). Errors: `messaging.chat_not_found` (also when the person was removed a moment ago), `messaging.not_admin`, `messaging.not_a_group`. |
| `PUT /v1/chats/{chatId}/members/{userId}/role`<br>**M11** Make someone an admin or a member, or hand over ownership | `app` | SetRoleRequest → ChatDto | **READY** | Admins set `ADMIN` or `MEMBER` on anyone but the owner (an admin may step down). Only the owner sets `OWNER`, and becomes an admin. A change moves the chat's `version` on and every member gets `chat.updated`. Errors: `messaging.chat_not_found`, `messaging.not_admin`, `request.malformed`. |

### Asha chat

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/assistant/chat/messages`<br>**A2** Ask Asha; the reply streams as server-sent events | `app` | SendMessageRequest → NoBody | **READY** | (the user token). Send the user's agent token in `X-Agent-Token` so Asha's tools can read contacts. The response is `text/event-stream`, not the envelope (errors before any text are the envelope): `message` events carry `{delta}`, each piece as the model writes it; a `tool_request` event `{turnId, callId, name, arguments}` asks the phone to run a device tool and answer with A9; `done` carries the saved reply; an `error` event `{code, message}` means the model failed after text had already gone out (nothing is saved). Errors: `assistant.quota_exceeded`, `assistant.model_unavailable`, `auth.token_invalid`, `request.malformed`. |
| `DELETE /v1/assistant/chat`<br>**A5** Delete the whole history with Asha | `app` | — → NoBody | **READY** | (the user token). |
| `GET /v1/assistant/chat`<br>**A1** The Asha chat, newest first | `app` | — → ChatResponse | **READY** | (the user token). Query: `cursor`, `limit` (default 50, at most 100). |
| `DELETE /v1/assistant/chat/messages/{messageId}`<br>**A6** Delete one exchange | `app` | — → NoBody | **READY** | (the user token). |
| `POST /v1/assistant/chat/messages/{messageId}/tool-results`<br>**A9** The phone's answer to a device tool; the reply then continues as server-sent events | `app` | ToolResultsRequest → NoBody | **READY** | (the user token). `messageId` is the `turnId` from the `tool_request` event. At most 30 short snippets; they are used for this turn and never stored. Answer a `tool_request` within 10 minutes. The response is a new `text/event-stream` carrying on the same reply (errors before any text are the envelope): `message` events `{delta}` as the model writes (append them to what was shown before the pause), another `tool_request` if Asha needs the phone again, `done` with the saved reply (it includes what was said before the pause), or an `error` event `{code, message}` if the model fails after text has gone out (nothing is saved). Errors: `assistant.turn_not_found`, `assistant.model_unavailable`, `auth.token_invalid`, `request.malformed`. |

### Asha voice and usage

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/assistant/voice`<br>**A3** Transcribe a voice clip | `app` | — → TranscriptResponse | **READY** | (the user token). The clip is the raw body (up to 60 s, under 3 MB); send `Content-Type` of the audio and `X-Duration-Seconds`, the clip's length in seconds as the recorder reports it (a decimal such as `5.5` is fine; it is rounded up, so `60.2` is over the limit). A missing or non-positive length and a clip over 3 MB are `request.malformed`, each named on its own in `details.fields` (`X-Duration-Seconds`, `audio`). Errors: `assistant.voice_too_long`, `assistant.quota_exceeded`, `request.malformed`. |
| `POST /v1/assistant/speech`<br>**A4** Turn a reply into audio | `app` | SpeechRequest → NoBody | **READY** | (the user token). The response is the audio itself; an error is the envelope. Returns audio, not the envelope. The server voice (Workers AI Aura) speaks English only: speak a Swahili reply with the phone's own text-to-speech instead of calling this route (with `"language": "sw"` the server answers `assistant.language_not_supported`, 400, and nothing is counted against the allowance). That refusal already works on dev, exactly as in production, so the Swahili path can be tested now. Errors: `assistant.quota_exceeded`, `assistant.language_not_supported`, `assistant.model_unavailable`. |
| `GET /v1/assistant/usage`<br>**A7** Today's allowance used and left, and the plan | `app` | — → UsageResponse | **PARTIAL** | (the user token). Works; the free allowance numbers and the paid plan are not decided yet, so show what the server returns and nothing hard-coded. |

### Wallet

A Circle user-controlled wallet: the server never holds keys and never moves money; the user signs every action with the Circle PIN in the Circle SDK. Each chain the wallet is on has its own address (EVM chains: lowercase `0x…`; Solana: base58). Amounts are strings: `amountMinor` (integer in the asset's smallest unit) and `amount` (whole units, for display).

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/wallet`<br>**W1** Your wallet: status, chains, assets, totals, fiat value | `app` | — → WalletResponse | **PARTIAL** | `status` is `NONE` (show "Create wallet"), `SETTING_UP` (the PIN isn't set yet: poll this route after the SDK finishes) or `ACTIVE`. `chains` lists each chain with its address and every asset offered there (zero included); `totals` adds each asset up over all chains. `?currency=KES` (any ISO 4217 code) adds `fiat` at USDC = 1 USD, null when no rate is known; `partial: true` means something held has no price yet. Credit "Rates by Exchange Rate API" next to the fiat value. `hold` is always null for now (the payment hold arrives with WS4). A chain with `receiveEnabled: false` is retired: don't show its address to be paid. Errors: `wallet.custody_unavailable`, `request.malformed`. |
| `POST /v1/wallet`<br>**W2** Create your wallet | `app` | — → CreateWalletResponse | **PARTIAL** | Run `challengeId` in the Circle SDK with `sdk` (`appId`, `userToken`, `encryptionKey`): the user sets the PIN and security questions, and Circle creates the wallet on the default chain (Base). Calling again while `SETTING_UP` gives a fresh challenge; `challengeId` is null when the PIN is already set and the wallet is on its way. On dev Circle runs on testnet: the challenge is real, so complete it in the Circle SDK, then poll W1 (or wait for `wallet.ready`). Errors: `wallet.already_exists`, `wallet.custody_unavailable`. |
| `POST /v1/wallet/session`<br>**W3** The Circle SDK's session values | `app` | — → SdkDto | **PARTIAL** | `appId`, `userToken`, `encryptionKey`, `expiresAt` (an hour at most). Keep them in memory only, never log them. Errors: `wallet.not_found`, `wallet.custody_unavailable`. |
| `GET /v1/wallet/chains`<br>**W12** The supported chains, and which the wallet is on | `app` | — → ChainsResponse | **PARTIAL** | Each chain is `ENABLED`, `ADDING` (asked for, not signed yet) or `AVAILABLE`, with the asset symbols offered on it; `defaultChain` is where a new wallet starts. A chain retired from receiving (`receiveEnabled: false`) is listed only while your wallet is on it, so it can be emptied. For the wallet-management screen. |
| `POST /v1/wallet/chains`<br>**W12** Add a supported chain | `app` | AddChainRequest → AddChainResponse | **PARTIAL** | `{ "blockchain": "SOL-DEVNET" }` (a name from the list above). Answers `ADDING` with a challenge to run in the SDK (the PIN creates the wallet there), or `ENABLED` with no challenge when the wallet is already on it, so a repeat is harmless. The chain shows in W1 once Circle has made it. Adds of the same chain within the hour (two taps, two phones) get the same challenge. A retired chain can't be added (`wallet.chain_unsupported`). Removing a chain isn't possible. Errors: `wallet.chain_unsupported`, `wallet.not_found`, `wallet.not_ready`, `wallet.custody_unavailable`, `request.malformed`. |
| `POST /v1/wallet/transfers`<br>**W4** Prepare a payment | `app` | PrepareTransferRequest → PrepareTransferResponse | **PARTIAL** | `{ "blockchain": "BASE-SEPOLIA", "to": { "userId": "…" } \| { "address": "0x…" }, "amount": { "asset": "USDC", "amountMinor": "20000000" } }` (or `"amount": "20"`; both only if they agree), optional `chatId` (paying from that chat: sharing it stands in for reach). The payment leaves from your wallet on that chain. Checks, in this order: account active, your wallet on the chain, hold, amount and limits, the payee, your balance there. Run `challengeId` in the Circle SDK with `sdk` before `expiresAt` (15 minutes); follow it with `transfer.updated` frames or W5. Always the same `Idempotency-Key` for one payment: a retry returns the same transfer (and the same challenge while it can be signed). `fee` is always zero (Circaso pays gas). Show a warning before paying an address: payments on chain can't be reversed. On dev Circle runs on testnet: sign the challenge in the SDK, and the frames follow from Circle's webhooks. `proposalId` is refused (`request.malformed`) until Asha's payment proposals exist. Errors: `wallet.account_restricted`, `wallet.chain_unsupported`, `wallet.not_found`, `wallet.not_ready`, `wallet.not_on_chain`, `wallet.on_hold`, `wallet.amount_invalid`, `wallet.limit_exceeded`, `wallet.self_transfer`, `users.not_reachable`, `wallet.recipient_no_wallet`, `wallet.recipient_not_on_chain`, `wallet.address_invalid`, `wallet.insufficient_funds`, `wallet.pin_locked`, `wallet.custody_unavailable`, `request.malformed`. |
| `GET /v1/wallet/transfers/{transferId}`<br>**W5** One payment and its state | `app` | — → TransferResponse | **PARTIAL** | For the sender (`direction: OUT`) and the person paid (`IN`), so the chat's payment card works on both sides. `origin` and `proposalId` are only shown to the sender (null for the person paid). `state`: `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED` (see `failureReason`) or `EXPIRED`. Errors: `wallet.transfer_not_found`, `request.malformed`. |
| `GET /v1/wallet/recipients/{userId}`<br>**W9** Can this person be paid, and where | `app` | — → RecipientResponse | **PARTIAL** | Query `blockchain` (optional, the chain you mean to pay on) and `chatId` (optional). `status`: `CAN_RECEIVE`, `NO_WALLET` (offer W10: "Amina hasn't set up her wallet yet") or `NO_WALLET_ON_CHAIN` (pick one of `blockchains`, where they can be paid now). Errors: `users.not_reachable`, `wallet.self_transfer`, `wallet.chain_unsupported`, `request.malformed`. |
| `POST /v1/wallet/recipients/{userId}/remind`<br>**W10** Remind a contact to set up a wallet | `app` | — → RemindResponse | **PARTIAL** | They get a `wallet` / `setup.requested` frame and a push. `status`: `SENT`, `ALREADY_SENT` (once per person per 24 hours) or `HAS_WALLET` (pay them instead). Errors: `users.not_reachable`, `wallet.self_transfer`, `request.malformed`. |
| `POST /v1/wallet/addresses/validate`<br>**W11** Check an outside address on a chain | `app` | ValidateAddressRequest → ValidateAddressResponse | **PARTIAL** | `{ "blockchain", "address" }` → `valid` (an answer, not an error) and `address` as it will be paid (EVM lowercase). Check before W4 to an address. Errors: `wallet.chain_unsupported`, `wallet.custody_unavailable`, `request.malformed`. |

### Chama (a group's shared goal)

A chama is a group chat with a goal. The money sits in the **treasurer's** own wallet (one member; the goal names who): contributing is an ordinary W4 payment to the treasurer with `chatId` = the group, after which you send the payment card into the chat; the server counts it from the confirmed transfer. Paying someone from the group needs the admins' approval, then the treasurer pays it (W4 from the chat) and links the transfer. Amounts are strings as across the wallet.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/chama/chats/{chatId}`<br>**CH1** The goal, totals and open requests | `app` | — → ChamaResponse | **READY** | Members only. `held` = `contributed` − `paidOut`. `chama.no_goal` when the group has none yet (offer the admins "Set a goal"). Errors: `chama.not_a_member`, `chama.no_goal`. |
| `PUT /v1/chama/chats/{chatId}/goal`<br>**CH2** Set or change the goal | `app` | SetGoalRequest → GoalDto | **READY** | Admins only. `treasurerUserId` defaults to the caller and must be a member. Everyone gets `chama` / `goal.updated`. Errors: `chama.not_admin`, `chama.goal_invalid`, `chama.treasurer_not_member`. |
| `GET /v1/chama/chats/{chatId}/ledger`<br>**CH3** Contributions in, payments out | `app` | — → LedgerResponse | **READY** | `?limit=` (≤200), `?before=` from the previous page's `next`. |
| `POST /v1/chama/chats/{chatId}/requests`<br>**CH4** Ask the group to pay someone | `app` | CreateRequestRequest → RequestDto | **READY** | Any member. Needs two admins' approval (all admins when fewer); an admin asking counts as their own. Open 48 hours, then `EXPIRED`. Everyone gets `chama` / `request.updated`; admins still to decide are pushed. Errors: `chama.no_goal`, `chama.amount_invalid`, `chama.payee_invalid`. |
| `POST /v1/chama/requests/{requestId}/approve` and `/decline`<br>**CH5** Decide | `app` | — → RequestDto | **READY** | Admins, once each. Enough approvals → `APPROVED`; one decline → `DECLINED`. Errors: `chama.not_admin`, `chama.request_closed`, `chama.already_decided`. |
| `POST /v1/chama/requests/{requestId}/payment`<br>**CH6** Link the treasurer's payment | `app` | AttachPaymentRequest → RequestDto | **READY** | Treasurer only, for an `APPROVED` request: pay with W4 (`to` = the request's payee, the same amount, `chatId` = the group), then send the `transferId` here. `PAID` once confirmed on chain. Errors: `chama.not_treasurer`, `chama.not_approved`, `chama.transfer_mismatch`. |

### Calls

Voice and video calls in a chat (direct or group), at most 8 people in a call at once. Each phone holds **one** WebRTC peer connection to Cloudflare Realtime's SFU; the server only brokers session descriptions and rings people. A chat has one call at a time. Media is encrypted in transit (DTLS-SRTP) but decrypted at the SFU: say **"Secure call"**, never "end-to-end encrypted" (decision 2026-10-29). Every state change sends an `Idempotency-Key`.

The flow: the caller **CL1** (gets `iceServers`), builds the peer connection with them, adds its tracks, creates an offer and **CL3** joins with it (set `answer` as the remote description). The callee gets `call.ringing` (or the push), reads the call with **CL2** (for `iceServers`) and joins the same way, or **CL6** declines. On every `call.updated`, **CL4** pulls the tracks of the people `JOINED` that you don't receive yet (by `userId` and `trackName` from `participants`); when `requiresRenegotiation`, set `offer` as the remote description, create an answer and send it with **CL5**. **CL8** for mute and camera, **CL7** to hang up. Name tracks uniquely in your own session, for example `audio-<yourUserId>` and `video-<yourUserId>`.

**CallResponse**: `callId`, `chatId`, `kind` (`VOICE` \| `VIDEO`), `state` (`RINGING` \| `ACTIVE` \| `ENDED`), `startedBy`, `createdAt`, `startedAt` (when a second person joined; null before), `endedAt`, `endReason` (`COMPLETED` \| `CANCELLED` \| `MISSED` \| `DECLINED`; null until ended), `participants` [{`userId`, `displayName`, `state` (`INVITED` \| `JOINED` \| `DECLINED` \| `MISSED` \| `LEFT`), `audio`, `video`, `tracks` [{`trackName`, `kind`}], `joinedAt`}], `iceServers` [{`urls`, `username`, `credential`}] (STUN always; TURN with credentials valid for a day when configured; empty in frames). Other people's SFU sessions never appear.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/calls`<br>**CL1** Call a chat | `app` | `{ "chatId", "video": false }` → CallResponse | **PARTIAL** | Everyone in the chat is `INVITED`, the caller too until they join (CL3). The others get `call.ringing`. A chat with a call ringing or active answers `calls.already_in_call` (409): fetch it with `GET /v1/chats/{chatId}/call` and join it. Errors: `calls.not_a_member`, `calls.already_in_call`, `request.malformed`. |
| `GET /v1/calls/{callId}`<br>**CL2** A call | `app` | — → CallResponse | **PARTIAL** | The chat's members only. Errors: `calls.not_found`, `calls.not_a_member`, `request.malformed`. |
| `GET /v1/chats/{chatId}/call`<br>**CL2** The chat's call going on | `app` | — → CallResponse | **PARTIAL** | The call `RINGING` or `ACTIVE` in the chat, for a group's "Join call" bar; `calls.not_found` (404) when there is none. Errors: `calls.not_found`, `calls.not_a_member`. |
| `POST /v1/calls/{callId}/join`<br>**CL3** Join with your offer | `app` | `{ "offer": "<sdp>", "tracks": [{ "mid": "0", "trackName": "audio-<userId>", "kind": "audio" }] }` → `{ "sessionId", "answer": "<sdp>", "call": CallResponse }` | **PARTIAL** | 1 to 4 tracks, `kind` `audio` or `video`, `mid` as in the offer. Keep `sessionId` for CL4 and CL5. The second person in makes the call `ACTIVE`; everyone gets `call.updated`. Joining again (after the app restarted, or after leaving a group call still going) gives a new session and drops the old one. A member who was `MISSED` or `DECLINED` in a group call can still join. Errors: `calls.not_found`, `calls.not_a_member`, `calls.call_ended`, `calls.call_full`, `calls.media_unavailable`, `request.malformed`. |
| `POST /v1/calls/{callId}/pull`<br>**CL4** Receive others' tracks | `app` | `{ "sessionId", "tracks": [{ "userId", "trackName" }] }` → `{ "offer": "<sdp>" \| null, "requiresRenegotiation": true, "tracks": [{ "userId", "trackName", "mid" }] }` | **PARTIAL** | The server finds each person's session. A track that isn't available (they left, or don't publish it) is left out of `tracks`; nothing available → `offer: null`, `requiresRenegotiation: false`. `mid` is the transceiver in the SFU's offer, to match the remote track to its tile. Errors: `calls.not_found`, `calls.call_ended`, `calls.not_in_call`, `calls.session_mismatch`, `calls.media_unavailable`, `request.malformed`. |
| `POST /v1/calls/{callId}/renegotiate`<br>**CL5** Answer the SFU's offer | `app` | `{ "sessionId", "answer": "<sdp>" }` → `{}` | **PARTIAL** | After a CL4 with `requiresRenegotiation`. Errors: as CL4. |
| `POST /v1/calls/{callId}/decline`<br>**CL6** Decline | `app` | — → CallResponse | **PARTIAL** | A direct call ends (`DECLINED`; both get `call.ended`). In a group only you become `DECLINED`; the call ends when nobody is left to ring or talk to. Declining a call that no longer rings you changes nothing; the caller declining is CL7. Errors: `calls.not_found`, `calls.call_ended`. |
| `POST /v1/calls/{callId}/leave`<br>**CL7** Hang up | `app` | — → CallResponse | **PARTIAL** | Your tracks are closed at the SFU. The caller hanging up before anyone answered → `CANCELLED`. Otherwise the call ends (`COMPLETED`) when fewer than two people are left in it. Everyone gets `call.ended` with the final call. Leaving an ended call returns it. Errors: `calls.not_found`. |
| `POST /v1/calls/{callId}/media`<br>**CL8** Microphone and camera | `app` | `{ "audio": true, "video": false }` → `{}` | **PARTIAL** | Sets `audio`/`video` on your tile for everyone (`call.updated`). Keep the tracks; stop sending on them (`track.enabled = false`). Errors: `calls.not_found`, `calls.call_ended`, `calls.not_in_call`. |
| `POST /v1/calls/{callId}/ring`<br>**CL9** Ring people again ("Add people") | `app` | `{ "userIds": [] }` → CallResponse | **PARTIAL** | Group calls only, by someone `JOINED`. Members who `MISSED`, `DECLINED` or `LEFT` become `INVITED` and get `call.ringing` (pushed when offline); `userIds` narrows it, empty is everyone not on the call. Each person's ring lasts 45 seconds from when they were rung. Errors: `calls.not_found`, `calls.call_ended`, `calls.not_in_call`, `calls.not_a_group`. |

**Timeouts.** A person rung for 45 seconds without answering is `MISSED`; a call nobody answered ends `MISSED` and a direct callee is pushed "Missed call from {name}". The server checks every 15 seconds, so show your own 45-second ring and stop at `call.ended`. An `ACTIVE` call with no join, leave, pull or media change for 2 hours is ended. **Pushes:** see `calls` frames in section 8. iOS: the ring arrives as an FCM alert with `mutable-content` (a Notification Service Extension can enrich it); CallKit's VoIP pushes (PushKit) are not sent yet.

### Account and security

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `PUT /v1/devices/current/push-token`<br>**I9** Store this phone's FCM or APNs token | `app` | PushTokenRequest → NoBody | **READY** | Send the FCM token on both platforms (on iOS, FCM's token, which Firebase maps to APNs). Used for message and group-add pushes (section 8). Update it whenever the platform rotates it. |
| `DELETE /v1/devices/current`<br>**I10** Forget this phone; the next sign-in verifies the number again | `app` | — → NoBody | **READY** | Forget this phone; the next sign-in verifies the number again. |
| `GET /v1/account/security`<br>**I11** Phone, linked sign-in accounts, this phone and verification status | `app` | — → SecurityOverviewResponse | **READY** |  |
| `POST /v1/account/providers`<br>**I12** Link a Google or Apple login | `app` | LinkProviderRequest → NoBody | **READY** | Link Google or Apple. Errors: `identity.provider_in_use`, `identity.nonce_invalid`. |
| `DELETE /v1/account/providers/{provider}`<br>**I12** Unlink a login, only if the other one stays | `app` | — → NoBody | **READY** | Only while the other login stays. Errors: `identity.provider_last`. |
| `GET /v1/account/security-log`<br>**I13** Security activity, newest first | `app` | — → SecurityLogResponse | **READY** | Query: `cursor`, `limit` (default 50, at most 200). Paged. |
| `DELETE /v1/account`<br>**I14** Schedule account deletion once every guard agrees | `app` | — → NoBody | **PARTIAL** | Schedules deletion: the account turns `DELETED` at once (sign-in answers `identity.account_deleted`) then 30 days later the server erases the account and its data (profile, contacts, blocks, invites, Asha chat), and other people stop seeing the person at once. There is no restore route yet: tell the user deletion is final after 30 days and can't be undone from the app. Revoking Apple access at deletion is not built. Refused while the wallet's US dollar stablecoins (USDC), added up over every chain, come to 0.01 or more, or it holds any amount of any other asset: tell the user to send it out first. Errors: `wallet.funds_remaining`, `wallet.custody_unavailable` (the wallet couldn't check: Circle down, or a wallet on a chain no longer configured; try again later). |

### Account recovery

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/recovery/requests`<br>**I15** Open a support case for a lost Google or Apple login | `onboarding` | RecoveryRequest → RecoveryResponse | **PARTIAL** | Opens a support case. The support-side tools to resolve it are not built, so nothing completes it yet. Errors: `identity.phone_invalid`, `identity.recovery_not_eligible`, `request.rate_limited`. |
| `wss://<host>/v1/assistant/voice/live`<br>**A11** Live voice with Asha (WebSocket) | `app` (in the first frame) | frames, see section 9 | **READY** | A second socket, open only while talking to Asha hands-free. Authenticate with the first frame, not a header, and send fresh tokens in a new `auth` frame before the access token's 15 minutes are up. Opus in, mulaw 8 kHz out. Swahili is refused (close reason `assistant.language_not_supported`): use A3 and the phone's own voice. Close reasons: `unauthorized` and `auth required` (1008, no frame), `Frame is too big: N` (1009, Ktor's reason, no frame), and after a `closing` frame `assistant.language_not_supported`, `assistant.quota_exceeded`, `assistant.model_unavailable`, `silence`, `ended`. |
| `GET /v1/recovery/requests/{requestId}`<br>**I17** Status of your own support case | `onboarding` | — → RecoveryResponse | **PARTIAL** | Status of your own case. |

### Messages and delivery

Every message is encrypted on the phone (libsignal); the server holds the ciphertext only until each recipient's phone acknowledges it, and 30 days at most. A direct message carries one copy per recipient in `recipients` (the other person); a group `MESSAGE` or `CONTROL` carries one `ciphertext` for every member (sender keys); a `SENDER_KEY` message hands your new sender key to chosen members, each their own copy over your 1:1 sessions. Ciphertext is standard base64, at most 64 KB decoded. Message ids are made by the server and sort in sending order.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/chats/{chatId}/messages`<br>**M12** Send one encrypted message | `app` | SendChatMessageRequest → SentMessageResponse | **READY** | Make `clientMessageId` (a UUID) on the phone and keep it with the message: a retry with the same one, within 24 hours, returns the first `messageId` and sends nothing, so resend freely after a timeout. A direct message needs reach both ways. Set `pushable: true` on a `MESSAGE` to wake offline recipients (a data-only push; never for `SENDER_KEY` or `CONTROL`). Recipients get `message.new` (section 8). Errors: `messaging.chat_not_found`, `messaging.message_too_large`, `messaging.recipients_mismatch` (reload the chat's members), `users.not_reachable`, `request.malformed`. |
| `GET /v1/inbox`<br>**M13** Messages waiting for you, oldest first | `app` | — → InboxResponse | **READY** | `?after=<messageId>&limit=100` (at most 100). Call it after connecting the socket and after a push wake; page with `next` until it is absent. Each message has its ciphertext (a group's shared one included). |
| `POST /v1/inbox/ack`<br>**M13** Acknowledge messages you have | `app` | AckRequest → AckResponse | **READY** | 1 to 100 ids, once each message is decrypted and saved to your own history. The messages leave your inbox and their senders get a delivered receipt. With the socket open, send the `ack` frame instead (section 8). `acked` counts the ids that were still waiting. |
| `DELETE /v1/chats/{chatId}/messages/{messageId}`<br>**M14** Delete a message for everyone | `app` | — → MessageDeletedResponse | **READY** | Sender only, within 48 hours of sending, delivered or not. Copies not yet delivered are dropped and the other members get `message.deleted`. Errors: `messaging.chat_not_found`, `messaging.delete_window_passed`. |
| `PATCH /v1/messaging/settings`<br>**M22** Read receipts and typing indicators on or off | `app` | MessagingSettingsRequest → MessagingSettingsResponse | **READY** | Both on until turned off; a field left out stays as it is, so `{}` reads the current settings. Off means nobody gets your `read` receipts / sees you typing. |

**A replaced phone.** When someone moves to a new phone, their old keys are deleted, everyone who shares a chat with them gets `keys.changed` ("Amina's security code changed": fetch their new bundle), and messages still waiting for the old phone are dropped: each sender gets `message.undeliverable` and should resend from its own copy once the new keys are there.

### Attachments

Photos, voice notes, video and files travel Signal-style. The phone makes a fresh random AES-256-GCM key for each file, encrypts it, and uploads only the ciphertext, straight to storage (R2) through a pre-signed URL; the server never streams file bytes and never sees the key. The **key, the digest (SHA-256 of the ciphertext) and the real content type go inside the E2EE message** that refers to the attachment: send it with M12 like any other message (put the `attachmentId` in the encrypted payload; `mediaIds` is stored as given and not checked). Recipients decrypt the message, call AT3, download, check the digest, then decrypt the file. Order: **AT1 → PUT → AT2 → M12**. No socket frames: the message is the notification. Every `POST` sends an `Idempotency-Key`.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/chats/{chatId}/attachments`<br>**AT1** Start an encrypted upload | `app` | `{ "sizeBytes": 123456, "kind": "image" }` → `{ "attachmentId", "uploadUrl", "uploadHeaders": { "Content-Type": "application/octet-stream" }, "expiresAt" }` | **READY** | Members of the chat only. `kind` is `image`, `voice`, `video` or `file` (lowercase). `sizeBytes` is the exact size of the **ciphertext** (with the GCM tag), 1 byte to 32 MiB. Then `PUT` the ciphertext bytes to `uploadUrl` with exactly the `uploadHeaders` (no auth header, no envelope) before `expiresAt` (15 minutes); on expiry start again with AT1. The file is deleted 30 days after this call, or after a day if never completed. Errors: `messaging.chat_not_found` (no such chat, or you're not in it), `messaging.attachment_too_large`, `request.malformed`. |
| `POST /v1/attachments/{attachmentId}/complete`<br>**AT2** Complete the upload | `app` | — → `{ "attachmentId", "sizeBytes" }` | **READY** | The uploader only, after the PUT returned 2xx. The server checks the object is there at exactly AT1's `sizeBytes`. Completing twice answers the same, so retry freely. Then send the M12 message. Errors: `messaging.attachment_incomplete` (nothing uploaded, or a different size: PUT again while the URL works, or start over), `messaging.attachment_not_found` (not yours, unknown or expired), `request.malformed`. |
| `GET /v1/attachments/{attachmentId}`<br>**AT3** Download link | `app` | — → `{ "downloadUrl", "expiresAt", "sizeBytes" }` | **READY** | Anyone **currently** in the attachment's chat, once it is complete. `GET` `downloadUrl` (no auth header) before `expiresAt` (15 minutes); don't store the link, ask again. Verify the digest from the message before decrypting. After 30 days, or after you leave the chat, show "No longer available". Errors: `messaging.attachment_not_found` (also for a pending attachment or a chat you aren't in: the server doesn't say which), `request.malformed`. |

## 8. Realtime socket

While the app is open, keep one WebSocket to `wss://<host>/v1/ws` (Ktor `WebSockets` plugin; the server pings every 25 seconds). The **first frame** must authenticate within a few seconds or the server closes it:
```json
{ "type": "auth", "accessToken": "<app token>", "last": [{ "module": "users", "seq": 1042 }] }
```
`last` lists the last `seq` the app saw for each module, one entry per module, for example `[{ "module": "users", "seq": 1042 }, { "module": "messaging", "seq": 17 }]` (omit it or send `[]` on first connect). Changed on 2026-10-27: it was an object `{ "users": 1042 }`, which is now refused. Only an **app**-scope token is accepted. The server then replays what was missed and sends live frames:
```json
{ "module": "users", "type": "contact.joined", "id": "0193…", "seq": 1043, "ts": "2026-10-24T18:07:12Z", "data": { "userId": "…", "displayName": "Amina" } }
```
`seq` rises per user per module: a gap means a missed frame. A `resync` frame means the gap is older than 7 days: reload that module over HTTP. `messaging` / `message.new` and `typing` are never replayed and carry `seq` 0: don't store 0 as your last `seq` (a missed message waits in `GET /v1/inbox`). Reconnect with backoff and send your `last` values again. Refresh the access token **before** reconnecting if it has expired.

**Close reasons.** When the server closes the socket, the close code and reason tell you what to do:

| Code | Reason | Do |
| --- | --- | --- |
| 1008 | `auth required` | The first frame wasn't `{"type":"auth",…}` or came after 10 seconds. Fix the client; reconnecting with the same frame fails again. |
| 1008 | `invalid last` | `last` isn't a list of `{ "module", "seq" }` with whole numbers of 0 or more, or names a module twice (`[{"module":"users","seq":"x"}]`, `[{"module":"users","seq":-1}]` and the old object form `{"users":5}` are refused). Send `[]` or valid entries. |
| 1008 | `unauthorized` | The token is expired, revoked or not an `app` token. Refresh, then reconnect; if refresh fails, show sign-in. |
| 1008 | `session revoked` | Comes right after a `system` / `session.revoked` frame. Clear tokens, show sign-in; don't reconnect. |
| 1009 | `Frame is too big: …` | You sent a frame over 64 KB. A client bug: don't resend it. |

Any other close (network drop, server restart) means reconnect with backoff.

**Frames that exist today (everything else is MISSING):**

| `module` / `type` | `data` | Do |
| --- | --- | --- |
| `system` / `session.revoked` | empty (`{}`) | The session ended: you signed out (on this socket's session), a new phone took over, or the account was closed. Clear tokens, show sign-in. The socket then closes with `session revoked`. |
| `users` / `contact.joined` | `userId`, `displayName` | Someone from your phone book joined: add them to the contacts list. |
| `users` / `connection.added` | `userId`, `displayName` | Someone connected with you (QR or invite). |
| `users` / `profile.updated` | `userId`, `version` (number) | A contact changed their profile: reload that profile if `version` is newer than yours. |
| `messaging` / `member.added` | `chatId`, `userIds` (everyone this action added), `byUserId` | One frame per member per action (a group created, or one add of several people), to every member and the people added. Send the newcomers your current sender key; if you are in `userIds`, load the chat (`GET /v1/chats/{chatId}`). Pushed to the people added when offline ("You were added to a group"). |
| `messaging` / `member.removed` | `chatId`, `userId` (the person removed or who left), `byUserId` | To every member and the person. Remaining phones make a new sender key before their next group message; if it is you, drop the chat. |
| `messaging` / `chat.updated` | `chatId`, `metaVersion` (number), `version` (number) | The sealed name, avatar, settings or roles changed (a role change, a handover, or the owner leaving): reload the chat if `version` is newer than yours. |
| `messaging` / `message.new` | `messageId`, `chatId`, `senderId`, `kind`, `pushable`, `sentAt`; `mediaIds`, and `ciphertext` when it is at most 16 K base64 characters (`MESSAGING_FRAME_CIPHERTEXT_MAX_CHARS`; null otherwise) | A message for you. Without `ciphertext`, fetch it from `GET /v1/inbox`. Decrypt, save to history, then `ack`. Not replayed (`seq` 0). When offline and `pushable`, a data-only push with `chatId` and `messageId` wakes the app instead: fetch the inbox and show the notification yourself (iOS: in the Notification Service Extension, replacing the "New message" placeholder). |
| `messaging` / `receipt` | `chatId`, `receipts`: [{`messageId`, `userId`, `status` (`DELIVERED` \| `READ`), `at`}] | To the sender only. A `READ` receipt means read up to that message. Group receipts arrive batched every 5 seconds, `at` being when the batch was sent. Keep the furthest status per person. |
| `messaging` / `typing` | `chatId`, `userId`, `on` | Show or hide "typing…". Chats of 50 people or fewer only; not replayed (`seq` 0). Hide it yourself after a few seconds without a new one. |
| `messaging` / `message.deleted` | `chatId`, `messageId` | The sender deleted it for everyone: remove it. |
| `messaging` / `message.undeliverable` | `chatId`, `messageId`, `recipientId` | That person's phone was replaced before your message reached it: resend it to them from your copy once their new keys are published. |
| `messaging` / `keys.changed` | `userId` | Their phone and keys changed: show the security-code notice and start a new session with their new bundle. |
| `wallet` / `transfer.updated` | `transferId`, `state` (`SUBMITTED` \| `CONFIRMED` \| `FAILED`), `blockchain`, `amount` {`asset`, `amountMinor`, `amount`}, `txHash`?, `failureReason`? | To the sender each time a payment moves: update the payment's screen and chat card. Pushed when offline on `CONFIRMED` ("Payment sent") and `FAILED` ("Payment failed"). |
| `wallet` / `payment.received` | `transactionId`, `blockchain`, `amount`, `fromUserId`?, `fromName`? | Money reached you on chain: refresh the balance (W1). `fromUserId` is null for an outside address. Pushed: "Amina sent you money" (never the amount). |
| `wallet` / `wallet.ready` | `blockchain` | Your wallet turned `ACTIVE` (its first chain): reload W1 and stop polling. No push. |
| `wallet` / `setup.requested` | `fromUserId`, `fromName` | Someone wants to pay you and you have no wallet: offer "Create wallet" (W2). Pushed. |
| `calls` / `call.ringing` | `callId`, `chatId`, `kind` (`VOICE` \| `VIDEO`), `callerId`, `callerName` | Someone is calling the chat: show the incoming-call screen (ring for 45 seconds at most). To each person rung, not the caller. Offline: a data-only, high-priority push (TTL 45 s) with `kind: "call.ringing"`, `chatId`, `callId`, `callerName`, `callKind`. |
| `calls` / `call.updated` | CallResponse (with `iceServers` empty) | Someone joined, left, declined or muted, or the call turned `ACTIVE`: redraw the tiles, and pull (CL4) the tracks of anyone newly `JOINED`. To everyone the call rang. No push. |
| `calls` / `call.ended` | CallResponse (with `iceServers` empty) | The call is over (`endReason`): close the peer connection and the call screen. Offline: `MISSED` → an alert "Missed call from {name}" (`kind: "call.missed"`); `CANCELLED` while it was still ringing you → data-only (`kind: "call.ended"`), so stop ringing and show the missed call yourself. |

**Frames the phone sends** (after the auth frame only; commands stay on HTTP): `{ "module": "messaging", "type": "ack", "data": { … } }`. The server hands each to the module it names and never answers it directly; a frame for a module that takes none is dropped. **Limits:** at most 100 frames per second per socket (frames over it are dropped silently, the socket stays open, so batch acks and throttle typing) and at most 64 KB per frame (a bigger frame closes the socket with 1009). Messaging takes three:

| `type` | `data` | Does |
| --- | --- | --- |
| `ack` | `messageIds` (1 to 100) | As `POST /v1/inbox/ack`: the messages leave your inbox and their senders get a delivered receipt. |
| `read` | `chatId`, `upToMessageId` | You read the chat up to that message: moves your read marker (forward only) and sends its sender a `READ` receipt, unless you turned read receipts off. `upToMessageId` must be the newest `MESSAGE` you received from someone else in that chat: your own messages, sender keys and control messages produce no receipt (decision 2026-10-27). |
| `typing` | `chatId`, `on` | Relayed to the chat's other members, never stored; repeats within 6 seconds are dropped. Send `on: true` while typing (at most every few seconds) and `on: false` when you stop. Not relayed in chats of more than 50 people, or when you turned typing off. |

## 9. Asha (the assistant)

**Status: READY.** The routes and event format below are final, and the dev server runs the real model, speech-to-text and voice.

- **History:** `GET /v1/assistant/chat` (newest first, paged); delete one exchange or everything.
- **Ask:** `POST /v1/assistant/chat/messages` `{ "text", "chatReading": true|false, "askedFrom"? }`. Send `X-Agent-Token: <agentSession.accessToken>` so her tools can read your contacts. The answer is **`text/event-stream`**, not the envelope. Read the response body as a stream and parse `event:` and `data:` lines yourself (works on every Ktor engine). Errors before any text (a used-up allowance, a bad request) arrive as the normal envelope with a non-200 status.

| Event | `data` | Do |
| --- | --- | --- |
| `message` | `{"delta": "…"}` | Append the piece to the reply on screen. Pieces arrive as the model writes them (the first within a second or two), so show them at once. A piece may start with a blank line (`"\n\n…"`): Asha said something, looked something up, and carries on. |
| `tool_request` | `{"turnId","callId","name","arguments"}` | Asha needs something only the phone can do (search or read chats, which stay end-to-end encrypted). Run it locally, then `POST /v1/assistant/chat/messages/{turnId}/tool-results` `{ "callId", "snippets": [...] }` (at most 30 short snippets; answer within 10 minutes or get `assistant.turn_not_found`). That request's response is a new event stream that carries on the same reply: keep the text shown so far (what Asha said before asking, such as "Let me look in your chats.") and append to it. |
| `done` | `{"message": {…}}` | The reply is complete and saved. `message.text` is the whole reply as shown, including any text before a `tool_request`: replace what you built from the pieces with it. |
| `error` | `{"code","message"}` | The model failed after some text had gone out (`assistant.model_unavailable`); nothing was saved. Show the failure on the half-written reply and let the user ask again. |

- **Voice in:** `POST /v1/assistant/voice` with the raw audio as the body, `Content-Type` of the audio and `X-Duration-Seconds` (the clip's length in seconds; a decimal such as `5.5` is fine; up to 60 s, 3 MB) → `{ text }`; send that text through the chat route.
- **Live voice (hands-free):** open `wss://<host>/v1/assistant/voice/live` for the length of the conversation and close it when the user leaves the voice screen. Not when the language is Swahili: then record clips (Voice in) and speak replies with the phone's own text-to-speech.
  1. First frame within 10 s: `{ "type": "auth", "accessToken": "<app token>", "agentToken": "<agentSession.accessToken>", "language": "en", "chatReading": true }`. An agent token, a bad, expired or revoked token, or no auth frame closes the socket with 1008 `unauthorized` / `auth required` and no frame.
  2. The server answers `{ "type": "ready", "voiceSecondsLeft": 118, "audio": { "encoding": "mulaw", "sampleRate": 8000 } }`. Then stream the microphone as **binary** frames of Opus (16 kHz mono, one packet per frame, about 24 kbit/s), continuously, including while Asha speaks (that is how barge-in works). Every frame is at most 64 KB both ways; a bigger one closes the socket with 1009 `Frame is too big: N` (Ktor's reason, N the frame's size) and no frame.
  3. Server text frames (JSON, routed on `type`):

| `type` | Fields | Do |
| --- | --- | --- |
| `transcript` | `text`, `final` | Show the live caption; `final: false` may still change. |
| `turn` | `text` | The question Asha is answering (also saved in the Asha chat). |
| `reply` | `text` | One sentence of the answer, sent about when the audio reaches it (show it as a caption; the timing is approximate). Each sentence goes to the voice as soon as the model has written it, so the first is heard while the rest is still being written. The audio comes as **binary** frames: mulaw (G.711 µ-law), mono, at `audio.sampleRate` (8 kHz), as `audio.encoding` says. Queue the audio and play it in order. |
| `reply_done` | `message` (as in A1), `spoken` | The answer as saved, sent once its audio has finished playing; `spoken: false` means the user talked over it (show it, don't play it). |
| `tool_request` | `turnId`, `callId`, `name`, `arguments` | As on A2 (`arguments` is a JSON **string**): run the device tool, answer on this socket within 30 s with `{ "type": "tool_results", "turnId", "callId", "snippets": [...] }`. A frame that doesn't decode, or the wrong `callId`, gets `error` `request.malformed` and the turn keeps waiting: send it again. Later than that the turn ends with an `error` frame `assistant.turn_not_found`. |
| `interrupted` | — | The user talked over Asha: drop the audio queued and stop playing at once. While her audio plays it takes words, not just voice activity, and her own words heard back from the loudspeaker are ignored (so keep the mic open; the phone's echo canceller still matters for the words she says to be heard clearly). |
| `error` | `code`, `message` | That answer failed (`assistant.model_unavailable`, `assistant.turn_not_found`, or `request.malformed` for a bad `tool_results`); the conversation goes on. |
| `timing` | `turn`, `modelFirstTextMs`, `firstSentenceMs`, `firstAudioMs` | Once per turn: milliseconds from the end of the user's speech to Asha's first words from the model, her first sentence sent to the voice, and the first of her audio sent to the phone. A step the turn never reached (talked over, failed) is `null`. For latency logs and debug overlays; nothing to show the user, and safe to ignore. |
| `closing` | `reason`, `message`?, `retryAfterSeconds`? | The server is closing; `reason` is also the WebSocket close reason. |

  Ignore any text frame whose `type` you don't know: new frame types are added without a new version (`timing` was added this way).

  4. Access tokens last 15 minutes: before yours expires, refresh it over HTTP and send `{ "type": "auth", "accessToken": "<new app token>", "agentToken": "<new agent token>" }` on this socket. The server re-checks the session at each turn and every minute, and closes with 1008 `unauthorized` (no frame) once it is signed out, replaced or expired.
  5. To hang up send `{ "type": "end" }` (the server answers `closing` `ended`) or just close the socket. The server closes on its own after 30 s with nobody speaking (`silence`, 1000), when the voice allowance runs out (`assistant.quota_exceeded`, 1008, with `retryAfterSeconds`: show the Asha Plus offer), or when a speech provider fails (`assistant.model_unavailable`, 1011: fall back to clips). Voice seconds count only speech: yours, timed by the transcriber from your first word to your last, and Asha's audio as it plays (what you talk over is free); an open mic and silence are free. The agent token is checked at every turn and every `auth` frame: it must be this user's own, or the socket closes with 1008 `unauthorized` (on A2 and A9 the error is `auth.token_invalid`). Audio is never stored.
- **Voice out:** `POST /v1/assistant/speech` `{ "text", "language"? }` → audio bytes (not the envelope). English only; Swahili replies are spoken by the phone's own voice (asking the server gives `assistant.language_not_supported`).
- **Allowance:** `GET /v1/assistant/usage` → used, left and plan. Show what the server says; the free allowance is not decided yet, so hard-code nothing. `assistant.quota_exceeded` carries `retryAfterSeconds` for the reset.
- The **agent** token only works on `GET /v1/agent/...` routes and exists for Asha's own tools; the app never calls those directly.

## 10. Not built yet, and a suggested order

**Backend MISSING (do not call, do not invent endpoints; stub the UI behind a feature flag):**
1. Chat history and the history-key backup (chats, sending, the inbox, receipts and attachments exist).
2. Transaction history and statements (W6, W7), PIN change and restore (W8), the payment hold after a support restore, chamas. The wallet (W1–W3, W12) and payments (W4, W5, W9–W11, wallet frames and pushes) are in.
3. Calls: a call log message in the chat, CallKit VoIP pushes on iOS (PushKit), screen sharing. Calls themselves (CL1–CL8) are in.
4. Push for anything other than new messages, group adds, wallet payments and calls.
5. Notification inbox, identity verification (KYC).
6. Production device attestation (Play Integrity, App Attest) and real carrier phone verification (Firebase).
7. Chat history's handling of deleted accounts, and Asha reading your chats on the server (chat history slice).
8. Support tools that resolve account-recovery cases; restoring an account during its 30-day deletion window.
9. Realtime frames beyond those in section 8.

**Backend behaviours that will look odd on dev:** the carrier check accepts the number itself as the token; device attestation is only logged. Everything else is real: SMS, email and push really go out, so test with your own number, email and devices.

**Open product decisions that affect screens** (ask Pasaka before building UI for them): Asha's free allowance and paid plan; how a deleted account is restored within its 30 days (no route yet); what happens when a second Google or Apple login is linked (today it replaces the first); how the old phone cancels a number release; whether short Asha replies are cached.

**Suggested build order**
1. Project skeleton: Ktor client, envelope, headers interceptor, base-URL setting, `X-Client-Version`, force-update screen.
2. `DeviceKeyStore` on both platforms, with a unit test that signs a payload and verifies it (the section 5 formats are where most bugs hide).
3. Sign in with Google (flows A and D), then Apple on iOS.
4. Phone verification (flow B), device registration (flow C), refresh and sign-out (E, F). At this point a person can sign up and stay signed in.
5. Profile, @handle, avatar (G).
6. Contacts sync, contacts list, connections, invites, blocks, reach (H).
7. Realtime socket (section 8).
8. Asha chat with streaming and tool requests, then voice (section 9).
9. Account security screens, linked logins, recovery request.
10. Push: register the FCM token (I9) and handle the data-only message push (section 8).
11. Encrypted chats: keys (M1–M3), chats and groups (M4–M11), sending, inbox and receipts (M12–M14, section 8), then attachments (AT1–AT3).

**Definition of done for each screen:** every error `code` in the catalogue's *Errors* column has a message in the app; every `POST`/`PUT`/`PATCH`/`DELETE` sends an `Idempotency-Key`; no token, code, key or phone number is ever logged; retries reuse the same `Idempotency-Key`.


## 11. Schema appendix

Generated from the spec. `?` marks an optional field. Every response is wrapped in the envelope from section 3, and the type named in the catalogue is what sits in `data`.

- **AckRequest** — `messageIds`: [string] (1 to 100)
- **AckResponse** — `acked`: integer
- **ActiveDeviceDto** — `model`: string; `activeSince`: string
- **AddChainRequest** — `blockchain`: string
- **AddChainResponse** — `blockchain`: string; `status`: string (`ADDING` | `ENABLED`); `challengeId`?: string|null; `sdk`?: SdkDto|null
- **AddMembersRequest** — `memberIds`: [string]
- **AddOneTimeKeysRequest** — `oneTimePreKeys`: [OneTimePreKeyDto]
- **AgentContactDto** — `userId`: string; `displayName`: string; `handle`?: string|null
- **AgentContactsResponse** — `contacts`: [AgentContactDto]; `nextCursor`?: string|null
- **AmountDto** — `asset`: string (`USDC`); `amountMinor`: string; `amount`: string
- **AmountRequest** — `asset`: string (`USDC`); `amountMinor`?: string (digits); `amount`?: string (whole units, at most the asset's decimals); at least one, both only if they agree
- **AppleNameDto** — `given`?: string|null; `family`?: string|null
- **AskedFromDto** — `chatId`: string; `chatName`?: string|null
- **AssetDto** — `symbol`: string; `tokenAddress`: string (empty for a chain's own coin); `decimals`: integer; `amountMinor`: string; `amount`: string; `fiatAmount`?: string|null
- **AssetTotalDto** — `symbol`: string; `decimals`: integer; `amountMinor`: string; `amount`: string
- **AttachmentCompletedResponse** — `attachmentId`: string; `sizeBytes`: integer
- **AttachmentDownloadResponse** — `downloadUrl`: string; `expiresAt`: string; `sizeBytes`: integer
- **AttachmentUploadResponse** — `attachmentId`: string; `uploadUrl`: string; `uploadHeaders`: UploadHeadersDto; `expiresAt`: string
- **BlocksResponse** — `blocks`: [PublicProfileDto2]
- **CarrierRequest** — `carrierToken`: string
- **ChainDto** — `blockchain`: string; `address`: string; `state`: string (`ACTIVE` | `FROZEN`); `assets`: [AssetDto]; `receiveEnabled`: boolean
- **ChainOptionDto** — `blockchain`: string; `assets`: [string]; `status`: string (`ENABLED` | `ADDING` | `AVAILABLE`); `receiveEnabled`: boolean
- **ChainsResponse** — `defaultChain`: string; `chains`: [ChainOptionDto]
- **ChatDto** — `chatId`: string; `kind`: string (`DIRECT` | `GROUP`); `sealedMeta`?: string|null; `metaVersion`: integer; `members`: [ChatMemberDto]; `settings`: ChatSettingsDto; `lastMessageId`?: string|null; `version`: integer; `created`?: boolean|null (M4 only); `notAdded`?: [string]|null (M5, M9 only)
- **ChatMemberDto** — `user`: ChatProfileDto; `role`: string (`OWNER` | `ADMIN` | `MEMBER`); `joinedAt`: string
- **ChatMessageDto** — `messageId`: string; `role`: string; `text`: string; `replyTo`?: string|null; `askedFrom`?: string|null; `readNote`?: ReadNoteDto; `at`: string
- **ChatProfileDto** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null
- **ChatResponse** — `messages`: [ChatMessageDto]; `nextCursor`?: string|null
- **ChatSettingsDto** — `membersCanAdd`?: boolean
- **ChatsResponse** — `chats`: [ChatDto]
- **ClaimHandleRequest** — `handle`: string
- **ConnectRequest** — `via`: string; `value`: string
- **ConnectResponse** — `user`: PublicProfileDto2; `created`: boolean
- **ContactDto** — `user`: PublicProfileDto2; `source`: string; `addedAt`: string
- **ContactsResponse** — `contacts`: [ContactDto]; `nextCursor`?: string|null
- **CreateGroupRequest** — `sealedMeta`: string; `memberIds`?: [string]
- **CreateWalletResponse** — `status`: string (`SETTING_UP`); `challengeId`?: string|null; `sdk`: SdkDto
- **DeviceDto** — `deviceId`: string; `platform`: string; `model`: string; `osVersion`?: string|null; `appVersion`: string
- **DeviceProofDto** — `timestamp`: string; `signature`: string
- **ErrorDetailsDto** — `retryAfterSeconds`?: integer|null; `fields`?: [FieldErrorDto]
- **FiatDto** — `currency`: string; `amount`: string; `rate`: string; `asOf`: string; `partial`: boolean
- **FieldErrorDto** — `field`: string; `problem`: string
- **HandleAvailabilityResponse** — `handle`: string; `available`: boolean; `status`: string; `suggestion`?: string|null
- **HealthCheckStatus** — `name`: string; `status`: string
- **HealthReport** — `status`: string; `checks`?: [HealthCheckStatus]
- **HoldDto** — `until`: string
- **InboxMessageDto** — `messageId`: string; `chatId`: string; `senderId`: string; `kind`: string (`MESSAGE` | `SENDER_KEY` | `CONTROL`); `ciphertext`: string (base64); `mediaIds`: [string]; `sentAt`: string
- **InboxResponse** — `messages`: [InboxMessageDto]; `next`?: string|null
- **InviteResponse** — `token`: string; `url`: string; `qrPayload`: string; `expiresAt`: string
- **JwkDto** — `kty`?: string; `crv`?: string; `use`?: string; `alg`?: string; `kid`: string; `x`: string
- **JwksResponse** — `keys`: [JwkDto]
- **KeyBundleResponse** — `userId`: string; `identityKey`: string; `signedPreKey`: SignedPreKeyDto; `kyberPreKey`: KyberPreKeyDto; `oneTimePreKey`?: OneTimePreKeyDto|null
- **KeyStatusResponse** — `oneTimePreKeysLeft`: integer; `signedPreKeyAt`: string
- **KyberPreKeyDto** — `keyId`: integer; `publicKey`: string (ML-KEM 1024, up to 2,048 bytes decoded); `signature`: string
- **LinkProviderRequest** — `provider`: string; `idToken`: string; `nonce`: string
- **MemberRemovedResponse** — `removed`: boolean
- **MessageDeletedResponse** — `deleted`: boolean
- **MessagingSettingsRequest** — `readReceipts`?: boolean|null; `typing`?: boolean|null
- **MessagingSettingsResponse** — `readReceipts`: boolean; `typing`: boolean
- **MyProfileResponse** — `userId`: string; `displayName`: string; `about`?: string|null; `handle`?: string|null; `handleChangeableAt`?: string|null; `avatarUrl`?: string|null; `privacy`: PrivacyDto; `version`: integer
- **NoBody** — `unused`?: string|null
- **NonceResponse** — `nonce`: string; `expiresInSeconds`: integer
- **OneTimePreKeyDto** — `keyId`: integer; `publicKey`: string
- **OneTimePreKeysLeftResponse** — `oneTimePreKeysLeft`: integer
- **OpenDirectChatRequest** — `userId`: string
- **PayeeDto** — `userId`?: string|null; `address`: string
- **PayeeRequest** — `userId`?: string; `address`?: string (exactly one)
- **PhoneVerifiedResponse** — `phoneVerified`: boolean; `maskedPhone`: string; `next`: string
- **PrepareAttachmentRequest** — `sizeBytes`: integer (1 to 33554432); `kind`: string (`image` | `voice` | `video` | `file`)
- **PrepareTransferRequest** — `blockchain`: string; `to`: PayeeRequest; `amount`: AmountRequest; `chatId`?: string; `proposalId`?: string (refused until Asha's proposals exist)
- **PrepareTransferResponse** — `transferId`: string; `state`: string; `blockchain`: string; `amount`: AmountDto; `challengeId`?: string|null; `sdk`?: SdkDto|null; `fee`: AmountDto; `expiresAt`: string
- **PrivacyChangeDto** — `avatar`?: string|null; `about`?: string|null; `handleLookup`?: boolean|null
- **PrivacyDto** — `avatar`: string; `about`: string; `handleLookup`: boolean
- **ProviderDto** — `provider`: string; `maskedEmail`?: string|null; `linkedAt`: string
- **PublicProfileDto** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null
- **PublicProfileDto2** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null
- **PublishKeysRequest** — `identityKey`: string; `signedPreKey`: SignedPreKeyDto; `kyberPreKey`: KyberPreKeyDto; `oneTimePreKeys`?: [OneTimePreKeyDto]
- **PushTokenRequest** — `pushToken`: string
- **ReachResponse** — `reachable`: boolean
- **ReadNoteDto** — `chatIds`: [string]; `chatNames`?: [string]; `messageCount`: integer
- **RecipientDto** — `userId`: string; `ciphertext`: string (base64)
- **RecipientResponse** — `userId`: string; `status`: string (`CAN_RECEIVE` | `NO_WALLET` | `NO_WALLET_ON_CHAIN`); `canReceive`: boolean; `blockchains`: [string]
- **RecoveryRequest** — `phone`: string; `contactEmail`: string; `note`?: string|null
- **RecoveryResponse** — `requestId`: string; `status`: string; `noticeSentAt`?: string|null; `relinkAfter`?: string|null
- **RefreshRequest** — `refreshToken`: string; `deviceId`: string; `timestamp`: string; `signature`: string
- **RefreshResponse** — `session`: SessionDto; `agentSession`: SessionDto
- **RegisterDeviceRequest** — `deviceId`: string; `publicKey`: string; `keyAlgorithm`: string; `pushToken`?: string|null; `attestation`?: string|null; `model`?: string|null
- **RegisterDeviceResponse** — `user`: UserStateDto; `session`: SessionDto; `agentSession`: SessionDto; `replacedDevice`?: ReplacedDeviceDto
- **ReleaseRequest** — `verificationId`: string; `confirmAbandon`: boolean
- **ReleaseResponse** — `releaseAt`: string
- **RemindResponse** — `status`: string (`SENT` | `ALREADY_SENT` | `HAS_WALLET`)
- **RemovedResponse** — `removed`: boolean
- **ReplacedDeviceDto** — `model`: string; `lastSeenAt`: string
- **SdkDto** — `appId`: string; `userToken`: string; `encryptionKey`: string; `expiresAt`: string
- **SecurityEventDto** — `eventId`: string; `type`: string; `deviceModel`?: string|null; `detail`?: string|null; `at`: string
- **SecurityLogResponse** — `events`: [SecurityEventDto]; `nextCursor`?: string|null
- **SecurityOverviewResponse** — `maskedPhone`?: string|null; `providers`: [ProviderDto]; `device`?: ActiveDeviceDto; `verification`: string
- **SendChatMessageRequest** — `clientMessageId`: string; `kind`: string (`MESSAGE` | `SENDER_KEY` | `CONTROL`); `ciphertext`?: string|null (a group's one ciphertext); `recipients`?: [RecipientDto] (direct, and `SENDER_KEY`); `mediaIds`?: [string]; `pushable`?: boolean (default false)
- **SendMessageRequest** — `text`: string; `askedFrom`?: AskedFromDto; `chatReading`?: boolean
- **SentMessageResponse** — `messageId`: string; `sentAt`: string
- **SessionDto** — `accessToken`: string; `refreshToken`?: string|null; `expiresInSeconds`: integer; `scope`: string
- **SessionDto2** — `accessToken`: string; `refreshToken`?: string|null; `expiresInSeconds`: integer; `scope`: string
- **SetAvatarRequest** — `objectKey`: string
- **SetRoleRequest** — `role`: string (`OWNER` | `ADMIN` | `MEMBER`)
- **SignInResponse** — `next`: string; `maskedPhone`?: string|null; `session`: SessionDto; `agentSession`?: SessionDto2
- **SignedPreKeyDto** — `keyId`: integer; `publicKey`: string; `signature`: string
- **SnippetDto** — `chatId`: string; `chatName`?: string|null; `sender`: string; `at`: string; `text`: string
- **SocialSignInRequest** — `provider`: string; `idToken`: string; `nonce`: string; `appleName`?: AppleNameDto; `device`: DeviceDto; `deviceProof`?: DeviceProofDto
- **SpeechRequest** — `text`: string; `language`?: string|null
- **StartPhoneRequest** — `phone`: string
- **StartPhoneResponse** — `verificationId`: string; `codeLength`: integer; `expiresInSeconds`: integer; `resendAfterSeconds`: integer
- **SyncMatchDto** — `phone`: string; `user`: PublicProfileDto2
- **SyncRequest** — `syncId`: string; `batch`?: integer; `final`?: boolean; `phones`: [string]
- **SyncResponse** — `matches`: [SyncMatchDto]
- **ToolResultsRequest** — `callId`: string; `snippets`?: [SnippetDto]
- **TranscriptResponse** — `text`: string; `seconds`: integer
- **TransferResponse** — `transferId`: string; `direction`: string (`OUT` | `IN`); `state`: string (`AWAITING_SIGNATURE` | `SUBMITTED` | `CONFIRMED` | `FAILED` | `EXPIRED`); `blockchain`: string; `from`: string; `to`: PayeeDto; `amount`: AmountDto; `fee`: AmountDto; `chatId`?: string|null; `origin`?: string|null (`APP` | `ASHA`; sender only); `proposalId`?: string|null (sender only); `txHash`?: string|null; `failureReason`?: string|null; `createdAt`: string; `updatedAt`: string; `expiresAt`: string
- **UpdateGroupRequest** — `sealedMeta`?: string|null; `settings`?: ChatSettingsDto|null; `version`: integer
- **UpdateProfileRequest** — `displayName`?: string|null; `about`?: string|null; `privacy`?: PrivacyChangeDto; `version`: integer
- **UploadHeadersDto** — `Content-Type`: string (always `application/octet-stream`)
- **UploadUrlRequest** — `contentType`: string; `sizeBytes`: integer
- **UploadUrlResponse** — `uploadUrl`: string; `objectKey`: string; `expiresInSeconds`: integer
- **UsageResponse** — `plan`: string; `textTurnsUsed`: integer; `textTurnsLeft`: integer; `voiceSecondsUsed`: integer; `voiceSecondsLeft`: integer; `resetsAt`: string
- **UserStateDto** — `userId`: string; `status`: string
- **ValidateAddressRequest** — `blockchain`: string; `address`: string
- **ValidateAddressResponse** — `blockchain`: string; `address`: string; `valid`: boolean
- **VerifyCodeRequest** — `verificationId`: string; `code`: string
- **WalletResponse** — `status`: string (`NONE` | `SETTING_UP` | `ACTIVE`); `chains`: [ChainDto]; `totals`: [AssetTotalDto]; `fiat`?: FiatDto|null; `hold`?: HoldDto|null
