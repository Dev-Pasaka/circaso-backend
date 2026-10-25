# Circaso mobile integration guide

For **Claude Code working in the Circaso mobile app** (Kotlin Multiplatform: one shared module, Android and iOS targets). It says how to connect the app to the backend, route by route, **what is finished on the backend, and what is not**. Written 2026-10-25 against the backend at `github.com/Dev-Pasaka/circaso-backend` (dev server `https://sandbox.circaso.com`).

## 1. How to use this file

- **The API reference is the source of truth for exact shapes**: `/docs` on the dev server (behind Cloudflare Access; ask Pasaka for the `openapi.json` file if you cannot open it). This file explains behaviour and order; section 11 repeats every request and response type so you can start offline.
- **Build against the real server, not mocks.** `https://sandbox.circaso.com` is a deployed dev server with real Google sign-in and a real database. Providers for push, email, speech and the AI model are stubs there (marked **DEV STUB** below); the HTTP contracts are final.
- **Status words used below:** **READY** (backend finished, safe to build on), **DEV STUB** (the HTTP contract is final but a vendor behind it is faked on dev), **PARTIAL** (works, with a named gap), **MISSING** (not built in the backend; do not invent an endpoint).
- If the backend says something different from this file, the backend wins: tell Pasaka and fix the file.

## 2. Status at a glance

Of the **50** routes the phone calls: **37 READY**, **8 PARTIAL**, **5 DEV STUB**.

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
| Realtime socket | **READY**, few frames | Only 4 frame types exist (section 8). |
| Asha chat (text), voice, speech | **DEV STUB** | Protocol final; the model, speech-to-text and text-to-speech are stubs on dev. |
| Push notifications | **MISSING** | The token is stored; nothing is ever sent. |
| Account security, linked logins, security log | **READY** | |
| Account deletion | **PARTIAL** | Schedules only; completion and recovery window not decided. |
| Account recovery | **PARTIAL** | A case can be opened and read; no support tool resolves it yet. |
| Encryption keys (publish, top up, status, fetch a bundle) | **READY** | Uploads are signed with the device key over a SHA-256 of the body (section 5). The shared-group way to fetch a bundle comes with chats. |
| Messaging between people (1:1 and group chat), media | **MISSING** | Designed (end-to-end encrypted); being built in slices. Only the key directory exists so far. |
| Payments, wallet, money movement, chamas | **MISSING** | Not designed yet; `identity.wallet_not_empty` is a placeholder. |
| Calls | **MISSING** | Not designed yet. |
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
On failure `status` is `false`, `data` is `null`, and `code` is a stable string such as `identity.code_invalid`. **Key your UI text on `code`, never on `message`.** `details.retryAfterSeconds` (also the `Retry-After` header) and `details.fields` (field → what is wrong) may be set. Log `requestId` and show it on error screens; Pasaka can find the exact trace from it.

**What the app does with the common failures**

| Answer | Do |
| --- | --- |
| `401` `auth.token_invalid` / `auth.token_missing` | Refresh once (section 5), retry the call once. If refresh fails, sign out locally. |
| `401` `identity.session_revoked` | The session ended (signed out elsewhere, replaced by a new phone). Clear tokens, show sign-in. |
| `403` `auth.scope_insufficient` | A bug in the app: the wrong token for this route. |
| `426` `request.client_outdated` | Force-update screen. |
| `429` `request.rate_limited` | Wait `Retry-After` seconds; show the wait, do not loop. |
| `409` `request.in_progress` | The same `Idempotency-Key` is still running: wait a moment and retry **with the same key**. |
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

Each phone holds one **hardware-backed ECDSA P-256 key**. Its public half is registered once (`POST /v1/devices`); the private half never leaves the phone. It signs three things: the **refresh** request, the **same-phone sign-in** proof, and **encryption-key uploads**. A wrong format fails with `identity.device_proof_invalid` and no hint, so follow this literally.

- **Algorithm:** `ES256` (P-256, SHA-256). Send `keyAlgorithm: "ES256"`.
- **`publicKey`:** standard Base64 (with padding) of the **DER `SubjectPublicKeyInfo`** (the X.509 public key encoding).
  - Android: `keyStore.getCertificate(alias).publicKey.encoded` is already SPKI.
  - **iOS gotcha:** `SecKeyCopyExternalRepresentation` returns only the raw 65-byte point `04‖X‖Y`. Prepend this fixed 26-byte header, then Base64 the 91 bytes: `3059301306072A8648CE3D020106082A8648CE3D030107034200` (hex).
