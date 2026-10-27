# Circaso Backend — Architecture & Plan

2026-10-21 · Pasaka

Circaso's backend is one Kotlin/Ktor deployable split into strict modules, each owning its own ScyllaDB keyspace and talking to others only through published interfaces and events; identity and users are the first two modules and set the pattern for the rest.

This tab holds what every module shares. Each module's endpoints, data, events and error codes live in Modules, one sub-tab per module; every decision and open question lives in Decisions. New modules and features are added on top of these, never in a separate document.

## Principles

Twelve rules every module follows; a change to any of them is an architecture decision, written down here first.

1. **One deployable, many modules.** A modular monolith in Kotlin and Ktor. Modules can be split into their own services later without rewriting them.
2. **Four layers.** The gateway handles the edge, business modules hold the logic, integrations wrap third-party services, infrastructure provides connections and libraries. Dependencies only point downward.
3. **Infrastructure is plumbing, not logic.** It installs and configures libraries and connections, including the shared HTTP client with its retries, and hands each module a scoped handle. Modules build their own tables, queries and logic on top.
4. **Vendors live behind contracts.** Every third-party service is reached through an interface in `integrations:api`. Swapping Africa's Talking for Twilio is a new implementation and a config change; no business module notices.
5. **One module, one keyspace.** Each module owns a ScyllaDB keyspace and is the only code that reads or writes it.
6. **Modules meet only through contracts.** Another module's `api` (interfaces, data types, events) is the only thing a module may depend on. Never its internals.
7. **No cross-module transactions.** Work that spans modules happens through durable events: each event is written to an outbox in the same batch as the change that caused it, then delivered through Redis Streams, and every handler is idempotent. There is no workflow engine: wallets are user-controlled, so the backend never moves money itself, and work that must run on a schedule (stuck transactions, audits) is a plain scheduled job.
8. **Tables are designed around queries.** One table per question asked; data is duplicated across tables and modules where reads need it.
9. **Commands over HTTP, signals over the socket.** Anything that changes state, above all money, is an HTTP call with an idempotency key. The WebSocket carries delivery and lightweight signals.
10. **The socket is a doorbell, not the mailbox.** Every pushed event also lives in its module's data, so a missed frame is recovered on reconnect.
11. **Circle holds the money.** Our database records transactions for statements and audit; Circle is the source of truth for balances.
12. **Costs stay low.** Managed where running it ourselves would cost more than it saves (ScyllaDB Cloud, Cloudflare, Circle, SMS), self-hosted where it is cheap to run (Redis, the Grafana stack), and nothing added before a feature needs it.

## System at a glance

> **Diagram:** system layers · gateway, modules, infrastructure, external services (see the diagram in the Claude Docs version of this document)

Requests come down through Cloudflare and the gateway into a module's api. Modules reach the database, cache and event streams through handles that infrastructure scopes to them, and reach every outside service through an integration contract; the vendor implementations behind those contracts make their calls with infrastructure's shared HTTP client.

## Project structure

One Git repository, one Gradle build, one container image. Each folder below is a Gradle module; the convention plugins in `build-logic` stamp the rules onto each kind of module.

```
circaso-backend/
├─ build-logic/                  convention plugins: circaso.kotlin, circaso.api, circaso.impl
├─ gradle/libs.versions.toml     one place for every library version
│
├─ shared/                       the kernel: ids, Money, PhoneNumber, Resource, DomainError,
│                                DomainEvent and the EventBus, Realtime and RealtimeReplay contracts
│
├─ shared-web/                   DefaultResponse envelope, Resource → response mapping,
│                                StatusPages: for presentation layers and the gateway
│
├─ infrastructure/
│  ├─ api/                       handles modules borrow: Kandra repositories, RedisScope,
│  │                             Scheduler, ObjectStore, HttpClients, Secrets, Telemetry
│  └─ impl/                      ScyllaDB + Kandra, Redis, the EventBus implementation
│                                (outbox relay, Redis Streams consumers), Cloudflare R2,
│                                Ktor HTTP client (retries, timeouts, circuit breaker),
│                                config, OpenTelemetry, migrations, scheduled jobs
│
├─ integrations/                 ONE module for every third party
│  ├─ api/                       the only thing exposed: contracts such as SmsGateway,
│  │                             EmailGateway, SocialSignIn, CarrierPhoneVerification,
│  │                             PushGateway, StablecoinCustody, CallMedia
│  └─ impl/                      vendor code, one package per vendor, never visible outside
│     ├─ sms/africastalking/
│     ├─ sms/twilio/             (later, same SmsGateway contract)
│     ├─ email/resend/
│     ├─ social/google/
│     ├─ social/apple/
│     ├─ firebase/               phone verification, FCM push
│     ├─ push/apns/
│     ├─ circle/
│     └─ dev/                    log and fake implementations of every contract
│
├─ gateway/                      Ktor edge: auth, scopes, rate limits, idempotency,
│                                logging/tracing, WebSocket hub, vendor webhooks,
│                                route assembly
│
├─ modules/
│  ├─ identity/
│  │  ├─ api/                    contract other modules and the gateway may use
│  │  └─ impl/                   presentation, domain, data, di
│  ├─ users/
│  │  ├─ api/
│  │  └─ impl/
│  └─ (messaging, wallet, calls — later, same shape)
│
├─ app/                          main(): `serve` loads config, picks vendor implementations,
│                                wires modules, starts the server; `Migrate` runs migrations
├─ architecture-tests/           Konsist rules that fail the build on a boundary breach
└─ deploy/                       docker-compose for local dev (ScyllaDB, Redis)
```

Code packages follow the folders: `com.circaso.identity.api`, `com.circaso.identity.domain`, `com.circaso.users.data`, and so on.

## Module anatomy

Every business module is two Gradle modules: a small `api` that others may see, and an `impl` that nobody else can. Inside `impl`, the business logic in `domain` never touches a library directly.

**`api` — the contract**

- Interfaces other modules call (for example `ReachPolicy`, `AccountDirectory`).
- The data types those interfaces take and return.
- The events the module publishes.
- No Ktor, no Kandra, no Koin. Depends only on `shared` and other modules' `api`.

**`impl` — everything else, in three layers plus `di`**

| Layer (package) | Holds | Calls | Never sees |
| --- | --- | --- | --- |
| `presentation` | **Controllers**: one `fun Route.xController(service: XService)` per area, in its own file, holding the path groups (`route("/…") { }`), the scope (`authenticate`), the handlers and their OpenAPI docs; a handler receives the request DTO, maps it, calls a service, responds. **Services**: one class per area of the module (`ProfileService`, `ContactService`), **each in its own file**, grouping that area's use cases and exposing them as methods. Request and response DTOs (`@Serializable`) and their mappers. Handlers for other modules' events. The implementation of the module's own `api` | Services call use cases; controllers, event handlers and `api` implementations call services | Rows, Kandra, repositories |
| `domain` | **Models** (plain Kotlin, used only inside the domain). **Repository interfaces**, declared here and implemented in `data`. **Use cases**, one business operation each. Domain errors and other ports | Repository interfaces and ports; `shared`, own `api`, other modules' `api`, `integrations:api` | DTOs, rows, Ktor, Kandra, any library |
| `data` | Kandra entities (`*Row`), repository implementations, row ↔ model mappers, migrations, other data sources (Redis caches, R2 objects) | `infrastructure:api` handles | DTOs, Ktor, services |
| `di` | The Koin module: binds repository interfaces to implementations, builds use cases, services and controllers, registers routes, event handlers, the `api` implementations and the Kandra spec | Everything in `impl` | — |

A business module never talks to a vendor itself: it calls a contract such as `SmsGateway`, and the integrations layer does the rest.

**Inside a module** (users shown; every module has the same folders):

```
modules/users/impl/src/main/kotlin/com/circaso/users/
├─ presentation/
│  ├─ controllers/   ProfileController.kt, ContactController.kt, HandleController.kt: each a `fun Route.profileController(service)`
│  ├─ services/      ProfileService.kt, ContactService.kt, HandleService.kt (one class per file)
│  ├─ dto/           UpdateProfileRequest.kt, ProfileResponse.kt, …
│  ├─ mappers/       DTO ↔ model
│  ├─ events/        AccountActivatedHandler.kt: reactions to other modules' events
│  ├─ agent/         AgentRoutes.kt, AgentController.kt, dto/: read-only routes for the assistant
│  └─ api/           ReachPolicyImpl.kt, ProfilesImpl.kt: this module's users:api contract
├─ domain/
│  ├─ models/        Profile.kt, Contact.kt, Handle.kt
│  ├─ repositories/  ProfileRepository.kt, ContactRepository.kt (interfaces only)
│  ├─ usecases/      UpdateProfile.kt, ClaimHandle.kt, SyncContacts.kt, …
│  └─ errors/        UsersError.kt
├─ data/
│  ├─ entities/      ProfileRow.kt, ContactRow.kt, …
│  ├─ repositories/  ProfileRepositoryImpl.kt, …
│  ├─ mappers/       row ↔ model
│  ├─ cache/         reach decisions in Redis
│  └─ migrations/    V001_CreateProfiles.kt, …
└─ di/
   └─ UsersModule.kt Koin bindings, routes, event handlers, Kandra spec
```

