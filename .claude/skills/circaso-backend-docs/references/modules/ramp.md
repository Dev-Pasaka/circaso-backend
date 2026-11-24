# Ramp module

Ramp answers "how do I turn shillings into USDC and back": it is an automated P2P merchant desk for USDC ↔ KES over M-Pesa. Vetted partner merchants supply the USDC float and their own M-Pesa collection account and earn commission; Circaso's merchant engine runs every trade, so a merchant does nothing per trade. Schema `ramp`, Redis prefix `rmp:`, stream `events:ramp`.

Status: **first slice built on `feat/ramp` (2026-11-22; see "As built" below), then staff visibility per merchant and the provider-refusal handling on `feat/ramp-merchant-insights` (same day), then identity verification with Didit on `feat/ramp-kyc` (same day; see "As built: identity verification"), then the merchant engine and verifying from the profile on `feat/ramp-engine` (2026-11-23; see "As built: the merchant engine" and "As built: verifying from the profile"), then the off-ramp on `feat/offramp` (2026-11-23, with FinoQ payouts; see "As built: the off-ramp"); the rest is design.** Earlier: draft, revised 2026-11-22 for `main`, for Pasaka's review. The first draft and a first on-ramp slice were written against an old branch (`feat/all-chains`, 2026-10-30) that predates wallet networks, settings in the database, the ops server and Circaso's own developer-controlled wallets. That code is parked (a stash) and is not on this branch. "How this fits main" below is what changes; the slice is ported only after Pasaka approves it. Items marked **Proposed** are choices this draft made that the agreed design did not settle; they are listed again under Open questions. Vendor details are from PayHero's docs (docs.payhero.co.ke, its removed pages from the web archive, and docs.payhero.africa), Circle's developer-controlled wallets reference, Didit's docs (docs.didit.me) and the Didit account as read on 2026-11-22; points the docs did not confirm are marked **unconfirmed** and listed under "To check against the vendor".

**This is the one module that moves money.** Everywhere else the backend prepares and the user signs (principle 7, principle 11). Ramp sends USDC on its own, but only from a partner merchant's developer-controlled wallet, and only against a payment it has verified itself (decision 2026-11-22). It never signs for a user's wallet: an off-ramp is still an ordinary W4 transfer signed with the user's PIN.

## How this fits main (revised 2026-11-22; approved by Pasaka the same day)

Four rules already decided on `main` reshape the first draft. Each is followed here. **Decided by Pasaka on 2026-11-22:** the release is signed in the ops process; merchant wallets are `SCA` with Gas Station paying the gas; merchants sign in to the portal with their email and a one-time code; the portal's first version is the essentials (sign up, connect PayHero under a PIN with a generated callback address, the float wallet to fund, online and offline, orders) with partner approval in the admin, and Didit KYC next; a merchant's PayHero credentials are the merchant's and are only ever entered on the portal. Items still marked **Proposed** below are the backend session's calls, for him to confirm.

**1. The environment holds only keys and secrets; every other setting lives in the database and is edited in the admin (decision 2026-11-18).**

- **Secrets only in the environment:** nothing new for Circle (`CIRCLE_API_KEY`, `CIRCLE_MAINNET_API_KEY`, `CIRCLE_ENTITY_SECRET`, `CIRCLE_MAINNET_ENTITY_SECRET` exist), `RAMP_CREDENTIALS_MASTER_KEY` (the envelope's master half, with the portal; the KYC identity key's own key is derived from it too), and Didit's four, named per Didit environment (decided 2026-11-22): `DIDIT_SANDBOX_API_KEY`, `DIDIT_SANDBOX_WEBHOOK_SECRET`, `DIDIT_LIVE_API_KEY`, `DIDIT_LIVE_WEBHOOK_SECRET`. Either pair may be absent: the server starts, and that environment is unavailable (`ramp.kyc_not_configured`). **A merchant's PayHero credentials are never in the environment and never supplied by Circaso** (Pasaka, 2026-11-22): they belong to the merchant, who enters them on the portal, and they exist only sealed in `merchant_credentials`.
- **Everything else is a row:** `ramp.settings`, one row per network (Data), seeded once from built-in defaults, read at most every 10 seconds per process as the wallet's network settings are, and edited by staff through the ops server (RO5). That covers the ramp's state (`OFF` | `PREVIEW` | `ON`), the chains it delivers on, the limits, the prompts per hour, the fee, the quote lifetime, the payment window, whether KYC is enforced, and the origin callback addresses are made on. None of the `RAMP_*` or `INTEGRATIONS_MERCHANT_CUSTODY_*` variables of the first draft exist.
- **The collections provider is a database setting too (decided by Pasaka, 2026-11-22; replaces the first version of this rule, which made it a start-up line).** There is no `INTEGRATIONS_COLLECTIONS_PROVIDER`. `ramp.settings.collections_provider` names it per network; staff switch it in the admin (RO5) at any time and it applies within the settings cache's 10 seconds, with no restart. Integrations binds every implementation at once behind `CollectionProviders` (`named(name)`, `names`) and the ramp names the one it wants on each call, so integrations never depends on the ramp. Real providers (`payhero` today) are always offered; `log`, which reports every prompt as paid, is offered only where the process runs in the local environment, and `fake` only where a test builds it. **This is the one place the ramp differs from the other `INTEGRATIONS_*_PROVIDER` start-up lines.** An order keeps the provider that prompted it (`orders.collections_provider`), so a switch mid-order never asks the wrong provider for its status; a merchant's sealed credentials name the provider they are an account at (`merchant_credentials.provider`), and a merchant whose account is at another provider than the network's current one is not a candidate and reconnects (RM5; RM2 `missing` says `payments`). The merchant float has no provider setting of its own: it uses the treasury (below), which follows the custody provider and the keys present.

**2. Everything about money carries `network` (`MAINNET` | `TESTNET`), and a new way in must set it (decision 2026-11-18).**

- A quote, an order, a reservation and a commission carry `network`, derived from the order's chain (`Network.ofChain`); a merchant has **one float wallet per network** (`merchant_wallets`), each in that network's Circle account.
- The app's ramp routes sit in the gateway's `walletNetwork { }` group (`X-Wallet-Network`), so the user's deposit address, the limits and the settings are that network's. Each job runs once per network; the PayHero callback takes the network from the order it names; a Circle update from the account it came through.
- **Shillings are real on both networks.** On the testnet a user pays real KES for test USDC, so the testnet ramp is for staff testing only: `PREVIEW` (people on an allow-list in `ramp.preview_access`) until it is switched `OFF` again. Mainnet starts `OFF`. (**Proposed.**)

**3. Only the ops process holds the entity secrets, and `TreasuryCustody` is the contract for wallets Circaso signs for (decision 2026-11-19).**

The first draft's `MerchantCustody` duplicated it and put the secret in the app. Revised:

- **No `MerchantCustody` contract.** A merchant's float wallet is a developer-controlled wallet in a wallet set of its own, created, read and sent from through the existing `TreasuryCustody` (`createWallets`, `balances`, `send`, `transaction`), with the existing `EntitySecret`.
- **The release is signed in the ops process (decided 2026-11-22).** The app verifies the payment and moves the order to `PAID`; an `OpsJob`, `ramp.release` (every 5 seconds, each network), sends `PAID` orders through `TreasuryCustody.send` with a key derived from the order and moves them to `RELEASING`. The internet-facing gateway never holds the secret that can move every merchant's float, which is the reason the secret is in the ops process at all. The cost is up to 5 seconds between a verified payment and the send. The alternative, giving the app process the entity secret, would be a new exception to the 2026-11-19 decision.
- **Confirmation** reaches the app through Circle's webhook as today (the notification names `custodyType: DEVELOPER`, which the wallet already uses to leave Circaso's own wallets alone): the ramp takes the ones whose `refId` is an order, completes the order and tells the user. `ramp.release` also re-reads each `RELEASING` order every 15 seconds, as fee withdrawals are followed, for a lost webhook.
- **Reading float in the app.** The engine needs a merchant's balance when it routes. Reading needs only the API key, which the app already has: a small read-only contract, `MerchantFloat.balance(network, walletId)`, bound in both processes (**Proposed**; `TreasuryCustody` stays ops-only).
- **Account type: SCA (decided 2026-11-22).** `TreasuryCustody.createWallets` makes EOA wallets, which pay their own gas: fine for a fee wallet staff top up, wrong for a merchant, who would have to keep ETH on Base for every release. Proposed: `TreasuryWalletRequest.accountType` (additive, default `EOA`), with merchant wallets created as `SCA` so Circle Gas Station sponsors the gas, as for user wallets. The alternative is EOA with a native balance the merchant funds and the engine checks.
- **Local runs and tests:** with the `log` or `fake` custody provider there is no treasury today (an open question on main). The ramp needs one: a `FakeTreasury` and a logging one in `dev`, bound by `treasuryModule` when the provider isn't `circle`.

**4. Admin is the ops server (decision 2026-11-08).** The ramp's staff routes (RO1–RO5) are in the `ops` module on the ops gateway, behind its roles, and call a staff contract in `ramp:api` (`RampAdmin`), as wallet's `FeeAdmin` does. Every write is audited (`ops.audit_log`).

**What does not change:** the rule that USDC is released only against a payment the ramp verified itself; pick and reserve in one transaction under the merchant's row lock; transition guards; `MobileCollections` and the unsigned PayHero callback as a doorbell; the KYC gate; the off-ramp waiting on a payout provider. `Wallets.depositAddress(userId, blockchain)` already exists on main, so the wallet needs no change for the on-ramp.

## Overview

### Owns

- **Merchants**: the partner's own merchant account (independent of any personal Circaso account the same person has), its KYC and approval state, its float wallet, its payment account, whether it is online and until when.
- **Ramp verification**: who has passed KYC for the ramp (users and merchants), as a status and a KYC identity key; never a document.
- **Merchant credentials**: each merchant's PayHero API username, password and channel id, encrypted at rest; plaintext exists only in one node's memory while that merchant is online.
- **Orders**: one on-ramp or off-ramp trade, from quote to its final state, with the merchant the engine picked.
- **Float reservations**: the part of a merchant's float promised to open orders (USDC), so two orders never spend the same float.
- **Commissions**: what each completed order earned its merchant (and Circaso, once the fee model is decided).
- **Ramp blocks**: users, and their KYC identities, barred from the ramp (M-Pesa reversals).
- **Withdrawals**: a merchant taking unreserved float out.
- Its realtime frames (`ramp/*`) and their push wording.

Circle stays the source of truth for a merchant's balance (principle 11); the M-Pesa side's source of truth is PayHero's status query, never a callback body.

### Who is who

| Party | Uses | Signs or holds |
| --- | --- | --- |
| User | The existing mobile app | Their own user-controlled wallet (PIN). Pays or receives M-Pesa on their verified number |
| Merchant | The web portal `merchants.circaso.com` only; no mobile app. A merchant account of its own, not their user account | Nothing per trade. Holds a PIN that unlocks their PayHero keys for a trading window |
| Circaso (ramp) | — | The Circle entity secret for the merchants' developer-controlled wallets; the master key half of the credential envelope |
| Ops | The admin portal (`ops` scope) | Approves and suspends merchants, resolves orders in `REVIEW`, lifts blocks |

### Gates before anyone trades

**Users (decided 2026-11-22, Pasaka):** a user passes Didit KYC **once**, and only before their first on- or off-ramp; the rest of the app stays KYC-free. Ramp checks the status on every quote and order and refuses with `ramp.kyc_required` until it is `VERIFIED`. Blocks are by user **and by KYC identity**, so a banned person can't come back on a new account: a second account that verifies with a blocked identity is refused at once.

**Merchants:** a merchant can't trade until **all** of: KYC approved (ID, liveness, face match and AML), approved by Circaso as a partner (ops), a float wallet created, a payment account stored and checked, and online inside a trading window.

**The ramp owns this gate (Proposed).** KYC is required for the ramp only, and a merchant is not an identity account, so ramp keeps the result itself (`verifications`) and is the one module that calls `KycProvider`. `identity.accounts.verification` (used by support recovery) is left alone; whether an approved ramp KYC should also set it is an Open question.

- **Flow.** R0 (users) or RM3 (merchants) creates a Didit session (`vendor_data` = `user:<id>` or `merchant:<id>`). The app starts Didit's native SDK with the session's `session_token` (the hosted `url` is the fallback); the merchant portal sends the browser to the hosted `url`, which returns to the portal. The result arrives at `/v1/hooks/didit`; ramp re-reads the decision from Didit before trusting `Approved`, then writes the status and the identity key. **Built: see "As built: identity verification".**
- **The KYC identity key** is `HMAC-SHA256(server key, issuing country | document type | document number)` of the approved document, like `phoneHmac`. No document, number, name or image is stored or put on a stream; Didit keeps the record.
- **One identity, one ramp user.** The identity key is unique among verified users: a second account presenting the same document is refused (`ramp.identity_in_use`), which is also what makes a block stick.
- **Workflows** (in the Didit sandbox application today; nothing was created or changed):

| Who | Proposed workflow | Checks | List price |
| --- | --- | --- | --- |
| Merchant | "ID, selfie and AML" (exists in sandbox, published) | ID document, liveness, face match, AML screening | $0.50 a session |
| User | "Basic KYC" (exists in sandbox, draft) | ID document, liveness, face match, device and IP analysis | $0.33 a session |

  Whether users are also AML-screened (the merchant workflow, $0.50) is part of the tiers question. The live application has only drafts and no webhook destination yet; both are set up with the Didit work, on Pasaka's go-ahead.
- **Tiered limits (Open question):** a lower limit on the basic check and a higher one after AML or proof of address; settings until decided.

### Fees: how the merchant and Circaso earn (decided 2026-11-22, Pasaka)

Circaso earns on every trade, and staff set the numbers in the admin.

- **Two settings per network, edited in the admin (RO5):** the **fee rate** the user pays on an on-ramp (`fee_bps`; 1 % to 2 % to start, never above 5 %: a `CHECK` of 0 to 500), and the **merchant's share** of that fee (`merchant_share_bps`, for example 7000 for 70/30). A change applies to quotes made after it; an order keeps the fee and the split of its quote.
- **The fee is inside what the user pays.** Paying 1 300 shillings at 2 % is a fee of 26 shillings; the user receives the USDC that 1 274 shillings buy at the quoted rate. The quote shows the fee.
- **The shillings all land in the merchant's till**, so the merchant already holds the whole fee in KES. Circaso's share therefore comes out of the merchant's **USDC float**: at 70/30, 7.80 of those 26 shillings, converted at the order's rate.
- **Circaso's ramp wallet:** one more developer-controlled wallet, Circaso's own, per network (`ramp.revenue_wallets`, created by the ops process in the merchants' wallet set under `refId` `ramp-revenue`; separate from the wallet's transfer-fee wallets, so ramp income is counted on its own). Staff withdraw from it as they do from the fee wallets (later).
- **Collected in one daily sweep (decided 2026-11-22, Pasaka).** Each completed order adds Circaso's share to what the merchant owes (`commissions.platform_asset_minor`), **held in the merchant's float** so it can't be traded or withdrawn. Once a day (`settings.fee_settle_hour_utc`, default 21, which is midnight in Nairobi) the ops job `ramp.settle-fees` sends each merchant's whole owed total to Circaso's wallet in one transfer, with an idempotency key per settlement, and marks the orders it covered. There is no threshold: every day's share is collected that day. A sweep that can't be sent (Circle down) is tried again on the job's next runs until it goes.
- **Transparency for the merchant (decided 2026-11-22, Pasaka).** A merchant sees exactly what they made and what Circaso took for providing the service:
  - **Overview (RM8)** for any period: orders completed, shillings collected, USDC sold, the fees charged, **their share** and **Circaso's share** (in shillings and in the USDC it was taken as), how much of Circaso's share has been swept and how much is still held, and each daily sweep with its amount and on-chain hash.
  - **Statement (RM15)** for a period: one line per exchange (when, the order, shillings in, USDC out, rate, fee, their share, Circaso's share, the sweep that collected it, the release's hash), then the sweeps, then the totals. The portal shows it and exports it (CSV, and a printable page).
  - Every number comes from the order and commission rows, so the overview, the statement and the admin's figures always agree.
