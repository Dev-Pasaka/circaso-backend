# Assistant module (Asha)

Asha answers "what do I want to know or do, said in my own words": she is Circaso's in-app assistant, by text or voice, on Cloudflare Workers AI models. She reads the user's own data through read-only routes, and later drafts messages and payments that only the user can confirm. Schema `assistant`, Redis prefix `ast:`, stream `events:assistant`. Status: planned; Phase A (read) first, Phase B (act) next.

## Overview

### Phases

| Phase | Asha can | Asha cannot |
| --- | --- | --- |
| A — Read | Answer questions about the user's own account ("How much did I send Mum yesterday?", "Who in my chama hasn't contributed?"), search and summarise their own chats (decrypted on the phone), explain the app, give the welcome tour | Change anything |
| B — Act | From a conversation, gather what is needed and **propose** a message to a contact or a payment to a contact; the user confirms | Send, pay or sign on her own; reach anyone outside the user's contacts |

### Where users meet Asha

- **The Asha section:** a full conversation with history, by text or voice.
- **Summoned from any chat input:** the Asha button in the composer (or typing `/asha`) asks her a question without leaving the chat. Her answer shows only to the user, as a private card above the composer; nothing is posted into the chat unless the user sends it.
- **She can read the user's chats.** Chats stay end-to-end encrypted on the server; Asha's chat tools run on the phone, which decrypts with the local private key (see Reading encrypted chats below).

### Asha in the chat list

