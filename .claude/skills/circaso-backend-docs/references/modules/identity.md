# Identity module

Identity answers "is this really the person behind this account, and is this request from their phone": it owns accounts, sign-in providers, the verified phone, devices, sessions and support-led recovery, and nothing about names, photos or friends. Schema `identity`, Redis prefix `idn:`, stream `events:identity`. The screen-by-screen flow, with each screen's call, is in Identity — sign-in flow.

## Overview

### Owns

- **Accounts** — `userId`, status (pending phone, active, recovery-pending, suspended, blocked, deleted).
- **Sign-in providers** — Google and Apple, keyed on the provider's `sub`; one or both per account. The Google/Apple sign-in *is* the account.
- **Phone number** — exactly one verified number per account, one account per number.
- **Devices** — one active phone per account (model ready for several), each with a hardware-backed public key.
- **Sessions** — 15-minute access tokens and device-bound refresh tokens, with scopes `onboarding`, `app`, `agent`, `ops`.
- **Recovery eligibility** — whether the account completed identity verification (status and a reference only, never the documents). A lost Google/Apple login can only be relinked by support, and only for verified accounts.
- **Security log** — every sign-in, device change, verification and recovery.

### Contract (`identity:api`)

```kotlin
interface TokenVerifier {            // the gateway, on every request
    suspend fun verify(accessToken: String): Resource<Principal>   // userId, deviceId, scopes; also checks the revoked-session deny-list
}

interface AccountDirectory {         // users (contact matching) and later modules
    suspend fun status(userId: UserId): AccountStatus?
    suspend fun resolvePhones(phones: Set<PhoneNumber>): Map<PhoneNumber, UserId>
}

interface DeviceSignatures {         // device-bound actions; wallet transactions are signed with the Circle PIN instead
    suspend fun verify(userId: UserId, deviceId: DeviceId,
                       payload: ByteArray, signature: ByteArray): Resource<Unit>
}

// Implemented by the modules above identity (wallet first), wired in app.
// Identity asks every guard before scheduling a deletion and never depends on those modules.
interface DeletionGuard {
    suspend fun canDelete(userId: UserId): Resource<Unit>   // Error carries the guard's own code, e.g. wallet.funds_remaining
}
```

The contract never hands out a user's phone number, and events carry only `phoneHmac`: HMAC-SHA256 of the E.164 number under the phone-HMAC key in `Secrets`, the same key users uses for waiting contacts. A module can match a number it already holds, but never learns one it doesn't.

### Integrations used

| Contract (`integrations:api`) | Used for | Provider now |
| --- | --- | --- |
| `PhoneVerificationGateway` | Optional: a vendor that makes, sends and checks the one-time code (`managesCodes = true`); then identity stores only a pending record marked as vendor-checked and `SmsGateway` is not used for codes | Twilio Verify |
| `SocialSignIn` | Verifying Google and Apple ID tokens; revoking Apple tokens when an account is deleted | Google, Apple |
| `CarrierPhoneVerification` | One-tap number check on Android | Firebase PNV |
| `SmsGateway` | Verification codes, sender ID "Circaso" | Africa's Talking |
| `EmailGateway` | Security alerts: new phone, support recovery started and completed; and one welcome when an account first becomes active (approved by Pasaka, 2026-10-22, since email is only held for Google/Apple sign-ins) | Resend |

Identity owns everything around these calls: generating and hashing codes, expiry, attempt limits, the wording of every message.

### Tokens

