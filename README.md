# Circaso backend

One Kotlin/Ktor deployable split into strict modules: `identity` and `users` are built; messaging, wallet, calls and
the assistant are not. The spec is in `.claude/skills/circaso-backend-docs` (architecture, decisions, module designs).
Problems found in Kandra are in [`docs/kandra-bugs.md`](docs/kandra-bugs.md).

## Layout

| Path | What it is |
| --- | --- |
| `shared`, `shared-web` | The kernel (ids, `Resource`, errors, events) and the web envelope, StatusPages, `authenticate(scope)` and `docs(...)` |
| `infrastructure` | Kandra/ScyllaDB, Redis, event bus and outbox, scheduler, HTTP clients, config, file storage (Cloudflare R2 through the AWS SDK for Kotlin; see `STORAGE_*` and `R2_*` in `.env.example`) |
| `integrations` | Contracts for SMS, email, social sign-in, carrier check and push; `dev` (log/fake) and placeholder vendors |
| `gateway` | Version check, auth and scopes, rate limits, idempotency, webhooks, WebSocket hub, API reader |
| `modules/identity`, `modules/users` | Business modules: `api` (the contract) and `impl` (presentation, domain, data, di) |
| `app` | `serve` and `migrate`; the only place that knows the implementations |
| `architecture-tests` | Konsist rules that fail the build on a boundary breach |

## Run locally

```
docker compose -f deploy/docker-compose.yml up -d     # ScyllaDB and Redis
cp .env.example .env                                   # once; .env is not committed
./gradlew :app:run                                     # serve on :8080
./gradlew :app:migrate                                 # the migrations, once (local runs create tables on their own)
```

It is a plain embedded Ktor server, configured with environment variables and no arguments. For local development they
come from `.env` (`main` loads it first), so variables and secrets are set in one file; a variable really set in the
environment wins over `.env`.

The API reference is our own small TypeScript app (`docs-ui/`, no dependencies, about 25 KB over the wire), styled with circaso.com's design tokens and with an Idempotency-Key UUID generator in its top bar. It is at `http://localhost:8080/docs` and draws everything from the spec at `/docs/openapi.json`, so the page changes only when the spec does (both are validated by ETag). The built files are committed in `gateway/src/main/resources/docs/`; after changing `docs-ui/src` rebuild them with `cd docs-ui && bun run build` (`bun test` runs its tests). It is off in production and
behind the `ops` scope outside local runs. Local SMS codes are printed in the console (`INTEGRATIONS_SMS_PROVIDER=log`).

## Logging

Colored and human-readable locally (`LOG_FORMAT=PRETTY`, `LOG_COLOR=true`), JSON in staging and production. Levels are
set in `.env` or the environment, per service, each `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG` or `TRACE`:

| Variable | Controls |
| --- | --- |
| `LOG_LEVEL` | Circaso's own code, every module (default `INFO`) |
| `LOG_LEVEL_OTHER` | libraries and anything not named below (default `WARN`) |
| `LOG_LEVEL_REQUESTS` | the one line per HTTP request |
| `LOG_LEVEL_GATEWAY`, `_IDENTITY`, `_USERS`, `_ASSISTANT` | the gateway and each module |
| `LOG_LEVEL_INTEGRATIONS`, `_EVENTS`, `_SCHEDULER` | vendors, the event streams, scheduled jobs |
| `LOG_LEVEL_DATABASE`, `_REDIS`, `_HTTP` | ScyllaDB and Kandra, Redis, the Ktor server and Netty (the libraries' side too) |

To watch sign-in only: `LOG_LEVEL=WARN`, `LOG_LEVEL_IDENTITY=DEBUG`, `LOG_LEVEL_REQUESTS=INFO`.

## Test

`./gradlew build` compiles, runs every test (Testcontainers needs Docker) and the Konsist rules.

## Configuration

`.env.example` lists every setting. A setting's dotted name is its variable (`scylla.contact-points` is
`SCYLLA_CONTACT_POINTS`). Secrets are never logged: `PHONE_HMAC_KEY`, `ASSISTANT_VAULT_KEY`, `IDENTITY_SIGNING_KEY` with
`IDENTITY_SIGNING_PUBLIC_KEY` (Ed25519), `SCYLLA_USERNAME` and `SCYLLA_PASSWORD`. Locally, throwaway values are made
when they are missing; elsewhere startup stops. The `.env` made for a machine holds generated ones, so tokens survive a restart.

## Deploying to the dev server

Every push to `main` runs `.github/workflows/deploy-dev.yml`: the tests (`ci.yml`), then a Docker image published to Docker Hub (a private repository, `circaso-backend`), then a rollout to the dev server over SSH. The rollout runs `deploy/remote-deploy.sh` on the server: pull the image, run the database migrations once, start the new version, wait for `/health/ready`, and put the previous version back if it never becomes ready. Pull requests run the tests only.

- **The server** (`deploy/server-setup.sh`, run once): Ubuntu with Docker, a firewall that opens only 22, 80 and 443, fail2ban, automatic security updates, and SSH that accepts keys only, for the `deploy` user only. Only nginx (`deploy/nginx`) is open to the internet; the app and Redis are on a private Docker network. nginx limits request rates and body size, hides the readiness and Kandra health routes, passes WebSockets and Asha's streamed answers through unbuffered, and trusts `CF-Connecting-IP` only from Cloudflare's address ranges. It serves plain HTTP on port 80 until you add a Cloudflare Origin Certificate for HTTPS (steps in `deploy/nginx/tls.conf.example`).
- **GitHub holds only the way in:** in the repository's **Settings → Environments → dev**, add the secrets `DEV_SSH_KEY` (the private key of `~/.ssh/circaso_dev`) and `DOCKERHUB_TOKEN` (a Docker Hub access token with read and write rights on the repository), and the variables `DEV_HOST`, `DEV_USER` (`deploy`), `DEV_SSH_KNOWN_HOSTS` (the output of `ssh-keyscan -t ed25519 <host>`, which pins the server's identity), `DOCKERHUB_USERNAME` and `DEV_URL`. Turn on **Required reviewers** for the environment if a deploy should wait for a click.
- **The app's settings and secrets stay on the server** in `/opt/circaso/.env` (mode 600; see `deploy/dev.env.example`). Changing one means editing that file and re-running the deploy; nothing secret is in the image, in git or in GitHub.
- **Rolling back:** re-run the workflow on an earlier commit, or on the server `IMAGE=<dockerhub user>/circaso-backend:<sha> docker compose -f /opt/circaso/docker-compose.dev.yml up -d`.
