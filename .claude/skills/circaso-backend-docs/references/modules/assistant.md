# Assistant module (Asha)

Asha answers "what do I want to know or do, said in my own words": she is Circaso's in-app assistant, by text or voice, built with Koog. She reads the user's own data through read-only routes, and later drafts messages and payments that only the user can confirm. Keyspace `circaso_assistant`, Redis prefix `ast:`, stream `events:assistant`. Status: planned; Phase A (read) first, Phase B (act) next.

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
- **Stored by this module.** The thread lives in `circaso_assistant`, encrypted at rest. It is not end-to-end encrypted, because Asha has to read what you write to answer; the top of her chat says so in one line.
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
| `TextToSpeech` / `LiveSpeech` | Voice out, whole reply / streamed sentence by sentence | Workers AI `@cf/deepgram/aura-2-en` (English); Swahili replies are spoken by the phone's own TTS | — |
| `StoreBilling` (later) | Asha Plus subscription state | Google Play Billing, App Store server notifications | — |

**Every model runs on Cloudflare Workers AI, through AI Gateway** (decision 2026-10-25); each model id is a setting, so swapping one is a config change. No Workers AI voice speaks Swahili and Deepgram's live models don't transcribe it, so Swahili uses clip transcription (Whisper) and the phone's own text-to-speech.

The integrations call Workers AI through AI Gateway (`https://gateway.ai.cloudflare.com/v1/<accountId>/<gateway>/workers-ai/…`) with `HttpClients.named("workers-ai")`: the OpenAI-compatible chat completions route for the text and image models, the model routes for Whisper and Aura clips, and WebSocket connections for Flux and Aura streaming. Settings: `CLOUDFLARE_ACCOUNT_ID`, `INTEGRATIONS_AI_GATEWAY`, the secret `CLOUDFLARE_AI_TOKEN`, and one `INTEGRATIONS_AI_MODEL_*` per model. AI Gateway logging is off for Asha's routes (inputs aren't kept). Every Deepgram call (Flux, Nova-3, Aura) sends `mip_opt_out=true`, so audio never enters Deepgram's model improvement programme (it defaults to opted in).

### Depends on

`shared`, `infrastructure:api`, `integrations:api`, `identity:api` (who the user is, account status), `users:api` (`ReachPolicy`, `Profiles`, contact lookup). Later `messaging:api` and `wallet:api` for proposals. Nothing depends on the assistant.

## Voice

1. **Voice in.** The app transcribes on the phone when it can (free, fast, private). Otherwise it uploads the clip (up to 60 s) to `POST /v1/assistant/voice`, which runs `SpeechToText` and returns the transcript. The transcript, not the audio, becomes the turn; the audio is deleted after transcription.
2. **Reply.** Text first, streamed, so the user sees Asha answer at once.
3. **Voice out.** On request, or when the user spoke, `TextToSpeech` turns the reply into audio streamed back to the app. Short replies can be cached by text hash for a day.

### Live voice (decision 2026-10-25)

**Phase 1, WebSocket:** while the user talks to Asha, the phone opens a dedicated socket `wss://api.circaso.com/v1/assistant/voice/live` (an exception to one socket per phone: audio is a heavy binary stream and lives only for the conversation), authenticated like the main socket with the user token.

1. The phone streams Opus audio (16 kHz mono, about 24 kbit/s, so roughly 2 MB per 10 minutes of talking) in binary frames; the assistant relays them to `LiveTranscription` (Nova-3, `encoding=opus`) and sends interim transcripts back as JSON frames.
2. When Nova-3 reports the end of the turn (`speech_final` after `endpointing`, or an utterance-end event), the final transcript becomes an Asha turn exactly like A2 (tools, limits, audit).
3. The reply streams back as text frames and, sentence by sentence, through `LiveSpeech` (Aura) as binary audio frames. If the user starts talking again, the server stops the speech (barge-in) and starts a new turn.
4. Usage counts voice seconds both ways; the socket closes at the allowance or after 30 s of silence. Audio is never stored.
5. For Swahili (the user's language setting) the live path isn't used: the phone records a clip for A3 (Whisper) and speaks the reply with its own voice.

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

- **Metering:** each turn records model tokens and voice seconds. Live counters sit in Redis (`ast:quota:{userId}:{day}`); daily totals go to `usage_by_user` for cost reports.
- **Out of allowance:** `assistant.quota_exceeded` (429) with `details.retryAfterSeconds` until the reset and an upgrade hint; the app shows the Asha Plus offer.
- **Entitlement:** checked on every turn from `entitlements_by_user`, kept up to date by store notifications received as webhooks at the gateway.

## How Asha acts (Phase B)

Asha never gets write power. The agent token stays read-only; the only thing she can create is a **proposal** in her own module. Carrying it out always takes the user's own token and the user's own confirmation.

1. **Gather.** From the conversation Asha fills what the action needs: the recipient, the amount and token for a payment, the text for a message. She resolves the recipient only among the user's contacts (`users:api`). If two contacts match ("John"), or anything is unclear, she asks; she never guesses a recipient or an amount.
2. **Propose.** She creates a proposal (`ProposalRow`: kind, recipient, amount or text, the turns it came from, expires in 10 minutes) and shows it as a card: "Send KES 2,000 to Mum?"
3. **Confirm.** The user taps Confirm. The app calls the normal endpoint with the **user** token and the `proposalId`: messaging's send, or wallet's prepare transfer. A payment still needs the PIN or biometrics, and the Circle signature, exactly as if the user had typed it.
4. **Record.** The message or transaction carries `origin: ASHA` and the `proposalId`; the assistant module records the outcome (confirmed, edited, declined, expired).

### Audit and monitoring

- Every turn, tool call, proposal and decision goes to `audit_by_user`, append-only, with the model, prompt version and request id. It records what happened, never what was said, so deleting the Asha chat leaves no conversation text behind. Audio is never kept.
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

## Data (outline)

`AshaMessageRow` (partition `userId`, time-ordered: the chat), `ProposalRow` (by user, 10-minute TTL once decided or expired), `UsageDailyRow` (by user and day), `EntitlementRow` (by user), `AssistantAuditRow` (by user, time-ordered, no message text), `AssistantOutboxRow`.

## Error codes (draft)

| Code | Kind → status | When |
| --- | --- | --- |
| `assistant.quota_exceeded` | RATE_LIMITED → 429 | Daily allowance used up |
| `assistant.plus_required` | FORBIDDEN → 403 | A Plus-only feature on the free plan |
| `assistant.voice_too_long` | INVALID → 400 | Clip over 60 s |
| `assistant.proposal_expired` | CONFLICT → 409 | Confirming after 10 minutes |
| `assistant.model_unavailable` | UPSTREAM → 502 | The model provider is down |
