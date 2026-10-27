# Circaso backend

One Kotlin/Ktor deployable split into strict modules: `identity`, `users`, `messaging` and `assistant` are built;
wallet and calls are not. The spec is in `.claude/skills/circaso-backend-docs` (architecture, decisions, module designs).

## Layout

| Path | What it is |
| --- | --- |
| `shared`, `shared-web` | The kernel (ids, `Resource`, errors, events) and the web envelope, StatusPages, `authenticate(scope)` and `docs(...)` |
| `infrastructure` | PostgreSQL (Exposed over R2DBC; Flyway for `migrate`), Redis, event bus and outbox, scheduler, HTTP clients, config, file storage (Cloudflare R2 through the AWS SDK for Kotlin; see `STORAGE_*` and `R2_*` in `.env.example`) |
| `integrations` | Contracts for SMS, email, social sign-in, carrier check and push; `dev` (log/fake) and placeholder vendors |
| `gateway` | Version check, auth and scopes, rate limits, idempotency, webhooks, WebSocket hub, the OpenAPI spec, the error pages |
| `modules/identity`, `modules/users`, `modules/messaging`, `modules/assistant` | Business modules: `api` (the contract) and `impl` (presentation, domain, data, di), each with its own PostgreSQL schema, role and Flyway scripts (`db/<module>`) |
| `app` | `serve` and `migrate`; the only place that knows the implementations |
| `architecture-tests` | Konsist rules that fail the build on a boundary breach |

## Run locally

```
docker compose -f deploy/docker-compose.yml up -d     # PostgreSQL and Redis
cp .env.example .env                                   # once; .env is not committed
./gradlew :app:migrate                                 # each module's schema, role and Flyway scripts, as a deploy does
./gradlew :app:run                                     # serve on :8080 (it never migrates, and stops if a schema is behind)
```

It is a plain embedded Ktor server, configured with environment variables and no arguments. For local development they
come from `.env` (`main` loads it first), so variables and secrets are set in one file; a variable really set in the
environment wins over `.env`.

The API reference is our own small TypeScript app (`docs-ui/`, no dependencies, about 25 KB over the wire), its own service rather than part of the server: static files in a tiny nginx image (`pascarl/circaso-docs`, built by the deploy workflow), styled with circaso.com's design tokens and with an Idempotency-Key UUID generator in its top bar. It draws everything from the spec at `/docs/openapi.json`, which the server builds from its routes, so the page changes only when the spec does (both are validated by ETag). In front of both, nginx routes `/docs/openapi.json` to the server and the rest of `/docs` to the docs container. Locally: start the server, then `cd docs-ui && bun run build && bun run serve` and open `http://localhost:8090/docs` (`bun test` runs its tests; the build writes `docs-ui/dist/`, which is not committed). It is off in production and
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
| `LOG_LEVEL_CIRCLE` | Circle wallets and its webhook (follows `LOG_LEVEL_INTEGRATIONS` when unset) |
| `LOG_LEVEL_DATABASE`, `_REDIS`, `_HTTP` | PostgreSQL (pools, transactions, migrations), Redis, the Ktor server and Netty (the libraries' side too) |

To watch sign-in only: `LOG_LEVEL=WARN`, `LOG_LEVEL_IDENTITY=DEBUG`, `LOG_LEVEL_REQUESTS=INFO`.

## Test

`./gradlew build` compiles, runs every test (Testcontainers needs Docker) and the Konsist rules.

## Configuration

`.env.example` lists every setting. A setting's dotted name is its variable (`database.ssl-mode` is
`DATABASE_SSL_MODE`). Secrets are never logged: `PHONE_HMAC_KEY`, `ASSISTANT_VAULT_KEY`, `IDENTITY_SIGNING_KEY` with
`IDENTITY_SIGNING_PUBLIC_KEY` (Ed25519), and the `DATABASE_*PASSWORD`s. Locally, throwaway values are made
when they are missing; elsewhere startup stops. The `.env` made for a machine holds generated ones, so tokens survive a restart.

Vendor webhooks at `/v1/hooks/{vendor}` can be limited to the vendor's published source addresses with
`GATEWAY_WEBHOOKS_<VENDOR>_ALLOWED_IPS` (comma separated, exact IPs; `africastalking`, `resend`, `circle`, `cloudflare`).
Unset, any address may call and the signature is the guard. For Circle, each node also fetches at most 10 unknown
signing keys a minute, so posts with made-up key ids can't spend our Circle rate limit; set
`GATEWAY_WEBHOOKS_CIRCLE_ALLOWED_IPS` to Circle's published IPs to turn such traffic away before it is read.

## Deploying to the dev server

