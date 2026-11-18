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
  (`WalletReports`, `FeeAdmin`, `NetworkAdmin`, `FeeWalletAdmin`), support (`SupportDesk`), assistant (`AssistantReports`), and integrations' `AiBilling`. Each is a staff-facing contract next to the module's own, never used by the app.
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
| AI usage and cost (`ai_usage.read`) | ✓ | | ✓ |
| groups (balances only with money) | ✓ | ✓ | ✓ |
| tickets: queue, reply, note, assign, status | ✓ | ✓ | |
| staff, audit, support accounts | ✓ | | |
| fees and limits: read (`fees.read`) | ✓ | | ✓ |
| fees and limits: change, fee bounds (`fees.manage`) | ✓ | | |
| fee wallets: balances, destinations, withdrawals (`fees.wallet.read`) | ✓ | | ✓ |
| fee wallets: add or remove a destination, withdraw (`fees.withdraw`) | ✓ | | |

## Endpoints (all under `/v1/ops`, on the ops gateway only)

Public: `GET auth/config`, `GET auth/handoff?return=`, `POST auth/dev-login`. Signed in: `GET me`, `GET dashboard?days=`,
`GET users?q=&cursor=&limit=`, `GET users/{id}`, `GET users/{id}/devices`, `GET users/{id}/wallets`,
`GET users/{id}/transfers`, `GET users/{id}/groups`, `GET users/{id}/tickets`, `POST users/{id}/suspend` `{reason}`,
`POST users/{id}/unsuspend` `{reason}`, `GET transactions`, `GET transactions/{id}`, `GET finance?from=&to=&bucket=`, `GET ai-usage?from=&to=&bucket=&limit=`,
`GET groups?wallet=`, `GET groups/{chatId}`, `GET tickets?status=&category=&assignee=&olderThanHours=`,
`GET tickets/{id}`, `POST tickets/{id}/replies|notes|assign|status`, `GET|POST staff`, `PATCH staff/{id}`, `GET audit`,
`GET|POST support-accounts` (OPS1, OPS2, moved here from the app gateway), `GET fees/rules`, `POST fees/rules`,
`POST fees/rules/{ruleId}/versions`, `POST fees/rules/{ruleId}/end`, `GET|PATCH fees/bounds`, `GET limits/rules`,
`POST limits/rules`, `POST limits/rules/{ruleId}/versions`, `POST limits/rules/{ruleId}/end`, and `GET openapi.json`
outside production.

