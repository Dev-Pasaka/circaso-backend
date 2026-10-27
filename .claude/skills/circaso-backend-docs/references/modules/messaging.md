# Messaging module

Messaging answers "get this message to these people, so that only they can read it": it owns 1:1 and group chats, membership, the public-key directory, delivery of end-to-end encrypted messages and media, receipts, and each user's encrypted history. It never sees plaintext. Schema `messaging`, Redis prefix `msg:`, stream `events:messaging`.

Status: draft, 2026-10-25. Decisions behind it are the 2026-10-25 rows in the decision log; points still to settle are under Open questions at the end.

## Overview

### Owns

- **Chats** — `DIRECT` (exactly two people, one chat per pair) and `GROUP` (up to 1,000 members, with an owner, admins and members). A group's name and avatar are encrypted on the phone; the server stores them sealed.
- **Key directory** — each account's public identity key, signed prekey and one-time prekeys, so anyone who can reach a person can start an encrypted session with them. One phone per account, so one key set per account.
- **Delivery** — encrypted messages held per recipient until their phone acknowledges them, then deleted; 30 days at the latest.
- **Media** — encrypted blobs in R2, deleted once every recipient has downloaded them; 30 days at the latest.
- **Receipts and typing** — delivered and read receipts, and typing signals, over the gateway WebSocket.
- **History** — each user's own copy of their chats, sealed on the phone under their history key, so a new phone can restore it. The server stores only ciphertext.
- **History-key backup** — the history key sealed under a passkey (or a generated recovery key), for when the old phone is gone.

### Encryption

Signal-style, done entirely on the phone with libsignal (or an equivalent implementation of the same protocols). The backend stores and forwards bytes it can't read.

| Part | Protocol | What the server sees |
| --- | --- | --- |
| Starting a 1:1 session | X3DH: the sender fetches the recipient's key bundle (M3) | Public keys only |
| 1:1 messages | Double ratchet | Ciphertext, sender, recipient, time, size |
| Group messages | Sender keys: each member sends their sender key to every other member once, over 1:1 sessions, then encrypts each group message once | One ciphertext per message, fanned out to members |
| Media | A random AES-256 key per file; the key and hash travel inside the encrypted message | An encrypted blob and its size |
| Group name and avatar | AES-256-GCM under a group metadata key, which phones share with the sender keys | A sealed blob and its version |
| History | AES-256-GCM under the user's history key (below) | Ciphertext per message, filed by chat |

**Metadata the server does see**, because it routes messages: who is in which chat, who sent a message to which chat and when, sizes and receipts. Not group names, and push notifications never carry message content.

**Group membership changes:** when a member leaves or is removed, every remaining member's phone makes a new sender key and sends it to the others before its next group message, so the person who left can't read what comes after. A new member gets current sender keys and nothing older. Messaging tells phones when to rotate through the `member.removed` and `member.added` frames; it never handles the keys.

**Group metadata key:** made by the creator's phone and sent to members inside `SENDER_KEY` messages. When a member is removed, the phone of the admin who removed them (or, on a leave, the owner's phone) makes a new metadata key, re-seals the name and avatar (M8) and sends the key to the remaining members. A wallet treasury for a chama keeps its own name in the wallet module if it needs one shown on statements.

**Key changes:** when a person's phone is replaced (`identity.DeviceReplaced`), their old keys are deleted, everyone who shares a chat with them gets `messaging.keys.changed`, and their phones show "Amina's security code changed". Sessions restart with the new bundle. Messages that were waiting for the old phone can't be decrypted by the new one, so they're dropped and each sender gets `messaging.message.undeliverable` and resends from its own copy.

**Identity key upload is device-signed.** M1 (both routes) must carry a signature from the phone's hardware-backed device key, checked through `identity.DeviceSignatures`. A stolen access token alone can't swap someone's keys and read their next messages. The proof travels in two headers, never in the body (decision 2026-10-25):

