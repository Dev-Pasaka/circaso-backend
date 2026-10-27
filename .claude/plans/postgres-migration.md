# Plan: ScyllaDB + Kandra → PostgreSQL + Exposed

Status: **approved by Pasaka on 2026-10-27** ("go with your recommendations"); the open questions in §8 are
answered with the recommendations, except question 6.

## 1. Goal and scope

Replace the database layer (ScyllaDB Cloud through Kandra 0.5.1) with PostgreSQL through JetBrains Exposed, without
changing the API, the events, the socket contract, Redis, or any module's domain layer.

**What changes:** infrastructure's database code, every module's `data/entities`, `data/repositories` and
`data/migrations`, the data-layer tests, the deploy's migrate step, local compose, CI services, the spec and the agent
files.

**What does not change:** routes, request and response classes, frames, events, `RealtimeEnvelope`, Redis keys and
streams, the outbox → Redis Streams design, `Resource`/`DomainError`, the module boundaries, integrations.

Why this is a contained change: every module already reaches storage only through its domain `ports`
(`domain/ports/Ports.kt`), and the outbox relay only knows the `OutboxStore` interface. The swap happens behind those
two seams.

### What we have today

| Module | Tables | Data-layer code | Scylla-specific patterns in use |
| --- | --- | --- | --- |
| identity | 10 (`accounts_by_id`, `phone_claims`, `provider_claims`, `providers_by_user`, `devices_by_user`, `sessions_by_id`, `security_log_by_user`, `support_recoveries`, `support_recoveries_by_request`, `outbox`) | ~1,000 lines | claim tables + LWT, TTL (sessions 30 d, outbox 7 d), `@Version`, lookup index, batches |
| users | 10 (`profiles`, `handle_claims`, `contacts_by_owner`, `contacts_by_target`, `waiting_contacts`, `blocks_by_user`, `blocked_by_user`, `invites`, `realtime_by_user`, `outbox`) | ~800 lines | claim tables, reverse tables, TTL (waiting 180 d, invites 7 d, realtime 7 d) |
| assistant | 5 (`asha_messages_by_user`, `usage_daily_by_user`, `entitlements_by_user`, `audit_by_user`, `outbox`) | ~650 lines | clustering DESC, TTL |
| messaging (with MS4) | 13 (`key_bundles`, `one_time_prekeys`, `chats`, `direct_chats_by_pair`, `members_by_chat`, `chats_by_user`, `inbox_by_user`, `group_messages`, `messages_by_id`, `sent_by_client_id`, `settings`, `realtime_by_user`, `outbox`) | ~1,600 lines | LWT claims (`IF EXISTS` on acks, removals, pair claim), size-split unlogged batches, `USING TIMESTAMP` deletes, TTL 1–30 d, raw CQL |
| infrastructure | — | ~360 lines | `install(Kandra)`, keyspace replication, `DatabaseMigrator`, Scylla Cloud TLS |

Wallet isn't built yet, so it is designed for Postgres from the start.

## 2. Target design

### 2.1 Stack

| Concern | Choice | Notes |
| --- | --- | --- |
| Database | PostgreSQL 18 (`postgres:18`): self-hosted on the sandbox server; production host decided before launch (§2.8) | Same image locally, in CI and on the sandbox. 18 has a native `uuidv7()`, but ids keep being generated in the app |
| ORM | Exposed 1.6.0 (`org.jetbrains.exposed`): `exposed-core`, `exposed-r2dbc`, `exposed-java-time`. **DSL API only** (`Table` objects + typed queries), not the DAO API | The DSL matches today's shape: row classes mapped to domain models in the data layer, no lazy-loaded entities. `exposed-java-time` because the code uses `java.time.Instant` |
| Driver and pool | R2DBC: `org.postgresql:r2dbc-postgresql` + `io.r2dbc:r2dbc-pool`, with `suspendTransaction` from `exposed-r2dbc` (decision 2026-10-27, Pasaka: "queries must be non-blocking") | Nothing in the app blocks a thread on the database: a coroutine waiting for a connection or a result suspends. The Exposed docs call R2DBC "still evolving"; what PG1 found is in §2.10. JDBC (`org.postgresql:postgresql`) is used only by Flyway in the `Migrate` step, because Flyway has no R2DBC driver |
| Migrations | Flyway applies plain SQL files, over JDBC, in the `Migrate` step only. The Exposed Gradle plugin (`org.jetbrains.exposed.plugin`, `generateMigrations` against a Testcontainers Postgres) drafts them from the `Table` objects | Per the docs, Exposed "does not apply generated scripts automatically", so a separate tool runs them, and every generated script is reviewed before it is committed |
| Tests | Testcontainers `postgres` for data-layer tests; in-memory port fakes for use-case tests | Replaces `FakeKandraSession` and the Cassandra 4.1 container |

All versions go in `gradle/libs.versions.toml` (the one-catalog rule).