- **Access tokens:** JWTs signed with an Ed25519 key held by identity, 15 minutes, claims `sub` (userId), `did` (deviceId), `sid` (sessionId), `scp` (scope), `ver` (session version). An app session always carries two: the **user token** (`app`) and the **agent token** (`agent`).
- **Agent token:** issued with the user token whenever an app session is issued (I2 with `next: DONE`, I6) and re-issued with it on every refresh (I7), so the two always expire and rotate together. It shares the session's `sid`, has no refresh token of its own, and dies with the session (sign-out, new phone, suspension, recovery), because the gateway's deny-list is keyed on `sid`. It reaches only the read-only routes under `/v1/agent/`; the gateway refuses it everywhere else.
- **Refresh token:** `rt_<sessionId>.<secret>`, only its hash stored, rotated on every use, and accepted only with a signature from the phone it was issued to. Reuse of an old one revokes the whole session.
- **Key rotation:** public keys are published at `/v1/.well-known/jwks.json` with a key id, so a new key can be introduced without signing anyone out.

### Depends on

`shared`, `infrastructure:api` and `integrations:api`. Identity depends on no other business module, which makes it the foundation.

## Endpoints

Seventeen routes: seven take a person from Google/Apple sign-in to an active session, the rest manage the session, the account and support recovery. Paths are relative to `https://api.circaso.com/v1`; samples show only `data` from the `DefaultResponse` envelope (API conventions are on the Architecture tab).

| # | Route | Scope | Use case (domain) | Does |
| --- | --- | --- | --- | --- |
| I1 | `POST /auth/nonce` | none | `IssueNonce` | One-time nonce for the Google/Apple request, valid 5 min |
| I2 | `POST /auth/social` | none | `SignInWithProvider` | Verifies the ID token and decides the next step (see the decision table) |
| I3 | `POST /auth/phone/start` | onboarding | `StartPhoneVerification` | Sends a 6-digit SMS code through `SmsGateway` |
| I4 | `POST /auth/phone/verify` | onboarding | `VerifyPhoneCode` | Checks the code; claims the number on sign-up |
| I5 | `POST /auth/phone/carrier` | onboarding | `VerifyCarrierToken` | Same as I4, from a Firebase PNV token, no SMS |
| I6 | `POST /devices` | onboarding | `RegisterDevice` | Registers the phone's public key; activates the account on sign-up; issues the `app` session |
| I7 | `POST /auth/refresh` | none (device-signed) | `RefreshSession` | New token pair; old refresh token dies |
| I8 | `POST /auth/signout` | app | `SignOut` | Ends the session; the phone stays registered; the gateway closes that session's open socket (decision 2026-10-25) |
| I9 | `PUT /devices/current/push-token` | app | `UpdatePushToken` | Stores the FCM/APNs token |
| I10 | `DELETE /devices/current` | app | `RemoveDevice` | Forgets this phone; next sign-in anywhere re-verifies the number |
| I11 | `GET /account/security` | app | `GetSecurityOverview` | Phone, linked providers, this phone, verification status |
| I12 | `POST /account/providers` / `DELETE /account/providers/{provider}` | app | `LinkProvider` / `UnlinkProvider` | Link Google or Apple; unlink only if the other stays |
| I13 | `GET /account/security-log` | app | `ListSecurityEvents` | Paged, newest first |
| I14 | `DELETE /account` | app | `RequestAccountDeletion` | Schedules deletion once every `DeletionGuard` agrees (the wallet refuses while it holds funds). A guard's own error reaches the app as is: `wallet.funds_remaining`, or `wallet.custody_unavailable` when the wallet can't check (decision 2026-10-28; `identity.wallet_not_empty` was removed) |
| I15 | `POST /recovery/requests` | onboarding | `RequestSupportRecovery` | Opens a support case for a lost Google/Apple login |
| I16 | `POST /auth/phone/release` | onboarding | `RequestPhoneRelease` | Frees a number held by a lost, unverified account, after a 7-day notice |
| I17 | `GET /recovery/requests/{requestId}` | onboarding | `GetRecoveryRequest` | Status of the caller's own support case: received, verifying, notice sent (with relink time), relinked, rejected or cancelled |

Plus `GET /.well-known/jwks.json` (public keys for access tokens) and ops-only routes under `/ops/recoveries` for support agents.

### I1 Nonce and I2 social sign-in

