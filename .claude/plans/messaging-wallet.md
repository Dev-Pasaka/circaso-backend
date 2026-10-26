# Build plan: messaging and wallet

Approved by Pasaka 2026-10-25 ("proceed"), including the proposals in both drafts. The spec is the source of truth:
`.claude/skills/circaso-backend-docs/references/modules/messaging.md`, `.../modules/wallet.md`, `decisions.md`,
`architecture.md`, `working-rules.md`. This file only orders the work and says what "done" means for each slice.

## Team and flow

One slice at a time, in this order. Each slice runs:

1. **Developer** builds it on branch `feat/<slice-id>` (from `main`), with tests, `./gradlew spotlessApply build` green, and commits.
2. **Architect** reviews the branch against the spec and these rules: `APPROVED` or a numbered list of required changes. Back to 1 until approved.
3. **QA** runs the server locally (port 8081, local ScyllaDB and Redis, never the cloud cluster) and exercises every endpoint and frame of the slice, happy path and failure cases. Bugs go back to the developer with repro steps. Back to 1 (and 2 if the fix is more than small).
4. **Devops** runs the slice's migrations against the sandbox cluster, merges to `main`, pushes (this triggers `.github/workflows/deploy-dev.yml`), watches the run, and smoke-tests `https://sandbox.circaso.com`.

Nobody changes the architecture. A gap or contradiction in the spec stops the slice and goes to Pasaka through the orchestrator.

## Slices

### Messaging

| Id | Slice | Done when |
| --- | --- | --- |
| MS1 | Foundation: `RealtimeInbound` + `ClientFrame` in `shared`, hub routing of phone frames, `Realtime.deliverMany` (one publish on the `rt:fanout` pub/sub channel every node reads; pipelined single-key offline lookups only for push-worthy frames), push wording entries; `modules/messaging/{api,impl}` skeleton, keyspace `circaso_messaging`, DI, `LOG_LEVEL_MESSAGING`, OpenAPI tags in `ApiDocsCatalog` | Existing tests green; new hub and fan-out tests; Konsist passes |
| MS2 | Key directory M1–M3 (device-signed upload via `identity.DeviceSignatures`, one-time prekey claim with LWT, bundle rate limit) | Tests for proof failure, prekey exhaustion, rate limit, reach |
| MS3 | Chats and membership M4–M11, `ChatDirectory` (and the shared-group path in M3 `FetchKeyBundle`, `TODO(MS3)`), `msg:members` cache, reach on add, 1,000 cap, roles, sealed metadata, `member.*` and `chat.updated` frames | Tests for pair claim race, cap, admin rules, leave, not-reachable |
| MS4 | Sending and delivery M12–M14: data-only option on `PushMessage` + `FcmPush`, inbox, group body + pointer rows, idempotent send, socket `ack` / `read` / `typing`, receipts batched 5 s, typing ≤ 50 members, data-only push, `RealtimeReplay`, undeliverable on `DeviceReplaced` | Tests for fan-out, ack deletion, group body deletion, replay, blocks |
| MS5 | Media M15–M17: presigned single and multipart (> 50 MB, 16 MB parts, 500 MB cap), recipients fixed at upload, delete on last ack, `MediaSweep` daily job, group avatars | Tests with the R2 fake; sweep test |
| MS6 | History M18–M20, key backup M21 (passkey and recovery-key wraps), settings M22, agent route, consumed events (`DeviceReplaced`, `AccountStatusChanged`, `AccountDeleted`), published events | Tests for ownership, rate limits, deletion cascade |

### Asha on Workers AI (decided 2026-10-25, runs beside messaging)

