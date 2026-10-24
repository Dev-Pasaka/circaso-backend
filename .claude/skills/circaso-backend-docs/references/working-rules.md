# Circaso backend

Circaso (formerly CoinX) is a chat-first social finance app for African markets: chat, money through Circle user-controlled wallets, group calls, and chamas. This repository is its backend: one Kotlin/Ktor deployable split into strict modules, each with its own ScyllaDB keyspace, talking to the others only through published contracts and durable events.

The architecture is decided; the code is not written yet. Your job is to build it exactly as designed.

## Read first

Before writing code, read these in order. They are the spec; this file only tells you how to work with them.

1. `docs/architecture.md`: principles, project structure, module anatomy, results and errors, how modules talk, gateway, infrastructure and Kandra, logging, observability, integrations, realtime, conventions.
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
| Database | ScyllaDB (ScyllaDB Cloud in staging and production, Docker locally) through **Kandra** `com.kandraorm.kandra` 0.5.1 with its BOM: `kandra-ktor`, `kandra-koin`, `kandra-migrate`, `kandra-test` |
| DI | Koin |
| JSON | kotlinx-serialization only (never Gson) |
| Events | Outbox in each keyspace + Redis Streams |
| Cache, counters, locks | Redis (Valkey compatible) |
| Architecture tests | Konsist, plus the `circaso.api` and `circaso.impl` Gradle convention plugins |
| Tests | kotlin-test / JUnit 5, in-memory fakes for the domain, Testcontainers ScyllaDB and Redis for data tests |
| Observability | OpenTelemetry Java agent, Logback (pretty console locally, `LogstashEncoder` JSON elsewhere), Prometheus metrics |
| AI (assistant, later) | Koog |

Libraries not named in the docs (the Redis client, for example) are your proposal: pick a well-maintained option, say so in your summary, and keep it behind the infrastructure handle so it can change.

Packages are `com.circaso.<module>.<api | presentation | domain | data | di>`.

## Build order

Work in this order. Each milestone ends green: it builds, its tests and the Konsist rules pass, and nothing from a later milestone is half-started.

**M0 — Skeleton**
- Gradle multi-module build exactly as the project tree in `docs/architecture.md`: `build-logic` (convention plugins `circaso.kotlin`, `circaso.api`, `circaso.impl`), `gradle/libs.versions.toml`, `shared`, `shared-web`, `infrastructure/{api,impl}`, `integrations/{api,impl}`, `gateway`, `modules/identity/{api,impl}`, `modules/users/{api,impl}`, `app`, `architecture-tests`, `deploy`.
- `shared`: ids (UUIDv7 value classes), `Money`, `PhoneNumber`, `Resource`, `ErrorKind`, `DomainError`, `ErrorDetails`, `DomainException`, `DomainEvent`, the `EventBus`, `Realtime` and `RealtimeReplay` contracts.
- `shared-web`: `DefaultResponse`, `ErrorKind.httpStatus()`, `toDefaultResponse`, `respondDefault` (with `Retry-After`), StatusPages.
- `architecture-tests`: every Konsist rule in Module anatomy and the dependency table.
- `deploy/docker-compose.yml` with ScyllaDB and Redis; `app` as a plain embedded Ktor server (no command-line arguments; settings from environment variables, local ones from `.env`), with `Migrate` as a second main class; `/health/live` and `/health/ready`.
- Logging as in the Logging section: both formats, request id in MDC.

**M1 — Infrastructure**
- Kandra installed once with keyspaces contributed by each module's `KandraModuleSpec`; no default keyspace; `AUTO_CREATE` locally, `VALIDATE` elsewhere; TLS and credentials for ScyllaDB Cloud from `Secrets`.
- `kandra-migrate` runner per keyspace, run only by `app migrate`.
- `RedisScope` with per-module prefixes; `Scheduler` with a Redis leader lock.
- `EventBus`: outbox write in the same batch, immediate `XADD`, `publishedAt` stamp, single-leader relay with checkpoint, consumer groups, `XACK` after success, `XAUTOCLAIM` at 30 s, 5 attempts then `events:dlq`, `evt:done:` idempotency, 7-day trim.
- Named Ktor HTTP clients with the retry table and circuit breaker; config and secrets; OpenTelemetry wiring and the `Telemetry` handle.