```json
// POST /auth/nonce → data
{ "nonce": "b8f3…", "expiresInSeconds": 300 }

// POST /auth/social ← request
{
  "provider": "google",                  // google | apple
  "idToken": "eyJ…",
  "nonce": "b8f3…",
  "appleName": { "given": "Amina", "family": "Otieno" },   // Apple, first sign-in only
  "device": { "deviceId": "0193…", "platform": "android", "model": "Samsung A54", "osVersion": "14", "appVersion": "1.0.0" },
  "deviceProof": { "timestamp": "2026-10-21T18:06:00Z", "signature": "MEUC…" }   // only if this phone already holds a device key
}

// → data
{
  "next": "VERIFY_PHONE",   // VERIFY_PHONE | REVERIFY_PHONE | REGISTER_DEVICE | DONE
  "maskedPhone": "+254 7•• ••• 678",   // when the account already has a number
  "session": { "accessToken": "eyJ…", "refreshToken": null, "expiresInSeconds": 900, "scope": "onboarding" },
  "agentSession": null   // set only with an app session (next = DONE)
}
```

`deviceProof.signature` signs `nonce + timestamp` with the device's private key; a valid proof from the registered active phone returns `next: DONE` with an `app` session and a refresh token.

### I3–I5 Phone verification

```json
// POST /auth/phone/start ← { "phone": "+254712345678" }
// → data
{ "verificationId": "0193…", "codeLength": 6, "expiresInSeconds": 300, "resendAfterSeconds": 30 }

// POST /auth/phone/verify ← { "verificationId": "0193…", "code": "482913" }
// POST /auth/phone/carrier ← { "carrierToken": "eyJ…" }
// → data (both)
{ "phoneVerified": true, "maskedPhone": "+254 7•• ••• 678", "next": "REGISTER_DEVICE" }
```

The SMS text ends with the app's SMS Retriever hash so Android reads it automatically: `Your Circaso code is 482913. Don't share it. FA+9qCX9VSu`. Identity generates, hashes and stores the code; `SmsGateway` only delivers it.

### I6 Register device

```json
// POST /devices ← request
{
  "deviceId": "0193…",
  "publicKey": "MFkwEwYH…",       // base64 SPKI, generated in Android Keystore / Secure Enclave
  "keyAlgorithm": "ES256",
  "pushToken": "fcm:…",
  "attestation": "…"               // Play Integrity or App Attest token
}

// → data
{
  "user": { "userId": "0193…", "status": "ACTIVE" },
  "session": { "accessToken": "eyJ…", "refreshToken": "rt_…", "expiresInSeconds": 900, "scope": "app" },
  "agentSession": { "accessToken": "eyJ…", "expiresInSeconds": 900, "scope": "agent" },   // read-only token for the assistant
  "replacedDevice": { "model": "Tecno Spark 10", "lastSeenAt": "2026-10-20T09:12:00Z" }   // null when none
}
```

**Attestation by environment.** The backend can't trust an app that says it is a debug build, so the rule is set by the environment the app talks to:

| Environment | Builds that reach it | Attestation (`identity.attestation`) |
| --- | --- | --- |
| Development, staging | Debug builds (separate `.debug` application id), internal testers | `LOG`: checked and recorded if present, never required |
| Production | Release builds installed from Google Play or the App Store | `ENFORCE`: Play Integrity must report a Play-recognised app on a device that meets device integrity; App Attest must verify. A failure returns `identity.device_attestation_failed` and no session |

Debug builds are compiled with the staging API address, so they can't reach production at all. A rooted or tampered phone fails the device-integrity check, so release builds don't run on them.

### I7 Refresh

```json
// POST /auth/refresh ← request
{ "refreshToken": "rt_…", "deviceId": "0193…", "timestamp": "2026-10-21T18:21:00Z", "signature": "MEUC…" }
// → data: a new session and agentSession (same shape as I6); both tokens rotate together
```

### I11 Security overview

```json
{
  "maskedPhone": "+254 7•• ••• 678",
  "providers": [ { "provider": "google", "maskedEmail": "am•••@gmail.com", "linkedAt": "2026-10-21T18:06:00Z" } ],
  "device": { "model": "Samsung A54", "activeSince": "2026-10-21T18:07:00Z" },
  "verification": "NONE"   // NONE | PENDING | VERIFIED — recovery is only possible when VERIFIED
}
```

### I15 Support recovery and I16 phone release

```json
// POST /recovery/requests ← { "phone": "+254712345678", "contactEmail": "amina@example.com", "note": "Lost my Google account" }
// → data { "requestId": "0193…", "status": "RECEIVED" }