### 2.2 One database, a schema per module

The keyspace-per-module rule becomes a schema-per-module rule. There is one database (`circaso`) with schemas
`identity`, `users`, `messaging`, `assistant` and later `wallet`.

- `KandraModuleSpec(keyspace, entities, migrations)` becomes `DatabaseModuleSpec(schema, tables, migrationsLocation)`.
  Each module declares its schema and its Flyway location (`classpath:db/<module>`).
- **Flyway runs once per schema**, with `schemas = [<module>]` and that schema's own history table. Like today, each
  module numbers its migrations on its own.
- **The isolation is stronger than today's.** Each module gets its own database role, with rights only on its own
  schema. The app opens one pool per role, or one pool with `SET ROLE` per transaction (open question 4). A module that
  queries another module's table fails in the database, not only in Konsist.
- Konsist keeps the code-level rule: an Exposed `Table` object is referenced only from the `data` package of the module
  that owns it.

### 2.3 Transactions are the big win

Postgres gives real multi-row transactions, which removes most of the complexity the Scylla design needed:

| Scylla pattern today | Postgres + Exposed |
| --- | --- |
| Logged batch "rows + outbox row" | One transaction; the outbox row is just another insert |
| Claim table + `saveIfNotExists` *before* the batch (LWT can't join a batch) | `UNIQUE` constraint on the real table, inside the same transaction; `INSERT … ON CONFLICT DO NOTHING RETURNING` tells who won |
| `DELETE … IF EXISTS` claim (acks, member removal) | `DELETE … RETURNING`; exactly one caller gets the row. This also ends the "applied three times" question for good |
| `UPDATE … IF EXISTS` / `IF col = ?` | `UPDATE … WHERE … RETURNING`; affected-row count |
| `@Version` + `update(old, new)` | `UPDATE … SET version = version + 1 WHERE id = ? AND version = ?`; 0 rows = concurrent edit |
| Query tables per access path (`contacts_by_target`, `blocked_by_user`, `chats_by_user`, `providers_by_user`, `support_recoveries_by_request`, `messages_by_id`) | One table + a secondary index. The reverse table is gone, along with the "write both copies" code |
| `@LookupIndex` | A unique index |
| Size-split unlogged batches (MS4) | Batched `INSERT` (`batchInsert`) in one transaction; no size cap to plan around at our message sizes |
| `USING TIMESTAMP` delete (prekeys on `DeviceReplaced`) | `DELETE … WHERE device_id = ? AND uploaded_at < :occurredAt` |
| Clustering order DESC + paging state | Index `(owner, created_at DESC)` + keyset pagination (`WHERE (created_at, id) < (?, ?)`) |
| Kandra null sentinels (empty text, nil UUID, epoch) | Real `NULL`s; the sentinel code and its comments are deleted |
| Counters kept in rows | `UPDATE … SET count = count + 1` in the same transaction; exact |

**Rule for use cases:** a transaction holds only database work. No Redis, HTTP or `XADD` inside it. "Publish at once"
still happens right after the commit, exactly as today.

### 2.4 TTL

Postgres has no row TTL. Each table that has one gets an `expires_at timestamptz` column, and two things enforce it:

1. **Reads filter it:** `WHERE expires_at > now()`, so an expired row is invisible at once, like a TTL.
2. **A purge job deletes it:** one scheduler job per module (`<module>.purge-expired`, single-leader via the existing
   Redis lock) deletes in chunks (`DELETE … WHERE ctid IN (SELECT ctid … LIMIT 5000)`) every few minutes.

The large, high-churn tables are partitioned by day and expire by dropping old partitions instead of deleting rows.
That is `messaging.inbox`, `messaging.group_messages` and `messaging.message_records` (30 d), plus the per-module
`outbox` and `realtime` (7 d). Dropping partitions avoids vacuum pressure from millions of deleted rows. The partitions
are managed by the same job (create tomorrow's, drop the expired ones); no extension needed.

| TTL today | Table | Mechanism |
| --- | --- | --- |
| 30 d | identity `sessions` | `expires_at` + purge |
| 7 d | every `outbox`, `realtime_by_user` | daily partitions, drop after 7 d |
| 180 d / 7 d | users `waiting_contacts`, `invites` | `expires_at` + purge |
| 30 d | messaging inbox, group messages, message records | daily partitions, drop after 30 d |
| 1 d | messaging `sent_by_client_id` | `expires_at` + purge |
| 7 d | assistant outbox | daily partitions |

### 2.5 Outbox relay

This gets simpler. The minute buckets and the checkpoint go away:

- `unpublished()` becomes `SELECT … WHERE published_at IS NULL AND occurred_at < now() - interval '10 seconds'
  ORDER BY occurred_at LIMIT 500`, backed by a partial index `WHERE published_at IS NULL`.
- `OutboxStore`'s interface loses the `bucket` argument. That's an infrastructure-api change; the relay and the four
  implementations move together in the foundation slice.
- Later, if useful, `LISTEN/NOTIFY` can wake the relay. It isn't needed at pilot scale.

### 2.6 Messaging specifics

Messaging is the write-heavy module: a group message to 1,000 members writes 1,000 inbox pointers.

- Inbox pointers are written with one multi-row `INSERT` (or `COPY` if measurements say so) in the sending transaction.
  At pilot scale that's milliseconds.
- **The ack claim is `DELETE FROM inbox WHERE user_id = ? AND message_id = ? RETURNING …`.** Exactly-once is
  guaranteed by the row lock, so the CAS-timeout handling and the "unknown outcome" branches in MS4 and MS3 go away.
- **Member removal** becomes one transaction: delete the member, decrement the count, insert the outbox row. That
  closes the MS3 half-done-removal gap by construction.
- Message records are their own table, `message_records (message_id PRIMARY KEY)`, because group bodies are deleted on
  the last ack while M14 and receipts still need the record (PG0 review).
- `inbox`, `group_messages` and `message_records` are partitioned by `RANGE (message_id)`, with daily bounds at the
  UUIDv7 value of midnight UTC; keys `(user_id, message_id)`, `(chat_id, message_id)` and `(message_id)`, so every
  lookup prunes to one partition. Ids are UUIDv7 made on the server.
- There is no `chats.last_message_id`: M6 reads the newest id per chat from `message_records (chat_id, message_id DESC)`
  with `LATERAL … LIMIT 1`; after 30 quiet days `lastMessageId` is null.
- Every membership transaction locks the `chats` row first; the member cap is
  `UPDATE chats SET member_count = member_count + :n WHERE … AND member_count + :n <= 1000 RETURNING …`.
- Ciphertext columns are `bytea` or `text` as today (base64 stays at the API edge). TOAST compresses large values on
  its own.

### 2.7 Config, health, ops

- **Settings** replace the `SCYLLA_*` ones: `DATABASE_URL` (`r2dbc:postgresql://…`, the app), `DATABASE_USER`,
  `DATABASE_PASSWORD` (secret), `DATABASE_JOBS_USER`, `DATABASE_JOBS_PASSWORD` (secret), `DATABASE_MIGRATION_URL`
  (`jdbc:postgresql://…`, direct, Flyway only), `DATABASE_MIGRATION_USER`, `DATABASE_MIGRATION_PASSWORD` (secret),
  `DATABASE_POOL_APP_SIZE` (10) and `DATABASE_POOL_JOBS_SIZE` (3) (§2.9), `DATABASE_SSL_MODE` (`verify-full` with the provider's CA outside
  local; `disable` only on the sandbox's internal network).
- **Health:** readiness runs `SELECT 1` through the pool. `/kandra/health` and its nginx block are removed.
- **Logging:** Exposed's SQL logger stays off in every environment except local `DEBUG`, and never logs bound values
  (phone numbers, keys). `LOG_LEVEL_DATABASE` replaces the Kandra level.
- **Observability:** r2dbc-pool's counts per pool as `Telemetry` gauges (`circaso_db_pool_connections{pool, state}`).
- **Backups:** on the sandbox, a nightly `pg_dump` to R2 kept 7 days (§2.8); in production, the host's point-in-time
  recovery, retention at least 7 days.

### 2.8 Where Postgres runs (decided 2026-10-27)

**Sandbox: self-hosted on the sandbox server.** A Neon project in us-east-2 was tested and works, but each query
round trip from the server (in France) was about 100 ms. A free Neon project also pauses after 5 idle minutes. The
server has room to spare: 4 CPUs, about 7 GB of RAM free, 92 GB of disk free.

- **Postgres:** a `postgres:18` service in `/opt/circaso/docker-compose.yml` on the `circaso-data` network, next to
  Valkey.
  - No published port; only the app and pgAdmin reach it.
  - A named volume `pgdata`, a memory limit of about 1.5 GB, and settings for that size (`shared_buffers=384MB`,
    `effective_cache_size=1GB`, `max_connections=50`).
  - The superuser password, the app role and each module's role go in `/opt/circaso/.env`, never printed.
- **Backups:** a nightly `pg_dump -Fc` uploaded to R2 under `backups/postgres/`, kept 7 days, run by a small cron
  container or host cron (decided by devops in PG1).
  - Restore is tested once in PG6.
- **Client, pgAdmin in the browser at `https://sandbox-db.circaso.com`, behind Cloudflare Access.**
  - **Container:** `dpage/pgadmin4` on the `circaso-data` network. It is **not** given a published port; nginx reaches
    it by container name.
  - **Route:** a new nginx `server` block for `sandbox-db.circaso.com` that proxies to `pgadmin:80`.
    - It keeps the existing guard that only Cloudflare's addresses may connect, so the origin can't be reached around
      Access.
    - It also refuses any request without a `Cf-Access-Jwt-Assertion` header (403), so a request that somehow skipped
      Access never reaches pgAdmin.
    - The `api` rate limit and the usual security headers apply. `client_max_body_size` is raised for pgAdmin's
      imports.
  - **DNS:** a proxied (orange-cloud) `sandbox-db` record to the server. The name is deliberately one level deep:
    the origin certificate covers only `*.circaso.com` and `circaso.com` (checked on the server), and so does
    Cloudflare's free edge certificate. A two-level name like `db.sandbox.circaso.com` would fail TLS without a paid
    certificate.
  - **Cloudflare Access (Pasaka sets it up in the Zero Trust dashboard):**
    - A self-hosted application for `sandbox-db.circaso.com`.
    - A policy that allows only Pasaka's email, with a one-time PIN or Google login, and a session of 24 h or less.
  - **Three locks in a row:**
    1. Cloudflare Access.
    2. pgAdmin's own login: its email and password, both in `/opt/circaso/.env`, never printed.
    3. A **read-only role** `circaso_readonly` for the saved server entry. It has `SELECT` on every module schema (and
       default privileges for future tables), so browsing can't change data by accident. Writes go through the owner
       role, used deliberately.
  - **Settings:** pgAdmin's settings live in a small named volume, so saved servers and queries survive restarts.
  - **Fallback:** if Access is ever off, the same pgAdmin can still be reached through an SSH tunnel to the container.
- **Locally:** the same `postgres:18` in `deploy/docker-compose.yml`. pgAdmin is optional (compose profile `tools`),
  or use the IDE's database tool against `localhost:5432`.

**Production: decided before launch.** The lean is a managed Postgres in Europe (for example Neon's paid plan in
Frankfurt) for failover and point-in-time restore. The app only reads `DATABASE_URL`, so the choice changes no code.

### 2.9 Connection pools

Postgres pays for each connection with a server process, so pools stay small and bounded. The app's pools are
r2dbc-pool `ConnectionPool`s over the non-blocking R2DBC driver: a coroutine waiting for a connection suspends in the
pool's queue, and no thread ever blocks on the database.

**Pools per app container**

| Pool | Used by | Size | Why separate |
| --- | --- | --- | --- |
| `circaso-app` | Requests and socket frames (every `Database.tx` from a route or frame handler) | max 10, initial 2 | The hot path |
| `circaso-jobs` | Outbox relay, event consumers, purge and partition jobs, scheduler | max 3, initial 1 | A slow purge or a burst of events can never take the connections requests need |
| `circaso-migrate` | The `Migrate` main only, during deploy: one direct JDBC connection for the setup, and Flyway's own (it holds a few at once) | no pool; closed when it ends | Flyway takes a session-level lock, so it uses the **direct** URL, never a transaction pooler |

Two URLs:
- `DATABASE_URL` (`r2dbc:postgresql://…`) is for the app pools. In production, behind a provider's pooler (PgBouncer
  in transaction mode), this is the pooled endpoint.
- `DATABASE_MIGRATION_URL` (`jdbc:postgresql://…`) is always a direct connection, used only by `Migrate`.

On the sandbox's self-hosted Postgres, both are the same address.

**Budget on the sandbox** (`max_connections = 50`, `superuser_reserved_connections = 3`):

| Item | Connections |
| --- | --- |
| app | 10 |
| jobs | 3 |
| migrate | about 3, only during deploy |
| pgAdmin | about 3 |
| nightly backup | 1 |
| reserved | 3 |
| **Total** | **about 22** |

That leaves room for a second app container (+13) during a rolling deploy. In production, the rule is
`containers × (app + jobs) + migrate + tools ≤ 80%` of the server's limit. Past that, the pooled endpoint
(PgBouncer) goes in front, not bigger pools.

**r2dbc-pool settings** (in `Config`, overridable by `DATABASE_POOL_*` settings):

- **Timeouts:**
  - `maxAcquireTime` 3 s on the app pool (`DATABASE_POOL_ACQUIRE_TIMEOUT_MS`). When no connection comes free in time,
    the request fails fast as `service.unavailable` (503) instead of piling up.
  - 20 s on the jobs pool (`DATABASE_POOL_JOBS_ACQUIRE_TIMEOUT_MS`), below the consumers' 30 s reclaim: background
    work waits out a burst without a message being reclaimed mid-wait, and an event
    handler that still gets `service.unavailable` is retried after a back-off without counting as an attempt.
  - `maxIdleTime` 10 min; `maxLifeTime` 30 min (below any proxy or firewall idle cut).
  - `validationQuery "SELECT 1"` on acquire.
- **Transactions:** isolation `READ_COMMITTED` (`R2dbcDatabaseConfig { defaultR2dbcIsolationLevel }`),
  `defaultMaxAttempts = 1`, nested transactions off; Exposed opens and commits the transactions.
- **Driver options** (r2dbc-postgresql; an option already in the URL wins):
  - `applicationName=circaso-app` (or `-jobs`; Flyway's connections `circaso-migrate`), so `pg_stat_activity` shows
    who holds what.
  - `statement_timeout` as a startup option, the same value as the login role's (5 s app, 60 s jobs).
  - `sslMode=verify-full` outside local, against the provider's CA (`DATABASE_SSL_ROOT_CERT`); `disable` only on the
    sandbox's internal Docker network. `tcpKeepAlive=true`.
  - `preparedStatementCacheQueries=256`: a bounded cache of named statements (see "Prepared statements" below).
- **Name** per pool (`circaso-app`, `circaso-jobs`).

**Server-side limits on the login roles** (so a bad query can't hold a pooled connection for long; decision A of the
PG0 review):

- `circaso_app` (the app pool): `statement_timeout = 5s`, `lock_timeout = 2s`,
  `idle_in_transaction_session_timeout = 10s`
- `circaso_jobs` (the jobs pool): `statement_timeout = 60s` for purges and partition work

**Coroutines and the pool**

- `Database.tx` is Exposed's R2DBC `suspendTransaction`: no dispatcher of its own and no thread held. At most
  `maxSize` transactions hold a connection; the rest suspend in the pool's queue until one frees or `maxAcquireTime`
  passes.
- Repositories consume result `Flow`s (`toList()`, `singleOrNull()`) inside the transaction.
- A transaction holds only database work (the §2.3 rule). An HTTP call or Redis round trip inside one would hold a
  connection for the whole wait, and a Konsist rule plus review catch it.
- Long reads (exports, admin listings) page with keyset queries, each page its own short transaction.

**Role switching.** One pool per purpose (above): the app pool logs in as `circaso_app` and the jobs pool as
`circaso_jobs`, both granted every module role. Each transaction starts with `SET LOCAL ROLE <module>`. Migrations
run as `circaso_migrator` (`CREATEROLE`, owns the schemas, secret `DATABASE_MIGRATION_PASSWORD`), with
`SET ROLE <module>` per schema so each module's tables are owned by its role.
- `SET LOCAL` ends with the transaction, so a connection returns to the pool clean.
- It also works behind PgBouncer in transaction mode.
- The alternative, one pool per module role, would multiply connections by 5.

**Prepared statements.** r2dbc-postgresql caches named server-side prepared statements per connection (bounded at
256). Behind PgBouncer in transaction mode that needs PgBouncer 1.21+ with `max_prepared_statements` set. Neon's pooler
already supports this. Otherwise add `preparedStatementCacheQueries=0` to the pooled `DATABASE_URL`.

**Watching it.**
- r2dbc-pool's counts (acquired, idle, allocated, pending, max) per pool go through `Telemetry` as the gauge
  `circaso_db_pool_connections{pool, state}`; an acquire timeout is a WARN line `kind=db.pool`.
- Alerts fire when `pending > 0` for 1 minute, on any connection timeout, or when connections in use stay above 80%
  of `max_connections`.
- Readiness runs `SELECT 1` over R2DBC through the app pool and fails if a connection can't be borrowed in time.

**Tests.** Testcontainers Postgres (one container per test JVM) over R2DBC, with a pool of 4 for the saturation test. A test that saturates the pool checks that the
6th concurrent `Database.tx` waits and then succeeds, and that a timeout maps to `service.unavailable`.

### 2.10 Exposed conventions (Kotlin best practice, checked against the Exposed docs, 1.6.0)

Sources: the Exposed documentation pages "About", "Transactions", "Migrations", "Modules", "Working with tables",
"DSL CRUD operations", "Working with Database", "Working with DataSources" and "Gradle plugin", read 2026-10-27. Where
a page did not cover a point, it is marked "verify in PG1" rather than assumed.

**Transactions**
- **Every database call runs in a transaction**, as the docs require. The only entry point is infrastructure's
  `Database.tx("<operation>") { }`, a thin wrapper over `org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction(db = …)`.
  Modules never open an Exposed transaction or call `R2dbcDatabase.connect`; a Konsist rule enforces both, and another
  keeps JDBC out of everything but the `Migrate` code.
- **`connect()` runs once:** infrastructure creates one `R2dbcDatabase` per r2dbc-pool `ConnectionPool` at startup
  and shares it. The docs warn that calling `connect()` repeatedly leaks resources.
- **Pool settings live in r2dbc-pool and the driver options;** `R2dbcDatabaseConfig` sets the dialect, isolation
  (`READ_COMMITTED`), attempts and the SQL logger.
- **Retries:** `maxAttempts` re-runs the whole block on an `R2dbcException`. The default stays `1`, so nothing runs
  twice by surprise. Only a block that is pure database work and safe to repeat may raise it (for example a
  `SERIALIZABLE` claim), and the call site says why.
- **The statement timeout is the connection's** (`statement_timeout` 5 s on the app pool, 60 s on the jobs pool, sent
  as a startup option and matching the login role). Checked in PG1: Exposed's R2DBC module calls
  `Connection.setStatementTimeout(queryTimeout)` before every statement, and r2dbc-postgresql turns that into a
  session `SET STATEMENT_TIMEOUT` round trip; with the default `queryTimeout` of 0 it would switch the timeout off.
  Infrastructure's connection factory ignores that per-statement call.
- **No nested transactions:** `useNestedTransactions` stays off. A use case opens one transaction through the domain
  port `Transactor.run("<operation>") { }` (implemented in `data` over `Database.tx`); repositories join it through the coroutine
  context.
- **`text` and `blob` columns are read inside the transaction**, using `eagerLoading = true` where they are used after
  it. The docs say they are unavailable outside a transaction otherwise.

**Tables**
- **One `object` per table in the module's `data/tables` package**, replacing `data/entities`, e.g.
  `object AccountsTable : Table("identity.accounts")`. The name is always given explicitly (the docs strip a `Table`
  suffix from the object name). Schema-qualified names should be checked in PG1; Exposed's `Schema` class is the
  alternative.
- **Plain `Table` with an explicit `override val primaryKey = PrimaryKey(…)`**, not `IntIdTable`/`UUIDTable`. Ids are
  UUIDv7 made in the app (the `@GeneratedUuid` behaviour), so no database-generated id is needed. Composite keys
  (`members(chat_id, user_id)`) use `PrimaryKey(a, b)`.
- **Constraints are declared on the table and in the SQL migration:** `uniqueIndex()` for the claims (phone, handle,
  provider subject, direct pair), `index(…)` for the reverse lookups, `filterCondition` for partial indexes (the
  outbox's `published_at IS NULL`), `.check { }` where the domain has a rule, and `reference(…, onDelete = CASCADE)`
  within one module only. There are no foreign keys across schemas, because modules stay independent.
- **Columns are `NOT NULL` unless `.nullable()`, with a nullable Kotlin type**, so the Kandra sentinels disappear.
  Time columns use `timestamp(...)` from `exposed-java-time` (`timestamptz` in the SQL).
- **Defaults:** `clientDefault { }` for values the app owns (ids, `createdAt` from the injected clock, so tests stay
  deterministic). `defaultExpression` / `databaseGenerated()` only for database-owned values, which are rare here.

**Queries**
- **Each table gets a private mapper,** `ResultRow.toAccount(): Account`, in the repository file; `ResultRow` never
  leaves the data layer.
- **Claims and races use the returning forms:** `insertIgnore` (`ON CONFLICT DO NOTHING`) and `insertedCount` for
  "who won"; `deleteReturning` / `updateReturning` for claims, acks and optimistic updates; `upsert` with explicit
  `keys` for idempotent writes. The docs warn that without explicit keys, behaviour varies by database.
- **Fan-out writes** use `batchInsert(items, shouldReturnGeneratedValues = false)` with
  `useMultiRowValues = true` where a single multi-row `INSERT` suits (R2DBC has no `reWriteBatchedInserts`; measure in
  PG5).
- **Pagination is keyset** (`where { (createdAt less cursorAt) or … }.orderBy(createdAt to DESC).limit(n)`); there is
  no `offset` for feeds.
- **Raw SQL** (`exec`) only for what the DSL can't express (partition management), in infrastructure or a migration.
  Never with values spliced in.
- **The SQL logger is off.** `StdOutSqlLogger` is never installed outside a local debug run, since it would print
  bound phone numbers and keys.

**Migrations**
- **Flyway applies, per module:** `src/main/resources/db/<module>/V<n>__<what>.sql`, one Flyway run per schema with its
  own history table, in the `Migrate` main before the containers start (today's deploy step).
- **Drafting:** a developer may run the Exposed Gradle plugin's `generateMigrations` (Testcontainers Postgres, the
  module's `tablesPackages`, `fileDirectory` = the module's `db/<module>`) to draft the next script. The docs say to
  review generated diffs before applying them, because they can contain `DROP`s. Adding the plugin is a structural
  change to the build, so it needs Pasaka's OK (open question 7).
- **No `SchemaUtils.create` anywhere, including dev:** dev runs the same Flyway scripts, so what is tested is what
  ships. At startup the app only checks that Flyway's history is current (the old `VALIDATE`).
- **Applied scripts are never edited,** as with Kandra; Flyway's checksum check fails the deploy otherwise.

## 3. Slices

The pipeline is the usual one: developer → architect → QA → devops. Because modules only touch storage through their
ports, **the app can run Kandra and Exposed side by side** while modules move one at a time. Each slice is deployable on
its own, and Scylla is switched off only in the last slice.

| Slice | Content | Done when |
| --- | --- | --- |
| **PG0: spec** | Decision row; architecture.md (Infrastructure, outbox step 1, Kandra section replaced by "Exposed and Postgres", guardrails); working-rules (drop Kandra sentinels and the Kandra bugs rule, add the transaction rule and the purge/partition rule); each module doc's Data section rewritten as Postgres tables; agent files (developer, architect, QA) updated | Pasaka approves the docs |
| **PG1: foundation** | Version catalog: Exposed (`exposed-r2dbc`), r2dbc-postgresql, r2dbc-pool, Flyway and the JDBC driver (Migrate only), Testcontainers postgres, the Exposed Gradle plugin. Infrastructure: `DatabaseModuleSpec`, the app and jobs r2dbc pools with their metrics (§2.9), `Database.tx { }` (R2DBC suspended transaction, `SET LOCAL ROLE`, a named operation, `DatabaseException` without values, guards against Redis/HTTP/publish inside), the shared outbox (`DatabaseModuleSpec(outbox = true)`, `database.writeOutbox`), test fixtures for module data tests, Flyway per schema in the `Migrate` main over JDBC, health check, purge/partition helper for the scheduler, a Testcontainers fixture shared per test JVM (schema reset by `TRUNCATE`). Postgres added to `deploy/docker-compose.yml` and the CI services. Postgres, pgAdmin and `circaso_readonly` already run on the sandbox (main `ae6e233`). Devops adds the `circaso_migrator`, `circaso_app` and `circaso_jobs` login roles with their limits (created by the superuser from `deploy/postgres/initdb/20-login-roles.sh`, run once by hand on the existing volume; passwords in `/opt/circaso/postgres.env` and `/opt/circaso/.env`) and the nightly R2 backup, with Pasaka's go-ahead for the server change. `Migrate` creates the schemas and the module roles and grants them. The `Transactor` port and its Konsist rule. `OutboxStore` without buckets; relay updated. Kandra still installed for the modules not yet moved. Konsist: the Exposed table-ownership rule, no way in but `Database.tx`, JDBC only in the Migrate code, no `runBlocking` | Migrate creates the schemas and roles; health is green with both databases; relay works against a Postgres outbox in a test |
| **PG2: identity** | `V1__identity.sql` (accounts with unique phone, `delete_after` and the pending and deleted partial indexes, provider links with unique (provider, subject), devices, sessions with `expires_at`, security log, support recoveries keyed by request id, the shared outbox partitioned) and `beforeMigrate.sql`; Exposed tables; repository impls behind the existing ports, with the `Transactor` port and the account row lock taken first by every change; claim tables dropped (the unique constraints are the claims); identity's Kandra code removed; jobs `identity.purge-expired` (sessions, outbox partitions), `identity.sweep-pending-accounts` (30 days) and `identity.complete-deletions` (30-day grace, `AccountDeleted` in the same transaction; decision 2026-10-27) through infrastructure's new `ScheduledJob` hook; `/health/ready` logs once per state change; data tests on Postgres (every repository method, the races, purge, outbox atomicity, no phone number in DEBUG logs) | All identity tests green on Postgres; `ServeTest` green with identity on Postgres; sign-up, sign-in, refresh, sign-out, deletion and recovery pass QA on local |
| **PG3: users** | Profiles with unique handle, `handles` (30-day hold by `expires_at`), contacts (one table, index on target), waiting contacts, blocks (one table, index on blocked), invites, realtime, outbox. Built on `feat/pg3-users`: `V1__users.sql` and `beforeMigrate.sql`; `data/tables`; repositories behind the existing ports; the `Transactor` port, with every read-dependent write in one `run` (a failed later step throws `Rollback`); the handle claim (`insertIgnore`, takeover `updateReturning`, one retry); `users.purge-expired` every 5 minutes through `ScheduledJob` (purges `handles`, `waiting_contacts`, `invites`; keeps `realtime` and `outbox` partitions, 7 days); all Kandra code, migrations and tests removed from users | Same, for users |
| **PG4: assistant** | Asha messages, daily usage, entitlements, audit, outbox | Same, for assistant |
| **PG5: messaging** | Key bundles, prekeys, chats with unique direct pair, members (count in the same transaction), chats by user (index), inbox / group messages / message records partitioned by day, client-message idempotency, settings, realtime, outbox. MS3's removal gap and MS4's claim and batch workarounds are dropped in favour of transactions. Relax the M1 rule that the phone keeps a refused top-up's private keys (`messaging.md` M1 and `docs/mobile-integration.md`) | All messaging tests green; the "exactly one wins" tests pass 50 runs in a loop under load |
| **PG6: cutover** | Sandbox: switch the app to the Postgres added in PG1, test a backup restore once, deploy migrate step on Flyway; remove Kandra (deps, `KandraInstall`, `KeyspaceReplication`, `ScyllaConnection`, the `SCYLLA_*` settings, the Kandra Konsist rules and allow-list entries, the `/kandra/` nginx block); full build; deploy; smoke test every route. **Then remove every trace of Kandra and ScyllaDB** (Pasaka, 2026-10-27: "nothing looks like Kandra or ScyllaDB existed before"): code, tests and fixtures, Konsist rules, Gradle deps and catalog entries, `.env.example`/`dev.env.example` `SCYLLA_*` lines, the local compose `scylla` service, the nginx `/kandra/` block, `docs/kandra-bugs.md`, every mention in the spec, plan files, agent files and decisions log (rewritten to state the Postgres design as the decision), and the project memory notes. Git history stays as it is unless Pasaka asks otherwise. A final `git grep -i -e kandra -e scylla` returns nothing | Sandbox serves only from Postgres; the ScyllaDB Cloud cluster can be shut down after Pasaka confirms |
| **Wallet (WS2+)** | Built directly on Postgres after PG1, using the same patterns | — |

**Order:** PG0 → PG1 → PG2, PG3 and PG4 in parallel (they share nothing) → PG5 → PG6. Each module slice can ship to
the sandbox on its own (the app runs mixed), or all of them can be held and shipped together at PG6. See open
question 3.

## 4. Data

The app isn't released, and the sandbox only holds test accounts. **Recommendation: no data migration.** The sandbox
starts empty on Postgres, and the test accounts sign up again. If some sandbox data must be kept (open question 2),
PG6 adds a one-off `ExportScylla` → `ImportPostgres` command per module, run once and then deleted.

## 5. In-flight work

| Slice | State | Recommendation |
| --- | --- | --- |
| **TY1** | Live | Nothing to do |
| **AI1b** | Approved; it barely touches the database | Ship it on Kandra as planned; PG4 moves its tables later |
| **MS4** | Round 4 stopped part-way | **Pause it and fold it into PG5.** Most of its round 2–4 work (atomic claim edge cases, size-split batches, `USING TIMESTAMP`, CAS timeouts) exists only because of Scylla. Its domain logic, frames, receipts in Redis, tests of behaviour and docs carry over unchanged |
| **MS3 removal gap** | Waiting for a decision | Solved by PG5's transaction; no separate slice |
| **MS5, MS6, WS2–WS4, AI3, GW2/3** | Not started | Start after PG1, directly on Postgres |

## 6. Risks

| Risk | Mitigation |
| --- | --- |
| Group fan-out (1,000 inbox rows per message) is heavier on one Postgres primary than on Scylla | Partitioned inbox, one multi-row insert per send, a load test in PG5 (1,000-member group, 50 msgs/s) before cutover; Redis already absorbs receipts and typing |
| Vacuum and bloat on high-churn tables (inbox rows deleted on ack) | Daily partitions dropped whole; autovacuum tuned per table if needed |
| Connection limits with 2+ app containers | Small, separate pools and a connection budget (§2.9); PgBouncer (transaction mode) in front when the budget is exceeded |
| Long transactions holding locks | The transaction rule (database work only); a statement timeout (5 s) on the app role |
| Exposed + coroutines (transaction context on the wrong thread) | One `Database.tx` helper is the only way in; a Konsist rule bans raw `transaction {}` in modules |
| A single primary is a single point of failure | Managed Postgres with a standby and PITR (open question 1) |
| Spec drift | PG0 updates the spec before code, and the architect checks each slice against it |

## 7. Effort

At the pace of the last slices: PG0 half a day; PG1 1–2 days; PG2, PG3 and PG4 about a day each (in parallel); PG5 2–3
days (the largest, but simpler than MS4 was); PG6 1 day. Roughly **a working week of slices**, mostly in parallel.

## 8. Decisions (2026-10-27)

1. **Hosting:** self-hosted `postgres:18` on the sandbox server, with pgAdmin at `sandbox-db.circaso.com` behind
   Cloudflare Access (§2.8). Pasaka sets the DNS record and the Access application. Production is decided before
   launch.
2. **Sandbox data:** none is migrated; the sandbox starts empty on Postgres.
3. **Shipping:** module by module. The app runs Kandra and Exposed side by side until PG6.
4. **Roles:** one pool per purpose (§2.9); the app pool logged in as `circaso_app`, the jobs pool as `circaso_jobs`, with
   `SET LOCAL ROLE <module>` per transaction (amended in the PG0 review).
5. **MS4:** paused and folded into PG5. AI1b ships on Kandra first, since it barely touches the database.
6. **Kandra dependencies outside Circaso:** not answered. Nothing in this plan depends on the answer.
7. **Exposed Gradle plugin:** added, to draft migrations. Drafts are always reviewed, and only Flyway applies them.
8. **PG0 review (2026-10-27):** the `Transactor` port, `users.handles`, no `chats.last_message_id`, messaging partitions
   by `message_id`, media lock order, wallet transition guards and permanent idempotency, the database log line,
   naming, the `circaso_jobs` login role, the `circaso_migrator` role and `accounts.delete_after`. See `decisions.md`.