| Id | Slice | Done when |
| --- | --- | --- |
| AI1 | Integrations on Workers AI through AI Gateway (`HttpClients.named("workers-ai")`): `LanguageModel` (kimi-k2.6, tool calls), new `VisionModel` (llama-4-scout), `SpeechToText` (whisper-large-v3-turbo clips), `TextToSpeech` (aura-2-en); provider `workers-ai` for `INTEGRATIONS_LLM/STT/TTS/VISION_PROVIDER`; settings in `.env.example` | MockEngine tests for each call, tool-call round trip, error mapping; a manual smoke test against the real gateway once credentials exist |
| AI2 | Live voice: a `webSockets` flag on `VendorHttpConfig` (decision 2026-10-25), `LiveTranscription` (Nova-3, Opus) and `LiveSpeech` (Aura) over Ktor WebSocket client; assistant A11 socket with turn detection, barge-in, metering, silence close; Swahili falls back to clips | Tests with fake streams; a local end-to-end run |
| AI3 | Images: A10 upload URL, A2 with `imageKey`, `VisionModel` description into the turn, deletion after the turn, metering | Tests for size/type, deletion, metering |

### Gateway follow-ups (from MS1 QA, decided 2026-10-25)

| Id | Slice | Done when |
| --- | --- | --- |
| GW1 | Phone frames: at most 100 per second per socket (excess dropped and counted, socket stays open), a maximum frame size on the WebSocket plugin (proposed 64 KB, the largest chat ciphertext), a clear close reason for a malformed `last` in the auth frame; sign-out (I8) sends `system.session.revoked` and closes that session's socket | Tests for each; done before MS4 |
| GW2 | Idempotency replays non-envelope responses (A4 audio, A2 SSE): today a repeated `Idempotency-Key` after success gets `409 request.in_progress` until expiry, so a phone can't recover a lost response; store a replayable result or answer `409` with a clear "already done" code. Voice quota refusals (A3, A4) give `retryAfterSeconds` until the reset, not a fixed 3600 (`RESET_HINT_SECONDS`) | Tests for replay of each response kind; quota hint equals seconds to reset |
| GW3 | A new sign-in that revokes a previous session (same phone) also closes that session's open socket (emit `identity.SignedOut` or a session-revoked event for it); `last` values below 0 give `invalid last` | Tests for both |

### Wallet

| Id | Slice | Done when |
| --- | --- | --- |
| WS1 | Integrations: `StablecoinCustody`, `CircleCustody` over `HttpClients.named("circle")` (MockEngine tests), `fake` provider, `CircleWebhook` (ECDSA P-256/SHA-256, key by `X-Circle-Key-Id` cached), `WebhookVendor` able to fetch a key, `CustodyNotifications`; all Circle configuration in integrations (`INTEGRATIONS_CUSTODY_PROVIDER` = log | fake | circle, `CIRCLE_API_KEY`, `INTEGRATIONS_CUSTODY_APP_ID`, `_BLOCKCHAIN`, `_ACCOUNT_TYPE`, `_USDC_TOKEN_ID` in `.env.example`), Circle user tokens cached inside `CircleCustody`; the wallet module never reads a Circle setting | MockEngine tests for every call and error mapping; webhook signature tests |
| WS2 | Wallet module skeleton, W1–W3 calling only `StablecoinCustody`, `DeletionGuard` | Tests for create, re-create while setting up, already exists, guard |
| WS3 | Transfers W4, W5, W9–W11: rule order, idempotency, `refId`, states, webhook updates, frames, push, published events, remind | Tests for each rule, webhook ordering, duplicates |
| WS4 | Transactions W6–W7, jobs (`ExpireUnsignedTransfers`, `StuckTransfers`, `ReconcileWallets`), payment hold on `AccountRelinked`, agent routes | Tests for statements, jobs, hold |

Wallet runs against the `fake` custody provider on the sandbox until Pasaka adds Circle sandbox credentials to `/opt/circaso/.env`.

## Every slice also

- Documents every route with `.docs<Req, Res>()` under a catalog tag; updates `gateway/src/main/resources/docs/guide.md` (error table) and `docs/mobile-integration.md` (status per route: READY / PARTIAL / DEV STUB).
- Records real Kandra bugs in `docs/kandra-bugs.md`.
- Leaves `// TODO(decision: …)` where the spec has an open question, and lists doc gaps in its report.
- Never touches `email-templates/build.ts` (Pasaka's uncommitted change) or any `.env*` file except `.env.example`.