**M2 — Integrations**
- Contracts in `integrations:api`. `dev` package: `log` and `fake` implementations of every contract (local runs and tests use these).
- Real implementations needed by identity and users: Africa's Talking SMS, Resend email, Google and Apple token verification (and Apple revoke), Firebase phone number verification, FCM and APNs push. One package per vendor; vendor types never cross the contract.

**M3 — Gateway**
- `/v1` routing, `X-Client-Version` check (426), `TokenVerifier` auth with the revoked-session deny-list, scopes `onboarding`, `app`, `agent`, `ops`, and the agent rules (`GET` under `/v1/agent/` only, and nothing else there).
- Rate limits (the table in Gateway), idempotency with the in-progress marker, the `RequestLog` plugin, webhooks at `/v1/hooks/{vendor}`, OpenAPI generation with Swagger UI outside production behind `ops`.
- WebSocket hub: auth on first frame, heartbeat, per-node Redis channel delivery, push fallback, replay through `RealtimeReplay`.

**M4 — Identity**: I1–I17, the sign-in decision table, the nine entities, Redis keys, events (with `phoneHmac`, never raw numbers), error codes and the attestation rules, exactly as `docs/modules/identity.md`. The flow in `identity-signin-flow.md` is the acceptance test: every screen's call must return what that screen needs.

**M5 — Users**: U1–U16, the reach rule, contact sync with HMACs, the entities and Redis keys, consumed and published events, `RealtimeReplay`, error codes, and the read-only agent routes, as `docs/modules/users.md`.

**Not yet**: messaging, wallet, calls and the assistant need their own design first. Only build what their docs say when Pasaka asks.

## How every endpoint is built

Controller (a `Route` extension function) → service method → use case → repository interface → repository implementation → Kandra entity. For each endpoint:

- Request and response DTOs in `presentation/dto`, mapped to and from models; shape checks in the mapper.
- One use case per operation (`suspend operator fun invoke`), returning `Resource<T>`; expected failures are `Resource.Error` with the module's error code from the docs, never thrown.
- The service method calls the use case and does nothing else. Each service class is in its own file.
- The controller is `fun Route.xController(service: XService)` in its own file (`controllers/ProfileController.kt`): path groups, scope, handlers and `.docs(...)` together, as in the Ktor reference (`fun Route.patientRatingController(service)` with `authenticate { route("/…") { post { … } } }`). The module's Koin `RouteContributor` installs its controllers; there is no separate routes file.
- Declared scope, `Idempotency-Key` on state changes, rate limit where the Gateway table lists one.
- OpenAPI: describe the route with `.docs<Request, Response>(summary, tag, errors, notes)` from `shared-web`: the response is declared as `DefaultResponse<T>`, the error codes it can return are listed, and the module's tag groups it. The spec is generated from these descriptions; the reader at `/docs` is Scalar, not Swagger UI.
- Tests: the use case against in-memory fakes of its ports (happy path and every error code it can return); the repository against Testcontainers ScyllaDB; one end-to-end test per flow (sign-up, sign-in on the same phone, new phone, refresh, sign-out, contact sync).

## Rules that are easy to get wrong

- **Configuration is environment variables, not arguments.** `main` takes no arguments: it loads `.env` (local development only, never overriding the real environment), builds `EnvConfig` and `EnvSecrets` from the result and starts `embeddedServer(Netty, …)`. A setting's dotted name is its variable (`scylla.contact-points` is `SCYLLA_CONTACT_POINTS`; a secret `identity.signing-key` is `IDENTITY_SIGNING_KEY`). A new setting is a line in `.env.example`. There is no `application.conf`. Nothing may log before `main` has read `.env` (no top-level logger `val` in `Main.kt`).
- **Logging is controlled per service by environment variable** (`LOG_LEVEL`, `LOG_LEVEL_OTHER`, `LOG_LEVEL_REQUESTS`, `…_IDENTITY`, `…_DATABASE`, `…_REDIS`, `…_HTTP` and so on; `OFF` to silence); a new module or integration gets its own `LOG_LEVEL_<NAME>` line in `logback.xml`, and `LogLevelsTest` covers it.