- **Still open:** the same two numbers for the off-ramp when it exists; a fee or share per merchant (a rule that names a merchant, as the wallet's fee rules name a scope); a minimum fee in shillings.

### How an on-ramp runs

1. **Quote (R2).** The user asks for an amount. Ramp checks the gates, prices it (rate and fee: Open questions) and returns a quote good for 60 s. A quote reserves nothing.
2. **Order (R3).** The engine picks a merchant and reserves the USDC in one transaction (below). The order is `RESERVED`.
3. **STK push.** `MobileCollections.requestPayment` with that merchant's credentials sends the M-Pesa prompt to the user's verified number, `external_reference` = the order id. The order is `PAYMENT_REQUESTED`. The POST is never retried (PayHero documents no idempotency key).
4. **Callback.** PayHero posts to `/v1/hooks/payhero`. The callback is **unsigned**, so it is only a doorbell: its body decides nothing.
5. **Verify.** Ramp asks PayHero itself (`MobileCollections.paymentStatus`, by the reference PayHero returned for the STK push) with the merchant's credentials. The order becomes `PAID` only if PayHero says `SUCCESS` **and** the amount, the paying number and our order reference all match the order. Anything else that isn't a clean failure sends the order to `REVIEW`; nothing is released.
6. **Release.** The move to `PAID` is a transition guard, so exactly one caller wins it. The ops process's `ramp.release` job then sends USDC from the merchant's developer-controlled wallet to the user's Circaso wallet on the order's chain (`TreasuryCustody.send`, idempotency key derived from the order id, since Circle wants a UUID v4 and ours are v7; `refId` = the order id). The order is `RELEASING`. A repeat sends nothing twice: Circle answers a replayed key with the same transaction.
7. **Confirm.** Circle's webhook reports the outbound transaction; ramp finds the order by `refId` and moves it to `COMPLETED`, closes the reservation as `CONSUMED`, writes the commission row and `ramp.OnRampCompleted` in one transaction, then sends `order.updated`. The user's wallet module sees the same money arrive as an ordinary inbound transaction (`payment.received`).

**On-ramp states.** `RESERVED` → `PAYMENT_REQUESTED` → `PAID` → `RELEASING` → `COMPLETED`. Side exits: `EXPIRED` (no payment), `FAILED` (PayHero says the payment failed or was cancelled, or Circle failed the release), `CANCELLED` (R6, before payment), `REVIEW` (a person must look). States move only by transition guards, as wallet transfers do (`WHERE order_id = ? AND state IN (<allowed predecessors>) RETURNING …`).

| Next state | Allowed predecessors |
| --- | --- |
| `PAYMENT_REQUESTED` | `RESERVED` |
| `PAID` | `PAYMENT_REQUESTED`, `EXPIRED` (the user paid after all: the money is right) |
| `RELEASING` | `PAID` |
| `COMPLETED` | `RELEASING` |
| `EXPIRED` | `RESERVED`, `PAYMENT_REQUESTED` |
| `CANCELLED` | `RESERVED`, `PAYMENT_REQUESTED` (only once PayHero confirms nothing was paid) |
| `FAILED` | `RESERVED`, `PAYMENT_REQUESTED`, `RELEASING` |
| `REVIEW` | `PAYMENT_REQUESTED`, `EXPIRED`, `PAID`, `RELEASING` |

**Paid after expiry.** `ramp.expire-orders` gives the float back, so a late payment may find it gone. `EXPIRED` → `PAID` re-reserves inside the same transaction when the merchant's free float still covers it; when it doesn't, the order goes to `REVIEW` (the user is owed a refund or a release once the merchant tops up; how is under Open questions, with reversal liability).

**A release that fails.** `RELEASING` → `FAILED` means the user paid KES and got nothing: the order is flagged for `ramp.reconcile`, which retries the send with the same key while the merchant's balance allows, then moves it to `REVIEW` and alerts.

### How an off-ramp runs (designed, waiting on a payout provider)

**Built on 2026-11-23: see "As built: the off-ramp", which replaces this section and the semi-manual fallback below (dropped for now).** Earlier: **Status: the ramp side is not built. Superseded in part on 2026-11-23:** PayHero, now FinoQ, documents a payout (`POST /api/v2/withdraw`), and the integrations half (contract, client, callback, fees) is built and waiting on a first real payout: see "As built: payouts through FinoQ". What follows in this paragraph is the earlier reading. PayHero has no payouts today (Pasaka, 2026-11-22; **unverified until PayHero replies in writing**). Its current docs agree: no payout endpoint, and the old "withdraw to mobile" page is gone. Collections on a merchant's own till settle straight there, so PayHero holds nothing to pay out of either. The automated off-ramp therefore waits on a **payout provider** (Open questions): the merchant's own Daraja B2C, another aggregator, or PayHero if it brings payouts back. `MobileCollections.payout`, `payoutStatus` and `balance` stay in the contract for that provider; nothing implements them yet. The engine checks **no KES float** until then.

The designed flow, for whichever provider pays out:

1. Quote, gates and merchant choice as for an on-ramp. With an automated provider the engine will also need the merchant's free KES float there; that check is added with the provider.
2. R7 creates the order (`AWAITING_TRANSFER`) and returns the merchant's float address and the `rampOrderId`.
3. The app calls W4 to that address with `rampOrderId`. **The user signs it with their PIN like any transfer**; ramp never touches the user's wallet. An order whose transfer isn't confirmed in 15 minutes (W4's own expiry) ends `EXPIRED`.
4. Wallet publishes `wallet.TransferConfirmed` carrying `rampOrderId`. Ramp consumes it, checks the transfer (`Wallets.transfer`: sender, destination, amount, state) **and** that Circle shows the inbound transaction on the merchant's wallet, then moves the order to `FUNDED` and **holds the received USDC** with a reservation on the merchant's wallet, so it can't be withdrawn or traded away before the user is paid.
5. The payout: `MobileCollections.payout` to the user's verified number (`PAYING_OUT`); the provider's callback is a doorbell and ramp re-verifies with its status query before `COMPLETED`, which closes the hold as `CONSUMED` (the USDC becomes the merchant's float).
6. A payout that fails is retried by `ramp.reconcile`, **only after the status query says it did not pay**. When it can't be paid, the held USDC goes back to the user automatically from the merchant's wallet (`TreasuryCustody.send` in the ops process, key derived from the order id and `refund`), ending `REFUNDED`.

**Off-ramp states.** `AWAITING_TRANSFER` → `FUNDED` → `PAYING_OUT` → `COMPLETED`; `EXPIRED` (never funded), `REFUNDED`, `REVIEW`.

### Semi-manual payout (Proposed fallback until a payout provider exists)

A way to open selling without an automated payout: the merchant sends the M-Pesa by hand, and the ramp still holds the USDC until the user is paid. It breaks "merchants do nothing per trade" for off-ramps only, so it is a fallback, switched on per merchant (`payout_mode`: `MANUAL`), and it goes away for a merchant once a provider pays out for them.

1. Steps 1–4 above are unchanged: the user's signed transfer lands on the merchant's developer-controlled wallet and is **held** by a reservation. The merchant can't touch it; only the ramp can release or return it.
2. The order becomes `PAYOUT_DUE` with a deadline (`RAMP_MANUAL_PAYOUT_MINUTES`, 15). The merchant is told on the portal (and by SMS or email) what to send: the amount in shillings and the user's number, shown in full to that merchant for this order only.
3. The merchant sends M-Pesa from their own line and enters the M-Pesa receipt code on the portal (RM12). The order is `PAYOUT_CLAIMED`; the user gets `order.updated` and a push asking them to confirm.
4. The user confirms they received it (R8): `COMPLETED`, and the hold closes as `CONSUMED`. If the user says nothing for `RAMP_MANUAL_CONFIRM_HOURS` (24) after a claim, the order completes on its own, unless they disputed.
5. The user says they were not paid (R9): `REVIEW`. The USDC stays held; ops decides with the receipt code (release to the merchant or refund the user), audited.
6. The merchant never claims before the deadline, or declines (RM13): the held USDC is refunded to the user automatically, `REFUNDED`, and the miss counts against the merchant's success rate. Repeated misses take the merchant offline for selling.

Only merchants who are online, approved and in `MANUAL` mode are candidates, and one at a time per merchant up to a small cap (`RAMP_MANUAL_OPEN_PER_MERCHANT`), since a person is doing the work. The rule that matters holds: **the user's USDC is never released to the merchant until the user is paid, and comes back by itself if they are not.** What ramp can't do here is verify the M-Pesa itself; that is why the user confirms and ops arbitrates.

**Off-ramp states with a manual payout.** `AWAITING_TRANSFER` → `FUNDED` → `PAYOUT_DUE` → `PAYOUT_CLAIMED` → `COMPLETED`; `REFUNDED`, `REVIEW`.

### The merchant engine (routing)

**Built on 2026-11-23, and more than this section designed: eligibility, scoring, the weighted draw, failover, the settings and the routing log are in "As built: the merchant engine" below, which replaces the ranking paragraph here.** What still holds from this section: who is a candidate at all, and that pick and reserve are one transaction under the merchant's row lock with the custodian read outside it.

A merchant is a candidate for an order only if all of these hold:

- state `APPROVED` (KYC verified and partner-approved, not suspended);
- online: a live `rmp:session:{merchantId}` and `online_until` in the future, with at least the order's lifetime left in the window;
- a float wallet on the order's chain;
- **free float** ≥ the order amount, where free float = the float wallet's USDC balance as Circle reports it − the sum of its `OPEN` reservations. (An off-ramp needs no USDC float; it waits on a payout provider, above.)

**Ranking:** recent success rate (completed ÷ finished, over the merchant's last 50 finished orders), then the least recently assigned merchant as the tie-breaker, so equal merchants take turns. Splitting an order across merchants is later, not now.

**Pick and reserve are one transaction; reading Circle is not inside it.** A transaction holds database work only, so the order runs in two steps:

1. Outside any transaction, read each candidate's balance (`MerchantFloat.balance`, through `rmp:float:{merchantId}`, 10 s) and rank them.
2. `Transactor.attempt("orders.reserve")`: lock the chosen merchant's row (`SELECT … FOR UPDATE`, the parent before its children), re-check its state and `online_until`, sum its `OPEN` reservations, and insert the reservation and the order only if `balance − reserved ≥ amount`. Otherwise roll back and try the next candidate. No candidate left answers `ramp.no_merchant_available`.

The row lock serialises reservations per merchant, so two orders can't both fit into the last of a merchant's float. The balance was read a moment earlier; if it dropped since (a withdrawal can't: withdrawals take the same lock and need the merchant offline), Circle refuses the release with insufficient funds and the order goes to `REVIEW`.

### Merchant credentials and the trading session

**At rest (envelope).** A merchant's PayHero username, password and channel id are sealed with AES-256-GCM under a random data key. The data key is wrapped under a key derived from **two** secrets: the ramp master key (`RAMP_CREDENTIALS_MASTER_KEY`, from `Secrets`) and the merchant's PIN stretched with Argon2id (per-merchant salt). A database copy alone, or the master key alone, opens nothing. A wrong PIN fails the GCM tag; five wrong tries lock PIN entry for 30 minutes (`rmp:pin:{merchantId}`).

**Going online (RM6).** The merchant enters the PIN on the portal and a trading window (minutes). The node that took the request unwraps the keys **into its own memory only** and registers `rmp:session:{merchantId}` = its node id, TTL = the window. Plaintext credentials are never written to the database, to Redis, to a log or to an event; credential classes mask `toString`.

**Routing to the holder.** Only the holding node can call PayHero for that merchant (STK push, status query, payout). Another node that needs a call made pushes the **order id** (nothing else) onto `rmp:work:{nodeId}`; the holder reads it and acts on the order row, which is the state. Every such step is idempotent by the order's transition guards.

**A node that restarts loses its sessions:** its merchants go offline, their `rmp:session` keys are removed at start (and expire on their own), and each merchant is asked to unlock again. Orders of theirs that were waiting on PayHero can't be verified until then: they are **never** expired or released on a guess. They wait as they are, `ramp.reconcile` alerts, and the merchant's next unlock lets the status queries run.

**No development shortcut (Pasaka, 2026-11-22).** The first draft read one merchant's PayHero keys from `Secrets`. That is dropped: the credentials are the merchant's, not Circaso's, so even the first merchant (Crynce) signs up on the portal and enters them there. The envelope, the PIN and the trading session are therefore part of the first slice.

### Withdrawals

A merchant withdraws USDC float to any address valid on that chain, their personal Circaso wallet's address included (RM10): only unreserved funds, and only while offline with no open orders. The check and the `withdrawals` row take the merchant's row lock, the same lock reservations take. The send is `TreasuryCustody.send`, in the ops process, with a key derived from the withdrawal id. There is no shilling withdrawal: collections already sit in the merchant's own till.

### Reversals

A user who reverses an M-Pesa payment after USDC was released is blocked from the ramp, **as a user and as a KYC identity** (`blocks`). PayHero's API reports no reversal (no status, no callback), so a reversal is learned from the merchant (RM11, with the M-Pesa receipt) or from ops (RO3); who carries the loss is an Open question. Blocking is immediate on a report; ops can lift a block.

### Contract (`ramp:api`)

```kotlin
interface Ramp {                                     // read by the assistant later; nobody depends on it today
    suspend fun hasOpenOrder(userId: UserId): Boolean
}

@JvmInline value class RampOrderId(val value: UUID)  // UUIDv7, made on the server
@JvmInline value class MerchantId(val value: UUID)
```

- Frames: `order.updated` (`RampFrames.kt`), so the gateway words the pushes typed.
- Events on `events:ramp` (below).

### Integrations used

Two new contracts in `integrations:api`, and one more webhook vendor. **`StablecoinCustody` is not changed or reused:** it is the user-controlled contract, where nothing moves without the user's PIN, and it stays that way.

**Merchant float: `TreasuryCustody` (existing) and `MerchantFloat` (new, read-only).** See "How this fits main", rule 3. Nothing about Circle is new code beyond an account type on wallet creation and a balance read:

```kotlin
/** A merchant float wallet's USDC as the vendor holds it now. Needs the account's API key only; bound in both processes. */
interface MerchantFloat {
    suspend fun balance(network: Network, walletId: String): Resource<Money>
}
```

- **Create** (ops process, RO2 on approval or RM4 through the ops server): `TreasuryCustody.createWallets` in the merchants' wallet set (`ramp.settings.wallet_set_id`, created once per network by `ramp.release`'s start-up run), `refId` = the merchant id, `accountType` `SCA` (**Proposed**).
- **Send** (ops process only): `TreasuryCustody.send`, the idempotency key derived from the order id and `release` (Circle wants a UUID v4 and ours are v7), `refId` = the order id. An `Unavailable` answer means the outcome is unknown: the job asks again with the same key; any other error sends the order to `REVIEW`.
- **Follow:** Circle's webhook in the app (by `custodyType: DEVELOPER` and the order `refId`), and the ops job's re-read every 15 seconds.

**`MobileCollections`** — the architecture's planned `MobileMoney` slot, named for what it does. PayHero first (`PayHeroCollections` in `integrations/impl/.../payhero/` over `HttpClients.named("payhero")`, base `https://backend.payhero.co.ke`). **Credentials are passed per call**, because each merchant brings their own account: this is the one contract whose vendor keys don't come from `Secrets` (decision 2026-11-22). Nothing in it is PayHero's shape, so another provider is a new implementation.

```kotlin
interface MobileCollections {
    /** Sends the payment prompt (M-Pesa STK push) to [CollectionRequest.payer]. Never retried by the client. */
    suspend fun requestPayment(credentials: CollectionCredentials, request: CollectionRequest): Resource<CollectionReceipt>

    /** What the provider itself says about a payment: the only thing a release may rest on. */
    suspend fun paymentStatus(credentials: CollectionCredentials, providerReference: String): Resource<CollectionStatus>

    /** Pays out to a phone (off-ramp). Never retried by the client. No provider implements it yet: PayHero has no payouts. */
    suspend fun payout(credentials: CollectionCredentials, request: PayoutRequest): Resource<PayoutReceipt>

    suspend fun payoutStatus(credentials: CollectionCredentials, providerReference: String): Resource<PayoutStatus>

    /** The merchant's balances at the provider: what payouts can spend, and what the provider's fees are paid from. */
    suspend fun balance(credentials: CollectionCredentials): Resource<CollectionBalances>

    /** Whether the credentials work, for the portal's "connect your account" step. */
    suspend fun verify(credentials: CollectionCredentials): Resource<Unit>
}

class CollectionCredentials(
    val username: String,
    val password: String,
    val collectionChannelId: String,                 // the merchant's own till or paybill
) {
    override fun toString() = "CollectionCredentials(***)"
}
data class CollectionRequest(val payer: PhoneNumber, val amount: FiatAmount, val reference: String, val callbackUrl: String)
data class CollectionReceipt(val providerReference: String, val checkoutId: String)
data class CollectionStatus(
    val state: CollectionState,                      // QUEUED | SUCCESS | FAILED
    val amount: FiatAmount?,
    val payer: PhoneNumber?,
    val reference: String?,                          // ours, as the provider echoes it
    val receipt: String?,                            // the M-Pesa receipt number
)
data class PayoutRequest(val payee: PhoneNumber, val amount: FiatAmount, val reference: String, val callbackUrl: String)
data class CollectionBalances(val payout: FiatAmount, val fees: FiatAmount)

interface CollectionNotifications {                  // implemented by ramp, wired in app
    suspend fun onPaymentCallback(reference: String): Resource<Unit>   // the order id only: a doorbell
    suspend fun onPayoutCallback(reference: String): Resource<Unit>
}
```

- **Collect:** STK push `POST /api/v2/payments` (`amount` in whole shillings, `phone_number`, `channel_id`, `provider: "m-pesa"`, `external_reference`, `callback_url`) → `reference`, `CheckoutRequestID`, `status: QUEUED`. Status `GET /api/v2/transaction-status?reference=…` (PayHero's reference or the M-Pesa code) → `status` (`QUEUED`, `SUCCESS`, `FAILED`), amount, phone, references. Authentication is HTTP Basic from the merchant's API username and password. `channel_id` names one registered till, paybill or bank account, registered per PayHero account.
- **Where collections settle:** on a merchant's own till, paybill or bank channel, straight into it ("receive payments directly into your linked MPESA Paybill, Till, or bank account"). PayHero holds nothing, so there is no shilling balance to withdraw.
- **Two wallets at PayHero.** The **service wallet** is a prepaid float PayHero's per-transaction fees are taken from (KES 0–20 by amount; `GET /api/v2/wallets?wallet_type=service_wallet`, read as `available_balance`); it can't be cashed out. When it is empty PayHero refuses the STK push with `400 … merchant has insufficient balance to allow you complete this transaction` (seen in the first real test, 2026-11-22), so RM6 refuses to go online on a service wallet below a floor (`ramp.settings.provider_fee_floor_minor`, edited in the admin; see "As built", when the provider refuses the prompt). 
- **No payouts (superseded 2026-11-23, see "As built: payouts through FinoQ").** The earlier reading: PayHero has none today (Pasaka, 2026-11-22; unverified until PayHero replies in writing). An archived page describes `POST /api/v2/withdraw` from a "payments wallet", but it is gone from the current docs and no payout status query is documented. `PayHeroCollections` answers `payout` and `payoutStatus` with `IntegrationError.NotImplemented`; `balance` answers the service wallet as `fees` (and zero for `payout`).
- **Each merchant has a callback address of their own (Pasaka, 2026-11-22).** The portal generates it when the merchant connects PayHero (RM5): `<callback_base_url>/v1/hooks/payhero/<name>-<token>`, the merchant's name as a readable slug and 16 random base62 characters (`merchants.callback_key`, unique). The merchant pastes it into PayHero, and the ramp sends the same address with every prompt it makes for them. The token is a shared secret in the address, which is the best guard a vendor that signs nothing allows: a callback on an address that isn't a merchant's is refused, and one that is may only ring for **that merchant's** orders. The merchant can generate a new one (RM14); the old one stops working at once. This needs the gateway's webhook path to take one more segment, `/v1/hooks/{vendor}/{key}` (additive; `WebhookVendor` is handed the key).
- **The callbacks are unsigned** (as documented; see "The webhook signing secret" below: FinoQ's dashboard does show a signing secret, which the merchant may enter on the portal since 2026-11-23, but no scheme is documented). PayHero documents no signature, secret, source address list or retry policy. `PayHeroWebhook` therefore takes exactly one thing from the body, `ExternalReference`, and hands it to `CollectionNotifications`; ramp ignores it unless it names an order in a state that is waiting for it, and then runs the status query. A forged callback can at most cause one status query for a real open order. The gateway's duplicate guard uses `CheckoutRequestID`.
- **No idempotency.** No idempotency header, and `external_reference` isn't documented as unique. A prompt or payout is never retried by the HTTP client; after a timeout the status query decides.
- **No sandbox** is documented: tests against PayHero are small real amounts; everything else runs on `fake`.
- **`fiat`:** `FiatAmount(amountMinor: Long, currency: String)` is new in `shared` (`Money` is crypto-only: it carries an `Asset`). PayHero takes whole shillings, so quotes are priced in whole shillings.
- **PayHero's abuse limits** shape ours: more than 10 failed or cancelled prompts in a row to one number blocks that number for 24 h, and many failed requests restrict the merchant's account for hours. Ramp limits prompts per user (below) so a user can't burn a merchant's account.

| Setting | Example | Purpose |
| --- | --- | --- |
| `ramp.settings.collections_provider` (database, per network) | `payhero` (a server); `log` (seeded and offered on a local run only) | Who sends the M-Pesa prompts; switched in the admin with no restart. `fake` exists only where a test builds it |
| `ramp.settings.callback_base_url` (database) | `https://api.circaso.com` | The public origin callback addresses are made on; edited in the admin |
| A merchant's callback address (generated, RM5) | `https://api.circaso.com/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s` | The merchant's own: shown on the portal to paste into PayHero, and sent with every prompt of theirs |

**`KycProvider`** — the other planned slot. Didit first (`DiditKyc` in `integrations/impl/.../didit/` over `HttpClients.named("didit")`, base `https://verification.didit.me`, `x-api-key` from `Secrets`).

```kotlin
interface KycProvider {
    /** A hosted check for [subject] (our own reference); the person completes it at [KycSession.url]. */
    suspend fun startSession(subject: String, workflow: KycWorkflow): Resource<KycSession>

    /** The provider's decision as it stands now: what a status change is checked against. */
    suspend fun decision(sessionId: String): Resource<KycDecision>
}

enum class KycWorkflow { USER, MERCHANT }            // mapped to provider workflow ids in settings
data class KycSession(val sessionId: String, val url: String)
data class KycDecision(
    val status: KycStatus,                           // PENDING | APPROVED | DECLINED | IN_REVIEW | EXPIRED
    val subject: String,
    val document: KycDocument?,                      // country, type, number: for the identity key only, never stored
)

interface KycNotifications {                         // implemented by ramp, wired in app
    suspend fun onStatusChanged(sessionId: String): Resource<Unit>   // the session id only; ramp re-reads the decision
}
```

- **Didit calls:** `POST /v3/session/` (`workflow_id`, `vendor_data`, `callback`) → `session_id`, `url`, `status: "Not Started"`; `GET /v3/session/{id}/decision/`.
- **Webhook** `/v1/hooks/didit`: HMAC-SHA256 with the destination's secret. `DiditWebhook` verifies `X-Signature-V2` (over the canonical JSON; `X-Signature` over the raw bytes as the fallback; never `X-Signature-Simple`, which doesn't cover the decision), refuses an `X-Timestamp` older than 300 s, and uses `event_id` as the duplicate guard. Statuses are exact strings with spaces (`Approved`, `Declined`, `In Review`, `Kyc Expired`, …).
- **Sandbox and live** are two Didit applications with separate keys: sandbox mocks every check and bills nothing; live bills per finished feature.

| Setting | Example | Purpose |
| --- | --- | --- |
| `ramp.settings.kyc_provider` (database, per network) | `didit` (a server); `log` (seeded and offered on a local run only) | Who runs the checks; switched in the admin with no restart, as the collections provider is. There is no `INTEGRATIONS_KYC_PROVIDER` line. `fake` exists only where a test builds it |
| `ramp.settings.kyc_environment` (database) | `SANDBOX` (TESTNET) \| `LIVE` (MAINNET) | Which Didit application a network's checks run in, and whose approvals it trusts |
| `DIDIT_SANDBOX_API_KEY`, `DIDIT_SANDBOX_WEBHOOK_SECRET`, `DIDIT_LIVE_API_KEY`, `DIDIT_LIVE_WEBHOOK_SECRET` (secrets) | — | Per Didit application; both can be set at once, either may be absent |
| `ramp.settings.kyc_workflow_user`, `kyc_workflow_merchant` (database) | workflow ids | Which Didit workflow each kind runs; edited in the admin; empty: that kind can't be verified on the network yet |
| `ramp.settings.merchant_portal_url`, `kyc_attempts_per_day`, `kyc_pending_hours` (database) | `https://merchants.circaso.com`, 3, 24 | The hosted flow's way back to the portal; the daily cap on new checks; how long a started check may stay unfinished |

The `log` providers print what they were asked and answer with made-up references; `fake` records calls, and lets a test settle a payment or a transaction by hand.

### Depends on

`shared`, `infrastructure:api`, `integrations:api`, `identity:api` (account status, the verified phone check) and `wallet:api` (a user's deposit address on a chain; `TransferConfirmed` and `Wallets.transfer` for the off-ramp). **Wallet never depends on ramp:** `rampOrderId` on W4 and on `TransferConfirmed` is an opaque id to the wallet.

### Changes to other modules (all additive)

| Where | Change | Needed for |
| --- | --- | --- |
| `wallet:api` | Nothing: `Wallets.depositAddress(userId, blockchain)` exists on main | On-ramp release |
| `integrations:api` `TreasuryWalletRequest` | `accountType` (default `EOA`; `SCA` for merchant float) (**Proposed**) | Gas for releases |
| `integrations` `dev` | A fake and a logging treasury, bound by `treasuryModule` when custody isn't `circle` | Local runs and tests |
| `ops` | RO1–RO5 on the ops gateway, calling `ramp:api` `RampAdmin`; the `ramp.release` `OpsJob` | Staff routes; signing |
| gateway | The ramp's app routes inside `walletNetwork { }`; `/v1/hooks/{vendor}/{key}` | The network of a request; a callback address per merchant |
| `wallet` W4, `TransferConfirmed`, `TransferSummary` | Optional `rampOrderId`; the wallet stores and echoes it, and checks nothing about it | Off-ramp |
| `integrations:api` | `MerchantFloat`, `MobileCollections`, `CollectionNotifications`, `KycProvider`, `KycNotifications` | — |
| `shared` | `FiatAmount`; `Scope.MERCHANT` (**Proposed**, with merchant accounts and portal sign-in) | Quotes; the portal |
| gateway | Webhook vendors `payhero`, `didit`; push wording for `ramp/*`; docs tags "Ramp", "Merchant portal" | — |

## Endpoints

Paths relative to `https://api.circaso.com/v1`. Every POST, PUT and DELETE takes an `Idempotency-Key`. Amounts follow the wallet: strings, `amountMinor` (exact) and `amount` (display). No response ever names a merchant to a user beyond a display name on the order, and none carries a credential.

### Mobile app (`app` scope)

| # | Route | Use case (domain) | Does | First slice |
| --- | --- | --- | --- | --- |
| R0 | `POST /ramp/kyc` · `GET /ramp/kyc` | `StartUserKyc` · `GetUserKyc` | Starts (or returns the open) Didit check for this user: `{ status, required, canStart, reason, retryAt, submitted, session: { sessionToken, url, expiresAt }? }`. Already `VERIFIED` answers so with no session. The `GET` reads the state, asking Didit while a check is unfinished. **Not gated by the ramp's state (2026-11-23): see "As built: verifying from the profile"** | Built 2026-11-22 |
| R1 | `GET /ramp` | `GetRampStatus` | Whether this user can trade now: KYC status, blocked or not, the limits, the chains offered, whether any merchant is online. Never a list of merchants | Yes |
| R2 | `POST /ramp/quotes` | `CreateQuote` | Prices an amount in one direction; a quote lasts 60 s and reserves nothing | Yes (on-ramp) |
| R3 | `POST /ramp/onramp/orders` | `StartOnRamp` | Picks a merchant, reserves float, sends the M-Pesa prompt | Yes |
| R4 | `GET /ramp/orders/{orderId}` | `GetOrder` | One order and its state | Yes |
| R5 | `GET /ramp/orders?cursor=&limit=` | `ListOrders` | The user's orders, newest first, keyset paged | Yes |
| R6 | `POST /ramp/orders/{orderId}/cancel` | `CancelOrder` | Cancels before payment; refused once PayHero shows a payment | Yes |
| R7 | `POST /ramp/offramp/orders` | `StartOffRamp` | Picks a merchant and returns the address and `rampOrderId` for the user's W4 | Built 2026-11-23 |
| R8 | `POST /ramp/orders/{orderId}/received` | `ConfirmPayout` | Manual payout: the user confirms the M-Pesa arrived | With the manual fallback |
| R9 | `POST /ramp/orders/{orderId}/dispute` | `DisputePayout` | Manual payout: the user says they were not paid; the order goes to `REVIEW` | With the manual fallback |

```json
// R2 POST /ramp/quotes ← request
{ "direction": "ON_RAMP", "blockchain": "BASE", "fiat": { "currency": "KES", "amount": "1300" } }   // or "crypto": { "asset": "USDC", "amount": "10" }
// → data
{ "quoteId": "0193…", "direction": "ON_RAMP", "blockchain": "BASE",
  "pay":     { "currency": "KES", "amountMinor": "130000", "amount": "1300" },
  "receive": { "asset": "USDC", "amountMinor": "9950000", "amount": "9.95" },
  "rate": "129.25", "fee": { "currency": "KES", "amountMinor": "1400", "amount": "14" },
  "expiresAt": "2026-11-22T18:01:00Z" }

// R3 POST /ramp/onramp/orders ← request
{ "quoteId": "0193…", "phone": "+254712345678" }
// → data
{ "orderId": "0193…", "direction": "ON_RAMP", "state": "PAYMENT_REQUESTED", "blockchain": "BASE",
  "pay": { … }, "receive": { … }, "maskedPhone": "+254 7•• ••• 678",
  "merchantName": "Crynce", "expiresAt": "2026-11-22T18:03:00Z" }
```

**When the provider refuses the prompt (2026-11-22):** the order is closed and its float freed as before, then the next candidate merchant is asked with a new order for the same quote; `ramp.merchant_unavailable` (409) when nobody is left. `ramp.collections_unavailable` (502) is only the provider out of reach. See "As built". **Since 2026-11-23** the engine gives the whole failover order at once, the settings' `maxAttempts` bounds it, and a provider that is out of reach is handled by whether a prompt can be on the phone: see "As built: the merchant engine" → Failover.

R3's checks, in order: account `ACTIVE`; KYC `VERIFIED` (`ramp.kyc_required`); not blocked, by user or identity key (`ramp.blocked`); within the limits of the user's KYC tier; the quote exists, is this user's and hasn't expired (`ramp.quote_expired`); within the per-order and daily limits (`ramp.limit_exceeded`); no other open order (`ramp.order_in_progress`: one at a time per user); **the phone is the account's own verified number** (`AccountDirectory.resolvePhones`; else `ramp.phone_not_yours`), so the payer PayHero reports can be matched to the user; the user has a wallet that receives on the chain (`ramp.wallet_required`); a merchant with the float (`ramp.no_merchant_available`). The order and reservation are written before PayHero is asked; when the prompt can't be sent the order ends `FAILED` and the float is given back in the same transaction.

Limits are settings until decided (`RAMP_ORDER_MIN_KES`, `RAMP_ORDER_MAX_KES`, `RAMP_DAILY_LIMIT_KES`, `RAMP_PROMPTS_PER_HOUR`; `TODO(decision)`), and the price comes from a `Pricing` port (`ExchangeRates` plus `RAMP_FEE_BPS`) until the fee model is decided.

### Merchant portal (`/v1/ramp/merchant`, a merchant session; first slice: MA1–MA3, RM1, RM2, RM5, RM6, RM7, RM8, RM9, RM14, RM15)

**Sign-in (decided 2026-11-22): email and a one-time code.** A merchant account is the ramp's own, not an identity account, so its session is the ramp's too and never an app token.

- The session travels in **`X-Merchant-Session`**, not `Authorization` (the gateway reads a Bearer token as an app token). It is 32 random bytes, base64url; only its SHA-256 is stored (`merchant_sessions`); it lasts 12 hours and each use within the last hour extends it.
- Every state change carries `Idempotency-Key`; money routes carry `X-Wallet-Network`. Answers use the usual envelope.
- The portal reaches the API through its own host (`merchants.circaso.com/api/...`, forwarded by its Cloudflare worker, as the admin portal does), so the app gateway needs no CORS.
- A code is 6 digits, lasts 10 minutes, allows 5 tries; 3 codes per email per 10 minutes and 10 per address per hour. Asking for a code always answers the same, whether or not the email is known. The first verified sign-in creates the merchant account (`NEW`).

| # | Route | Does |
| --- | --- | --- |
| MA1 | `POST /ramp/merchant/auth/code` | `{ email }` → `{ sent: true, retryAfterSeconds }`. Emails the code (`EmailTemplate.MERCHANT_SIGN_IN_CODE`) |
| MA2 | `POST /ramp/merchant/auth/verify` | `{ email, code }` → `{ sessionToken, expiresAt, merchant }` |
| MA3 | `POST /ramp/merchant/auth/signout` | Ends the session; takes the merchant offline |

```json
// RM2 GET /ramp/merchant → data  (also the `merchant` of MA2 and the answer of RM1)
{
  "merchantId": "0193…", "email": "pay@crynce.co.ke", "displayName": "Crynce", "contactPhone": "+254712345678",
  "state": "APPROVED",                                   // NEW | APPLIED | APPROVED | SUSPENDED
  "network": "TESTNET",
  "steps": { "profile": true, "approved": true, "wallet": true, "payments": true },
  "wallet": { "blockchain": "BASE-SEPOLIA", "address": "0x5a3c…" },          // null until the ops server has made it
  "float": { "balance": { "unit": "USDC", "amountMinor": "100000000", "amount": "100" },
             "reserved": { … }, "free": { … } },                               // null without a wallet or when Circle is down
  "payments": { "provider": "payhero", "connected": true, "channelId": "911",
                "callbackUrl": "https://api.circaso.com/v1/hooks/payhero/crynce-9fQ2xLr7Tk3mVb8s" },   // null until RM5
  "online": { "online": true, "until": "2026-11-22T22:00:00Z",     // the first two fields as before; the rest 2026-11-23
              "state": "ONLINE", "openEnded": false, "keysHeld": true, "since": "2026-11-22T18:00:00Z",
              "offlineReason": null, "offlineAt": null },          // see "As built: online until stopped"
  "canGoOnline": true, "missing": [],                    // e.g. ["approval", "wallet", "payments", "float", "provider_balance"]
  "paymentsIssue": null,                                 // { "code": "SERVICE_BALANCE", "since": "2026-11-22T18:04:10Z" }: "Top up your PayHero service wallet"
  "webhookSecretSet": true,                              // RM5c; the secret itself is never sent back
  "webhookSecretUpdatedAt": "2026-11-23T10:15:00Z"       // null when none is set
}
// RM1 PUT /ramp/merchant/profile ← { "displayName": "Crynce", "contactPhone": "+254712345678" }   (NEW → APPLIED)
// RM5 PUT /ramp/merchant/payments ← { "username": "…", "password": "…", "channelId": "911", "pin": "482913" }
//     → { "connected": true, "callbackUrl": "…" }       the PIN is 6 digits; set here the first time, required after
// RM14 POST /ramp/merchant/payments/callback → { "callbackUrl": "…" }
// RM5c PUT /ramp/merchant/payments/webhook-secret ← { "secret": "…" } → RM2      (8–512 visible characters, no spaces)
//      DELETE /ramp/merchant/payments/webhook-secret → RM2
// RM6 POST /ramp/merchant/session ← { "pin": "482913", "minutes": 240 }       → RM2's `online` (until "…")
//                                  ← { "pin": "482913", "untilStopped": true } → RM2's `online` (openEnded, until null)
//     DELETE /ramp/merchant/session → { "online": false, "until": null, "state": "OFFLINE", …, "offlineReason": "STOPPED" }
// RM9 PUT /ramp/merchant/pin ← { "currentPin": "482913", "newPin": "771204" } → {}
// RM7 GET /ramp/merchant/orders?cursor=&limit= → { "orders": [ { "orderId", "direction", "state", "pay", "receive",
//       "fee", "maskedPhone", "createdAt", "updatedAt" } ], "next": null }
```

```json
// RM8 GET /ramp/merchant/earnings?from=2026-11-01&to=2026-11-30 → data   (dates are UTC days, inclusive; default: this month)
{ "from": "2026-11-01", "to": "2026-11-30", "network": "TESTNET",
  "orders": 42,
  "collected":      { "unit": "KES",  "amountMinor": "5460000", "amount": "54600" },   // shillings paid into your till
  "sold":           { "unit": "USDC", "amountMinor": "415800000", "amount": "415.8" }, // USDC released to buyers
  "fees":           { "unit": "KES",  "amountMinor": "109200", "amount": "1092" },      // the fee buyers paid, inside `collected`
  "yourShare":      { "unit": "KES",  "amountMinor": "76440", "amount": "764.4" },
  "circasoShare":   { "unit": "KES",  "amountMinor": "32760", "amount": "327.6" },
  "circasoShareUsdc": { "unit": "USDC", "amountMinor": "2520000", "amount": "2.52" },   // what it is taken as, from your float
  "swept":          { "unit": "USDC", "amountMinor": "2400000", "amount": "2.4" },
  "held":           { "unit": "USDC", "amountMinor": "120000", "amount": "0.12" },      // collected at the next daily sweep
  "feeRatePercent": "2", "yourSharePercent": "70",                                      // the rates in force now
  "sweeps": [ { "settlementId": "0193…", "day": "2026-11-21", "amount": { "unit": "USDC", … }, "state": "CONFIRMED",
                "orders": 9, "txHash": "0x…" } ] }

// RM15 GET /ramp/merchant/statement?from=&to=&cursor=&limit= → data   (newest first, keyset paged; totals are for the whole period)
{ "from": "…", "to": "…", "network": "TESTNET",
  "lines": [ { "orderId": "0193…", "completedAt": "2026-11-21T18:04:10Z", "maskedPhone": "+254 7•• ••• 678",
               "paid": { "unit": "KES", … }, "released": { "unit": "USDC", … }, "rate": "129.25",
               "fee": { "unit": "KES", … }, "yourShare": { "unit": "KES", … }, "circasoShare": { "unit": "KES", … },
               "circasoShareUsdc": { "unit": "USDC", … }, "settlementId": "0193…", "txHash": "0x…" } ],
  "totals": { "orders": 42, "collected": { … }, "sold": { … }, "fees": { … }, "yourShare": { … },
              "circasoShare": { … }, "circasoShareUsdc": { … } },
  "next": null }
```

**The float wallet has no route of its own.** Creating it takes the entity secret, which only the ops process holds: `ramp.release` makes an approved merchant's wallet on its next run (seconds), and RM2 then shows the address to fund.

Merchant errors: `ramp.session_invalid` (401), `ramp.code_invalid` (400), `ramp.code_locked` (429), and the `ramp.merchant_*`, `ramp.pin_*` and `ramp.credentials_rejected` codes below.

| # | Route | Use case | Does |
| --- | --- | --- | --- |
| RM1 | `PUT /ramp/merchant/profile` | `ApplyAsMerchant` | The merchant's name and contact phone; applies as a partner (`NEW` → `APPLIED`) |
| RM2 | `GET /ramp/merchant` | `GetMerchant` | State and what is still missing, USDC float (balance, reserved, free), and while online the PayHero service wallet balance; online or not and until when |
| RM3 | `POST /ramp/merchant/kyc` | `StartMerchantKyc` | The Didit session link for the merchant (ID, liveness, face match, AML): the hosted `url`, which returns to `<merchant_portal_url>/kyc/done`. Built 2026-11-22 |
| RM5 | `PUT /ramp/merchant/payments` | `StorePaymentAccount` | PayHero username, password, the till or paybill channel id, and the PIN that seals them; answers the merchant's callback address to paste into PayHero; checked with `MobileCollections.verify` first |
| RM6 | `POST /ramp/merchant/session` / `DELETE` | `GoOnline` / `GoOffline` | PIN and a window in minutes, or online until stopped (2026-11-23); unlocks the keys on this node. Going offline stops new orders; open ones finish |
| RM7 | `GET /ramp/merchant/orders?cursor=` | `ListMerchantOrders` | The merchant's orders, payer numbers masked |
| RM8 | `GET /ramp/merchant/earnings?from=&to=` | `GetEarnings` | The overview for a period: orders, shillings in, USDC sold, fees, the merchant's share, Circaso's share (swept and still held), and the daily sweeps |
| RM15 | `GET /ramp/merchant/statement?from=&to=&cursor=` | `GetStatement` | Every exchange in the period, line by line, with the sweeps and the totals; the portal shows and exports it |
| RM9 | `PUT /ramp/merchant/pin` | `ChangePin` | Re-wraps the data key under the new PIN (old PIN required) |
| RM10 | `POST /ramp/merchant/withdrawals` | `WithdrawFloat` | Unreserved USDC float to an address (their Circaso wallet's or any other); offline and no open orders |
| RM12 | `POST /ramp/merchant/orders/{orderId}/payout` | `ClaimPayout` | Manual payout: the merchant enters the M-Pesa receipt code after sending | 
| RM13 | `POST /ramp/merchant/orders/{orderId}/decline` | `DeclinePayout` | Manual payout: the merchant can't pay; the held USDC is refunded |
| RM14 | `POST /ramp/merchant/payments/callback` | `RotateCallback` | Generates a new callback address; the old one stops working |
| RM5c | `PUT /ramp/merchant/payments/webhook-secret` / `DELETE` | `SetWebhookSecret` | The FinoQ webhook signing secret from the merchant's dashboard: set or replace `{ secret }`, or remove; no PIN; answers RM2 (`webhookSecretSet`, `webhookSecretUpdatedAt`, never the value). Built 2026-11-23 |
| RM11 | `POST /ramp/merchant/orders/{orderId}/reversal` | `ReportReversal` | Reports an M-Pesa reversal with its receipt; blocks the user and their KYC identity, opens a case for ops |
| RM16 | `GET /ramp/merchant/performance` | `GetMerchantPerformance` | The merchant's **own** ranking signals per direction, in plain terms: completion and failure rate, median time against the target and the SLA, trades, whether the newcomer boost applies, their order limit. Never another merchant's. Built 2026-11-23 |
| RM17 | `PUT /ramp/merchant/offers` | `SetMerchantOffers` | The merchant's own switches: `{ onRamp?, offRamp? }` (one left out stays as it is); answers RM2. Built 2026-11-23 |
| RM19 | `POST /ramp/merchant/topups` · `GET /ramp/merchant/topups` · `GET /ramp/merchant/topups/{topupId}` | `StartTopUp` / `ListTopUps` / `GetTopUp` | An M-Pesa prompt to the merchant's own contact phone to top up their FinoQ service or payment wallet; offline with the PIN. Built 2026-11-23: see "As built: merchant top-ups" |

### Ops (on the ops gateway, `/v1/ops/ramp`, through `ramp:api` `RampAdmin`)

| # | Route | Does |
| --- | --- | --- |
| RO1 | `GET /ops/ramp/merchants` | Merchants and their states |
| RO2 | `POST /ops/ramp/merchants/{id}/approve` · `/suspend` | Partner approval; suspension takes the merchant offline at once |
| RO3 | `GET` · `POST /ops/ramp/blocks` · `DELETE /ops/ramp/blocks/{userId}` | The block list; block or unblock a user (and identity). Built 2026-11-22, with `GET /ops/ramp/users/{userId}/verification` and the two verification resets (see "As built: identity verification") |
| RO4 | `GET /ops/ramp/orders?state=REVIEW` · `POST /ops/ramp/orders/{id}/resolve` | Orders waiting for a person, and their resolution (mark paid, refund, re-check) with a reason, audited. Resolve built 2026-11-23, both directions |
| RO5 | `GET` · `PUT /ops/ramp/settings?network=` | One network's ramp settings (state, chains, limits, fee, windows, KYC enforcement, callback origin), with a version check; audited; in force within 10 seconds |

| RO6 | `GET /ops/ramp/overview?network=&from=&to=` | What each merchant did in a period, and the network's totals (the sum of the rows) |
| RO7 | `GET /ops/ramp/merchants/{id}/summary?network=&from=&to=` | One merchant: account, float, orders by how they ended, the money, each day, the sweeps |
| RO8 | `GET /ops/ramp/merchants/{id}/orders?network=&from=&to=&state=&cursor=&limit=` | The merchant's orders, newest first, keyset paged, the payer masked |
| RO9 | `GET` · `PUT /ops/ramp/engine-settings?network=&direction=` | The merchant engine's settings for one network and direction: a versioned document, version-checked, audited with a reason kept on the version (2026-11-23) |
| RO10 | `POST /ops/ramp/engine-settings/simulate?network=&direction=` | What a draft of the settings would do to the last real orders (or one amount): each merchant's share, with nothing written |
| RO11 | `GET /ops/ramp/routing?network=&merchantId=&orderId=&from=&to=&cursor=&limit=` | Routing decisions, newest first |
| RO12 | `GET /ops/ramp/routing/{decisionId}` | One routing in full: why this merchant |
| RO13 | `PUT /ops/ramp/merchants/{id}/tier` | A merchant's type (`INDIVIDUAL` \| `BUSINESS`) and own order limit, audited |
| RO14 | `GET /ops/ramp/merchants/{id}/signals?network=` | What the engine scores a merchant on, per direction |
| RO15 | `GET /ops/ramp/pricing?network=` | One network's pricing settings: a spread on each side of the reference, or manual rates with an expiry (2026-11-24, "As built: rates") |
| RO16 | `PUT /ops/ramp/pricing?network=` | Changes them, version-checked, audited with a reason |
| RO17 | `GET /ops/ramp/rates?network=` | The buy and sell rates in force now (or why each is paused), the reference and its age |

Every money read takes `?network=`, as the other ops routes do. RO6–RO8 are described under "As built" (staff visibility per merchant).

There are no agent routes in the first slice. When Asha may read the ramp, `GET /v1/agent/ramp/orders` only.

## Data

PostgreSQL tables in schema `ramp` (role `ramp`, Flyway location `db/ramp`), and Redis keys under `rmp:`.

```sql
-- V1__ramp.sql (first slice: merchants, quotes, orders, reservations, commissions, realtime, outbox);
-- verifications and blocks arrived with Didit (V4__kyc.sql, shown below as built); withdrawals arrive with the off-ramp

CREATE TABLE ramp.settings (                             -- one row per network; edited in the admin (RO5), seeded once
    network                text        PRIMARY KEY,      -- MAINNET | TESTNET
    state                  text        NOT NULL,         -- OFF | PREVIEW | ON (built-in seed: OFF)
    chains                 text[]      NOT NULL,         -- where bought USDC is delivered (BASE-SEPOLIA; BASE)
    order_min_minor        bigint      NOT NULL,         -- KES minor units. TODO(decision: KYC tiers and limits)
    order_max_minor        bigint      NOT NULL,
    daily_limit_minor      bigint      NOT NULL,
    prompts_per_hour       integer     NOT NULL,
    fee_bps                integer     NOT NULL CHECK (fee_bps BETWEEN 0 AND 500),          -- what the user pays on an on-ramp
    merchant_share_bps     integer     NOT NULL CHECK (merchant_share_bps BETWEEN 0 AND 10000),  -- the merchant's part of the fee; the rest is Circaso's
    fee_settle_hour_utc    integer     NOT NULL CHECK (fee_settle_hour_utc BETWEEN 0 AND 23),    -- the daily sweep of Circaso's share
    quote_seconds          integer     NOT NULL,         -- 60
    payment_window_seconds integer     NOT NULL,         -- 120
    kyc_required           boolean     NOT NULL,         -- staff's switch; never false on MAINNET (CHECK)
    -- V4: kyc_provider (didit), kyc_environment (SANDBOX | LIVE), kyc_workflow_user, kyc_workflow_merchant,
    --     merchant_portal_url, kyc_attempts_per_day (1..20, 3), kyc_pending_hours (1..672, 24)
    callback_base_url      text        NOT NULL,         -- the public origin merchants' callback addresses are made on
    collections_provider   text        NOT NULL,         -- who sends the M-Pesa prompts (payhero); switched in the admin
    provider_fee_floor_minor bigint    NOT NULL DEFAULT 5000 CHECK (provider_fee_floor_minor >= 0),  -- V3: the least a merchant's service balance at the provider must hold to go online (KES minor units; 0: no check)
    wallet_set_id          text,                         -- the merchants' wallet set at Circle; written once by the ops process
    updated_by             text,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0
);

CREATE TABLE ramp.revenue_wallets (                      -- Circaso's own ramp wallet, one per network; made by the ops process
    network           text        PRIMARY KEY,
    blockchain        text        NOT NULL,
    custody_wallet_id text        NOT NULL UNIQUE,
    wallet_address    text        NOT NULL,
    created_at        timestamptz NOT NULL
);

CREATE TABLE ramp.fee_settlements (                      -- one sweep of what a merchant owes Circaso
    settlement_id uuid        PRIMARY KEY,               -- the custodian's refId; its idempotency key is derived from it
    merchant_id   uuid        NOT NULL,
    network       text        NOT NULL,
    amount_minor  bigint      NOT NULL CHECK (amount_minor > 0),
    state         text        NOT NULL,                  -- PENDING | CONFIRMED | FAILED
    custody_tx_id text,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);
CREATE UNIQUE INDEX fee_settlements_open_key ON ramp.fee_settlements (merchant_id, network) WHERE state = 'PENDING';

CREATE TABLE ramp.preview_access (user_id uuid, network text, PRIMARY KEY (user_id, network));   -- who may trade while PREVIEW

CREATE TABLE ramp.merchant_wallets (                     -- a merchant's float wallet, one per network
    merchant_id       uuid        NOT NULL,
    network           text        NOT NULL,
    blockchain        text        NOT NULL,
    custody_wallet_id text        NOT NULL UNIQUE,       -- Circle's wallet id: a custody update finds the merchant by it
    wallet_address    text        NOT NULL,
    created_at        timestamptz NOT NULL,
    PRIMARY KEY (merchant_id, network)
);
-- `network` is also a column on quotes, orders, reservations and commissions, set from the order's chain
-- (Network.ofChain); every index that serves a money read leads with it where staff narrow by network. The
-- `blockchain`, `custody_wallet_id` and `wallet_address` columns shown on merchants below move to merchant_wallets.

-- V3 adds to merchants: payments_issue text (NULL | 'SERVICE_BALANCE') and payments_issue_at; to orders: attempt
-- integer (1, 2, …) with UNIQUE (quote_id, attempt) in place of the unique quote_id.
CREATE TABLE ramp.merchants (
    merchant_id       uuid        PRIMARY KEY,           -- UUIDv7
    display_name      text        NOT NULL,              -- a merchant account of its own: no user_id (decided 2026-11-22)
    contact_email     text        NOT NULL UNIQUE,       -- one merchant account per email; sign-in is an Open question
    contact_phone     text        NOT NULL,
    state             text        NOT NULL,              -- APPLIED | APPROVED | SUSPENDED (partner approval; KYC is in verifications)
    blockchain        text,                              -- the float wallet's chain; NULL until RM4
    custody_wallet_id text        UNIQUE,                -- Circle's wallet id: webhooks find the merchant by it
    wallet_address    text,
    online_until      timestamptz,                       -- the end of the trading window; NULL when offline
    payout_mode       text        NOT NULL DEFAULT 'NONE', -- NONE | MANUAL (the semi-manual fallback) | PROVIDER (later)
    last_assigned_at  timestamptz,                       -- the fairness tie-breaker
    approved_by       text,
    approved_at       timestamptz,
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0
);
CREATE INDEX merchants_online_idx ON ramp.merchants (blockchain, online_until)
    WHERE state = 'APPROVED' AND online_until IS NOT NULL;                    -- the engine's candidates; ramp.end-sessions

CREATE TABLE ramp.verifications (                        -- V4: who passed KYC for the ramp; never a document
    subject_kind       text        NOT NULL,             -- USER | MERCHANT
    subject_id         uuid        NOT NULL,
    environment        text        NOT NULL,             -- SANDBOX | LIVE: the provider environment the check ran in, not the network
    status             text        NOT NULL,             -- PENDING | IN_REVIEW | VERIFIED | DECLINED | EXPIRED (no row: NONE)
    reason             text,                             -- our own code (document, identity_in_use, …); never the provider's text
    provider           text        NOT NULL,             -- who holds the session (didit): its decision is asked there
    session_id         text,                             -- the provider's current session: the webhook finds the row by it
    session_token      text,                             -- what the provider's SDK starts with; kept only while the check is unfinished
    session_url        text,                             -- the provider's hosted page for the same session
    session_expires_at timestamptz,
    identity_key       text,                             -- HMAC of the approved document (live only); set with VERIFIED
    attempts           integer     NOT NULL DEFAULT 0,   -- sessions ever started
    attempt_day        date,                             -- the Nairobi day the count below is for
    attempts_on_day    integer     NOT NULL DEFAULT 0,   -- the daily cap
    started_at         timestamptz,
    checked_at         timestamptz NOT NULL,             -- when the provider was last asked: the sweep's order
    verified_at        timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    PRIMARY KEY (subject_kind, subject_id, environment)
);
CREATE UNIQUE INDEX verifications_session_key ON ramp.verifications (session_id) WHERE session_id IS NOT NULL;
CREATE UNIQUE INDEX verifications_identity_key ON ramp.verifications (subject_kind, environment, identity_key)
    WHERE identity_key IS NOT NULL;                      -- one verified ramp user (and one merchant) per real identity
CREATE INDEX verifications_open_idx ON ramp.verifications (checked_at) WHERE status IN ('PENDING', 'IN_REVIEW');

CREATE TABLE ramp.merchant_credentials (                 -- sealed; never plaintext
    merchant_id  uuid        PRIMARY KEY,
    provider     text        NOT NULL,                   -- whose account these are (payhero): the network's provider when sealed
    sealed       bytea       NOT NULL,                   -- AES-256-GCM(data key) over the credentials
    wrapped_key  bytea       NOT NULL,                   -- the data key under KDF(master key, Argon2id(PIN, salt))
    pin_salt     bytea       NOT NULL,
    kdf_params   text        NOT NULL,                   -- Argon2id memory, iterations, parallelism; and the master key's version
    updated_at   timestamptz NOT NULL
);

CREATE TABLE ramp.quotes (
    quote_id          uuid        PRIMARY KEY,
    user_id           uuid        NOT NULL,
    direction         text        NOT NULL,              -- ON_RAMP | OFF_RAMP
    blockchain        text        NOT NULL,
    fiat_currency     text        NOT NULL,              -- KES
    fiat_amount_minor bigint      NOT NULL CHECK (fiat_amount_minor > 0),
    asset             text        NOT NULL,              -- USDC
    asset_amount_minor bigint     NOT NULL CHECK (asset_amount_minor > 0),
    rate              text        NOT NULL,              -- KES per USDC, a decimal string
    fee_minor         bigint      NOT NULL,              -- in the fiat currency
    expires_at        timestamptz NOT NULL               -- 60 s; filtered and purged
);

CREATE TABLE ramp.orders (
    order_id           uuid        PRIMARY KEY,          -- UUIDv7; PayHero's external_reference and Circle's refId
    user_id            uuid        NOT NULL,
    merchant_id        uuid        NOT NULL,
    quote_id           uuid        NOT NULL UNIQUE,      -- a quote makes one order: a repeated R3 finds it
    direction          text        NOT NULL,
    state              text        NOT NULL,
    blockchain         text        NOT NULL,
    fiat_currency      text        NOT NULL,
    fiat_amount_minor  bigint      NOT NULL,
    asset              text        NOT NULL,
    asset_amount_minor bigint      NOT NULL,
    rate               text        NOT NULL,
    fee_minor          bigint      NOT NULL,
    collections_provider text      NOT NULL,             -- the provider that prompted it: its status is asked there
    payer_phone        text        NOT NULL,             -- E.164: needed to match the payment; never logged
    destination_address text       NOT NULL,             -- on-ramp: the user's wallet; off-ramp: the merchant's
    provider_reference text,                             -- PayHero's reference for the prompt: the status query's key
    checkout_id        text,
    mpesa_receipt      text,                             -- set when verified
    custody_tx_id      text,                             -- Circle's transaction for the release (or the refund)
    tx_hash            text,
    wallet_transfer_id uuid,                             -- off-ramp: the user's W4 transfer
    failure_reason     text,
    payment_deadline   timestamptz NOT NULL,             -- when ramp.expire-orders gives up on an unpaid prompt (not `expires_at`: the row stays)
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);
CREATE INDEX orders_user_idx ON ramp.orders (user_id, created_at DESC, order_id DESC);          -- R5
CREATE INDEX orders_merchant_idx ON ramp.orders (merchant_id, created_at DESC, order_id DESC);  -- RM7, success rate
CREATE UNIQUE INDEX orders_user_open_key ON ramp.orders (user_id)
    WHERE state IN ('RESERVED', 'PAYMENT_REQUESTED', 'PAID', 'RELEASING',
                    'AWAITING_TRANSFER', 'FUNDED', 'PAYING_OUT',
                    'PAYOUT_DUE', 'PAYOUT_CLAIMED');                          -- one open order per user
CREATE INDEX orders_open_idx ON ramp.orders (state, updated_at)
    WHERE state NOT IN ('COMPLETED', 'EXPIRED', 'FAILED', 'CANCELLED', 'REFUNDED');  -- expire-orders, reconcile
CREATE UNIQUE INDEX orders_receipt_key ON ramp.orders (mpesa_receipt) WHERE mpesa_receipt IS NOT NULL;
                                                         -- one M-Pesa payment releases one order, ever

CREATE TABLE ramp.reservations (
    order_id     uuid        PRIMARY KEY,                -- one reservation per order
    merchant_id  uuid        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    asset        text        NOT NULL,                   -- USDC
    kind         text        NOT NULL,                   -- FLOAT (promised to an on-ramp) | HOLD (an off-ramp's USDC, held until the user is paid)
    state        text        NOT NULL,                   -- OPEN | CONSUMED | RELEASED
    created_at   timestamptz NOT NULL,
    closed_at    timestamptz
);
CREATE INDEX reservations_open_idx ON ramp.reservations (merchant_id) WHERE state = 'OPEN';   -- free float

CREATE TABLE ramp.commissions (
    order_id     uuid        PRIMARY KEY,                -- written once, with COMPLETED
    merchant_id  uuid        NOT NULL,
    merchant_minor bigint    NOT NULL,                   -- the merchant's share of the fee, in the fiat currency
    platform_minor bigint    NOT NULL,                   -- Circaso's share, in the fiat currency
    platform_asset_minor bigint NOT NULL,                -- Circaso's share in USDC at the order's rate: what the merchant's float owes
    settlement_id  uuid,                                 -- the sweep that collected it; NULL while owed
    currency     text        NOT NULL,
    earned_at    timestamptz NOT NULL
);
CREATE INDEX commissions_merchant_idx ON ramp.commissions (merchant_id, earned_at DESC);       -- RM8

CREATE TABLE ramp.blocks (                               -- V4
    block_id       uuid        PRIMARY KEY,
    user_id        uuid,                                 -- exactly one of the two is set (CHECK)
    identity_key   text,                                 -- a KYC identity key (an HMAC), never a document number
    reason         text        NOT NULL,                 -- OPS | REVERSAL | IDENTITY (verified as a barred identity)
    note           text        NOT NULL DEFAULT '',      -- what staff wrote; never personal data
    source_user_id uuid,                                 -- on an identity row: the user whose block it came from
    created_by     text        NOT NULL,                 -- a staff id, or `system`
    created_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX blocks_user_key ON ramp.blocks (user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX blocks_identity_key ON ramp.blocks (identity_key) WHERE identity_key IS NOT NULL;
CREATE INDEX blocks_source_idx ON ramp.blocks (source_user_id) WHERE source_user_id IS NOT NULL;
CREATE INDEX blocks_listed_idx ON ramp.blocks (created_at DESC, block_id DESC) WHERE user_id IS NOT NULL;

CREATE TABLE ramp.withdrawals (
    withdrawal_id uuid       PRIMARY KEY,
    merchant_id  uuid        NOT NULL,
    destination_address text NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    asset        text        NOT NULL,
    state        text        NOT NULL,                   -- SUBMITTED | CONFIRMED | FAILED
    custody_tx_id text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);
CREATE INDEX withdrawals_merchant_idx ON ramp.withdrawals (merchant_id, created_at DESC);

CREATE TABLE ramp.realtime (user_id, seq, frame, sent_at);   -- the shared shape: daily partitions, 7 days
CREATE TABLE ramp.outbox (…);                                -- the shared DDL: daily partitions, 7 days
```

| Table | Expiry | How the race is settled |
| --- | --- | --- |
| `settings` | — | RO5 writes with `version = version + 1 WHERE version = ?`; 0 rows is a concurrent edit. Read through a 10-second cache per process; on a database error the last read stays in force |
| `merchants` | — | The row is the lock for everything about a merchant's float: reservations and withdrawals take it `FOR UPDATE` first. Edits use `version` |
| `verifications` | — (a staff reset deletes a row) | Every outcome moves a row by `updateReturning` guarded on its `session_id` and on not already being there, so a webhook, the sweep and a status read apply it once; `verifications_identity_key` refuses a second account on one identity, in the statement that would verify it |
| `merchant_credentials` | — | One row per merchant, replaced whole by RM5 and RM9 |
| `quotes` | `expires_at`, purged by `ramp.purge-expired` | — |
| `orders` | — (kept for the retention period) | R3 inserts with `insertIgnore` on `(quote_id, attempt)`: a repeat finds the quote's latest order. `orders_user_open_key` refuses a second open order. States move only by the transition guard, so two callbacks, a callback and the reconcile job, or two nodes can't both release. `orders_receipt_key` stops one M-Pesa payment from settling two orders |
| `reservations` | — | Inserted in the same transaction as the order, under the merchant's row lock. Closed by `updateReturning … WHERE state = 'OPEN'`: only the caller that closes it writes what follows |
| `commissions` | — | `insertIgnore` on `order_id`, in the transaction that completes the order |
| `blocks` | — | `insertIgnore` on each unique key; lifting a user's block deletes their row and the identity rows that came from it |
| `withdrawals` | — | Written under the merchant's row lock with the offline and no-open-orders checks |
| `realtime`, `outbox` | Daily partitions, 7 days | — |

**V6 (2026-11-23, the merchant engine):** `ramp.order_events`, four columns on `merchants`, `ramp.engine_settings` with `ramp.engine_settings_history`, and `ramp.routing_decisions`. The DDL and how each race is settled are under "As built: the merchant engine".

**Redis (`rmp:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `rmp:session:{merchantId}` | The node id that holds the merchant's unlocked keys. Never a key or a PIN | The trading window | Says the merchant is online and where their PayHero calls must run |
| `rmp:work:{nodeId}` | A list of order ids | 1 h, renewed on use | Hands a PayHero step to the node that holds the keys |
| `rmp:float:{merchantId}` | The wallet's USDC balance in minor units | 10 s, cleared on any webhook for the wallet | Ranking candidates without a Circle call per order |
| `rmp:pin:{merchantId}` | Wrong PIN tries | 30 min | Locks PIN entry after 5 |
| `rmp:prompts:{userId}` | Prompts sent this hour | 1 h | Keeps a user from burning a merchant's PayHero account |
| `rmp:daily:{userId}:{date}` | KES traded today | 2 days | The daily limit, reserved and given back as the wallet's `wal:sent` |
| `rmp:seq:{userId}` | Counter | 8 days, renewed on use | Next realtime seq for ramp frames |

**Scheduled jobs**

| Job | Every | Does | First slice |
| --- | --- | --- | --- |
| `ramp.release` (`OpsJob`, the ops process) | 5 s | For each network: sends `PAID` orders through `TreasuryCustody.send` (key derived from the order) and moves them to `RELEASING`; re-reads `RELEASING` orders older than 15 s. At start-up, makes the merchants' wallet set and any approved merchant's missing wallet | Yes |
| `ramp.settle-fees` (`OpsJob`) | 10 min | Once a day, at the settings' hour: for each merchant owing Circaso anything, one transfer of the whole total from the merchant's float to Circaso's ramp wallet, then the orders it covers are marked settled. An unsent sweep is tried again each run | With fees |
| `ramp.payouts` | 10 s | Selling (2026-11-23): unconfirmed fundings, due payouts, `PAYING_OUT` lookups, unfunded sales past their deadline, staff's re-checks, test payouts in flight. See "As built: the off-ramp" | Built 2026-11-23 |
| `ramp.expire-orders` | 30 s | An order unpaid 2 minutes after its prompt: **asks PayHero first**. `FAILED` or no payment → `EXPIRED` and the reservation `RELEASED`, in one transaction; `SUCCESS` → the normal verify and release; still `QUEUED` → left for the next run, and to `REVIEW` after 10 minutes. When the merchant's keys aren't available it changes nothing | Yes |
| `ramp.reconcile` | 2 min | Orders stuck in `PAID`, `RELEASING`, `FUNDED` or `PAYING_OUT`: reads Circle and PayHero and applies what they say (a lost webhook or callback), retries a failed release with the same key, and alerts on anything in `REVIEW` or waiting on a locked merchant | Later (the first slice covers `RELEASING` through Circle's webhook and a manual re-read) |
| `ramp.end-sessions` | 1 min | Merchants whose `online_until` passed (`WINDOW_ENDED`), or whose keys this node doesn't hold (`RESTART`, timed or open-ended, with an email): marks them offline. Renews open-ended sessions' marks; drops keys no open order needs. Open orders finish | Built (`RESTART` 2026-11-23) |
| `ramp.sweep-verifications` | 2 min | Re-reads from the provider every `PENDING` verification not asked about in 5 minutes and every `IN_REVIEW` one not asked about in 30 (a lost webhook, an abandoned session). One the provider still calls unfinished after its time (`kyc_pending_hours`) is marked `EXPIRED`, and so is one whose provider can't be asked any more; while the provider is only out of reach nothing changes | Built 2026-11-22 |
| `ramp.purge-expired` | 5 min | Expired quotes; tomorrow's partitions and old ones for `realtime` and `outbox` | Yes |
| `ramp.prune-routing` | 1 h | Deletes routing decisions older than each network and direction's `logRetentionDays` (90), 500 at a time | Built 2026-11-23 |

## Events

**Consumed**

| Stream (group `ramp`) | Event | What ramp does |
| --- | --- | --- |
| `events:wallet` | `wallet.TransferConfirmed` with `rampOrderId` | Off-ramp: verifies the transfer and moves the order to `FUNDED` |
| `events:wallet` | `wallet.TransferFailed` | Off-ramp: an order waiting on that transfer ends `FAILED` |
| `events:identity` | `identity.AccountStatusChanged` | A suspended or blocked account: no new orders; a merchant goes offline |
| `events:identity` | `identity.AccountDeleted` | Closes the user's quotes; keeps order records for the retention period (Open questions) |

**From vendors** (through the integrations' notification interfaces, not a stream): `CollectionNotifications.onPaymentCallback` (and `onPayoutCallback` once a provider pays out: doorbells that start a status query), `KycNotifications.onStatusChanged` (ramp re-reads the decision, writes the verification, and refuses or blocks on a known identity key) and Circle's webhook for a developer-controlled wallet whose `refId` is an order (a release reaching the chain; a deposit into a merchant's wallet clears `rmp:float`).

**Published** (stream `events:ramp`)

| Event | `data` | Consumers |
| --- | --- | --- |
| `ramp.OnRampCompleted` v1 | `orderId`, `userId`, `merchantId`, `blockchain`, `asset`, `assetAmountMinor`, `fiatCurrency`, `fiatAmountMinor` | auditor; later assistant |
| `ramp.OffRampCompleted` v1 | The same; `fiatAmountMinor` is what the user was paid (published since 2026-11-23) | auditor |
| `ramp.OrderFailed` v1 | `orderId`, `userId`, `direction`, `reason` | auditor |
| `ramp.UserBlocked` v1 | `userId`, `reason` (never the identity key) | auditor; identity later, if a block should mark the account |

No event carries a phone number, an M-Pesa receipt or a credential.

**Realtime frames** (module `ramp`)

| Type | `data` | Goes to | Push if offline |
| --- | --- | --- | --- |
| `order.updated` | `orderId`, `direction`, `state`, `blockchain`, `pay`, `receive`, `failureReason?` | The user, each time their order moves | Yes on `COMPLETED`, `FAILED`, `EXPIRED` and `REFUNDED`: "Your USDC has arrived" / "Your top-up didn't go through"; never an amount on the lock screen |
| `kyc.updated` | `network`, `status`, `required`, `canStart`, `reason?`, `retryAt?`, `submitted` (the same as R1's `verification`; also sent, never pushed, when `submitted` flips) | The user, each time their verification moves after a check was started | Yes when `VERIFIED` ("You're verified. You can add money now."), `DECLINED`, `PENDING` with `resubmission_requested`, and `EXPIRED` with `document_expired`; never the reason on the lock screen. Push `kind` `ramp.kyc` |

Ramp implements `RealtimeReplay` with its `realtime` table. Merchants get no frames: the portal polls RM2 and RM7.

## Error codes

| Code | Kind → status | When |
| --- | --- | --- |
| `ramp.unavailable` | FORBIDDEN → 403 | The ramp is `OFF` on this network, or `PREVIEW` and the user isn't on the list (R1, R2, R3; **never R0**: verifying is not a trade) |
| `ramp.kyc_required` | FORBIDDEN → 403 | The user hasn't passed KYC on this network (R2, R3); R1's `verification` says where it stands |
| `ramp.kyc_unavailable` | UPSTREAM → 502 | Didit is out of reach, or refused the session (R0, RM3) |
| `ramp.kyc_not_configured` | UNAVAILABLE → 503 | Checks can't run on this network yet: the environment's key isn't on the server, the workflow id is empty, the provider isn't offered, or (live) the master key is missing |
| `ramp.kyc_attempts_exceeded` | RATE_LIMITED → 429 | Today's checks are used up (`kyc_attempts_per_day`); `retryAfterSeconds` to the next Nairobi midnight |
| `ramp.merchant_kyc_required` | CONFLICT → 409 | Staff approving a merchant whose identity isn't verified on an open network that requires KYC |
| `ramp.blocked` | FORBIDDEN → 403 | The user or their KYC identity is barred from the ramp |
| `ramp.account_restricted` | FORBIDDEN → 403 | The account isn't `ACTIVE` |
| `ramp.wallet_required` | CONFLICT → 409 | No wallet that receives on the order's chain |
| `ramp.chain_unsupported` | INVALID → 400 | A chain the ramp doesn't offer |
| `ramp.amount_invalid` | INVALID → 400 | Zero, negative, not whole shillings, or both sides given and disagreeing |
| `ramp.limit_exceeded` | INVALID → 400 | Under the minimum, over the per-order or daily limit |
| `ramp.quote_not_found` | NOT_FOUND → 404 | No such quote, or not this user's |
| `ramp.quote_expired` | CONFLICT → 409 | The quote's 60 s are over: ask again |
| `ramp.phone_not_yours` | INVALID → 400 | The number isn't the account's verified one |
| `ramp.order_in_progress` | CONFLICT → 409 | The user already has an open order |
| `ramp.no_merchant_available` | CONFLICT → 409 | Nobody online has the float for this amount |
| `ramp.merchant_unavailable` | CONFLICT → 409 | The provider refused the prompt for every merchant who could take the order (2026-11-22): "We couldn't start this payment right now. Nothing was taken. Please try again in a moment." Nothing was sent or reserved |
| `ramp.offramp_unavailable` | CONFLICT → 409 | Selling isn't open to the user on this network (`offramp_state`, 2026-11-23) |
| `ramp.payout_fee_unavailable` | UPSTREAM → 502 | Neither the provider's published payout fee nor staff's table covers the amount: a sale isn't quoted |
| `ramp.merchant_offline` | CONFLICT → 409 | A test payout while the merchant's keys aren't unlocked on this node |
| `ramp.test_payout_in_progress` | CONFLICT → 409 | A test payout while one is still in flight |
| `ramp.test_payout_not_found` | NOT_FOUND → 404 | No such test payout of this merchant |
| `ramp.payout_account_invalid` | INVALID → 400 | A payout account id that isn't digits |
| `ramp.topup_in_progress` | CONFLICT → 409 | RM19 while a top-up of the same wallet is still `REQUESTED` |
| `ramp.topup_not_found` | NOT_FOUND → 404 | No such top-up of this merchant |
| `ramp.resolution_invalid` | CONFLICT → 409 | RO4 on an order not in review, or a resolution that doesn't apply as it stands |
| `ramp.receipt_required` | INVALID → 400 | RO4 `MARK_PAID` without the M-Pesa receipt |
| `ramp.payout_may_be_paid` | CONFLICT → 409 | RO4 `REFUND` of a sale whose last payout the provider said was paid |
| `ramp.too_many_prompts` | RATE_LIMITED → 429 | Too many M-Pesa prompts this hour |
| `ramp.order_not_found` | NOT_FOUND → 404 | No such order, or not this user's or merchant's |
| `ramp.order_not_cancellable` | CONFLICT → 409 | Already paid or finished |
| `ramp.collections_unavailable` | UPSTREAM → 502 | PayHero is out of reach (down, timing out, or the circuit is open) for every merchant tried, or gave no answer and couldn't then say whether a prompt went out (2026-11-23). A refusal is `ramp.merchant_unavailable`, never this |
| `ramp.pricing_unavailable` | UPSTREAM → 502 | The side asked for has no rate in force: no reference rate, one older than `maxReferenceAgeHours`, or an expired manual rate (2026-11-24; see "As built: rates") |
| `ramp.custody_unavailable` | UPSTREAM → 502 | Circle is down |
| `ramp.merchant_not_found` | NOT_FOUND → 404 | The account is not a merchant |
| `ramp.merchant_not_approved` | FORBIDDEN → 403 | Not yet KYC-verified and partner-approved, or suspended |
| `ramp.merchant_setup_incomplete` | CONFLICT → 409 | Going online without a float wallet or a payment account, with the provider's service wallet below its floor (then with `details.fields`: `provider_balance`), or before identity verification where the network requires it (`details.fields`: `kyc`); also RM3 before the profile |
| `ramp.pin_invalid` | INVALID → 400 | Wrong PIN |
| `ramp.pin_locked` | RATE_LIMITED → 429 | Five wrong PINs; `retryAfterSeconds` |
| `ramp.credentials_rejected` | INVALID → 400 | PayHero refused the username, password or channel |
| `ramp.merchant_online` | CONFLICT → 409 | A withdrawal while online or with open orders |
| `ramp.insufficient_float` | INVALID → 400 | A withdrawal above the unreserved balance |
| `ramp.address_invalid` | INVALID → 400 | A withdrawal address not valid on the chain |
| `ramp.routing_not_found` | NOT_FOUND → 404 | No such routing decision (RO12) |

Metrics (`circaso_ramp_`): `orders_total` (direction, outcome), `verify_total` (result: matched, mismatch, failed), `release_duration_seconds`, `orders_in_review` (gauge), `merchants_online` (gauge), `no_merchant_total`. No merchant or user id as a label.

## First slice (after approval of "How this fits main")

On-ramp end to end on the testnet (`BASE-SEPOLIA`, state `PREVIEW`), **with the first merchant onboarded through the portal**, not seeded:

- **Merchant accounts and the portal's API:** sign-in by email and a one-time code (decided 2026-11-22; a session of the ramp's own, separate from identity's user sessions), RM1 apply, RM2 status and float, RM4 float wallet, RM5 PayHero credentials sealed under the PIN (checked against PayHero first), RM6 go online and offline, RM7 the merchant's orders.
- **The portal** (`merchants.circaso.com`): a web app like the admin portal, for those routes.
- **Partner approval** by staff on the ops gateway (RO1, RO2), and the settings route (RO5).
- **Credentials and session:** the envelope (`merchant_credentials`), PIN attempts, `rmp:session:{merchantId}` and the in-memory keys; one node for now.
- `MobileCollections` with PayHero (STK push, status query, `verify`), its `log` and `fake`, and `/v1/hooks/payhero`.
- `MerchantFloat` (read-only), `accountType` on `TreasuryWalletRequest`, a fake and a logging treasury.
- `ramp.settings` (seeded, cached 10 s), `preview_access`, `merchants`, `merchant_wallets`, `merchant_credentials`, `quotes`, `orders`, `reservations`, `commissions`, `realtime`, `outbox`, all with `network`.
- R1–R6 inside `walletNetwork { }`; the simple engine; verify in the app; `ramp.release` in the ops process; Circle's confirmation through the webhook; `ramp.expire-orders`, `ramp.end-sessions`, `ramp.purge-expired`.

**Parked code.** A first slice was built on 2026-11-22 against the old branch and passed its build there: the PayHero integration, `MobileCollections`, the ramp module (orders, reservations, engine, verify, release, sweep) and their tests. It is kept in a git stash, not on this branch. Ported as is: PayHero, `MobileCollections`, the domain flow and its tests. Reworked for main: settings from the database instead of `RAMP_*` variables, `network` throughout, the release moved to the ops process on `TreasuryCustody` (its own `CircleMerchantCustody` and `CircleEntitySecret` dropped), the staff routes in `ops`, and the seeded development merchant and its credentials from `Secrets` replaced by merchant accounts and the envelope.

Where the slice is simpler than the full design: the engine picks the least recently assigned candidate (no success-rate ranking, no `rmp:float` cache); `ramp.expire-orders` also stands in for `ramp.reconcile` (from 30 s it asks PayHero about an unpaid prompt; a prompt still queued after 10 minutes goes to `REVIEW`; when the provider can't be asked, nothing changes); paid-after-expiry and a failed release go straight to `REVIEW`; a prompt that can't be sent ends the order `FAILED` and frees the float.

Skipped for now: off-ramp payouts, routing work to another node, the Didit integration (`settings.kyc_required` is false on the testnet until it exists), blocks, withdrawals, splitting, `ramp.reconcile` and `ramp.end-sessions`.

## As built (first slice, 2026-11-22)

What the code on `feat/ramp` does where it differs from, or settles, what is written above and below. Nothing here changes a decision.

**Where an order completes, and who tells the user.** The ops process's `ramp.release` does all of it: it sends `PAID` orders, then reads each `RELEASING` order's transaction on every run (5 s) and completes it (reservation consumed, commission row, `ramp.OnRampCompleted` in the outbox, one transaction), publishes the event and sends `ramp/order.updated`. The frame goes out from the ops process through the gateway's `Realtime`, which that process already binds so a staff reply reaches a user: the sequence (`rmp:seq`) and the kept frame (`ramp.realtime`) are shared, and delivery crosses to the app's node over Redis. Circle's webhook is not used for the ramp, and the app consumes no `events:ramp` event. The app's processes only verify the payment (`PAID`).

**Settings and the gate.** `ramp.settings.chains` is comma-separated `text`, not `text[]`. The table also checks the state's values, the limits' order and `kyc_required OR network = 'TESTNET'`; code enforces KYC on mainnet whatever the row says. Built-in seed: `OFF`, a 1.5 % fee split 70/30, 10 to 5 000 shillings an order, 20 000 a day, 5 prompts an hour, sweep at 21:00 UTC, callback origin `https://api.circaso.com`. The state gate (`ramp.unavailable`) is on R1, R2 and R3; R4, R5 and R6 stay open so an order made before the ramp was switched off can still be read and cancelled.

**The collections provider.** `integrations:api` has `CollectionProviders`; `OfferedCollections` (integrations) offers `payhero` everywhere and `log` only when `config.environment.isLocal`; it never offers `fake`. PayHero's webhook vendor is always bound, since the provider can be switched on at any time. The built-in seed of `collections_provider` is `payhero`, or `log` where the server offers it (a developer's machine), so a local order still completes end to end. `OrderFlow` asks `named(order.collectionsProvider)` for the prompt and for every status query; when that provider isn't offered any more the order waits untouched. The engine takes only merchants whose unlocked keys are for the network's current provider (the node keeps the provider beside the keys). RM5 checks the account with the network's current provider and seals it as that provider's, under the PIN already set; RM6 refuses (`ramp.merchant_setup_incomplete`) while the stored account is at another provider. RO5 refuses a provider this server doesn't offer (`ramp.settings_invalid`, field `collectionsProvider`), which is what keeps `log` off every server, and answers the offered names as `collectionsProviders` (read-only).

**Merchants.** `merchants.state` has `NEW` (signed in, no profile yet) before `APPLIED`; `display_name` and `contact_phone` are empty until RM1, and the API answers them as `null`. The float wallet's columns are in `merchant_wallets` only; `merchants.callback_key` is the end of the callback address. Portal sign-ins are `ramp.merchant_sessions (token_hash bytea PRIMARY KEY, merchant_id, created_at, expires_at)`. `merchant_credentials.channel_id` keeps the till or paybill in plaintext beside the sealed blob, because RM2 shows it while the merchant is offline; it is not a secret. RM2 also answers `pinSet`. RM5 is allowed in `APPLIED` or `APPROVED`. `missing` is `profile`, `approval`, `wallet`, `payments`, `float` (free float below what the smallest order buys; a float the custodian can't report is not counted). RM6 takes 15 to 720 minutes, or (2026-11-23) `untilStopped` for a session with no end time: see "As built: online until stopped".

**The envelope.** AES-256-GCM under a random 256-bit data key; the data key wrapped with AES-256-GCM under `HMAC-SHA256(master key, "ramp:kek:v1" ‖ Argon2id(PIN, 16-byte salt))`; Argon2id at 19 MiB, 2 passes, 1 lane (Bouncy Castle). Both ciphertexts carry the merchant's id as additional data. `kdf_params` is `argon2id;m=19456;t=2;p=1;mk=1`. Without (a valid) `RAMP_CREDENTIALS_MASTER_KEY` the server still starts; RM5, RM6 and RM9 answer `ramp.vault_unavailable`.

**The trading session.** Keys live in the app process's memory (`NodeTradingSessions`); `rmp:session:{merchantId}` holds the node's id, which is new on every start. One node only: nothing hands work to another node (`rmp:work` is not built), so the engine takes only merchants whose keys this node holds. Going offline (RM6, sign-out, a suspension, the window's end) clears the window and the Redis mark at once, but the keys stay in memory while one of the merchant's orders still waits for its payment, since only the provider's own answer may settle it; `ramp.end-sessions` (every minute, built) drops them once none is left. (2026-11-23: a session may also be open-ended, and a session whose keys a restart took is ended with `RESTART`: see "As built: online until stopped".)

**Sign-in codes.** In Redis under `rmp:`, by a SHA-256 of the email, never the address: `code:`, `code-tries:`, `code-wait:` (one request a minute, which is MA1's `retryAfterSeconds`), `code-sent:` (three in ten minutes) and `code-from:{address}` (ten an hour). The address is `CF-Connecting-IP`, else the socket's peer: the source the gateway's own limits trust.

**Fees.** What a merchant owes is not a `FEE` reservation: `commissions.settled_at` is null while the float still holds Circaso's share, and free float is `balance − open reservations − unsettled commissions`. A sweep is opened by naming it on every unswept commission (`settlement_id`) in one transaction; `settled_at` is written when the chain confirms it. `fee_settlements` also has `day`, `orders`, `tx_hash` and `failure_reason`. A sweep the custodian can't take stays `PENDING` and is asked again with the same key; one it refuses, or the chain fails, is `FAILED`, its commissions are freed, and a new one is opened an hour later at the earliest.

**Periods are Nairobi days.** RM8 and RM15 take `from` and `to` as calendar days in East Africa Time (UTC+3), inclusive; the default is the current Nairobi month. A sweep's `day` is the Nairobi day that ended as it fell due (21:00 UTC is midnight there). RM15's `settlementId` is null until the sweep that took the order's share is confirmed.

**The treasury locally.** With custody `log` or `fake` the stand-in `TreasuryCustody` is bound under the Koin name `treasury.dev`, not as the plain binding, so the wallet's fee wallets still find no treasury and report `NOT_CREATED` as before; the ramp asks for the plain one and then for that name. `MerchantFloat` follows the custody provider in both processes (`log`: every wallet always holds 1 000 USDC). Only the USDC contract the wallet's network settings name for the chain is counted or sent.

**Staff routes as built** (ops gateway, `ramp.read` for FINANCE and ADMIN, `ramp.manage` for ADMIN, every write audited with a reason): `GET /ops/ramp/merchants`, `POST /ops/ramp/merchants/{id}/approve` and `/suspend`, `GET` and `PUT /ops/ramp/settings?network=`, `GET /ops/ramp/preview-access?network=` and `POST /ops/ramp/preview-access`, `GET /ops/ramp/orders?state=REVIEW`. Resolving an order in `REVIEW` and the block list (RO3, RO4's resolve) are not built.

**Codes added:** `ramp.email_unavailable` (502, the code couldn't be sent), `ramp.vault_unavailable` (502), `ramp.merchant_not_ready` (409, approving a merchant who hasn't applied), `ramp.settings_invalid` (400, the field named) and `ramp.settings_changed` (409).

**Not built in this slice:** the Didit integration and blocks (both built on `feat/ramp-kyc` the same day: see "As built: identity verification"), withdrawals, the off-ramp, `ramp.reconcile`, the `rmp:float` cache and success-rate ranking, metrics, and the agent route. (The PayHero service-wallet floor on RM6 was built on 2026-11-22: see below.)

**The hourly prompt limit is optional (2026-11-22, Pasaka: limits must not frustrate a buyer).** `settings.prompts_per_hour` 0 means no limit, which is the default (V2). When staff set one, only prompts the provider accepted (ones that reached the phone) are counted: an attempt that was refused, failed, or found no merchant never counts. It remains as a tool for protecting a merchant's account at the provider, which blocks a number after repeated failed prompts.

**Staff visibility per merchant (built 2026-11-22, `feat/ramp-merchant-insights`).** Three reads on the ops gateway, all `ramp.read` (FINANCE, ADMIN), all needing `?network=`, through `RampAdmin` (`overview`, `merchantSummary`, `merchantOrders`; the types are in `ramp:api` `RampInsights.kt`). `from` and `to` are Nairobi calendar days, inclusive, at most a year apart, as RM8 takes them.

- **One computation.** A merchant's money for staff is `StatementRepository.totals`, the very call RM8 and RM15 make (`MerchantInsights.figures`), formatted by the same `Amounts`; the overview's totals are the sum of its merchant rows (`RampOverview.totals`). `InsightsTest` holds both: the staff summary equals the merchant's earnings for the same merchant and period (in the domain and on the wire), and the overview's totals equal the sum of the rows.
- **Orders are counted in two ways, which add up.** `completed` is the number of commissions earned in the period (the day the release confirmed, as RM8 counts); `failed`, `expired`, `cancelled`, `inReview` and `open` are orders **made** in the period that didn't complete. `orders` (`total`) is the sum, and `successRatePercent` is completed out of that, with at most one decimal (`"0"` with no orders).
- **RO6 `GET /ops/ramp/overview`** → `{ network, from, to, totals: { orders, completed, failed, inReview, collected, sold, fees, merchantShare, circasoShare, circasoShareUsdc, swept, held }, merchants: [ { merchantId, displayName, state, online, orders, completed, successRatePercent, collected, sold, fees, merchantShare, circasoShare, circasoShareUsdc, held, lastOrderAt } ] }`. Default period: this Nairobi month. Listed: approved merchants, and any other with an order in the period (at most 500 merchants are read). `lastOrderAt` is the merchant's latest order on the network, whatever the period. `online` is the trading window (`online_until` in the future): the ops process holds nobody's keys, so it can't see a merchant whose node restarted.
- **RO7 `GET /ops/ramp/merchants/{id}/summary`** → `{ merchant: { merchantId, displayName, email, contactPhone, state, createdAt, approvedAt, online, onlineUntil, paymentsConnected, collectionsProvider, paymentsIssue }, network, from, to, wallet: { blockchain, address } | null, float: { balance, reserved, owedToCircaso, free } | null, orders: { total, completed, failed, expired, cancelled, inReview, open }, successRatePercent, collected, sold, fees, merchantShare, circasoShare, circasoShareUsdc, swept, held, daily: [ { day, orders, completed, collected, sold, fees, merchantShare, circasoShare } ], sweeps: [ { settlementId, day, amount, state, orders, txHash } ] }`. `float` is null without a wallet or when the custodian can't be read; unlike RM2, `reserved` is open orders only and `owedToCircaso` is the unswept commissions. `daily` is one row per Nairobi day with activity, oldest first, grouped in SQL (`(earned_at AT TIME ZONE 'Africa/Nairobi')::date`). `paymentsIssue` is `{ code, since }` or null. `ramp.merchant_not_found` (404).
- **RO8 `GET /ops/ramp/merchants/{id}/orders`** → `{ orders: [ { orderId, createdAt, completedAt, state, direction, blockchain, maskedPhone, paid, released, rate, fee, merchantShare, circasoShare, circasoShareUsdc, failureReason, settlementId, txHash, collectionsProvider } ], nextCursor }`. Newest first by `(created_at, order_id)`; `limit` default 50, at most 200; `nextCursor` is `<createdAt>~<orderId>`. `from`/`to` filter by the day the order was made (none: every day); `state` is one order state. The payer's number is masked inside the ramp, before it crosses `RampAdmin`. `released` is the order's USDC (it reached the buyer only when `COMPLETED`); `completedAt`, the three shares and `settlementId` come from the commission row, so they are null until the order completes (`settlementId` until its sweep is confirmed, as RM15).

**When the seller's provider refuses the prompt (built 2026-11-22, after the first real test).** PayHero answered an STK push with `400 … merchant has insufficient balance to allow you complete this transaction`: the merchant's prepaid service wallet was empty. That used to be `ramp.collections_unavailable` (502) and the same merchant kept being picked.

- **Three outcomes, told apart in `MobileCollections.requestPayment`.** `IntegrationError.Unavailable`: the provider is out of reach and whether a prompt went out is unknown. `Rejected`: the provider refused this merchant's request and nothing was sent. `ServiceBalanceLow` (new): that refusal when the reason is the merchant's own prepaid balance; PayHero gives no code for it, so `PayHeroCollections` reads it from the refusal's wording (`insufficient … balance`), which goes no further than the log.
- **A refusal tries the next merchant.** `OrderFlow.requestPayment` closes the order (`FAILED`, `prompt_refused` or `provider_balance`; float released, `ramp.OrderFailed` published) **without a frame or a push**, since the user never saw that order, and answers `ramp.merchant_unavailable`; `StartOnRamp` then reserves with the next candidate, excluding the refused ones, as a new order for the same quote. `orders.attempt` (V3) numbers them and `orders_quote_attempt_key (quote_id, attempt)` replaces the unique `quote_id`: a quote makes one order per attempt, a repeated R3 finds the latest attempt and prompts nobody, and `orders_user_open_key` still allows one open order. The day's limit is counted once (a closed attempt gives its amount back and the next takes it again) and only the prompt that reached the phone counts toward `prompts_per_hour`. Nobody left: `ramp.merchant_unavailable` (CONFLICT, 409). The provider out of reach stays `ramp.collections_unavailable` (502), with a frame, and nobody else is asked.
- **A balance refusal marks the merchant.** `merchants.payments_issue` = `SERVICE_BALANCE` with `payments_issue_at` (V3). The merchant is taken offline at once (`Presence.goOffline`), is left out of `candidates` and refused under the reservation's row lock, and the people on call are alerted (`provider_balance`). RM2 shows `missing: ["provider_balance"]` and `paymentsIssue: { code: "SERVICE_BALANCE", since }`; `canGoOnline` ignores `provider_balance`, because going online is how it clears. Staff see it on RO7.
- **It clears** when the merchant goes online again (RM6), or when a later prompt of theirs goes through.
- **RM6 checks the service wallet first.** `MobileCollections.balance` is built for PayHero (`GET /api/v2/wallets?wallet_type=service_wallet`, `available_balance` as a number or text → `CollectionBalances.fees`). After the PIN opens the keys, RM6 answers `ramp.merchant_setup_incomplete` with `details.fields: [{ field: "provider_balance" }]` when the balance is under `ramp.settings.provider_fee_floor_minor` (KES minor units, seeded 5000 = KES 50, 0 for no check, at most 1 000 000; edited through RO5 as `providerFeeFloorMinor`, a string of cents, and left as it is when an older admin form doesn't send it). A balance that can't be read never blocks. A merchant already marked needs a balance above zero whatever the floor.

**Codes added (2026-11-22):** `ramp.merchant_unavailable` (409).

## As built: identity verification (2026-11-22, `feat/ramp-kyc`)

What the code does where it differs from, or settles, what is written above. Decided by Pasaka on 2026-11-22: users verify once before their first on- or off-ramp ("Basic KYC": ID, liveness, face match); merchants verify before approval ("ID, selfie and AML"); blocks are by identity; one identity verifies one ramp user; the app uses Didit's native SDK and the portal the hosted page; the secrets are named per Didit environment; which environment a network uses is a setting; one webhook address for both environments; the webhook is a signed doorbell.

**A verification belongs to a Didit environment, not a network.** `ramp.verifications` is keyed `(subject_kind, subject_id, environment)`, `environment` `SANDBOX` or `LIVE`. A sandbox approval is simulated, so it must never count where real people are verified, whichever network asks; and a real (live) verification is the same fact on any network that trusts live. Each network's settings name the environment it trusts (`kyc_environment`: TESTNET `SANDBOX`, MAINNET `LIVE`), and a subject is verified on a network when their row in that environment is `VERIFIED`. A row also keeps the `provider` that holds its session (as an order keeps its collections provider), so a switch of provider never asks the wrong one.

**The identity key, and why only live keeps one.** `HMAC-SHA256(secret, COUNTRY|TYPE|NUMBER)` as hex (`HmacIdentityKeys`). The parts are trimmed and upper-cased, inner whitespace collapsed in the country and the type, and everything but letters and digits removed from the number. The secret is derived, not a new secret: `HMAC-SHA256(RAMP_CREDENTIALS_MASTER_KEY, "ramp:identity-key:v1")`. **The master key must never change**: under another key the same document gives another identity key, so "one identity, one account" and every block by identity silently stop matching. The document is read from Didit once, on approval, and is never stored or logged. **In the sandbox no key is kept**: Didit's sandbox fabricates every document from one pinned sample identity (docs.didit.me → Sandbox testing), so a key there would make every tester the same person. One identity, one account and blocks by identity are therefore live-only; `verifications_identity_key` is unique on `(subject_kind, environment, identity_key)`, so one person may be one user **and** one merchant.

**Statuses.** `PENDING` | `IN_REVIEW` | `VERIFIED` | `DECLINED` | `EXPIRED`; no row is `NONE`. (`PENDING` covers both a check the person hasn't finished and one they finished that Didit hasn't decided; `submitted` tells them apart, see "As built: submitted".) From Didit's exact strings: `Not Started`, `In Progress`, `Awaiting User` → `PENDING`; `Resubmitted` → `PENDING` with reason `resubmission_requested` (the same session reopens); `In Review` → `IN_REVIEW`; `Approved` → the approval path below; `Declined` → `DECLINED`; `Expired`, `Abandoned` → `EXPIRED` (`session_expired`); `Kyc Expired` → `EXPIRED` (`document_expired`: a verified person whose document has since expired must verify again). An unknown string is never guessed at. The ramp follows whatever Didit says now, so an approval a reviewer takes back un-verifies the subject.

**Reason codes** (`verifications.reason`, the app's `reason`; never Didit's text): `document`, `document_expired`, `document_unreadable` (a live approval with no country, type or number: another document), `liveness`, `face_match`, `declined` (no check named), `not_approved` (a screening hit, never named to the person; no retry), `identity_in_use` (no retry), `blocked` (no retry), `session_expired`, `resubmission_requested`. Didit's reason is read from which feature declined (`id_verifications`, `liveness_checks`, `face_matches`, `aml_screenings`) and the `DOCUMENT_EXPIRED` risk.

**On approval** (`Verifications.approve`): in the sandbox, `VERIFIED` with no key. Live: no readable document → `DECLINED` `document_unreadable`; the key is barred (`blocks`) → `DECLINED` `blocked`, and a user is barred too (`blocks` row, reason `IDENTITY`, by `system`), in one transaction; otherwise `VerificationRepository.verify` claims the key under the unique index: the loser of the claim is `DECLINED` `identity_in_use` and nothing of theirs is written.

**Only the provider's own answer moves a verification.** The webhook, the app's status read and `ramp.sweep-verifications` all end in `Verifications.refresh`: read the decision from Didit (`KycProvider.decision`), check its `vendor_data` is the row's own reference, apply it through the repository's guards (`WHERE session_id = ? AND NOT (status = ? AND reason IS NOT DISTINCT FROM ?)`), and tell the user only when the row moved. So the same outcome is applied once, and an old session's late news never touches the session that replaced it.

**Starting and resuming** (`Verifications.start`). The session's link (`session_token`, `session_url`, `session_expires_at`) is kept on the row while the check is unfinished and cleared on `VERIFIED`. R0 and RM3 answer the stored link while it works, without calling Didit; a new session is made only for no row, a `PENDING` whose link ran out, or a retryable `DECLINED`/`EXPIRED`. Each new session counts one attempt (`attempts`; `attempt_day` and `attempts_on_day` for the cap, by Nairobi day); `kyc_attempts_per_day` (3) refuses more with `ramp.kyc_attempts_exceeded` and `retryAt` at the next Nairobi midnight. Didit is asked for a link lasting `kyc_pending_hours` (`session_expiration_time`). A provider that needs nothing from the person (the local `log`) returns no link and is read at once, so a local run goes straight to `VERIFIED`.

**Routes as built.**

- **R0 `POST /v1/ramp/kyc`** (`app`, in the wallet-network group, `Idempotency-Key`) → `{ status, required, canStart, reason, retryAt, submitted, session: { sessionToken, url, expiresAt } | null }` (`submitted` since 2026-11-23, see "As built: submitted" below). Gates first: the ramp open to the user (`ramp.unavailable`), the account active, not barred (`ramp.blocked`). Allowed where KYC isn't required yet.
- **`GET /v1/ramp/kyc`** → `{ status, required, canStart, reason, retryAt, submitted }`. While the check is unfinished it asks the provider (at most every 5 seconds), so the app sees the outcome the moment the SDK closes. Never a link.
- **R1 `GET /v1/ramp`** gains `verification` (the same fields) and `blocked`; `kyc` stays `VERIFIED` | `NONE` | `NOT_REQUIRED` for apps built before it, and `canTrade` also needs `blocked` false. `canStart` is true for `NONE`, for a `PENDING` that isn't submitted whose link works (continue) or whose cap allows a new one, and for a retryable `DECLINED`/`EXPIRED` under the cap; false once submitted, in review, once verified, for a reason that can't be retried, and when barred.
- **R2, R3**: `ramp.blocked` (the user, or the identity they verified with) before `ramp.kyc_required`.
- **RM3 `POST /v1/ramp/merchant/kyc`** → the same shape as R0; the link is the hosted `url`, created with `callback` = `<merchant_portal_url>/kyc/done` (Didit appends `?verificationSessionId=…&status=…`, which the portal must not trust: it reads RM2). Needs the profile first; a suspended merchant can't.
- **RM2** gains `kyc` `{ status, required, canStart, reason, retryAt, submitted }` and `steps.kyc`; `missing` has `kyc` (before `approval`) while the network requires it and the merchant isn't verified. RM2 asks the provider while the merchant's check is unfinished (the portal reads it after the callback). Merchants get no frames.
- **RM6** refuses with `ramp.merchant_setup_incomplete` and `details.fields: [{ field: "kyc" }]`; **the engine** takes no merchant who isn't verified in the order's network's environment where that network requires KYC (a merchant's trading window is one for both networks, so being online never stands in for being verified).
- **Staff approval** (`RampAdmin.approve`, `DecideMerchant`): `ramp.merchant_kyc_required` (409, the networks named) while the merchant isn't `VERIFIED` on every network whose ramp is open (`PREVIEW` or `ON`) and requires KYC. A network that is `OFF` asks nothing yet; when it opens, RM6 and the engine keep an unverified merchant out.
- **Ops gateway** (`ramp.read` for FINANCE and ADMIN, `ramp.manage` for ADMIN, every write audited with a reason): `GET /ops/ramp/users/{userId}/verification` → `{ userId, verifications: [ { environment, status, reason, provider, attempts, hasIdentity, startedAt, verifiedAt, updatedAt } ], blocked, block }`; `GET /ops/ramp/blocks?cursor=&limit=` → `{ blocks: [ { blockId, userId, reason, note, createdBy, createdAt } ], nextCursor }`; `POST /ops/ramp/blocks` `{ userId, reason }` (audit `user.ramp_block`); `DELETE /ops/ramp/blocks/{userId}?reason=` (`user.ramp_unblock`); `POST /ops/ramp/users/{userId}/verification/reset` and `POST /ops/ramp/merchants/{merchantId}/verification/reset` `{ environment, reason }` (`user.ramp_verification_reset`, `ramp_merchant.verification_reset`). The merchant list gains `verifications` and RO7's `merchant` gains `kyc`. RO5 reads and writes the identity settings (each left out on an update stays as it is) and answers `kycProviders`. No document, name or key is ever shown: `hasIdentity` only says a key is held.

**Blocks.** `ramp.blocks` rows name a user or an identity key, never both. Barring a user (`BlockUser`) also bars every identity key their verifications hold (`source_user_id` = the user), so the same person on a new account is refused at verification and barred. Lifting a user's block lifts the identity rows that came from it. A block is checked on R0, R1, R2 and R3 (`blocks.isBlocked(userId, their identity key)`). **The reset** is staff's way out of a dead end (a lost account holding the identity; a decline that can't be retried): it deletes the row and frees the key, and never lifts a block. `ramp.UserBlocked` is not published yet.

**Settings** (V4, seeded, validated in `UpdateSettings`, audited by name in RO5): `kyc_required` (unchanged; never false on MAINNET), `kyc_provider` (one of the offered names: `didit` everywhere, `log` on a local run only, where it is also the seed), `kyc_environment`, `kyc_workflow_user` and `kyc_workflow_merchant` (seeded on TESTNET with the sandbox's `a3cb5782-d5c1-4c35-832d-ed373d80a0ea` and `dabdb52a-b4bc-435a-b2bf-a636bb17bad6`; empty on MAINNET until the live workflows exist), `merchant_portal_url` (an origin), `kyc_attempts_per_day` (1 to 20, default 3), `kyc_pending_hours` (1 to 672, default 24).

**Didit, as built** (`integrations/impl/.../didit/`). `DiditKyc` over `HttpClients.named("didit-sandbox")` and `"didit-live"`, base `https://verification.didit.me`, `x-api-key`: `POST /v3/session/` (`workflow_id`, `vendor_data`, `callback?`, `session_expiration_time?`; never retried by the client) and `GET /v3/session/{id}/decision/` (the V3 plural arrays only). `KycProviders` binds every provider at once (`OfferedKyc`); an environment with no key answers `IntegrationError.NotConfigured`. `DiditWebhook` is the vendor `didit` at `/v1/hooks/didit` for both environments: the delivery's `environment` picks the secret; `X-Signature-V2` (HMAC-SHA256 hex over the canonical JSON: keys sorted by code point, compact, Unicode unescaped, whole floats as integers) is checked first and `X-Signature` over the raw bytes is the fallback; `X-Signature-Simple` is never accepted; the `-Previous` headers are accepted during a secret rotation; the `timestamp` inside the signed body must be within 300 seconds (and `X-Timestamp` too when sent); `event_id` is the delivery id. Only `status.updated` and `data.updated` of user sessions ring the bell, with the environment and the session id; a session the ramp never started is ignored, and so is one whose row belongs to the other environment. The gateway waits for the translation (as for Circle): when Didit can't be asked the answer is a 502 and Didit delivers again (twice, after about one and four minutes); the sweep is the net under that. The canonical JSON is rebuilt by a small reader of its own (`DiditCanonicalJson`), not by untyped JSON elements: it is not payload reading (the typed `WebhookEnvelope` does that).

**Codes added:** `ramp.blocked` (403), `ramp.kyc_unavailable` (502), `ramp.kyc_not_configured` (503), `ramp.kyc_attempts_exceeded` (429), `ramp.merchant_kyc_required` (409). `ramp.identity_in_use` is a reason code on the verification, not an HTTP error.

**Assumptions about Didit** (from the docs on 2026-11-22; to confirm against real sessions):

- A session's `session_token` and `url` keep working until `expires_at` (the docs call the token "short-lived" without a figure). If a stored link stops working earlier, R0 should ask Didit again instead (Didit returns the open session for the same workflow and `vendor_data`).
- The body's `timestamp` is always present and equals `X-Timestamp`; the ramp checks the signed one in the body.
- `id_verifications[].document_type` is stable for one document across sessions (it is Didit's human-readable class, "Identity Card"); a change of wording would change identity keys.
- The decision's `vendor_data` echoes what the session was created with.
- `GET /v3/session/{id}/features/overrides/` says whether the person finished the flow (`person_in_flow`, `flow_finished_at`): see "As built: submitted" for what is unconfirmed.
- A decline's cause can be read from the per-feature `status` (`Declined`) of `id_verifications`, `liveness_checks`, `face_matches`, `aml_screenings`.
- Didit answers a 5xx from us by redelivering; a `Rejected` (400) from us is not retried, which is what an unreadable delivery should get.
- Non-whole floats in a delivery are written as Python's shortest form (the canonical form keeps them as written).

**To configure in Didit's console** (each application, sandbox and live): a webhook destination with URL `https://<api host>/v1/hooks/didit`, version `v3`, event `status.updated` (and `data.updated` if reviewers edit sessions); its `secret_shared_key` is that environment's `DIDIT_*_WEBHOOK_SECRET`. If the application has allowed domains, add the merchant portal's origin (the callback). The user workflow ("Basic KYC") must be published. Recommended in the workflows: decline duplicate faces, since a second document for the same person gives a second identity key.

**The verified badge (2026-11-24, decision of that day).** Every move of a **user's live** row (an approval, a decline or expiry after one, a reviewer taking it back, a barred refusal) and a staff reset of one publish `ramp.UserVerificationChanged` `{ userId, verified, verifiedAt?, changedAt }` on `events:ramp`: the state, never a document or the identity key. Sandbox rows and merchants never publish it. A move writes the outbox row in its own transaction (`Verifications.moved`, `refuseBarred`, `ResetVerification`); an approval right after it commits, since the identity claim's unique violation can't share a transaction. `ramp.announce-verifications` (every 6 hours) says it again for every user's live row that moved in the last 14 days, which covers that gap and backfilled the live approvals made before the event. Identity consumes it (the account's verification, support-recovery eligibility) and users shows the badge; see identity.md → "The verified badge". No ramp migration.

**Not built:** the `ramp.UserBlocked` event; reversal reports (RM11) that would add `REVERSAL` blocks; tiered limits; a frame when staff reset a verification or bar a user (R1 says both); a deep-link callback for the app's hosted-page fallback.

## As built: submitted (2026-11-23, `fix/ramp-kyc-submitted`)

**The gap.** Between the person finishing the capture and Didit deciding, the session is still `In Progress` at Didit, so the row stayed `PENDING` with `canStart: true` and its link: the app and the portal couldn't tell "submitted, awaiting a decision" from "started, not finished", and showed "Finish verifying your identity" to someone who had finished.

**The contract (additive).** One new field, `submitted: boolean`, on the verification everywhere it is returned: R0, `GET /v1/ramp/kyc`, R1's `verification`, the `kyc.updated` frame, RM2's `kyc` and RM3. It is true when the person has done their part and the decision is awaited: a `PENDING` check the provider says they finished, and `IN_REVIEW` (whose own status already said so). False for `NONE`, an unfinished `PENDING`, `VERIFIED`, `DECLINED` and `EXPIRED`. While it is true `canStart` is false, `retryAt` is null and R0/RM3 answer `session: null`, so no client reopens the capture. `status` keeps its six values: a client that never reads `submitted` sees `PENDING` with `canStart: false`.

**Where it comes from: Didit's own word, never the client's.** Didit's session statuses have no "submitted" (docs.didit.me → Verification statuses: `In Progress` is the person "actively completing verification steps" and goes straight to `Approved`, `Declined`, `In Review` or `Abandoned`), and the decision (`GET /v3/session/{id}/decision/`) carries no "flow finished" field. What says it is **`GET /v3/session/{id}/features/overrides/`** (docs.didit.me → Sessions API → List Feature Overrides, read 2026-11-23), which besides the overrides returns `person_in_flow` ("`true` while the session is `Not Started`, `In Progress` or `Resubmitted` and the person has not finished the flow") and `flow_finished_at` ("when the person last finished the flow. `null` while they are still in it, a resubmission included, and when the session closed before they finished"). `DiditKyc.decision` reads it after the decision, **only** for a session that is `In Progress` or `Resubmitted` (one more call per read while a check is undecided; none for `Not Started`, which is not submitted, or for a decided session). `KycDecision.isSubmitted` is then true when `person_in_flow` is false **and** `flow_finished_at` is set, false when Didit says otherwise, and **null when Didit didn't say** (the call failed, a 403, a body without the field). The per-feature statuses of the decision were not used: `Not Finished` means "not started or mid-run" for checks Didit runs by itself after the capture (screening, face match) as well as for the person's own steps, so "every feature has a status" would be a guess about each workflow.

**How it is kept** (`Verifications.apply`). `ramp.verifications.submitted` (V5, `boolean NOT NULL DEFAULT false`) is true only on a `PENDING` row. A provider answer of pending or resubmitted moves the row to `PENDING` with `submitted` as Didit says it, through the same guard as every outcome (`move` now compares status, reason **and** submitted), so a flip is applied once and a `kyc.updated` frame goes out when it flips (no push: `rampKycPush` words only the final outcomes and a resubmission). A null from the provider keeps what the row knew, so one failed read never sends anyone back to the capture or flaps a frame. Every other outcome clears it. After a resubmission request the row is `PENDING`, `resubmission_requested`, not submitted (the link works again); once the steps are redone it is `PENDING`, submitted, with no reason. `IN_REVIEW` reports `submitted: true` from its status alone.

**Kept fresh by what was already there.** A submitted row is still `PENDING`, so `GET /v1/ramp/kyc` and RM2 ask the provider at most every 5 seconds (`refreshIfDue`), the webhook rings `refresh`, and `ramp.sweep-verifications` re-reads it every 5 minutes. R0 and RM3, which otherwise answer from the row, ask the provider first when the row is submitted (also at most every 5 seconds), so a decision or a resubmission request whose webhook was lost isn't answered with "wait". A submitted check the provider never decides is still ended by the sweep at the session's expiry (`EXPIRED`, `session_expired`), which is the way out if Didit were ever wrong about "finished"; a later approval of that same session still applies, since the guard is the session id.

**Could not be confirmed** (no key was read and no real session was run; the sandbox application holds only seeded demo sessions, none `In Progress`):

- That a real session does sit in `In Progress` with `person_in_flow: false` and `flow_finished_at` set between the last capture step and the decision, and for how long. Didit's docs define the two fields exactly so, but show no example of that moment.
- That the ramp's API keys may call the overrides endpoint (the docs ask for `read:sessions` and, for a scoped key, read access to decisions; a full key has both). If not, the answer is a 403, `isSubmitted` is null and nothing changes from before this fix: look for `didit unavailable: operation=session_flow … status=403` in the log.
- Whether Didit sends a `status.updated` or `data.updated` webhook when the flow finishes without a status change. The design doesn't rely on one: the app's read after the SDK closes is what learns it, and the sweep is the net.
- How the hosted page (the portal) behaves: the callback to `/kyc/done` happens when the flow finishes, so RM2's read right after should already see `submitted`.
- The sandbox: its scenarios decide at once, so `submitted` may never be seen there for longer than a read.

**If the signal proves unreliable: the alternative, not built.** A small authenticated route, `POST /v1/ramp/kyc/submitted` (and the same for the merchant), that the app calls when the SDK reports completion. The server would never take the client's word as a decision: it would mark the row `submitted` only if Didit's session is still undecided and the row's session is the one named, keep `canStart` false for a bounded time (minutes), and go on reading the decision as now. That covers the app, not a person who finishes and kills the app before the call, which is why the provider's own signal was preferred.

**Not changed:** the ops gateway's verification lookup (`GET /ops/ramp/users/{userId}/verification`) and the merchant list still show `PENDING` without `submitted`.

## As built: the merchant engine (2026-11-23, `feat/ramp-engine`)

Approved by Pasaka for implementation on 2026-11-23. It replaces the first slice's pick (the least recently assigned merchant) with an engine that is a hard eligibility filter, a score per merchant, a weighted random draw that gives the whole failover order, a log of every routing, and settings staff change without a deploy. Everything the engine computes is a pure function (`domain/support/Scoring.kt`: `Scoring`, `WeightedDraw`, `Router`, `Stats`), tested as tables; what reads the database and the custodian is `EngineFacts`, and `Engine` joins the two.

### Eligibility (a hard filter, the same in every mode)

The pool is every merchant with a float wallet on the order's network and chain (at most 500). Each one is either eligible or **left out with one reason, the first that applies, in this order**:

| Reason | Left out because |
| --- | --- |
| `NOT_APPROVED` | Not `APPROVED` (applied only, or suspended) |
| `KYC_REQUIRED` | Not verified in the environment the order's network trusts, where that network requires KYC |
| `OFFLINE` | No trading window, or one that ends before the order's payment window does |
| `NO_KEYS` | This node doesn't hold their payment keys, or holds keys for another provider than the network's |
| `PAYMENTS_ISSUE` | Their provider refused a prompt for something only they can fix |
| `DIRECTION_OFF` | They don't offer this direction (`offers_onramp`, `offers_offramp`) |
| `OVER_LIMIT` | The order (KES) is above their limit: `order_limit_minor`, else their type's tier limit from the engine settings |
| `FLOAT_UNKNOWN` | Their float couldn't be read |
| `FLOAT_SHORT` | Their free float doesn't cover the order |

- **Float.** Buying: free USDC = the wallet's balance at the custodian − open reservations − commissions not yet swept, as before. Selling: a `kesFloat` the caller of the engine gives (`RoutingCommand.kesFloat: KesFloat?`, a function from merchant to free shillings); nobody passes one yet, so on an off-ramp everybody is `FLOAT_UNKNOWN`.
- **Cost.** The checks that need no float come first; floats are read (one custodian call each, made together) only for whoever passed them, and statistics only for whoever's float was read.
- **Under the row lock, again.** `Engine.reserve` locks the merchant's row and re-checks state, window, payments issue, chain, direction, limit and free float (the balance read at routing, less what is spoken for **now**) before the order and its reservation are written. A merchant who no longer fits is passed over (step `reserve_failed`) without counting as an attempt.

### Merchant attributes (V6 columns on `ramp.merchants`)

| Column | Default | Set by | Meaning |
| --- | --- | --- | --- |
| `merchant_type` | `INDIVIDUAL` | Staff (RO13) | `INDIVIDUAL` \| `BUSINESS`: which tier limit applies |
| `order_limit_minor` | null | Staff (RO13) | Their own limit for one order, KES minor units; null: the tier's default |
| `offers_onramp` | true | The merchant (RM17) | Takes purchases |
| `offers_offramp` | false | The merchant (RM17) | Takes sales (the off-ramp isn't open: the switch is kept for it) |

**Completed trades are derived, not stored:** `count(*)` of the merchant's commissions on the network (one commission is one completed trade; `commissions_merchant_idx`), in both directions together. Nothing to keep in step, and it is per network, so testnet trades never make a mainnet merchant look experienced.

### The order timeline (`ramp.order_events`)

`orders.updated_at` is overwritten on every move, so the times of the steps were lost. Now **every insert and every move of an order writes one row** (the state entered, when, and the failure reason when the move carried one) **in the same transaction**: the writes are inside `OrderRepositoryImpl.insert`, `move` and `markPaid` themselves, the only three places `orders.state` is ever written, so the app's processes and the ops process (`Releases`) both leave the timeline without knowing about it, and a move rolled back takes its event with it. Each row carries `merchant_id`, `network` and `direction`, so the statistics need no join. Nothing was backfilled: an order from before V6 has no timeline and the statistics don't see it. Read through `OrderEventRepository` (`timeline`, `counted`, `notCounted`).

### Merchant statistics

Per merchant × network × direction, over **the merchant's last N counted orders (default 50) that finished within D days (default 30)**, computed from the timeline by `Stats`:

| Statistic | Definition |
| --- | --- |
| `completed` | Orders that reached `COMPLETED` |
| `merchantFailures` | `FAILED` with a reason in the merchant's list below |
| `merchantExpiries` | `EXPIRED` with a reason in the merchant's list (`payout_missed`, the off-ramp's; nothing on the on-ramp expires because of a merchant) |
| `reversals` | M-Pesa reversals after release: **always 0** until reversal reports (RM11) are built |
| `counted` | `completed + merchantFailures + merchantExpiries + reversals`: the only orders a rate is taken over |
| `notCounted` | Every other order of theirs that finished in the D days: shown to the merchant and staff, never scored |
| `finished` | `counted + notCounted` |
| `medianHandlingSeconds`, `p90HandlingSeconds` | Of the completed orders in the window, nearest rank. **Handling time is `PAID → COMPLETED` when buying** (from the payment being verified to the release confirmed on chain: release speed) **and `FUNDED → COMPLETED` when selling** |

**Which reasons count against a merchant, and which never do:**

| Order ended | Reason | Counts as |
| --- | --- | --- |
| `FAILED` | `prompt_refused` (the provider refused this merchant's prompt) | Merchant failure |
| `FAILED` | `provider_balance` (their prepaid balance at the provider was empty) | Merchant failure |
| `FAILED` | `merchant_offline` (their keys were gone when the prompt had to go out) | Merchant failure |
| `FAILED` | `payout_failed` (selling; not produced yet) | Merchant failure |
| `EXPIRED` | `payout_missed` (selling; not produced yet) | Merchant expiry |
| `FAILED` | `payment_failed` (**the buyer ignored, declined or mistyped the M-Pesa prompt**: the provider reports the payment failed) | Not counted |
| `CANCELLED` | `cancelled` (the buyer cancelled before the prompt) | Not counted |
| `FAILED` | `provider_unreachable`, `prompt_failed`, `prompt_unconfirmed`, `provider_unavailable` (the provider, or which provider is offered) | Not counted |
| `EXPIRED` | `never_prompted` (the prompt never went out: Circaso's side) | Not counted |
| any | A reason nobody has classified | Not counted, until it is added to `Stats` on purpose |

- **`REVIEW` is not a finished state**: a person still decides it, so it is in no statistic.
- **Why the window is "last N counted" and not "last N finished":** with a window of finished orders, fifty buyers letting a prompt lapse would push a merchant's whole record out of the window and reset them to the priors. Buyers' behaviour must not move a merchant's score in either direction, so uncounted orders take no place in the window.
- **Cost and the cache.** Three indexed reads per merchant (`order_events_finished_idx` for the counted and the uncounted, `order_events_order_idx` for the `PAID` step of the completed ones) and one count of commissions. `MerchantStatsBook` keeps the result **30 seconds per process**: a finished order moves its merchant's score at most half a minute late.

### Scoring (as built: the formulas)

With `k` = `priorStrength`, `p0` = `completionPrior`, `f0` = `failurePrior`:

```
C = (completed + k·p0) / (counted + k)                         completion, pulled toward the prior while data is thin
F = (failed + k·f0) / (counted + k)                            failed = merchantFailures + merchantExpiries + reversals
S = 1                         when median ≤ target
    1 − (median − target) / (2·SLA − target)                   between them: a straight line
    0                         when median ≥ 2·SLA
    speedPrior                when nothing has been timed
Q = wc·C + ws·S + wf·(1 − F)

hunger   = hMax × min(1, minutes since last assigned / hWindow)        never assigned: hMax
newcomer = nMax × max(0, 1 − completed trades / newcomerTrades)
sizeFit  = 1                                                            when the order ≤ the small-order line (KES)
           min(1, free float / (order × cover))                         otherwise
           × sharePenalty                                               when the order > shareThreshold × free float

W = max(Q, floor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit
```

`free float` and `order` in `sizeFit` are in the float's own unit (USDC minor units when buying, shillings when selling); the small-order line and the limits are in KES. **No starvation:** the validation keeps `floor` and `sharePenalty` above zero and `cover` at least 1, so every eligible merchant's `W` is above zero. **The draw:** `WeightedDraw.rank` draws without replacement, each place going to one of those left with a chance in proportion to its weight; the first is the pick and the rest are the failover order. The random source is `kotlin.random.Random`, injected (`Random.Default` in the servers, a seeded one in tests).

### Modes

| `mode` | What picks | What is scored and logged |
| --- | --- | --- |
| `ROUND_ROBIN` | The least recently assigned eligible merchant (never assigned first) | Nothing: statistics aren't read |
| `SHADOW` | Round-robin, as above | Everything: the engine's own draw is in the log beside the order that was used (`engineRanked`) |
| `ACTIVE` | The engine's weighted draw | Everything |

Eligibility, limits and direction switches apply in all three.

### Engine settings (`ramp.engine_settings`: one row per network × direction)

Defaults (the seed, written once when a network and direction have no row):

| Setting | Default | Range |
| --- | --- | --- |
| `mode` | `ACTIVE` on TESTNET, `SHADOW` on MAINNET | `ROUND_ROBIN` \| `SHADOW` \| `ACTIVE` |
| `weightCompletion` (wc), `weightSpeed` (ws), `weightFailure` (wf) | 0.5, 0.3, 0.2 | each 0 to 1, adding up to 1 |
| `priorStrength` (k) | 10 | 0 to 1000 |
| `completionPrior` (p0) | 0.9 | 0 to 1 |
| `failurePrior` | 0.1 | 0 to 1 |
| `speedPrior` | 0.9 | 0 to 1 |
| `qualityFloor` | 0.1 | 0.001 to 1 |
| `alpha` | 1 | 0 to 5 |
| `hungerMax`, `hungerWindowMinutes` | 1.0, 120 | 0 to 10; 1 to 10080 |
| `newcomerMax`, `newcomerTrades` | 0.5, 20 | 0 to 10; 1 to 10000 |
| `smallOrderMinor` | 200000 (KES 2,000) | ≥ 0 |
| `floatCover` | 3 | 1 to 100 |
| `shareThreshold`, `sharePenalty` | 0.5, 0.25 | 0.001 to 1 each |
| `slaSeconds`, `targetSeconds` | 900 (15 min), 300 (5 min) | target 1 to 86400; SLA from the target to 86400 |
| `statsOrders`, `statsDays` | 50, 30 | 1 to 500; 1 to 365 |
| `maxAttempts` | 3 | 1 to 10 |
| `individualLimitMinor`, `businessLimitMinor` | 3000000 (KES 30,000), 15000000 (KES 150,000) | above 0 |
| `logRetentionDays` | 90 | 1 to 3650 |

- **Why a versioned JSON document and not columns.** The two dozen numbers are read and written whole and mean nothing apart; the routing log names the version that made each decision, so every version must be kept whole (`ramp.engine_settings_history`, written in the transaction of the change); and a setting added later is a field of the typed document with a default, not a migration. The document is typed on both sides: `EngineSettings` in the domain (its `problem()` is the validation), `SettingsDocument` (`@Serializable`) in `data`, decoded with unknown keys ignored and missing ones defaulted, so a row written by an older build still opens.
- **The house rule holds:** settings live in the database, are validated before they are written (`ramp.settings_invalid`, naming the field), version-checked (`SET version = version + 1 WHERE version = ?`; `ramp.settings_changed`), audited with a reason on the ops gateway (`ramp_engine_settings.update`, the line saying what changed by name) and the reason is kept on the version itself. Read through `EngineBook` at most every 10 seconds per process, like `SettingsBook`; on a database error the last read stays in force.
- **`failurePrior` and `speedPrior` are the backend session's additions** (the brief named p0 only): the failure rate is "smoothed the same way with its own prior", and a merchant with nothing timed needs a speed score. For Pasaka to confirm.

### Failover

`StartOnRamp` routes once (`Engine.route`: facts, plan, log row) and tries the merchants in the plan's order, **up to `maxAttempts` merchants** (a merchant passed over under the lock isn't one). Each attempt is a new order for the same quote (`orders.attempt`), as since 2026-11-22. What sending a prompt answers decides what happens next (`OrderFlow.requestPayment` → `Prompted`):

| What happened | The order ends | Next |
| --- | --- | --- |
| The provider accepted the prompt | `PAYMENT_REQUESTED` | Done (`ROUTED`) |
| The provider refused this merchant (`Rejected`) | `FAILED` `prompt_refused` | The next merchant |
| …for their prepaid balance (`ServiceBalanceLow`) | `FAILED` `provider_balance`; the merchant marked and taken offline | The next merchant |
| This node no longer held the merchant's keys | `FAILED` `merchant_offline` | The next merchant |
| **The request never left** (the client's circuit was open, or no connection was made) | `FAILED` `provider_unreachable` | The next merchant |
| **No answer, and it may have left** (a timeout, a dropped answer, a 5xx), **and the provider then says the prompt failed** | `FAILED` `prompt_failed` | The next merchant |
| …and the provider says the prompt is queued or paid | `PAYMENT_REQUESTED`, with the reference the provider answered to | Done: the prompt went out after all |
| …and the provider can't say | `FAILED` `prompt_unconfirmed`; the people on call alerted | **Stop**: `ramp.collections_unavailable`, nobody else is asked |

- **A buyer never gets two prompts for one quote without the first being closed.** After a failure with no answer, the provider is asked about the order **before anybody else is prompted**, by our own reference (the order id we sent as the prompt's reference; the provider never gave us one of its own). Only an answer that **names that very reference** counts. No such answer means nobody knows, and then the purchase stops.
- **What proves the request never left** is read from the failure's cause in `data` (`HttpPromptTransport`): `CircuitOpenException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, `UnresolvedAddressException`. A connect *timeout* is treated as "may have left" (the cautious side).
- **The `payhero` HTTP client never retries** (`maxAttempts = 1`, now set in `PayHeroHosts`, 2026-11-23). The shared client repeats a request answered 429 or 503, POSTs included, and the STK push has no idempotency key, so the client itself could have sent a buyer a second prompt. A 429 or 503 now comes back once as `Unavailable` with no cause: "may have left", checked as above. (Carried over when `feat/finoq-payouts` was merged into `feat/offramp`: every PayHero/FinoQ client in `PayHeroHosts` (`payhero`, `finoq`, `finoq-auth`, `finoq-payout`, `payhero-payout`) has `maxAttempts = 1`, proved by `IntegrationsModuleTest`.)
- **PayHero today.** Its status query takes PayHero's own reference or the M-Pesa code (docs.payhero.co.ke), not ours, so after a real timeout the check finds nothing and **the purchase stops rather than fails over**: the safe behaviour, and the same answer the buyer got before. Timeouts fail over only for a provider that can look a prompt up by our reference. **To make PayHero timeouts fail over too**, `MobileCollections` needs a "status by our reference" call that PayHero implements (if its API can) or answers "can't say": an `integrations` change, left to that module's owner.
- **An order closed as `prompt_unconfirmed` whose prompt did go out:** the float was freed and the order is `FAILED`, so if the provider later rings about it (`/v1/hooks/payhero`), nothing moves (the callback is unsigned) and the people on call are alerted again (`callback_after_unconfirmed_prompt`). A person then looks at the merchant's till.
- **The answer when nobody takes it** is the last merchant's: every merchant refused → `ramp.merchant_unavailable` (409); the provider unreachable for all → `ramp.collections_unavailable` (502); nobody eligible → `ramp.no_merchant_available` (409). The day's limit is counted once, as before.

### The routing log (`ramp.routing_decisions`)

One row per purchase routed, written **before the first attempt** (`outcome` `PENDING`, or `NO_CANDIDATE`) and finished when the routing ends, so a request that dies halfway still leaves what was decided.

| Field | Holds |
| --- | --- |
| `decision_id`, `quote_id`, `network`, `direction`, `created_at` | Which purchase |
| `fiat_amount_minor`, `need_minor` | The order in KES, and what the float had to cover |
| `settings_version`, `mode` | The engine settings that made it (readable in the history) |
| `candidates` (JSON) | Every merchant in the pool: its inputs (approved, kycCleared, online, hasKeys, paymentsIssue, offersDirection, limitMinor, floatMinor, lastAssignedAt, completedTrades, stats), `excluded` with the reason, or its `score` (completion, speed, failure, quality, hunger, newcomer, sizeFit, weight) |
| `ranked` (JSON) | `used`: the failover order that was used; `engine`: the engine's own draw (null in `ROUND_ROBIN`) |
| `steps` (JSON) | Each attempt: merchant, order, `prompted` or why it moved on or stopped, when |
| `outcome`, `merchant_id`, `order_id` | `ROUTED` \| `NO_CANDIDATE` \| `EXHAUSTED` \| `STOPPED` \| `PENDING`; who ended up with the order |

The three JSON columns are `text` holding typed documents (`data/support/EngineDocuments.kt`). Pruned by `ramp.prune-routing` (hourly) after the settings' `logRetentionDays`. The simulation and R1's "is anybody online" never write a row.

### Contracts

**Ops gateway** (`/v1/ops/ramp`, through `ramp:api` `RampEngineAdmin`; reads `ramp.read`: FINANCE, ADMIN; writes `ramp.manage`: ADMIN, with a reason and an `Idempotency-Key`):

```json
// RO9 GET /ops/ramp/engine-settings?network=TESTNET&direction=ON_RAMP → data
{ "network": "TESTNET", "direction": "ON_RAMP", "version": 1,
  "settings": { "mode": "ACTIVE", "weightCompletion": 0.5, "weightSpeed": 0.3, "weightFailure": 0.2,
    "priorStrength": 10.0, "completionPrior": 0.9, "failurePrior": 0.1, "speedPrior": 0.9, "qualityFloor": 0.1,
    "alpha": 1.0, "hungerMax": 1.0, "hungerWindowMinutes": 120, "newcomerMax": 0.5, "newcomerTrades": 20,
    "smallOrderMinor": "200000", "floatCover": 3.0, "shareThreshold": 0.5, "sharePenalty": 0.25,
    "slaSeconds": 900, "targetSeconds": 300, "statsOrders": 50, "statsDays": 30, "maxAttempts": 3,
    "individualLimitMinor": "3000000", "businessLimitMinor": "15000000", "logRetentionDays": 90 },
  "updatedBy": null, "reason": null, "updatedAt": "2026-11-23T09:00:00Z" }

// RO9 PUT /ops/ramp/engine-settings?network=&direction= ← the whole document; → the new version (the GET's shape)
{ "settings": { …as above… }, "version": 1, "reason": "Watching the engine before it picks" }
//   ops.reason_required · ramp.settings_invalid (details.fields names it) · ramp.settings_changed (409)

// RO10 POST /ops/ramp/engine-settings/simulate?network=&direction= ← { "settings": {…}?, "lastOrders": 50 } or { "amountMinor": "150000" }
{ "network": "TESTNET", "direction": "ON_RAMP", "mode": "ACTIVE", "currentMode": "ROUND_ROBIN",
  "orders": 20, "unroutable": 0,
  "shares": [ { "merchantId": "0193…", "displayName": "Crynce", "sharePercent": "62.5", "currentSharePercent": "50",
                "eligibleOrders": 20, "exclusions": {}, "quality": 0.91 },
              { "merchantId": "0193…", "displayName": "Bidii", "sharePercent": "37.5", "currentSharePercent": "50",
                "eligibleOrders": 19, "exclusions": { "FLOAT_SHORT": 1 }, "quality": 0.6 } ] }

// RO11 GET /ops/ramp/routing?network=TESTNET&merchantId=&orderId=&from=&to=&cursor=&limit= → data
{ "decisions": [ { "decisionId": "0193…", "createdAt": "2026-11-23T09:00:00Z", "quoteId": "0193…", "orderId": "0193…",
                   "merchantId": "0193…", "direction": "ON_RAMP", "fiatAmountMinor": "130000", "mode": "ACTIVE",
                   "outcome": "ROUTED", "settingsVersion": 3, "candidates": 3, "eligible": 2, "attempts": 2 } ],
  "nextCursor": "2026-11-23T09:00:00Z~0193…" }

// RO12 GET /ops/ramp/routing/{decisionId} → data
{ "decisionId": "0193…", "createdAt": "…", "network": "TESTNET", "direction": "ON_RAMP", "quoteId": "0193…",
  "orderId": "0193…", "merchantId": "0193…", "fiatAmountMinor": "130000", "needMinor": "9800000",
  "mode": "ACTIVE", "outcome": "ROUTED", "settingsVersion": 3,
  "candidates": [
    { "merchantId": "0193…a", "excluded": null, "approved": true, "kycCleared": true, "online": true, "hasKeys": true,
      "paymentsIssue": false, "offersDirection": true, "limitMinor": "3000000", "floatMinor": "100000000",
      "lastAssignedAt": null, "completedTrades": 4,
      "stats": { "finished": 6, "completed": 4, "merchantFailures": 1, "merchantExpiries": 0, "reversals": 0,
                 "counted": 5, "notCounted": 1, "medianHandlingSeconds": 12, "p90HandlingSeconds": 40 },
      "score": { "completion": 0.9, "speed": 1.0, "failure": 0.1, "quality": 0.93, "hunger": 1.0, "newcomer": 0.4,
                 "sizeFit": 1.0, "weight": 2.604 } },
    { "merchantId": "0193…c", "excluded": "FLOAT_SHORT", …, "floatMinor": "1000000", "stats": null, "score": null } ],
  "ranked": ["0193…a", "0193…b"], "engineRanked": ["0193…a", "0193…b"],
  "steps": [ { "merchantId": "0193…a", "orderId": "0193…", "result": "prompt_refused", "at": "…" },
             { "merchantId": "0193…b", "orderId": "0193…", "result": "prompted", "at": "…" } ] }

// RO13 PUT /ops/ramp/merchants/{id}/tier ← { "merchantType": "BUSINESS", "orderLimitMinor": "5000000" | null, "reason": "…" }
//   → the merchant as RO1 lists it, which now carries "merchantType", "orderLimitMinor", "offersOnRamp", "offersOffRamp"

// RO14 GET /ops/ramp/merchants/{id}/signals?network=TESTNET → data
{ "merchantId": "0193…", "network": "TESTNET", "merchantType": "INDIVIDUAL",
  "directions": [ { "direction": "ON_RAMP", "offered": true, "orderLimitMinor": "3000000",
                    "stats": { …as above… }, "trades": 4, "newcomerTrades": 20, "newcomerTradesLeft": 16,
                    "targetSeconds": 300, "slaSeconds": 900, "statsOrders": 50, "statsDays": 30 },
                  { "direction": "OFF_RAMP", "offered": false, … } ] }
```

- **RO10, the simulation.** The last real orders' amounts (each quote's first order; default 50, at most 500), or one amount, are routed again among **the merchants as they are now** (who is online, their float, their record), once under the draft and once under what is in force. The share is exact, not sampled: under `ACTIVE` a merchant's chance of the first pick is their weight over the eligible weights; under `ROUND_ROBIN` and `SHADOW` round-robin picks, an equal turn each. It reads floats from the custodian and nothing else leaves; no order, reservation, assignment or log row is written, and nothing is audited. It can't know who was online when the orders were really made.
- **RO11 by merchant** is the decisions that ended with that merchant; **by order** finds the purchase the order belongs to (through its quote), so a refused first attempt finds the routing too. `from` inclusive, `to` exclusive, both instants.

**Merchant portal** (`X-Merchant-Session`, `X-Wallet-Network`):

```json
// RM16 GET /ramp/merchant/performance → data   (your own figures only)
{ "network": "TESTNET", "merchantType": "INDIVIDUAL",
  "directions": [
    { "direction": "ON_RAMP", "offered": true,
      "orderLimit": { "unit": "KES", "amountMinor": "3000000", "amount": "30000" },
      "window": { "orders": 50, "days": 30 },
      "finished": 4, "counted": 3, "completed": 2, "failed": 1, "notCounted": 1,
      "completionRatePercent": "66.7", "failureRatePercent": "33.3",          // of `counted`; null with nothing counted
      "medianSeconds": 12, "p90Seconds": 40, "targetSeconds": 300, "slaSeconds": 900,
      "speed": "ON_TARGET",                                                   // NO_DATA | ON_TARGET | WITHIN_SLA | SLOW
      "trades": 2,                                                            // completed on this network, ever
      "newcomer": { "applies": true, "until": 20, "tradesLeft": 18 } },
    { "direction": "OFF_RAMP", "offered": false, …, "speed": "NO_DATA" } ] }

// RM17 PUT /ramp/merchant/offers ← { "onRamp": true, "offRamp": false }   (either may be left out) → RM2's body
// RM2 GET /ramp/merchant gains: "merchantType": "INDIVIDUAL", "offers": { "onRamp": true, "offRamp": false }
```

RM16 shows plain rates (completed and failed out of counted), not the smoothed ones the engine draws with, and never a weight, a rank or anything about another merchant. `failed` is `merchantFailures + merchantExpiries + reversals`.

### What is left for the off-ramp to wire (wired 2026-11-23: see "As built: the off-ramp")

The engine already takes `Direction.OFF_RAMP` end to end: its own settings row per network (seeded), `offers_offramp`, statistics from `FUNDED → COMPLETED`, the reasons `payout_failed` and `payout_missed`, and the routing log. When the off-ramp is built it has to:

1. **Pass `kesFloat`** on `RoutingCommand` (a merchant's free shillings at the payout provider, or null when it can't be read) and `RoutingOrder(OFF_RAMP, fiatMinor, needMinor = fiatMinor)`.
2. **Write its own reserve under the lock**: `Engine.reserve` writes an on-ramp float reservation; an off-ramp holds the USDC the user sent (`HOLD`) instead, after re-checking the same things.
3. **Add its states** to `OrderState` (`AWAITING_TRANSFER`, `FUNDED`, `PAYING_OUT`, `REFUNDED`, …): the timeline stores state names, `order_events_finished_idx` already covers `REFUNDED`, and `Stats.FINISHED` and `Stats.attribution` are where a new state or reason is classified.
4. **Close orders with the reasons the statistics know** (`payout_failed`, `payout_missed`) or classify new ones in `Stats`.

**Not built:** reversals in the statistics (they wait on RM11); a history route for past versions of the engine settings (the table has them, and each routing names its version); handing work to another node (a merchant unlocked on another node is `NO_KEYS` here, as before); the `rmp:float` cache (floats are read from the custodian for each routing).

## As built: verifying from the profile (2026-11-23, `feat/ramp-engine`)

Pasaka wants a person to see their identity verification in their profile and verify there, without starting a purchase.

- **R0 was refusing.** `POST /v1/ramp/kyc` and `GET /v1/ramp/kyc` both opened with the ramp's state gate, so they answered `ramp.unavailable` with the ramp `OFF`, and in `PREVIEW` for anyone not on the list. (`kyc_required: false` alone was never the problem: R0 already started a check where KYC wasn't required.)
- **Now verifying is not a trade.** Both routes take the network's settings whatever the ramp's state (`Gates.settingsOf`). `POST` needs an active account (`ramp.account_restricted`) and no block, on the user or the identity they verified with (`ramp.blocked`), then starts or resumes the check as before; where the network's identity provider isn't set up it answers `ramp.kyc_not_configured` (503). `GET` answers the state to anyone signed in, with `canStart: false` for someone barred. Neither ever answers `ramp.unavailable`.
- **Unchanged:** the daily cap, `submitted`, the frames and pushes, and every trading gate (R1, R2, R3 still need the ramp open to the person). The routes still sit in the wallet-network group, so the check runs for the network the request is for, in the provider environment that network trusts (the testnet: Didit's sandbox).

## As built: payouts through FinoQ, integrations only (2026-11-23, `feat/finoq-payouts`)

PayHero is becoming **FinoQ** (docs.payhero.africa, "FinoQ API 2.0.0", host `api.finoq.africa`) and its new docs describe a payout to an M-Pesa number. This supersedes "PayHero has no payouts" above. **Only the integrations half is built**: the contract, the PayHero/FinoQ client, the callback, the provider's fees, `log` and `fake`. The ramp's off-ramp (R7, `PAYING_OUT`, `ramp.reconcile`) is not built here and does not call any of it yet. Nothing below has run against a real account: the first real payout (the runbook at the end) is what confirms it.

### The contract (`integrations:api`)

```kotlin
interface MobileCollections {
    // requestPayment, paymentStatus, balance, verify: unchanged (the on-ramp)

    /** Sent once; never repeated by the client. Not a Resource: every case must be handled. */
    suspend fun sendPayout(credentials: CollectionCredentials, request: PayoutRequest): PayoutResult

    /** What the provider itself says became of a payout: the only thing an off-ramp may rest on. */
    suspend fun lookUpPayout(credentials: CollectionCredentials, lookup: PayoutLookup): PayoutStatus

    /** Payout and fee balances, each null when it could not be read (never zero), with the call that gave it. */
    suspend fun balances(credentials: CollectionCredentials, accountId: String? = null): Resource<ProviderBalances>
}

data class PayoutRequest(val payee: PhoneNumber, val amount: FiatAmount, val reference: String, val callbackUrl: String, val accountId: String)
data class PayoutReceipt(val providerReference: String, val secondaryReference: String? = null)

sealed interface PayoutResult {
    data class Accepted(val receipt: PayoutReceipt)                              // queued, not paid
    data class Rejected(val reason: PayoutRejection, val cost: FiatAmount? = null) // permanent, nothing sent
    data object InsufficientBalance                                              // nothing sent; the merchant tops up
    data object Unknown                                                          // MAY have been sent: look it up first
    data object Unavailable                                                      // provably nothing sent
}
enum class PayoutRejection { INVALID_REQUEST, AMOUNT_BELOW_COST, CREDENTIALS_REFUSED, NOT_SUPPORTED, REFUSED }

data class PayoutLookup(val reference: String, val providerReference: String? = null)
sealed interface PayoutStatus {
    data object Pending
    data class Paid(val providerReceipt: String?, val amount: FiatAmount?, val reference: String? = null)
    data class Failed(val reason: String)             // the provider's status word: FAILED, CANCELLED…
    data class NotFound(val by: PayoutReference)      // PROVIDER (its own reference) or OURS (weaker, see below)
    data object Unknown
}

data class ProviderBalances(val payout: BalanceReading?, val fees: BalanceReading?)
data class BalanceReading(val amount: FiatAmount, val source: String)

interface CollectionNotifications {
    suspend fun onPaymentCallback(callbackKey: String, reference: String): Resource<Unit>
    suspend fun onPayoutCallback(callbackKey: String, reference: String): Resource<Unit>   // a doorbell, like the first
}

interface ProviderFees {
    suspend fun collectionFee(provider: String, amount: FiatAmount): ProviderFee
    suspend fun payoutFee(provider: String, amount: FiatAmount): ProviderFee
}
data class ProviderFee(val fee: FiatAmount?, val source: FeeSource, val asOf: Instant? = null)   // fee null iff UNKNOWN
enum class FeeSource { LIVE_TABLE, CACHED, UNKNOWN }
```

The old `payout` and `payoutStatus` (which returned a `Resource` and could not tell "may have been sent" from "nothing was sent") stay in the interface with default bodies that answer `NotImplemented`, only because the ramp's test fake still overrides them; they go when the ramp moves over. `balance` stays as the on-ramp's fee-floor check; its `payout` field is not a reading.

### What the ramp must do to use it (built 2026-11-23: see "As built: the off-ramp")

- **Collect one more thing from the merchant: the account id.** FinoQ's withdraw needs `account_id`, the integer id of the merchant's account ("team") on its dashboard. RM5 and the portal collect username, password and channel id today. The id is not a secret; it can be a plain column on the merchant or a fourth field in the envelope. It is passed as `PayoutRequest.accountId` and to `balances`.
- **Send once.** Persist the order as `PAYING_OUT` **before** calling `sendPayout`, with the order id as `reference`. Never call it twice for one order unless a lookup has shown the first did not go out: nothing in FinoQ's docs says a repeated `external_reference` is paid once.
- **On each result:**

| `sendPayout` answers | The order | Next |
| --- | --- | --- |
| `Accepted(receipt)` | stays `PAYING_OUT`; store `receipt.providerReference` (and the secondary one) | wait for the doorbell or the reconcile job, then `lookUpPayout` |
| `Rejected(reason, cost)` | nothing was sent: back to `FUNDED` for another merchant, or refund | `CREDENTIALS_REFUSED` and `INVALID_REQUEST` take the merchant offline with a reason; `AMOUNT_BELOW_COST` is a quote that should not have been given (`cost` is the provider's charge) |
| `InsufficientBalance` | nothing was sent: back to `FUNDED` | the merchant is off the off-ramp until `balances` shows enough; they are told to top up |
| `Unknown` | stays `PAYING_OUT`, marked "outcome unknown" | **`lookUpPayout` before anything else**; no resend, no refund, no other merchant |
| `Unavailable` | nothing was sent: stays `FUNDED` | try again later or another merchant |

  A call that is cancelled or throws (a caller's own timeout around `sendPayout`) is `Unknown` too.
- **Only `lookUpPayout` decides.** `Paid` completes the order, after checking `amount` and `reference` against the order where they are given. `Failed` allows a resend or the refund. `Pending` waits. `Unknown` waits and is asked again. `NotFound(PROVIDER)` (the provider denies a reference it gave us) and `NotFound(OURS)` after an `Unknown` send both go to **`REVIEW` for staff**, not to a resend: whether FinoQ's lookups search by our reference is unconfirmed, so "not found by ours" is not yet proof that nothing was sent. Once the first real payouts show that a lookup by `external_reference` works, `NotFound(OURS)` can allow a resend.
- **The doorbell.** Implement `onPayoutCallback(callbackKey, reference)` in `RampCollectionNotifications`: check the key is the merchant's and the reference is an order of theirs in `PAYING_OUT`, then run the lookup. It can ring twice, and a callback whose layout doesn't say whether it is a payment or a payout rings **both** bells, so each handler ignores a reference that isn't its kind. The same callback address is sent with payouts as with prompts.
- **Float.** Before picking a merchant for an off-ramp, `balances(credentials, accountId)`: `payout` must cover the amount **plus** `ProviderFees.payoutFee`, and a null `payout` means "unknown", which must not be treated as enough or as empty without a decision.
- **Fees.** `ProviderFees` is bound in integrations (`PayHeroFees`). When it answers `UNKNOWN`, fall back to the table staff set in the admin.

### What the client does

- **Hosts and their order** (`PayHeroHosts`, constants in code):
  - Collect, its status, `verify`, the on-ramp's `balance`: `https://backend.payhero.co.ke` only. Unchanged.
  - Payout: `POST /api/v2/withdraw` on `https://api.finoq.africa`, then on `https://backend.payhero.co.ke`. It moves to the second host **only when the first provably took nothing**: it could not be reached, it refused the credentials (`401`/`403`), or it has no such route (a bare `404`/`405`). After a timeout, a 5xx or any answer it can't read, no other host is asked.
  - Payout status: `POST /api/global/transaction-status` `{ "request_id": … }` on the new host, then `GET /api/v2/transaction-status?reference=…` on the old one; by the provider's reference first, then by ours.
  - Balances: superseded 2026-11-24 by three wallets read apart, never one for another (see "As built: three provider wallets").
- **Never retried.** The two payout clients (`finoq-payout`, `payhero-payout`) have `maxAttempts = 1`: the shared client would otherwise repeat a POST on `429` or `503`. Since the merge into `feat/offramp` the three read clients do too, so no PayHero/FinoQ POST (the STK push, the payout, FinoQ's POST status read) is ever repeated by the client.
- **The request:** `external_reference` (the order id), `account_id`, `amount` (whole shillings), `phone_number` and `account_number` (the local form, `0712345678`), `network_code: "63902"`, `channel: "phone"`, `recipient_type: "MSISDN"`, `callback_url`. An amount with cents, another currency or an account id that isn't a number is `Rejected(INVALID_REQUEST)` without a call.
- **Reading the answer:** a 2xx with a reference is `Accepted`; a 2xx without one, a 5xx, a `408` and a timeout are `Unknown`; a `429` is `Unavailable`; any other 4xx is a rejection, told apart by the provider's words: "wont cater for cost" → `AMOUNT_BELOW_COST` (the cost in brackets is handed on, not logged), "insufficient balance/funds/float" → `InsufficientBalance`, `invalid_argument`/`invalid_arguments` → `INVALID_REQUEST`, anything else → `REFUSED`.
- **Reading a status:** the status word is looked for at the top level, or inside `data`/`response`/`transaction` when there is such a wrapper (a wrapper's own `status: "success"` is never read as "paid"). `SUCCESS`/`COMPLETED`/`PAID`… → `Paid`; `FAILED`/`CANCELLED`/`REJECTED`/`REVERSED`/`EXPIRED`… → `Failed`; `QUEUED`/`PENDING`/`PROCESSING`… → `Pending`; any other word → `Unknown`. A `404`, or words like "not found", is "not known to this lookup". An answer about another `external_reference` is not used.
- **The callback** (`/v1/hooks/payhero/{key}`, the same keyed route; the gateway is unchanged): both documented layouts are read. FinoQ's flat one carries `transaction_type`: `inbound_payment` rings `onPaymentCallback`, `outbound_payment` rings `onPayoutCallback`. The old nested one (`response.ExternalReference`) carries no type and rings both. Only our reference is taken; `status` is used for nothing but the delivery id (so a second callback that says something new isn't dropped as a repeat). A layout with no reference rings nothing; the gateway has already answered `200`.
- **Layouts are logged, values never.** The first time a layout of each answer or callback is seen, one INFO line: `payhero layout first seen: what=<call> status=<http> shape={field:kind,…}` (field names and `string`/`number`/`bool`/`null` only, long digit runs in a name masked). Bodies, numbers and amounts are never logged; a refusal's reason is logged with every digit masked.
- **Fees** (`PayHeroFees`): collections from `GET /api/transaction_fees` (unauthenticated; the old host first, because that is where the prompt goes, then the new one; they differed below KES 50 on 2026-11-23; a row whose `amount_to` is below its `amount_from` is dropped). Payouts from `GET https://api.payhero.africa/api/pricing` (the public pricing page's data): bands with a fee at the first and last amount, **interpolated in a straight line and rounded up to the cent**. That formula is ours, not published; the bands read as a flat part plus 0.8 %, which a straight line fits exactly. A table is fetched on first use, kept in memory per node, refreshed after 6 hours, served as `CACHED` while a refresh fails for up to 7 days, then `UNKNOWN`; a failed fetch isn't retried for 5 minutes.
- **`log` and `fake`:** `LogCollections` accepts a payout, reports it paid after 3 seconds and rings the payout doorbell on the callback address's key. `FakeCollections` is scripted with `nextPayout` (`ACCEPT`, `REJECT`, `INSUFFICIENT_BALANCE`, `UNKNOWN_SENT`, `UNKNOWN_NOT_SENT`, `UNAVAILABLE`), `completePayout`, `failPayout`, `ringPayout(…, times = 2)` and `payoutBalanceMinor`; `FakeProviderFees` answers what a test sets.

### Confirmed and unconfirmed

**Confirmed from the docs (read 2026-11-23):** the withdraw request and its 200 (`status: QUEUED`, `merchant_reference`, `checkout_request_id`, `conversation_id`); HTTP Basic with the account's API username and password; the error body (`error_code`, `error_message`, `status_code`, `invalid_arguments[]`); "a 4xx is a permanent rejection" and "a timeout or 5xx may still have started the payment"; the flat callback with `transaction_type`; the new status lookup's request; the two fee tables.

**Not confirmed (each is what the code assumes):**

- That the withdraw endpoint is enabled for a merchant's account, and on which host. Assumed: the new host, the old one as a fallback.
- That the same API username and password work on `api.finoq.africa` and `backend.payhero.co.ke`. The new docs only say the API and auth hosts share a key. Assumed: tried on both.
- Which callback layout a withdraw sends, whether it is signed (assumed: no), whether it is retried (assumed: no; the reconcile job must poll).
- The **status answer's fields**, on both lookups, for a payout. Assumed: a `status` word as above, `provider_reference`/`third_party_reference` as the M-Pesa receipt, `amount`, `external_reference`.
- Whether either lookup finds a payout by **our** reference. Assumed: maybe; hence `NotFound(OURS)` is not trusted.
- Idempotency. Assumed: none; a repeated `external_reference` may pay twice.
- **Which balance a payout spends**, and how to read it: the "payment wallet" (the owner's and the old dashboard's name) has no documented query, and the new account balance has no documented answer. Assumed: the names in "What the client does"; unread is null.
- The payout fee formula, and whether the fee is taken from the payout balance, the service wallet or the amount. Assumed: the pricing page's bands, linear.
- The words FinoQ uses for a balance refusal on a withdraw. Assumed: "insufficient … balance/funds/float".
- The phone format `phone_number` wants on withdraw. Assumed: the docs' example, `0712345678`.

### Runbook: first real payout (KES 10): what to look for in the logs

The off-ramp isn't wired, so the first payout is made by whatever calls `sendPayout` once (a one-off ops action or a test hook, with a real merchant's credentials and account id), followed by `lookUpPayout` and `balances`. Raise nothing: every line below is INFO or WARN under `com.circaso.integrations.impl.payhero` (`LOG_LEVEL_INTEGRATIONS`). Expect the provider's charge on KES 10 to be about **KES 0.09** if the published bands are linear.

1. **Before sending, `balances`.** Look for `payhero balances: payout=<source|unread> fees=<source|unread>` and the `payhero layout first seen: what=payhero.wallets.payment_wallet …`, `what=finoq.account.balance …` lines. They show which call answered and the field names of each. `payout=unread` with a 200 layout line means the balance is there under a name the client doesn't read yet: add the name to `BalanceReply`.
2. **The send.** `attempt … url.path=/api/v2/withdraw http.status=…` (the shared client's line, with the vendor name: `finoq-payout` or `payhero-payout`), then `payhero payout: host=<finoq|payhero> status=<http> outcome=<…> reason=<masked> fields=<…>`.
   - `outcome=accepted`: note the layout line `what=withdraw status=200 shape={…}` and check it has `merchant_reference`.
   - `outcome=next_host:CREDENTIALS` or `next_host:NO_ROUTE` on `finoq`, then an answer from `payhero`: the account lives on the old host. Record which.
   - `outcome=rejected:AMOUNT_BELOW_COST`: KES 10 is under the charge; the caller's `Rejected.cost` has the number. Send more.
   - `outcome=rejected:INVALID_REQUEST fields=…`: the named fields are wrong (most likely `account_id` or the phone format).
   - `outcome=insufficient_balance`: the payout wallet is empty. If the reason was a balance refusal but the outcome is `rejected:REFUSED`, the wording is new: add it to `LOW_BALANCE`.
   - `outcome=unknown`: **do not send again.** Go to step 4 and check the M-Pesa statement.
3. **The callback.** `payhero layout first seen: what=callback status=- shape={…}` and `payhero callback: layout=<flat|nested> about=<PAYOUT|PAYMENT|EITHER>`. `about=PAYOUT` means FinoQ's flat layout with `transaction_type`. `about=EITHER` means it sent the old layout (or no type). No callback line within a few minutes: callbacks may not be sent for withdraws, or went to an address set on the dashboard instead; the gateway's `webhook` lines say whether anything arrived. `webhook not translated: vendor=payhero code=integrations.not_implemented` is expected until the ramp implements `onPayoutCallback`.
4. **The lookup.** `payhero payout status: source=<payout_status|payout_status_legacy> status=<http> read=<Paid|Pending|Failed|not_found|unread>` and the layout lines `what=payout_status …`, `what=payout_status_legacy …`. `read=unread` with `no status this client knows (words=…)` names the status word to add. Do it twice: once with the provider's reference, once with **only ours** (`PayoutLookup(reference, null)`), to learn whether a lookup by our reference finds it.
5. **After it is paid.** `balances` again: the difference in the payout balance, less KES 10, is the real fee; the service wallet's difference shows whether the fee came from there instead. Compare with `ProviderFees.payoutFee`. Check the M-Pesa message for the receipt and compare it with `Paid.providerReceipt`.
6. **Write down** in "Confirmed and unconfirmed": the host, the callback layout and whether it repeated, each status layout and word, whether our reference is searchable, the balance source that moved, the fee, and what a second send of the same reference does (only if FinoQ says in writing that it is safe to try).

## As built: the off-ramp (2026-11-23, `feat/offramp`)

Approved by Pasaka for implementation on 2026-11-23, on `feat/ramp-engine` merged with `feat/finoq-payouts` (the merge keeps every PayHero/FinoQ client at `maxAttempts = 1`, so no POST to them is ever repeated by the client; `IntegrationsModuleTest` proves it for all five). A user sells USDC and is paid shillings to their own verified M-Pesa number from a merchant's FinoQ account. This replaces "How an off-ramp runs (designed, waiting on a payout provider)" and "What the ramp must do to use it (not built)" above; the semi-manual payout (RM12, RM13, R8, R9) is **dropped for now** and not built.

**Decided by Pasaka (2026-11-23):** users never pick a merchant (the engine routes the sale); merchants hold their own KES in their own FinoQ account and top it up themselves (collections are unchanged); the provider's payout fee is added to the user's cost, shown in the quote beside Circaso's percentage, and the merchant receives their full amount; selling uses the same `fee_bps` and merchant/Circaso split as buying, Circaso's share swept daily as today; the order stores the provider fee quoted and actual; only the order's amount plus its payout fee is held at the merchant's provider; merchant credentials stay in the PIN envelope and the trading session; FinoQ's account id is a plain column.

### The state machine

Every move is a transition guard (`SaleStates`), with what it holds or frees in the same transaction and a timeline row (`ramp.order_events`):

| Next state | From | When | Holds |
| --- | --- | --- | --- |
| `AWAITING_TRANSFER` | (R7) | The engine routed the sale; the deadline is `transfer_window_seconds` (900) | KES hold `OPEN` (payout + payout fee) |
| `FUNDED` | `AWAITING_TRANSFER` | The custodian recorded exactly the order's USDC from the user's wallet in the merchant's float | USDC `HOLD` reservation `OPEN` |
| `FUNDED` | `PAYING_OUT` | Provably nothing was paid (refused, no balance, provider unreachable, or the lookup said `Failed` once): sent again later | unchanged |
| `PAYING_OUT` | `FUNDED` | The attempt is written (number, our reference, `SENDING`) **before** `sendPayout` | unchanged |
| `COMPLETED` | `PAYING_OUT`, `REVIEW` | The provider's lookup says `Paid` (amount and our reference checked where given), or staff mark it paid with the M-Pesa receipt | USDC hold `CONSUMED` (the USDC is the merchant's), KES hold `CONSUMED`, commission, `ramp.OffRampCompleted` |
| `EXPIRED` | `AWAITING_TRANSFER` | No transfer known by the deadline (`never_funded`; never counted) | KES hold `RELEASED`, the day given back |
| `CANCELLED` | `AWAITING_TRANSFER` | R6 before any transfer is known | KES hold `RELEASED` |
| `REFUNDING` | `AWAITING_TRANSFER` (wrong amount), `FUNDED` (nothing sent, window over), `PAYING_OUT` (only after provably nothing was paid), `EXPIRED`/`CANCELLED` (late transfer), `REVIEW` (staff) | The user is owed their USDC | KES hold `RELEASED`; USDC held until it leaves |
| `REFUNDED` | `REFUNDING` | The ops process's refund confirmed on chain | USDC hold `RELEASED`, `ramp.OrderFailed` with the reason |
| `REVIEW` | `AWAITING_TRANSFER`, `FUNDED`, `PAYING_OUT`, `REFUNDING`, `EXPIRED`, `CANCELLED` | A person must decide | Everything stays held |

`OrderState.OPEN` now includes `AWAITING_TRANSFER`, `FUNDED`, `PAYING_OUT` and `REFUNDING` (`orders_user_open_key` too: one open order per user, either direction). `OrderState.NEEDS_KEYS` (the purchase's two waiting states and the sale's three) is what keeps a merchant's keys on the node after they go offline.

### Pricing (R2, `direction: OFF_RAMP`)

`SalePricing`, pure. The rate is the **sell rate** (`Pricing.rate(network, OFF_RAMP)`; until 2026-11-24 the reference rate: see "As built: rates"). From USDC `X`: `gross = floor_cent(X × rate)`, `fee = ceil_cent(gross × fee_bps)`, the provider's payout fee asked **of `gross − fee`** (never less than the fee of what is finally paid), `receive = floor_shilling(gross − fee − providerFee)`. From shillings `Y` (whole): `providerFee = fee(Y)`, `X = ceil_6dp((Y + providerFee) / (1 − fee_bps) / rate)`, `fee = floor_cent(X × rate) − Y − providerFee` (never below the rate: the rounding goes to the fee). The payout fee is `ProviderFees.payoutFee(network's provider, amount)` when it is `LIVE_TABLE` or `CACHED`, else the first band of `settings.payout_fee_bands` that covers the amount (`ADMIN_TABLE`), else **no quote** (`ramp.payout_fee_unavailable`, 502). The quote keeps `provider_fee_minor` and its source; the order copies them. Limits are selling's own (`offramp_min/max/daily_limit_minor`, on what the user receives); the day is counted on its own Redis key (`rmp:sold:…`).

### The order (R7) and the engine

`StartOffRamp`: selling's gate (`offramp_state`; `PREVIEW` uses buying's list), the account, the block and KYC gates, the quote (the user's, a sale's, alive), one open order, **the phone is the account's own verified number**, the user's own wallet address on the chain (kept as `refund_address`: where the USDC must come from and where a refund goes), the day. Then the engine, as "What is left for the off-ramp to wire" asked:

1. `kesFloat` is `ProviderBalancesReader`: the merchant's payout balance read **live** with their unlocked keys (`MobileCollections.balances(keys, accountId).payout`), null when it can't be read; `EngineFacts` subtracts what their open sales hold (`payout_holds`, both networks: one account pays for both). `RoutingOrder(OFF_RAMP, fiatMinor = receive, needMinor = receive + providerFee)`.
2. `Engine.reserve` re-checks under the row lock (window = the transfer window, `hasIssue(OFF_RAMP)`, `offers(OFF_RAMP)` which also needs a payout account id, the limit, and the **live balance less the holds now**) and writes the order with a `payout_holds` row instead of a float reservation. The USDC `HOLD` is written only when the USDC has arrived.
3. The states are in `OrderState`; `Stats.FINISHED` includes `REFUNDED`; a `REFUNDED` sale counts against the merchant when its reason is `payout_failed` (failure) or `payout_missed` (expiry). `never_funded`, `late_transfer`, `amount_mismatch`, `payout_unavailable`, `payout_rejected` and `resolved_refund` are never counted. Handling time is `FUNDED → COMPLETED`.
4. Every merchant tried is a routing step (`held` or `reserve_failed`); nothing is sent at routing, so every eligible merchant may be tried (no failover by attempts).

A merchant who only sells needs no USDC float to go online (`missing` asks for `float` only from one who buys).

### How inbound USDC is confirmed (the safest of the two options)

**The ramp confirms it itself, from the custodian's own record of the receiving wallet; the wallet module's handling of Circle's webhook is untouched** (it still drops every developer-wallet update, as its invariants require: a fee wallet's or a merchant's transactions are nobody's payment there).

1. W4 takes `rampOrderId` (wallet.md). The wallet keeps one live transfer per order (`transfers_ramp_order_key`), so a sale can't be funded twice from the app.
2. The wallet's `wallet.TransferConfirmed` carries `rampOrderId`; the ramp's consumer group `ramp` on `events:wallet` (`WalletEventHandler`) records the transfer on the sale once (`orders.wallet_transfer_id`, `orders_wallet_transfer_key`) and checks it (`SaleFunding`).
3. The wallet's own record (`Wallets.transfer`): the user's (not a group's), `CONFIRMED`, to this merchant's float address, on the sale's chain, with a hash.
4. The custodian's record of that very chain transaction on the merchant's wallet (`MerchantFloat.deposits`, new in `integrations:api`: Circle's `GET /v1/w3s/transactions?txHash=&blockchain=` with the developer's key, narrowed to that wallet's incoming transfers): `CONFIRMED`, **from the user's own address**, **of the USDC contract the network's settings name** (`UsdcContracts`). Its amount is what the sale is funded with.
5. Exactly the order's amount: `FUNDED`, the USDC held, and the payout is sent at once when this node holds the merchant's keys. Another amount: `REFUNDING` (`amount_mismatch`), all of it goes back. Another coin: `REVIEW` (`wrong_token`). Another sender, address or chain: `REVIEW` (`transfer_mismatch`). Arrived after the sale expired or was cancelled: `REFUNDING` (`late_transfer`). Not recorded yet, or the custodian out of reach: nothing changes; `ramp.payouts` checks every 10 s and, after 30 minutes without a record, sends it to `REVIEW` (`funding_unconfirmed`, still checked: a record that turns up is held for staff). A sale whose transfer is known is never expired.

Why not have the wallet pass developer-wallet updates on: it would have to tell a merchant float from Circaso's fee wallets inside the wallet, which knows nothing of merchants, and the ramp would still have to re-read Circle to trust it. Reading the receiving wallet's record directly is one call, with the API key the app already has, and doesn't depend on a webhook arriving.

**Locally** (`log` custody) `LogMerchantFloat.deposits` reports the deposit asked for as arrived (no coin moves); the `fake` reports what a test recorded.

### The payout (`SalePayouts`, the app's process)

The table "On each result" of "As built: payouts through FinoQ" is applied exactly, with these choices:

- **Our reference** is the order id for the first attempt and `<orderId>-<n>` after; the callback and the lookup find the sale by its first 36 characters.
- `Accepted`: stays `PAYING_OUT` (`ACCEPTED`, the provider's references kept); a merchant payout issue is cleared.
- `Unknown` (and a call that throws or is cancelled): `UNKNOWN`, looked up at once by our reference; **never sent again, never refunded on this**.
- `Rejected(AMOUNT_BELOW_COST | NOT_SUPPORTED)`: refunded at once (`payout_rejected`): asking again can't change it.
- `Rejected(CREDENTIALS_REFUSED | INVALID_REQUEST | REFUSED)`: the merchant is marked `PAYOUT_REFUSED` (no sale is routed to them; cleared when a payout goes out, they go online again, or they set a new account id), alerted, and the **same merchant** is tried again in 2 minutes. `InsufficientBalance`: marked `PAYOUT_BALANCE`, the same. `Unavailable`: tried again in 30 s. Each `FUNDED` again with `payout_retry_at`. Once the **payout window** (`payout_window_seconds`, 1800, from funding) is over: refunded, `payout_failed` (counted) or `payout_unavailable` (the provider: not counted). The user's USDC can't go to another merchant: it is in this one's float.
- A merchant whose keys aren't on the node (offline, a restart) or who has no account id: the sale waits in `FUNDED`; after the window it is refunded (`payout_missed`, counted).
- **Only the lookup decides.** `Paid` with the amount and our reference matching where given → `COMPLETED`; otherwise `REVIEW` (`payout_mismatch`). `Failed` → one more attempt within the window (`payout_failures`), a second → refunded (`payout_failed`). `NotFound(PROVIDER)` → `REVIEW` (`payout_not_found`); `NotFound(OURS)` → `REVIEW` (`payout_unconfirmed`): **never a resend or a refund**. `Pending`/`Unknown` wait; still so when the payout window is over → `REVIEW` (`payout_unresolved`), never a refund. A sale in `REVIEW` whose payout the provider later confirms (its callback, or staff's re-check) still completes.
- **The callback** (`onPayoutCallback`, now implemented) checks the key is the merchant's and the reference is their sale's current attempt (or their test payout), then only looks up. A second ring looks up again and changes nothing.
- **The provider fee actual** is the provider's published tariff when the payout is sent (`provider_fee_actual_minor`), since FinoQ returns no fee; the test payout's balance difference is what measures the real charge.

### The refund (`SaleRefunds`, the ops process)

`ramp.release` now also advances `REFUNDING` sales: `TreasuryCustody.send` from the merchant's float of **`funded_minor`** (what the custodian recorded arriving) to **`refund_address`**, key `nameUUID("ramp:refund:<orderId>")`, `refId` the order; the transaction kept on the order (`custody_tx_id`), followed every run; `CONFIRMED` → `REFUNDED`; `FAILED` → `REVIEW` (`refund_failed`); refused → `REVIEW` (`refund_refused`); unreachable → asked again with the same key. **A refund never starts while a payout may have gone out**: `REFUNDING` is entered only from where none can be out (see the table).

### Staff (RO4, both directions)

`POST /ops/ramp/orders/{orderId}/resolve` `{ resolution, receipt?, reason }`, ADMIN, audited (`ramp_order.resolve` on the new target `ramp_order`; the receipt never reaches the audit line). Only an order in `REVIEW`.

| Resolution | A sale | A purchase |
| --- | --- | --- |
| `MARK_PAID` (receipt required) | `COMPLETED`, as a confirmed payout (commission, holds consumed); refused when the USDC never arrived or a refund was sent, and when the receipt settled another order | `PAID` (its float held again if it was given back: paid after expiry), and the ops process releases; refused once a release was sent |
| `REFUND` | `REFUNDING`; refused when the USDC never arrived, when a refund was already sent (one that failed on chain must be sent by hand), and `ramp.payout_may_be_paid` when the last payout outcome was `PAID`. **Staff decide a payout didn't go out** (the merchant's M-Pesa statement, FinoQ's dashboard) | `FAILED` (`resolved_refund`), its float freed; the shillings are returned by hand |
| `RECHECK` | Queued (`recheck_requested_at`): the app's process (which alone holds the merchant's keys) looks the payout up on its next sweep, or checks a pending funding; a confirmed payout completes it | Queued the same way: the payment is asked about; paid and matching with nothing released → `PAID`; failed → `FAILED` |

A re-check while the merchant is offline asks nobody; ask again once they are online.

### The merchant

- **The payout account id** (FinoQ's, digits, not a secret): `merchants.payout_account_id`, set with RM5 (`accountId`, optional) or `PUT /ramp/merchant/payments/account` `{ accountId }` (RM5b, no PIN; answers RM2; clears a payout refusal).
- **RM2's `offRamp` card**: `{ offered, accountId, payoutBalance: { amount, source } | null, feeBalance | null, balancesLive, balancesReadAt, held, payoutIssue: { code, since } | null, flags }`. Balances are read live while the merchant is online on this node (at most every 30 s) and kept on the merchant row for staff (`payout_balance_minor`, `fee_balance_minor`, their sources, `balances_read_at`); null is **unknown**, never zero. `flags`: `payout_account_missing`, `payout_balance_low` (below `payout_balance_low_minor`), `service_balance_low` (below `provider_fee_floor_minor`), `provider_balance` (the existing service-balance issue), `payout_balance`, `payout_refused`.
- **The test payout (RM18)**: `POST /ramp/merchant/test-payout` `{ amountMinor }` (1000 to 10000, whole shillings) and `GET /ramp/merchant/test-payout[?testId=]`. From the merchant's own account to **their own contact phone, never any other number**; needs the account id, a callback address and the merchant online (`ramp.merchant_offline`); one in flight (`ramp.test_payout_in_progress`); written `SENDING` before it is sent; the same result rules (`Unknown` looked up at once, never resent); `GET` looks it up while it is open (at most every 3 s), and the `ramp.payouts` job and the callback do too. It records the payout balance before and after (`charged` = before − after − amount: the provider's real fee) and the provider's published fee. Table `ramp.test_payouts`.

### The webhook signing secret (2026-11-23, V8)

The owner: "FinoQ webhooks also have a signing secret; add that in the dashboard." Each merchant's FinoQ dashboard shows one, so each merchant enters theirs on the portal (RM5c).

**What FinoQ documents: nothing.** docs.payhero.africa (every page in its sitemap, including Guides → Callbacks & Webhooks and API reference → Webhooks → Transaction result callback), docs.payhero.co.ke (Payment callback and every other page) and the `PAY-HERO-KENYA` GitHub organisation (its PHP package and deposit sample) name no signature header, algorithm, signed string, encoding, timestamp or tolerance, and don't say where the secret is shown (read 2026-11-23). The callback reference lists `basicAuth` under Authorizations, which reads as the spec's global setting for our calls to FinoQ, not something FinoQ sends us.

**Stored:** `merchants.webhook_secret_sealed` (AES-256-GCM under `HMAC-SHA256(RAMP_CREDENTIALS_MASTER_KEY, "ramp:webhook-secret:v1")`, the merchant id as additional data; `MasterKeyWebhookSecrets`) and `webhook_secret_updated_at`, set and cleared together (a `CHECK`). **Not under the PIN**, unlike the API credentials: callbacks arrive while the merchant is offline, when nothing sealed under their PIN can be opened, and the secret only tells FinoQ's callbacks from forgeries. A callback is a doorbell (the ramp re-reads every outcome from FinoQ), so the secret moves no money; its leak would let someone ring a doorbell that the unguessable callback address already guards. Setting it needs the callback address (RM5; `ramp.merchant_setup_incomplete` otherwise) and the master key (`ramp.vault_unavailable`). A secret that no longer opens (another master key) is logged by merchant id and treated as none. Rotating the callback address (RM14) keeps it.

**Checked** in `PayHeroWebhook`'s keyed path (`/v1/hooks/payhero/{key}`), before the gateway's duplicate record: integrations asks `CollectionNotifications.callbackSigningSecret(key)`, and `CallbackSignatures` looks for a recognised header (`x-finoq-signature`, `x-payhero-signature`, `x-webhook-signature`, `x-callback-signature`, `x-signature`, `x-hub-signature-256`, `signature`, or a token-style `x-finoq-webhook-secret`, `x-payhero-webhook-secret`, `x-webhook-secret`). A value matches when it is the HMAC-SHA256 of the raw body under the secret, as hex (any case), base64 or base64url, with an optional `sha256=` prefix, or the secret itself; every comparison is constant-time.

| Secret | Recognised header | Outcome |
| --- | --- | --- |
| none (or unreadable) | anything | As before: accepted |
| set | none | Accepted; INFO `payhero callback signature: outcome=missing` (until the scheme is confirmed, so a missing header can't drop a real payout's doorbell) |
| set | matches | Accepted; INFO `outcome=verified header=<name> form=<hmac-sha256-hex\|…\|shared-token>` |
| set | doesn't match | Refused `401 auth.token_invalid`, nothing rung, not recorded as seen; WARN `security: payhero callback refused: outcome=bad_signature header=<name>` |

Every set of header names is logged once, names only: `payhero callback headers first seen: names=[…] signing=[…]`. Values, the secret and the body are never logged. A refused callback never blocks an outcome: `ramp.reconcile`, `ramp.payouts` and the portal's own reads still ask FinoQ about every open payment and payout.

**What the first real callback with a secret set will confirm:** the `headers first seen` line names the header FinoQ signs with (or shows it sends none), and the `signature:` line says whether it verified and in which form. Then: cut `CallbackSignatures.HEADERS` to that header; if it verified, refuse a callback with the secret set and no signature; if FinoQ signs something other than the raw body (a timestamp and the body, say) the line reads `bad_signature`: ask FinoQ for the scheme, and add the timestamp window the gateway already supports (`sentAt`). If no header is ever recognised, the names line shows what to add.

### Settings (V7, RO5, all audited by name)

| Setting | Default | Range |
| --- | --- | --- |
| `offRampState` | `OFF` | `OFF` \| `PREVIEW` \| `ON`: selling's own switch |
| `offRampMinMinor`, `offRampMaxMinor`, `offRampDailyLimitMinor` | 1 000, 500 000, 2 000 000 (KES 10, 5 000, 20 000) | on what the user receives |
| `transferWindowSeconds` | 900 | 60 to 3600 |
| `payoutWindowSeconds` | 1800 | 60 to 86400 |
| `payoutFeeBands` | `[]` | `[{ upToMinor, feeMinor }]`, the fallback table |
| `payoutBalanceLowMinor` | 100 000 (KES 1 000) | ≥ 0; 0: never |

Engine settings for `OFF_RAMP` were already seeded (V6).

### Data (V7)

`ramp.settings` (the six settings), `ramp.quotes` (`provider_fee_minor`, `provider_fee_source`), `ramp.orders` (`provider_fee_minor`, `provider_fee_source`, `provider_fee_actual_minor`, `wallet_transfer_id` unique, `funded_minor`, `funding_tx_hash`, `funded_at`, `refund_address`, `payout_attempts`, `payout_failures`, `payout_reference`, `payout_provider_reference`, `payout_secondary_reference`, `payout_outcome`, `payout_sent_at`, `payout_retry_at`, `recheck_requested_at`; `orders_funding_idx`, `orders_recheck_idx`; `orders_user_open_key` with `REFUNDING`), `ramp.merchants` (`payout_account_id` digits, `payout_issue`, `payout_issue_at`, the last balances), `ramp.payout_holds` (one per sale, closed once), `ramp.test_payouts` (one in flight per merchant). `payer_phone` is a sale's payee; `destination_address` the merchant's float; `mpesa_receipt` the payout's receipt; `custody_tx_id`/`tx_hash` the refund's.

### Jobs

`ramp.payouts` (app, every 10 s): per network, unconfirmed fundings, due payouts, `PAYING_OUT` lookups (one still `SENDING` only after 2 minutes), unfunded sales past their deadline; then staff's re-checks and the test payouts in flight. `ramp.release` (ops) also advances refunds. `ramp.expire-orders` is unchanged (it never touches a sale).

### Insights and frames

Statements and the staff figures tell the directions apart: `collected`/`sold` are buying's, `paidOut` (KES) and `bought` (USDC) selling's; `fees`, the shares and the sweeps cover both. Order counts gain `refunded`. RO7 shows the merchant's last KES balances (`kesBalances`) and their payout account and issue. `ramp/order.updated` for a sale has `pay` in USDC and `receive` in KES; pushes: `COMPLETED` "Your M-Pesa payment has been sent", `REFUNDING` "We couldn't pay you out. Your USDC is on its way back.", `REFUNDED` "Your USDC is back in your wallet", `EXPIRED` "Your sale timed out before your USDC arrived".

### Logs

Every payout step is one INFO line under `com.circaso.ramp` (`LOG_LEVEL_RAMP`) through the `PayoutJournal` port: `ramp payout sent: id=<orderId>#<n> outcome=<Accepted|Unknown|…>`, `ramp payout looked up: id=… outcome=<PAID|PENDING|FAILED|NOT_FOUND_BY_OURS|…>`, `ramp sale funded`, `ramp balances read: id=<merchant> outcome=payhero_service=<source|unread> finoq_service=<…> finoq_payment=<…>` (2026-11-24), `ramp test payout sent|looked up`. Ids and outcomes only, never an amount, a number or a credential. The integration's own lines (layouts, hosts, outcomes) are unchanged.

### The first real test payout (KES 10)

1. On the portal (or with curl against the API), the merchant: RM5b `{ "accountId": "<FinoQ account id>" }`, RM6 online with the PIN, then `POST /v1/ramp/merchant/test-payout` `{ "amountMinor": "1000" }` with `X-Merchant-Session` and an `Idempotency-Key`. The KES 10 goes to the merchant's own contact phone (RM1).
2. It exercises, for real: `balances` (both hosts, which source answered), the withdraw on `api.finoq.africa` then `backend.payhero.co.ke` only if the first provably took nothing, the answer read as one `PayoutResult`, the callback on the merchant's own address (its layout, whether it is flat with `transaction_type`), and `lookUpPayout` by the provider's reference (and, after an `Unknown`, by ours).
3. `GET /v1/ramp/merchant/test-payout` until `PAID`, `FAILED`, `NOT_SENT` or `NOT_FOUND`; once `PAID`, `charged` is the real fee (compare with `providerFee`).
4. Read the runbook of "As built: payouts through FinoQ" for the log lines, and write down what it confirmed in "Confirmed and unconfirmed".

### Not built

The semi-manual payout (RM12, RM13, R8, R9); a second transfer for a sale that the wallet's key can't stop (one sent outside W4 to the float address is left in the float, alerted as `second_transfer` only when it names an order); a refund that failed on chain sent again by the system (staff send it by hand); handing a payout to another node (a merchant unlocked on another node is `NO_KEYS`, as before); reading a payout's actual fee from the provider (none returns it).

## As built: online until stopped (2026-11-23, `feat/online-until-stopped`)

The owner's request: "Include one with no duration, so you don't have to set a duration manually: you can go live and only turn it off yourself." RM6 now opens either a timed window (unchanged) or a session that lasts until the merchant ends it. **What still ends an open-ended session:** the merchant (RM6 `DELETE`), signing out (MA3), a suspension, the provider refusing a prompt for the service balance, and **a restart of the app server**: the unlocked keys live only in its memory (unchanged, and never stored), so a deploy ends every session, open-ended ones included. The portal is told plainly (RM2 below) and the merchant is emailed.

**RM6.**

```json
// POST /v1/ramp/merchant/session
{ "pin": "482913", "minutes": 240 }          // timed, as before: 15 to 720
{ "pin": "482913", "untilStopped": true }     // open-ended: no `minutes`
// → data (the same object as RM2's `online`)
{ "online": true, "until": null, "state": "ONLINE", "openEnded": true, "keysHeld": true,
  "since": "2026-11-23T09:00:00Z", "offlineReason": null, "offlineAt": null }
// DELETE → { "online": false, "until": null, "state": "OFFLINE", "openEnded": false, "keysHeld": false,
//            "since": null, "offlineReason": "STOPPED", "offlineAt": "2026-11-23T17:30:00Z" }
```

`ramp.malformed` on field `minutes` when neither is given, when `minutes` is outside 15–720, or when both are. Every other check is as before, in the same order: approval, KYC where the network requires it (`kyc`), the float wallet, `float`, a payment account at the network's current provider, the PIN, and the provider's service-balance floor (`provider_balance`).

**RM2 `online`.** `online` and `until` keep their meaning for older portals (`until` is null for an open-ended session). Added: `state` (`ONLINE` | `OFFLINE`, the same fact), `openEnded`, `keysHeld` (this server holds the unlocked account), `since` (when the session began), and while offline `offlineReason` with `offlineAt`: `STOPPED`, `SIGNED_OUT`, `SUSPENDED`, `PROVIDER_BALANCE`, `WINDOW_ENDED`, `RESTART` (null when unknown, e.g. a session that ended before 2026-11-23). A session the database still holds but whose keys the server lost reads at once as `OFFLINE`, `keysHeld: false`, `offlineReason: "RESTART"` (`offlineAt` null until the sweep below records it). The portal says "Online until you go offline" for `openEnded`, and "You were taken offline when Circaso restarted: go online again" for `RESTART`.

**Data (V9).** `merchants.online_open_ended` (boolean, with `CHECK (NOT (online_open_ended AND online_until IS NOT NULL))`), `online_since`, `offline_reason`, `offline_at`; partial index `merchants_sessions_idx` on `merchant_id WHERE online_open_ended OR online_until IS NOT NULL`. An open-ended session is `online_until` NULL with the flag set, never a far-future date. Every check that read `online_until` reads both through `Merchant.isOnlineAt` / `isTradingUntil`: the engine's eligibility (`online`: the session must outlast the order's payment or transfer window, which an open-ended one always does), the re-check under the reservation's row lock, RM2, the staff overview and summary (RO6, RO7: `online` is the flag or a window in the future). Staff reads also carry `onlineOpenEnded` and `offlineReason` (`OpsMerchant`, `OpsRampMerchantProfile`, and the ops DTOs).

**`ramp.end-sessions`, every minute (the app process).** (1) Ends timed windows that ran out (`WINDOW_ENDED`). (2) For every merchant with a session in the database whose keys this node doesn't hold, ends it with `RESTART`, guarded by `online_since` so a session opened again since the row was read is never ended; the merchant is emailed `MERCHANT_TAKEN_OFFLINE` once (idempotency key `merchant-offline-<merchantId>-<since>`; best effort, nothing sent while the template has no id). This applies to timed sessions too: after a restart they could take no order either, and staff's `online` no longer shows them as online. (3) Renews `rmp:session:{merchantId}` for open-ended sessions it holds (the mark lasts 10 minutes, so a dead node's marks lapse). (4) Drops keys no open order needs, as before. Orders that were waiting on the provider when the keys went still wait, never settled on a guess (unchanged).

**One node, still.** "Keys not on this node" counts as lost because the trading session is single-node (`rmp:work` is not built). With a second app node this sweep would need the holder's mark instead: `TODO(decision: more than one app node)` in `EndSessions`.

**The email.** `EmailTemplate.MERCHANT_TAKEN_OFFLINE` (`Email.MerchantTakenOffline`, no variables), `email-templates/merchant-taken-offline.html`. Until staff set its template id in the admin, nothing is sent and the session still ends.

## As built: merchant top-ups (2026-11-23, `feat/merchant-topups`)

The owner's request: "On the merchant side, add an option to top up their service account, and on the balance also an option to top up their FinoQ payment wallet, so they just enter an amount, get an STK push, and top up immediately." RM19.

**FinoQ calls (`MobileCollections.requestTopUp` / `lookUpTopUp`, `PayHeroTopUps`).**

- Service wallet: `POST https://backend.payhero.co.ke/api/v2/topup` `{ "amount": 100, "phone_number": "0712345678" }` (docs.payhero.co.ke, "Service Wallet Top Up": "The phone number you provide will get an MPESA prompt"). 200: `{ success, status: "QUEUED", reference, CheckoutRequestID }`. No reference of ours, no callback address. Legacy host only.
- Payment wallet: a wallet-based collection, `POST /api/v2/payments` `{ amount, phone_number, provider: "sasapay", network_code: "63902", account_id, external_reference: <topupId>, callback_url }` (docs.payhero.africa, Kenya collections, "Wallet-based collections"; `account_id` is required there), on `api.finoq.africa`, then `backend.payhero.co.ke` only when the first provably took nothing (as the payout). No dedicated wallet top-up endpoint is documented on the new host.
- Status: the payout's two lookups (`POST /api/global/transaction-status`, then `GET /api/v2/transaction-status?reference=`), by the provider's reference then ours; an answer echoing another `external_reference` is not used for the payment wallet (the service top-up sends none, so it isn't checked).
- Typed results: `TopUpResult` `Accepted(receipt)` / `Rejected(reason)` / `Unknown` / `Unavailable`; `TopUpRejection` `INVALID_REQUEST`, `CREDENTIALS_REFUSED`, `NOT_SUPPORTED`, `SERVICE_BALANCE_LOW` ("insufficient … balance"), `PAYER_NOT_ALLOWED` (words about the own/first-party number or KYC level: FinoQ's KYC 1–2 accounts collect only from the number the account was opened with), `REFUSED`. Sent once on the no-retry clients; a timeout, a 5xx, a 408 and a 2xx without a reference (or `success: false`) are `Unknown`; a 429 is `Unavailable`. Layouts logged once (`what=topup_service|topup_payment|topup_status…`), never bodies or numbers.

**The contract.**

```json
// POST /v1/ramp/merchant/topups   (Idempotency-Key; X-Merchant-Session)
{ "wallet": "SERVICE", "amountMinor": "50000" }                    // online
{ "wallet": "PAYMENT", "amountMinor": "100000", "pin": "482913" }  // offline: the PIN unlocks the account for this top-up only
// → data (also GET /topups/{topupId})
{ "topupId": "0193…", "wallet": "SERVICE", "state": "REQUESTED", "failure": null,
  "amount": { "unit": "KES", "amountMinor": "50000", "amount": "500" }, "maskedPhone": "+254 7•• ••• 678",
  "confirmedBy": null, "receipt": null,
  "balanceBefore": { "unit": "KES", "amountMinor": "5000", "amount": "50" }, "balanceAfter": null, "added": null,
  "outcome": "accepted", "createdAt": "…", "updatedAt": "…", "closedAt": null }
// GET /v1/ramp/merchant/topups?limit=20 → { "topups": [ … ], "limits": { "min": { KES 10 }, "max": { KES 150000 } } }
```

`state`: `REQUESTED` (open) → `CONFIRMED` (`confirmedBy` `STATUS` | `BALANCE`, `balanceAfter`, `added`) | `FAILED` (`failure`: `CANCELLED`, `FAILED` after the prompt; `SERVICE_BALANCE_LOW`, `PAYER_NOT_ALLOWED`, `CREDENTIALS_REFUSED`, `PROVIDER_UNAVAILABLE`, `REFUSED` when nothing was sent) | `UNKNOWN` (nothing settled it within 5 minutes; a late provider "paid" still confirms it). Errors: `ramp.amount_invalid` (field `amountMinor`), `ramp.merchant_not_approved`, `ramp.merchant_setup_incomplete` (field `accountId` for a payment top-up without the FinoQ account id; or no contact phone/payment account), `ramp.merchant_offline` (field `pin`: offline without a PIN), `ramp.pin_invalid`, `ramp.pin_locked`, `ramp.vault_unavailable`, `ramp.topup_in_progress`, `ramp.collections_unavailable`, `ramp.topup_not_found`, `request.malformed` (`wallet`, `amountMinor`, `pin`, `topupId`).

**Rules (`MerchantTopUps`).** Approved merchant; KES 10–150,000 in whole shillings (constants `MIN_SHILLINGS`/`MAX_SHILLINGS`); the prompt goes **only** to the merchant's own contact phone (the request has no phone field); one `REQUESTED` per merchant and wallet (`topups_open_key`); the row is written before the call, the call is made once.

**Online or PIN (the choice).** Online on this node, the held keys are used. Offline, the request carries the PIN: `PinGuard` opens the envelope (wrong tries counted as everywhere) and `TradingSessions.hold` keeps the keys in this node's memory **without** a session mark, so the merchant takes no orders. Requiring "go online first" would lock out exactly the merchant who needs it: RM6 refuses to go online while the service wallet is under the floor. `ramp.end-sessions` keeps held keys while a top-up is open (`TopUpRepository.hasOpen`) and drops them after.

**Confirmation.** A callback (`/v1/hooks/payhero/{key}`) is a doorbell: `HandlePaymentCallback` treats a reference that is no order as a possible top-up of the key's merchant (`MerchantTopUps.rung`). `lookUp` asks the provider's status: `Paid` → `CONFIRMED` (`STATUS`). Otherwise that wallet's balance is read (`ProviderBalancesReader`, which also records it for RM2): a rise of at least 90 % of the amount since `balanceBefore` → `CONFIRMED` (`BALANCE`; FinoQ may take a fee from a wallet collection). Else a provider `Failed` → `FAILED` (`CANCELLED` for the word CANCELLED). Else it waits; past 5 minutes → `UNKNOWN`. The portal's `GET /topups/{id}` asks at most every 3 s; `ramp.payouts` (every 10 s) sweeps open ones untouched for 10 s. Balances are refreshed on confirmation.

**Data (V10).** `ramp.topups` (`topup_id`, `merchant_id`, `wallet`, `provider`, `amount_minor` whole KES, `payer_phone`, `state`, `failure`, `outcome`, `provider_reference`, `checkout_id`, `receipt`, `confirmed_by`, `balance_before_minor`, `balance_after_minor`, `created_at`, `updated_at`, `closed_at`), `topups_open_key` (unique `merchant_id, wallet` where `REQUESTED`), `topups_merchant_idx`, `topups_open_idx`.

**`log` and `fake`.** `LogCollections` accepts a top-up, reports it paid after 3 s, credits the fake wallet and rings the payment doorbell; `FakeCollections` takes `nextTopUp`, `completeTopUp`, `failTopUp`.

**Unconfirmed (each is what the code assumes).** That `/api/v2/topup` still works for FinoQ accounts and with the same API keys; that the wallet collection is allowed for the merchant's own number at their KYC tier and that `sasapay`/`63902` is right for M-Pesa; that either status lookup finds a service top-up (it sends no reference of ours) and with which words; whether a callback comes for either (and with which `transaction_type`); whether FinoQ takes a fee from the collected amount (hence the 90 % rule); FinoQ's wording for a non-owner number (`PAYER_NOT_ALLOWED` is matched widely, else `REFUSED`).

## As built: three provider wallets (2026-11-24, `feat/three-wallets`)

The owner: "There's the FinoQ service wallet and there's the PayHero service wallet: the PayHero service wallet is for on-ramp, the FinoQ service wallet is for off-ramp. You should be able to top up any of them." Until now one "fee balance" held whichever service wallet answered first (PayHero's, else FinoQ's), so two wallets shared a name and a column, and RM19's `SERVICE` topped up only PayHero's.

**The three wallets.**

| RM19 `wallet` | Contract (`TopUpWallet`, `ProviderBalances`) | What it is for | Read from | Topped up with |
| --- | --- | --- | --- | --- |
| `PAYHERO_SERVICE` | `COLLECTION_FEES`, `collectionFees` | PayHero's fee for each M-Pesa prompt (buying) | `GET backend.payhero.co.ke/api/v2/wallets?wallet_type=service_wallet` → `available_balance` (source `payhero.wallets.service_wallet`) | `POST backend.payhero.co.ke/api/v2/topup` `{amount, phone_number}` (documented; worked on dev 2026-11-23) |
| `FINOQ_SERVICE` | `PAYOUT_FEES`, `payoutFees` | FinoQ's fee for each payout (selling) | `GET api.finoq.africa/api/global/accounts/{id}/balance` → `service_wallet_balance.balance` (`finoq.balance.service_wallet`), else the Team on `auth.payhero.africa/api/v2/account/{id}` → `account.service_wallet_balance` (`finoq.account.service_wallet_balance`) | `POST api.finoq.africa/api/v2/topup` `{amount, phone_number, account_id}`: **undocumented, a try** (below) |
| `FINOQ_PAYMENT` | `PAYOUT`, `payout` | What payouts spend | the same balance call → `wallet_balance.balance` (`finoq.account.wallet_balance`; present without `balance` = 0) | wallet collection `POST /api/v2/payments` with `network_code` on `api.finoq.africa` (unchanged) |

Each wallet is read only from its own calls: a PayHero reading never fills FinoQ's service wallet or the other way round, and one not read is null ("unknown"), never zero. The guessed legacy `wallet_type=payment_wallet` query is no longer asked (it was never documented and answered `404` on every dev-server call). FinoQ's two wallets need the account id; PayHero's doesn't. Log line: `payhero balances: payhero_service=<source|unread> finoq_service=<…> finoq_payment=<…>`.

**Which check reads which.** RM6's provider-balance floor (`provider_fee_floor_minor`) reads PayHero's service wallet (`MobileCollections.balance`, unchanged), and its reading is now kept for RM2 and staff; a prompt refused for the balance still marks `paymentsIssue: SERVICE_BALANCE` (PayHero's). The off-ramp's flags read FinoQ's wallets only: `payout_balance_low` the payment wallet against `payout_balance_low_minor`, `service_balance_low` FinoQ's service wallet against `provider_fee_floor_minor` (one floor for both service wallets: `TODO(decision: a floor of its own for FinoQ's service wallet)` in `ProviderWallets`). `provider_balance` left the `offRamp` flags (it is buying's; still in `missing` and `paymentsIssue`). The engine's KES float for a sale and the test payout read the payment wallet, as before.

**Kept per wallet (V11).** `ramp.merchants` gains `payhero_service_balance_minor|_source`, `payhero_service_read_at`, `finoq_service_balance_minor|_source`, `finoq_service_read_at`, `payout_balance_read_at`; the old `fee_balance_*` was copied to the wallet its source names and is no longer read or written (a later script drops it). A reading that couldn't read one wallet keeps that wallet's last reading with its own time; `balances_read_at` is the last reading of any. `ramp.topups.wallet` is now `PAYHERO_SERVICE | FINOQ_SERVICE | FINOQ_PAYMENT` (rows moved: `SERVICE` → `PAYHERO_SERVICE`, `PAYMENT` → `FINOQ_PAYMENT`).

**RM2.** `providerWallets[]`, buying's first:

```json
"providerWallets": [
  { "wallet": "PAYHERO_SERVICE", "name": "PayHero service wallet", "provider": "PAYHERO", "direction": "ON_RAMP",
    "purpose": "PayHero's fee for each M-Pesa prompt when people buy comes from here",
    "balance": { "unit": "KES", "amountMinor": "31240", "amount": "312.4" }, "source": "payhero.wallets.service_wallet",
    "readAt": "2026-11-24T09:00:00Z", "low": false, "lowBelow": { "unit": "KES", "amountMinor": "5000", "amount": "50" },
    "canTopUp": true },
  { "wallet": "FINOQ_SERVICE", "name": "FinoQ service wallet", "provider": "FINOQ", "direction": "OFF_RAMP",
    "purpose": "FinoQ's fee for each M-Pesa payout when people sell comes from here",
    "balance": null, "source": null, "readAt": null, "low": false, "lowBelow": { … }, "canTopUp": true },
  { "wallet": "FINOQ_PAYMENT", "name": "FinoQ payment wallet", "provider": "FINOQ", "direction": "OFF_RAMP",
    "purpose": "Sellers' M-Pesa payouts are paid from here", "balance": { … }, "source": "finoq.account.wallet_balance",
    "readAt": "…", "low": true, "lowBelow": { "unit": "KES", "amountMinor": "100000", "amount": "1000" }, "canTopUp": true }
]
```

`low`: under `lowBelow` (null when the line is 0), or PayHero's after a prompt refused for it, or the payment wallet after a payout refused for it. `canTopUp` is false only for a FinoQ wallet without the account id. `offRamp.payoutBalance` stays the payment wallet; `offRamp.feeBalance` is now **FinoQ's** service wallet.

**RM19.** `wallet`: `PAYHERO_SERVICE | FINOQ_SERVICE | FINOQ_PAYMENT`; the old `SERVICE` is taken as `PAYHERO_SERVICE` and `PAYMENT` as `FINOQ_PAYMENT`, and answers carry the new names. Both FinoQ wallets need the account id (`ramp.merchant_setup_incomplete`, field `accountId`). One open top-up per wallet. Confirmation is unchanged (the provider's "paid", or **that** wallet's balance rising by 90 % of the amount).

```json
// POST /v1/ramp/merchant/topups
{ "wallet": "FINOQ_SERVICE", "amountMinor": "20000" }
// → { "topupId": "…", "wallet": "FINOQ_SERVICE", "state": "REQUESTED", "balanceBefore": { KES 7 }, … }
```

**FinoQ's service-wallet top-up: not documented.** docs.payhero.africa (all 69 pages of its sitemap, read 2026-11-24) has no top-up for a service wallet or an account: its only "top-ups" are wallet collections and PesaLink deposits into the wallet collections settle into. So the client tries the legacy call's twin on FinoQ's host, `POST api.finoq.africa/api/v2/topup` `{ "amount": 200, "phone_number": "0712345678", "account_id": 63 }`, **on that host only** (PayHero's host would fill PayHero's wallet), once, on the no-retry client. A bare `404`/`405` is "no such route" → `FAILED`/`REFUSED` with outcome `rejected:NOT_SUPPORTED` and nothing sent; `401`/`403` → `CREDENTIALS_REFUSED`; a timeout, 5xx or 2xx without a reference → `Unknown` (looked up, never resent). The first real attempt logs `payhero layout first seen: what=topup_finoq_service status=<http> shape={…}` and `payhero topup: wallet=PAYOUT_FEES host=finoq status=… outcome=…`: a `404` means FinoQ has no such route (ask FinoQ how its service wallet is funded); a `2xx` with `reference` confirms it; then watch `finoq_service` rise in `ramp balances read`.

**Ops.** RO7's `kesBalances` gains `wallets[]` (`wallet`, `name`, `provider`, `direction`, `balance`, `source`, `readAt`, `low`), and its `fees`/`feesSource` are FinoQ's service wallet; RO6's overview rows gain `providerWallets[]` in the same shape.

**Confirmed and not.** *Confirmed:* on the dev server (logs, 2026-11-23/24) PayHero's legacy wallet answer is its own object (`{account_id, wallet_type, available_balance, …}`) and FinoQ's account balance is `{service_wallet_balance:{account_id,balance,currency}, wallet_balance:{…}}`; a `SERVICE` top-up on the legacy host was accepted (`201`) and reported paid by the legacy status lookup, while FinoQ's status lookup didn't know it (`404`); PayHero's STK callbacks carry `ServiceWalletBalance` and `PaymentWalletBalance` of their own. FinoQ's docs say each Team "gets its own service wallet (created automatically)" and that balances "never mix across Teams". *Not confirmed (trusted from the owner):* that PayHero's legacy service wallet and FinoQ's `service_wallet_balance` are different wallets with different balances (no doc says either way; the logs carry no values), that the prompt fee comes from PayHero's and the payout fee from FinoQ's (no doc names which wallet pays which fee), and FinoQ's service-wallet top-up itself. Check: after a real payout, `finoq_service` should fall by the fee and `payhero_service` stay put.

## As built: rates (2026-11-24, `feat/ramp-rates`)

Approved by Pasaka the same day. Until now both directions were priced at one rate, the day's reference (shillings per US dollar from open.er-api.com through `ExchangeRates`, refreshed about daily) with no spread, the fee being the only margin. That is open to arbitrage: the rate goes stale intraday, and Kenya's P2P USDC market sits above the official rate. Now each network has **two rates, set by staff in the admin**.

**The rule (`RatePolicy`, pure).** Buying (`ON_RAMP`, the user pays KES for USDC) is quoted at the **buy rate**; selling (`OFF_RAMP`) at the **sell rate**. Per network, `mode`:

- `REFERENCE_SPREAD` (the default): buy = reference × (1 + `buySpreadBps` / 10 000), sell = reference × (1 − `sellSpreadBps` / 10 000). A rate with more than six decimals rounds the merchant's way (buy up, sell down). **Spreads start at 0**, so the rates equal the reference and nothing changed until staff set them. A reference older than `maxReferenceAgeHours` (default 36; `UsdRate.asOf` is the source's publication time, `time_last_update_unix`), or none at all, **pauses** quoting both sides.
- `MANUAL`: staff's exact `manualBuyRate` and `manualSellRate` (KES per USDC) until `manualExpiresAt` (24 hours from the save when none is given; at most 30 days). Once it passes, quoting pauses until staff set new rates or go back to the reference. A manual rate needs no reference to quote; the reference is needed only for the band check when it is saved.

A paused side is `ramp.pricing_unavailable` (502, unchanged for the app), **never a silent fallback** to another rate; the reason (`NO_REFERENCE`, `REFERENCE_STALE`, `MANUAL_MISSING`, `MANUAL_EXPIRED`) is logged (`ramp rate paused`) and shown on the admin's rates read. One expiry covers both manual rates, so the pair is always checked together.

**Checked when saved** (`ramp.settings_invalid`, 400, naming the field): spreads 0 to 2000 bps; `maxDeviationBps` 1 to 5000 (default 500); `maxReferenceAgeHours` 1 to 168; in `MANUAL`, both rates above 0 with at most six decimals, **buy ≥ sell**, the expiry in the future, and **both rates within `maxDeviationBps` of the reference read now** (the sanity band; with no reference to check against, manual rates can't be saved). The rest of the house rule holds: version-checked (`ramp.settings_changed`), audited by name on the ops gateway, read through `PricingBook` at most every 10 seconds per process (the last read stays in force on a database error). A quote already made keeps its rate.

**Rounding still favours the merchant** everywhere: the spread rate as above; buying keeps its fee rounded up to a shilling and the USDC down (`RatePricing`, which replaces `ReferenceRatePricing`); selling keeps `SalePricing` as it was, at the sell rate.

**Transparency.** Every quote and order records how its rate was made: `rate_source` (`REFERENCE_SPREAD` | `MANUAL`), `reference_rate` (the reference read at the time; null when none could be read, which a manual rate allows) and `spread_bps` (the spread set; for a manual rate the margin it works out to, signed, null without a reference). The order copies them from its quote. Orders from before V12 have them null. Staff see them on RO4 (the review list, which also gains `rate`) and RO8 (a merchant's orders). **The app's quote response is unchanged**: it already carries `rate`, and the reference and margin are not shown to users.

**Data (V12).** `ramp.pricing_settings` (one row per network: `mode`, `buy_spread_bps`, `sell_spread_bps`, `manual_buy_rate`, `manual_sell_rate` (decimal strings), `manual_expires_at`, `max_deviation_bps`, `max_reference_age_hours`, `version`, `updated_by`, `reason`, `updated_at`; `CHECK`s on the ranges and on a manual row having its rates and expiry), seeded from the defaults by `PricingBook` the first time a network has no row. `ramp.quotes` and `ramp.orders` gain `rate_source`, `reference_rate`, `spread_bps`.

**Contract (`ramp:api` `RampRatesAdmin`)**, for the ops module: `pricing(network)`, `updatePricing(settings, version, reason, by)`, `rates(network)`.

**Ops routes** (`rampRatesController`, inside `networkFiltered { }`; `?network=` required):

| # | Route | Does |
| --- | --- | --- |
| RO15 | `GET /ops/ramp/pricing?network=` (`ramp.read`) | The pricing settings with their version |
| RO16 | `PUT /ops/ramp/pricing?network=` (`ramp.manage`, ADMIN) | Replaces them: `{ mode, buySpreadBps, sellSpreadBps, manualBuyRate?, manualSellRate?, manualExpiresAt?, maxDeviationBps, maxReferenceAgeHours, version, reason }`; audited `ramp_pricing.update` with what changed, by name |
| RO17 | `GET /ops/ramp/rates?network=` (`ramp.read`) | The reference and its age (`referenceRate`, `referenceAsOf`, `referenceAgeSeconds`, `referenceStale`), and `buy` and `sell`: `{ rate, source, spreadBps, paused }` |

**The admin's "Rates" page** (under Ramp, per network): the reference and its age with an alert when stale, the effective buy and sell rates (or why each is paused), a mode switch; in spread mode the two spreads with a live preview of the rates and the margin over the reference; in manual mode the two rates and the expiry, with the band shown; a reason on save.

## To check against the vendor before building on it

- **PayHero, payouts:** superseded 2026-11-23: FinoQ's docs describe `POST /api/v2/withdraw`, and the integrations half is built on them (see "As built: payouts through FinoQ"). Still to get in writing: that withdraws are enabled for a merchant's account, and every "Not confirmed" item there.
- **PayHero, which API:** docs.payhero.africa is branded "FinoQ", names another base (`api.finoq.africa`) and other payout fields (`account_id`, `recipient_type`), and never says it is PayHero's API. The on-ramp follows docs.payhero.co.ke and `backend.payhero.co.ke`; payouts try the new host first and the old one when the new one provably took nothing. PayHero must say which one a new integration should use.
- **PayHero, the on-ramp:** whether callbacks are retried; whether the status query's amount and phone are always present on `SUCCESS` (the match depends on them); the format `phone_number` wants; what an empty service wallet does to a prompt.
- **PayHero, the business:** its terms don't name crypto or P2P trading as prohibited, but keep "sole discretion" over restricted categories, and its newer docs sell a crypto on-ramp as a product that must be enabled per account. Merchants should have PayHero's agreement in writing before real volume.
- **Circle:** settled by main: one account holds both wallet types (the fee wallets), the webhook marks them `custodyType: DEVELOPER`, and `EntitySecret` already encrypts for Circle. Still to check: that Gas Station sponsors a developer-controlled `SCA` wallet's transfers on the testnet policy and what mainnet's policy must allow.
- **Didit:** settled from the docs (2026-11-22): the decision's `id_verifications[]` carry `issuing_state`, `document_type` and `document_number`. Still to confirm against a real live session: see "Assumptions about Didit" under "As built: identity verification", and "Could not be confirmed" under "As built: submitted" (the flow-finished signal).

## Open questions

These also go into `decisions.md`; each is a `TODO(decision: …)` in code, behind a port or a setting.

- **The exchange rate.** Settled 2026-11-24 (see "As built: rates"): staff set each network's buy and sell rates as a spread on each side of the reference, or as exact manual rates with an expiry. Still open: whether merchants may set their own (an order book is "Not in v1"), and a live intraday reference (a P2P or exchange feed) in place of the daily one.
- **Reversal after release.** Who carries the loss when a user reverses M-Pesa after USDC was released (the merchant, Circaso, a shared reserve), how a reversal is proven (PayHero reports none), and what a user paid-after-expiry is owed when the float is gone.
- **Key vault or envelope encryption.** The envelope above keeps merchant keys out of the database in plaintext, but the master key and the Circle entity secret are environment secrets. Which key store before real money moves (the open "Signing key storage" question, now sharper: the entity secret can move every merchant's float).
- **Payout provider for the off-ramp.** Settled 2026-11-23: FinoQ (PayHero) payouts from each merchant's own account (see "As built: the off-ramp"). Earlier: PayHero has no payouts today (unverified until it replies in writing). Which provider pays users out: the merchant's own Daraja B2C, another aggregator, or PayHero later; whether merchants hold KES float there; and whether the semi-manual payout (Proposed above) opens selling in the meantime.
- **Merchant float caps.** The most float a merchant may hold, the largest single order against one merchant, and whether a new merchant starts lower.
- **VASP licensing.** Whether running the desk, holding the entity secret for merchants' wallets and matching trades needs a licence under Kenya's Virtual Asset Service Providers Act, 2025, and what the merchants themselves need. A legal question to settle before mainnet.
- **KYC tiers and limits.** Which checks a user needs (the basic check, or AML too), what each tier may trade per order and per day, and whether a merchant that is a company needs KYB as well.
- **Ramp KYC and identity.** Ramp owns the ramp's KYC (Proposed). Whether an approved ramp KYC should also set `identity.accounts.verification`, which support recovery reads.
- **Merchant accounts and portal sign-in.** A merchant account is its own, not a user account (decided 2026-11-22). How it signs in is not designed: identity's sessions need a registered phone, and the portal is a browser. Proposed: merchant sign-in by email with a one-time code and a `merchant` scope limited to `/v1/ramp/merchant/`; it needs a short design of its own before the portal is built.
- **Paying from another number.** The first version takes payment only from the account's own verified number. Whether a user may pay from, or cash out to, someone else's M-Pesa.
- **Deleting an account** of a user with an open order: identity's `DeletionGuard` has one answerer today (wallet).
- **Record retention** for orders after an account is deleted (with the wallet's same question).

## Not in v1

Splitting an order across merchants; currencies other than KES and providers other than PayHero (the contract allows both); assets other than USDC; a merchant mobile app; merchant-set prices and an order book; disputes inside the app (support handles them).
