# Modules

One file per business module, each in the same shape, so a new module is a new file that follows the template below.

## Module map

| Module | Status | Schema | Stream | Owns | Depends on (`api`) |
| --- | --- | --- | --- | --- | --- |
| identity | Designed — [identity.md](identity.md), screens in [identity-signin-flow.md](identity-signin-flow.md) | `identity` | `events:identity` | Accounts, Google/Apple sign-in, phone, devices, sessions, support recovery | — |
| users | Designed — [users.md](users.md) | `users` | `events:users` | Profiles, handles, contacts, blocks, the reach rule | identity |
| messaging | Draft — [messaging.md](messaging.md) | `messaging` | `events:messaging` | 1:1 and group chats, messages, delivery, push for chat | users |
| wallet | Draft — [wallet.md](wallet.md) | `wallet` | `events:wallet` | Circle user-controlled wallets, transaction records, statements | identity, users, messaging |
| calls | Planned | `calls` | `events:calls` | 1:1 and group calls on Cloudflare Realtime, contributions during calls | users |
| assistant (Asha) | Outlined — [assistant.md](assistant.md) | `assistant` | `events:assistant` | Asha conversations, voice, usage and Asha Plus, proposals, audit trail | identity, users; later messaging, wallet |

## Adding a module

A new module is a new file here plus the same code skeleton as the first two; nothing in the architecture changes unless the new module needs a new kind of infrastructure.

**In the documents**

1. Add `docs/modules/<name>.md` with the template headings below, and a row to the module map.
2. Record every decision it needs in `docs/decisions.md`, with the reason.
3. Add it to the system overview in `docs/architecture.md`.

**In the code**

1. `modules/<name>/api` and `modules/<name>/impl`, with the `presentation` (routes, controllers, services, dto, mappers, events, agent, api), `domain` (models, repositories, usecases, errors), `data` (tables, repositories, cache) and `di` folders from Module anatomy in the architecture doc.
2. The database (Architecture → Infrastructure → Exposed and PostgreSQL):
   - **Schema** `<name>` and a **role** `<name>` with rights only on that schema, both created by the `Migrate` step; `circaso_app` and `circaso_jobs` are granted the role, and the module's tables are owned by it.
   - **Flyway location** `classpath:db/<name>`: plain SQL in `impl/src/main/resources/db/<name>/V1__<name>.sql`, numbered on its own, with its own history table.
   - **`DatabaseModuleSpec(schema = "<name>", tables, migrationsLocation = "classpath:db/<name>")`** contributed from the module's `di`.
   - Exposed `Table` objects in `data/tables`, named `<Thing>Table` with the schema in the table name (`Table("<name>.<things>")`), including its `realtime` if it sends frames (partitioned by day). Its `outbox` is infrastructure's: `outbox = true` in the spec, the shared DDL (architecture.md → Durable events) in V1, and `Database.writeOutbox` in the transaction.
   - A `<name>.purge-expired` job if any table has `expires_at` or daily partitions.
3. Redis prefix, stream `events:<name>`, consumer group `<name>` on any stream it reads.
4. Error codes `<name>.<reason>`, an OpenAPI tag `<name>`, metrics prefixed `circaso_<name>_`.
5. If it sends realtime frames, implement `RealtimeReplay`. If the assistant may read from it, add read-only routes in `presentation/agent/`.
6. The Gradle convention plugins and Konsist rules apply automatically; nothing to opt into.

**Template for a module file**

```
# <Name> module
<one-line answer to the question the module owns>; schema, Redis prefix, stream

## Overview
### Owns
### Contract (<name>:api)
### Integrations used
### Depends on

## Endpoints
(route table: #, route, scope, service method → use case, does; then request/response samples)

## Data
(PostgreSQL tables as SQL: schema, columns and types, primary key, unique and secondary indexes, expiry by `expires_at` or daily partition, and what each unique constraint settles; then Redis keys)

## Events
(consumed, published with payloads, realtime events)

## Error codes
```