- **Signatures:** standard Base64 of the **DER-encoded ECDSA signature** (an ASN.1 sequence of r and s), over the UTF-8 bytes of the payload below.
  - Android `Signature.getInstance("SHA256withECDSA")` already produces DER.
  - iOS: `SecKeyCreateSignature` with `.ecdsaSignatureMessageX962SHA256` produces DER.
  - If a library hands you raw 64-byte `r‖s`, convert it to DER first.
- **Payloads (concatenate exactly, no spaces or newlines):**
  - Same-phone sign-in (`deviceProof` on `POST /v1/auth/social`): `nonce + timestamp`.
  - Refresh (`POST /v1/auth/refresh`): `refreshToken + "|" + timestamp`.
  - Key uploads (`deviceProof` on `PUT /v1/keys` and `POST /v1/keys/one-time`): the **32-byte SHA-256 of the request body without `deviceProof`**. Build the body without `deviceProof` as **compact JSON** (no spaces or newlines), fields in the order you will send them, strings escaped only where JSON requires it (**no `\/`**: on iOS use `JSONEncoder.OutputFormatting.withoutEscapingSlashes`, since base64 keys contain `/`). Hash those UTF-8 bytes, sign the 32-byte digest with `SHA256withECDSA` / `.ecdsaSignatureMessageX962SHA256` as above (the signer hashes it again, and so does the server), then add `"deviceProof":{"timestamp":"…","signature":"…"}` to the object and send it. The timestamp is not part of the signed bytes but must still be within 5 minutes. Failure: `messaging.keys_proof_invalid` (403).
- **`timestamp`:** an ISO-8601 UTC string, for example `2026-10-24T18:58:25Z`. **Send exactly the string you signed.** The server rejects a proof more than **5 minutes** away from its own clock, so a phone with a wrong clock cannot sign in: show a "check your date and time" message for `identity.device_proof_invalid`.
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
| `POST /v1/auth/signout`<br>**I8** End this session; the phone stays registered | `app` | — → NoBody | **READY** | The phone stays registered. |
| `GET /v1/.well-known/jwks.json`<br>Public keys the access tokens are signed with | `none` | — → JwksResponse | **READY** | The app does not need it; the server verifies its own tokens. |

### Phone verification

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/auth/phone/start`<br>**I3** Send a 6-digit SMS code | `onboarding` | StartPhoneRequest → StartPhoneResponse | **PARTIAL** | 3 codes per number per 10 minutes, 10 per phone per day. Built on Twilio Verify. A real SMS send has not been confirmed end to end yet (the first attempts failed provider-side), so test with a real number and report the `code` you get. Errors: `identity.phone_mismatch`, `identity.code_locked`, `identity.sms_unavailable`, `request.rate_limited`. |
| `POST /v1/auth/phone/verify`<br>**I4** Check the SMS code; claims the number on sign-up | `onboarding` | VerifyCodeRequest → PhoneVerifiedResponse | **PARTIAL** | Five wrong codes lock the account for 10 minutes. Same as above: `code` is checked by Twilio on the dev server. Errors: `identity.code_invalid`, `identity.code_expired`, `identity.code_locked`, `identity.phone_taken`, `identity.phone_mismatch`. |
| `POST /v1/auth/phone/carrier`<br>**I5** Same as I4 from a carrier token, with no SMS | `onboarding` | CarrierRequest → PhoneVerifiedResponse | **DEV STUB** | Android-only one-tap check. On the dev server the provider is `log`: the `carrierToken` is simply the phone number in E.164. The real Firebase phone-number verification is not built, so keep SMS as the fallback. Errors: `identity.carrier_unsupported`, `identity.phone_taken`, `identity.phone_mismatch`. |
| `POST /v1/auth/phone/release`<br>**I16** Free a number held by a lost, unverified account after a 7-day notice | `onboarding` | ReleaseRequest → ReleaseResponse | **READY** | Needs a verification that proved the SIM (I3 and I4). Rare path. There is no route yet for the old phone to cancel a release. Errors: `identity.release_not_allowed`, `identity.code_expired`. |

### Register device

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/devices`<br>**I6** Register this phone's key; activates the account on sign-up and issues the app session | `onboarding` | RegisterDeviceRequest → RegisterDeviceResponse | **PARTIAL** | Send `X-Client-Version` (`android/1.0.0`): it carries the platform and app version. Works on dev (attestation is only logged). In production no device can register until Play Integrity / App Attest verification is built; the format of `attestation` is not decided yet, so send the platform token if you have one and expect it to be ignored for now. Errors: `identity.device_attestation_failed`, `request.malformed`. |

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
| `GET /v1/handles/{handle}/availability`<br>**U5** Whether a @handle is free, taken or invalid, with a suggestion | `app` | — → HandleAvailabilityResponse | **READY** | 30 per hour. |
| `PUT /v1/me/handle`<br>**U6** Claim or change your @handle, once every 30 days | `app` | ClaimHandleRequest → MyProfileResponse | **READY** | Once every 30 days. Errors: `users.handle_invalid`, `users.handle_taken`, `users.handle_cooldown`. |
| `GET /v1/users/by-handle/{handle}`<br>**U9** Exact-match @handle lookup | `app` | — → PublicProfileDto | **READY** | 30 lookups per user per hour. Exact match; an owner who turned lookup off looks like not found. Errors: `users.handle_not_found`, `request.rate_limited`. |