- **Every route is documented into a section.** `.docs<Req, Res>(summary, tag, errors, notes)` takes a `tag` that must be one of the sections in `gateway/.../ApiDocsCatalog.kt` (Health, Webhooks, Sign in, Phone verification, Register device, Profile, Handles, Contacts, Connections and invites, Blocks and reach, Asha chat, Asha voice and usage, Account and security, Account recovery, Agent routes). The catalog orders the reference as a person uses the API (Infrastructure first, then authentication, profile, people, Asha, account) and explains each section; a new area adds a tag and its explanation there. A route in the spec with no section fails `ServeTest`. The reader page is `gateway/src/main/resources/docs/reader.html` (circaso.com's tokens) and the introduction is `guide.md`: keep the quick start, scopes, limits and error table in step with the code.
- **Every dependency and plugin is declared in the version catalog, `gradle/libs.versions.toml`, and nowhere else.** One version per library family (`ktor`, `kotlinx-coroutines`, ...) in `[versions]`, one line per artifact in `[libraries]`, and build files use the generated accessors (`libs.ktor.server.core`, `libs.kotlinx.serialization.json`). No second catalog (the old `ktorLibs` import was removed), no `"group:artifact:version"` strings in a build file, and a module declares what its own code imports instead of relying on another module to bring it. Adding a library means adding its line to the catalog first. The one exception is a Gradle *settings* plugin (the Java toolchain resolver in `settings.gradle.kts`), because the catalog is not available there.
- **Ask before changing the project's structure.** A new module, a move of packages, a new convention, or a change to how dependencies or the build are organised is Pasaka's decision: put the options and a recommendation in front of them and wait.
- **Outbound HTTP is Ktor's client, always, through `HttpClients`.** A vendor integration (and anything else that calls out over HTTP) asks `HttpClients.named("<vendor>") { baseUrl = ... }` from `infrastructure:api` and uses that Ktor `HttpClient`: `post(path) { operation("send_sms"); header(...); setBody(FormDataContent(...)) }`. That client brings the timeouts (5 s connect, 15 s request), the retry table, the circuit breaker, tracing, the `circaso_integration_*` metrics and log lines that never carry numbers, codes, tokens or bodies. Never `java.net.http`, `HttpURLConnection`, OkHttp, Apache, Retrofit or a hand-built `HttpClient(...)`: only `infrastructure:impl` builds clients. A POST is retried only if it carries an `Idempotency-Key` (use the `IDEMPOTENCY_KEY_HEADER` constant), so a send such as an SMS or a payment without one is never repeated. Name each call with `operation("...")` for the metric. Catch `CancellationException` and rethrow it, translate everything else into `IntegrationError` (including `CircuitOpenException` → `Unavailable`), and log the exception class, never its message (it can hold a URL). In tests give the vendor class a Ktor client over `MockEngine` (a small fake `HttpClients` returning it), not a local server. The Konsist rule `httpOnlyThroughKtor` fails the build on any other client. Vendor SDKs that bring their own transport (the AWS SDK for Kotlin for R2) are the one exception, and live in `infrastructure:impl`.
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
- **Clearing a value:** Kandra writes nothing for a `null` (it is "unset") through `save`, `saveInBatch` and `update`. A column that must be clearable stores a sentinel (empty text, the nil UUID, the epoch) and the row mapper turns it back into `null`; `saveWithNulls` is the only call that writes a real null. See `docs/kandra-bugs.md`.
- **Message and event ids:** UUIDv7 made in the same millisecond come out in random order. Where a list is shown by id (a chat), take ids from `OrderedIds`.
- **Architecture tests only mean something if they re-run:** `architecture-tests` declares the other modules' sources as inputs; do not remove that, or the rules report "up to date" and check nothing.

- A domain class never imports Ktor, Kandra or kotlinx-serialization. Rows never leave `data`; DTOs never leave `presentation`.
- Entities name their keyspace in `@ScyllaTable(keyspace = …)`, end in `Row` and are unique across modules. Unique values use a claim table with `saveIfNotExists` before the batch; an LWT can't join a batch.
- Tables are designed around queries. No `ALLOW FILTERING`, no scans.
- No module reads another module's keyspace or Redis keys. Ask through its `api`, tell through its stream.
- Never log tokens, OTP codes, PINs, device keys, amounts, emails or raw phone numbers. Mark such entity fields `@Sensitive`.
- Never retry a POST that has no idempotency key (SMS, email).
- Times are ISO-8601 UTC in the API and epoch milliseconds in storage. Money is integer minor units with its asset.
- `/v1` only changes additively.

## Done means

- `./gradlew build` passes: compile, tests, Konsist, and the Gradle dependency rules.
- The OpenAPI spec is generated and lists every new route with its envelope and error codes.
- It runs locally against `docker compose up` with the `log`/`fake` integrations.
- Your summary lists what was built, anything you had to assume, and every `TODO(decision: …)` you left.
