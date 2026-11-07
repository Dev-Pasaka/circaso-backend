# ops — the staff side, and the ops gateway

Built 2026-11-08 (Pasaka's decisions of that day; details made by the backend session and recorded in decisions.md).

## Overview

- **Owns:** the staff list (who may sign in to the Circaso admin, with which role) and the audit log of every staff
  action. Nothing else: every other figure a staff page shows is read, or changed, through the owning module's contract.
- **Served by:** the **ops gateway** only: its own Gradle module (`ops-gateway`, package `com.circaso.opsgateway`), its own
  process (`app`'s second main class `com.circaso.app.OpsMain`, `./gradlew :app:runOps`, compose service `ops`), host
  (`admin-api.circaso.com`), port (`OPS_PORT`, 8081), CORS, sign-in and rate limits. The app gateway answers
  `request.not_found` for anything under `/v1/ops`, and mounts only `RouteContributor`s; the ops gateway mounts only
  `OpsRouteContributor`s (shared-web). `OpsSeparationTest` checks both.
- **Contract (`ops:api`):** `StaffDirectory` (`signIn(email)`, `recordDevLogin`), implemented here, used by the ops gateway.
- **Depends on:** identity (`AccountAdmin`, `SupportAccounts`), users (`ProfileAdmin`), messaging (`GroupReports`), wallet
  (`WalletReports`), support (`SupportDesk`). Each is a staff-facing contract next to the module's own, never used by the app.
- **Schema** `ops`, no outbox (publishes nothing), no Redis keys of its own (the ops gateway uses `ogw:` for its limits and
  idempotency), no frames.

## Sign-in (the ops gateway)

1. **Cloudflare Access** (production, and the sandbox once switched on): the token from `Cf-Access-Jwt-Assertion` or the
   `CF_Authorization` cookie, RS256, signed by a key from `https://<OPS_ACCESS_TEAM_DOMAIN>/cdn-cgi/access/certs` (kept 10
   minutes, read again for an unknown key id at most every 30 s), `aud` containing `OPS_ACCESS_AUD`, `iss` =
   `https://<team>`, not expired, `nbf` within a minute. Production refuses to start without it.
2. **Dev login** (sandbox and local only): `OPS_DEV_LOGIN_SECRET` (32+ characters) turns on `POST /v1/ops/auth/dev-login`
   `{email, secret}` → an HMAC-SHA256 bearer token for `OPS_DEV_LOGIN_TTL_MINUTES` (480). Production refuses to start with
   the secret set. Audited as `staff.dev_login`. The portal shows a DEV LOGIN banner.
3. Either way, the email must belong to an **active staff member** on every request (403 `ops.not_staff`): a member
   switched off is refused from their next call. `OPS_BOOTSTRAP_ADMINS` are always active ADMINs (ids derived from the email).
4. The principal is `StaffPrincipal(staffId, email, role, signIn)` with scope `Scope.OPS`; routes guard with
   `staffOnly(roles)` from shared-web, using the one `Permission` table, which `/v1/ops/me` also hands the portal.

## Roles

| Permission | ADMIN | SUPPORT | FINANCE |
| --- | --- | --- | --- |
| dashboard (users, active, groups) | ✓ | ✓ | ✓ |
| dashboard (transactions, fees) | ✓ | | ✓ |
| dashboard (tickets) | ✓ | ✓ | |
| users: search, account, groups | ✓ | ✓ | ✓ |
| users: devices and sessions, tickets | ✓ | ✓ | |
| users: wallets, transfers, fees paid | ✓ | | ✓ |
| users: suspend, unsuspend | ✓ | ✓ | |
| transactions, finance | ✓ | | ✓ |
| groups (balances only with money) | ✓ | ✓ | ✓ |
| tickets: queue, reply, note, assign, status | ✓ | ✓ | |
| staff, audit, support accounts | ✓ | | |

## Endpoints (all under `/v1/ops`, on the ops gateway only)

Public: `GET auth/config`, `GET auth/handoff?return=`, `POST auth/dev-login`. Signed in: `GET me`, `GET dashboard?days=`,
`GET users?q=&cursor=&limit=`, `GET users/{id}`, `GET users/{id}/devices`, `GET users/{id}/wallets`,
`GET users/{id}/transfers`, `GET users/{id}/groups`, `GET users/{id}/tickets`, `POST users/{id}/suspend` `{reason}`,
`POST users/{id}/unsuspend` `{reason}`, `GET transactions`, `GET transactions/{id}`, `GET finance?from=&to=&bucket=`,
`GET groups?wallet=`, `GET groups/{chatId}`, `GET tickets?status=&category=&assignee=&olderThanHours=`,
`GET tickets/{id}`, `POST tickets/{id}/replies|notes|assign|status`, `GET|POST staff`, `PATCH staff/{id}`, `GET audit`,
`GET|POST support-accounts` (OPS1, OPS2, moved here from the app gateway), and `GET openapi.json` outside production.
Every change needs an `Idempotency-Key`; limits: `OPS_RATE_LIMIT_PER_MINUTE` per staff member (300),
`OPS_PUBLIC_RATE_LIMIT_PER_MINUTE` per address (30), the dev login 5 a minute per address.

## Data

- `ops.staff` (staff_id, email unique lowercased, role, active, added_by, created_at, updated_at, version).
- `ops.audit_log` (entry_id UUIDv7, actor id/email/role, action, target type/id, reason, detail, outcome
  PENDING|SUCCEEDED|FAILED, error_code, request_id, at, finished_at), indexed by actor, action and target, newest first.
  A line is written `PENDING` before the action and finished after: no action without its line.

## Error codes

`ops.sign_in_required` (401), `ops.role_forbidden` (403) — shared-web's; `ops.access_token_invalid` (401),
`ops.not_staff` (403), `ops.access_unavailable` (503), `ops.dev_login_disabled` (404), `ops.dev_login_refused` (401),
`ops.return_url_invalid` (400) — the gateway's; `ops.reason_required`, `ops.user_not_found`, `ops.transfer_not_found`,
`ops.group_not_found`, `ops.staff_not_found`, `ops.staff_exists`, `ops.email_invalid`, `ops.cannot_change_self`,
`ops.staff_from_config`, `ops.assignee_invalid`, `ops.staff_changed` — the module's.