### Contacts

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/contacts/sync`<br>**U7** Upload a batch of phone-book numbers (up to 1,000) and get back the ones on Circaso | `app` | SyncRequest → SyncResponse | **READY** | 5 syncs per user per hour. Names from the phone book never leave the phone. Up to 1,000 numbers per batch, 5 syncs per hour. Send E.164 numbers only; names never leave the phone. Errors: `users.sync_too_large`, `request.rate_limited`. |
| `GET /v1/contacts`<br>**U8** Your contacts with public profiles | `app` | — → ContactsResponse | **READY** | Query: `cursor`, `limit` (default 50, at most 200). Paged with `cursor` and `limit`. |
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
| `PUT /v1/blocks/{userId}`<br>**U14** Block someone; a block overrides every other way of reaching a person | `app` | — → NoBody | **READY** | Errors: `users.cannot_block_self`. |
| `DELETE /v1/blocks/{userId}`<br>**U14** Lift a block | `app` | — → NoBody | **READY** |  |
| `GET /v1/me/reach/{userId}`<br>**U15** Whether you can reach that person | `app` | — → ReachResponse | **READY** | Never says why not. |

### Encryption keys

Chats are end-to-end encrypted on the phone with libsignal (X3DH, double ratchet, sender keys); the server only stores public keys and ciphertext. Keys are standard base64.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `PUT /v1/keys`<br>**M1** Publish this phone's identity key, signed prekey and up to 100 one-time prekeys | `app` | PublishKeysRequest → OneTimePreKeysLeftResponse | **READY** | Device-signed (section 5, key uploads). Replaces the whole set: one-time prekeys from an earlier upload are deleted, so send fresh ones with it. Publish after registering the device, and again every 7 days to rotate the signed prekey. Errors: `messaging.keys_proof_invalid`, `request.malformed`. |
| `POST /v1/keys/one-time`<br>**M1** Top up one-time prekeys | `app` | AddOneTimeKeysRequest → OneTimePreKeysLeftResponse | **READY** | Device-signed. Top up when `GET /v1/keys/me` shows fewer than 20. At most 100 are kept at once; an upload that would pass that is refused whole with `request.malformed`. Keys with an id already stored replace it. Errors: `messaging.keys_proof_invalid`, `messaging.keys_not_found` (this phone has not published with `PUT /v1/keys`), `request.malformed`. |
| `GET /v1/keys/me`<br>**M2** One-time prekeys left and when the signed prekey was set | `app` | — → KeyStatusResponse | **READY** | `messaging.keys_not_found` until this phone has published its own keys (also after a new phone, whose keys must be published again). |
| `GET /v1/keys/{userId}`<br>**M3** A person's key bundle, to start an encrypted session | `app` | — → KeyBundleResponse | **READY** | Uses up one of their one-time prekeys; `oneTimePreKey` is missing or null when none are left, which X3DH allows. You must be able to reach the person (the shared-group way comes with group chats). 60 bundles per user per hour. Errors: `users.not_reachable`, `messaging.keys_not_found`, `messaging.rate_limited`. |

### Asha chat

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/assistant/chat/messages`<br>**A2** Ask Asha; the reply streams as server-sent events | `app` | SendMessageRequest → NoBody | **DEV STUB** | (the user token). Send the user's agent token in `X-Agent-Token` so Asha's tools can read contacts. The response is `text/event-stream`, not the envelope (errors before any text are the envelope): `message` events carry `{delta}`; a `tool_request` event `{turnId, callId, name, arguments}` asks the phone to run a device tool and answer with A9; `done` carries the saved reply. The streaming protocol is final (SSE events `message`, `tool_request`, `done`), but the model is a stub on the dev server, so replies are canned. Build the client against the protocol. Errors: `assistant.quota_exceeded`, `assistant.model_unavailable`, `request.malformed`. |
| `DELETE /v1/assistant/chat`<br>**A5** Delete the whole history with Asha | `app` | — → NoBody | **READY** | (the user token). |
| `GET /v1/assistant/chat`<br>**A1** The Asha chat, newest first | `app` | — → ChatResponse | **READY** | (the user token). Query: `cursor`, `limit` (default 50, at most 100). |
| `DELETE /v1/assistant/chat/messages/{messageId}`<br>**A6** Delete one exchange | `app` | — → NoBody | **READY** | (the user token). |
| `POST /v1/assistant/chat/messages/{messageId}/tool-results`<br>**A9** The phone's answer to a device tool; the reply then continues as server-sent events | `app` | ToolResultsRequest → NoBody | **DEV STUB** | (the user token). `messageId` is the `turnId` from the `tool_request` event. At most 30 short snippets; they are used for this turn and never stored. Answer a `tool_request` within 10 minutes. The real model that asks for tools is not connected yet. Errors: `assistant.turn_not_found`. |

