# Modules

One file per business module, each in the same shape, so a new module is a new file that follows the template below.

## Module map

| Module | Status | Keyspace | Stream | Owns | Depends on (`api`) |
| --- | --- | --- | --- | --- | --- |
| identity | Designed — [identity.md](identity.md), screens in [identity-signin-flow.md](identity-signin-flow.md) | `circaso_identity` | `events:identity` | Accounts, Google/Apple sign-in, phone, devices, sessions, support recovery | — |
| users | Designed — [users.md](users.md) | `circaso_users` | `events:users` | Profiles, handles, contacts, blocks, the reach rule | identity |
| messaging | Draft — [messaging.md](messaging.md) | `circaso_messaging` | `events:messaging` | 1:1 and group chats, messages, delivery, push for chat | users |
| wallet | Draft — [wallet.md](wallet.md) | `circaso_wallet` | `events:wallet` | Circle user-controlled wallets, transaction records, statements | identity, users, messaging |
| calls | Planned | `circaso_calls` | `events:calls` | 1:1 and group calls on Cloudflare Realtime, contributions during calls | users |
| assistant (Asha) | Outlined — [assistant.md](assistant.md) | `circaso_assistant` | `events:assistant` | Asha conversations, voice, usage and Asha Plus, proposals, audit trail | identity, users; later messaging, wallet |

## Adding a module

A new module is a new file here plus the same code skeleton as the first two; nothing in the architecture changes unless the new module needs a new kind of infrastructure.

**In the documents**

1. Add `docs/modules/<name>.md` with the template headings below, and a row to the module map.
2. Record every decision it needs in `docs/decisions.md`, with the reason.
3. Add it to the system overview in `docs/architecture.md`.

**In the code**

1. `modules/<name>/api` and `modules/<name>/impl`, with the `presentation` (routes, controllers, services, dto, mappers, events, agent, api), `domain` (models, repositories, usecases, errors), `data` (entities, repositories, mappers, migrations) and `di` folders from Module anatomy in the architecture doc.
2. Keyspace `circaso_<name>`, declared in its `KandraModuleSpec`; entities end in `Row` and don't clash with other modules' names (its outbox is `<Name>OutboxRow`).
3. Redis prefix, stream `events:<name>`, consumer group `<name>` on any stream it reads.
4. Error codes `<name>.<reason>`, an OpenAPI tag `<name>`, metrics prefixed `circaso_<name>_`.
5. If it sends realtime frames, implement `RealtimeReplay`. If the assistant may read from it, add read-only routes in `presentation/agent/`.
6. The Gradle convention plugins and Konsist rules apply automatically; nothing to opt into.

**Template for a module file**

```
# <Name> module
<one-line answer to the question the module owns>; keyspace, Redis prefix, stream

## Overview
### Owns
### Contract (<name>:api)
### Integrations used
### Depends on

## Endpoints
(route table: #, route, scope, service method → use case, does; then request/response samples)

## Data
(Kandra entities, then Redis keys)

## Events
(consumed, published with payloads, realtime events)

## Error codes
```
