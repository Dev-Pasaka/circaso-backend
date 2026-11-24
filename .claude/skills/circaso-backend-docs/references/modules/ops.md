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
`POST limits/rules`, `POST limits/rules/{ruleId}/versions`, `POST limits/rules/{ruleId}/end`, the ramp's routes
(`ramp/…`, see "Ramp" and "Ramp: the merchant engine" below), and `GET openapi.json` outside production.

**The verified badge on staff reads (2026-11-24, additive):** every `GET users` row carries `verification`
(`VERIFIED` | `NONE`, identity's `accounts.verification`) and `verifiedAt` (null unless verified); `GET users/{id}`
adds `verifiedAt` beside its `verification`. `GET users?verified=true` lists only verified people: with an empty `q`
identity pages the verified accounts through `accounts_verified_idx` (identity V11, a partial index on the newest-first
keyset), so pages stay full; with a search it keeps the verified among what the search found (a page can come back
short; its `nextCursor` is still good). Only an approved Didit check in the **live** environment makes an account
`VERIFIED` (ramp.md, identity.md → "The verified badge"); the ramp identity card (`ramp/users/{id}/verification`) shows
the per-environment checks behind it.

**Transfers** on every staff route (`transactions`, `transactions/{id}`, `users/{id}/transfers`) carry `sender` and
`recipient` as typed parties `{kind: USER | GROUP | EXTERNAL | FEE, id, name, memberCount}` and `fee` (the fee leg to
Circaso's fee wallet, with its amount) when the payment carried one; `senderName`/`recipientName` are deprecated. A
group's name is always null (end-to-end encrypted). Groups and profiles are looked up once per page.

**Fees and limits** (decision 2026-11-08): the rules are the wallet's, edited through `FeeAdmin`. A change is a new
version (`baseVersion` the version edited; `effectiveFrom` null for now, never in the past); ending sets the latest
version's end; nothing is deleted. Every write needs a `reason` and is audited as `fee_rule.create|change|end`,
`limit_rule.create|change|end` or `fee_bounds.update` with the wallet's before and after in `detail`. Since 2026-11-24 a fee rule's `terms` carry `bands` (`[{fromUsdMicros, toUsdMicros|null, rateBps}]`, always at least one on a read; see wallet.md, "Amount bands"), and the audit detail lists them (`bands [$0-$1: 200 bps; $1+: 100 bps]`). The ramp settings (RO5) carry `onRampFeeBands` and `offRampFeeBands` (`[{fromMinor, toMinor|null, feeBps}]`, KES cents as strings; ramp.md, "As built: fee bands").
Every change needs an `Idempotency-Key`; limits: `OPS_RATE_LIMIT_PER_MINUTE` per staff member (300),
`OPS_PUBLIC_RATE_LIMIT_PER_MINUTE` per address (30), the dev login 5 a minute per address.

## Data

- `ops.staff` (staff_id, email unique lowercased, role, active, added_by, created_at, updated_at, version).
- `ops.email_templates` (template primary key, provider_template_id, updated_by, updated_at, version; `V2__email_templates.sql`): the provider's template id for each email (decision 2026-11-22). A change is `version = version + 1 WHERE version = ?`.
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

## Email templates (decision 2026-11-22)

The environment holds keys and secrets only, so the id each email is sent with at the provider (Resend) is a row staff set here. `integrations:api` declares `EmailTemplateIds` and this module answers it in both processes (`StoredEmailTemplateIds`), behind `EmailTemplateBook`: one read of `ops.email_templates` at most every ten seconds per process; on a database error the last read stays in force. The first time an email has no row, the old `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` variable is copied in once if it is still set (`EmailTemplateSeeds`, from integrations), and never read for that email again. An email with no id is not sent; nothing stops a server from starting.

- **`GET /email-templates`** (`email_templates.read`: FINANCE, ADMIN) → `{ templates: [ { template, providerTemplateId, set, version, updatedBy, updatedAt } ] }`: every `EmailTemplate`, `set: false` and `version: 0` while it has no id; `updatedBy` null for an imported one.
- **`PUT /email-templates/{template}`** (`email_templates.manage`: ADMIN) `{ providerTemplateId, version, reason }` → the row. `version` is the one read (0 when none was set; left out, save over whatever is there). Audited as `email_template.set` on target `email_template` with the id before and after. Errors: `ops.reason_required`, `ops.email_template_not_found` (404), `ops.email_template_invalid` (400: blank, spaces or over 200 characters), `ops.email_template_changed` (409).

The admin portal has no page for these routes yet.

## Ramp (decision 2026-11-22)

The ramp's staff routes, under `/v1/ops/ramp` inside `networkFiltered { }`, through `ramp:api`'s `RampAdmin` (ramp.md → "Staff routes as built"). Reads need `ramp.read` (FINANCE, ADMIN); writes need `ramp.manage` (ADMIN), a reason and an `Idempotency-Key`, and are audited (`ramp_merchant.approve|suspend`, `ramp_settings.update`, `user.ramp_preview_access`, and for identity `user.ramp_block`, `user.ramp_unblock`, `user.ramp_verification_reset`, `ramp_merchant.verification_reset`).

- `GET merchants`, `POST merchants/{id}/approve|suspend`, `GET|PUT settings?network=`, `GET|POST preview-access`, `GET orders?state=REVIEW`.
- **Settings** gained `providerFeeFloorMinor` (2026-11-22): the least a merchant's prepaid service wallet at the collections provider must hold for them to go online, KES cents as a string, `"0"` for no check. Always in the answer; a `PUT` that leaves it out keeps the current value, so the admin form works before it has the field. **The admin form needs the field.** The audit line names it when it changes.

**Identity verification and blocks (2026-11-22, `feat/ramp-kyc`; ramp.md → "As built: identity verification").** Reads `ramp.read`, writes `ramp.manage` with a reason; every write is audited on the person or merchant it is about. A verification belongs to a provider environment (`SANDBOX`: simulated checks; `LIVE`: real people), and each network's settings name the one it trusts. No document, name, picture or identity key ever reaches the ops module: `hasIdentity` only says a key is held.

- **`GET /ramp/users/{userId}/verification`** → `{ userId, verifications: [ { environment, status, reason, provider, attempts, hasIdentity, startedAt, verifiedAt, updatedAt } ], blocked, block: { blockId, userId, reason, note, createdBy, createdAt } | null }`. `verifications` is empty for someone who never started. `status`: `PENDING`, `IN_REVIEW`, `VERIFIED`, `DECLINED`, `EXPIRED`. `ops.user_not_found` (404).
- **`GET /ramp/blocks?cursor=&limit=`** → `{ blocks: [ … ], nextCursor }`, barred users newest first; `nextCursor` is `<createdAt>~<blockId>`. `reason`: `OPS`, `REVERSAL`, `IDENTITY` (the account verified as an identity that was already barred; `createdBy` is `system`).
- **`POST /ramp/blocks`** `{ userId, reason }` → the block. Audit `user.ramp_block`; the reason is kept on the block as its `note`. The identity the user verified with is barred too, so an account that later verifies as it is refused and barred at once. Barring someone already barred answers the block there is.
- **`DELETE /ramp/blocks/{userId}?reason=`** → the user as they stand afterwards (the lookup's shape). Audit `user.ramp_unblock` (`unblocked` or `was not blocked`). Lifts the identity blocks that came from this user's block. An account barred as `IDENTITY` stays declined until the source user's block is lifted and this account's verification is reset.
- **`POST /ramp/users/{userId}/verification/reset`** `{ environment, reason }` → the user as they stand afterwards. Audit `user.ramp_verification_reset` (`reset LIVE: removed` or `nothing to remove`). Removes the verification in that environment and frees the identity it held: the way out when a lost account holds someone's identity (`identity_in_use` on the new one) or a decline can't be retried. It never lifts a block.
- **`POST /ramp/merchants/{merchantId}/verification/reset`** `{ environment, reason }` → `{ removed }`. Audit `ramp_merchant.verification_reset`. The merchant keeps their state but can't go online where KYC is required until verified again.
- **Approving a merchant** now answers `ramp.merchant_kyc_required` (409, the networks named in the message and `details.fields`) while their identity isn't verified on a network whose ramp is open (`PREVIEW` or `ON`) and requires KYC.
- **The merchant list** gains `verifications` (the same shape, one per environment) and **the summary's `merchant`** gains `kyc` (the verification in the environment its network trusts, or null).
- **Settings** gained the identity settings: `kycProvider` (one of the read-only `kycProviders`: what the server offers), `kycEnvironment` (`SANDBOX` | `LIVE`), `kycWorkflowUser`, `kycWorkflowMerchant` (the provider's workflow ids; empty: that kind can't be verified on the network yet), `merchantPortalUrl` (an origin), `kycAttemptsPerDay` (1 to 20) and `kycPendingHours` (1 to 672). Always in the answer; a `PUT` that leaves one out keeps its current value. `kycRequired` is unchanged (never false on MAINNET). **The admin form needs these fields**; the audit line names each one that changes.
- **What the admin needs:** on a user's page, a "Ramp identity" card (the lookup; Block / Unblock; Reset per environment); a "Blocked" list under Ramp; on a merchant, their verification beside the state, with the approve button explaining `ramp.merchant_kyc_required`; the seven settings on the Ramp settings form, per network.

**Each merchant's activity (2026-11-22)**: three reads, `ramp.read`, `?network=MAINNET|TESTNET` required on each (`request.malformed` without it). `from` and `to` are **Nairobi calendar days** (East Africa Time), inclusive, at most a year apart; one without the other, a `to` before `from` or anything that isn't a date is `request.malformed`. Amounts are `{ unit, amountMinor, amount }` (`unit` KES or USDC). Every figure is added up by the ramp from the order and commission rows the merchant's own earnings overview and statement use, so the admin and the merchant portal always agree; the ops module only shapes the answer.

- **`GET /ramp/overview?network=&from=&to=`** (default: this Nairobi month) → `{ network, from, to, totals: { orders, completed, failed, inReview, collected, sold, fees, merchantShare, circasoShare, circasoShareUsdc, swept, held }, merchants: [ { merchantId, displayName, state, online, orders, completed, successRatePercent, collected, sold, fees, merchantShare, circasoShare, circasoShareUsdc, held, lastOrderAt } ] }`. `totals` is the sum of the merchant rows. The rows are the approved merchants and any other with an order in the period.
- **`GET /ramp/merchants/{merchantId}/summary?network=&from=&to=`** (default: this month) → `{ merchant: { merchantId, displayName, email, contactPhone, state, createdAt, approvedAt, online, onlineUntil, paymentsConnected, collectionsProvider, paymentsIssue: { code, since } | null }, network, from, to, wallet: { blockchain, address } | null, float: { balance, reserved, owedToCircaso, free } | null, orders: { total, completed, failed, expired, cancelled, inReview, open }, successRatePercent, collected, sold, fees, merchantShare, circasoShare, circasoShareUsdc, swept, held, daily: [ { day, orders, completed, collected, sold, fees, merchantShare, circasoShare } ], sweeps: [ { settlementId, day, amount, state, orders, txHash } ] }`. `float` is null when the custodian can't be read (never a zero nobody measured). `daily` is oldest first. `ramp.merchant_not_found` (404).
- **`GET /ramp/merchants/{merchantId}/orders?network=&from=&to=&state=&cursor=&limit=`** → `{ orders: [ { orderId, createdAt, completedAt, state, direction, blockchain, maskedPhone, paid, released, rate, fee, merchantShare, circasoShare, circasoShareUsdc, failureReason, settlementId, txHash, collectionsProvider } ], nextCursor }`, newest first, keyset paged (default 50, at most 200; `nextCursor` is `<createdAt>~<orderId>`, null on the last page; a cursor that isn't one is `request.malformed`). Without `from`/`to`: every day. `state` is one of RESERVED, PAYMENT_REQUESTED, PAID, RELEASING, COMPLETED, EXPIRED, CANCELLED, FAILED, REVIEW (any case). The payer's phone is always masked: the whole number never reaches the ops module. `completedAt`, `merchantShare`, `circasoShare`, `circasoShareUsdc` and `settlementId` are null until the order completes. `ramp.merchant_not_found` (404).

Counting: `completed` orders are counted on the day they completed, the others on the day they were made; `orders` is their sum and `successRatePercent` is completed out of it (`"0"` with none). `online` is the merchant's trading window.

**Merchants stay online across restarts (2026-11-24; ramp.md → "As built: merchants stay online across restarts").** RO5 settings gain `onlineSessionMaxDays` (integer, 1 to 90, default 30): how many days an open-ended merchant session's encrypted working copy is kept, so how long it survives restarts and deploys without the merchant's PIN. Always in the answer; a `PUT` that leaves it out keeps the current value; audited by name.

**Float withdrawals (2026-11-24; ramp.md → "As built: float withdrawals").** `GET /ramp/merchants/{merchantId}/withdrawals?network=&limit=` (`ramp.read`, `?network=` required) → `{ withdrawals: [ { withdrawalId, state, amount, blockchain, destinationAddress, destinationLabel, txHash, explorerUrl, failureReason, createdAt, updatedAt, closedAt } ], destinations: [ { destinationId, label, address, blockchain, createdAt, usableAt, removedAt } ] }`, newest first. `state`: `REQUESTED`, `SENT`, `COMPLETED`, `FAILED`. `ramp.merchant_not_found` (404). The ramp settings (RO5) carry `withdrawDestinationDelayHours`, `withdrawMinMinor` and `withdrawDailyMaxMinor` (USDC minor units as strings; `"0"`: no cap), audited by name in `ramp_settings.update`. The sends themselves run in the ops process inside `ramp.release`.

## Ramp: the merchant engine (decision 2026-11-23)

The engine's staff routes, under `/v1/ops/ramp` inside `networkFiltered { }`, in a controller of their own (`rampEngineController`), through a second contract in `ramp:api`, `RampEngineAdmin` (ramp.md → "As built: the merchant engine" has the formulas, the defaults and the JSON). Reads need `ramp.read` (FINANCE, ADMIN); writes need `ramp.manage` (ADMIN), a reason and an `Idempotency-Key`, and are audited: `ramp_engine_settings.update` on the new target `ramp_engine_settings` (target id `<NETWORK>:<DIRECTION>`), and `ramp_merchant.tier` on `ramp_merchant`.

- **`GET /ramp/engine-settings?network=&direction=`** → `{ network, direction, version, settings: { mode, weightCompletion, weightSpeed, weightFailure, priorStrength, completionPrior, failurePrior, speedPrior, qualityFloor, alpha, hungerMax, hungerWindowMinutes, newcomerMax, newcomerTrades, smallOrderMinor, floatCover, shareThreshold, sharePenalty, slaSeconds, targetSeconds, statsOrders, statsDays, maxAttempts, individualLimitMinor, businessLimitMinor, logRetentionDays }, updatedBy, reason, updatedAt }`. Both `?network=MAINNET|TESTNET` and `?direction=ON_RAMP|OFF_RAMP` are required (`request.malformed` without). `mode`: `ROUND_ROBIN`, `SHADOW`, `ACTIVE`. The three `…Minor` fields are KES cents as strings; the rest are numbers. `updatedBy` (a staff id) and `reason` are null on the seed.
- **`PUT /ramp/engine-settings?network=&direction=`** `{ settings: { …the whole document… }, version, reason }` → the new version, in the GET's shape. `version` is the one read (left out: save over whatever is there). Audit `ramp_engine_settings.update`; the line's detail is what changed, by name (`v1→v2; mode ACTIVE→SHADOW; alpha 1.0→2.0`), and the reason is also kept on the version in the ramp. Errors: `ops.reason_required`, `ramp.settings_invalid` (400, `details.fields` names the setting: the ranges are in the DTO's description and in ramp.md), `ramp.settings_changed` (409), `request.malformed` (a `…Minor` that isn't digits, a mode that isn't one).
- **`POST /ramp/engine-settings/simulate?network=&direction=`** (`ramp.read`) `{ settings?, lastOrders? | amountMinor? }` → `{ network, direction, mode, currentMode, orders, unroutable, shares: [ { merchantId, displayName, sharePercent, currentSharePercent, eligibleOrders, exclusions: { REASON: count }, quality } ] }`. `settings` is the draft (left out: what is in force); `lastOrders` (1 to 500, default 50) replays the last real orders, `amountMinor` one order of that many KES cents (not both). **Nothing is written, reserved or sent, and nothing is audited.** It is a `POST` only because it carries a body, so it still needs an `Idempotency-Key`. Errors: `ramp.settings_invalid`, `ramp.pricing_unavailable` (502, pricing a single amount), `request.malformed`.
- **`GET /ramp/routing?network=&merchantId=&orderId=&from=&to=&cursor=&limit=`** → `{ decisions: [ { decisionId, createdAt, quoteId, orderId, merchantId, direction, fiatAmountMinor, mode, outcome, settingsVersion, candidates, eligible, attempts } ], nextCursor }`, newest first, keyset paged (`nextCursor` is `<createdAt>~<decisionId>`). `?network=` is required. `merchantId`: the decisions that ended with that merchant. `orderId`: the routing of the purchase the order belongs to, whichever attempt it was. `from` (inclusive) and `to` (exclusive) are instants (`2026-11-23T09:00:00Z`). `outcome`: `PENDING`, `ROUTED`, `NO_CANDIDATE`, `EXHAUSTED`, `STOPPED`.
- **`GET /ramp/routing/{decisionId}`** → the routing in full: `{ decisionId, createdAt, network, direction, quoteId, orderId, merchantId, fiatAmountMinor, needMinor, mode, outcome, settingsVersion, candidates: [ { merchantId, excluded, approved, kycCleared, online, hasKeys, paymentsIssue, offersDirection, limitMinor, floatMinor, lastAssignedAt, completedTrades, stats, score } ], ranked, engineRanked, steps: [ { merchantId, orderId, result, at } ] }`. `ramp.routing_not_found` (404).
- **`PUT /ramp/merchants/{merchantId}/tier`** `{ merchantType, orderLimitMinor, reason }` → the merchant as the list shows them. `merchantType`: `INDIVIDUAL` or `BUSINESS`; `orderLimitMinor`: KES cents as a string, or null or left out for the type's default. Audit `ramp_merchant.tier`, the detail being the type and limit afterwards. Errors: `ops.reason_required`, `ramp.merchant_not_found` (404), `ramp.settings_invalid` (a limit of zero), `request.malformed`.
- **`GET /ramp/merchants/{merchantId}/signals?network=`** → `{ merchantId, network, merchantType, directions: [ { direction, offered, orderLimitMinor, stats: { finished, completed, merchantFailures, merchantExpiries, reversals, counted, notCounted, medianHandlingSeconds, p90HandlingSeconds }, trades, newcomerTrades, newcomerTradesLeft, targetSeconds, slaSeconds, statsOrders, statsDays } ] }`: what the engine scores the merchant on, the same record their portal shows them (RM16). `ramp.merchant_not_found` (404).
- **The merchant list and a tier change** now carry `merchantType`, `orderLimitMinor` (KES cents as a string, or null), `offersOnRamp` and `offersOffRamp`.
- **What the admin needs:** an "Engine" page under Ramp with the settings form per network and direction (with the simulation beside it, run before saving), a "Routing" list with a detail view (the candidates table with each one's reason or score, the order tried, the steps), and on a merchant's page their type and limit (editable by an ADMIN) and their signals.

## Ramp: rates (decision 2026-11-24)

The ramp's buy and sell rates (ramp.md → "As built: rates"), under `/v1/ops/ramp` inside `networkFiltered { }`, in a controller of their own (`rampRatesController`), through a third contract in `ramp:api`, `RampRatesAdmin`. FINANCE and ADMIN read (`ramp.read`); ADMIN changes (`ramp.manage`). `?network=MAINNET|TESTNET` is required on each (`request.malformed` without).

- **`GET /ramp/pricing?network=`** → `{ network, mode, buySpreadBps, sellSpreadBps, manualBuyRate, manualSellRate, manualExpiresAt, maxDeviationBps, maxReferenceAgeHours, version, updatedBy, reason, updatedAt }`. `mode`: `REFERENCE_SPREAD` or `MANUAL`. Rates are KES per USDC as decimal strings; `updatedBy` (a staff id) and `reason` are null on the seed.
- **`PUT /ramp/pricing?network=`** `{ mode, buySpreadBps, sellSpreadBps, manualBuyRate?, manualSellRate?, manualExpiresAt?, maxDeviationBps, maxReferenceAgeHours, version, reason }` → the new version, in the GET's shape. `version` is the one read (left out: save over whatever is there); `manualExpiresAt` left out in `MANUAL` is 24 hours from now. Audit `ramp_pricing.update` (target `ramp_pricing`, the network); the line's detail is what changed, by name (`v1→v2; mode REFERENCE_SPREAD→MANUAL; manualBuyRate null→131.5`). Errors: `ops.reason_required`, `ramp.settings_invalid` (400, `details.fields` names the setting: a spread out of 0 to 2000, a buy rate below the sell rate, an expiry past or beyond 30 days, a manual rate outside `maxDeviationBps` of the reference, or no reference to check it against), `ramp.settings_changed` (409), `request.malformed` (a mode that isn't one, a rate that isn't a number, a time that isn't ISO-8601).
- **`GET /ramp/rates?network=`** → `{ network, mode, referenceRate, referenceAsOf, referenceAgeSeconds, referenceStale, maxReferenceAgeHours, maxDeviationBps, manualExpiresAt, buy: { rate, source, spreadBps, paused }, sell: { … }, at }`. `rate` is null when the side is paused, and `paused` says why (`NO_REFERENCE`, `REFERENCE_STALE`, `MANUAL_MISSING`, `MANUAL_EXPIRED`): quoting that side answers `ramp.pricing_unavailable` until staff act. `spreadBps` is the margin over the reference (for a manual rate, what it works out to).
- **Orders show how their rate was made:** the review list (RO4) gains `rate`, `rateSource`, `referenceRate`, `spreadBps`; a merchant's orders (RO8) gain `rateSource`, `referenceRate`, `spreadBps`. All null on orders from before 2026-11-24.
- **What the admin needs:** a "Rates" page under Ramp (built in circaso-admin the same day).

## Ramp: merchant security (decision 2026-11-24)

Through a fourth contract in `ramp:api`, `RampSecurityAdmin` (ramp.md → "As built: merchant security"), in `rampSecurityController`.

- **`GET /ramp/merchants/{id}/security`** (`ramp.read`) → `{ merchantId, locked, lockedAt, lockedByLink, lockedCancelled, unlockedAt, unlockedBy, unlockReason, orderEmails, dailySummary, passkeys: [{ passkeyId, label, createdAt, lastUsedAt, backedUp }], alerts: [{ alertId, kind, security, state, failure, createdAt, sentAt, linkUsedAt }] }` (the last 50 emails; never content). `ramp.merchant_not_found`.
- **`POST /ramp/merchants/{id}/unlock-withdrawals`** `{ reason }` (`ramp.manage`, ADMIN) → the same shape. Lifts a "This wasn't me" lock; cancelled withdrawals stay cancelled and frozen addresses stay frozen. Audit `ramp_merchant.withdrawals_unlock` (target `ramp_merchant`). Errors `ops.reason_required`, `ramp.not_locked` (409).
- **`GET` · `PUT /ramp/security-settings`** `{ merchantPasskeyRequired, version, reason }` (read `ramp.read`, change ADMIN) → `{ merchantPasskeyRequired, version, updatedBy, reason, updatedAt }`. Audit `ramp_security_settings.update` (new target `ramp_security_settings`). `ramp.settings_changed` on an old version.
- **What the admin needs:** on a merchant's page, a Security card (locked banner with Unlock and a reason, passkeys, recent emails); on Ramp settings, the passkey-required switch with a reason (built in circaso-admin the same day).

## Ramp: selling (decision 2026-11-23)

The off-ramp's staff side (ramp.md → "As built: the off-ramp"), on the same routes as "Ramp" with the same roles.

- **`POST /ramp/orders/{orderId}/resolve`** (RO4, ADMIN, `Idempotency-Key`) `{ resolution, receipt?, reason }` → `{ order: <the review list's order>, providerSaid }`. Only an order in `REVIEW`, in either direction. `resolution`: `MARK_PAID` (needs `receipt`, the M-Pesa receipt staff saw: a sale completes as a confirmed payout; a purchase goes to `PAID` and the ops server releases it), `REFUND` (a sale's USDC goes back to the user from the merchant's float, sent by the ops server; a purchase closes unreleased and its shillings go back by hand), `RECHECK` (queued: the app server, which alone holds the merchant's keys, asks the provider on its next sweep, within about ten seconds; `providerSaid` is `queued`, read the order again). Audit `ramp_order.resolve` on the new target `ramp_order`, the detail being the outcome asked for (never the receipt), the outcome line the state afterwards. Errors: `ops.reason_required`, `ramp.order_not_found` (404), `ramp.resolution_invalid` (409, naming what doesn't fit: not in review, the USDC never arrived, a refund already sent, a release already sent), `ramp.receipt_required` (400), `ramp.payout_may_be_paid` (409: the provider last said the sale's payout was paid), `request.malformed`. **Before a refund, check the merchant's M-Pesa statement or FinoQ dashboard**: a payout that went out and is then refunded pays the user twice; that is exactly why such a sale is in `REVIEW`.
- **The review list** (`GET /ramp/orders?state=REVIEW`) gains, per order, `direction`, `providerFeeMinor`, `fundedMinor` (the USDC the merchant's float received), `payoutAttempts`, `payoutOutcome` (`SENDING`, `ACCEPTED`, `UNKNOWN`, `NOT_SENT:…`, `FAILED:…`, `PAID`) and `hasReceipt`. A sale's `failureReason`s: `payout_mismatch`, `payout_not_found`, `payout_unconfirmed`, `payout_unresolved`, `receipt_reused`, `transfer_mismatch`, `wrong_token`, `funding_unconfirmed`, `refund_refused`, `refund_failed`, `refund_float_short`, `refund_unknown`.
- **Settings** gain selling's: `offRampState` (`OFF` | `PREVIEW` | `ON`, its own switch; `PREVIEW` uses the same list), `offRampMinMinor`, `offRampMaxMinor`, `offRampDailyLimitMinor` (KES cents the user receives, as strings), `transferWindowSeconds` (60 to 3600), `payoutWindowSeconds` (60 to 86400), `payoutFeeBands` (`[{ upToMinor, feeMinor }]`, cents as strings: the fallback payout fee table, used only when the provider's published one can't be read) and `payoutBalanceLowMinor` (the line under which a merchant's portal flags `payout_balance_low`). Always in the answer; a `PUT` that leaves one out keeps it. The audit line names each one that changes (the fee table by its number of bands, or "payoutFeeBands changed"). **The admin form needs these fields.**
- **Provider fees passed on (2026-11-24).** The M-Pesa providers' own fees are charged to the customer on both sides (PayHero's per-prompt fee on a purchase, FinoQ's payout fee on a sale). **Settings** gain `collectionFeeBands` (`[{ upToMinor, feeMinor }]`, cents as strings, the fallback for PayHero's prompt fee; left out on a `PUT`: unchanged; audited by size or "collectionFeeBands changed"); both fallback tables were seeded from the providers on 2026-11-24. **`GET /ramp/provider-fees?network=`** (FINANCE, ADMIN) → `{ network, provider, collection, payout, collectionFallback, payoutFallback }`: each provider table `{ source: LIVE|CACHED, asOf, ageSeconds, bands: [{ fromMinor, toMinor, feeFromMinor, feeToMinor }] }` or null when it can't be read, for the admin to show read-only beside the fallbacks (and copy into them). **Order reads:** the review list gains `providerFeeSource` (`LIVE`, `CACHED`, `STAFF_TABLE`) and `providerFeeActualMinor`; a merchant's orders gain `providerFee`, `providerFeeSource`, `providerFeeActual`. A purchase's provider fee is inside its `fiatAmountMinor`/`paid` (the total prompted). See ramp.md → "As built: provider fees passed on".
- **Insights**: the overview's totals and each merchant row gain `paidOut` (KES paid out to sellers) and `bought` (USDC received for them); `collected` and `sold` are buying's alone; the totals gain `refunded`. The summary's `orders` gains `refunded`, its `merchant` gains `offersOffRamp`, `payoutAccountId` and `payoutIssue`, and it gains `paidOut`, `bought` and `kesBalances` `{ payout, payoutSource, fees, feesSource, readAt, held }`: the merchant's KES at FinoQ **as the app server last read it** (the ops server holds nobody's keys), null amounts being unreadable, never zero. The merchant's orders list takes the sale states (`AWAITING_TRANSFER`, `FUNDED`, `PAYING_OUT`, `REFUNDING`, `REFUNDED`); for a sale `paid` is the KES paid out and `released` the USDC received.
- **What the admin needs:** on the review list, the direction and a "Resolve" action (mark paid with a receipt field, refund, re-check) with the reason; the selling settings on the Ramp settings form; on a merchant's page their FinoQ balances with the time they were read, their payout account and issue, and selling's money.