- **A chat like any other.** Asha appears as a pinned chat at the top of the chat list and in contacts, with her own avatar and a "Circaso assistant" badge. Her chat is one continuous thread per user: text, voice notes (kept as transcripts), proposal cards, and the questions asked from other chats, marked "asked from Family".
- **Stored by this module.** The thread lives in the `assistant` schema, encrypted at rest. It is not end-to-end encrypted, because Asha has to read what you write to answer; the top of her chat says so in one line.
- **Not a person.** Asha has a fixed system id, no account in identity and no profile in users. She can't be blocked, paid, called, added to a group or found by handle, and she never appears in anyone else's contacts. The app opens her chat through the assistant routes, so the reach rule doesn't apply.
- **Memory of the thread.** She uses the recent part of the thread as context: the last few exchanges on the free plan, much more on Asha Plus.
- **Deleting.** The user can delete one exchange (A6) or the whole history (A5) at any time, and deleting the account deletes it too. After that Asha has no copy: not in the thread, caches or the model provider. Two things remain, neither holding conversation text: the audit records of Phase B actions (what was proposed, to whom, the amount and the user's decision), because they back money movements, and usage counts for billing.

### Reading encrypted chats

The server never holds the keys, so Asha's chat tools are **device tools**: the agent asks, the phone decrypts and answers.

1. Asha decides she needs chat content ("What did the chama agree about the December contribution?") and calls a device tool: `searchChats(query, chatIds?, since?)` or `readChat(chatId, around?, limit)`.
2. The tool call travels to the app inside the turn's response stream (A2) as a `tool_request` event.
3. The app decrypts locally with the user's private key, searches, and returns only the matching messages (at most 30 short snippets with sender and time) to A9.
4. The agent continues the turn with those snippets and answers.

**What this changes, and the rules that come with it:**

- Snippets Asha reads leave the phone in plaintext for that turn, to the backend and the model provider. Chats are still end-to-end encrypted at rest and in transit between people; Asha is a reader the user brings in, like forwarding a message to themselves.
- **Minimum needed:** the phone sends only the matching snippets, never whole chats or media; the agent can't ask for more than the cap per call.
- **Nothing kept:** decrypted snippets are used for the turn and dropped. The Asha chat keeps her answer as the user saw it, and a note of which chats and how many messages she read; the snippets themselves are never stored.
- **Visible:** the answer shows "Asha read 4 messages from Family", tappable to see exactly which.
- **User control:** reading chats is on by default and can be turned off entirely or for single chats. Hidden or locked chats are never searchable.
- **Model provider:** only a provider that doesn't keep or train on inputs, which AI Gateway's logging must not undo for these requests.
- **Later, cheaper and more private:** simple chat questions (find, count, summarise one chat) could run on an on-device model, so nothing leaves the phone.

### Owns

- **Conversations** with Asha: one continuous chat per user, stored like any chat and deletable by the user; device tool results are never stored.
- **Proposals** (Phase B): drafted messages and payments waiting for the user's confirmation.
- **Usage and entitlements:** what each user has used today, and whether they have Asha Plus.
- **Audit trail:** every turn, tool call, proposal and the user's decision on it.

### Runs as its own module

Asha's runtime (the Koog agent, prompts, tools and memory of the conversation) lives in this module, not in the gateway, which keeps to auth, limits and routing. The gateway authenticates the agent token; Asha's tools call the `/v1/agent/` routes of other modules with it, exactly as an outside client would. This also keeps her tools bound to the same read-only rules as everyone else. (Open question on the Decisions doc until confirmed.)

### Integrations used

| Contract (`integrations:api`) | Used for | Provider to start | Can swap to |
| --- | --- | --- | --- |
| `LanguageModel` | Asha's reasoning and tool calls (text model) | Workers AI `kimi-k2.6`, through AI Gateway | Another Workers AI model with tool calling (setting) |
| `VisionModel` | Reading a photo the user sends (image model) | Workers AI `llama-4-scout-17b-16e-instruct` | Another Workers AI vision model (setting) |
| `SpeechToText` | A voice clip to text | On-device first; Workers AI `whisper-large-v3-turbo` on the server (the only Workers AI model that handles Swahili) | — |
| `LiveTranscription` | Live voice in, with end-of-turn detection | Workers AI `@cf/deepgram/nova-3` over a WebSocket through AI Gateway, Opus audio, end of turn from `endpointing` / `utterance_end_ms` / `vad_events` (`flux` as the setting's alternative: better turn detection but PCM only, about ten times the mobile data) | — |
| `TextToSpeech` / `LiveSpeech` | Voice out, whole reply / streamed sentence by sentence | Workers AI `@cf/deepgram/aura-2-en` (English); clips through AI Gateway, the live socket **directly to Workers AI** (AI Gateway refuses it, decision 2026-10-27); Swahili replies are spoken by the phone's own TTS | — |
| `StoreBilling` (later) | Asha Plus subscription state | Google Play Billing, App Store server notifications | — |

**Every model runs on Cloudflare Workers AI, through AI Gateway** (decision 2026-10-25); each model id is a setting, so swapping one is a config change. No Workers AI voice speaks Swahili and Deepgram's live models don't transcribe it, so Swahili uses clip transcription (Whisper) and the phone's own text-to-speech.

The integrations call Workers AI at `https://api.cloudflare.com/client/v4/accounts/<accountId>/ai/` through AI Gateway (the gateway named in the `cf-aig-gateway-id` header, logging off per request with `cf-aig-collect-log: false`) with `HttpClients.named("workers-ai")`: `v1/chat/completions` (OpenAI-compatible) for the text and image models, `run/@cf/<model>` for Whisper and Aura clips, and WebSocket connections for live voice (AI2). Asha's text replies stream from the model (`stream: true`, `LanguageModel.stream`: text and tool-call pieces, then the whole answer with its usage), so A2's `message` events and A11's sentences go out as the model writes them (decision 2026-10-27). One exception to the gateway: **Aura's live socket connects to Workers AI directly, with no `cf-aig-*` header**, because AI Gateway refuses it (`400` "Unsupported websocket provider/model"); Nova-3's live socket and every HTTP call keep the gateway (decision 2026-10-27). Workers AI closes Aura's socket (`1011`) 5 s after the last message we send, even while Aura is still making a long reply's audio, and neither a WebSocket ping nor Deepgram's `KeepAlive` (which closes it at once) prevents that (observed in the AI1b re-check, not documented). Decided 2026-10-27: live voice keeps one Aura socket per conversation, held open by a `Speak` of one space after 3 s without another message (it kept a 25 s reply and a 12 s silence alive), and a socket still closed while idle is reopened at the next sentence; if Cloudflare changes this, replies are spoken as clips (`TextToSpeech`) instead. No agent framework: the assistant calls `LanguageModel` directly. Kimi K2.6 needs paid Workers AI (the Workers Paid plan, or AI Gateway credits with unified billing). Settings: `CLOUDFLARE_ACCOUNT_ID`, `INTEGRATIONS_AI_GATEWAY`, the secret `CLOUDFLARE_AI_TOKEN`, and one `INTEGRATIONS_AI_MODEL_*` per model. AI Gateway logging is off for Asha's routes (inputs aren't kept). Every Deepgram call (Flux, Nova-3, Aura) sends `mip_opt_out=true`, so audio never enters Deepgram's model improvement programme (it defaults to opted in); Workers AI accepts it for Aura but likely ignores it, which is accepted (decision 2026-10-27).

### Depends on

`shared`, `infrastructure:api`, `integrations:api`, `identity:api` (who the user is, account status), `users:api` (`ReachPolicy`, `Profiles`, contact lookup). Later `messaging:api` and `wallet:api` for proposals. Nothing depends on the assistant.

## Voice

1. **Voice in.** The app transcribes on the phone when it can (free, fast, private). Otherwise it uploads the clip (up to 60 s) to `POST /v1/assistant/voice`, which runs `SpeechToText` and returns the transcript. The transcript, not the audio, becomes the turn; the audio is deleted after transcription.
2. **Reply.** Text first, streamed from the model as it is written, so the user sees Asha answer at once. Text the model writes before calling a tool is shown too, and the saved reply is everything shown, a blank line between the parts; a failure after text has gone out is an `error` event `{code, message}` on A2 and A9, and nothing is saved (decision 2026-10-27).
3. **Voice out.** On request, or when the user spoke, `TextToSpeech` turns the reply into audio streamed back to the app. Short replies can be cached by text hash for a day.

### Live voice (decision 2026-10-25)

**Phase 1, WebSocket:** while the user talks to Asha, the phone opens a dedicated socket `wss://api.circaso.com/v1/assistant/voice/live` (an exception to one socket per phone: audio is a heavy binary stream and lives only for the conversation), authenticated like the main socket with the user token.

1. The phone streams Opus audio (16 kHz mono, about 24 kbit/s, so roughly 2 MB per 10 minutes of talking) in binary frames; the assistant relays them to `LiveTranscription` (Nova-3, `encoding=opus`) and sends interim transcripts back as JSON frames.
2. When Nova-3 reports the end of the turn (`speech_final` after `endpointing`, or an utterance-end event), the final transcript becomes an Asha turn exactly like A2 (tools, limits, audit).
3. The reply streams back as text frames and, sentence by sentence as the model writes it, through `LiveSpeech` (Aura) as binary audio frames. If the user starts talking again, the server stops the speech (barge-in) and starts a new turn.
4. Frames on this socket are at most 64 KB, like the main socket (Opus frames are far smaller). Usage counts voice seconds both ways; the socket closes at the allowance or after 30 s of silence. Audio is never stored.
5. For Swahili (the user's language setting) the live path isn't used: the phone records a clip for A3 (Whisper) and speaks the reply with its own voice.

**Frames (A11, built in AI2).** Every frame is at most 64 KB both ways; a bigger one from the phone closes the socket with 1009 `Frame is too big: N` (Ktor's reason, N the frame's size) and **no** `closing` frame, and Asha's audio is split to fit. Binary frames are audio both ways: Opus packets from the phone (16 kHz mono), and Asha's speech to the phone as mulaw, mono, 8 kHz by default (64 kbit/s; the encoding and rate are settings, named in the `ready` frame). Text frames are JSON with a `type`:

| Direction | `type` | Fields | Meaning |
| --- | --- | --- | --- |
| phone → server | `auth` | `accessToken`, `agentToken`?, `language`?, `chatReading` (default true) | First frame, within 10 s. The user (`app`) token only; `agentToken` is the user's agent token for Asha's tools (as `X-Agent-Token` on A2); `language` is the user's language setting. Sent again later with fresh tokens (`accessToken`, `agentToken`) before the 15-minute access token expires |
| phone → server | `tool_results` | `turnId`, `callId`, `snippets` | The answer to a `tool_request` (as A9). A frame that doesn't decode, or a `callId` the turn isn't waiting on, gets an `error` frame `request.malformed` and the turn keeps waiting (on A9 too) |
| phone → server | `end` | | Hang up |
| server → phone | `ready` | `voiceSecondsLeft`, `audio: {encoding, sampleRate}` | Accepted; start streaming |
| server → phone | `transcript` | `text`, `final` | The words of the turn so far; `final` false while they may change |
| server → phone | `turn` | `text` | The user finished; this question now goes through A2 |
| server → phone | `reply` | `text` | One sentence of the reply, sent when the audio reaches it (timed at about 15 characters a second, as the voice says no more; the last ones at the latest when all the audio is sent); the audio is in binary frames. Each sentence goes to the voice as soon as the model has written it, with one `Flush` per reply, or two when the turn pauses for a device tool (what was said before the pause is flushed so it is heard while the phone searches; decision 2026-10-27) |
| server → phone | `reply_done` | `message` (as A1), `spoken` | Sent once the reply's audio has finished playing on the phone (or at once when it is talked over). The reply as saved in the Asha chat; `spoken` false if the user talked over it |
| server → phone | `tool_request` | `turnId`, `callId`, `name`, `arguments` (a JSON string, as on A2) | A device tool; unanswered after 30 s, the turn ends with an `error` frame `assistant.turn_not_found` |
| server → phone | `interrupted` | | Barge-in: stop playing what is buffered |
| server → phone | `error` | `code`, `message` | One turn failed (`assistant.model_unavailable`, `assistant.turn_not_found`, `request.malformed` for a bad `tool_results`); the conversation goes on |
| server → phone | `closing` | `reason`, `message`?, `retryAfterSeconds`? | Sent before the server closes; `reason` is also the close reason |

Closing: a bad, expired, revoked or agent token, or no auth frame, closes with 1008 `unauthorized` / `auth required` and no frame, as the main socket does. The session is re-checked at each turn and every 60 s; once it is revoked (sign-out, replaced phone, account deletion) or its token has expired without a fresh `auth` frame, the socket closes the same way, 1008 `unauthorized`, no frame. A frame over 64 KB closes with 1009 `Frame is too big: N` (Ktor's reason, N the frame's size), no frame. Otherwise a `closing` frame comes first: `assistant.language_not_supported` (1008, the phone uses A3 and its own voice), `assistant.quota_exceeded` (1008, with `retryAfterSeconds`; voice allowance at the start or reached during the conversation, or the text allowance on a turn), `assistant.model_unavailable` (1011, a provider is down at the start or dropped the session), `silence` (1000, nobody spoke for 30 s) or `ended` (1000, after the phone's `end`).

Metering (decisions 2026-10-26): voice seconds count only speech. The user's is measured on the transcriber's clock: Nova-3's word timestamps (each result's `start`/`duration` when it has no words), from the first word's start to the last word's end of each stretch of speech (a gap over 1 s starts a new stretch), so a burst of late events can't stretch or shrink it; arrival time is only the fallback for a provider without timestamps. Asha's is charged as it plays on the phone, so what a barge-in or hang-up cuts off is never charged. An open mic, silence and pauses are free. Seconds are metered against the day's voice allowance as they gather, atomically, never past it even with two sockets open.

Agent token (decision 2026-10-26): every A11 turn (and A2 and A9) checks that the agent token is a valid `agent` token of the same user; the opening `auth` frame and every later one check it too. A mismatch closes the socket with 1008 `unauthorized` and no frame (`auth.token_invalid` on A2 and A9). A later `auth` frame whose access token isn't the same user's `app` token (another user's, or an agent token) closes the same way.

**Phase 2, WebRTC:** when the calls module brings in Cloudflare Realtime, the phone side moves to WebRTC (Opus, jitter buffer, better on poor networks) and Cloudflare forwards the audio to the assistant; the server-side transcription and speech stay the same.

### Images

The user can send Asha a photo (a receipt, a screenshot, a document): the app uploads it straight to R2 with a presigned URL (A10), then sends the turn with its key. `VisionModel` reads it and its description joins the conversation for the text model; the photo is deleted from R2 after the turn and never kept in the Asha chat (the chat shows "Photo" and Asha's reading of it). JPEG, PNG or WebP, up to 10 MB.

Voice seconds and images are metered separately from text, because they cost more.

## Usage limits and Asha Plus

Asha costs money per message, so free use is capped and longer use is a subscription.

| | Free | Asha Plus |
| --- | --- | --- |
| Text turns | A daily allowance | High daily cap (fair use) |
| Voice | A small daily allowance, on-device transcription | Larger allowance, server transcription and natural voices |
| Conversation length | Full chat history kept; Asha uses the last few exchanges as context | Full chat history kept; Asha uses a much longer stretch of it as context |
| Phase B actions | Included (they save the user effort) | Included |

Exact numbers and the price are open (see Decisions).

- **Metering:** each turn records model tokens and voice seconds. Live counters sit in Redis (`ast:quota:{userId}:{day}`); daily totals go to `usage_daily` for cost reports.
- **Out of allowance:** `assistant.quota_exceeded` (429) with `details.retryAfterSeconds` until the reset and an upgrade hint; the app shows the Asha Plus offer.
- **Entitlement:** checked on every turn from `entitlements`, kept up to date by store notifications received as webhooks at the gateway.

## How Asha acts (Phase B)

Asha never gets write power. The agent token stays read-only; the only thing she can create is a **proposal** in her own module. Carrying it out always takes the user's own token and the user's own confirmation.

1. **Gather.** From the conversation Asha fills what the action needs: the recipient, the amount and token for a payment, the text for a message. She resolves the recipient only among the user's contacts (`users:api`). If two contacts match ("John"), or anything is unclear, she asks; she never guesses a recipient or an amount.
2. **Propose.** She creates a proposal (a `proposals` row: kind, recipient, amount or text, the turns it came from, expires in 10 minutes) and shows it as a card: "Send KES 2,000 to Mum?"
3. **Confirm.** The user taps Confirm. The app calls the normal endpoint with the **user** token and the `proposalId`: messaging's send, or wallet's prepare transfer. A payment still needs the PIN or biometrics, and the Circle signature, exactly as if the user had typed it.
4. **Record.** The message or transaction carries `origin: ASHA` and the `proposalId`; the assistant module records the outcome (confirmed, edited, declined, expired).

### Audit and monitoring

- Every turn, tool call, proposal and decision goes to `audit`, append-only, with the model, prompt version and request id. It records what happened, never what was said, so deleting the Asha chat leaves no conversation text behind. Audio is never kept.
- Events `assistant.ProposalCreated`, `assistant.ProposalConfirmed`, `assistant.ProposalDeclined` feed monitoring and the transaction auditor.
- Grafana: proposals per user per hour, confirm rate, amounts proposed, declined-after-edit rate. Alerts on bursts of proposals or unusual amounts.
- **Prompt injection:** contact names, profile text and anything quoted from a chat are untrusted text to the model. They can make Asha say something wrong, but never act: her tools are read-only, and a proposal does nothing until the user confirms it.

## Endpoints (draft)

| # | Route | Scope | Does |
| --- | --- | --- | --- |
| A1 | `GET /v1/assistant/chat` | app | The Asha chat, newest first, paged |
| A2 | `POST /v1/assistant/chat/messages` | app | Send Asha a message: typed, spoken, or asked from another chat (with that chat's id); reply streamed as server-sent events |
| A3 | `POST /v1/assistant/voice` | app | Transcribe a voice clip |
| A4 | `POST /v1/assistant/speech` | app | Turn a reply into audio |
| A5 | `DELETE /v1/assistant/chat` | app | Delete the whole history with Asha |
| A6 | `DELETE /v1/assistant/chat/messages/{messageId}` | app | Delete one exchange |
| A7 | `GET /v1/assistant/usage` | app | Today's allowance used and left, and the plan |
| A8 | `GET /v1/assistant/proposals/{id}` / `POST …/decline` | app | Read or decline a proposal (Phase B) |
| A9 | `POST /v1/assistant/chat/messages/{messageId}/tool-results` | app | The phone's answer to a device tool (decrypted chat snippets); the reply then continues |
| A10 | `POST /v1/assistant/images/upload-url` | app | Presigned R2 upload for one photo; A2 then carries its `imageKey` |
| A11 | `GET /v1/assistant/voice/live` (WebSocket) | app | Live voice conversation (Phase 1) |

The user talks to Asha with the **user** token; Asha's tools call other modules with the **agent** token.

## Data

PostgreSQL tables in schema `assistant` (role `assistant`, Flyway location `db/assistant`), built in PG4.

```sql
-- V1__assistant.sql (shape; the script is modules/assistant/impl/src/main/resources/db/assistant/V1__assistant.sql)

CREATE TABLE assistant.asha_messages (                  -- the Asha chat
    user_id     uuid        NOT NULL,
    message_id  uuid        NOT NULL,                   -- newest first by id
    role        text        NOT NULL,
    body        text        NOT NULL,                   -- sealed (AES-GCM) before it is stored
    reply_to    uuid,
    answered_by uuid,
    asked_from  text,
    read_note   text,                                   -- JSON: the chat ids and the number of messages Asha read, never the messages
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, message_id)                   -- pages by keyset, message_id DESC
);

CREATE TABLE assistant.usage_daily (                    -- daily totals for cost reports
    user_id       uuid        NOT NULL,
    usage_day     date        NOT NULL,
    text_turns    int         NOT NULL,
    voice_seconds int         NOT NULL,
    input_tokens  bigint      NOT NULL,
    output_tokens bigint      NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (user_id, usage_day)                    -- added to with upsert (keys: user_id, usage_day), count = count + n
);

CREATE TABLE assistant.entitlements (
    user_id    uuid PRIMARY KEY,
    plan       text NOT NULL,
    plan_ends_at timestamptz                            -- when the paid plan ends; NULL for the free plan
);

CREATE TABLE assistant.audit (                          -- append-only; what happened, never what was said
    user_id    uuid NOT NULL,
    event_id   uuid NOT NULL,                           -- UUIDv7 made in the app
    kind       text NOT NULL,
    detail     text NOT NULL,
    request_id text,
    PRIMARY KEY (user_id, event_id)
);

CREATE TABLE assistant.outbox (…);                      -- the shared outbox DDL (architecture.md → Durable events): daily partitions, 7 days
```

| Table | Expiry |
| --- | --- |
| `asha_messages` | — (until the user deletes the Asha chat) |
| `usage_daily` | — |
| `entitlements` | — |
| `audit` | — |
| `outbox` | Daily partitions, 7 days, kept one day ahead and dropped by `assistant.purge-expired` (every 5 minutes) |

**Phase B** adds `assistant.proposals` (by user, with an `expires_at` 10 minutes after the proposal is decided or expires, purged by `assistant.purge-expired`), designed with Phase B.

## Error codes (draft)

| Code | Kind → status | When |
| --- | --- | --- |
| `assistant.quota_exceeded` | RATE_LIMITED → 429 | Daily allowance used up |
| `assistant.plus_required` | FORBIDDEN → 403 | A Plus-only feature on the free plan |
| `assistant.voice_too_long` | INVALID → 400 | Clip over 60 s |
| `assistant.proposal_expired` | CONFLICT → 409 | Confirming after 10 minutes |
| `assistant.model_unavailable` | UPSTREAM → 502 | The model provider is down |
| `assistant.language_not_supported` | INVALID → 400 | A4 asked for speech in a language the server voice doesn't speak (Swahili: the phone speaks it) |
