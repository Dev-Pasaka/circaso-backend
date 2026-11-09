# calls

Built 2026-10-29. Voice and video calls between the people in a chat (direct or group), at most 8 in a call at once. Media goes through **Cloudflare Realtime's SFU**: each phone holds one WebRTC peer connection to it; the server only opens SFU sessions, brokers the session descriptions and rings people (decision 2026-10-29). Media is encrypted in transit (DTLS-SRTP) and decrypted at the SFU, so the app says "Secure call", not "end-to-end encrypted". Schema `calls`, Redis prefix `cal:`, stream `events:calls`, frames module `calls`.

## Overview

### Owns
- Calls: one per chat at a time (`RINGING` → `ACTIVE` → `ENDED`, with `endReason` `COMPLETED`, `CANCELLED`, `MISSED` or `DECLINED`).
- Participants: everyone in the chat when the call started, plus members who join later (`INVITED`, `JOINED`, `DECLINED`, `MISSED`, `LEFT`), each joined one's SFU session id and published tracks, and their mic/camera state.
- Its realtime frames (`calls/*`) and the push wording the gateway gives them.

### Contract (`calls:api`)
- `Calls.liveCall(chatId)`: the chat's call that hasn't ended.
- Frames: `call.ringing` (`CallRingingFrame`), `call.updated` and `call.ended` (`CallSnapshot`, the REST `CallResponse` shape with `iceServers` empty).
- Event on `events:calls`: `calls.CallEnded` (once per call; who joined, how it ended), for a call log later.

### Integrations used
- `CallMedia` (`integrations.calls.provider = cloudflare | log | fake`, default `log`): `newSession`, `pushTracks`, `pullTracks`, `renegotiate`, `closeTracks` (forced: the server has no offer of its own), `iceServers` (STUN `stun:stun.cloudflare.com:3478`, plus TURN credentials from `generate-ice-servers` when a TURN key is configured, cached 23 h). Cloudflare: `CLOUDFLARE_REALTIME_APP_ID`, secret `CLOUDFLARE_REALTIME_APP_TOKEN`; optional `CLOUDFLARE_TURN_KEY_ID`, secret `CLOUDFLARE_TURN_KEY_TOKEN`. Every vendor failure is `calls.media_unavailable`.
- `PushGateway` through the gateway: `PushPayload` gained `callId`, `kind`, `callerName`, `callKind`; `PushMessage` gained `ttlSeconds` (FCM `android.ttl`, `apns-expiration`).

### Depends on
- `messaging:api` `ChatDirectory`: members, and `isGroup` (added 2026-10-29: a declined direct call ends, a declined group call doesn't).
- `users:api` `Profiles` for names on tiles and in the ring.

## Endpoints

| ID | Route | Scope | Use case | Notes |
| --- | --- | --- | --- | --- |
| CL1 | `POST /calls` `{chatId, video}` | app | `StartCall` | members; rings the others (`call.ringing`); a chat with a live call answers that call and rings nobody (2026-11-09, was `calls.already_in_call`): glare joins one call |
| CL2 | `GET /calls/{callId}`, `GET /chats/{chatId}/call` | app | `GetCall`, `GetLiveCall` | members only; the second is the chat's live call or `calls.not_found` |
| CL3b | `POST /calls/{callId}/connected` `{sessionId}` | app | `ReportConnected` | the phone's peer connection is `connected`; sets the participant's `connected_at`, `call.updated` (`connected: true`); others pull it then (2026-11-09) |
| CL3 | `POST /calls/{callId}/join` `{offer, tracks[{mid, trackName, kind}]}` | app | `JoinCall` | new SFU session, push tracks, `{sessionId, answer, call}`; second joiner → `ACTIVE`; ≤ 8 joined; rejoin replaces the session |
| CL4 | `POST /calls/{callId}/pull` `{sessionId, tracks[{userId, trackName}]}` | app | `PullTracks` | maps userId → that participant's session; `{offer?, requiresRenegotiation, tracks[{userId, trackName, mid}]}` |
| CL5 | `POST /calls/{callId}/renegotiate` `{sessionId, answer}` | app | `Renegotiate` | `{}` |
| CL6 | `POST /calls/{callId}/decline` | app | `DeclineCall` | direct: ends `DECLINED`; group: the person `DECLINED`, the call ends when nobody is left to ring or talk to |
| CL7 | `POST /calls/{callId}/leave` | app | `LeaveCall` | answers at once, the SFU close runs after (background, 3 tries, 404/410 = done); caller before any answer → `CANCELLED`; a direct call ends for both on either hang-up (`COMPLETED`, `DECLINED` for an unanswered callee); a group call with fewer than 2 joined → `COMPLETED` |
| CL8 | `POST /calls/{callId}/media` `{audio, video, screen?}` (`screen` since 2026-11-09, reset on join and leave) | app | `SetMedia` | `{}`; broadcast in `call.updated` |

Every change to an existing call goes through `CallChanges`: lock the call row, decide with the pure `CallRules`, write what changed and (when it ended) the outbox row, commit, then frames, publish and SFU closes. SFU closes run in a background scope after the answer (2026-11-09: Cloudflare took ~5.8 s to answer `410` for a session the phone had closed, so hang-ups took 6 to 12 s). SFU calls never run inside the transaction; a join pushes to the SFU first and gives the session up if the call ended or filled meanwhile.

## Data

Schema `calls`: `calls` (call_id PK; partial unique `(chat_id) WHERE state <> 'ENDED'` settles one call per chat; partial indexes for the ring timeout and the idle sweep), `participants` (call_id, user_id PK; session_id while joined; connected_at once CL3b said so, V2), `tracks` (call_id, user_id, track_name PK; kind, mid), `realtime` (daily partitions, 7 days), `outbox`. Redis prefix `cal:` (`cal:seq:{userId}`). Jobs: `calls.ring-timeout` (15 s: `INVITED` for 45 s → `MISSED`, the caller who never joined → `LEFT`, then the call settles; `ACTIVE` with no activity for 2 h → ended `COMPLETED`), `calls.purge-expired` (5 min, partitions).

## Events

Publishes `calls.CallEnded` `{callId, chatId, kind, startedBy, endReason, joinedUserIds, createdAt, startedAt?, endedAt}`. Consumes nothing.

Frames: `call.ringing` to the people rung (pushed data-only, high priority, TTL 45 s: "Incoming call"); `call.updated` to every participant on each change (no push); `call.ended` to every participant (pushed to a callee still being rung: `MISSED` as an alert "Missed call from {name}", `CANCELLED` data-only so the phone stops ringing); `call.ring_stopped` `{callId, chatId, reason}` once to each person whose ring stopped without them answering or declining (INVITED → MISSED), which the gateway also sends an iPhone with a PushKit token as a VoIP push `kind: "call.ended"` (2026-11-09).

## Error codes

`calls.not_found` 404, `calls.not_a_member` 404, `calls.already_in_call` 409 (no longer returned since 2026-11-09), `calls.call_ended` 409, `calls.call_full` 409, `calls.not_in_call` 409, `calls.session_mismatch` 409, `calls.media_unavailable` 502 (UPSTREAM).

## Not in v1

iOS VoIP pushes through PushKit/APNs directly (FCM can't send them; the ring is a mutable-content alert); a call log message in the chat (from `CallEnded`); screen sharing; a "busy" answer when the callee is in another call; recording.