Every push to `main` runs `.github/workflows/deploy-dev.yml`: the tests (`ci.yml`), then a Docker image published to Docker Hub (a private repository, `circaso-backend`), then a rollout to the dev server over SSH. The rollout runs on the server, in `/opt/circaso`: pull the image, run the migrations once from the new image (`docker compose run --rm --no-deps --entrypoint java app -cp "/app/lib/*" com.circaso.app.Migrate`, with the server's `.env`; the server reaches its PostgreSQL, a laptop is not allowed in); the migration step first creates any module schema and role that is missing, as `circaso_migrator`, then runs each module's Flyway scripts, so a new module needs no schema made by hand, and only if they pass `docker compose up -d`, wait for `/health/ready` (a failed migration fails the run before anything is restarted and shows the migration's last lines, so the old version keeps serving; the run also fails, and shows the app's last log lines, if the new version never becomes ready), and prune unused images. Pull requests run the tests only.

- **The server** (`deploy/server-setup.sh`, run once): Ubuntu with Docker, a firewall that opens only 22 and 443, fail2ban, automatic security updates, and SSH that accepts keys only, for the `deploy` user only. Only nginx (`deploy/nginx`) is open to the internet; on the server `/opt/circaso/docker-compose.yml` matches the reference copy `deploy/docker-compose.dev.yml`: `circaso-backend` (nginx, the app and the docs reader; created once with `docker network create circaso-backend`) and `circaso-data` (the app and Redis, internal so no internet, so nginx cannot reach Redis); only port 443 is published, and Redis runs as `999:1000`. nginx answers its own errors (a refused request, a rate limit) with the app's designed pages: the gateway serves them at the internal path `/_errors/<status>` (`gateway/src/main/resources/errors`, the same tokens as the API reference), the 403 page shows the caller their own address, country, browser and request, and one static page (`deploy/nginx/errors/50x.html`) covers the app being down. nginx limits request rates and body size, hides the readiness route, passes WebSockets and Asha's streamed answers through unbuffered, and trusts `CF-Connecting-IP` only from Cloudflare's address ranges. It serves HTTPS on 443 only, with a Cloudflare Origin Certificate in `/opt/circaso/certs` and `nginx/tls.conf` (steps in `deploy/nginx/tls.conf.example`); port 80 is closed. After a good deploy the script removes every app image except the new one and the previous one. The files in `/opt/circaso` (`docker-compose.yml`, nginx, certs, `.env`) belong to the server: the workflow copies none of them. It logs in, starts the new image with the server's `docker-compose.yml` (which reads `IMAGE`), waits for `/health/ready`, and prunes unused images. `deploy/docker-compose.dev.yml` and `deploy/nginx` are reference copies to start a server from.
- **GitHub holds only the way in:** in the repository's **Settings → Environments → dev**, add the secrets `DEV_SSH_KEY` (the private key of `~/.ssh/circaso_dev`) and `DOCKERHUB_TOKEN` (a Docker Hub access token with read and write rights on the repository), and the variables `DEV_HOST`, `DEV_USER` (`deploy`), `DEV_SSH_KNOWN_HOSTS` (the output of `ssh-keyscan -t ed25519 <host>`, which pins the server's identity), `DOCKERHUB_USERNAME` and `DEV_URL`. Turn on **Required reviewers** for the environment if a deploy should wait for a click.
- **The app's settings and secrets stay on the server** in `/opt/circaso/.env` (mode 600; see `deploy/dev.env.example`). Changing one means editing that file and re-running the deploy; nothing secret is in the image, in git or in GitHub.
- **Phone codes** can be made and checked by Twilio Verify instead of by the app (`INTEGRATIONS_VERIFY_PROVIDER=twilio`; setup in `docs/twilio-verify.md`).
- **Rolling back:** re-run the workflow on an earlier commit, or on the server `cd /opt/circaso && IMAGE=<dockerhub user>/circaso-backend:<sha> DOCS_IMAGE=<dockerhub user>/circaso-docs:<sha> docker compose up -d`. To restart the stack as it is, `/opt/circaso/up.sh` (it uses `.current` and `.current-docs`, which each deploy writes; `.env` holds settings only, no image).
- **The API reference on the dev server** (`https://sandbox.circaso.com/docs`) is guarded by **Cloudflare Access**, not by the app's `ops` token (a browser cannot send one). The server's `.env` sets `GATEWAY_API_DOCS_REQUIRE_OPS=false`, and nginx refuses any `/docs` request (the reader and the spec) that does not carry Access's `Cf-Access-Jwt-Assertion` header. nginx drops any connection that does not come from one of Cloudflare's address ranges (`deploy/nginx/cloudflare-ips.inc`, refreshed with `deploy/nginx/refresh-cloudflare-ips.sh`), so the server cannot be reached around Cloudflare. Docker publishes ports around the host firewall, so that rule lives in nginx; `deploy/firewall-cloudflare.sh` adds the same limit at the host as a second layer.
