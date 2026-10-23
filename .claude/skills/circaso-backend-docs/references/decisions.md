# Decisions

Every architecture decision, newest first, with the reason it was made. A decision changes only by adding a newer row that replaces it, so the history stays readable. If code needs to break one of these, stop and ask; don't decide it in code.

## Decision log

| Decided | Decision | Why | Where it applies |
| --- | --- | --- | --- |
| 2026-10-22 | Asha appears as a pinned chat and contact with one continuous, stored history per user, which the user can delete in part or whole; she is a system participant, not a user account | Talking to Asha feels like talking to anyone else on Circaso; deletion leaves only text-free audit records of money actions | Assistant → Asha in the chat list |
| 2026-10-22 | Asha can read the user's encrypted chats through device tools: the phone decrypts with the local private key and returns only matching snippets, which are used for the turn and never stored | Asha is useful only if she can answer about conversations; the keys never leave the phone | Assistant → Reading encrypted chats |
| 2026-10-22 | The assistant is called Asha: text and voice (speech-to-text and text-to-speech), a free daily allowance and an Asha Plus subscription for longer use, reachable from her own section and from any chat input. Phase B lets her propose messages and payments to contacts; the user always confirms, and every agent-driven action is audited | Keeps model and voice costs bounded; the agent token stays read-only, so Asha can prepare but never execute | Assistant |
| 2026-10-22 | Every app session carries two access tokens, user (`app`) and agent; the agent token is read-only, limited to `GET /v1/agent/`, shares the session and is re-issued on every refresh. Each module's assistant routes live in `presentation/agent/` | The assistant can read the user's data but can never change it, and it can't outlive the session it came from | Identity → Tokens, I2, I6, I7; Architecture → Gateway, Module anatomy |
| 2026-10-21 | Module layout: presentation (routes, controllers, services, DTOs), domain (models, repository interfaces, single-operation use cases), data (entities, repository implementations), di. Services group use cases by area and hold no logic; every entry point calls services | Clean architecture as Uncle Bob lays it out; a controller gets one object per area instead of a dozen use cases, and HTTP, events and other modules all reach the same code | Architecture → Module anatomy; Modules → Adding a module |
| 2026-10-21 | Logging: colored console locally, JSON in staging and production; one line per request and per service call, all carrying the request id; boundary lines written by infrastructure and the gateway, not by modules | Readable while developing, searchable in Loki, and one request can be followed across modules and vendors | Architecture → Logging |
| 2026-10-21 | The account turns active at device registration (I6), not at phone verification | Every active account has a device and a session | Identity → I6; Architecture → Sign-up end to end |
| 2026-10-21 | Staging gets its own ScyllaDB cluster; keyspace names are the same in every environment | Keyspace names are fixed in each entity's annotation and can't change per environment | Architecture → Infrastructure |
| 2026-10-21 | Migrations run as a one-shot deploy step, never when a container boots | Two containers must not race on the same keyspace | Architecture → Infrastructure |
| 2026-10-21 | A module lower down asks the modules above it through an interface it owns (first case: identity's `DeletionGuard`, answered by the wallet) | Keeps dependencies one-way; identity never depends on the wallet | Architecture → How modules talk; Identity → I14 |
| 2026-10-21 | Events carry a phone number's HMAC, never the number | No phone numbers in Redis or its backups; users already matches waiting contacts by HMAC | Identity → Events; Users → Events |
| 2026-10-21 | Realtime frames cross nodes through a Redis channel per node; each module replays missed frames through `RealtimeReplay` | The app runs as two containers, and a missed frame must be recoverable | Architecture → Realtime and push |
| 2026-10-21 | Error envelope gains details (`retryAfterSeconds`, field errors); idempotency keys hold an in-progress marker | The app needs Retry-After and per-field errors; concurrent repeats must not run twice | Architecture → Results and responses; Gateway |
| 2026-10-21 | ScyllaDB runs on the existing ScyllaDB Cloud cluster | Managed, multi-node and already set up; no self-run database for the pilot | Architecture → Infrastructure |
| 2026-10-21 | One living document: Architecture, a sub-tab per module, Decisions | New modules and features add on top of what exists | This document |
| 2026-10-21 | Device attestation off in development and staging, enforced in production | Debug builds need to run; release builds must come from the stores on genuine phones | Identity → I6 |
| 2026-10-21 | Privacy defaults: phone never shown, avatar to anyone who can reach you, about to contacts, handle lookup on | Discoverable by people you know without exposing the number | Users |
| 2026-10-21 | Users can remove a contact; sync never re-adds them | Phone books keep numbers people no longer want to hear from | Users → U16 |
| 2026-10-21 | Unique values use claim tables; `@LookupIndex` only for uncontested one-to-one values | Kandra lookup tables are keyed by value and don't check uniqueness | Architecture → Kandra |
| 2026-10-21 | Observability: OpenTelemetry into self-hosted Prometheus, Tempo, Loki and Grafana | Open source, no per-gigabyte bill, vendor-neutral code | Architecture → Monitoring |
| 2026-10-21 | OpenAPI generated by Ktor from the routes; Swagger UI outside production; oasdiff blocks breaking changes in CI | The spec can't drift from the code | Architecture → API documentation |
| 2026-10-20 | Account recovery is support-only, for KYC-verified accounts; no recovery key | Fewer moving parts; a lost Circaso account means locked Circle wallets, so recovery needs real identity checks | Identity |
| 2026-10-20 | No Temporal | Wallets are user-controlled: the backend prepares, the user signs, the chain executes, so there is no backend money flow to orchestrate. Stuck transactions and audits are plain scheduled jobs | Architecture |
| 2026-10-20 | Events are durable: outbox in the same batch, then Redis Streams, one stream per module | An in-memory bus loses events on a crash; Redis is already in the stack | Architecture → Events |
| 2026-10-20 | Ask through a module's API, tell through the stream | Questions need answers now; announcements don't | Architecture → How modules talk |
| 2026-10-20 | `Resource<T>` and `DomainError` in `shared`; `DefaultResponse` and StatusPages in `shared-web`; kotlinx-serialization only | One response shape, and no Ktor in the domain | Architecture → Results and responses |
| 2026-10-20 | Clean layers in every module: presentation (DTOs), domain (models), data (rows) | The domain sees only models | Architecture → Module anatomy |
| 2026-10-20 | Kandra 0.5: one install, a keyspace per module, `kandra-migrate` per keyspace | Matches the one-keyspace-per-module rule | Architecture → Infrastructure |
| 2026-10-20 | One integrations module: contracts in `api`, a sub-folder per vendor in `impl`; the Ktor HTTP client lives in infrastructure | Swap vendors without touching business code | Architecture → Integrations |
| 2026-10-20 | Vendor webhooks are received and verified by the gateway | Edge concern; integrations only translate the payload | Architecture → Gateway |
| 2026-10-20 | SMS codes through Africa's Talking; Firebase PNV only for one-tap carrier checks | Far cheaper per SMS in Kenya than Firebase's SMS; local sender ID | Identity |
| 2026-10-20 | The phone number stays required | Contact discovery, the reach rule, one real SIM per account, and M-PESA cash-out later | Identity, Users |
| 2026-10-19 | Identity is built in-house; no auth-as-a-service | The design (devices, one phone, recovery rules) doesn't fit Auth0 or Firebase Auth | Identity |
| 2026-10-19 | Rebrand to Circaso (`circaso.com`); iOS bundle `com.circaso.app`; Android keeps `online.pascarl.coinx` | Package names can't change on a published listing | Everywhere |
| 2026-10-19 | Personal developer accounts now; convert to a Crins Limited organisation account later | Converting keeps the Apple Team ID, so Sign in with Apple ids don't change | Release |
| 2026-10-19 | Modular monolith in Kotlin/Ktor, a keyspace per module, contracts between modules | Clear boundaries now, separate services later without rewrites | Architecture |
| 2026-10-18 | Sign-in with Google or Apple only, then a verified phone; one number per account, duplicates blocked | No passwords; the number is identity in Kenya | Identity |
| 2026-10-18 | One active phone per account; a new phone re-verifies the number | Unambiguous device for money; a stolen Google account alone isn't enough | Identity |
| 2026-10-18 | One WebSocket per device, events grouped by module and type; push when closed | One connection to manage; the socket is a doorbell, data is the mailbox | Architecture → Realtime |
| 2026-10-18 | Calls is its own module | Different lifecycle and vendor from messaging | Modules |
| 2026-10-18 | Keep the existing Play listing and ship the new app as an update | Keeps about 1,000 installs and the listing's history | Release |

## Open questions

Not decided yet. Where code depends on one of these, build behind an interface or config value and leave a `TODO(decision: …)` rather than picking an answer.

- [x] **When the account turns active.** Decided 2026-10-21: at device registration (I6).
- [ ] **What a blocked or unconnected person is told.** Proposed: a handle with lookup turned off answers `users.handle_not_found`, like one that doesn't exist; any action toward someone you can't reach answers `users.not_reachable`, never saying whether it's a block or a missing connection.
- [ ] **Hosting for the app, Redis and monitoring.** The database is on ScyllaDB Cloud; the app containers, Redis and the Grafana stack still need a home. Money in production should not depend on a home connection.
- [ ] **Signing key storage.** Environment secret for the pilot; which key store before real money moves.
- [ ] **Support recovery tooling.** The ops screen, the KYC vendor and the selfie match that recovery relies on.
- [ ] **Unique lookup index in Kandra.** An optional Kandra feature (`@LookupIndex(unique = true)`) that would replace the three claim tables.
- [ ] **CI/CD and environments.** How a build reaches staging and production, who approves a production deploy, and where the one-shot migration step runs.
- [ ] **Backups and disaster recovery.** ScyllaDB Cloud's backup schedule and a tested restore; Redis AOF backups; how long the service may be down and how much data it may lose.
- [ ] **Kenya Data Protection Act.** Registering with the Office of the Data Protection Commissioner as a data controller and processor, a retention schedule per table, and how account deletion reaches every module.
- [ ] **Creating the Circle user.** Whether the wallet module creates the Circle user on `AccountActivated` or on first wallet use, and how the Circle user token reaches the app for PIN setup. The wallet module's first design question.
- [ ] **Payment hold after a support restore.** The sign-in flow keeps a 48-hour hold on outgoing transfers after an account is relinked (ID-23), on top of the 48-hour notice before it. Identity has no status for it yet. Keep it (it would live in the wallet, keyed on `AccountRelinked`) or drop it because the notice already gives the owner time to cancel?
- [x] **The assistant's name.** Decided 2026-10-22: Asha.
- [ ] **Where the assistant runs.** Inside the gateway, or as its own `assistant` module (Koog agent, its prompts, its conversation history) whose tools call the `/v1/agent/` routes with the agent token. The gateway holds no business logic today, which favours its own module.
- [ ] **Asha's free allowance and Asha Plus price.** Daily text turns, voice seconds and session length on the free plan, and the subscription price, from measured cost per turn.
- [ ] **How Asha Plus is paid.** Apple and Google require their in-app billing for digital subscriptions sold in the app; paying from the wallet in USDC may not be allowed there.
- [ ] **Summoning Asha in a chat.** The trigger (a composer button, `/asha`, or both), and whether chat reading is on by default with a per-chat opt-out, or off until the user turns it on.
- [ ] **Limits on Asha's payment proposals.** A per-proposal or daily amount cap for payments that start from Asha, on top of the PIN.
