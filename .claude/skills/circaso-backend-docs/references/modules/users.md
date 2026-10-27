# Users module

Users answers "who is this person to me, and may I reach them": it owns profiles, @handles, the contact graph and the reach rule that every later module (messaging, wallet, calls) checks before letting one person touch another. Schema `users`, Redis prefix `usr:`, stream `events:users`.

## Overview

### Owns

- **Profile** — display name, avatar, short about line, privacy settings.
- **@handle** — unique, chosen by the user, changeable once per 30 days; a released handle is held 30 days for its old owner.
- **Contacts** — who each user can reach, and how the connection was made: phone book, QR code, @handle or invite link. Users can remove a contact; sync never adds them back.
- **Waiting contacts** — numbers in a user's phone book that aren't on Circaso yet, kept as keyed hashes, so the user hears when they join.
- **Blocks** — a block overrides every other way of reaching someone.
- **Invite links and QR codes** — signed, revocable tokens that stand in for giving out a number.

### The reach rule

A can message, call or pay B only if all of these hold:

1. B's account is active.
2. Neither has blocked the other.
3. A has an active connection to B: B's number is in A's synced phone book, or A connected through B's QR code, @handle or invite link, and A hasn't removed B.

It does not require B to have A saved, which matches how WhatsApp behaves: if you have someone's number, you can message them. Group membership (chamas, later) is a separate rule owned by messaging.

### Privacy defaults

- Phone numbers are never shown to other users; people see the name, @handle and avatar.
- Avatar visible to anyone who can reach you or shares a group with you; about line to contacts only; handle lookup on. All three can be changed in privacy settings.

### Contract (`users:api`)

```kotlin
interface ReachPolicy {              // messaging, wallet, calls check this
    suspend fun canReach(from: UserId, to: UserId): Boolean
    suspend fun reachable(from: UserId, to: Set<UserId>): Set<UserId>
}

interface Profiles {                 // display data for other modules
    suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile>   // name, avatar url, handle
}
```

### Contact sync