### Asha voice and usage

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/assistant/voice`<br>**A3** Transcribe a voice clip | `app` | — → TranscriptResponse | **DEV STUB** | (the user token). The clip is the raw body (up to 60 s, under 3 MB); send `Content-Type` of the audio and `X-Duration-Seconds`. Raw audio body, up to 60 s and 3 MB, with `Content-Type` and `X-Duration-Seconds`. Speech-to-text is a stub on dev. Errors: `assistant.voice_too_long`, `assistant.quota_exceeded`. |
| `POST /v1/assistant/speech`<br>**A4** Turn a reply into audio | `app` | SpeechRequest → NoBody | **DEV STUB** | (the user token). The response is the audio itself; an error is the envelope. Returns audio, not the envelope. Text-to-speech is a stub on dev. Errors: `assistant.quota_exceeded`. |
| `GET /v1/assistant/usage`<br>**A7** Today's allowance used and left, and the plan | `app` | — → UsageResponse | **PARTIAL** | (the user token). Works; the free allowance numbers and the paid plan are not decided yet, so show what the server returns and nothing hard-coded. |

### Account and security

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `PUT /v1/devices/current/push-token`<br>**I9** Store this phone's FCM or APNs token | `app` | PushTokenRequest → NoBody | **PARTIAL** | The token is stored, but no push is ever sent yet: the FCM/APNs sender is not built. |
| `DELETE /v1/devices/current`<br>**I10** Forget this phone; the next sign-in verifies the number again | `app` | — → NoBody | **READY** | Forget this phone; the next sign-in verifies the number again. |
| `GET /v1/account/security`<br>**I11** Phone, linked sign-in accounts, this phone and verification status | `app` | — → SecurityOverviewResponse | **READY** |  |
| `POST /v1/account/providers`<br>**I12** Link a Google or Apple login | `app` | LinkProviderRequest → NoBody | **READY** | Link Google or Apple. Errors: `identity.provider_in_use`, `identity.nonce_invalid`. |
| `DELETE /v1/account/providers/{provider}`<br>**I12** Unlink a login, only if the other one stays | `app` | — → NoBody | **READY** | Only while the other login stays. Errors: `identity.provider_last`. |
| `GET /v1/account/security-log`<br>**I13** Security activity, newest first | `app` | — → SecurityLogResponse | **READY** | Query: `cursor`, `limit` (default 50, at most 200). Paged. |
| `DELETE /v1/account`<br>**I14** Schedule account deletion once every guard agrees | `app` | — → NoBody | **PARTIAL** | Schedules deletion. What finishes it, and how long it stays recoverable, is not decided; revoking Apple access at deletion is not built. The wallet-empty guard is a placeholder (there is no wallet yet). Errors: `identity.wallet_not_empty`. |

### Account recovery

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/recovery/requests`<br>**I15** Open a support case for a lost Google or Apple login | `onboarding` | RecoveryRequest → RecoveryResponse | **PARTIAL** | Opens a support case. The support-side tools to resolve it are not built, so nothing completes it yet. Errors: `identity.recovery_not_eligible`, `request.rate_limited`. |
| `GET /v1/recovery/requests/{requestId}`<br>**I17** Status of your own support case | `onboarding` | — → RecoveryResponse | **PARTIAL** | Status of your own case. |