// POST /auth/phone/release ← { "verificationId": "0193…", "confirmAbandon": true }   // after I3/I4 proves the SIM
// → data { "releaseAt": "2026-10-28T18:30:00Z" }
```

Recovery requests only proceed for accounts with `verification = VERIFIED`; the agent checks ID and a live selfie, then the relink happens 48 h after the old phone and email are notified. The user id doesn't change, so the Circle user and its wallets carry over. Phone release only applies to unverified accounts, waits 7 days with the same notices, and can be cancelled from the old phone, so a SIM swap alone can't wipe someone's account.

## Sign-in decision table

After verifying the Google/Apple token, `SignInWithProvider` looks up the provider claim, then the account and the device, and returns exactly one of these outcomes.

| Provider login known? | Account status | This phone | Returned `next` | Session | What the app does |
| --- | --- | --- | --- | --- | --- |
| No | — (new account created as `PENDING_PHONE`) | — | `VERIFY_PHONE` | onboarding | Phone number screen |
| Yes | `PENDING_PHONE` | any | `VERIFY_PHONE` | onboarding | "Finish setting up", phone screen |
| Yes | `ACTIVE` | Registered and active, valid `deviceProof` | `DONE` | app + refresh | Home |
| Yes | `ACTIVE` | New, or proof missing or invalid | `REVERIFY_PHONE` | onboarding | "New phone" screen, then verify the account's number |
| Yes | `ACTIVE`, phone verified on this device | Not yet registered | `REGISTER_DEVICE` | onboarding | Biometrics and device key step |
| Yes | `RECOVERY_PENDING` | any | error `identity.recovery_pending` (409) | none | "Your account is being recovered" with the time left |
| Yes | `SUSPENDED` | any | error `identity.account_suspended` (403) | none | Suspended screen |
| Yes | `BLOCKED` | any | error `identity.account_blocked` (403) | none | Blocked screen |
| Yes | `DELETED` (in grace period) | any | error `identity.account_deleted` (404) | none | "This account was deleted" |

**Rules the use case enforces**

- A new provider login creates the account immediately as `PENDING_PHONE` and claims the provider subject through the unique index on `provider_links (provider, subject)` in the same transaction, so two taps on the button can't create two accounts.
- `REVERIFY_PHONE` only accepts the account's own number; a different number returns `identity.phone_mismatch` and points to support recovery.
- On sign-up, `VerifyPhoneCode` claims the number through the unique index on `accounts.phone`. A number already claimed by another account returns `identity.phone_taken`.
- The account turns `ACTIVE` and `AccountActivated` is written to the outbox when the first device is registered (I6), so every active account has a device and a session.
- Registering a new phone on an active account marks the old device `REPLACED`, ends its sessions (the gateway deny-list picks this up within one access-token lifetime), and writes `DeviceReplaced`.
- Pending accounts that never finish are deleted after 30 days by a scheduled sweep (`identity.sweep-pending-accounts`, hourly), with their logins and security log, which frees the number and the login. Such an account was never activated, so nothing is published.
- A deleted account stays recoverable for 30 days (`identity.deletion-grace-days`; decision 2026-10-27). At its `delete_after`, `identity.complete-deletions` (every 15 minutes) deletes the account and every identity row of it (logins, devices, sessions, security log, support cases) and writes `AccountDeleted` to the outbox in the same transaction, one account per transaction; every module then deletes that user's data. A second run, or a second node, finds nothing to do.

## Data

Seven PostgreSQL tables in schema `identity` (role `identity`, Flyway location `db/identity`) and five Redis key families.

```sql
-- db/identity/V1__identity.sql (this is its shape; the script is the source of truth)

