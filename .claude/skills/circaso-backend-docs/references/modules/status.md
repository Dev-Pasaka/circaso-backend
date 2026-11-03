# status

Built 2026-11-03 (Spaces phase 1). **Moments**: WhatsApp-style statuses that live 24 hours, end to end encrypted on the poster's phone with libsignal's group machinery (one moments sender key per poster, the body encrypted once, a pairwise key copy for audience members who don't have the current distribution). The server stores ciphertext, the audience, view receipts and expiry; nothing readable. Schema `status`, Redis prefix `sts:`, no stream of its own (it publishes no events), frames module `status`. The contract the apps build against is the Moments API (`CircasoApp/.claude/skills/circaso-mobile/references/moments-api.md`, 2026-11-03); `docs/mobile-integration.md` → Moments is the copy that wins.

## Overview

### Owns
- Moments: one post (text, photo, video or voice) as ciphertext, live 24 hours from `postedAt`.
- Each moment's audience: who it was shown to, their key copy, their view (`viewedAt`, and `reaction`: one emoji or none, with `reacted` = `reaction != null`).
- Moment files: encrypted blobs in object storage under the `status` prefix (`media/{posterId}/{mediaId}`), the same pattern as messaging's attachments, authorised by "the poster, or in the audience of a live moment carrying it".
- Its realtime frames (`status/*`) and their 7-day replay.

### Contract (`status:api`)
- `MomentId`.
- Frames (`StatusFrames`): `status.posted` (`MomentPostedFrame {momentId, posterId}`) to the audience, `status.deleted` (`MomentDeletedFrame {momentId, posterId}`) to the audience, `status.viewed` (`MomentViewedFrame {momentId, viewerId, viewedAt, reacted, reaction, viewerName}`) to the poster. `viewerName` is set only when the frame adds or changes a reaction to an emoji, and the gateway pushes exactly those to an offline poster: "Name reacted 😂 to your moment", `kind` `moment.loved` (the name kept from the heart-only first version). Plain views and cleared reactions never push.
- Nothing else asks status anything yet.

### Integrations used
- `ObjectStore` (infrastructure) for pre-signed PUT and GET of the ciphertext; no vendor contract.

### Depends on
- `users:api` `Profiles` (optional): the viewer's display name for a reaction frame; without it the frame goes unnamed and is not pushed.
- `users:api` `ReachPolicy`: an audience member is kept only when `canReach(poster, member)` and `canReach(member, poster)` (users' rule: the other is active, no block either way, and each has the other as a contact), so the server never sees an address book and a block is never revealed. Reads re-check `blockedEitherWay` so a block made after posting hides the moment.
- `identity:api` events: `AccountStatusChanged` to `DELETED` and `AccountDeleted` remove the person's moments, files and audience rows at once.

## Endpoints

| ID | Route | Scope | Use case | Notes |
| --- | --- | --- | --- | --- |
| ST1 | `POST /moments/media` `{sizeBytes, kind}` | app | `PrepareMomentMedia` | `kind` `image` \| `video` \| `voice`; 1 B to 32 MiB of ciphertext; `{mediaId, uploadUrl, uploadHeaders: {Content-Type}, expiresAt}` (15 min); `PENDING` row expiring an hour later unless attached |
| ST2 | `POST /moments/media/{mediaId}/complete` | app | `CompleteMomentMedia` | the poster only; the object must be there at exactly ST1's size (`status.media_invalid`); repeat answers the same |
| ST3 | `POST /moments` `{clientMomentId, ciphertext, audience, keyCopies, mediaIds}` | app | `PostMoment` | validates shapes and sizes; (poster, clientMomentId) claim returns the first answer; 50 per 24 h; reach filter both ways, silently; moment + audience + file attach in one `attempt` (an unusable file rolls it all back); `status.posted` to the kept audience in the background |
| ST4 | `GET /moments?after=&limit=` | app | `ListMoments` | shown-to-me (with my own `reaction`) and mine (with `viewCount` and `reactionCount`), each read a page by moment id and merged; `next` is the last id of a full page; default 50, at most 200 |
| ST5 | `POST /moments/{momentId}/view` `{reaction?, reacted?}` | app | `ViewMoment` | audience only; `viewed_at` set once. A `reaction` key sets the emoji (`isReaction`: 1 to 16 UTF-8 bytes, no letters, digits, spaces or controls, ASCII only as a keycap base; else `status.reaction_invalid`) or clears it (`null`); without one, `reacted: true` is `❤️` and `false` clears (older apps). The mapper reads the body as a `JsonObject` because a present `null` and an absent key differ. `reaction` and `reacted` are written in one `UPDATE`, so a retry is idempotent. `status.viewed` to the poster when something changed |
| ST6 | `GET /moments/{momentId}/views` | app | `ListViews` | each `{userId, viewedAt, reacted, reaction}`; poster only; an audience member gets `status.not_poster`, anyone else `status.moment_not_found` |
| ST7 | `DELETE /moments/{momentId}` | app | `DeleteMoment` | poster only (as ST6); deletes moment and audience, brings the file's expiry to now, `status.deleted` to the audience, then deletes the object and its row |
| ST8 | `GET /moments/media/{mediaId}` | app | `GetMomentMediaDownload` | 15-minute link for the poster or the audience of the live moment carrying it; everything else `status.media_not_found` |

Moment ids come from `OrderedIds` (UUIDv7, at least a millisecond apart per node), so listing by id is posting order.

## Data

```sql
status.moments  (moment_id uuid PK, poster_id uuid, client_moment_id uuid, ciphertext text, media_id uuid NULL,
                 posted_at timestamptz, expires_at timestamptz)
                UNIQUE (poster_id, client_moment_id)      -- the ST3 retry claim
                INDEX (poster_id, moment_id)              -- own moments, the 50-a-day count
                INDEX (expires_at)                        -- the sweep
status.audience (moment_id uuid REFERENCES moments ON DELETE CASCADE, user_id uuid, key_copy text NULL,
                 viewed_at timestamptz NULL, reacted boolean DEFAULT false, reaction text NULL, PK (moment_id, user_id))
                -- reaction: V2__moment_reactions (earlier hearts backfilled as ❤️); reacted = reaction IS NOT NULL
                INDEX (user_id, moment_id)                -- ST4: what was shown to me
status.media    (media_id uuid PK, poster_id, kind IMAGE|VIDEO|VOICE, size_bytes > 0, state PENDING|READY,
                 moment_id uuid NULL, created_at, expires_at)
                INDEX (expires_at), INDEX (moment_id), INDEX (poster_id)
status.realtime (user_id, seq, frame, sent_at) PK (user_id, seq, sent_at), daily partitions, 7 days
```

A file's `expires_at` is the single rule the sweep reads: ST1 + 1 hour while unattached, the moment's expiry once ST3 attaches it, now once its moment is deleted. Views live on the audience row (one row per person per moment), so a moment's views go with it by the foreign key.

Redis: `sts:seq:{userId}` (per-user frame `seq`, renewed on use, 8 days).

Job `status.purge-expired` (every 10 minutes, leader lock): `purgeExpired("moments", 100)` (audience and views cascade), the `realtime` partitions, then every file past `expires_at` (storage first, the row after; a failed delete keeps the row for the next run).

## Events

Consumes `events:identity` (group `status`): `AccountStatusChanged` (only `DELETED`) and `AccountDeleted` → `RemoveAccountMoments` (each moment removed as ST7 does, every file, every audience row). Publishes nothing.

Frames: `status.posted`, `status.deleted`, `status.viewed` as above; replayed through `RealtimeReplay` (`StatusRealtimeReplay`), `resync` beyond 7 days.

## Error codes

`status.audience_invalid` 400, `status.moment_too_large` 400 (body over 64 KiB decoded, or a key copy over 16 KiB), `status.media_invalid` 400 (ST1 size or kind, ST2 size, more than one file on ST3), `status.media_not_found` 404, `status.moment_not_found` 404, `status.not_poster` 403, `status.too_many_moments` 429 (`Retry-After`: until the oldest of the 50 leaves the 24-hour window), `status.reaction_invalid` 400 (ST5's `reaction` is not one emoji).

## Not in v1

Pushes for anything but a reaction; replies (an ordinary M12 chat message quoting the moment); muting a poster on the server (the phone hides them); a key copy size limit from measurement (16 KiB is a guess well above a real distribution message).