- `X-Device-Timestamp`: ISO-8601 UTC, accepted within ±5 minutes of the server clock (as identity's device proofs).
- `X-Device-Signature`: base64 signature by the device key over the UTF-8 bytes of `<X-Device-Timestamp>` + `"\n"` + lowercase hex SHA-256 of the **exact request body bytes** as sent.

The server hashes the raw bytes it received (after any content decoding; phones send key uploads uncompressed), so nothing is re-encoded or canonicalised, and the timestamp is covered by the signature.

### History and the history key

The double ratchet throws away each message key once it's used, so the delivered ciphertext can't be read again later, even by the recipient. History is therefore a second copy the phone writes for itself:

1. On first launch the phone makes a random 32-byte **history key** (HK). It never leaves the phone in the clear.
2. After the phone decrypts (or sends) a message, it seals the message under HK and uploads it in batches to its own history (M18). The delivery copy is then acknowledged and deleted.
3. On a new phone, the user brings HK across in one of two ways and the app downloads and decrypts the history chat by chat (M19).

**Bringing HK to a new phone** (decision 2026-10-25: QR handoff first, backup as the fallback):

- **(b) QR handoff, both phones present, no server involved.** The new phone shows a QR code holding a one-time X25519 public key. The old phone scans it, seals HK to that key, and shows the sealed HK as a QR code. The new phone scans that. The handoff works even after the old phone's session has been ended by I6, because it only reads data already on the phone.
- **(a) Backup, sealed under a passkey or a recovery key.** No user-chosen passphrase, so there is nothing to guess from a copy of the database.
  - **Passkey (the default).** When the user turns on backup, the app creates a passkey for `circaso.com` with the WebAuthn PRF extension (Credential Manager on Android, AuthenticationServices on iOS). The PRF output for a random salt is a 32-byte secret only that passkey can produce; the phone derives a key from it with HKDF, seals HK under it and uploads the sealed HK with the credential id and salt (M21). On a new phone, the passkey arrives through iCloud Keychain or Google Password Manager. The app fetches the backup, asks the passkey for the same PRF output (one Face ID or fingerprint prompt) and opens HK on the phone. The server never sees the PRF output or HK, and doesn't verify the passkey: it isn't used to sign in.
  - **Recovery key (the fallback).** A random 256-bit key shown once as 64 characters, for phones without passkey PRF support (older Android, phones without Google services) and for moves between Android and iPhone, where passkeys don't sync unless the user has a cross-platform password manager. The user writes it down or saves it to a password manager.
  - A backup can hold both wraps of the same HK, so a user can set up a passkey and a recovery key. Losing every wrap and the old phone means losing history; new messages still arrive.

Media is not part of history: files are deleted from R2 after delivery, so a restored chat shows earlier media as "No longer available". Messages that arrive after the new phone is registered are delivered normally.

### Contract (`messaging:api`)

```kotlin
interface ChatDirectory {            // read by wallet (pay-in-chat) and calls (group calls) later
    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean
    suspend fun members(chatId: ChatId): Set<UserId>
}
```

Messaging also implements `RealtimeReplay` and the new `RealtimeInbound` (below), and supplies the push wording for its frames.

### Depends on

`shared`, `infrastructure:api`, `identity:api` (`DeviceSignatures`, account status) and `users:api` (`ReachPolicy`, `Profiles`). Reach is checked when a 1:1 chat is opened, on every 1:1 send (cached by users), and when someone adds a person to a group. Inside a group, members message each other through the group without needing a contact connection, which is the "separate rule owned by messaging" that users leaves to this module.

### Integrations used

- **R2** through the existing presigned upload and download (private bucket, prefix `chat-media/`).
- **Push** through `PushGateway`, data-only: the push holds `chatId` and `messageId` and wakes the app, which fetches (M13), decrypts and shows the notification locally. On iOS this runs in a Notification Service Extension. If decryption can't run, the phone shows "New message" with the sender's name.

### New in `shared` and the gateway

- **`RealtimeInbound`**: the socket hub ignores frames sent by the phone today (`SocketHub`: "client frame ignored"). Messaging needs a contract in `shared`, `suspend fun receive(userId: UserId, deviceId: DeviceId, frame: ClientFrame): Resource<Unit>` (not `Principal`, which lives in `identity:api`; decision 2026-10-25), registered per module like `RealtimeModule`, so the hub can route `messaging.*` frames from the phone. Only acknowledgements, read receipts and typing come this way (Architecture → Realtime: commands stay on HTTP).
- **Push wording per module**: messaging's entries go in `GatewayRealtime.pushFor`. `message.new` frame data carries `pushable`; `member.added` carries the added `userIds`.
- **Data-only pushes** (decision 2026-10-25): `PushMessage` in `integrations:api` gets a data-only option. FCM sends a data message on Android and, on iOS, an alert with `mutable-content` and the fallback text, so the Notification Service Extension can decrypt and replace it. Built in MS4.
- **Phone frame wire shape:** `{"module": "messaging", "type": "ack", "data": {…}}`, the same fields as the server envelope without `id`, `seq` and `ts`.
- **Fan-out to many users at once**: `Realtime.deliver` handles one user at a time, which means one `rt:conn` lookup and one publish per member: 999 of each for a message in a full group. Messaging needs `Realtime.deliverMany(userIds, envelope)`, built on Redis pub/sub so it works the same on standalone Redis and on a cluster (decision 2026-10-25): the gateway publishes the frame once, with the list of users, on the broadcast channel `rt:fanout`, which every node subscribes to; each node writes it to the sockets it holds for those users, from its own in-memory connection table, with no Redis read. Only when the frame type is push-worthy (`message.new`, `member.added`) does the gateway find the members with no socket, with single-key `GET`s of `rt:conn:{userId}` sent as one pipeline (no multi-key command, so it works across cluster slots), and hands them to push in batches of 100. Receipts and typing cost one publish and no reads.

## Endpoints

Paths are relative to `https://api.circaso.com/v1`, all with the `app` scope. Every POST that creates something takes an `Idempotency-Key`. Each route goes route → controller → service method → use case.

| # | Route | Use case (domain) | Does |
| --- | --- | --- | --- |
| M1 | `PUT /keys` / `POST /keys/one-time` | `PublishKeys` / `AddOneTimeKeys` | Uploads identity key, signed prekey and up to 100 one-time prekeys (device-signed); tops up one-time prekeys |
| M2 | `GET /keys/me` | `GetKeyStatus` | How many one-time prekeys are left and when the signed prekey was set, so the phone knows to top up or rotate |
| M3 | `GET /keys/{userId}` | `FetchKeyBundle` | The person's bundle; uses up one one-time prekey; reach or a shared group required |
| M4 | `POST /chats/direct` | `OpenDirectChat` | Opens the 1:1 chat with a person, or returns the existing one; reach required |
| M5 | `POST /chats/groups` | `CreateGroup` | Creates a group with a name and first members (each must be reachable by the creator) |
| M6 | `GET /chats` | `ListChats` | Every chat the user is in, with members' public profiles and the last message id (null after 30 days without a message); the app sorts and counts unread |
| M7 | `GET /chats/{chatId}` | `GetChat` | One chat with members and roles |
| M8 | `PATCH /chats/{chatId}` | `UpdateGroup` | Sealed name and avatar, who may add members (admins only); optimistic lock on `version` |
| M9 | `POST /chats/{chatId}/members` | `AddMembers` | Adds members (admins, or anyone if the group allows); each must be reachable by whoever adds them; 1,000 cap |
| M10 | `DELETE /chats/{chatId}/members/{userId}` | `RemoveMember` / `LeaveGroup` | An admin removes someone, or a member removes themselves (leave) |
| M11 | `PUT /chats/{chatId}/members/{userId}/role` | `SetRole` | Makes someone an admin or a member; the owner can hand over ownership |
| M12 | `POST /chats/{chatId}/messages` | `SendMessage` | Sends one encrypted message; returns its server id |
| M13 | `GET /inbox` / `POST /inbox/ack` | `ReadInbox` / `AckDelivered` | Waiting messages, oldest first, paged; acknowledges delivery when the socket isn't open (push wake) |
| M14 | `DELETE /chats/{chatId}/messages/{messageId}` | `DeleteForEveryone` | Sender only, within 48 hours: undelivered copies are dropped and members get `message.deleted` |
| M15 | `POST /media/upload-url` / `POST /media/{mediaId}/complete` | `PrepareMediaUpload` / `CompleteMediaUpload` | Presigned R2 upload for one encrypted file (multipart over 50 MB), with its recipients fixed at this point; completes a multipart upload |
| M16 | `GET /media/{mediaId}/download-url` | `PrepareMediaDownload` | Presigned download for a recipient who hasn't acknowledged it yet |
| M17 | `POST /media/{mediaId}/downloaded` | `AckMediaDownloaded` | The recipient has the file; the last acknowledgement deletes it |
| M18 | `POST /history` | `AppendHistory` | Up to 200 sealed history entries |
| M19 | `GET /history/{chatId}` / `DELETE /history/{chatId}` | `ReadHistory` / `ClearHistory` | Pages one chat's history, newest first; "clear chat" for this user only |
| M20 | `DELETE /history/{chatId}/{messageId}` | `DeleteFromHistory` | "Delete for me" of one message |
| M21 | `PUT /history-key/backup` / `GET` / `DELETE` | `SaveKeyBackup` / `GetKeyBackup` / `RemoveKeyBackup` | The history key sealed under a passkey and/or a recovery key; GET is rate-limited |
| M22 | `PATCH /messaging/settings` | `UpdateSettings` | Read receipts and typing indicators on or off |

### Agent routes (read-only, for Asha)

`GET /v1/agent/messaging/chats`: the user's chats with ids, kind and member profiles. Group names are sealed, so turning "the chama chat" into a `chatId` is a device tool (`findChat(name)`, answered by the phone like `searchChats`). Messaging has no plaintext to give her; content and group names only come through the phone (Assistant → Reading encrypted chats).

### M1 Publish keys

```json
// PUT /keys ← request
{
  "identityKey": "BcF2…",                                  // base64 Curve25519 public key
  "signedPreKey": { "keyId": 7, "publicKey": "BXa…", "signature": "kq3…" },   // signed by the identity key
  "oneTimePreKeys": [ { "keyId": 101, "publicKey": "BQm…" } ]                // up to 100
}
// headers: X-Device-Timestamp: 2026-10-25T09:00:00Z
//          X-Device-Signature: MEUC…   (over "2026-10-25T09:00:00Z\n" + hex(SHA-256(body bytes)))
// → data { "oneTimePreKeysLeft": 100 }
```

`PUT /keys` replaces the whole set: one-time prekeys from an earlier upload are deleted, because after a reinstall they may belong to keys the phone no longer holds. `POST /keys/one-time` adds to the set, needs a bundle published by the same device, and is refused whole with `messaging.too_many_prekeys` if more than 100 would be kept, or with `messaging.prekey_id_exists` if it resends a key id already stored (replacing it could let a concurrent fetch hand out the old public key). A top-up checks and inserts in one transaction under the bundle's row lock, so the 100 cap holds for concurrent top-ups too, and a refused one is rolled back whole: nothing of it was ever stored or handed out, so the phone can drop that request's private keys at once (PG5 relaxed the earlier rule that kept them until the next `PUT /keys`). `PUT /keys` deletes the old set and inserts the new one in one transaction. M2 and the top-up answer `messaging.keys_not_found` when the stored bundle was published by another device, so a new phone publishes its own set. The phone rotates the signed prekey every 7 days and tops up one-time prekeys when M2 shows fewer than 20. When none are left, M3 returns a bundle without one, which X3DH allows.

### M3 Fetch a key bundle

```json
// GET /keys/0193… → data
{ "userId": "0193…", "identityKey": "BcF2…", "signedPreKey": { "keyId": 7, "publicKey": "BXa…", "signature": "kq3…" }, "oneTimePreKey": { "keyId": 101, "publicKey": "BQm…" } }
```

Rate-limited to 60 bundles per caller per hour (`msg:bundle-rate:{callerId}`), so nobody can drain someone's one-time prekeys; a per-target limit would let anyone block a person's key fetches.

### M4–M5 Chats

```json
// POST /chats/direct ← { "userId": "0193…" }
// → data: Chat, "created": true on first open

// POST /chats/groups ← { "sealedMeta": "AAEC…", "memberIds": ["0193…", "0193…"] }
// → data: Chat; anyone who can't be reached is left out and listed in "notAdded" (no reason given, as in users)

// Chat
{
  "chatId": "0193…", "kind": "GROUP",
  "sealedMeta": "AAEC…",                         // name and avatar (its R2 key and file key), under the group metadata key; null for direct
  "metaVersion": 3,
  "members": [ { "user": PublicProfile, "role": "OWNER", "joinedAt": "2026-10-25T09:00:00Z" } ],
  "settings": { "membersCanAdd": false }, "lastMessageId": "0193…", "version": 1
}
```

`lastMessageId` is the newest message the server still holds a record of, so it is `null` for a chat with no message in the last 30 days; the app then keeps the last id it saw.

A group's id is derived from the creator and the request's `Idempotency-Key`, so a retried create resumes the same group; a resume only completes a create that nothing else has changed since (otherwise it returns the group as it is), so it never re-adds someone who left. A direct chat is claimed through the unique index on the pair in `chats` (`insertIgnore`; the loser reads the winner's chat), so two people opening a chat at the same moment get the same one.

**Leaving and removing (M10)** is one transaction: the member row (which is also the chat-list line) is taken with `deleteReturning`, and only the caller that gets it decrements `member_count`, picks the successor when the owner left, deletes the group when it is empty and writes `MemberRemoved` (event id from chat, user and `joinedAt`) to the outbox; it sends `member.removed` after the commit. A caller that gets no row changed nothing, so either party can retry and a removal is never left half done. **Roles (M11, succession)** are written with `updateReturning … WHERE chat_id = ? AND user_id = ?`, so a role write never brings back a member who left; the chat's `owner_id` is the authority on who owns it.

A group avatar is an encrypted file uploaded through M15 with `"purpose": "GROUP_AVATAR"`: it has no recipients and is never deleted on download, only when the avatar changes or the group is deleted. A phone without the current metadata key shows "Group" and a placeholder until it gets one.

### M12 Send a message

```json
// POST /chats/{chatId}/messages ← request
{
  "clientMessageId": "0193…",                    // made on the phone; a retry with the same id returns the first result
  "kind": "MESSAGE",                             // MESSAGE | SENDER_KEY | CONTROL
  "ciphertext": "MwohB…",                        // group: one ciphertext for every member
  "recipients": [ { "userId": "0193…", "ciphertext": "MwohB…" } ],   // direct, or SENDER_KEY to some members: one per recipient
  "mediaIds": ["0193…"],                         // files this message refers to, from M15
  "pushable": true                               // false for SENDER_KEY and CONTROL: nobody is woken for them
}
// → data { "messageId": "0193…", "sentAt": "2026-10-25T09:01:02Z" }
```

- `messageId` comes from `OrderedIds`, so a chat lists in order.
- A direct send checks reach both ways; a block answers `users.not_reachable`. A group send checks the sender is a member.
- `SENDER_KEY` messages carry a member's new sender key to chosen members over their 1:1 sessions, filed under the group so the phone knows where they belong. `CONTROL` carries encrypted chat events phones agree on among themselves (reactions, edits later).
- Ciphertext is capped at 64 KB; media goes through M15.

### M13 Inbox

```json
// GET /inbox?after=0193…&limit=100 → data
{ "messages": [ { "messageId": "0193…", "chatId": "0193…", "senderId": "0193…", "kind": "MESSAGE", "ciphertext": "MwohB…", "mediaIds": [], "sentAt": "…" } ], "next": "0193…" }

// POST /inbox/ack ← { "messageIds": ["0193…"] }   → data { "acked": 1 }
```

When the socket is open, delivery arrives as `messaging.message.new` frames and acknowledgements go back over the socket; the inbox is for catching up and for push wakes.

### M15–M17 Media

```json
// POST /media/upload-url ← { "chatId": "0193…", "sizeBytes": 2400000, "purpose": "MESSAGE" }   // MESSAGE | GROUP_AVATAR
// → data { "mediaId": "0193…", "uploadUrl": "https://…r2…", "expiresInSeconds": 300 }

// over 50 MB → data { "mediaId": "0193…", "partSizeBytes": 16777216, "parts": [ { "number": 1, "uploadUrl": "https://…r2…" } ], "expiresInSeconds": 3600 }
// POST /media/{mediaId}/complete ← { "parts": [ { "number": 1, "etag": "…" } ] }   → data { "complete": true }

// GET /media/{mediaId}/download-url → data { "downloadUrl": "https://…r2…", "expiresInSeconds": 300 }
// POST /media/{mediaId}/downloaded ← {}   → data { "deleted": false }
```

Files are up to 500 MB, always `application/octet-stream` because they're encrypted (the real type is inside the message). Files over 50 MB upload in 16 MB parts (R2 multipart), so a dropped mobile connection resumes from the last part instead of starting again; downloads resume with HTTP range requests. Recipients are fixed at upload: the chat's members at that moment, less the sender. The file is deleted from R2 when the last recipient acknowledges it, or after 30 days. Someone who joins a group later can't download older files.

### M18–M19 History

```json
// POST /history ← { "entries": [ { "chatId": "0193…", "messageId": "0193…", "sealed": "AAEC…" } ] }   → data { "stored": 1 }
// GET /history/{chatId}?before=0193…&limit=200 → data { "entries": [ { "messageId": "0193…", "sealed": "AAEC…" } ], "next": "0193…" }
```

`sealed` is AES-256-GCM under HK, up to 64 KB, and holds the plaintext, sender and time. Only the owner can read or delete their history. History is kept until the user clears it or the account is deleted.

### M21 History-key backup

```json
// PUT /history-key/backup ← request: one wrap per way of opening it
{
  "wraps": [
    { "kind": "PASSKEY", "credentialId": "kTq…", "prfSalt": "x8f…", "sealed": "AAEC…" },
    { "kind": "RECOVERY_KEY", "sealed": "AAED…" }
  ],
  "version": 1
}
// GET /history-key/backup → the same shape; 5 per user per day
```

Each `sealed` is HK under AES-256-GCM with a key from HKDF over the PRF output or the recovery key. Turning on a new passkey or recovery key replaces the backup with the new set of wraps.

## Socket frames

**Server to phone** (module `messaging`, through `Realtime.deliver`):

| Type | Goes to | Push if offline | Replayed |
| --- | --- | --- | --- |
| `message.new` | Each recipient (each member except the sender, for groups) | Yes, data-only, if `pushable` | No; the inbox is the catch-up |
| `message.deleted` | Chat members | No | Yes |
| `message.undeliverable` | The sender, when a recipient's phone was replaced before delivery | No | Yes |
| `receipt` | The sender: `delivered` / `read`, by `messageId` and `userId`; in groups batched every 5 seconds | No | Yes |
| `typing` | Other members while the chat is open; groups up to 50 members only | No | No |
| `chat.updated` | Members: sealed name or avatar, or settings changed | No | Yes |
| `member.added` / `member.removed` | Members, and the person themselves; tells phones to send or rotate sender keys | Yes for the person added | Yes |
| `keys.changed` | Everyone who shares a chat with the person | No | Yes |

**Frame data** (the gateway's push logic reads `pushable` and `userId`):

| Type | `data` |
| --- | --- |
| `message.new` | `messageId`, `chatId`, `senderId`, `kind`, `pushable` (missing means `false`), `sentAt`, `mediaIds`, `ciphertext` (null when over `MESSAGING_FRAME_CIPHERTEXT_MAX_CHARS`, 16 K base64 characters); the ciphertext is fetched from the inbox (M13) when it doesn't fit in the frame |
| `message.deleted` | `chatId`, `messageId` |
| `message.undeliverable` | `chatId`, `messageId`, `recipientId` |
| `receipt` | `chatId`, `receipts: [{ messageId, userId, status: DELIVERED \| READ, at }]` |
| `typing` | `chatId`, `userId`, `on` |
| `chat.updated` | `chatId`, `metaVersion`, `version` |
| `member.added` | `chatId`, `userIds` (everyone added in that action, sorted), `byUserId`; one frame per member per action (decision 2026-10-26) |
| `member.removed` | `chatId`, `userId` (the person removed or who left), `byUserId`; one frame per person |
| `keys.changed` | `userId` |

The `member.added` push goes only to the people in `userIds`, and is ids-only once pushes are data-only (MS4): the phone words it from the sealed group name. Until then the server sends "You were added to a group", never the group name.

Group frames go out through `Realtime.deliverMany`, so a message, receipt batch or typing signal to a full group costs one publish, not one per member; only push-worthy frames also look up who is offline. Group read receipts collect in `msg:receipts:{messageId}` and are sent to the sender as one `receipt` frame every 5 seconds.

Frames marked "Replayed" are kept 7 days in the `realtime` table for `RealtimeReplay`, as in users. `message.new` isn't replayed because the inbox already holds every undelivered message for up to 30 days.

**Phone to server** (through `RealtimeInbound`):

| Frame | Data | Does |
| --- | --- | --- |
| `messaging.ack` | `messageIds` | Delivered: deletes the inbox rows, sends `receipt delivered` to the senders |
| `messaging.read` | `chatId`, `upToMessageId` | Moves the read marker, sends `receipt read` unless the reader turned read receipts off |
| `messaging.typing` | `chatId`, `on` | Relayed to the other members, nothing stored; dropped if the sender turned typing off |

## Data

PostgreSQL tables in schema `messaging` (role `messaging`, Flyway location `db/messaging`) and Redis keys under `msg:`. Foreign keys stay inside the schema.

```sql
-- V1__messaging.sql (PG5) has every table below but the media and history ones, which come with MS5 and MS6

CREATE TABLE messaging.chats (
    chat_id         uuid        PRIMARY KEY,
    kind            text        NOT NULL CHECK (kind IN ('DIRECT', 'GROUP')),
    direct_low      uuid,                               -- DIRECT: the smaller user id of the pair; NULL for groups
    direct_high     uuid,                               -- DIRECT: the larger user id of the pair; NULL for groups
    sealed_meta     text,                               -- groups: name and avatar under the group metadata key; NULL for direct
    meta_version    int         NOT NULL DEFAULT 0,
    avatar_media_id uuid,                               -- the encrypted avatar file, deleted when replaced
    settings        text        NOT NULL,               -- JSON: membersCanAdd
    member_count    int         NOT NULL,               -- changed in the same transaction as the member rows
    owner_id        uuid,                               -- groups: the owner, the authority on who owns it; NULL for direct
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,     -- optimistic lock (M8, ownership changes)
    CHECK ((kind = 'DIRECT') = (direct_low IS NOT NULL AND direct_high IS NOT NULL)),
    CHECK (direct_low < direct_high)
);
CREATE UNIQUE INDEX chats_direct_pair_key ON messaging.chats (direct_low, direct_high) WHERE kind = 'DIRECT';
                                                        -- one direct chat per pair

CREATE TABLE messaging.members (
    chat_id    uuid        NOT NULL REFERENCES messaging.chats ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,                    -- OWNER | ADMIN | MEMBER
    read_up_to uuid,                                    -- this member's read marker (messaging.read)
    joined_at  timestamptz NOT NULL,
    PRIMARY KEY (chat_id, user_id)
);
CREATE INDEX members_user_idx ON messaging.members (user_id, chat_id);   -- the chat list (M6)

CREATE TABLE messaging.key_bundles (
    user_id                  uuid        PRIMARY KEY,   -- one phone per account, so one bundle
    device_id                uuid        NOT NULL,
    identity_key             text        NOT NULL,
    signed_pre_key_id        int         NOT NULL,
    signed_pre_key           text        NOT NULL,
    signed_pre_key_signature text        NOT NULL,
    signed_pre_key_at        timestamptz NOT NULL,
    uploaded_at              timestamptz NOT NULL
);

CREATE TABLE messaging.one_time_prekeys (
    user_id     uuid        NOT NULL,
    key_id      int         NOT NULL,
    device_id   uuid        NOT NULL,                   -- the phone that uploaded it
    public_key  text        NOT NULL,
    uploaded_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, key_id)                       -- a resent key id is messaging.prekey_id_exists
);

CREATE TABLE messaging.inbox (
    user_id    uuid        NOT NULL,                    -- the recipient
    message_id uuid        NOT NULL,                    -- OrderedIds; read oldest first
    chat_id    uuid        NOT NULL,
    sender_id  uuid        NOT NULL,
    kind       text        NOT NULL,                    -- MESSAGE | SENDER_KEY | CONTROL
    ciphertext text,                                    -- direct and SENDER_KEY: this recipient's own copy;
                                                        -- NULL for a group MESSAGE, whose body is in group_messages
    media_ids  uuid[]      NOT NULL DEFAULT '{}',
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (user_id, message_id)                   -- M13 pages WHERE user_id = ? AND message_id > ? ORDER BY message_id
) PARTITION BY RANGE (message_id);                      -- daily partitions, dropped after 30 days

CREATE TABLE messaging.group_messages (                 -- one ciphertext per group message, not one per member
    chat_id    uuid        NOT NULL,
    message_id uuid        NOT NULL,
    sender_id  uuid        NOT NULL,
    ciphertext text        NOT NULL,
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (chat_id, message_id)
) PARTITION BY RANGE (message_id);                      -- daily partitions, dropped after 30 days

CREATE TABLE messaging.message_records (                -- who sent what, for M14 and group read receipts after delivery
    message_id uuid        NOT NULL,
    chat_id    uuid        NOT NULL,
    sender_id  uuid        NOT NULL,
    kind       text        NOT NULL,
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (message_id)
) PARTITION BY RANGE (message_id);                      -- daily partitions, dropped after 30 days
CREATE INDEX message_records_chat_idx ON messaging.message_records (chat_id, message_id DESC);
                                                        -- M6: the newest message per chat (LATERAL … LIMIT 1)

CREATE TABLE messaging.client_messages (                -- idempotent send
    sender_id         uuid        NOT NULL,
    client_message_id uuid        NOT NULL,
    message_id        uuid        NOT NULL,
    expires_at        timestamptz NOT NULL,             -- 1 day after the send
    PRIMARY KEY (sender_id, client_message_id)
);
CREATE INDEX client_messages_expires_at_idx ON messaging.client_messages (expires_at);

CREATE TABLE messaging.media (
    media_id            uuid        PRIMARY KEY,
    chat_id             uuid        NOT NULL,
    uploader_id         uuid        NOT NULL,
    purpose             text        NOT NULL,           -- MESSAGE | GROUP_AVATAR
    object_key          text        NOT NULL,
    size_bytes          bigint      NOT NULL,
    multipart_upload_id text,                           -- set while a multipart upload is open
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz                     -- created_at + 30 days for MESSAGE; NULL for GROUP_AVATAR
);
CREATE INDEX media_expires_at_idx ON messaging.media (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE messaging.media_pending (                  -- recipients still to download; none left → delete the object
    media_id uuid NOT NULL REFERENCES messaging.media ON DELETE CASCADE,
    user_id  uuid NOT NULL,
    PRIMARY KEY (media_id, user_id)
);

CREATE TABLE messaging.history (
    user_id    uuid NOT NULL,
    chat_id    uuid NOT NULL,
    message_id uuid NOT NULL,
    sealed     text NOT NULL,
    PRIMARY KEY (user_id, chat_id, message_id)          -- M19 pages newest first (message_id DESC); "clear chat" deletes (user_id, chat_id)
);

CREATE TABLE messaging.history_key_backups (
    user_id    uuid        PRIMARY KEY,
    wraps      text        NOT NULL,                    -- JSON: kind, credentialId, prfSalt, sealed per wrap
    version    int         NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE messaging.settings (
    user_id       uuid    PRIMARY KEY,
    read_receipts boolean NOT NULL DEFAULT true,
    typing        boolean NOT NULL DEFAULT true
);

CREATE TABLE messaging.realtime (…);                    -- the same shape as users' realtime: daily partitions, 7 days
CREATE TABLE messaging.outbox (…);                      -- the shared outbox DDL (architecture.md → Durable events):
                                                        -- daily partitions, 7 days
```

| Table | Expiry | How the race is settled |
| --- | --- | --- |
| `chats` | — | M4 inserts a direct chat with `insertIgnore`; `insertedCount = 0` means the other person opened it at the same moment, and the winner's chat is read, so both get the same one. M8 and ownership changes use the `version` check |
| `members` | — | **Lock order:** every membership transaction (add, remove or leave, role, ownership change, group delete) locks the `chats` row first, with `FOR UPDATE` or the conditional update below, and only then touches `members`, so two membership changes to one group run one after the other and the successor is chosen from a settled member list. An add reserves its places with `UPDATE chats SET member_count = member_count + :n WHERE chat_id = ? AND member_count + :n <= 1000 RETURNING …` (`updateReturning`); 0 rows is `messaging.group_full`. Removal and leave take the member row with `deleteReturning` (below); role writes use `updateReturning` |
| `key_bundles` | — | `PUT /keys` replaces the bundle and the one-time prekeys in one transaction |
| `one_time_prekeys` | — | M3 takes one key with `deleteReturning` over the lowest `key_id` (`FOR UPDATE SKIP LOCKED`), so two fetches never get the same key; the pick is a scalar subquery (`key_id = (SELECT …)`), which runs once, never `IN (…)`, which PostgreSQL may re-run per scanned row and so take several keys. A top-up inserts in one transaction and is refused whole (rolled back) on a resent key id or more than 100 kept. On `DeviceReplaced`: `DELETE … WHERE user_id = ? AND device_id = ? AND uploaded_at < :occurredAt` |
| `inbox` | Daily partitions, 30 days | An ack is `DELETE … WHERE user_id = ? AND message_id = ? RETURNING …`: exactly one ack gets the row and sends the `delivered` receipt |
| `group_messages` | Daily partitions, 30 days | Deleted on the last ack (`msg:pending:{messageId}` counts down) |
| `message_records` | Daily partitions, 30 days | — |
| `client_messages` | `expires_at`, 1 day | M12 inserts with `insertIgnore` in the sending transaction; `insertedCount = 0` means a retry, which returns the first result. An expired row the purge hasn't reached yet is taken over (`ON CONFLICT … DO UPDATE … WHERE expires_at <= :now`), so it never answers a send a day later |
| `media` | `expires_at`, 30 days (none for group avatars) | **Lock order:** every path that touches both tables (complete, M17, purge) locks the `media` row (`FOR UPDATE`) first, then `media_pending`, so exactly one ack sees that none are left |
| `media_pending` | Deleted with its `media` row | M17 takes the caller's row with `deleteReturning` |
| `history` | — (until cleared or the account is deleted) | — |
| `history_key_backups` | — | — |
| `settings` | — | — |
| `realtime`, `outbox` | Daily partitions, 7 days | — |

Every read of a table with `expires_at` filters `expires_at > now()`; `messaging.purge-expired` deletes the expired rows and drops the expired partitions.

**Deleting what's been delivered**

- Direct messages: `messaging.ack` deletes the recipient's `inbox` row; nothing else holds the ciphertext.
- Group messages: each member's `inbox` row is deleted on ack. The shared `group_messages` row is deleted when the last member acks (`msg:pending:{messageId}` in Redis counts down), and dropping the day's partition after 30 days catches anything Redis loses.
- Media: each recipient's `media_pending` row is deleted on M17; when none are left, the `media` row is deleted and the R2 object after the commit. A daily job (`MediaSweep`) deletes R2 objects under `chat-media/` whose `media` row is gone or expired, because R2 objects have no TTL of their own (decision 2026-10-25). It walks the bucket listing, so it needs no table scan, and it never touches a `GROUP_AVATAR` object that still has a row.

**Fan-out:** a group message to 1,000 members writes one `group_messages` row, one `message_records` row and 999 small `inbox` rows (no ciphertext in them) with one `batchInsert` in the sending transaction, together with the `client_messages` row, then sends the frames after the commit. Sending answers once the transaction has committed; frames and pushes go out after.

**Redis (`msg:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `msg:pending:{messageId}` | count of members yet to ack | 30 days | Deletes the group body on the last ack |
| `msg:members:{chatId}` | userIds, one comma-separated value | 10 min; a change writes a 5 s marker and readers fill only with set-if-absent | Membership for sends and fan-out without reading `members` each time |
| `msg:receipts:{messageId}` | hash `<userId>:delivered` / `<userId>:read` → `1` | 10 s | Group receipts collected for the 5-second batch to the sender; one field per status, so a delivered receipt never replaces a read one; the batch takes and clears the hash in one step (`RedisScope.hashTake`) and sends each person's furthest status |
| `msg:receipts-due:{messageId}` | `1` | 5 s | Claims the next receipt batch for one node; a batched receipt's `at` is when the batch is sent |
| `msg:bundle-rate:{userId}` | counter | 1 h | 60 key bundles per user per hour |
| `msg:hk-fetch:{userId}` | counter | 24 h | 5 backup fetches per user per day |
| `msg:typing:{chatId}:{userId}` | `1` | 6 s | Drops repeated typing frames |
| `msg:seq:{userId}` | counter | 8 days, renewed on use | Next realtime seq for messaging frames |

## Events

**Consumed**

| Stream (group `messaging`) | Event | What messaging does |
| --- | --- | --- |
| `events:identity` | `identity.DeviceReplaced` | Delete the old key bundle and one-time prekeys; drop inbox rows encrypted for the old phone and send `message.undeliverable` to their senders; send `keys.changed` |
| `events:identity` | `identity.AccountStatusChanged` | Suspended accounts can't send; a restored account can again |
| `events:identity` | `identity.AccountDeleted` | Remove the user from every group (as a leave), delete their keys, inbox, history, backup, settings and chat list rows; direct chats become read-only for the other person |
| `events:users` | `users.UserBlocked` / `users.UserUnblocked` | Nothing to store: direct sends check reach each time; clear any cached reach the module holds |
| `events:users` | `users.ProfileUpdated` | Nothing: chat lists read profiles through `users.Profiles` (cached by users) when served; no local copy (decision 2026-10-25) |

**Published** (stream `events:messaging`)

| Event | `data` | Consumers |
| --- | --- | --- |
| `messaging.ChatCreated` v1 | `chatId`, `kind`, `memberIds` (direct) or `createdBy` | later wallet and calls |
| `messaging.MemberAdded` v1 / `messaging.MemberRemoved` v1 | `chatId`, `userId`, `byUserId` | later wallet (chama treasuries), calls |
| `messaging.GroupDeleted` v1 | `chatId` | later wallet, calls |

Message traffic is not published as events: it goes straight to the recipient's inbox and socket, and nobody else needs it.

## Error codes

Messaging's own codes; `users.not_reachable` is returned as-is when reach fails.

| Code | Kind → status | When |
| --- | --- | --- |
| `messaging.not_a_group` | INVALID → 400 | Leaving a direct chat, or removing someone from one |
| `messaging.chat_not_found` | NOT_FOUND → 404 | No such chat, or the user isn't in it (the two look the same) |
| `messaging.not_admin` | FORBIDDEN → 403 | A member-only user tries an admin action |
| `messaging.group_full` | CONFLICT → 409 | Adding would pass 1,000 members |
| `messaging.group_invalid` | INVALID → 400 | Sealed name and avatar missing or over 4 KB |
| `messaging.version_conflict` | CONFLICT → 409 | Group changed since it was read |
| `messaging.message_too_large` | INVALID → 400 | Ciphertext over 64 KB |
| `messaging.recipients_mismatch` | INVALID → 400 | A direct or `SENDER_KEY` send whose recipients aren't members of the chat |
| `messaging.delete_window_passed` | CONFLICT → 409 | Delete for everyone after 48 hours, or by someone other than the sender |
| `messaging.keys_not_found` | NOT_FOUND → 404 | The person has no key bundle yet, or (M2, top-up) the stored bundle belongs to another device |
| `messaging.prekey_id_exists` | CONFLICT → 409 | A top-up resends a one-time prekey id that is already stored |
| `messaging.too_many_prekeys` | INVALID → 400 | More than 100 one-time prekeys in an upload, or kept after a top-up |
| `messaging.keys_proof_invalid` | FORBIDDEN → 403 | M1 without valid `X-Device-Timestamp` / `X-Device-Signature` headers |
| `messaging.media_invalid` | INVALID → 400 | Over 500 MB, upload missing, or a multipart upload with missing parts |
| `messaging.media_gone` | NOT_FOUND → 404 | Already downloaded by everyone, expired, or not for this user |
| `messaging.history_too_large` | INVALID → 400 | Over 200 entries, or an entry over 64 KB |
| `messaging.backup_not_found` | NOT_FOUND → 404 | No history-key backup |
| `messaging.rate_limited` | RATE_LIMITED → 429 | Key-bundle or backup-fetch limit reached |

## Not in v1

Linked devices; editing messages; message reactions as anything other than `CONTROL` messages; disappearing messages; group invite links; media in history; voice and video calls (the calls module).

## Open questions

These also go into the open-question list in `decisions.md`.

- **Passkey domain setup** (how-to in `docs/passkeys.md`). The passkey's relying party is `circaso.com`, which needs `/.well-known/assetlinks.json` (Android) and `/.well-known/apple-app-site-association` (iOS) served there, with the app's signing fingerprint and team id.