**Transfers** on every staff route (`transactions`, `transactions/{id}`, `users/{id}/transfers`) carry `sender` and
`recipient` as typed parties `{kind: USER | GROUP | EXTERNAL | FEE, id, name, memberCount}` and `fee` (the fee leg to
Circaso's fee wallet, with its amount) when the payment carried one; `senderName`/`recipientName` are deprecated. A
group's name is always null (end-to-end encrypted). Groups and profiles are looked up once per page.

**Fees and limits** (decision 2026-11-08): the rules are the wallet's, edited through `FeeAdmin`. A change is a new
version (`baseVersion` the version edited; `effectiveFrom` null for now, never in the past); ending sets the latest
version's end; nothing is deleted. Every write needs a `reason` and is audited as `fee_rule.create|change|end`,
`limit_rule.create|change|end` or `fee_bounds.update` with the wallet's before and after in `detail`.
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
`ops.staff_from_config`, `ops.assignee_invalid`, `ops.staff_changed` — the module's; the fee routes pass the wallet's
`wallet.rule_not_found`, `wallet.rule_changed`, `wallet.rule_ended`, `wallet.rule_change_pending`,
`wallet.rule_scope_taken`, `wallet.rule_invalid`, `wallet.fee_rate_out_of_bounds`, `wallet.fee_bounds_exclude_rules`.

## AI usage (decision 2026-11-08)

`GET /v1/ops/ai-usage` prices the assistant's daily token totals (`AssistantReports`) with the configured table
(`OPS_AI_PRICES`, `OPS_AI_CACHE_WRITE_MULTIPLIER`, `OPS_AI_CACHE_READ_MULTIPLIER`, `OPS_AI_WEB_SEARCH_PER_1000_USD`,
`OPS_AI_PRICES_AS_OF`) over whole UTC days (1 to 366), and returns totals (turns, voice seconds, web searches, the four
token kinds, active users, cost, cost per active user, unpriced tokens), a series per bucket, the model breakdown, the
`limit` (default 10, at most 50) costliest users with their names, the price table with its assumptions, and
`anthropic`: Anthropic's own bill for the same days (`ok`), `off` without `OPS_ANTHROPIC_ADMIN_KEY`, or `unavailable`.
Money is micro-dollars, computed in `BigDecimal` and rounded once.

## Wallet networks (decision 2026-11-18)

Money exists on two networks, `MAINNET` and `TESTNET` (wallet.md → Networks). On the ops API:

- **`?network=MAINNET|TESTNET`** on any money read (`/users/{id}/wallets`, `/users/{id}/transfers`, `/transactions`, `/finance`, `/dashboard`, `/groups`, `/groups/{id}`, `/fees/rules`, `/limits/rules`): only that network. Without it, both, side by side: every wallet, transfer, chain-and-coin row, series row and group wallet carries `network`; `/finance` adds `byNetwork` totals, and `/finance` and `/dashboard` echo the filter as a top-level `network` (null when none). Any other value is `request.malformed`. It is one route group, `networkFiltered { }` (`shared-web`), around every ops controller: the handler runs with the network in its coroutine context and the use cases pass `networkFilter()` to `WalletReports`.
- **Rules.** A fee or limit rule's `scope.network` names the one network it is for (null: both, as every rule from before). A create with `?network=` and no `scope.network` belongs to that network; `?network=` on the lists shows the rules that apply there (those that name it, or a chain on it, and those that name none). `?network=` on a version or an end is accepted and changes nothing: a rule's scope is fixed.
- **`GET /users/{id}`** adds `networkAccess: [ { network, source, grantedBy, reason, at } ]`.
- **`POST /users/{id}/network-access`** `{ network, enabled, reason }` → the user (with `networkAccess`). Permission `users.network` (ADMIN); audited as `user.network_access` (`grant TESTNET`, `revoke TESTNET`, with `(no change)` when nothing changed); `ops.reason_required` without a reason. Revoking never deletes a wallet or its funds.
- **`GET /wallet/networks`** → `{ networks: [ { network, state, appId, defaultChain, chains: [ { blockchain, enabled, receiveEnabled, accountType, assets: [ { symbol, tokenAddress, decimals, usd } ] } ], version, custodyConfigured } ] }` and **`PUT /wallet/networks/{network}`** (the same object plus `reason`; `version` optional, a stale one answers `wallet.network_settings_changed`) → the network as saved. Permission `wallet.settings` (ADMIN) for both; the change is audited as `wallet_network.update` on target `wallet_network` with what changed (`v1→v2; state OFF→PREVIEW; added ARB`). The wallet's rules answer `wallet.network_settings_invalid` with the field. `custodyConfigured: false` means this server has no custody key for the network: it is off whatever `state` says. `tokenAddress` may be null or empty for a chain's own coin; `accountType` left out is `EOA` on Solana, `SCA` elsewhere.
- **Permissions added**: `users.network` (ADMIN), `wallet.settings` (ADMIN), `fees.wallet.read` (ADMIN, FINANCE), `fees.withdraw` (ADMIN). The last two guard the fee-wallet routes (below).
- The ops gateway's CORS allows `GET, POST, PUT, PATCH, DELETE, OPTIONS`.

## Fee wallets (decision 2026-11-18, built 2026-11-19)

Circaso's fee wallets are the wallet's (wallet.md → Fee wallets): Circle developer-controlled wallets **this process creates itself when it starts** and checks every ten minutes. There is no create route and no button. The ops process is the only one that holds the entity secrets (`CIRCLE_ENTITY_SECRET`, `CIRCLE_MAINNET_ENTITY_SECRET`, read by integrations' `treasuryModule`, which only `OpsMain` loads), and so the only one that runs the wallet's two `OpsJob`s (`wallet.fee-wallets`, `wallet.fee-withdrawals`): `startOpsInfrastructure` runs each once at start-up and then on its interval under the scheduler's Redis lock. A missing secret or a failing Circle never stops the server.

All under `/v1/ops/fee-wallets`, inside `networkFiltered { }`. Reads need `fees.wallet.read` (ADMIN, FINANCE); every write needs `fees.withdraw` (ADMIN) and an `Idempotency-Key` (the gateway's rule for every change).

- **`GET /fee-wallets?network=MAINNET|TESTNET`** (`network` required: the answer is one network's) → `{ network, status: READY|NOT_CREATED|FAILED, reason?, destinationDelayHours, wallets: [ { blockchain, address, balances: [ { symbol, amountMinor, decimals } ], nativeBalance: { symbol, amountMinor, decimals } | null, balancesKnown, withdrawable, unavailableReason? } ] }`. Balances are read from Circle on each call. `balancesKnown: false` (no Circle wallet behind the address, or Circle is down): `balances` is empty and `nativeBalance` null, never a zero nobody measured. `nativeBalance` is the chain's own coin, which pays a withdrawal's network fee.
- **`GET /fee-wallets/destinations?network=`** → `{ destinations: [ { id, network, blockchain, address, label, createdBy, createdById, createdAt, usableAt, usable } ] }`, newest first; without `network`, both. `createdBy` is the staff member's email.
- **`POST /fee-wallets/destinations`** `{ network, blockchain, address, label, reason }` → the destination. Audited as `fee_destination.add` (`add MAINNET BASE 0x… (id), usable <instant>`).
- **`DELETE /fee-wallets/destinations/{id}`** (`?reason=` optional) → the destination as it was. Audited as `fee_destination.remove` on the destination's id.
- **`GET /fee-wallets/withdrawals?network=&cursor=&limit=`** → `{ items: [ { id, network, blockchain, asset, amountMinor, decimals, destination: { id, address, label }, state: PENDING|SENT|CONFIRMED|FAILED, txHash?, reason, failureReason?, by, byId, createdAt, updatedAt } ], nextCursor }`, newest first, at most 100 a page.
- **`POST /fee-wallets/withdrawals`** `{ network, blockchain, asset, amountMinor, destinationId, reason }` → the withdrawal as it stands (`SENT`, or `PENDING` when Circle couldn't be reached: it is sent once, as soon as it can be). `amountMinor` is the whole amount in the coin's smallest unit **as text** (`"2500000"`), like every amount of the wallet's; a decimal, a sign or a JSON number is `request.malformed`. The request's `Idempotency-Key` is also the withdrawal's own key at the wallet. Audited as `fee_withdrawal.create` on the destination's id: the line is written before Circle is asked with what was asked (`MAINNET BASE 2500000 USDC (minor units) to destination <id>`), and finished with the outcome (`… to 0x… (label); withdrawal <id> SENT`) or the wallet's error code.

Every write needs a `reason` (`ops.reason_required`), except removing a destination, where it is optional. Errors pass through from the wallet: `fees.destination_not_ready`, `fees.insufficient_balance`, `fees.insufficient_gas`, `fees.destination_not_found`, `fees.destination_exists`, `fees.destination_is_fee_wallet`, `fees.asset_unsupported`, `fees.withdrawals_unavailable`, `fees.withdrawal_refused`, `fees.custody_unavailable`, `fees.invalid`. Audit targets `fee_destination` and `fee_withdrawal`.
