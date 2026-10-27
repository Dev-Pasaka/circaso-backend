# Circaso backend

Circaso (formerly CoinX) is a chat-first social finance app for African markets: chat, money through Circle user-controlled wallets, group calls, and chamas. This repository is its backend: one Kotlin/Ktor deployable split into strict modules, each with its own PostgreSQL schema, talking to the others only through published contracts and durable events.

The architecture is decided; the code is not written yet. Your job is to build it exactly as designed.

## Read first

Before writing code, read these in order. They are the spec; this file only tells you how to work with them.

1. `docs/architecture.md`: principles, project structure, module anatomy, results and errors, how modules talk, gateway, infrastructure and the database (Exposed and PostgreSQL), logging, observability, integrations, realtime, conventions.
2. `docs/decisions.md`: every decision with its reason, and the open questions.
3. `docs/modules/README.md`: the module map and the checklist for adding a module.
4. The module you are working on: `docs/modules/identity.md` (and `identity-signin-flow.md` for the screens each endpoint serves), `docs/modules/users.md`, `docs/modules/assistant.md`.

`docs/` is exported from the Claude Docs document "Circaso Backend — Architecture & Plan" (export of 2026-10-22). The diagrams live only there; the docs mark where they were.

## Who decides what

- **Pasaka owns the architecture, the coding standards and the review of all code.** You implement to his design.
- **Never change the architecture in code.** If the spec is wrong, unclear or missing something, stop and ask, or write the question down. Don't quietly pick another design.
- **Open questions stay open.** Where code depends on one (see the list in `docs/decisions.md`), build behind an interface or a config value and leave `// TODO(decision: <question>)`. Don't decide it in code.
- When the work shows a gap in the docs, say so in your summary with the exact place, so the doc can be updated.

## Stack