CREATE TABLE identity.accounts (
    user_id          uuid        PRIMARY KEY,
    phone            text,                              -- E.164, set once verified
    status           text        NOT NULL,              -- PENDING_PHONE | ACTIVE | RECOVERY_PENDING | SUSPENDED | BLOCKED | DELETED
    active_device_id uuid,
    verification     text        NOT NULL DEFAULT 'NONE', -- NONE | PENDING | VERIFIED (KYC)
    session_version  int         NOT NULL DEFAULT 1,    -- bump to invalidate every token at once
    delete_after     timestamptz,                       -- DELETED: when the grace period ends and deletion completes
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0     -- optimistic lock
);
CREATE UNIQUE INDEX accounts_phone_key ON identity.accounts (phone);   -- one account per number
CREATE INDEX accounts_pending_idx ON identity.accounts (created_at) WHERE status = 'PENDING_PHONE';
                                                        -- the 30-day sweep of pending accounts
CREATE INDEX accounts_deleted_idx ON identity.accounts (delete_after) WHERE status = 'DELETED';
                                                        -- the job that completes deletion after the grace period

CREATE TABLE identity.provider_links (
    user_id   uuid        NOT NULL,
    provider  text        NOT NULL,                     -- GOOGLE | APPLE
    subject   text        NOT NULL,
    email     text,
    linked_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, provider)
);
CREATE UNIQUE INDEX provider_links_login_key ON identity.provider_links (provider, subject);
                                                        -- one account per Google/Apple login