## 8. Realtime socket

While the app is open, keep one WebSocket to `wss://<host>/v1/ws` (Ktor `WebSockets` plugin; the server pings every 25 seconds). The **first frame** must authenticate within a few seconds or the server closes it:
```json
{ "type": "auth", "accessToken": "<app token>", "last": { "users": 1042 } }
```
`last` maps each module to the last `seq` the app saw (omit or send `{}` on first connect). Only an **app**-scope token is accepted. The server then replays what was missed and sends live frames:
```json
{ "module": "users", "type": "contact.joined", "id": "0193…", "seq": 1043, "ts": "2026-10-24T18:07:12Z", "data": { "userId": "…", "displayName": "Amina" } }
```
`seq` rises per user per module: a gap means a missed frame. A `resync` frame means the gap is older than 7 days: reload that module over HTTP. Reconnect with backoff and send your `last` values again. Refresh the access token **before** reconnecting if it has expired.

**Frames that exist today (everything else is MISSING):**

| `module` / `type` | `data` | Do |
| --- | --- | --- |
| `system` / `session.revoked` | empty | The session ended. Clear tokens, show sign-in. (The socket then closes.) |
| `users` / `contact.joined` | `userId`, `displayName` | Someone from your phone book joined: add them to the contacts list. |
| `users` / `connection.added` | `userId`, `displayName` | Someone connected with you (QR or invite). |
| `users` / `profile.updated` | `userId`, `version` | A contact changed their profile: reload that profile if `version` is newer than yours. |

