# Identity module

Identity answers "is this really the person behind this account, and is this request from their phone": it owns accounts, sign-in providers, the verified phone, devices, sessions and support-led recovery, and nothing about names, photos or friends. Keyspace `circaso_identity`, Redis prefix `idn:`, stream `events:identity`. The screen-by-screen flow, with each screen's call, is in Identity — sign-in flow.

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
| I8 | `POST /auth/signout` | app | `SignOut` | Ends the session; the phone stays registered |
| I9 | `PUT /devices/current/push-token` | app | `UpdatePushToken` | Stores the FCM/APNs token |
| I10 | `DELETE /devices/current` | app | `RemoveDevice` | Forgets this phone; next sign-in anywhere re-verifies the number |
| I11 | `GET /account/security` | app | `GetSecurityOverview` | Phone, linked providers, this phone, verification status |
| I12 | `POST /account/providers` / `DELETE /account/providers/{provider}` | app | `LinkProvider` / `UnlinkProvider` | Link Google or Apple; unlink only if the other stays |
| I13 | `GET /account/security-log` | app | `ListSecurityEvents` | Paged, newest first |
| I14 | `DELETE /account` | app | `RequestAccountDeletion` | Schedules deletion once every `DeletionGuard` agrees (the wallet refuses while it holds funds) |
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

- A new provider login creates the account immediately as `PENDING_PHONE` and claims the provider subject with `saveIfNotExists`, so two taps on the button can't create two accounts.
- `REVERIFY_PHONE` only accepts the account's own number; a different number returns `identity.phone_mismatch` and points to support recovery.
- On sign-up, `VerifyPhoneCode` claims the number with `saveIfNotExists`. A number already claimed by another account returns `identity.phone_taken`.
- The account turns `ACTIVE` and `AccountActivated` is written to the outbox when the first device is registered (I6), so every active account has a device and a session.
- Registering a new phone on an active account marks the old device `REPLACED`, ends its sessions (the gateway deny-list picks this up within one access-token lifetime), and writes `DeviceReplaced`.
- Pending accounts that never finish are deleted after 30 days by a scheduled sweep.

## Data

Nine Kandra entities in keyspace `circaso_identity` and five Redis key families.

```kotlin
const val KS = "circaso_identity"

@ScyllaTable(name = "accounts_by_id", keyspace = KS)
data class AccountRow(
    @PartitionKey val userId: UUID,
    @Sensitive val phone: String? = null,       // E.164, set once verified
    val status: String,                         // PENDING_PHONE | ACTIVE | RECOVERY_PENDING | SUSPENDED | BLOCKED | DELETED
    val activeDeviceId: UUID? = null,
    val verification: String = "NONE",         // NONE | PENDING | VERIFIED (KYC)
    val sessionVersion: Int = 1,                // bump to invalidate every token at once
    @CreatedAt val createdAt: Instant? = null,
    @UpdatedAt val updatedAt: Instant? = null,
    @Version val version: Long = 0,
)

@ScyllaTable(name = "phone_claims", keyspace = KS)              // one account per number
data class PhoneClaimRow(@PartitionKey @Sensitive val phone: String, val userId: UUID, val claimedAt: Instant)

@ScyllaTable(name = "provider_claims", keyspace = KS)           // one account per Google/Apple login
data class ProviderClaimRow(
    @PartitionKey(index = 0) val provider: String,
    @PartitionKey(index = 1) @Sensitive val subject: String,
    val userId: UUID,
    val linkedAt: Instant,
)

@ScyllaTable(name = "providers_by_user", keyspace = KS)
data class ProviderLinkRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val provider: String,
    @Sensitive val subject: String,
    @Sensitive val email: String?,
    val linkedAt: Instant,
)

@ScyllaTable(name = "devices_by_user", keyspace = KS)
data class DeviceRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val deviceId: UUID,
    val platform: String, val model: String, val appVersion: String,
    val publicKey: String, val keyAlgorithm: String,
    @Sensitive val pushToken: String? = null,
    val status: String,                         // ACTIVE | REPLACED | REMOVED
    val registeredAt: Instant, val lastSeenAt: Instant,
)

@ScyllaTable(name = "sessions_by_id", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 30)                // 30 days without use and the session is gone
data class SessionRow(
    @PartitionKey val sessionId: UUID,
    val userId: UUID, val deviceId: UUID,
    @Sensitive val currentTokenHash: String,    // SHA-256 of the live refresh token
    val scope: String, val status: String,      // ACTIVE | REVOKED
    val createdAt: Instant, val rotatedAt: Instant,
)

@ScyllaTable(name = "security_log_by_user", keyspace = KS)
data class SecurityEventRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = DESC) @GeneratedUuid val eventId: UUID? = null,
    val type: String,                           // SIGN_IN | NEW_PHONE | PHONE_VERIFIED | PROVIDER_LINKED | RECOVERY_REQUESTED | …
    val deviceModel: String?, val detail: String?,
)

@ScyllaTable(name = "support_recoveries", keyspace = KS)
data class SupportRecoveryRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = DESC)
    @LookupIndex(tableSuffix = "by_request") val requestId: UUID,   // agents open a case by request id
    val status: String,                         // RECEIVED | VERIFYING | NOTICE_SENT | RELINKED | REJECTED | CANCELLED
    @Sensitive val contactEmail: String,
    val idCheck: String?, val selfieMatch: String?, val agentId: String?,
    val noticeSentAt: Instant?, val relinkAfter: Instant?,
    val newProvider: String?, @Sensitive val newSubject: String?,
)

@ScyllaTable(name = "outbox", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24 * 7)
data class IdentityOutboxRow(
    @PartitionKey val bucket: String,           // minute bucket, e.g. "2026-10-21T18:06"
    @ClusteringKey val eventId: UUID,           // UUIDv7
    val type: String, val payload: String,      // JSON envelope
    val publishedAt: Instant? = null,
)
```

Refresh tokens have the form `rt_<sessionId>.<secret>`, so a refresh reads one partition by `sessionId` and compares hashes; no table is keyed by the token itself. Uniqueness (phone, provider login) uses claim tables written with `saveIfNotExists`, which also serve as the lookup for that value; `@LookupIndex` is used only where nobody races for the value (`requestId`). See the Kandra patterns on the Architecture tab.

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
| `identity.AccountStatusChanged` v1 | Suspended, reinstated, blocked | `userId`, `status` | users: hide or restore the profile, clear cached reach decisions; gateway: close sockets when not active |
| `identity.AccountRelinked` v1 | Support recovery completed after the 48-hour notice | `userId`, `provider` | gateway: close old sockets; the user id is unchanged, so the Circle user and wallets carry over |
| `identity.AccountDeleted` v1 | Deletion completed | `userId` | users: delete profile, contacts, blocks; release the handle into its 30-day hold; every later module removes or anonymises its data |

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
| `identity.wallet_not_empty` | CONFLICT → 409 | Deleting an account that still holds funds |
| `identity.recovery_not_eligible` | FORBIDDEN → 403 | Support recovery for an unverified account |
| `identity.release_not_allowed` | FORBIDDEN → 403 | Phone release for a verified account (recovery applies instead) |