CREATE TABLE identity.devices (
    user_id       uuid        NOT NULL,
    device_id     uuid        NOT NULL,
    platform      text        NOT NULL,
    model         text        NOT NULL,
    app_version   text        NOT NULL,
    public_key    text        NOT NULL,
    key_algorithm text        NOT NULL,
    push_token    text,
    status        text        NOT NULL,                 -- ACTIVE | REPLACED | REMOVED
    registered_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE identity.sessions (
    session_id         uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    device_id          uuid        NOT NULL,
    current_token_hash text        NOT NULL,            -- SHA-256 of the live refresh token
    scope              text        NOT NULL,
    status             text        NOT NULL,            -- ACTIVE | REVOKED
    created_at         timestamptz NOT NULL,
    rotated_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL             -- rotated_at + 30 days: unused for 30 days and the session is gone
);
CREATE INDEX sessions_expires_at_idx ON identity.sessions (expires_at);   -- for the purge

CREATE TABLE identity.security_log (
    user_id      uuid NOT NULL,
    event_id     uuid NOT NULL,                         -- UUIDv7 made in the app; newest first by id
    type         text NOT NULL,                         -- SIGN_IN | NEW_PHONE | PHONE_VERIFIED | PROVIDER_LINKED | RECOVERY_REQUESTED | …
    device_model text,
    detail       text,
    PRIMARY KEY (user_id, event_id)                     -- I13 pages it by keyset, event_id DESC
);

CREATE TABLE identity.support_recoveries (
    request_id     uuid        PRIMARY KEY,             -- agents open a case by request id
    user_id        uuid        NOT NULL,
    status         text        NOT NULL,                -- RECEIVED | VERIFYING | NOTICE_SENT | RELINKED | REJECTED | CANCELLED
    contact_email  text        NOT NULL,
    note           text,
    id_check       text,
    selfie_match   text,
    agent_id       text,
    notice_sent_at timestamptz,
    relink_after   timestamptz,
    new_provider   text,
    new_subject    text
);
CREATE INDEX support_recoveries_user_idx ON identity.support_recoveries (user_id, request_id DESC);
CREATE INDEX support_recoveries_relink_idx ON identity.support_recoveries (relink_after) WHERE status = 'NOTICE_SENT';
                                                        -- the job that relinks 48 h after the notice
CREATE INDEX support_recoveries_status_idx ON identity.support_recoveries (status, request_id);
                                                        -- /ops/recoveries: the cases in a status, oldest first

-- identity.outbox: the shared outbox, created with the DDL in architecture.md → Durable events
-- (DatabaseModuleSpec(outbox = true); infrastructure writes and relays it)
```

| Table | Expiry | How the race is settled |
| --- | --- | --- |
| `accounts` | — (a `DELETED` account is removed by `identity.complete-deletions` at `delete_after`, 30 days after the request; a `PENDING_PHONE` one by the 30-day sweep) | The number is claimed by `AccountRepository.claimPhone` (setting `phone`) inside the verifying transaction (one `Transactor.attempt`); the unique index refuses a second account's write, `claimPhone` answers false, the transaction is rolled back and the answer is `identity.phone_taken`. A take-over after an I16 release closes the abandoned account in the same transaction, like a deletion (I14): `DELETED`, `delete_after` set, its sessions ended and `AccountStatusChanged` written; a lost claim rolls that back too. A repeated I14 changes nothing. Every write checks the `version` it read, and every operation that changes an account, its devices, sessions or logins locks the account row first (`SELECT … FOR UPDATE`), so two requests for one person run one after the other |
| `provider_links` | — | A new login claims the link with `insertIgnore` and inserts the account in the same transaction only when it won; `insertedCount = 0` means another tap won, so nothing is written and the winner's account is read |
| `devices` | — | Under the account's lock |
| `sessions` | `expires_at`, purged by `identity.purge-expired` | Sign-out is `UPDATE … WHERE status = 'ACTIVE' RETURNING`, so one caller ends a session; a refresh checks and rotates under the account's lock, so a second refresh with the same token is a reuse |
| `security_log` | — | — |
| `support_recoveries` | — | — |
| `outbox` | Daily partitions, dropped after 7 days | — |

Refresh tokens have the form `rt_<sessionId>.<secret>`, so a refresh reads one row by `session_id` and compares hashes; no table is keyed by the token itself. Every read of `sessions` filters `expires_at > now()`.

**Redis (`idn:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `idn:nonce:{nonce}` | `1` | 5 min | Single-use sign-in nonce; deleted on use |
| `idn:otp:{verificationId}` | hash: phone HMAC, code hash, attempts, userId | 5 min | Pending SMS code |
| `idn:otp-rate:phone:{phoneHmac}` / `idn:otp-rate:device:{deviceId}` | counter | 10 min / 24 h | 3 codes per number per 10 min, 10 per device per day |
| `idn:revoked:{sessionId}` | `1` | 15 min | Gateway deny-list for access tokens of a revoked session |
| `idn:phone-release:{phoneHmac}` | pending release info | 7 days | Phone release waiting period |

## Events published

All on stream `events:identity`, in the envelope described on the Architecture tab. Identity consumes no other module's events.

| Event | When | `data` | Consumers and what they do |
| --- | --- | --- | --- |
| `identity.AccountActivated` v1 | First device registered at sign-up (I6) | `userId`, `phoneHmac`, `hints: { displayName?, avatarUrl? }` | users: create the profile (prefilled from hints), turn waiting contacts for this number into contacts, send `contact.joined`; wallet later |
| `identity.PhoneChanged` v1 | Number changed through support recovery | `userId`, `oldPhoneHmac`, `newPhoneHmac` | users: re-check waiting contacts for the new number |
| `identity.DeviceReplaced` v1 | A new phone registered on an active account | `userId`, `oldDeviceId`, `newDeviceId` | gateway: close the old phone's socket, send `system.session.revoked` |
| `identity.SignedOut` v1 | A session signed out (I8), only when it goes from active to revoked | `userId`, `sessionId` | gateway: send `system.session.revoked` to that session's socket and close it (no push) |
| `identity.AccountStatusChanged` v1 | Suspended, reinstated, blocked | `userId`, `status` | users: hide or restore the profile, clear cached reach decisions; gateway: close sockets when not active |
| `identity.AccountRelinked` v1 | Support recovery completed after the 48-hour notice | `userId`, `provider` | gateway: close old sockets; the user id is unchanged, so the Circle user and wallets carry over |
| `identity.AccountDeleted` v1 | Deletion completed, 30 days after I14 (written in the transaction that deletes the rows) | `userId` | users: delete profile, contacts, blocks; release the handle into its 30-day hold; every later module removes or anonymises its data |

No raw phone number goes on a stream: `AccountActivated` and `PhoneChanged` carry the number's HMAC only, so neither Redis nor its backups hold phone numbers.

```json
{
  "eventId": "0193f2c1-7a4e-7c21-9b1e-5f3d2a8c4e61",
  "type": "identity.AccountActivated",
  "version": 1,
  "occurredAt": "2026-10-21T18:07:12Z",
  "producer": "identity",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "data": {
    "userId": "0193f2bf-11aa-7e02-8c3d-2b9a4f7e1c50",
    "phoneHmac": "k3Jx9Q…",
    "hints": { "displayName": "Amina Otieno", "avatarUrl": "https://lh3.googleusercontent.com/…" }
  }
}
```

## Error codes

Identity's own codes; the shared `request.*`, `auth.*` and `internal.error` codes are on the Architecture tab.

| Code | Kind → status | When |
| --- | --- | --- |
| `identity.nonce_invalid` | INVALID → 400 | Nonce missing, used or expired |
| `identity.provider_token_invalid` | UNAUTHORIZED → 401 | Google/Apple token fails verification |
| `identity.account_suspended` | FORBIDDEN → 403 | Account suspended |
| `identity.account_blocked` | FORBIDDEN → 403 | Account blocked |
| `identity.account_deleted` | NOT_FOUND → 404 | Account deleted (grace period) |
| `identity.recovery_pending` | CONFLICT → 409 | A support recovery is running |
| `identity.phone_invalid` | INVALID → 400 | Not a valid E.164 number |
| `identity.phone_taken` | CONFLICT → 409 | Number belongs to another account |
| `identity.phone_mismatch` | CONFLICT → 409 | New phone verifying a number that isn't the account's |
| `identity.code_invalid` | INVALID → 400 | Wrong SMS code; message says attempts left |
| `identity.code_expired` | INVALID → 400 | Code older than 5 min |
| `identity.code_locked` | RATE_LIMITED → 429 | 5 wrong codes; wait 10 min |
| `identity.sms_unavailable` | UPSTREAM → 502 | SMS provider down; app offers retry |
| `identity.carrier_unsupported` | INVALID → 400 | Carrier token refused; app falls back to SMS |
| `identity.device_attestation_failed` | FORBIDDEN → 403 | Play Integrity / App Attest rejected (production) |
| `identity.device_proof_invalid` | UNAUTHORIZED → 401 | Bad device signature on refresh |
| `identity.session_revoked` | UNAUTHORIZED → 401 | Session ended (replaced, signed out, suspended) |
| `identity.provider_last` | CONFLICT → 409 | Unlinking the only sign-in provider |
| `identity.provider_in_use` | CONFLICT → 409 | That Google/Apple login belongs to another account |
| `identity.recovery_not_eligible` | FORBIDDEN → 403 | Support recovery for an unverified account |
| `identity.release_not_allowed` | FORBIDDEN → 403 | Phone release for a verified account (recovery applies instead) |
