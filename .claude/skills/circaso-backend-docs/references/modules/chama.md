# chama

Built 2026-10-29 (first slice). A **chama** is a group chat with a shared goal. The design (D2–D6) draws a group-held wallet with approvals; a user-controlled wallet can't be owned by a group, so the money sits in one member's wallet, the **treasurer** (decision 2026-10-29). Contributions are ordinary USDC payments from members to the treasurer, paid from the chat (W4 with `chatId`); paying out needs the admins' approval, then the treasurer's own PIN. No group-owned key exists anywhere; the module records and shows, it never moves money.

## Overview

### Owns
- The goal per group chat: name, target (USDC), optional deadline, treasurer.
- Contributions: one row per confirmed transfer from a member to the treasurer from that chat (from `wallet.TransferConfirmed`).
- Payment requests: who asked to pay whom, how much, the admins' decisions, the treasurer's transfer once linked, and the state (`PENDING` → `APPROVED` → `PAID`; `DECLINED`; `EXPIRED` after 48 h).
- Its realtime frames (`chama/*`) and the push wording the gateway gives them.

### Contract (`chama:api`)
- `Chamas.hasGoal(chatId)`.
- Frames: `goal.updated`, `contribution.added`, `request.updated` (`Frames.kt`).
- Events on `events:chama`: `chama.ContributionRecorded`, `chama.RequestPaid`.

### Depends on
- `messaging:api` `ChatDirectory` (members, and `admins`, added 2026-10-29).
- `wallet:api` `Wallets.transfer` (added 2026-10-29) to check the treasurer's transfer, and `wallet.TransferConfirmed` (now carrying `chatId`).
- `users:api` `Profiles` for names in frames.

## Endpoints

| ID | Route | Scope | Use case | Notes |
| --- | --- | --- | --- | --- |
| CH1 | `GET /chama/chats/{chatId}` | app | `GetChama` | goal, totals (`contributed`, `paidOut`, `held`), `myContribution`, contributors, open requests, `isAdmin`, `isTreasurer` |
| CH2 | `PUT /chama/chats/{chatId}/goal` | app | `SetGoal` | admins; treasurer defaults to the caller; `goal.updated` to all |
| CH3 | `GET /chama/chats/{chatId}/ledger` | app | `ListLedger` | contributions in and paid requests out, newest first; `?limit=&before=` |
| CH4 | `POST /chama/chats/{chatId}/requests` | app | `CreateRequest` | any member; needs 2 admins (all when fewer); an admin asking counts; 48 h |
| CH5 | `POST /chama/requests/{id}/approve` and `/decline` | app | `DecideRequest` | admins, once each; one decline closes |
| CH6 | `POST /chama/requests/{id}/payment` | app | `AttachPayment` | treasurer links their W4 transfer (same payee, amount, chat); `PAID` on confirmation |

Agent routes (read-only, for Asha; built 2026-10-31; scope `agent`, `GET` only; answers in `chama:api` `AgentRoutes.kt`, USDC in whole units as exact strings; service `ChamaAgentService`):

| Route | Use case | Answers |
| --- | --- | --- |
| `GET /v1/agent/chama` | `ListMyChamas` | `AgentChamasResponse`: every group the caller is in that has a goal (`ChatDirectory.groupsOf`, new in `messaging:api`), at most 20: target, contributed, paid out, held, the caller's contribution, progress, deadline, treasurer, open requests (at most 10) |
| `GET /v1/agent/chama/{chatId}` | `GetChamaDigest` | `AgentChamaResponse`: the same plus everyone's contributions by name (at most 30) and the latest 10 ledger lines; members only (`chama.not_a_member`, `chama.no_goal`) |

`GetChama`, `ListLedger` and both agent use cases read through the same `ChamaViews` helper, so they add up the same way.

Contributing has no route of its own: the phone pays the treasurer with W4 (`to.userId` = treasurer, `chatId` = the group) and sends the payment card into the chat; the wallet's `TransferConfirmed` makes it a contribution.

## Data

Schema `chama`: `goals` (chat_id PK), `contributions` (transfer_id PK; chat, user, amount, at), `requests`, `approvals` (request_id, user_id PK), `realtime` (daily partitions), `outbox`. Redis prefix `cha:` (`cha:seq:{userId}`). Jobs: `chama.purge-expired` (partitions), `chama.expire-requests` (10 min).

## Events

Consumes `events:wallet` `TransferConfirmed` (group `chama`): a contribution when the transfer went from a member to the treasurer from the chat; `PAID` when a request names it. Idempotent (insert-ignore, state guard).

## Error codes

`chama.not_a_member` 404, `chama.not_admin` 403, `chama.not_treasurer` 403, `chama.no_goal` 404, `chama.goal_invalid` 400, `chama.treasurer_not_member` 400, `chama.amount_invalid` 400, `chama.payee_invalid` 400, `chama.request_not_found` 404, `chama.request_closed` 409, `chama.already_decided` 409, `chama.not_approved` 409, `chama.transfer_mismatch` 400.

## Not in v1

A group-owned wallet (needs multi-party signing Circle's user-controlled wallets don't give); group invite links (messaging); business groups (D7); statements export.
