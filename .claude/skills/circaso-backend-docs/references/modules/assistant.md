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

- **A chat like any other.** Asha appears as a pinned chat at the top of the chat list and in contacts, with her own avatar and a "Circaso assistant" badge. Her chat is a list of **conversations** per user (decision 2026-10-31), like a chat list: the user can start a brand new one at any time. Each holds text, voice notes (kept as transcripts), proposal cards, and the questions asked from other chats, marked "asked from Family".
- **Stored by this module.** The thread lives in the `assistant` schema, encrypted at rest. It is not end-to-end encrypted, because Asha has to read what you write to answer; the top of her chat says so in one line.
- **Not a person.** Asha has a fixed system id, no account in identity and no profile in users. She can't be blocked, paid, called, added to a group or found by handle, and she never appears in anyone else's contacts. The app opens her chat through the assistant routes, so the reach rule doesn't apply.
- **Memory of the conversation.** She uses the recent part of the conversation she is asked in as context, never another conversation: the last few exchanges on the free plan, much more on Asha Plus. A new conversation starts with a clean context, which also keeps turns cheap.
- **Deleting.** The user can delete one exchange (A6), one conversation (C4) or the whole history (A5, every conversation) at any time, and deleting the account deletes it too. After that Asha has no copy: not in the thread, caches or the model provider. Deleting the account deletes the chat and the entitlement in one transaction and, once it has committed, the user's `ast:` Redis keys (turns parked for the phone, which hold the conversation, and the day's counters), so nothing of the conversation stays in caches. All audit records (what was done, never what was said; in Phase B what was proposed, to whom, the amount and the user's decision, which back money movements) and usage counts for billing remain.

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

- **Conversations** with Asha: each user's list of conversations and their messages, stored like any chat and deletable by the user; device tool results are never stored.
- **Proposals** (Phase B): drafted messages and payments waiting for the user's confirmation.
- **Usage and entitlements:** what each user has used today, and whether they have Asha Plus.
- **Audit trail:** every turn, tool call, proposal and the user's decision on it.

### Runs as its own module

Asha's runtime (the Koog agent, prompts, tools and memory of the conversation) lives in this module, not in the gateway, which keeps to auth, limits and routing. The gateway authenticates the agent token; Asha's tools call the `/v1/agent/` routes of other modules with it, exactly as an outside client would. This also keeps her tools bound to the same read-only rules as everyone else. (Open question on the Decisions doc until confirmed.)

### Integrations used

| Contract (`integrations:api`) | Used for | Provider to start | Can swap to |
| --- | --- | --- | --- |
| `LanguageModel` | Asha's reasoning and tool calls (text model); live voice turns on a faster voice model (`ModelRequest.purpose = VOICE`, decision 2026-10-30) | Workers AI `kimi-k2.6` (typed), `llama-4-scout-17b-16e-instruct` (voice, `model.voice-text`), through AI Gateway | Another Workers AI model with tool calling (setting) |
| `VisionModel` | Reading a photo the user sends (image model) | Workers AI `llama-4-scout-17b-16e-instruct` | Another Workers AI vision model (setting) |
| `SpeechToText` | A voice clip to text | On-device first; Workers AI `whisper-large-v3-turbo` on the server (the only Workers AI model that handles Swahili) | — |
| `LiveTranscription` | Live voice in, with end-of-turn detection | Workers AI `@cf/deepgram/nova-3` over a WebSocket through AI Gateway, Opus audio, end of turn from `endpointing` / `utterance_end_ms` / `vad_events` (`flux` as the setting's alternative: better turn detection but PCM only, about ten times the mobile data) | — |
| `TextToSpeech` / `LiveSpeech` | Voice out, whole reply / streamed sentence by sentence | Workers AI `@cf/deepgram/aura-2-en` (English); clips through AI Gateway, the live socket **directly to Workers AI** (AI Gateway refuses it, decision 2026-10-27); Swahili replies are spoken by the phone's own TTS | — |
| `StoreBilling` (later) | Asha Plus subscription state | Google Play Billing, App Store server notifications | — |

**Every model runs on Cloudflare Workers AI, through AI Gateway** (decision 2026-10-25); each model id is a setting, so swapping one is a config change. No Workers AI voice speaks Swahili and Deepgram's live models don't transcribe it, so Swahili uses clip transcription (Whisper) and the phone's own text-to-speech.

The integrations call Workers AI at `https://api.cloudflare.com/client/v4/accounts/<accountId>/ai/` through AI Gateway (the gateway named in the `cf-aig-gateway-id` header, logging off per request with `cf-aig-collect-log: false`) with `HttpClients.named("workers-ai")`: `v1/chat/completions` (OpenAI-compatible) for the text and image models, `run/@cf/<model>` for Whisper and Aura clips, and WebSocket connections for live voice (AI2). Asha's text replies stream from the model (`stream: true`, `LanguageModel.stream`: text and tool-call pieces, then the whole answer with its usage), so A2's `message` events and A11's sentences go out as the model writes them (decision 2026-10-27). One exception to the gateway: **Aura's live socket connects to Workers AI directly, with no `cf-aig-*` header**, because AI Gateway refuses it (`400` "Unsupported websocket provider/model"); Nova-3's live socket and every HTTP call keep the gateway (decision 2026-10-27). Workers AI closes Aura's socket (`1011`) 5 s after the last message we send, even while Aura is still making a long reply's audio, and neither a WebSocket ping nor Deepgram's `KeepAlive` (which closes it at once) prevents that (observed in the AI1b re-check, not documented). Decided 2026-10-27: live voice keeps one Aura socket per conversation, held open by a `Speak` of one space after 3 s without another message (it kept a 25 s reply and a 12 s silence alive), and a socket still closed while idle is reopened at the next sentence; if Cloudflare changes this, replies are spoken as clips (`TextToSpeech`) instead. No agent framework: the assistant calls `LanguageModel` directly. Kimi K2.6 needs paid Workers AI (the Workers Paid plan, or AI Gateway credits with unified billing). Settings: `CLOUDFLARE_ACCOUNT_ID`, `INTEGRATIONS_AI_GATEWAY`, the secret `CLOUDFLARE_AI_TOKEN`, and one `INTEGRATIONS_AI_MODEL_*` per model. AI Gateway logging is off for Asha's routes (inputs aren't kept). Every Deepgram call (Flux, Nova-3, Aura) sends `mip_opt_out=true`, so audio never enters Deepgram's model improvement programme (it defaults to opted in); Workers AI accepts it for Aura but likely ignores it, which is accepted (decision 2026-10-27).

### Depends on

`shared`, `infrastructure:api`, `integrations:api` (`LanguageModel`, the speech contracts, `WebSearch`), and the `api` of identity, users, messaging, wallet and chama, only for the types their agent routes answer (decision 2026-10-31). Nothing depends on the assistant.

### Conversations (decision 2026-10-31)

Pasaka (2026-10-31): "to save on context we can have chat history where the user can create a brand new chat conversation", like Claude's chat list.

- **Model.** A conversation has an id (UUIDv7), a title, `createdAt`, `updatedAt` (any change: a message, a rename) and `lastMessageAt` (the last message, or its creation while empty), and belongs to one user. Every message belongs to one conversation of its user.
- **Which conversation a turn goes to** (`ConversationResolver`): the one named (A2's `conversationId`, A11's `auth.conversationId`); with none named, the user's most recent (by `lastMessageAt`), or a new one when they have none, so an app that doesn't know about conversations keeps one thread. A named id that isn't the user's is `assistant.conversation_not_found`, checked before the allowance is counted. A live call keeps every turn in the conversation its first turn went to. A turn parked for the phone (A9) carries its conversation in `ast:turn:…`; one parked before conversations existed goes to the most recent.
- **Context.** `StartTurn` gives the model only that conversation's messages, capped by the plan's `contextMessages` (`ASSISTANT_LIMITS_UNLIMITED` still lifts it to 200).
- **Saving.** `TurnRecorder` updates the conversation first (`UPDATE … RETURNING`, which locks the row; lock order: the conversation, then its messages), then writes the exchange, its audit record and the usage in the same transaction. A conversation deleted while Asha answered keeps nothing of the exchange (the audit record and usage stay); `done` then carries `conversation: null`.
- **Titles** (`ConversationTitles`, `ConversationTitler`). A rule, not a model: greetings dropped ("Hi Asha, …"), markdown marks removed, the first six words kept (at most 60 characters), closing punctuation trimmed, first letter capitalised. Free, instant, never fails, never counts against the allowance, and the question goes to no other provider. Set with the conversation's first exchange, inside the transaction that saves it (the reply has already streamed, so it never delays it), only while the title is empty (`WHERE title IS NULL`): a title the user set (C2, C3) is never replaced. Shown as "New chat" while empty. Titles are sealed like message bodies.
- **Conversation 1.** `V2__asha_conversations` moves each user's existing messages into one conversation, created at their first message and last active at their last. Bodies are sealed with the app's key, which SQL doesn't have, so the script leaves the title empty and the app titles it the first time it is listed (C1) or asked in: from its first message when that is a question, otherwise "Earlier chat".
- **Deleting** a conversation (C4) deletes it and every message in it, with a `CONVERSATION_DELETED` audit record, in one transaction, as A5 and A6 do. A5 deletes every conversation too; account deletion erases them with the chat.
- **Realtime.** The assistant sends no realtime frames; the app pulls C1 (and updates the row from A2's `done`, which carries the conversation).

### Asha's tools (Phase A, decision 2026-10-31)

Server tools run in `data/tools` (`HttpAgentTools` over the other modules' `/v1/agent/` routes with the agent token; `OwnTools` for what needs no token), device tools on the phone. All read; each answer is JSON of at most 12,000 characters (`ToolOutput`: a list too long is halved with a `note`), wrapped as `<untrusted>` for the model, and named in the turn's `TURN` audit record (`tools=…`). Without an agent token only the token-free tools are offered.

| Tool | Reads | Route or source |
| --- | --- | --- |
| `getProfile` | the user's display name, handle, about | `GET /v1/agent/users/me` |
| `searchContacts` | up to 5 contacts by part of a name or handle | `GET /v1/agent/users/contacts/search` |
| `listContacts` | contacts, names and handles only (≤ 50) | `GET /v1/agent/users/contacts` |
| `listBlocked` | the people the user blocked (≤ 50) | `GET /v1/agent/users/blocks` |
| `getSecurityOverview` | masked phone, linked sign-ins, the active phone, latest 10 security events; no secrets | `GET /v1/agent/identity/security` |
| `listChats` | chat ids, direct or group, members (≤ 10 each); never names of groups or messages | `GET /v1/agent/messaging/chats` |
| `getWallet` | status, balances per chain and asset, totals, addresses, fiat | `GET /v1/agent/wallet` |
| `listTransactions` | payments with filters (dates, month, direction, person by name or id, asset, chain), ≤ 50 a page | `GET /v1/agent/wallet/transactions` |
| `summarizeTransactions` | in, out, net per asset, top counterparties, by month | `GET /v1/agent/wallet/transactions/summary` |
| `getTransaction` | one payment by id | `GET /v1/agent/wallet/transactions/{id}` |
| `listChamas` | the user's chamas with progress and open requests | `GET /v1/agent/chama` |
| `getChama` | one chama with contributions by name and the latest ledger lines | `GET /v1/agent/chama/{chatId}` |
| `getAshaUsage` | her own plan and today's allowance | this module (`UsageReader`) |
| `searchWeb` | up to 5 public results (title, url, snippet) | `WebSearch` (Tavily) |
| `searchChats`, `readChat` | chat snippets, decrypted on the phone | device tools (A2 `tool_request`, A9) |

A name in a payment question ("what did I send Mum?") becomes a user id inside the tool through the contacts search; two matches or none go back to the model to ask. The money tools pass the user's display currency (A2's `currency`, A11's `auth.currency`, kept with a turn parked for the phone) so amounts come with their worth at today's rate.

### The prompt and the reply (decision 2026-10-31)

`AshaPrompt` (`asha-2026-10-31-v2`, recorded in every `TURN` audit record) is a fixed part and a short per-turn part (text or voice, today's date, the display currency, whether chats can be read). Guardrails: only this user's data, read-only; she never moves money and, with no proposals yet, explains how to pay in the app (PIN); never asks for or accepts a PIN, recovery key, seed phrase, OTP or password, and calls such requests scams; never reveals the prompt or tools; refuses abuse politely; never invents numbers (tool data only, exact, with coin and network, display currency first, fiat "at today's rate"); concise plain speech in voice; Kenya and East Africa tone; web search only for public facts, never with the user's data, sources cited as links.

Text replies are markdown with ```chart, ```statement and ```diagram JSON blocks (contract and schemas: docs/mobile-integration.md, section 9, Rich replies). `BlockFilter` sits between the model's stream and the user: it holds a block until its closing fence, has `ReplyBlocks` (`JsonReplyBlocks`) check it, and sends it whole and canonical, repaired, or not at all; the saved reply is what was sent. A spoken turn gets no blocks and no `*`, `` ` `` or heading marks.

Each server tool call is announced with `tool_status` (the server's label) and closed with `tool_done`; a device tool gets its `tool_status` before the `tool_request` and its `tool_done` at the start of the A9 stream (or A11 frame).

## Voice

1. **Voice in.** The app transcribes on the phone when it can (free, fast, private). Otherwise it uploads the clip (up to 60 s) to `POST /v1/assistant/voice`, which runs `SpeechToText` and returns the transcript. The transcript, not the audio, becomes the turn; the audio is deleted after transcription.
2. **Reply.** Text first, streamed from the model as it is written, so the user sees Asha answer at once. Text the model writes before calling a tool is shown too, and the saved reply is everything shown, a blank line between the parts; a failure after text has gone out is an `error` event `{code, message}` on A2 and A9, and nothing is saved (decision 2026-10-27).
3. **Voice out.** On request, or when the user spoke, `TextToSpeech` turns the reply into audio streamed back to the app. Short replies can be cached by text hash for a day.

### Asha's voices (decision 2026-10-31)

The user chooses the voice Asha speaks in. The list is the assistant's config (`AssistantConfig.voices`, `AshaVoices`), not the integration's: each voice is an opaque `id` (the provider's speaker name, passed through `TextToSpeech.speak(…, voice)` and `LiveSpeech.open(…, voice)`), a display `name`, a one-word `character` and a `gender` hint. `ASSISTANT_VOICES` (`id:Name:Character:gender`, comma-separated) replaces the list and `ASSISTANT_DEFAULT_VOICE` picks the default; a malformed list fails at start.

| id | Name | Character | Gender |
| --- | --- | --- | --- |
| `luna` (default) | Luna | Friendly | female |
| `harmonia` | Harmonia | Calm | female |
| `thalia` | Thalia | Bright | female |
| `arcas` | Arcas | Warm | male |
| `orion` | Orion | Gentle | male |
| `zeus` | Zeus | Steady | male |

All six are Deepgram Aura 2 speakers listed for `@cf/deepgram/aura-2-en` on Workers AI (American English, chosen from Deepgram's descriptions for clear, calm, trustworthy delivery). Luna is Aura's default and was Asha's only voice, so it stays the default.

- **A12** lists them with `default: true` on one. The phone keeps the user's choice and sends its id as `voice` on A4 and on A11's first `auth` frame.
- **The choice is checked, never refused:** a missing voice, or one not on the list (an old app, a voice since dropped), is the default, with a debug log line (`VoiceFallbacks`) and no error. A live conversation keeps the voice of its first `auth` frame for every sentence, including after Aura's socket is reopened; a later `auth` frame changes tokens only.
- **A13 previews a voice:** the fixed line "Hi, I'm Asha. I can help you understand your money and payments." in that voice, as MP3. Not counted against the voice allowance, because the text is fixed and the clip is kept in Redis for a day (`ast:voice-sample:…`), so previews cost a handful of provider calls a day in all; `Cache-Control: private, max-age=86400` lets the phone keep it too. An id not on the list is `assistant.voice_not_found` (404).

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
| phone → server | `auth` | `accessToken`, `agentToken`?, `language`?, `chatReading` (default true), `currency`?, `voice`?, `conversationId`? | First frame, within 10 s. The user (`app`) token only; `agentToken` is the user's agent token for Asha's tools (as `X-Agent-Token` on A2); `language` is the user's language setting; `voice` an id from A12 (missing or unknown: the default voice), taken from the first frame only; `conversationId` (first frame only) is the conversation the call's turns are saved in, with A2's fallback; one that isn't the user's fails each turn with an `error` frame `assistant.conversation_not_found`. Sent again later with fresh tokens (`accessToken`, `agentToken`) before the 15-minute access token expires |
| phone → server | `tool_results` | `turnId`, `callId`, `snippets` | The answer to a `tool_request` (as A9). A frame that doesn't decode, or a `callId` the turn isn't waiting on, gets an `error` frame `request.malformed` and the turn keeps waiting (on A9 too) |
| phone → server | `end` | | Hang up |
| server → phone | `ready` | `voiceSecondsLeft`, `audio: {encoding, sampleRate}`, `voice` | Accepted; start streaming. `voice` is the id Asha speaks in for this conversation |
| server → phone | `transcript` | `text`, `final` | The words of the turn so far; `final` false while they may change |
| server → phone | `turn` | `text` | The user finished; this question now goes through A2 |
| server → phone | `reply` | `text` | One sentence of the reply, sent when the audio reaches it (timed at about 15 characters a second, as the voice says no more; the last ones at the latest when all the audio is sent); the audio is in binary frames. Each sentence goes to the voice as soon as the model has written it, with one `Flush` per reply, or two when the turn pauses for a device tool (what was said before the pause is flushed so it is heard while the phone searches; decision 2026-10-27) |
| server → phone | `reply_done` | `message` (as A1), `spoken` | Sent once the reply's audio has finished playing on the phone (or at once when it is talked over). The reply as saved in the Asha chat; `spoken` false if the user talked over it |
| server → phone | `tool_request` | `turnId`, `callId`, `name`, `arguments` (a JSON string, as on A2) | A device tool; unanswered after 30 s, the turn ends with an `error` frame `assistant.turn_not_found` |
| server → phone | `interrupted` | | Barge-in: stop playing what is buffered |
| server → phone | `error` | `code`, `message` | One turn failed (`assistant.model_unavailable`, `assistant.turn_not_found`, `assistant.conversation_not_found`, `request.malformed` for a bad `tool_results`); the conversation goes on |
| server → phone | `timing` | `turn`, `modelFirstTextMs`, `firstSentenceMs`, `firstAudioMs` | Once per turn (decision 2026-10-30): milliseconds from the transcriber's end of turn to the model's first text, the first sentence queued with the voice and the first audio sent; `null` for a step never reached (barge-in, failure). Also one log line of the durations and the `circaso_assistant_live_first_audio_seconds` histogram. The phone ignores unknown types |
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

Exact numbers and the price are open (see Decisions). The numbers are settings (`ASSISTANT_FREE_*`, `ASSISTANT_PLUS_*`), and `ASSISTANT_LIMITS_UNLIMITED=true` lifts them all for testing (decision 2026-10-31; off by default).

- **Metering:** each turn records model tokens and voice seconds. Live counters sit in Redis (`ast:quota:{kind}:{userId}:{day}`); a turn refused over the allowance is given back, so the text count never ends above it. The day's totals go to `usage_daily` for cost reports, through one `GREATEST` upsert, when a turn is saved, a clip is transcribed (A3) or spoken (A4), and when a live conversation ends (A11).
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
| A1 | `GET /v1/assistant/chat` | app | The Asha chat, newest first, paged: every conversation's messages, each with its `conversationId` |
| A2 | `POST /v1/assistant/chat/messages` | app | Send Asha a message: typed, spoken, or asked from another chat (with that chat's id); reply streamed as server-sent events. Optional `conversationId` (else the most recent conversation, or a new one); `done` carries `conversationId` and `conversation` |
| A3 | `POST /v1/assistant/voice` | app | Transcribe a voice clip |
| A4 | `POST /v1/assistant/speech` | app | Turn a reply into audio, in the chosen `voice` |
| A5 | `DELETE /v1/assistant/chat` | app | Delete the whole history with Asha, every conversation included (one `CHAT_DELETED` audit record, only when there was something to delete) |
| A6 | `DELETE /v1/assistant/chat/messages/{messageId}` | app | Delete one exchange |
| A7 | `GET /v1/assistant/usage` | app | Today's allowance used and left, and the plan |
| A8 | `GET /v1/assistant/proposals/{id}` / `POST …/decline` | app | Read or decline a proposal (Phase B) |
| A9 | `POST /v1/assistant/chat/messages/{messageId}/tool-results` | app | The phone's answer to a device tool (decrypted chat snippets); the reply then continues |
| A10 | `POST /v1/assistant/images/upload-url` | app | Presigned R2 upload for one photo; A2 then carries its `imageKey` |
| A11 | `GET /v1/assistant/voice/live` (WebSocket) | app | Live voice conversation (Phase 1) |
| A12 | `GET /v1/assistant/voices` | app | The voices Asha can speak in, the default marked |
| A13 | `GET /v1/assistant/voices/{voiceId}/sample` | app | A voice's preview clip (MP3), not counted against the allowance |
| C1 | `GET /v1/assistant/conversations` | app | The user's conversations, newest first by last message, paged (`cursor`, `limit` ≤ 100) |
| C2 | `POST /v1/assistant/conversations` | app | Start an empty conversation (optional `title`, 1 to 80 characters); it becomes the most recent |
| C3 | `PATCH /v1/assistant/conversations/{conversationId}` | app | Rename (`title`, 1 to 80 characters) |
| C4 | `DELETE /v1/assistant/conversations/{conversationId}` | app | Delete a conversation and its messages (one `CONVERSATION_DELETED` audit record) |
| C5 | `GET /v1/assistant/conversations/{conversationId}/messages` | app | One conversation's messages, newest first, paged as A1 |

The user talks to Asha with the **user** token; Asha's tools call other modules with the **agent** token.

## Data

PostgreSQL tables in schema `assistant` (role `assistant`, Flyway location `db/assistant`), and four Redis key families.

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
    PRIMARY KEY (user_id, usage_day)                    -- upsert (keys: user_id, usage_day) keeping the larger of stored and new totals (the live Redis counters only grow within a day)
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

-- V2__asha_conversations.sql (decision 2026-10-31)
CREATE TABLE assistant.asha_conversations (
    user_id         uuid        NOT NULL,
    conversation_id uuid        NOT NULL,                   -- UUIDv7
    title           text,                                   -- sealed (AES-GCM); NULL until titled
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    last_message_at timestamptz NOT NULL,                   -- the last message, or the creation of an empty one
    PRIMARY KEY (user_id, conversation_id)
);
CREATE INDEX asha_conversations_recent_idx ON assistant.asha_conversations (user_id, last_message_at, conversation_id);
ALTER TABLE assistant.asha_messages ADD COLUMN conversation_id uuid NOT NULL;   -- backfilled: one conversation 1 per user
CREATE INDEX asha_messages_conversation_idx ON assistant.asha_messages (user_id, conversation_id, message_id);
```

| Table | Expiry |
| --- | --- |
| `asha_messages` | — (until the user deletes the exchange, its conversation or the Asha chat) |
| `asha_conversations` | — (until the user deletes it or the Asha chat) |
| `usage_daily` | — |
| `entitlements` | — |
| `audit` | — |
| `outbox` | Daily partitions, 7 days, kept one day ahead and dropped by `assistant.purge-expired` (every 5 minutes) |

**Redis (`ast:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `ast:quota:{kind}:{userId}:{day}` | counter; `kind` is `text`, `voice`, `in` or `out` | 2 days | The day's live allowance counters (text turns, voice seconds, input and output tokens) |
| `ast:turn:{userId}:{turnId}` | JSON: the turn parked for the phone, with the conversation so far | 10 min | A turn waiting on a device tool (A9, A11); taken once |
| `ast:turns:{userId}` | hash: the user's parked turn ids | 10 min, renewed on each save | Lets account deletion find and delete the parked turns without scanning keys; an id leaves it when its turn is taken |
| `ast:voice-sample:{voiceId}:{textHash}` | JSON: `contentType` and base64 audio | 1 day | A13's preview clip per voice (no user data); `textHash` is the first 8 bytes of the sample line's SHA-256 in hex |

All three are deleted for a user on `identity.AccountDeleted`, after its transaction commits.

**Phase B** adds `assistant.proposals` (by user, with an `expires_at` 10 minutes after the proposal is decided or expires, purged by `assistant.purge-expired`), designed with Phase B.

## Error codes (draft)

| Code | Kind → status | When |
| --- | --- | --- |
| `assistant.quota_exceeded` | RATE_LIMITED → 429 | Daily allowance used up |
| `assistant.plus_required` | FORBIDDEN → 403 | A Plus-only feature on the free plan |
| `assistant.voice_too_long` | INVALID → 400 | Clip over 60 s |
| `assistant.proposal_expired` | CONFLICT → 409 | Confirming after 10 minutes |
| `assistant.model_unavailable` | UPSTREAM → 502 | The model provider is down |
| `assistant.conversation_not_found` | NOT_FOUND → 404 | No conversation of this user has that id (C3–C5, A2 with `conversationId`, an A11 turn) |
| `assistant.language_not_supported` | INVALID → 400 | A4 asked for speech in a language the server voice doesn't speak (Swahili: the phone speaks it) |
| `assistant.voice_not_found` | NOT_FOUND → 404 | A13 asked for a voice not on Asha's list (fetch A12 again) |