**Rules for use cases and services**

1. **A use case does one business operation** and has one public function (`suspend operator fun invoke`). It gets repository interfaces and other ports injected (`EventBus`, `SmsGateway`, another module's `api`), never a concrete class.
2. **Use cases don't call each other.** A rule two of them share lives on the model (`Handle.validate()`) or in a small domain helper, so each use case stays readable on its own.
3. **A service has no logic of its own.** Each method calls one use case and returns its `Resource`. If a method needs an `if`, that decision belongs in a use case.
4. **Services are split by area, not one per module,** so none grows into a god class: `ProfileService`, `ContactService`, `HandleService`. A controller injects only the services it uses.
5. **Every way in goes through a service.** Controllers (HTTP), event handlers (streams) and `api` implementations (other modules) all call services, never use cases or repositories directly, so a call behaves the same whichever door it came through.

**The assistant's folder.** Each module that the in-app assistant may read from has `presentation/agent/`: its own routes under `/v1/agent/<module>/…`, its own controller and its own response DTOs, which carry only what the assistant needs. Agent controllers call only service methods that read (`get`, `list`, `find`, `count`, `summarise`); Konsist fails the build if anything in `agent/` calls a method that writes, and the gateway refuses non-`GET` methods for the `agent` scope as a second guard. Agent routes appear under their own `agent` tag in the OpenAPI spec, which doubles as the assistant's tool list.

### Data flow through a module

Data changes shape twice on its way in and twice on its way out: DTOs stop at presentation, rows stop at data, and the domain only ever sees models.

> **Diagram:** data flow · DTO → model → row and back (see the diagram in the Claude Docs version of this document)

**Rules**

1. **DTOs live only in `presentation`.** A request is deserialised into a request DTO, mapped to a model, and passed to a use case. The use case's result model is mapped to a response DTO. Domain functions never take or return a DTO.
2. **Rows live only in `data`.** The domain asks a port (`ProfileRepository.save(profile: Profile)`); the data layer maps the model to a `ProfileRow`, saves it with Kandra, and maps rows back to models on reads. A row never leaves `data`.
3. **Models are plain Kotlin.** No `@Serializable`, no Kandra annotations, no Ktor types. They use value types from `shared` (`UserId`, `PhoneNumber`, `Money`), so an invalid phone number can't exist as a model.
4. **Mappers sit at the edge that owns the foreign shape:** `toModel()` / `toResponse()` in `presentation`, `toRow()` / `toModel()` in `data`. They are plain extension functions, tested on their own.
5. **Validation in two places.** Presentation checks shape (required fields, formats, lengths) while mapping and returns `400` for a bad request. The domain checks rules ("this number is already taken", "handle changed less than 30 days ago") and returns a `DomainError`.
6. **Contracts and events between modules are their own types.** `users:api` exposes `PublicProfile`, not the `Profile` model, so a module can reshape its internals without breaking callers. The `api` types are mapped from models at the module edge too.
7. **The shapes may look alike.** `ProfileResponse`, `Profile` and `ProfileRow` often have similar fields. They stay separate anyway: the response can't leak a stored field by accident, and the table can change without touching the API.

```kotlin
// presentation/dto
@Serializable data class UpdateProfileRequest(val displayName: String, val about: String? = null, val version: Long)
@Serializable data class ProfileResponse(val userId: String, val displayName: String, val handle: String?, val avatarUrl: String?)
fun UpdateProfileRequest.toCommand(userId: UserId): Resource<UpdateProfileCommand> = …   // shape checks here
fun Profile.toResponse(avatarUrls: AvatarUrls): ProfileResponse = …

// presentation/controllers: a function on Route that takes the service. The parent route owns the path, each
// endpoint carries only its own segment with no leading slash (Ktor "Group by path"); handlers map, call the
// service and respond; `.docs<Req, Res>(…)` documents the route.
fun Route.profileController(profiles: ProfileService) {
    authenticate(Scope.APP) {
        route("/me/profile") {
            get {
                call.respondDefault(profiles.getMine(call.userId()).toDefaultResponse { it.toResponse() })
            }.docs<NoBody, MyProfileResponse>("U1 · Your full profile", "users")

            patch {
                val result = call.receive<UpdateProfileRequest>().toChange().flatMap { profiles.update(call.userId(), it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<UpdateProfileRequest, MyProfileResponse>("U2 · Change your profile", "users")
        }
    }
}

// di: the module hands the gateway its controllers
single<RouteContributor> { val profiles = get<ProfileService>(); RouteContributor { route -> route.profileController(profiles) } }



// presentation/services: groups the profile use cases, no logic of its own
class ProfileService(
    private val getMyProfile: GetMyProfile,
    private val updateProfile: UpdateProfile,
    private val setAvatarImage: SetAvatar,
) {
    suspend fun get(userId: UserId) = getMyProfile(userId)
    suspend fun update(command: UpdateProfileCommand) = updateProfile(command)
    suspend fun setAvatar(userId: UserId, objectKey: String) = setAvatarImage(userId, objectKey)
}

// domain/models
data class Profile(val userId: UserId, val displayName: DisplayName, val about: String?, val handle: Handle?, val version: Long)

// domain/repositories: interfaces only
interface ProfileRepository { suspend fun find(id: UserId): Profile?; suspend fun save(old: Profile?, new: Profile) }

// domain/usecases: one operation
class UpdateProfile(private val profiles: ProfileRepository, private val events: EventBus) {
    suspend operator fun invoke(command: UpdateProfileCommand): Resource<Profile> = …
}

// data/entities and data/mappers
@ScyllaTable(name = "profiles", keyspace = "circaso_users")
data class ProfileRow(@PartitionKey val userId: UUID, val displayName: String, val about: String?, val handle: String?, @Version val version: Long = 0)
fun ProfileRow.toModel(): Profile = …
fun Profile.toRow(): ProfileRow = …

// data/repositories
class ProfileRepositoryImpl(private val rows: KandraRepository<ProfileRow>) : ProfileRepository { … }

// di
val usersModule = module {
    single<ProfileRepository> { ProfileRepositoryImpl(get()) }
    single { GetMyProfile(get()) }; single { UpdateProfile(get(), get()) }; single { SetAvatar(get(), get()) }
    single { ProfileService(get(), get(), get()) }
    
}
```

Konsist checks the boundaries: nothing in `domain` imports `kotlinx.serialization`, Ktor or Kandra; nothing outside `presentation` references a `*Request` or `*Response`; nothing outside `data` references a `*Row`; controllers, event handlers and `api` implementations depend on services only; services depend on use cases only; use cases depend on `domain` interfaces and ports, never on a `*Impl`; classes in `services/` end in `Service`, each controllers file declares a `fun Route.xController(service)` and no class, and classes in `usecases/` have exactly one public function.

### Results, errors and responses

Every use case returns a `Resource<T>`; the domain defines its errors, the presentation layer maps any `Resource` to one `DefaultResponse` envelope, and Ktor's StatusPages turns anything thrown into the same envelope, so the app always gets one shape.

**In `shared` (pure Kotlin, used by the domain):**

```kotlin
sealed class Resource<out T> {
    data class Success<T>(val data: T, val message: String = "Success") : Resource<T>()
    data class Error(val error: DomainError, val cause: Throwable? = null) : Resource<Nothing>()
}

enum class ErrorKind { INVALID, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, UPSTREAM, INTERNAL }

interface DomainError {
    val code: String       // stable, e.g. "identity.phone_taken"; the app keys its copy on it
    val message: String    // safe to show the user
    val kind: ErrorKind    // says what went wrong, not which HTTP status
    val details: ErrorDetails? get() = null   // optional extras the app can act on
}

data class ErrorDetails(
    val retryAfterSeconds: Long? = null,             // RATE_LIMITED: also sent as the Retry-After header
    val fields: List<FieldError> = emptyList(),      // INVALID: which field and what is wrong with it (FieldError(field, problem); sent as FieldErrorDto)
)

class DomainException(val error: DomainError, cause: Throwable? = null) : RuntimeException(error.message, cause)
```

**In each module's `domain`, its own errors:**

```kotlin
sealed class IdentityError(
    override val code: String, override val message: String, override val kind: ErrorKind,
) : DomainError {
    data object PhoneTaken : IdentityError("identity.phone_taken", "This number already has a Circaso account.", ErrorKind.CONFLICT)
    data object CodeExpired : IdentityError("identity.code_expired", "That code has expired. Request a new one.", ErrorKind.INVALID)
    data class Locked(val retryAfterSeconds: Long) : IdentityError("identity.locked", "Too many attempts. Try again later.", ErrorKind.RATE_LIMITED)
}
```

**In `shared-web` (Ktor, used only by presentation layers and the gateway):**

```kotlin
@Serializable
data class DefaultResponse<T>(
    val httpStatusCode: Int = HttpStatusCode.OK.value,
    val status: Boolean = false,
    val message: String = "",
    val code: String? = null,         // error code; null on success
    val details: ErrorDetailsDto? = null,   // retryAfterSeconds, field errors; null on success
    val requestId: String? = null,
    val data: T? = null,
)

fun ErrorKind.httpStatus(): HttpStatusCode = when (this) {
    ErrorKind.INVALID -> HttpStatusCode.BadRequest
    ErrorKind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
    ErrorKind.FORBIDDEN -> HttpStatusCode.Forbidden
    ErrorKind.NOT_FOUND -> HttpStatusCode.NotFound
    ErrorKind.CONFLICT -> HttpStatusCode.Conflict
    ErrorKind.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    ErrorKind.UPSTREAM -> HttpStatusCode.BadGateway
    ErrorKind.INTERNAL -> HttpStatusCode.InternalServerError
}

fun <T, R> Resource<T>.toDefaultResponse(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    transform: (T) -> R,
): DefaultResponse<R> = when (this) {
    is Resource.Success -> DefaultResponse(successStatus.value, true, message, data = transform(data))
    is Resource.Error -> DefaultResponse(
        error.kind.httpStatus().value, false, error.message,
        code = error.code, details = error.details?.toDto(),
    )
}

suspend inline fun <reified R> ApplicationCall.respondDefault(body: DefaultResponse<R>) {
    body.details?.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respond(HttpStatusCode.fromValue(body.httpStatusCode), body.copy(requestId = callId))
}
```

**A route, end to end:**

```kotlin
// controller method behind PATCH /v1/me/profile
suspend fun update(call: ApplicationCall) {
    val result = call.receive<UpdateProfileRequest>().toCommand(call.userId())
        .flatMap { profiles.update(it) }                           // ProfileService → UpdateProfile use case
    call.respondDefault(result.toDefaultResponse { it.toResponse(avatarUrls) })
}
```

**StatusPages (installed once by the gateway) catches what was thrown:**

| Thrown | Response |
| --- | --- |
| `DomainException` | Its error's kind, code and message, as if it had been returned |
| Malformed JSON, missing field (`BadRequestException`, `SerializationException`) | `400`, code `request.malformed` |
| Anything else | `500`, code `internal.error`, message "Something went wrong"; the exception is logged with the request id |
| Unknown route | `404`, code `request.not_found` |

**Rules**

- **Expected failures are returned, not thrown.** "Number taken" is a `Resource.Error`. `DomainException` exists for the rare spot where returning is awkward; it ends up in the same envelope.
- **The status comes from the error's kind**, never chosen per route. That replaces the old `failedStatusCode = NotFound` default, which made every failure a 404.
- **The `cause` is for logs only.** It is logged with the request id and never sent to the app, so stack traces and vendor messages can't leak.
- **The body tells the truth.** `httpStatusCode` in the body always equals the real HTTP status, and there is no `200` with `status: false`.
- **kotlinx-serialization only**, no Gson: `DefaultResponse<T>` is generic, so routes respond through the reified `respondDefault` and Ktor's content negotiation picks the right serializer.
- `shared-web` may depend on `shared` and Ktor. Presentation layers and the gateway may use it; the domain may not.

**Dependency rules**

| From | May depend on | Never on |
| --- | --- | --- |
| `shared` | Kotlin stdlib, kotlinx-serialization | anything else |
| `infrastructure:api` | `shared` | any module |
| `infrastructure:impl` | `infrastructure:api`, libraries | any business or vendor module |
| `integrations:api` | `shared` | any vendor module, any vendor SDK |
| `integrations:impl` | `integrations:api`, `infrastructure:api`, vendor SDKs, one package per vendor | business modules; one vendor package never imports another |
| `x:api` | `shared`, `y:api` | any `impl`, any library |
| `x:impl` | `x:api`, `y:api`, `infrastructure:api`, `integrations:api`, `shared` | `y:impl`, `infrastructure:impl`, any vendor module, `gateway` |
| `gateway` | every `api`, `infrastructure:api`, `integrations:api` | any `impl`, any vendor module |
| `app` | everything | — (the only place that knows the implementations) |

**Enforcement.** Two guards fail the build: the `circaso.api` and `circaso.impl` Gradle plugins reject a forbidden project dependency at configuration time, and Konsist tests in `architecture-tests` reject forbidden imports (for example `domain` importing Kandra, or anything referencing another module's tables).

## How modules talk

Modules use three ways to reach each other; pick by whether the caller needs an answer now, is telling others that something happened, or must reach a person's phone.

| Style | Use when | Example | Failure behaviour |
| --- | --- | --- | --- |
| **Direct call** through another module's `api` | The caller needs an answer to continue: a read or a single check | messaging asks `users.ReachPolicy.canReach(a, b)` before creating a chat | Error returned to the caller as a `Resource.Error` |
| **Event** on Redis Streams | Something happened and others may react; the publisher doesn't wait | identity publishes `AccountActivated`; users creates the profile | Redelivered until acknowledged; dead-lettered after 5 failures; handlers are idempotent |
| **Realtime delivery** through the gateway | A client must be told something now | `users.contact.joined` to everyone waiting for that number | Fallback to push; client catches up from data on reconnect |

**Rules that keep this healthy**

- A direct call never writes into another module. Writes across modules happen through events.
- Hot paths don't call other modules per request. Messaging keeps a copy of display names and avatars and updates it on `ProfileUpdated`, instead of asking users for every message.
- Events carry ids and the facts that changed, not whole objects. A handler that needs more calls the owner's `api`.
- Events are durable from day one (outbox plus Redis Streams, see below), with at-least-once delivery, so moving to Kafka or Redpanda later changes no module code.
- No cycles: users depends on identity's `api`, so identity may never depend on users' `api`. When a module lower down needs an answer from one above it, it declares the question as an interface in its own `api`, the upper module implements it, and `app` wires the two together. Example: identity's `DeletionGuard` asks "may this account be deleted?" and the wallet answers "not while it holds funds", without identity depending on the wallet.

### Durable events: outbox plus Redis Streams

An event must never be lost between the change that caused it and the module that reacts to it, so it is stored with the change before it is delivered. Each module publishes to one stream of its own, and each subscribing module reads it through its own consumer group.

| Stream | Producer | Consumer groups (today) |
| --- | --- | --- |
| `events:identity` | identity | `users`, `gateway` |
| `events:users` | users | `gateway` (later `messaging`, `calls`) |
| `events:dlq` | the consumer runtime | alerting, manual replay |

**Delivery, step by step**

1. **Write with the change.** The use case writes its rows and an outbox row (in its own keyspace) in one Kandra batch, so both land or neither does. A uniqueness claim (`saveIfNotExists`) happens before the batch, since an LWT can't join one.
2. **Publish at once.** Right after the batch succeeds, the module `XADD`s the event to its stream: the normal path, about a millisecond.
3. **Rescue after a crash.** After the `XADD` succeeds, the module stamps `publishedAt` on its outbox row. A relay in infrastructure, run by one node at a time under the scheduler's Redis lock, sweeps the outbox minute-buckets from its last checkpoint up to 10 seconds ago, publishes every row with no `publishedAt`, then moves the checkpoint forward. A rescued event may be a duplicate; handlers already skip those.
4. **Consume.** Consumers read with `XREADGROUP` (blocking), run the handler, and `XACK` only after it succeeds.
5. **Retry, then dead-letter.** Messages unacknowledged for 30 seconds are reclaimed with `XAUTOCLAIM` and retried; after 5 attempts they move to `events:dlq` and raise an alert.
6. **Idempotent handlers.** Each group records handled `eventId`s in `evt:done:{group}:{eventId}` (7-day expiry) and skips repeats. The `evt:` prefix belongs to infrastructure's event runtime, not to any module.
7. **Retention.** Streams are trimmed to 7 days. Redis runs with append-only persistence (`appendfsync everysec`) and backups, reachable only from the app's private network.

**Order is not guaranteed.** A retried or rescued event can arrive after a newer one, so a handler compares the version or timestamp in the event with what it already holds and ignores anything older. For example, messaging drops a `ProfileUpdated` whose profile version is lower than the copy it stores.

**Envelope**

```json
{
  "eventId": "0193f2c1-7a4e-7c21-9b1e-5f3d2a8c4e61",
  "type": "identity.AccountActivated",
  "version": 1,
  "occurredAt": "2026-10-21T18:07:12Z",
  "producer": "identity",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "data": { }
}
```

`traceparent` carries the OpenTelemetry trace context, so a consumer's handler continues the trace of the request that caused the event. Fields are only ever added within a version; a breaking change publishes `version: 2` alongside version 1 until every consumer has moved. Tokens, device keys, provider subjects and emails never go on a stream. Each module's tab lists the events it publishes and consumes.

When traffic outgrows Redis Streams, the relay and consumers point at Kafka or Redpanda instead; publishers and handlers stay the same.

Direct calls through a module's `api` remain the way to **ask** another module something (`canReach`, `resolvePhones`). Events are the way to **tell** other modules something happened.

## Gateway

The gateway is the only door into the backend: every request is authenticated, limited, traced and routed here, and no business logic lives here.

**What it does, in request order**

1. **Request id and tracing.** Assigns a request id, starts an OpenTelemetry span, structured JSON logs with user id and route (never tokens, phone numbers or amounts in plain text).
2. **Version and client check.** Routes live under `/v1`. The `X-Client-Version` header is compared to the minimum supported version; too old returns `426` and the app shows Force update.
3. **Authentication.** Verifies the access token locally with identity's public key (no database call), using `identity.api.TokenVerifier`. The same contract also checks identity's deny-list of revoked sessions, so the gateway never reads identity's Redis keys itself.
4. **Scope check.** Each route declares the scope it needs. Two hard rules sit above that: an `agent` token is refused on anything outside `/v1/agent/` and on any method other than `GET`, and `/v1/agent/` accepts nothing but an `agent` token. A route mistake can't hand the assistant a write.
5. **Rate limiting.** Token buckets in Redis, per IP and per user, with tighter buckets on sensitive routes.
6. **Idempotency.** Routes that change state require an `Idempotency-Key` header. The gateway stores the first response in Redis for 24 hours and replays it for a repeated key, so a retried request never runs twice. Keys are scoped to the user and route. While the first request is still running, an in-progress marker holds the key, and a repeat gets 409 request.in_progress instead of running in parallel.
7. **Routing.** Hands the call to the module's routes, which each module registers through its `di module`.
8. **Error mapping.** Turns `DomainError` into HTTP status and one JSON error shape (see Conventions).

**Token scopes**

| Scope | Issued when | Can reach |
| --- | --- | --- |
| `onboarding` | Google/Apple sign-in done, phone or device not yet verified | Sign-up routes and the support-recovery request only |
| `app` | Phone verified and device registered | Everything for that user |
| `agent` | Together with every `app` token (sign-in, device registration, every refresh), for the in-app assistant; same session, no refresh token of its own | Only `GET` routes under `/v1/agent/`, for that user's own data |
| `ops` | Internal admin tools, never issued to the app | Support and moderation routes |

**Rate limits to start with** (tune with real traffic)

| Route group | Limit |
| --- | --- |
| SMS code requests | 3 per number per 10 min, 10 per device per day |
| Code attempts | 5 per account, then locked for 10 minutes |
| Contact sync | 5 per user per hour |
| Handle lookup | 30 per user per hour |
| Agent token (all /v1/agent/ routes) | 60 per user per minute, its own bucket, so the assistant can't use up the user's limit |
| Nonce and social sign-in (I1, I2), before any token exists | 20 per IP per minute |
| Support recovery requests (I15) | 3 per number per day, 5 per IP per day |
| Phone release (I16) | 1 per number per 7 days |
| Everything else | 120 per user per minute |

The gateway also hosts the WebSocket hub (see Realtime and push), because the socket is an edge concern: it authenticates the same way and only delivers what modules hand it.

**Webhooks.** Vendor callbacks enter at `/v1/hooks/{vendor}` and belong to the gateway. They skip the user-token check but pass their own guards: the vendor's signature (or a shared secret where the vendor offers nothing better), a timestamp window against replays, a Redis record of event ids so a repeated delivery is processed once, and an IP allowlist where the vendor publishes one. The gateway answers `200` quickly (except a vendor that sets `answersAfterTranslation`, today Circle: it answers after translating, so a failure returns non-200 and the vendor retries), then hands the verified body to that vendor's module in integrations, which translates it into a Circaso event (`SmsDelivered`, `CustodyTransferSettled`).

## API documentation

The OpenAPI spec is generated from the routing code at build time by Ktor's own OpenAPI support, so it can't drift from the code. Each module documents its own routes, Swagger UI serves the spec outside production, and CI blocks breaking changes to `/v1`.

**Setup**

```kotlin
// app/build.gradle.kts — Ktor Gradle plugin, Kotlin 2.2.20 or newer
ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true   // reads call.receive<T>() and call.respond<T>()
    }
}

// gateway: serve it only where config allows (dev and staging)
if (config.apiDocs.enabled) {
    routing {
        swaggerUI("/docs") {
            info = OpenApiInfo("Circaso API", "v1")
            source = OpenApiDocSource.Routing(contentType = ContentType.Application.Json)
        }
    }
}
```

**What each route adds by hand**

- A KDoc comment above the route with Ktor's comment keywords (`Description:`, `Response:`, …), which needs no experimental opt-in; or `.describe { }` where a route needs more.
- **The response type as `DefaultResponse<T>`.** Inference only sees a direct `call.respond<T>()`, and our routes answer through `respondDefault`, so the envelope is declared explicitly. It is also the truthful shape: the app always receives the envelope.
- **The error codes the route can return** (`users.version_conflict`, …) from the spec doc's catalog, so the app team sees every failure next to the route.
- A tag per module (`identity`, `users`), so Swagger UI groups routes the way the code does.
- `@JsonSchema.Description` on request and response DTOs where a field needs explaining.

**In CI**

1. Build and export the spec to `api/openapi-v1.json`, committed with the code.
2. Compare it with the last released spec using oasdiff; a removed field, renamed route or narrowed type fails the build. That enforces the "additive changes only within `/v1`" rule automatically.
3. Attach the spec to every release.

**Later:** generate the API client for the KMP app from the same spec, so the app's request and response types come from the backend and can't fall out of step.

**Exposure:** `/docs` and the raw spec run only in development and staging, behind the `ops` scope. In production the spec is a build artifact, never a public URL, because it maps every route, support and ops routes included.

## Infrastructure

Infrastructure installs the libraries, opens the connections, manages their lifecycle and hands each module a handle scoped to that module; it holds no business logic and knows no module's tables.

| Provides | Library / service | Handle a module receives | Scoped by |
| --- | --- | --- | --- |
| Database | ScyllaDB Cloud with Kandra 0.5 (`kandra-ktor`, `kandra-koin`) | Kandra repositories for the module's own entities | The module's own keyspace, declared by the module (`circaso_identity`, `circaso_users`) |
| Schema migrations | `kandra-migrate`, one runner per keyspace, before the app starts serving | Module ships migration objects in `data/migrations` | Own history per keyspace, versioned and checksummed |
| Cache, counters, presence, locks | Redis (Valkey compatible), append-only persistence | `RedisScope` | Key prefix per module (`idn:`, `usr:`) |
| Events | Outbox per keyspace plus Redis Streams, with a relay and consumer groups | `EventBus` (from `shared`) | A stream per publishing module, a consumer group per subscriber |
| Scheduled jobs | A small scheduler in the app (single-leader via a Redis lock) | `Scheduler` | Jobs named per module (pending-account sweep, stuck-transaction checks, audits) |
| Files | Cloudflare R2 (S3 compatible) | `ObjectStore` | A bucket prefix per module; uploads by pre-signed URL |
| Outbound HTTP | Ktor client with timeouts, retries, circuit breaker, tracing | `HttpClients.named("africastalking")` | A named client per vendor, used by integrations |
| Configuration and secrets | Typed config, environment secrets now, a key store later | `Config`, `Secrets` | Own config section per module and vendor |
| Observability | OpenTelemetry, into Prometheus, Tempo, Loki and Grafana | automatic | — |
| Health | Liveness and readiness checks for every connection, including Kandra's `/kandra/health` | automatic | — |

**Kandra is the only way modules touch ScyllaDB.** [Kandra](https://kandraorm.com/) (`com.kandraorm.kandra`, 0.5.1) is Circaso's own Kotlin ORM for ScyllaDB, built for this backend. Since 0.5, one `install(Kandra)` serves several keyspaces over one connection pool, which is the one-keyspace-per-module rule in code: infrastructure installs Kandra once, each module declares its keyspace and entities, and every entity names its keyspace in its own annotation. The patterns every module uses:

| Need | Kandra feature |
| --- | --- |
| Put a table in the module's keyspace | `@ScyllaTable(name, keyspace = "circaso_identity")` |
| A second way to look a row up by a one-to-one value nobody races for (support case by request id) | `@LookupIndex` — the lookup table is written in the same LOGGED BATCH as the main row |
| A value that must be unique (one account per number, one owner per handle) | `saveIfNotExists` (lightweight transaction) on a claim table first, then the main write; the claim table doubles as that value's lookup. An LWT can't join a batch, so the claim is always its own call |
| Time-ordered ids | `@GeneratedUuid` (UUIDv7) |
| Time-ordered rows (security log, contacts) | `@ClusteringKey` with order |
| Safe concurrent edits (profile updates) | `@Version` with `update(old, new)` |
| Delete with a recovery window (deleted accounts, released handles) | `@SoftDelete` with a TTL |
| Phone numbers, token hashes and keys kept out of logs | `@Sensitive` |
| Several tables changed together | `saveAll` / batch |
| Unit tests without a database | `kandra-test` `FakeKandraSession`; Testcontainers for data-layer tests |
| Catch bad models and query shapes early | Kandra's validation at startup: a broken entity stops the app from booting |

Kandra's query DSL cannot express `ALLOW FILTERING`, which enforces principle 7 (tables designed around queries) in code.

**How it's wired.** Each module's `di module` contributes a `KandraModuleSpec(keyspace, entities, migrations)`; `app` collects them and infrastructure installs Kandra once. No default keyspace is set, so Kandra refuses to start if any entity forgets to name one.

```kotlin
// infrastructure: one install, keyspaces contributed by the modules
install(Kandra) {
    contactPoints = config.scylla.contactPoints
    localDatacenter = config.scylla.datacenter
    schemaMode = if (config.dev) SchemaMode.AUTO_CREATE else SchemaMode.VALIDATE
    specs.forEach { spec ->
        keyspace(spec.keyspace) { autoCreate = config.dev }
        register(*spec.entities.toTypedArray())
    }
}
kandraKoin()

// identity/impl/data: the entity names its own keyspace
@ScyllaTable(name = "accounts_by_id", keyspace = "circaso_identity")
data class AccountRow(
    @PartitionKey val userId: UUID,
    @Sensitive val phone: String,
    val status: String,
    @Version val version: Long = 0,
)
```

**Migrations.** `kandra-migrate` runs as a one-shot deploy step, not inside `install(Kandra)`, with one `KandraMigrationRunner(session, keyspace = "circaso_identity")` per module. Each keyspace keeps its own migration history, so identity and users number their migrations independently. Migrations are Kotlin `object`s in the module's `data/migrations`; an applied migration is never edited (its checksum would fail the boot), its CQL names tables with their keyspace and uses `IF NOT EXISTS`. Development lets Kandra create tables from entities (`AUTO_CREATE`); every other environment only validates (`VALIDATE`) and leaves changes to migrations.

**Who runs migrations.** Each deploy runs the migrations once, as a one-shot job before the new containers start (the same image started with the `Migrate` main class instead of `MainKt`). The containers never migrate at boot, so two instances can't race on the same keyspace; they only validate the schema. A failed migration stops the deploy and the old containers keep serving.

**ScyllaDB Cloud.** The database is the existing ScyllaDB Cloud cluster, so the Kandra install differs from local development in four ways:

- **Connection:** TLS on (`ssl { enabled = true }`), credentials from `Secrets` (`auth { provider = KandraAuth.fromEnv() }`), `localDatacenter` set to the cluster's datacenter name, and the app's address allowlisted (or VPC-peered) in ScyllaDB Cloud.
- **Keyspaces created by the deploy's migration step**, one per module: `migrate` runs `CREATE KEYSPACE IF NOT EXISTS` for each module's keyspace before its migrations, in every environment, and never alters a keyspace that exists. The replication is configured (`SCYLLA_KEYSPACE_REPLICATION_CLASS`, `SCYLLA_KEYSPACE_REPLICATION_FACTOR`, `SCYLLA_DATACENTER`, `SCYLLA_KEYSPACE_TABLETS`): `NetworkTopologyStrategy` in `SCYLLA_DATACENTER` for any cluster other than a node on this machine (which uses `SimpleStrategy`), because it names the datacenter, is correct on a multi-node cluster and also works with tablets on; tablets off, like the existing sandbox keyspaces. The app's start creates keyspaces (`autoCreate`) only for local development.
- **Schema mode:** `VALIDATE` against the cloud cluster; changes go through `kandra-migrate`.
- **A separate cluster per environment.** Staging gets its own ScyllaDB Cloud cluster (or a self-run ScyllaDB) with the same keyspace names as production. Keyspace names are written into each entity's annotation, so they can't change per environment; a separate cluster keeps staging data away from production with no code differences.

**Two guardrails Kandra doesn't give us for free:**

- Kandra binds repositories into Koin by the entity's simple class name across the whole app, so **entity names must be unique across modules**. Entities end in `Row` (`AccountRow`, `ProfileRow`), and two modules that need a similar name prefix it (`IdentityDeviceRow`).
- Those Koin bindings are global, so nothing in Koin stops users from resolving identity's repository. A Konsist rule enforces it instead: a Kandra repository is only resolved inside the `data` package of the module that owns the entity. The isolation is in code, not in database permissions, since all modules share one session.

**The shared HTTP client.** Infrastructure owns one Ktor client factory. Each vendor gets a named client with its base URL, timeouts (5 s to connect, 15 s per request; a vendor client with `webSockets = true` never retries an upgrade, the circuit breaker neither counts nor blocks upgrades, and only the handshake is bounded (`handshakeTimeout` 10 s, `pingInterval` 20 s); per-vendor overrides: Workers AI 60 s, and its chat completions are not retried, so one Asha turn waits at most about a minute), retries, a circuit breaker, tracing and logging with phone numbers, tokens and amounts hidden. The retry policy is careful, because retrying the wrong call sends money or an OTP twice:

| Situation | Retried? |
| --- | --- |
| Connection failed before the request reached the vendor | Yes |
| `429` or `503` from the vendor | Yes, after its `Retry-After` |
| `GET`, `PUT`, `DELETE` that time out | Yes |
| `POST` that carries an idempotency key (Circle transfers) | Yes, with the same key |
| `POST` without one (send this SMS, send this email) that times out | No: the caller decides, so a slow network never sends the same code twice |

Backoff is exponential with jitter, at most 3 attempts. After 5 failures in a row the circuit opens for 30 seconds for that vendor, so a vendor outage fails fast instead of piling up waiting requests.

**Local development:** `deploy/docker-compose.yml` starts ScyllaDB and Redis; the `log` and `fake` integrations stand in for SMS, email, push and Circle.

**Production at pilot scale:** ScyllaDB on the existing ScyllaDB Cloud cluster; the app as two containers behind Cloudflare; Redis with append-only persistence on a small instance; the Grafana stack on a separate small host. Where the app, Redis and monitoring run is still open (see Decisions).

## Monitoring and observability

Everything is instrumented with OpenTelemetry and lands in a self-hosted Grafana stack: Prometheus for metrics, Tempo for traces, Loki for logs, Grafana for dashboards and alerts. All three signals share one trace id, which is also the `requestId` the app sees in every response, so any support ticket leads to the exact trace and its logs.

**Why this setup:** open source end to end with no per-gigabyte bill, and OpenTelemetry keeps the code vendor-neutral, so moving to a hosted backend later is a Collector config change, not a code change.

| Piece | Role | Notes |
| --- | --- | --- |
| OpenTelemetry Java agent | In the app: traces, metrics, logs | No code changes for the basics |
| OpenTelemetry Collector | Receives OTLP from the app; scrubs sensitive attributes, samples traces, routes signals | The one place privacy filtering and sampling happen |
| Prometheus | Metrics storage and queries | Scrapes the app, ScyllaDB's built-in metrics endpoint, the Redis exporter, the Collector and the hosts |
| Tempo | Trace storage | OTLP from the Collector; trace blocks stored in Cloudflare R2 |
| Loki | Log storage | OTLP from the Collector (native ingest, no Promtail); chunks stored in R2 |
| Grafana | Dashboards, exploring, alerting | Metrics jump to traces (exemplars), traces to their logs, logs back to traces, all by trace id |
| Blackbox exporter + external uptime check | Is the API reachable from outside | See the Uptime row below |

### Setup

```bash
# app container — OpenTelemetry Java agent
JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=circaso-backend
OTEL_RESOURCE_ATTRIBUTES=deployment.environment=pilot,service.version=${APP_VERSION}
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_TRACES_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
OTEL_METRICS_EXPORTER=prometheus      # exposes :9464/metrics for Prometheus to scrape
OTEL_EXPORTER_PROMETHEUS_PORT=9464
```

```yaml
# otel-collector.yaml
receivers:
  otlp:
    protocols: { http: {}, grpc: {} }
processors:
  batch: {}
  attributes/scrub:
    actions:
      - { key: http.request.header.authorization, action: delete }
      - { key: enduser.phone, action: delete }
  tail_sampling:
    policies:
      - { name: errors, type: status_code, status_code: { status_codes: [ERROR] } }
      - { name: slow, type: latency, latency: { threshold_ms: 500 } }
      - { name: rest, type: probabilistic, probabilistic: { sampling_percentage: 20 } }
exporters:
  otlp/tempo: { endpoint: "tempo:4317", tls: { insecure: true } }
  otlphttp/loki: { endpoint: "http://loki:3100/otlp" }
service:
  pipelines:
    traces: { receivers: [otlp], processors: [attributes/scrub, tail_sampling, batch], exporters: [otlp/tempo] }
    logs:   { receivers: [otlp], processors: [attributes/scrub, batch], exporters: [otlphttp/loki] }
```

Every error and every request slower than 500 ms keeps its full trace; 20% of the rest are sampled. During the pilot, set the last policy to 100% and lower it when traffic grows.

**Where it runs:** the Collector sits next to the app. Prometheus, Tempo, Loki and Grafana run on a separate small host with Docker Compose, so the monitoring survives when the app's host doesn't.

Infrastructure owns the OpenTelemetry setup and gives modules a `Telemetry` handle for business metrics and manual spans. The domain stays library-free: outcomes are recorded around use cases in `presentation`, and where a use case needs its own counter, it declares a small port that `di` binds to `Telemetry`.

### What is instrumented

| Signal | How | Covers |
| --- | --- | --- |
| Traces | Java agent, automatic → Collector → Tempo | Ktor server, Ktor client (every vendor call), ScyllaDB driver, Redis client |
| Traces | Manual spans | Each use case, each stream handler, each outbox relay sweep |
| Traces across events | `traceparent` field in the event envelope | A sign-up trace runs from identity's route through the stream into users' handler |
| Metrics | Agent, automatic → Prometheus scrape | HTTP rate, errors and duration per route; JVM memory, GC, threads |
| Metrics | `Telemetry` meters and Kandra's metrics recorder → Prometheus scrape | Business, events, vendors, per-table database timings (catalog below) |
| Metrics | Exporters | ScyllaDB (built-in endpoint), Redis (redis_exporter), hosts (node_exporter) |
| Logs | Logback JSON with trace and span ids → agent → Collector → Loki | Every log line links to its trace in Grafana |
| Health | `/health/live`, `/health/ready` (ScyllaDB, Redis, stream consumers) | Restarts and load balancer checks |
| Uptime | Blackbox exporter probing the public `/health/ready` every minute from outside the app's network (the homelab works), plus a free external uptime service | The external service catches the case where the app and the monitoring go down together |

### Metrics catalog

| Metric (Prometheus name) | Type | Labels | Answers |
| --- | --- | --- | --- |
| `circaso_auth_signins_total` | counter | provider, next | How many sign-ins, and where they land |
| `circaso_otp_sent_total` / `circaso_otp_verified_total` | counter | channel (sms, carrier), result | Is SMS delivery working; carrier vs SMS share |
| `circaso_sms_cost_kes_total` | counter | provider | What verification costs per day |
| `circaso_integration_duration_seconds` | histogram | vendor, operation, outcome | Vendor latency and errors |
| `circaso_integration_circuit_open` | gauge | vendor | Is a vendor being cut off |
| `circaso_events_published_total` | counter | stream, type | Event volume |
| `circaso_events_handled_duration_seconds` | histogram | group, type, outcome | Handler time and failures |
| `circaso_events_lag` | gauge | group | Pending and unread messages per consumer group |
| `circaso_gateway_socket_frames_dropped_total` | counter | reason | Phone frames dropped by the gateway (`rate_limited`) |
| `circaso_events_dlq` | gauge | stream | Events that gave up |
| `circaso_outbox_unpublished` | gauge | module | Events the relay had to rescue |
| `kandra_query_duration_seconds` | histogram | table, op | Database latency per table |
| `circaso_contacts_sync_matches_ratio` | histogram | — | Share of a phone book already on Circaso |
| `http_server_request_duration_seconds` | histogram (agent) | route, method, status | Rate, errors and latency per route |

Attributes never include a user id, phone number or handle: they would leak personal data and explode the number of series. User ids belong on traces and logs only.

### Dashboards

1. **Service health:** request rate, error rate and p95 latency per module and route.
2. **Sign-up funnel:** sign-in → code sent → verified → device registered → profile created, with drop-off between each step.
3. **Events:** publish rate, consumer lag, handler failures, dead letters, outbox rescues.
4. **Vendors:** latency, errors and circuit state per vendor; SMS spend per day.
5. **Data stores:** ScyllaDB latency per table, lightweight-transaction contention, Redis memory and connections.

### Alerts

| Alert | Fires when | Severity |
| --- | --- | --- |
| API errors | 5xx above 2% for 5 min | Page |
| Outside check failing | `/health/ready` fails twice in a row | Page |
| SMS broken | Code verification success below 60% over 30 min, or the SMS circuit open over 2 min | Page |
| ScyllaDB node down | Any node unreachable | Page |
| Slow API | p95 above 500 ms for 10 min | Warn |
| Consumers falling behind | Lag above 1,000 or oldest pending over 2 min | Warn |
| Dead letters | Any event in `events:dlq` | Warn |
| Outbox rescues | Unpublished events for over 5 min | Warn |
| Database slow | ScyllaDB p99 above 50 ms for 10 min | Warn |
| Redis memory | Above 80% | Warn |
| SMS budget | Daily spend above the set budget | Warn |
| Silence | No sign-ins for 2 h between 07:00 and 22:00 EAT | Warn |

Alerts are Grafana alert rules over Prometheus and Loki. Pages go to Telegram (a bot) and email; warnings to email only. Thresholds are starting points, tuned after the first weeks of real traffic. A monitoring stack can't report its own death, which is why the external uptime check also watches Grafana.

### Privacy rules for telemetry

- No phone numbers, tokens, PINs, emails or amounts in span attributes, metric attributes or logs; Kandra's `@Sensitive` masks entity fields in its logs.
- The agent drops the `Authorization` header and database statement parameters.
- A last-line log filter masks anything shaped like an E.164 number or a JWT, in case one slips through.
- Retention: metrics 30 days in Prometheus, logs 30 days in Loki, traces 14 days in Tempo; Loki and Tempo keep their data in R2.

## Logging

Every request writes a line when it leaves (and, at DEBUG, when it arrives), every call to another service writes a line of its own, and every line carries the request id. In a terminal one request reads as a block; in Grafana the same id opens its trace. One set of log events feeds two formats: colored console lines for local development, JSON for staging and production.

> **Diagram:** console log sample · one request, its service calls, an event and two failures (see the diagram in the Claude Docs version of this document)

The request id is the OpenTelemetry trace id, so the lines of an event handler (`5d3e81c0` above) share the id of the request that caused the event, even in another module.

### What a line carries

| Field | Console | JSON key | Comes from |
| --- | --- | --- | --- |
| Time | `18:07:12.347`, local time, dim | `@timestamp`, ISO-8601 UTC with milliseconds | Logback |
| Level | Colored, padded to 5 | `level` | Logback |
| Request id | First 8 characters of the trace id, dim | `requestId`, `traceId`, `spanId` | OpenTelemetry agent; returned to the app as `requestId` |
| Module | Bold | `module` | Logger name `com.circaso.<module>` |
| Kind | Arrow and service tag (`→ in`, `← out`, `⇄ scylla`, `⇄ redis`, `⇄ http`, `⇢ stream`, `⇠ stream`, `⇄ ws`, `⏲ job`) | `kind`: `request`, `response`, `db`, `redis`, `http`, `stream.publish`, `stream.consume`, `ws`, `job`, `webhook` | The logging helper that wrote it |
| Method, route | Colored method, then the path | `http.method`, `http.route` (template, `/v1/users/{userId}`), `url.path` | Ktor |
| Status, duration | Colored by status class and by speed | `http.status`, `durationMs` | Gateway, HTTP client, Kandra, Redis wrapper |
| Client IP, country | `102.68.143.7 KE` | `client.ip`, `client.country` | `CF-Connecting-IP`, `CF-IPCountry` from Cloudflare; the origin accepts traffic only from Cloudflare, so the header can be trusted |
| Client app | `android/1.4.0` on the request line | `client.platform`, `client.version`, `client.model`, `client.os` | `X-Client-Version`, `User-Agent` |
| Who | `user=0193…c50 dev=0193…a1` | `userId`, `deviceId`, `scope` (full ids) | The verified access token |
| Error | The error code, in the level's color | `error.code`, `error.kind`; `error.stack` only for 5xx | `DomainError`, StatusPages |
| Metadata | `key=value` pairs after the message | One JSON field each | Structured arguments: `vendor`, `attempt`, `table`, `stream`, `eventType`, `eventId`, `rateLimit.remaining`, `idempotency.replayed`, `circuit` |

### Colors

Colors exist only on a terminal. The console format switches them off when output isn't a TTY, and JSON never has them, so no ANSI codes reach Loki; in Grafana the `level` field colors each line instead.

| Element | Color |
| --- | --- |
| Time, request id, metadata, durations under 300 ms | Dim gray |
| Levels | TRACE gray · DEBUG cyan · INFO green · WARN yellow · ERROR bold red |
| Module name | Bold white |
| HTTP methods | GET blue · POST green · PUT and PATCH yellow · DELETE red · HEAD and OPTIONS gray |
| Status codes | 2xx green · 3xx cyan · 4xx yellow · 5xx bold red |
| Durations | Under 300 ms dim · 300 ms to 1 s yellow · over 1 s red |
| Service tags | scylla magenta · redis red · http cyan · stream blue · ws green · job yellow · webhook magenta |
| Error codes and circuit state | Same color as the line's level |

### Service interactions

Every boundary the backend crosses writes its own line, from the one place that owns that boundary, so modules never write these lines by hand.

| Interaction | Written by | Level | The line carries |
| --- | --- | --- | --- |
| Request in and out | Gateway `RequestLog` plugin | Start: DEBUG. End: INFO; WARN for 401, 403 and 429; ERROR for 5xx | Method, route, status, duration, client IP and country, app version, user, device, scope, error code, idempotency replay |
| Vendor call | Infrastructure HTTP client plugin, and the wrapper around each SDK | INFO; WARN on a retry; ERROR when the circuit opens or the last attempt fails | Vendor, method, path without the query string, status, duration, attempt, circuit state; never a body |
| ScyllaDB | Kandra's query hook | DEBUG; WARN over 50 ms | Keyspace, table, operation, whether an LWT applied, rows, duration; never values (`@Sensitive`) |
| Redis | `RedisScope` | DEBUG; WARN over 20 ms | Command, key with ids shortened, duration |
| Event published | `EventBus` | INFO | Stream, type, event id |
| Event handled | Stream consumer runtime | INFO; WARN on a retry; ERROR when dead-lettered | Consumer group, type, event id, attempt, duration |
| WebSocket | Gateway hub | INFO on connect and disconnect; DEBUG per frame | User, device, `module.type`, sockets reached, pushes sent |
| Webhook | Gateway | INFO; WARN on a bad signature or a replay | Vendor, vendor event id, verified, duplicate |
| Scheduled job | `Scheduler` | INFO at start and end; ERROR on failure | Job, items processed, duration |
| Startup and deploy | `app` | INFO | Version, environment, vendor implementation chosen per contract, migrations applied |

### Levels per environment

| Environment | Format | Level | Bodies |
| --- | --- | --- | --- |
| Local | Colored console | DEBUG | Request and response bodies at DEBUG, redacted |
| Staging | JSON | INFO; DEBUG switchable per logger | Never by default |
| Production | JSON | INFO | Never |

A level can be raised for one logger for a limited time without a redeploy: `PUT /ops/log-level` with `{ "logger": "com.circaso.identity", "level": "DEBUG", "minutes": 15 }`, `ops` scope only, reverting on its own. `/health/*`, `/metrics` and CORS preflights are not logged; metrics count them.

### Privacy

- Never in a log line: access and refresh tokens, OTP codes, PINs, device keys, amounts, email addresses, raw phone numbers.
- Bodies are logged only locally, through a redactor that masks `code`, `pin`, `idToken`, `refreshToken`, `publicKey`, `phone`, `email` and `contactEmail`.
- The last-line filter masks anything shaped like an E.164 number (`+254 7•• ••• 678`) or a JWT, in case one slips through.
- Client IP addresses are personal data. They stay in log lines (needed for abuse and rate-limit investigations) with the same 30-day retention as every log, never become metric labels, and are readable only through Grafana. The Kenya Data Protection question on the Decisions tab covers them.
- Ids are shortened only on the console; JSON keeps them whole so they can be searched.

### Setup

```xml
<!-- infrastructure/impl/src/main/resources/logback.xml -->
<configuration>
  <conversionRule conversionWord="lvl" converterClass="com.circaso.infrastructure.logging.LevelColor"/>
  <conversionRule conversionWord="rid" converterClass="com.circaso.infrastructure.logging.ShortRequestId"/>
  <conversionRule conversionWord="mod" converterClass="com.circaso.infrastructure.logging.ModuleName"/>
  <conversionRule conversionWord="line" converterClass="com.circaso.infrastructure.logging.ColoredLine"/>

  <!-- local: colored, human-readable; colors drop automatically when stdout isn't a terminal -->
  <appender name="PRETTY" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%gray(%d{HH:mm:ss.SSS}) %gray(%rid) %lvl %mod %line%n%ex{short}</pattern></encoder>
  </appender>

  <!-- staging and production: one JSON object per line, MDC and structured arguments as fields -->
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <fieldNames><timestamp>@timestamp</timestamp><level>level</level></fieldNames>
      <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter"/>
    </encoder>
  </appender>

  <root level="${LOG_LEVEL:-INFO}">
    <appender-ref ref="${LOG_FORMAT:-JSON}"/>
  </root>
</configuration>
```

`ColoredLine` reads the structured arguments (`kind`, `http.method`, `http.status`, `durationMs`, …) and colors each part by the table above, so the colors come from the same fields the JSON carries.

```kotlin
// gateway: one plugin writes the request lines and fills the MDC every later line inherits
val RequestLog = createApplicationPlugin("RequestLog") {
    onCall { call ->
        MDC.put("requestId", Span.current().spanContext.traceId)
        MDC.put("client.ip", call.request.header("CF-Connecting-IP"))
        MDC.put("client.country", call.request.header("CF-IPCountry"))
        MDC.put("client.version", call.request.header("X-Client-Version"))
        MDC.put("deviceId", call.request.header("X-Device-Id"))
        log.debug("in", kv("kind", "request"), kv("http.method", call.request.httpMethod.value), kv("url.path", call.request.path()))
    }
    on(ResponseSent) { call ->
        val status = call.response.status()?.value ?: 0
        val line = arrayOf(kv("kind", "response"), kv("http.method", call.request.httpMethod.value),
            kv("http.route", call.routeTemplate()), kv("http.status", status), kv("durationMs", call.durationMs()),
            kv("userId", call.principalOrNull()?.userId), kv("error.code", call.errorCode()))
        when {
            status >= 500 -> log.error("out", *line)
            status in setOf(401, 403, 429) -> log.warn("out", *line)
            else -> log.info("out", *line)
        }
    }
}
```

With Ktor's coroutine MDC support (`MDCContext`), the values set on the call follow it into every module, so a ScyllaDB or vendor line written deep inside identity still carries the request id, client and user.

**Into Loki:** the OpenTelemetry agent ships the same events over OTLP with every MDC value and structured argument as an attribute (`OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=*`, `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true`). The Collector's scrub step still runs before Loki. Only `level`, `module`, `kind` and `deployment.environment` become Loki labels; ids, IPs and routes stay as fields, so the label count stays small.

## Integrations

Every third-party service sits behind a contract in `integrations:api`, named for what Circaso needs (send an SMS) rather than for a vendor. Each vendor is its own implementation, built on the vendor's SDK or its REST API, and configuration picks which one runs.

```kotlin
interface SmsGateway {
    suspend fun send(to: PhoneNumber, text: String, purpose: SmsPurpose): Resource<SmsReceipt>
}
enum class SmsPurpose { VERIFICATION, SECURITY_ALERT, NOTIFICATION }
data class SmsReceipt(val messageId: String, val provider: String)

// all inside integrations/impl, one package per vendor:
// sms/africastalking   class AfricasTalkingSms(http, secrets) : SmsGateway
// sms/twilio           class TwilioSms(twilioSdk)             : SmsGateway   (later)
// dev                  class LogSms : SmsGateway                // prints the code locally
```

**Contracts**

| Contract | Does | Used by | Implementation now | Can swap to |
| --- | --- | --- | --- | --- |
| `SmsGateway` | Send a text message | identity | Africa's Talking (REST) | Twilio, Infobip |
| `EmailGateway` | Send a templated email | identity, later others | Resend (REST) | Amazon SES, Postmark |
| `SocialSignIn` | Verify a Google or Apple ID token; revoke Apple tokens on account deletion | identity | Google and Apple public keys and REST | — |
| `CarrierPhoneVerification` | Turn a carrier token into a verified number | identity | Firebase PNV (Admin SDK) | — |
| `PushGateway` | Push a notification to a device | gateway only (when a user has no open socket) | FCM (Firebase Admin SDK), APNs (HTTP/2) | — |
| `StablecoinCustody` | Create wallets, move funds, read balances | wallet (later) | Circle (REST) | — |
| `CallMedia` | Create call sessions and join tokens | calls (later) | Cloudflare Realtime (REST) | LiveKit |
| Later: `MobileMoney`, `KycProvider` | Cash-out to M-PESA; identity checks | cash-out, compliance | Daraja; a KYC vendor | — |

**Rules**

- **Our language in, our language out.** Contracts take and return Circaso types (`PhoneNumber`, `Money`, `UserId`). Vendor classes and vendor error codes never cross the contract; failures become `ErrorKind.UPSTREAM` or `ErrorKind.INVALID`.
- **One integrations module, one package per vendor,** inside integrations:impl. Only integrations:api is visible to the rest of the backend, so vendor SDKs and classes stay sealed inside, and removing a vendor deletes one package.
- **Chosen in config:** `integrations.sms.provider = africastalking | twilio | log | fake`. Local development uses `log` (codes printed to the console, no SMS bill); tests use `fake` (messages recorded for assertions).
- **REST implementations use the infrastructure HTTP client.** SDK implementations wrap each SDK call in the same timeout and circuit breaker, since the SDK brings its own HTTP stack.
- **Credentials come from `Secrets`,** one section per vendor, never in code or config files.
- **Webhooks are received by the gateway, understood here.** The gateway owns the endpoint and its signature, replay and duplicate checks; the vendor module only translates a verified body into a Circaso event. Business modules react to the event, never to the raw webhook.
- **No business logic.** Integrations never decide what to send or when. Identity generates, stores and counts the OTP and words the message; `SmsGateway` only delivers it.
- **Failover later, same contract.** A routing implementation can wrap two providers (Africa's Talking first, Twilio when it fails or for non-Kenyan numbers) and still be just an `SmsGateway`.

## Realtime and push

Each phone keeps one WebSocket open to the gateway while the app is in the foreground; when it is closed, push takes over, and on reconnect the phone catches up from each module's data.

**Envelope** — every server-to-client frame has the same shape; the app routes on `module`, then `type`:

```json
{
  "module": "users",
  "type":   "contact.joined",
  "id":     "0193f2c1-7a4e-7c21-9b1e-5f3d2a8c4e61",
  "seq":    1042,
  "ts":     "2026-10-21T18:07:12Z",
  "data":   { "userId": "…", "displayName": "Amina" }
}
```

`seq` increases per user per module, so the app can tell what it missed.

**Lifecycle**

1. App comes to the foreground and opens `wss://api.circaso.com/v1/ws`.
2. First frame authenticates with the access token; the gateway checks it like any request.
3. App sends its last `seq` per module as `last: [{"module": "users", "seq": 1042}]` (each module at most once; a malformed `last` closes with `invalid last`); each module returns what was missed from its own tables.
4. Heartbeat every 25 seconds (Cloudflare closes idle sockets after about 100).
5. App goes to the background and closes the socket.

**Client to server** over the socket: only typing, read receipts, presence and acknowledgements. Commands use HTTP.

**How a module sends**: through `Realtime.deliver(userId, envelope)` from `shared`. Each node records the sockets it holds in Redis (`rt:conn:{userId}` → node id, refreshed by the heartbeat) and listens on its own Redis channel (`rt:node:{nodeId}`). The gateway looks up the user's node and publishes the frame on that node's channel, and that node writes it to the socket. If no node holds a socket, the gateway hands the event to `PushGateway` when the event type is marked push-worthy.

**Sending to many users** (a group): `Realtime.deliverMany(userIds, envelope)` publishes the frame once, with the user list, on the broadcast channel `rt:fanout`, which every node subscribes to beside its own channel. Each node writes it to the sockets it holds for those users, from its in-memory connection table, with no Redis read. Only for push-worthy frames does the gateway look up who has no socket (single-key `GET`s of `rt:conn:{userId}` in one pipeline, never a multi-key command, so it works on a Redis cluster) and push them in batches of 100.

**From the phone**: a frame `{"module": "messaging", "type": "ack", "data": {…}}` after the auth frame goes to the module that registered a `RealtimeInbound` for that `module`, with the token's user and device; unknown modules and unreadable frames are dropped and the socket stays open. A socket may send at most 100 frames per second; frames over the limit are dropped the same way and counted. A frame may be at most 64 KB; a larger one closes the socket with 1009. The `rt:` prefix belongs to the gateway.

**Catching up after a gap**: every module that sends frames implements `RealtimeReplay` from `shared`, `suspend fun since(userId: UserId, seq: Long): Resource<List<RealtimeEnvelope>>`. The module takes `seq` from a per-user counter under its own Redis prefix (`usr:seq:{userId}`) and keeps what it sent in its own keyspace for 7 days (`realtime_by_user`). On reconnect the gateway calls each module's replay with the app's last `seq`; if the gap is older than 7 days, the module answers with a `resync` frame and the app reloads that module's data over HTTP.

**Events identity and users send**

| Module | Type | Goes to | Push if offline |
| --- | --- | --- | --- |
| `system` | `session.revoked` | The replaced or removed phone, and the phone that signed out (its socket for that session is then closed) | Yes, except after its own sign-out |
| `system` | `force.update` | All connected phones on old versions | No |
| `users` | `contact.joined` | Everyone who has the new user's number | Yes ("Amina is on Circaso") |
| `users` | `profile.updated` | The user's contacts | No |
| `users` | `connection.added` | Both people, after a QR or handle connection | Yes |

## Modules

Each business module has its own sub-tab under Modules, in the same shape: overview, endpoints, data, events, error codes. Designed so far: Identity and Users. The Modules tab also holds the module map and the checklist for adding a module.

## Sign-up end to end

> **Diagram:** sign-up sequence · 14 steps across app, identity, users. In text:
>
> 1. App → identity: Google/Apple ID token (I1 nonce, I2)
> 2. identity → Google/Apple: verify the token against the provider's keys
> 3. identity → App: onboarding session
> 4. App → identity: phone number (I3)
> 5. identity → Africa's Talking: send the code
> 6. App → identity: the code, auto-read (I4), or a carrier token (I5)
> 7. identity: claim the number; the account is still pending
> 8. App → identity: device public key (I6); the account goes active
> 9. identity → users: `AccountActivated` on `events:identity`
> 10. identity → App: app session and agent token
> 11. users: create the profile, turn waiting contacts into contacts
> 12. users → gateway socket: `contact.joined` to those contacts
> 13. App → users: profile, handle, contact sync (U2, U6, U7)
> 14. users → identity: `resolvePhones` during contact sync

Identity never calls users: it only announces `AccountActivated` (step 9), once the phone has registered its device key, and users does its own work from there. The only direct call between them goes the other way, users asking identity to resolve phone numbers during contact sync (step 14).

## Conventions

These apply to every module from the first line of code, so later modules look like the first two.

| Topic | Convention |
| --- | --- |
| Base URL | `https://api.circaso.com/v1` |
| Ids | UUIDv7 everywhere (time-ordered, sorts well as a clustering key; `@GeneratedUuid` in Kandra), wrapped in typed value classes: `UserId` and `DeviceId` in `shared`; ids a module owns live in its `api` (`SessionId` in identity, `ChatId` in messaging) |
| Money | Integer minor units plus asset (`Money(amountMinor, USDT)`); never floating point |
| Phone numbers | E.164 only, parsed once at the edge into `PhoneNumber`; masked in responses (`+254 7•• ••• 678`); never logged in full |
| Time | ISO-8601 UTC strings in the API (`2026-10-21T18:06:00Z`); epoch milliseconds in storage; the app formats for the user |
| Results | Every use case and module contract returns `Resource<T>`; expected failures are `Resource.Error(DomainError)`; exceptions only for bugs and outages |
| Errors over HTTP | Always the `DefaultResponse` envelope, for example `{"httpStatusCode": 409, "status": false, "message": "…", "code": "identity.phone_taken", "requestId": "…", "data": null}`; an INVALID error lists its fields as `details.fields: [{"field": "displayName", "problem": "required"}]`; codes are `module.reason` and stable, and the app keys its copy on them |
| Auth | `Authorization: Bearer <accessToken>`; each route declares the scope it needs |
| Client headers | `X-Client-Version` (e.g. `android/1.4.0`); `X-Device-Id` on every call after sign-in |
| Idempotency | Every `POST`, `PUT`, `PATCH`, `DELETE` that changes state takes `Idempotency-Key: <uuid>`; a repeat within 24 h returns the first response |
| Pagination | `?cursor=<opaque>&limit=<n>` (default 50, max 200); responses carry `nextCursor`, `null` on the last page |
| API versions | Path prefix `/v1`; additive changes only within a version; breaking changes get `/v2` and the old version stays until old apps are forced to update |
| JSON | kotlinx-serialization only (no Gson), camelCase fields, unknown fields ignored on read |
| Events | Past tense (`AccountActivated`), declared in the publisher's `api`, carry ids and changed facts, versioned by field addition only |
| Keyspaces and entities | Keyspaces `circaso_<module>`; tables `<thing>_by_<key>`; entity classes end in `Row` and are unique across modules |
| Redis keys | `<module prefix>:<purpose>:<id>`, every key with an expiry |
| Packages | `com.circaso.<module>.<api, presentation, domain, data or di>` |
| Logging | JSON in staging and production, colored console locally (see Logging); every line carries the request id; user id yes, phone numbers, tokens, PINs and amounts never |
| Tests | Domain tested with in-memory fakes of its ports; data tested against a real ScyllaDB in a container; one end-to-end test per flow |

**Shared error codes** (module codes are on each module's tab)

| Code | Kind → status | When |
| --- | --- | --- |
| `request.malformed` | INVALID → 400 | Bad JSON or missing field |
| `request.not_found` | NOT_FOUND → 404 | Unknown route |
| `request.client_outdated` | set by the gateway → 426 | `X-Client-Version` below the minimum |
| `request.rate_limited` | RATE_LIMITED → 429 | Any rate limit; `Retry-After` header set |
| `auth.token_missing` / `auth.token_invalid` | UNAUTHORIZED → 401 | No or bad access token |
| `auth.scope_insufficient` | FORBIDDEN → 403 | e.g. an onboarding token on an app route |
| `internal.error` | INTERNAL → 500 | Anything unexpected; logged with the request id |
| request.in_progress | CONFLICT → 409 | A request with the same Idempotency-Key is still running |