**Frames the phone sends** (after the auth frame only; commands stay on HTTP): `{ "module": "messaging", "type": "ack", "data": { … } }`. The server hands each to the module it names and never answers it directly; a frame for a module that takes none is dropped. **MISSING** until messaging delivery is built: no module takes phone frames yet (messaging's `ack`, `read` and `typing` come with sending and delivery).

## 9. Asha (the assistant)

**Status: DEV STUB.** The routes and event format below are final; on the dev server the language model, speech-to-text and text-to-speech are stubs, so replies are canned. Build the full client now.

- **History:** `GET /v1/assistant/chat` (newest first, paged); delete one exchange or everything.
- **Ask:** `POST /v1/assistant/chat/messages` `{ "text", "chatReading": true|false, "askedFrom"? }`. Send `X-Agent-Token: <agentSession.accessToken>` so her tools can read your contacts. The answer is **`text/event-stream`**, not the envelope. Read the response body as a stream and parse `event:` and `data:` lines yourself (works on every Ktor engine). Errors before any text (a used-up allowance, a bad request) arrive as the normal envelope with a non-200 status.

| Event | `data` | Do |
| --- | --- | --- |
| `message` | `{"delta": "…"}` | Append the piece to the reply on screen. |
| `tool_request` | `{"turnId","callId","name","arguments"}` | Asha needs something only the phone can do (search or read chats, which stay end-to-end encrypted). Run it locally, then `POST /v1/assistant/chat/messages/{turnId}/tool-results` `{ "callId", "snippets": [...] }` (at most 30 short snippets; answer within 10 minutes or get `assistant.turn_not_found`). The reply then continues on the **same** event stream. |
| `done` | `{"message": {…}}` | The reply is complete and saved. |

- **Voice in:** `POST /v1/assistant/voice` with the raw audio as the body, `Content-Type` of the audio and `X-Duration-Seconds` (up to 60 s, 3 MB) → `{ text }`; send that text through the chat route.
- **Voice out:** `POST /v1/assistant/speech` `{ "text", "language"? }` → audio bytes (not the envelope).
- **Allowance:** `GET /v1/assistant/usage` → used, left and plan. Show what the server says; the free allowance is not decided yet, so hard-code nothing. `assistant.quota_exceeded` carries `retryAfterSeconds` for the reset.
- The **agent** token only works on `GET /v1/agent/...` routes and exists for Asha's own tools; the app never calls those directly.

## 10. Not built yet, and a suggested order

**Backend MISSING (do not call, do not invent endpoints; stub the UI behind a feature flag):**
1. Messaging between people (1:1 and group), media, delivery and read state. **Not designed yet.**
2. Payments, wallet, sending and receiving money, chamas. Not designed yet.
3. Calls. Not designed yet.
4. Push delivery (FCM/APNs sender). The token endpoint exists, nothing is sent.
5. Notification inbox, identity verification (KYC).
6. Production device attestation (Play Integrity, App Attest) and real carrier phone verification (Firebase).
7. Real Asha model, speech-to-text and text-to-speech (stubs on dev).
8. Support tools that resolve account-recovery cases; finishing account deletion.
9. Realtime frames beyond the four in section 8.

**Backend behaviours that will look odd on dev:** email (welcome and security notices) is logged, not sent; push is logged; Asha's answers are canned; the carrier check accepts the number itself as the token.

**Open product decisions that affect screens** (ask Pasaka before building UI for them): Asha's free allowance and paid plan; how long a deleted account can be recovered; how the old phone cancels a number release; whether short Asha replies are cached.

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
10. Push: register the token now; delivery arrives later.

**Definition of done for each screen:** every error `code` in the catalogue's *Errors* column has a message in the app; every `POST`/`PUT`/`PATCH`/`DELETE` sends an `Idempotency-Key`; no token, code, key or phone number is ever logged; retries reuse the same `Idempotency-Key`.


## 11. Schema appendix

Generated from the spec. `?` marks an optional field. Every response is wrapped in the envelope from section 3, and the type named in the catalogue is what sits in `data`.

- **ActiveDeviceDto** — `model`: string; `activeSince`: string
- **AddOneTimeKeysRequest** — `oneTimePreKeys`: [OneTimePreKeyDto]; `deviceProof`: KeyUploadProofDto
- **AgentContactDto** — `userId`: string; `displayName`: string; `handle`?: string|null
- **AgentContactsResponse** — `contacts`: [AgentContactDto]; `nextCursor`?: string|null
- **AppleNameDto** — `given`?: string|null; `family`?: string|null
- **AskedFromDto** — `chatId`: string; `chatName`?: string|null
- **BlocksResponse** — `blocks`: [PublicProfileDto2]
- **CarrierRequest** — `carrierToken`: string
- **ChatMessageDto** — `messageId`: string; `role`: string; `text`: string; `replyTo`?: string|null; `askedFrom`?: string|null; `readNote`?: ReadNoteDto; `at`: string
- **ChatResponse** — `messages`: [ChatMessageDto]; `nextCursor`?: string|null
- **ClaimHandleRequest** — `handle`: string
- **ConnectRequest** — `via`: string; `value`: string
- **ConnectResponse** — `user`: PublicProfileDto2; `created`: boolean
- **ContactDto** — `user`: PublicProfileDto2; `source`: string; `addedAt`: string
- **ContactsResponse** — `contacts`: [ContactDto]; `nextCursor`?: string|null
- **DeviceDto** — `deviceId`: string; `platform`: string; `model`: string; `osVersion`?: string|null; `appVersion`: string
- **DeviceProofDto** — `timestamp`: string; `signature`: string
- **ErrorDetailsDto** — `retryAfterSeconds`?: integer|null; `fields`?: map<string,string>
- **HandleAvailabilityResponse** — `handle`: string; `available`: boolean; `status`: string; `suggestion`?: string|null
- **HealthReport** — `status`: string; `checks`?: map<string,string>
- **InviteResponse** — `token`: string; `url`: string; `qrPayload`: string; `expiresAt`: string
- **JwkDto** — `kty`?: string; `crv`?: string; `use`?: string; `alg`?: string; `kid`: string; `x`: string
- **JwksResponse** — `keys`: [JwkDto]
- **KeyBundleResponse** — `userId`: string; `identityKey`: string; `signedPreKey`: SignedPreKeyDto; `oneTimePreKey`?: OneTimePreKeyDto|null
- **KeyStatusResponse** — `oneTimePreKeysLeft`: integer; `signedPreKeyAt`: string
- **KeyUploadProofDto** — `timestamp`: string; `signature`: string
- **LinkProviderRequest** — `provider`: string; `idToken`: string; `nonce`: string
- **MyProfileResponse** — `userId`: string; `displayName`: string; `about`?: string|null; `handle`?: string|null; `handleChangeableAt`?: string|null; `avatarUrl`?: string|null; `privacy`: PrivacyDto; `version`: integer
- **NoBody** — `unused`?: string|null
- **NonceResponse** — `nonce`: string; `expiresInSeconds`: integer
- **OneTimePreKeyDto** — `keyId`: integer; `publicKey`: string
- **OneTimePreKeysLeftResponse** — `oneTimePreKeysLeft`: integer
- **PhoneVerifiedResponse** — `phoneVerified`: boolean; `maskedPhone`: string; `next`: string
- **PrivacyChangeDto** — `avatar`?: string|null; `about`?: string|null; `handleLookup`?: boolean|null
- **PrivacyDto** — `avatar`: string; `about`: string; `handleLookup`: boolean
- **ProviderDto** — `provider`: string; `maskedEmail`?: string|null; `linkedAt`: string
- **PublicProfileDto** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null
- **PublicProfileDto2** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null
- **PublishKeysRequest** — `identityKey`: string; `signedPreKey`: SignedPreKeyDto; `oneTimePreKeys`?: [OneTimePreKeyDto]; `deviceProof`: KeyUploadProofDto
- **PushTokenRequest** — `pushToken`: string
- **ReachResponse** — `reachable`: boolean
- **ReadNoteDto** — `chatIds`: [string]; `chatNames`?: [string]; `messageCount`: integer
- **RecoveryRequest** — `phone`: string; `contactEmail`: string; `note`?: string|null
- **RecoveryResponse** — `requestId`: string; `status`: string; `noticeSentAt`?: string|null; `relinkAfter`?: string|null
- **RefreshRequest** — `refreshToken`: string; `deviceId`: string; `timestamp`: string; `signature`: string
- **RefreshResponse** — `session`: SessionDto; `agentSession`: SessionDto
- **RegisterDeviceRequest** — `deviceId`: string; `publicKey`: string; `keyAlgorithm`: string; `pushToken`?: string|null; `attestation`?: string|null; `model`?: string|null
- **RegisterDeviceResponse** — `user`: UserStateDto; `session`: SessionDto; `agentSession`: SessionDto; `replacedDevice`?: ReplacedDeviceDto
- **ReleaseRequest** — `verificationId`: string; `confirmAbandon`: boolean
- **ReleaseResponse** — `releaseAt`: string
- **RemovedResponse** — `removed`: boolean
- **ReplacedDeviceDto** — `model`: string; `lastSeenAt`: string
- **SecurityEventDto** — `eventId`: string; `type`: string; `deviceModel`?: string|null; `detail`?: string|null; `at`: string
- **SecurityLogResponse** — `events`: [SecurityEventDto]; `nextCursor`?: string|null
- **SecurityOverviewResponse** — `maskedPhone`?: string|null; `providers`: [ProviderDto]; `device`?: ActiveDeviceDto; `verification`: string
- **SendMessageRequest** — `text`: string; `askedFrom`?: AskedFromDto; `chatReading`?: boolean
- **SessionDto** — `accessToken`: string; `refreshToken`?: string|null; `expiresInSeconds`: integer; `scope`: string
- **SessionDto2** — `accessToken`: string; `refreshToken`?: string|null; `expiresInSeconds`: integer; `scope`: string
- **SetAvatarRequest** — `objectKey`: string
- **SignedPreKeyDto** — `keyId`: integer; `publicKey`: string; `signature`: string
- **SignInResponse** — `next`: string; `maskedPhone`?: string|null; `session`: SessionDto; `agentSession`?: SessionDto2
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
- **UpdateProfileRequest** — `displayName`?: string|null; `about`?: string|null; `privacy`?: PrivacyChangeDto; `version`: integer
- **UploadUrlRequest** — `contentType`: string; `sizeBytes`: integer
- **UploadUrlResponse** — `uploadUrl`: string; `objectKey`: string; `expiresInSeconds`: integer
- **UsageResponse** — `plan`: string; `textTurnsUsed`: integer; `textTurnsLeft`: integer; `voiceSecondsUsed`: integer; `voiceSecondsLeft`: integer; `resetsAt`: string
- **UserStateDto** — `userId`: string; `status`: string
- **VerifyCodeRequest** — `verificationId`: string; `code`: string