1. The app normalises phone-book numbers to E.164 and uploads them over TLS, in batches of up to 1,000, with a sync id; after the first full sync only changes are sent.
2. Users calls `identity.AccountDirectory.resolvePhones` to find which numbers belong to active accounts.
3. Matches become contact rows (unless the user removed that person). Numbers with no match are stored only as an HMAC (a hash keyed with a server secret, so the stored values can't be reversed by guessing numbers) in `waiting_contacts`.
4. The app receives the matches with public profiles; the names from the phone book stay on the phone and are never stored on the server.

**Why a keyed hash and not a plain hash:** there are few enough Kenyan numbers that a plain hash of every one can be computed in minutes, so a plain hash protects nothing. The phone-HMAC key is one secret shared with identity, so identity's events (`phoneHmac`) match waiting contacts directly.

**Avatars** live in Cloudflare R2. The app asks for a pre-signed upload URL, uploads directly, then confirms; the backend never streams image bytes.

### Depends on

`shared`, `infrastructure:api` and `identity:api`. Every later module will depend on `users:api` for the reach rule and profiles.

## Endpoints

Sixteen routes, all with the `app` scope; reads that return another person's data only return `PublicProfile`, which never contains a phone number. Each route goes route → controller → service method → use case (see Module anatomy on the Architecture doc).

| # | Route | Use case (domain) | Does |
| --- | --- | --- | --- |
| U1 | `GET /me/profile` | `GetMyProfile` | Own full profile, privacy settings included |
| U2 | `PATCH /me/profile` | `UpdateProfile` | Name, about, privacy; optimistic lock on `version` |
| U3 | `POST /me/avatar/upload-url` | `PrepareAvatarUpload` | Pre-signed R2 upload URL |
| U4 | `POST /me/avatar` / `DELETE /me/avatar` | `SetAvatar` / `RemoveAvatar` | Confirms an uploaded image, or removes it |
| U5 | `GET /handles/{handle}/availability` | `CheckHandle` | Free, taken or invalid, with a suggestion |
| U6 | `PUT /me/handle` | `ClaimHandle` | Claims or changes the @handle |
| U7 | `POST /contacts/sync` | `SyncContacts` | Uploads a batch of phone-book numbers, returns matches |
| U8 | `GET /contacts` | `ListContacts` | Paged contacts with public profiles |
| U9 | `GET /users/by-handle/{handle}` | `LookupHandle` | Exact-match lookup, rate-limited |
| U10 | `GET /users/{userId}` | `GetPublicProfile` | Public profile of someone you can reach |
| U11 | `POST /connections` | `Connect` | Connect through a handle, QR code or invite link |
| U12 | `POST /invites` / `DELETE /invites/{token}` | `CreateInvite` / `RevokeInvite` | Shareable link and QR payload |
| U13 | `GET /blocks` | `ListBlocks` | People this user has blocked |
| U14 | `PUT /blocks/{userId}` / `DELETE /blocks/{userId}` | `BlockUser` / `UnblockUser` | Block or unblock |
| U15 | `GET /me/reach/{userId}` | `CheckReach` | Whether this user can reach that one (debug and app hints) |
| U16 | `DELETE /contacts/{userId}` | `RemoveContact` | Removes someone from this user's contacts; sync won't add them back |

### Shared shapes

```json
// PublicProfile — the only shape other people ever see
{ "userId": "0193…", "displayName": "Amina Otieno", "handle": "amina", "avatarUrl": "https://cdn.circaso.com/a/0193….jpg", "about": "Chama treasurer" }

// MyProfile — U1, and the response of U2, U4, U6
{
  "userId": "0193…", "displayName": "Amina Otieno", "about": "Chama treasurer",
  "handle": "amina", "handleChangeableAt": "2026-11-20T18:06:00Z",
  "avatarUrl": "https://cdn.circaso.com/a/0193….jpg",
  "privacy": { "avatar": "EVERYONE_WHO_CAN_REACH", "about": "CONTACTS", "handleLookup": true },
  "version": 4
}
```

### U2 Update profile

```json
// PATCH /me/profile ← only the fields being changed, plus the version read last
{ "displayName": "Amina O.", "version": 4 }
// → data: MyProfile with version 5; a stale version returns users.version_conflict (409)
```

### U3–U4 Avatar

```json
// POST /me/avatar/upload-url ← { "contentType": "image/jpeg", "sizeBytes": 182000 }
// → data { "uploadUrl": "https://…r2…", "objectKey": "avatars/0193…/0193….jpg", "expiresInSeconds": 300 }
// app PUTs the bytes to uploadUrl, then:
// POST /me/avatar ← { "objectKey": "avatars/0193…/0193….jpg" }   → data: MyProfile
```

JPEG, PNG or WebP up to 5 MB. The backend checks the object exists and its size before accepting it.

### U5–U6 Handles

Rules: 3–20 characters, lowercase letters, digits and underscore, starting with a letter; reserved words (`circaso`, `support`, `admin`, `asha`, …) refused. One change per 30 days.

```json
// GET /handles/amina/availability → data { "handle": "amina", "available": false, "suggestion": "amina_o" }
// PUT /me/handle ← { "handle": "amina_o" }   → data: MyProfile
```

### U7 Contact sync

```json
// POST /contacts/sync ← request (up to 1,000 numbers per batch)
{ "syncId": "0193…", "batch": 1, "final": false, "phones": ["+254712345678", "+254733000111"] }

// → data
{ "matches": [ { "phone": "+254712345678", "user": { "userId": "0193…", "displayName": "Amina Otieno", "handle": "amina", "avatarUrl": null, "about": null } } ] }
```

The phone in a match is echoed only because the app sent it, so the app can attach the name from its own address book. The batch with `final: true` closes the sync. Numbers removed from the phone book since the last sync stay as contacts; removing someone is the explicit U16 action.

### U11 Connect and U12 invites

```json
// POST /connections ← { "via": "handle", "value": "amina" }     // via: handle | qr | invite
// → data { "user": PublicProfile, "created": true }

// POST /invites ← {}
// → data { "token": "inv_7Hq…", "url": "https://circaso.com/i/inv_7Hq…", "qrPayload": "circaso://i/inv_7Hq…", "expiresAt": "2026-10-28T18:06:00Z" }
```

A QR code is a signed invite token. Connecting through a QR code or invite link creates the contact in both directions, because the owner handed it over in person or on purpose. Connecting through a typed @handle only lets the person who typed it reach the handle's owner; the owner can reach back once they connect too.

### U16 Remove contact

```json
// DELETE /contacts/{userId} → data { "removed": true }
```

The contact row is kept with status `REMOVED`, in one transaction with its outbox row, and cached reach decisions for the pair are cleared. Keeping the row stops the next sync from adding the person back while their number is still in the phone book. Only the user can restore them, through a QR code, invite or handle (U11). The other person isn't told and keeps you if they had you.

### Agent routes (read-only, for Asha)

Users exposes read-only routes for the assistant under `presentation/agent/` (`GET /v1/agent/users/…`, `agent` scope only): for example the user's contacts with public profiles and contact search by name, so Asha can resolve "Mum" to a contact. Exact routes are designed with the assistant module.

## Data

Eight PostgreSQL tables in schema `users` (role `users`, Flyway location `db/users`) and four Redis key families. Contacts and blocks are each one table, read from both sides through a second index.

> **Until PG6: modules not yet moved.** Temporary; PG6 deletes this block. Users still runs on its Kandra tables in keyspace `circaso_users` until slice PG3 builds the tables below.

```sql
-- V1__users.sql (shape; the script itself is written in PG3)

CREATE TABLE users.profiles (
    user_id           uuid        PRIMARY KEY,
    display_name      text        NOT NULL,
    about             text,
    handle            text,                             -- lowercase; the live handle, a copy of the owner's row in handles
    handle_changed_at timestamptz,
    avatar_key        text,
    privacy           text        NOT NULL,             -- JSON: avatar, about, handleLookup
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0    -- optimistic lock (U2's version)
);
CREATE UNIQUE INDEX profiles_handle_key ON users.profiles (handle);

CREATE TABLE users.handles (
    handle      text        PRIMARY KEY,                -- lowercase; one owner per handle, live or on hold
    user_id     uuid        NOT NULL,
    claimed_at  timestamptz NOT NULL,
    released_at timestamptz,                            -- set on a change or on account deletion
    expires_at  timestamptz                             -- released_at + 30 days: the hold for the old owner; NULL while in use
);
CREATE INDEX handles_user_idx ON users.handles (user_id);
CREATE INDEX handles_expires_at_idx ON users.handles (expires_at) WHERE expires_at IS NOT NULL;   -- for the purge

CREATE TABLE users.contacts (
    owner_id   uuid        NOT NULL,
    contact_id uuid        NOT NULL,
    source     text        NOT NULL,                    -- PHONEBOOK | QR | INVITE | HANDLE
    status     text        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | REMOVED; removed rows stop sync from re-adding
    removed_at timestamptz,
    added_at   timestamptz NOT NULL,
    PRIMARY KEY (owner_id, contact_id)
);
CREATE INDEX contacts_target_idx ON users.contacts (contact_id) WHERE status = 'ACTIVE';
                                                        -- who has me: fan-out for joins and profile changes

CREATE TABLE users.waiting_contacts (
    phone_hmac text        NOT NULL,                    -- HMAC-SHA256(phone, phone-HMAC key shared with identity)
    owner_id   uuid        NOT NULL,
    added_at   timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,                    -- added_at + 180 days
    PRIMARY KEY (phone_hmac, owner_id)
);
CREATE INDEX waiting_contacts_expires_at_idx ON users.waiting_contacts (expires_at);

CREATE TABLE users.blocks (
    user_id    uuid        NOT NULL,
    blocked_id uuid        NOT NULL,
    blocked_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, blocked_id)
);
CREATE INDEX blocks_blocked_idx ON users.blocks (blocked_id);   -- who blocked me

CREATE TABLE users.invites (
    token      text        PRIMARY KEY,
    owner_id   uuid        NOT NULL,
    kind       text        NOT NULL,                    -- LINK | QR
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL                     -- created_at + 7 days
);
CREATE INDEX invites_expires_at_idx ON users.invites (expires_at);

CREATE TABLE users.realtime (
    user_id uuid        NOT NULL,
    seq     bigint      NOT NULL,
    frame   text        NOT NULL,                       -- the envelope as JSON
    sent_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)                 -- replay reads WHERE user_id = ? AND seq > ? ORDER BY seq
) PARTITION BY RANGE (sent_at);                         -- daily partitions, dropped after 7 days

CREATE TABLE users.outbox (…);                          -- the same shape as identity's outbox: daily partitions, 7 days
```

| Table | Expiry | How the race is settled |
| --- | --- | --- |
| `profiles` | — | U2 updates with `WHERE version = ?` (0 rows is `users.version_conflict`). A missing profile is created with `insertIgnore`, so two readers can't create two |
| `handles` | On release, `expires_at` = 30 days; purged by `users.purge-expired` | U6 claims with `insertIgnore` in the same transaction that updates `profiles.handle` and releases the old handle. On a conflict the existing row is taken over only if it is the caller's own hold or an expired one (`updateReturning … WHERE user_id = ? OR expires_at <= now()`); a claim that loses to the purge between the `insertIgnore` and the takeover retries once. Otherwise (owned, or on hold for someone else) the answer is `users.handle_taken` |
| `contacts` | — | `insertIgnore` keeps a sync from re-adding a `REMOVED` row; U16 updates the one row to `REMOVED` |
| `waiting_contacts` | `expires_at`, 180 days | — |
| `blocks` | — | — |
| `invites` | `expires_at`, 7 days | — |
| `realtime` | Daily partitions, 7 days | — |
| `outbox` | Daily partitions, 7 days | — |

Every read of a table with `expires_at` filters `expires_at > now()`.

**How the reach rule reads:** `canReach(a, b)` = `b` is active (identity, cached) ∧ no row in `blocks` for (a, b) or (b, a) ∧ a row in `contacts` for (a, b) with status `ACTIVE`. Three primary-key reads, cached in Redis.

**Redis (`usr:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `usr:reach:{a}:{b}` | `1` or `0` | 10 min | Cached reach decision; deleted on block, unblock, connect, remove, or account status change |
| `usr:handle-lookup:{userId}` | counter | 1 h | 30 handle lookups per user per hour |
| `usr:sync:{userId}:{syncId}` | batch count | 1 h | Tracks an in-progress contact sync |
| `usr:seq:{userId}` | counter | 8 days, renewed on use | Next realtime seq for this user's users-module frames |

## Events

**Consumed** (consumer group `users` on `events:identity`)

| Event | What users does |
| --- | --- |
| `identity.AccountActivated` | Create the profile, prefilled from the name and photo hints; look up `waiting_contacts` by the event's `phoneHmac`; turn waiting rows into contacts; send `contact.joined` to those users |
| `identity.PhoneChanged` | Re-check waiting contacts by `newPhoneHmac`; existing contacts stay |
| `identity.AccountStatusChanged` | Hide or restore the profile; clear cached reach decisions |
| `identity.AccountDeleted` | Delete profile, contacts and blocks; release the handle into its 30-day hold |

If a profile is ever missing when it's read (the event is still in flight, or failed into the dead-letter stream), users creates an empty one on the spot, so nobody is stuck without a profile.

**Published** (stream `events:users`)

| Event | `data` | Consumers and what they do |
| --- | --- | --- |
| `users.ProfileUpdated` v1 | `userId`, `version`, `changed: ["displayName" \| "avatar" \| "handle" \| "about"]` | gateway: send `users.profile.updated` to contacts; later messaging refreshes its copies |
| `users.ConnectionAdded` v1 | `fromUserId`, `toUserId`, `source`, `mutual` | gateway: send `users.connection.added`; later messaging can open the chat |
| `users.ContactRemoved` v1 | `ownerId`, `contactId` | later messaging |
| `users.UserBlocked` v1 / `users.UserUnblocked` v1 | `byUserId`, `targetUserId` | later messaging and calls drop or restore delivery |

**Realtime events users sends** (through the gateway, not the stream): `users.contact.joined`, `users.profile.updated`, `users.connection.added`.

Users implements `RealtimeReplay` (see Realtime and push on the Architecture doc): each frame it sends takes the next `seq` from `usr:seq:{userId}` and is kept for 7 days in the `realtime` table (by user and `seq`, the envelope as JSON, daily partitions dropped after 7 days), so a phone that reconnects gets what it missed. Handlers ignore a `ProfileUpdated` whose `version` is older than the copy they hold, because stream retries can reorder events.

## Error codes

Users' own codes; the shared `request.*`, `auth.*` and `internal.error` codes are on the Architecture doc.

| Code | Kind → status | When |
| --- | --- | --- |
| `users.profile_invalid` | INVALID → 400 | Name empty or over 40 characters, about over 140 |
| `users.version_conflict` | CONFLICT → 409 | Profile changed since it was read |
| `users.avatar_invalid` | INVALID → 400 | Wrong type, over 5 MB, or upload missing |
| `users.handle_invalid` | INVALID → 400 | Breaks the handle rules or is reserved |
| `users.handle_taken` | CONFLICT → 409 | Owned or on hold for someone else |
| `users.handle_cooldown` | CONFLICT → 409 | Changed less than 30 days ago |
| `users.handle_not_found` | NOT_FOUND → 404 | No such handle, or its owner turned lookup off (the two look the same on purpose) |
| `users.not_reachable` | FORBIDDEN → 403 | No connection, or blocked either way (never says which) |
| `users.contact_not_found` | NOT_FOUND → 404 | Removing someone who isn't a contact |
| `users.invite_invalid` | NOT_FOUND → 404 | Invite expired or revoked |
| `users.sync_too_large` | INVALID → 400 | Over 1,000 numbers in a batch |
| `users.cannot_block_self` | INVALID → 400 | Blocking yourself |
