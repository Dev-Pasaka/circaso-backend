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
| MS1 | Foundation: `RealtimeInbound` + `ClientFrame` in `shared`, hub routing of phone frames, `Realtime.deliverMany` (one `MGET`, one publish per node), push wording entries; `modules/messaging/{api,impl}` skeleton, keyspace `circaso_messaging`, DI, `LOG_LEVEL_MESSAGING`, OpenAPI tags in `ApiDocsCatalog` | Existing tests green; new hub and fan-out tests; Konsist passes |
| MS2 | Key directory M1–M3 (device-signed upload via `identity.DeviceSignatures`, one-time prekey claim with LWT, bundle rate limit) | Tests for proof failure, prekey exhaustion, rate limit, reach |
| MS3 | Chats and membership M4–M11, `ChatDirectory`, `msg:members` cache, reach on add, 1,000 cap, roles, sealed metadata, `member.*` and `chat.updated` frames | Tests for pair claim race, cap, admin rules, leave, not-reachable |
| MS4 | Sending and delivery M12–M14: inbox, group body + pointer rows, idempotent send, socket `ack` / `read` / `typing`, receipts batched 5 s, typing ≤ 50 members, data-only push, `RealtimeReplay`, undeliverable on `DeviceReplaced` | Tests for fan-out, ack deletion, group body deletion, replay, blocks |
| MS5 | Media M15–M17: presigned single and multipart (> 50 MB, 16 MB parts, 500 MB cap), recipients fixed at upload, delete on last ack, `MediaSweep` daily job, group avatars | Tests with the R2 fake; sweep test |
| MS6 | History M18–M20, key backup M21 (passkey and recovery-key wraps), settings M22, agent route, consumed events (`DeviceReplaced`, `AccountStatusChanged`, `AccountDeleted`), published events | Tests for ownership, rate limits, deletion cascade |

### Wallet

| Id | Slice | Done when |
| --- | --- | --- |
| WS1 | Integrations: `StablecoinCustody`, `CircleCustody` over `HttpClients.named("circle")` (MockEngine tests), `fake` provider, `CircleWebhook` (ECDSA P-256/SHA-256, key by `X-Circle-Key-Id` cached), `WebhookVendor` able to fetch a key, `CustodyNotifications`; `INTEGRATIONS_CUSTODY_PROVIDER`, `CIRCLE_API_KEY`, `CIRCLE_APP_ID`, chain setting in `.env.example` | MockEngine tests for every call and error mapping; webhook signature tests |
| WS2 | Wallet module skeleton, W1–W3, `DeletionGuard`, `wal:session` sealed cache | Tests for create, re-create while setting up, already exists, guard |
| WS3 | Transfers W4, W5, W9–W11: rule order, idempotency, `refId`, states, webhook updates, frames, push, published events, remind | Tests for each rule, webhook ordering, duplicates |
| WS4 | Transactions W6–W7, jobs (`ExpireUnsignedTransfers`, `StuckTransfers`, `ReconcileWallets`), payment hold on `AccountRelinked`, agent routes | Tests for statements, jobs, hold |

Wallet runs against the `fake` custody provider on the sandbox until Pasaka adds Circle sandbox credentials to `/opt/circaso/.env`.

## Every slice also

- Documents every route with `.docs<Req, Res>()` under a catalog tag; updates `gateway/src/main/resources/docs/guide.md` (error table) and `docs/mobile-integration.md` (status per route: READY / PARTIAL / DEV STUB).
- Records real Kandra bugs in `docs/kandra-bugs.md`.
- Leaves `// TODO(decision: …)` where the spec has an open question, and lists doc gaps in its report.
- Never touches `email-templates/build.ts` (Pasaka's uncommitted change) or any `.env*` file except `.env.example`.