| Piece | Choice |
| --- | --- |
| Language and server | Kotlin (2.2.20 or newer, for Ktor's OpenAPI plugin), Ktor 3 server and client |
| Database | PostgreSQL 18 (`postgres:18`: self-hosted on the sandbox, Docker locally and in CI; production host decided before launch) through **Exposed** 1.6.0, DSL only (`exposed-core`, `exposed-r2dbc`, `exposed-java-time`), the non-blocking R2DBC driver with r2dbc-pool (`exposed-r2dbc`, `r2dbc-postgresql`, `r2dbc-pool`), Flyway for migrations (over JDBC, in the `Migrate` step only). |
| DI | Koin |
| JSON | kotlinx-serialization only (never Gson) |
| Events | Outbox table in each schema + Redis Streams |
| Cache, counters, locks | Redis (Valkey compatible) |
| Architecture tests | Konsist, plus the `circaso.api` and `circaso.impl` Gradle convention plugins |
| Tests | kotlin-test / JUnit 5, in-memory fakes for the domain, Testcontainers PostgreSQL and Redis for data tests |
| Observability | OpenTelemetry Java agent, Logback (pretty console locally, `LogstashEncoder` JSON elsewhere), Prometheus metrics |
| AI (assistant) | Cloudflare Workers AI over plain Ktor (no agent framework; decision 2026-10-25) |

Libraries not named in the docs (the Redis client, for example) are your proposal: pick a well-maintained option, say so in your summary, and keep it behind the infrastructure handle so it can change.

Packages are `com.circaso.<module>.<api | presentation | domain | data | di>`.

## Build order

Work in this order. Each milestone ends green: it builds, its tests and the Konsist rules pass, and nothing from a later milestone is half-started.

**M0 — Skeleton**
- Gradle multi-module build exactly as the project tree in `docs/architecture.md`: `build-logic` (convention plugins `circaso.kotlin`, `circaso.api`, `circaso.impl`), `gradle/libs.versions.toml`, `shared`, `shared-web`, `infrastructure/{api,impl}`, `integrations/{api,impl}`, `gateway`, `modules/identity/{api,impl}`, `modules/users/{api,impl}`, `app`, `architecture-tests`, `deploy`.
- `shared`: ids (UUIDv7 value classes), `Money`, `PhoneNumber`, `Resource`, `ErrorKind`, `DomainError`, `ErrorDetails`, `DomainException`, `DomainEvent`, the `EventBus`, `Realtime` and `RealtimeReplay` contracts.
- `shared-web`: `DefaultResponse`, `ErrorKind.httpStatus()`, `toDefaultResponse`, `respondDefault` (with `Retry-After`), StatusPages.
- `architecture-tests`: every Konsist rule in Module anatomy and the dependency table.
- `deploy/docker-compose.yml` with PostgreSQL and Redis; `app` as a plain embedded Ktor server (no command-line arguments; settings from environment variables, local ones from `.env`), with `Migrate` as a second main class; `/health/live` and `/health/ready`.
- Logging as in the Logging section: both formats, request id in MDC.

**M1 — Infrastructure**
- The database as in architecture.md → Exposed and PostgreSQL: one schema and role per module from each module's `DatabaseModuleSpec`; the app and jobs r2dbc pools and the `Migrate` step's JDBC; `Database.tx` (R2DBC `suspendTransaction`, nothing blocks a thread) with `SET LOCAL ROLE`; credentials from `Secrets`.
- Flyway once per schema, run only by the `Migrate` main; the app only checks that the history is current.
- `RedisScope` with per-module prefixes; `Scheduler` with a Redis leader lock.
- `EventBus`: outbox write in the same transaction, immediate `XADD` after the commit, `publishedAt` stamp, single-leader relay over the unpublished rows, consumer groups, `XACK` after success, `XAUTOCLAIM` at 30 s, 5 attempts then `events:dlq`, `evt:done:` idempotency, 7-day trim.
- Named Ktor HTTP clients with the retry table and circuit breaker; config and secrets; OpenTelemetry wiring and the `Telemetry` handle.

**M2 — Integrations**
- Contracts in `integrations:api`. `dev` package: `log` and `fake` implementations of every contract (local runs and tests use these).
- Real implementations needed by identity and users: Africa's Talking SMS, Resend email, Google and Apple token verification (and Apple revoke), Firebase phone number verification, FCM and APNs push. One package per vendor; vendor types never cross the contract.

**M3 — Gateway**
- `/v1` routing, `X-Client-Version` check (426), `TokenVerifier` auth with the revoked-session deny-list, scopes `onboarding`, `app`, `agent`, `ops`, and the agent rules (`GET` under `/v1/agent/` only, and nothing else there).
- Rate limits (the table in Gateway), idempotency with the in-progress marker, the `RequestLog` plugin, webhooks at `/v1/hooks/{vendor}`, OpenAPI generation with Swagger UI outside production behind `ops`.
- WebSocket hub: auth on first frame, heartbeat, per-node Redis channel delivery, push fallback, replay through `RealtimeReplay`.

**M4 — Identity**: I1–I17, the sign-in decision table, the tables, Redis keys, events (with `phoneHmac`, never raw numbers), error codes and the attestation rules, exactly as `docs/modules/identity.md`. The flow in `identity-signin-flow.md` is the acceptance test: every screen's call must return what that screen needs.

**M5 — Users**: U1–U16, the reach rule, contact sync with HMACs, the tables and Redis keys, consumed and published events, `RealtimeReplay`, error codes, and the read-only agent routes, as `docs/modules/users.md`.

**Not yet**: messaging, wallet, calls and the assistant need their own design first. Only build what their docs say when Pasaka asks.

## How every endpoint is built

Controller (a `Route` extension function) → service method → use case → repository interface → repository implementation → Exposed table. For each endpoint:

- Request and response DTOs in `presentation/dto`, mapped to and from models; shape checks in the mapper.
- One use case per operation (`suspend operator fun invoke`), returning `Resource<T>`; expected failures are `Resource.Error` with the module's error code from the docs, never thrown.
- The service method calls the use case and does nothing else. Each service class is in its own file.
- The controller is `fun Route.xController(service: XService)` in its own file (`controllers/ProfileController.kt`): path groups, scope, handlers and `.docs(...)` together, as in the Ktor reference (`fun Route.patientRatingController(service)` with `authenticate { route("/…") { post { … } } }`). The module's Koin `RouteContributor` installs its controllers; there is no separate routes file.
- Declared scope, `Idempotency-Key` on state changes, rate limit where the Gateway table lists one.
- OpenAPI: describe the route with `.docs<Request, Response>(summary, tag, errors, notes)` from `shared-web`: the response is declared as `DefaultResponse<T>`, the error codes it can return are listed, and the module's tag groups it. The spec is generated from these descriptions; the reader is Circaso's own (`docs-ui/`), not Swagger UI or Scalar.
- Tests: the use case against in-memory fakes of its ports (happy path and every error code it can return); the repository against Testcontainers PostgreSQL; one end-to-end test per flow (sign-up, sign-in on the same phone, new phone, refresh, sign-out, contact sync).

## Rules that are easy to get wrong

- **Configuration is environment variables, not arguments.** `main` takes no arguments: it loads `.env` (local development only, never overriding the real environment), builds `EnvConfig` and `EnvSecrets` from the result and starts `embeddedServer(Netty, …)`. A setting's dotted name is its variable (`database.migration-url` is `DATABASE_MIGRATION_URL`; a secret `identity.signing-key` is `IDENTITY_SIGNING_KEY`). A new setting is a line in `.env.example`. There is no `application.conf`. Nothing may log before `main` has read `.env` (no top-level logger `val` in `Main.kt`).
- **Logging is controlled per service by environment variable** (`LOG_LEVEL`, `LOG_LEVEL_OTHER`, `LOG_LEVEL_REQUESTS`, `…_IDENTITY`, `…_DATABASE`, `…_REDIS`, `…_HTTP` and so on; `OFF` to silence); a new module or integration gets its own `LOG_LEVEL_<NAME>` line in `logback.xml`, and `LogLevelsTest` covers it.

- **Every route is documented into a section.** `.docs<Req, Res>(summary, tag, errors, notes)` takes a `tag` that must be one of the sections in `gateway/.../ApiDocsCatalog.kt` (Health, Webhooks, Sign in, Phone verification, Register device, Profile, Handles, Contacts, Connections and invites, Blocks and reach, Asha chat, Asha voice and usage, Account and security, Account recovery, Agent routes). The catalog orders the reference as a person uses the API (Infrastructure first, then authentication, profile, people, Asha, account) and explains each section; a new area adds a tag and its explanation there. A route in the spec with no section fails `ServeTest`. The reader is its own service: `docs-ui/` builds static files into a small nginx image (`circaso-docs`), nginx routes `/docs` to it and `/docs/openapi.json` to the server; the server only builds the spec. The introduction is `gateway/src/main/resources/docs/guide.md`: keep the quick start, scopes, limits and error table in step with the code.
- **Every dependency and plugin is declared in the version catalog, `gradle/libs.versions.toml`, and nowhere else.** One version per library family (`ktor`, `kotlinx-coroutines`, ...) in `[versions]`, one line per artifact in `[libraries]`, and build files use the generated accessors (`libs.ktor.server.core`, `libs.kotlinx.serialization.json`). No second catalog (the old `ktorLibs` import was removed), no `"group:artifact:version"` strings in a build file, and a module declares what its own code imports instead of relying on another module to bring it. Adding a library means adding its line to the catalog first. The one exception is a Gradle *settings* plugin (the Java toolchain resolver in `settings.gradle.kts`), because the catalog is not available there.
- **Ask before changing the project's structure.** A new module, a move of packages, a new convention, or a change to how dependencies or the build are organised is Pasaka's decision: put the options and a recommendation in front of them and wait.
- **Code is formatted to be read, not to be short.** Applies to every Kotlin file, new or touched:
  - **The build enforces it.** The rules are in the root `.editorconfig` and Spotless (ktlint) runs inside `./gradlew build`. Run `./gradlew spotlessApply` before you finish; `spotlessCheck` fails the build on a file that is off. Spotless and ktlint versions live in `libs.versions.toml`. Strings longer than a line are split with `+`; a rule is switched off only in `.editorconfig`, with a reason, after asking Pasaka.
  - **Lines stop at 120 characters.** A line that would run past it is broken, never left long.
  - **One idea per line.** A call with more than two arguments, or that does not fit on one line, puts each argument on its own line with a trailing comma and the closing parenthesis on its own line. A chain of more than two calls puts each call on its own line, the dot leading. Class and function declarations follow the formatter instead (`.editorconfig`: one parameter per line from three parameters for functions and four for classes, or when the signature doesn't fit); `spotlessApply` is the final word on layout.
  - **Imports, never inline qualified names.** `PhoneVerificationGateway`, not `com.circaso.integrations.api.PhoneVerificationGateway` in the middle of code. Imports are sorted, with none unused and no wildcards.
  - **Small functions that read top to bottom.** A function does one thing and fits on a screen; a nested `if`/`when` three levels deep, or a body that mixes steps, is split into named functions. Early returns for the failure cases, the happy path last and unindented.
  - **Blank line between members, none inside a tight group.** One blank line between functions, between a class's property block and its functions, and between the steps of a long function (setup, act, return). No double blank lines, no blank first or last line in a block.
  - **Names say what a thing is**, whole words (`verification`, not `ver`; `deviceId`, not `did`), and a boolean reads as a question (`isExpired`, `hasKey`). Test names are sentences in backticks.
  - **KDoc says why, not what.** A comment explains a decision or a trap; it never repeats the code beside it. Public contracts in `api` modules carry a KDoc.
  - **Four-space indent, no tabs, LF line endings, one newline at the end of the file.** One top-level class per file, named after the file, unless the others are small and private to it.

  ```kotlin
  val response = client.post("verifications") {
      operation("start_verification")
      header(IDEMPOTENCY_KEY_HEADER, key)
      setBody(
          FormDataContent(
              Parameters.build {
                  append("To", number)
                  append("Channel", "sms")
              },
          ),
      )
  }
  ```
- **Every payload is a typed `@Serializable` data class (kotlinx.serialization), never a map or untyped JSON.** This covers REST requests and responses, WebSocket frames both ways (one class per frame type; phone frames as a `sealed interface` with `type` as the class discriminator), events, realtime frame `data` (encoded from the frame's class, never built by hand), Asha's tool arguments and results, our own JWT header and claims, and every vendor request and response (private `@Serializable` classes per vendor, decoded with `ignoreUnknownKeys`). No `Map<String, Any>`, `JsonObject`/`JsonElement` building or `.jsonObject["key"]` reading in payload code. Exceptions: a value the model or a vendor defines as an opaque string (tool `arguments`), and a map the vendor's API itself requires (FCM `data` is a string map, encoded from a typed class inside the vendor package). Error field details go out as `List<FieldError(field, problem)>`. (Decision 2026-10-27.)
- **Outbound HTTP is Ktor's client, always, through `HttpClients`.** A vendor integration (and anything else that calls out over HTTP) asks `HttpClients.named("<vendor>") { baseUrl = ... }` from `infrastructure:api` and uses that Ktor `HttpClient`: `post(path) { operation("send_sms"); header(...); setBody(FormDataContent(...)) }`. That client brings the timeouts (5 s connect, 15 s request; a vendor may override the request timeout when its latency needs it, recorded in architecture.md: Workers AI 60 s), the retry table, the circuit breaker, tracing, the `circaso_integration_*` metrics and log lines that never carry numbers, codes, tokens or bodies. Never `java.net.http`, `HttpURLConnection`, OkHttp, Apache, Retrofit or a hand-built `HttpClient(...)`: only `infrastructure:impl` builds clients. A POST is retried only if it carries an `Idempotency-Key` (use the `IDEMPOTENCY_KEY_HEADER` constant), so a send such as an SMS or a payment without one is never repeated. Name each call with `operation("...")` for the metric. Catch `CancellationException` and rethrow it, translate everything else into `IntegrationError` (including `CircuitOpenException` → `Unavailable`), and log the exception class, never its message (it can hold a URL). In tests give the vendor class a Ktor client over `MockEngine` (a small fake `HttpClients` returning it), not a local server; vendor WebSockets, which MockEngine can't do, are tested against Ktor's test host. The Konsist rule `httpOnlyThroughKtor` fails the build on any other client. Vendor SDKs that bring their own transport (the AWS SDK for Kotlin for R2) are the one exception, and live in `infrastructure:impl`.
- **Routes are grouped by path** (Ktor's [Group by path](https://ktor.io/docs/server-routing-organization.html#group_by_path)). A parent `route("/order") { … }` owns the path; each endpoint inside carries only its own segment and **no leading slash**, so it inherits what is above it. `get { }` is the parent's own path, `get("{id}") { }` and `get("{id}/total") { }` are below it, and a deeper resource nests another `route("/{id}") { get { } put { } delete { } }`. One controller file per area; scopes wrap groups with `authenticate(Scope.APP) { … }` inside the path groups. The Konsist rule `routesGroupByPath` fails the build on `get("/…")` in a controllers file.

  ```kotlin
  route("/contacts") {
      authenticate(Scope.APP) {
          get { contacts.list(call) }.docs<NoBody, ContactsResponse>("U8 · Your contacts", TAG)
          post("sync") { contacts.sync(call) }.docs<SyncRequest, SyncResponse>("U7 · Sync the phone book", TAG)
          delete("{userId}") { contacts.remove(call) }.docs<NoBody, RemovedResponse>("U16 · Remove a contact", TAG)
      }
  }
  ```
- **Postgres and Exposed** (decision 2026-10-27; architecture.md → Infrastructure → Exposed and PostgreSQL).
  - **Every database call goes through `Database.tx { }`,** over R2DBC: no thread ever blocks on the database. Never an Exposed transaction of a module's own, `R2dbcDatabase.connect`, JDBC (`java.sql`, `exposed-jdbc`; only the `Migrate` code in infrastructure uses JDBC, for Flyway) or `runBlocking`; Konsist fails the build on each. Results are `Flow`s, consumed inside the transaction. An Exposed `Table` is referenced only from the `data` package of the module that owns it.
  - **One operation, one transaction, database work only.** A use case opens the transaction through the domain port `Transactor.run("<area>.<operation>") { }` (implemented in `data` over `Database.tx(operation)`; repositories join it through the coroutine context). The operation is a string literal, never a value, on every `run` and `tx` (Konsist). A failed statement is a `DatabaseException` (SQLSTATE and constraint only); `isUniqueViolation` answers a lost claim. An operation's rows, its uniqueness claims and its outbox row are written in one `run`, and every decision that depends on what was read (version checks, claims, caps, successors, last-ack deletes) is made inside it. Nothing inside it calls Redis, HTTP, `EventBus` or any other port that leaves the database (Konsist checks `Transactor.run` and `attempt` lambdas anywhere in `domain`, and those calls throw at run time inside a transaction); the event is published right after the commit.
  - **The outbox is infrastructure's.** A module that publishes sets `outbox = true` in its `DatabaseModuleSpec`, creates `<schema>.outbox` in its V1 script with the shared DDL (architecture.md → Durable events) and writes rows with `database.writeOutbox(envelope)` inside its transaction; it writes no `OutboxStore`. A `Database` handle is always named `database` (Konsist): inside `tx { }` the name `db` means the transaction's own `db` (Exposed's), not the field.
  - **Lock order.** When a transaction touches a parent row and its children (a chat and its members, a media file and its pending recipients), it locks the parent first, on every path.
  - **Ids are UUIDv7 made on the server.** Messaging's partitions are keyed by them, so an id is never taken from a phone. A `java.util.UUID` column is `javaUUID("…")` (`org.jetbrains.exposed.v1.core.java`): Exposed 1.6's `uuid()` is `kotlin.uuid.Uuid`.
  - **Claims and races use the returning forms.** A unique value is a `UNIQUE` constraint on the table that holds it: `insertIgnore` and its `insertedCount` say who won. `deleteReturning` when exactly one caller may take a row (acks, member removal, a one-time prekey); `updateReturning` for conditional changes and optimistic locks (`SET version = version + 1 WHERE … AND version = ?`, 0 rows is a conflict); `upsert` only with explicit `keys`. A second access path is an index on the same table. A cleared value is a real `NULL`.
  - **A write whose outcome decides an event or a response is a repository method that returns a Boolean or a count** (`insertIgnore` → inserted, a conditional `upsert` or `updateReturning` → changed, a delete → count), and the outbox row is written only when it says so. Never a read followed by a blind write: two callers both read "absent" and both answer "created". An operation that may fail after it wrote uses `Transactor.attempt`, whose `Resource.Error` rolls the transaction back (decision 2026-10-27, PG3 review).
  - **Lists page by keyset** (`WHERE (created_at, id) < (?, ?) ORDER BY … LIMIT n`), never `OFFSET`.
  - **Rows that expire** carry `expires_at`: every read filters `expires_at > :now`, with `:now` bound from the app's injected `Clock` (so tests can move time), while the purge job compares with the database's `now()`, and the module's purge job deletes them in chunks. The high-churn tables the module doc names are partitioned by day and expire by dropping partitions.
  - **Migrations are Flyway SQL** in `src/main/resources/db/<module>/V<n>__<what>.sql`, declaring the same keys, constraints and indexes as the `Table` objects. A script is never edited once it has been applied anywhere; a change is a new script. No `SchemaUtils.create` anywhere, development and tests included. A draft from the Exposed Gradle plugin (`generateMigrations`) is reviewed line by line before it is committed, because it can contain `DROP`s. Drafting needs `db/<module>/beforeMigrate.sql` (Flyway runs it before every migrate): `DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = '<module>') THEN CREATE SCHEMA <module>; END IF; END $$;`. It runs at every `Migrate` as the module's role, so it must stay idempotent and do nothing but create the schema when it is missing.
  - **Data-layer tests run on Testcontainers Postgres** (`postgres:18`, one container per test JVM, reset with `TRUNCATE`), migrated by the module's own Flyway scripts, through infrastructure's test fixtures: `testImplementation(testFixtures(project(":infrastructure:impl")))`, then `TestPostgres.database(spec)` and `TestPostgres.truncate(schema)`, which hand out `infrastructure:api` types only (the one test-only exception in the dependency rules); use-case tests use in-memory fakes of the ports. No test, and no local run, ever points at the sandbox database.
  - **The SQL logger stays off** outside a local debug run; it would print bound phone numbers and keys.
- **Message and event ids:** UUIDv7 made in the same millisecond come out in random order. Where a list is shown by id (a chat), take ids from `OrderedIds`.
- **Architecture tests only mean something if they re-run:** `architecture-tests` declares the other modules' sources as inputs; do not remove that, or the rules report "up to date" and check nothing.

- A domain class never imports Ktor, Exposed or kotlinx-serialization. `Table` objects and `ResultRow`s never leave `data`; DTOs never leave `presentation`.
- Tables are designed around queries. Every read is served by a primary key or an index; no scans.
- No module reads another module's schema or Redis keys. Ask through its `api`, tell through its stream.
- Never log tokens, OTP codes, PINs, device keys, amounts, emails or raw phone numbers. Keep the SQL logger off, so bound values never reach a log.
- Never retry a POST that has no idempotency key (SMS, email).
- Times are ISO-8601 UTC in the API and `timestamptz` in storage. Money is integer minor units with its asset.
- `/v1` only changes additively.

## Done means

- `./gradlew build` passes: compile, tests, Konsist, and the Gradle dependency rules.
- The OpenAPI spec is generated and lists every new route with its envelope and error codes.
- It runs locally against `docker compose up` with the `log`/`fake` integrations.
- Your summary lists what was built, anything you had to assume, and every `TODO(decision: …)` you left.
