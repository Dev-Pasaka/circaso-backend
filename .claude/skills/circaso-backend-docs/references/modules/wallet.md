# Wallet module

Wallet answers "what do I hold, and how do I send it to someone": it gives each user a Circle user-controlled wallet on request, prepares transfers that only the user can sign, and keeps the record of every transaction for statements and audit. It never holds keys and never moves money on its own: the backend prepares, the user signs with their Circle PIN, the chain executes. Keyspace `circaso_wallet`, Redis prefix `wal:`, stream `events:wallet`.

Status: draft, 2026-10-25. Proposals marked **Proposed** are listed again under Open questions; Circle details are from Circle's user-controlled wallets docs and API reference as read on 2026-10-25.

## Overview

### Owns

- **Circle users and wallets**: the link between a Circaso account and its Circle user (Circle never sees our user id) and the user's wallet, created when the user taps "Create wallet" in the wallet section (decision 2026-10-25).
- **Transfers**: a payment from one wallet, to a Circaso contact or to an outside address, from preparing it to its final state on chain.
- **Transaction records**: every incoming and outgoing transaction per user, for the history screen, monthly statements and audit. Circle stays the source of truth for balances (Architecture principle 11).
- **Payment rules** checked before a transfer is prepared: reach, the hold after a support restore, limits, account status.

### How a Circle user-controlled wallet works

Circle splits each wallet key between Circle and the user (2-of-2 MPC). Every action that signs needs the user's own authorisation in the Circle SDK on the phone; the backend can only create **challenges**.

| Step | Backend (API key) | Phone (Circle SDK) |
| --- | --- | --- |
| Create the Circle user | `POST /v1/w3s/users` with our `circleUserId` | — |
| Session | `POST /v1/w3s/users/token` → `userToken` (60 min) and `encryptionKey` | Receives both from W2 or W3 |
| Create the wallet | `POST /v1/w3s/user/initialize` with `X-User-Token`, `accountType`, `blockchains` → `challengeId` | `execute(challengeId)`: the user sets a 6-digit PIN and security questions; Circle creates the wallet |
| Send | `POST /v1/w3s/user/transactions/transfer` with `walletId`, `tokenId`, `destinationAddress`, `amounts`, `idempotencyKey`, `refId` → `challengeId` | `execute(challengeId)`: the user enters the PIN (or biometrics); Circle signs and broadcasts |
| Follow it | Webhook notifications to `/v1/hooks/circle`; `GET /v1/w3s/transactions/{id}` as a fallback | — |
| Change or forget the PIN | `PUT /v1/w3s/user/pin` / `POST /v1/w3s/user/pin/restore` → `challengeId` | The user changes the PIN, or answers the security questions to set a new one |

**Authentication method: PIN with security questions** (Proposed; identity's `DeviceSignatures` already says "wallet transactions are signed with the Circle PIN"). Circle also offers social login and email OTP, but those add a second Google/Apple sign-in through Circle's SDK or an email OTP over our own SMTP, and make the wallet only as safe as that email account. With the PIN, three wrong tries lock PIN entry for 30 minutes, and Circle can't recover a lost PIN: the security questions are the only way back. The app offers biometrics in place of typing the PIN where the SDK supports it.

### Chain, account type and gas

**Proposed: USDC on Base, smart-contract accounts (`SCA`), gas paid by Circaso through Circle Gas Station.**

- **Base:** low fees, native USDC, and Circle supports user-controlled wallets and Gas Station on it. Dev and staging use `BASE-SEPOLIA` with test USDC.
- **SCA:** an ERC-4337 account, which Gas Station needs on EVM chains. Users never hold ETH or see a gas fee.
- **Gas Station:** Circaso pays the gas plus Circle's 5% fee on it, billed to the Circle account, under a sponsorship policy with per-wallet limits set in Circle's console.
- **Multi-chain ready:** the chain, account type and token are integration settings (`INTEGRATIONS_CUSTODY_*`, below), and every wallet row stores the `blockchain` the contract returned, so a second chain (Solana, or Arc if it fits) is a config value and a new row, not a new model. v1 creates one wallet per user on one chain.
- **Asset:** USDC only in v1 (`Asset.USDC`, 6 decimals, already in `shared`). `USDT` stays in the enum but isn't offered.

### Paying a person

- **Reach rule:** paying a Circaso user needs `users.ReachPolicy.canReach(sender, recipient)`, like a message. Inside a group chat, paying a member also works through `messaging.ChatDirectory.isMember` for both people.
- **The recipient needs a wallet.** The backend can't create one for them: creating it takes their PIN. If they have none, W9 says so and the app offers to remind them ("Amina hasn't set up her wallet yet"); the reminder goes as `wallet.setup.requested` with a push. Nothing is held for them in the meantime.
- **To an outside address:** any Base address, checked with Circle's `validateAddress` first. The app shows a warning that payments on chain can't be reversed.
- **Pay in a chat:** the payment card is an end-to-end encrypted chat message that carries the `transferId`; the wallet only records the `chatId` (which the server already knows). A note written with the payment travels in the encrypted message and is never stored by the wallet.
- **From Asha** (Phase B): the app calls W4 with the user token and the `proposalId`; the transfer records `origin: ASHA`. The PIN is still required.

### Contract (`wallet:api`)

```kotlin
interface Wallets {                  // read by the assistant later (Phase B proposals)
    suspend fun hasWallet(userId: UserId): Boolean
}
```

Wallet also implements identity's `DeletionGuard` (refuses while the wallet holds 0.01 USDC or more), `RealtimeReplay`, and the new `CustodyNotifications` listener from integrations (below).

### Integrations used

**All Circle setup and configuration lives in integrations, as for every other vendor; the wallet module only calls the contract.** The wallet never sees the Circle API key, the app id, the chain name, the account type, the USDC token id or a Circle user token's lifetime: `CircleCustody` reads them from its own settings and `Secrets`, and the wallet stores whatever the contract returns (wallet id, address, chain).

`StablecoinCustody` in `integrations:api`, implemented by `CircleCustody` in `integrations/impl/.../circle/` over `HttpClients.named("circle")`, base URL `https://api.circle.com`. Chosen and configured like the others:

| Setting | Example | Purpose |
| --- | --- | --- |
| `INTEGRATIONS_CUSTODY_PROVIDER` | `log` \| `fake` \| `circle` | `log` locally and on the sandbox, `fake` in tests, `circle` once real wallets are wanted |
| `CIRCLE_API_KEY` (secret) | `TEST_API_KEY:…` | Circle API key; testnet and mainnet keys are separate |
| `INTEGRATIONS_CUSTODY_APP_ID` | `…` | Circle app id, handed to the phone's SDK |
| `INTEGRATIONS_CUSTODY_BLOCKCHAIN` | `BASE-SEPOLIA` (`BASE` in production) | Chain new wallets are created on |
| `INTEGRATIONS_CUSTODY_ACCOUNT_TYPE` | `SCA` | Account type for new wallets |
| `INTEGRATIONS_CUSTODY_USDC_TOKEN_ADDRESS` | `0x036C…CF7e` (Base Sepolia) | USDC's contract address on that chain; transfers name the token by address + blockchain (Circle's token ids are resolved to it once and cached) |

Our language in and out. Circle user tokens are the vendor's business: `CircleCustody` asks Circle for one when it needs it and keeps it in memory per node until 5 minutes before it expires, so the wallet only passes our `circleUserId`. Where the phone needs to run a challenge, the result carries the SDK values with it.

```kotlin
interface StablecoinCustody {
    suspend fun createUser(circleUserId: String): Resource<Unit>                              // already-exists is success
    suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> // initialize, on the configured chain
    suspend fun sdkSession(circleUserId: String): Resource<SdkSession>                        // appId, userToken, encryptionKey, expiresAt
    suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>>                  // id, address, chain, state
    suspend fun balance(circleUserId: String, walletId: String, asset: Asset): Resource<Money>
    suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> // idempotencyKey, refId
    suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction>
    suspend fun validateAddress(address: String): Resource<Boolean>                           // on the configured chain
    suspend fun pinChallenge(circleUserId: String, kind: PinChallenge, idempotencyKey: UUID): Resource<Challenge>
}

data class Challenge(val challengeId: String, val sdk: SdkSession)

interface CustodyNotifications {     // implemented by wallet, wired in app; integrations never depend on wallet
    suspend fun onTransaction(update: CustodyTransaction): Resource<Unit>
}
```

**Contract details (as built in WS1).** `createWallet` for a user Circle has already initialized returns a `Challenge` with an empty `challengeId` and `needsSignature == false`; the caller then reads `wallets()`. `CustodyWallet` has id, address, blockchain, account type and state (`SETTING_UP`, `ACTIVE`, `FROZEN`). `CustodyTransaction` has circleTxId, circleUserId, walletId, blockchain, direction (`IN`, `OUT`), state, amount (`Money`), source and destination address, refId, txHash, failure reason and `updatedAt` (to ignore out-of-order updates). States: Circle's INITIATED, CLEARED, QUEUED, SENT → `SUBMITTED`; STUCK → `STUCK`; CONFIRMED, COMPLETE → `CONFIRMED`; FAILED, DENIED, CANCELLED → `FAILED`. Errors in our language: `PinLocked`, `InsufficientFunds`, `AddressInvalid`, plus `Unavailable` and `Rejected` with vendor-neutral messages. **Addresses are returned exactly as the chain gives them**; the wallet module normalises per chain when it compares or indexes them (EVM chains such as Base: lowercase; Solana: unchanged).

The `log` provider answers with made-up wallets and challenges and prints what it was asked; `fake` records calls for tests.

Circle's error codes stay inside the vendor package. "User already initialized" (`155106`) becomes success with the existing wallet; a locked PIN, insufficient funds or an invalid address become `ErrorKind.INVALID` with a wallet code; everything else is `UPSTREAM`. Every POST to Circle carries the idempotency key in the body, so the HTTP client may retry it.

**Webhooks:** Circle posts to `/v1/hooks/circle`. Each notification is signed with ECDSA over SHA-256: the signature in `X-Circle-Signature`, the key id in `X-Circle-Key-Id`, the public key from `GET /v2/notifications/publicKey/{keyId}`, cached for good by key id (it never changes). Verified against the raw body. `CircleWebhook` translates `transactions.inbound` and `transactions.outbound` into `CustodyTransaction` and hands it to `CustodyNotifications`; a failure returns non-200 so Circle retries. `WebhookVendor.verifySignature` is not `suspend` today, so the first sight of a new key id needs either a `suspend` signature or a key fetched before the check (see gaps in shared code below).

### Depends on

`shared`, `infrastructure:api`, `integrations:api`, `identity:api` (account status, `DeletionGuard`), `users:api` (`ReachPolicy`, `Profiles`) and `messaging:api` (`ChatDirectory`, for payments inside a group). Messaging never depends on `wallet:api`, so there is no cycle: the app learns who can be paid from W9, and messaging can react to `wallet.WalletCreated` if it ever needs to.

### New in shared code

- `WebhookVendor.verifySignature` needs to be able to fetch Circle's public key for a new key id (`suspend`, or a key provider the vendor warms).
- `CustodyNotifications` in `integrations:api`.
- Push wording for wallet frames in `GatewayRealtime.pushFor`.

## Endpoints

Paths relative to `https://api.circaso.com/v1`, all `app` scope. Every POST takes an `Idempotency-Key`. Responses never include the Circle API key; the `userToken` and `encryptionKey` only ever go to the app, never to the agent token.

| # | Route | Use case (domain) | Does |
| --- | --- | --- | --- |
| W1 | `GET /wallet` | `GetWallet` | Status (`NONE`, `SETTING_UP`, `ACTIVE`), address, USDC balance from Circle, and any hold |
| W2 | `POST /wallet` | `CreateWallet` | Creates the Circle user if needed and the initialize challenge; returns what the SDK needs |
| W3 | `POST /wallet/session` | `OpenSession` | The SDK values (`appId`, `userToken`, `encryptionKey`) from `StablecoinCustody.sdkSession` |
| W4 | `POST /wallet/transfers` | `PrepareTransfer` | Checks the rules and creates a transfer challenge for the user to sign |
| W5 | `GET /wallet/transfers/{transferId}` | `GetTransfer` | One transfer and its state |
| W6 | `GET /wallet/transactions?month=2026-10` | `ListTransactions` | The user's transactions for a month, newest first, paged |
| W7 | `GET /wallet/statements/{month}` | `GetStatement` | Totals in and out for a month, with the transactions; the app renders or exports it |
| W8 | `POST /wallet/pin/change` / `POST /wallet/pin/restore` | `ChangePin` / `RestorePin` | PIN challenges: change with the current PIN, or set a new one through the security questions |
| W9 | `GET /wallet/recipients/{userId}` | `CheckRecipient` | Whether this person can be paid: `canReceive`, or `NO_WALLET` (reach failures answer `users.not_reachable`) |
| W10 | `POST /wallet/recipients/{userId}/remind` | `RequestSetup` | Tells a contact without a wallet that someone wants to pay them; once per pair per day |
| W11 | `POST /wallet/addresses/validate` | `ValidateAddress` | Checks an outside address on the wallet's chain |

### W2 Create wallet

```json
// POST /wallet ← {}
// → data
{
  "status": "SETTING_UP",
  "challengeId": "c7a1…",
  "sdk": { "appId": "…", "userToken": "eyJ…", "encryptionKey": "Pq1…", "expiresAt": "2026-10-25T10:00:00Z" }
}
```

The app runs the challenge; the user sets the PIN and security questions. When Circle reports the wallet (W1 polls Circle's wallet list while `SETTING_UP`, and the first webhook confirms it), the status turns `ACTIVE` and `wallet.WalletCreated` is published. Calling W2 again while `SETTING_UP` returns a fresh challenge for the same Circle user; once `ACTIVE` it returns `wallet.already_exists`.

### W4 Prepare a transfer

```json
// POST /wallet/transfers ← request
{
  "to": { "userId": "0193…" },                   // or { "address": "0x…" }
  "amount": { "amountMinor": 20000000, "asset": "USDC" },   // 20 USDC
  "chatId": "0193…",                             // optional: paid from this chat
  "proposalId": null                             // set when confirming an Asha proposal
}
// → data
{ "transferId": "0193…", "challengeId": "c7a2…", "sdk": { "userToken": "eyJ…", "encryptionKey": "Pq1…" }, "fee": { "amountMinor": 0, "asset": "USDC" }, "expiresAt": "2026-10-25T09:15:00Z" }
```

Checks, in order: account `ACTIVE`; wallet `ACTIVE`; no payment hold; amount above zero and within limits; the recipient reachable (or a member of `chatId` with the sender) and holding a wallet, or the address valid; balance (from Circle) enough. Then the transfer row is written as `AWAITING_SIGNATURE` and `StablecoinCustody.prepareTransfer` creates Circle's transfer challenge with `idempotencyKey` derived from the request's `Idempotency-Key` and `refId = transferId`, so the webhook leads back to it. If the user doesn't sign within 15 minutes the transfer becomes `EXPIRED`.

**Transfer states:** `AWAITING_SIGNATURE` → `SUBMITTED` (Circle `INITIATED`, `QUEUED`, `SENT`) → `CONFIRMED` (`CONFIRMED`, `COMPLETE`) or `FAILED` (`FAILED`, `DENIED`, `CANCELLED`); `EXPIRED` if never signed; `STUCK` is shown as submitted and handled by the stuck-transfer job.

### W6 Transactions

```json
// GET /wallet/transactions?month=2026-10 → data
{
  "transactions": [
    {
      "id": "0193…", "direction": "OUT", "state": "CONFIRMED",
      "amount": { "amountMinor": 20000000, "asset": "USDC" },
      "counterparty": { "user": PublicProfile },   // or { "address": "0x…" }
      "chatId": "0193…", "origin": "APP", "txHash": "0x…",
      "occurredAt": "2026-10-25T09:02:11Z"
    }
  ],
  "next": "…"
}
```

### Agent routes (read-only, for Asha)

`GET /v1/agent/wallet` (status and balance) and `GET /v1/agent/wallet/transactions` (same shape as W6), so Asha can answer "How much did I send Mum yesterday?". No route under `/v1/agent/` can prepare a transfer or open a Circle session.

## Data

Kandra entities in keyspace `circaso_wallet` (plus `WalletRealtimeRow` and `WalletOutboxRow`), Redis keys under `wal:`.

```kotlin
const val KS = "circaso_wallet"

@ScyllaTable(name = "circle_users", keyspace = KS)
data class CircleUserRow(
    @PartitionKey val userId: UUID,
    val circleUserId: UUID,                     // random; what Circle knows us by, never our user id
    val createdAt: Instant,
)

@ScyllaTable(name = "wallets_by_user", keyspace = KS)
data class WalletRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey val circleWalletId: String,
    val blockchain: String,                     // BASE | BASE-SEPOLIA
    val accountType: String,                    // SCA
    val address: String,                        // lowercase hex
    val state: String,                          // SETTING_UP | ACTIVE | FROZEN
    val createdAt: Instant,
)

@ScyllaTable(name = "wallets_by_address", keyspace = KS)       // inbound webhooks and paying a contact
data class WalletAddressRow(
    @PartitionKey val address: String,          // lowercase hex; one small partition per address
    @ClusteringKey val blockchain: String,
    val userId: UUID, val circleWalletId: String,
)

@ScyllaTable(name = "wallets_by_circle_id", keyspace = KS)     // webhooks carry Circle's wallet id
data class WalletCircleIdRow(@PartitionKey val circleWalletId: String, val userId: UUID)

@ScyllaTable(name = "transfers", keyspace = KS)
data class TransferRow(
    @PartitionKey val transferId: UUID,
    val senderId: UUID, val senderWalletId: String,
    val recipientId: UUID = NIL_UUID,           // nil for an outside address
    val destinationAddress: String,
    val amountMinor: Long, val asset: String,
    val state: String,                          // AWAITING_SIGNATURE | SUBMITTED | CONFIRMED | FAILED | EXPIRED
    val circleTxId: String = "", val txHash: String = "",
    val chatId: UUID = NIL_UUID,
    val origin: String,                         // APP | ASHA
    val proposalId: UUID = NIL_UUID,
    val failureReason: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
    @Version val version: Long = 0,
)

@ScyllaTable(name = "transfers_by_idempotency", keyspace = KS)
@Ttl(seconds = 60 * 60 * 24)
data class TransferIdempotencyRow(@PartitionKey val senderId: UUID, @ClusteringKey val idempotencyKey: UUID, val transferId: UUID)

@ScyllaTable(name = "transactions_by_user_month", keyspace = KS)   // history and statements
data class TransactionRow(
    @PartitionKey(index = 0) val userId: UUID,
    @PartitionKey(index = 1) val month: String, // "2026-10", so one month is one partition
    @ClusteringKey val id: UUID,                // OrderedIds, descending
    val direction: String,                      // IN | OUT
    val state: String,
    val amountMinor: Long, val asset: String,
    val counterpartyId: UUID = NIL_UUID, val counterpartyAddress: String,
    val transferId: UUID = NIL_UUID,            // set for outgoing transfers prepared here
    val circleTxId: String, val txHash: String = "",
    val chatId: UUID = NIL_UUID, val origin: String = "APP",
    val occurredAt: Instant,
)

@ScyllaTable(name = "transactions_by_circle_id", keyspace = KS)    // a webhook finds the rows it updates
data class TransactionCircleIdRow(
    @PartitionKey val circleTxId: String,
    @ClusteringKey val userId: UUID,            // sender and recipient each have a row
    val month: String, val id: UUID,
)

@ScyllaTable(name = "payment_holds", keyspace = KS)
data class PaymentHoldRow(@PartitionKey val userId: UUID, val until: Instant, val reason: String)   // RELINKED
```

A payment between two Circaso users writes two `TransactionRow`s: the sender's `OUT`, created when the transfer is prepared and updated from `transactions.outbound`, and the recipient's `IN`, created from `transactions.inbound` (the recipient is found through `wallets_by_address`). Payments from outside addresses only have the `IN` row.

**Redis (`wal:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `wal:balance:{circleWalletId}` | balance in minor units | 15 s, cleared on any webhook for the wallet | W1 without calling Circle on every open |
| `wal:sent:{userId}:{date}` | minor units sent today | 2 days | Daily limit |
| `wal:remind:{from}:{to}` | `1` | 24 h | W10 once a day per pair |
| `wal:seq:{userId}` | counter | 8 days, renewed on use | Next realtime seq for wallet frames |

**Scheduled jobs** (Architecture → Scheduled jobs)

| Job | Every | Does |
| --- | --- | --- |
| `ExpireUnsignedTransfers` | 1 min | `AWAITING_SIGNATURE` older than 15 min → `EXPIRED` |
| `StuckTransfers` | 5 min | `SUBMITTED` older than 15 min: read the transaction from Circle and apply its state; still stuck after an hour → alert and tell the sender it's taking longer |
| `ReconcileWallets` | daily | For wallets with activity that day, compare Circle's transaction list with ours and fill or correct missing rows (a lost webhook) |

## Events

**Consumed**

| Stream (group `wallet`) | Event | What wallet does |
| --- | --- | --- |
| `events:identity` | `identity.AccountRelinked` | Writes a 48-hour `PaymentHoldRow`, if the hold is kept (Open questions) |
| `events:identity` | `identity.AccountStatusChanged` | Suspended or blocked: W4 refuses; wallets aren't frozen at Circle, which we can't do for user-controlled wallets |
| `events:identity` | `identity.AccountDeleted` | Deletion only happens once the guard has seen an empty wallet; marks the wallet `CLOSED` and keeps transaction records for the retention period (Open questions) |

**From Circle** (through `CustodyNotifications`, not a stream): each transaction update finds its rows by `refId` (outgoing) or by `circleTxId` and wallet address (incoming), applies the state if it's newer, clears the cached balance, and sends frames.

**Published** (stream `events:wallet`)

| Event | `data` | Consumers |
| --- | --- | --- |
| `wallet.WalletCreated` v1 | `userId`, `blockchain` | gateway (frames) |
| `wallet.TransferConfirmed` v1 | `transferId`, `senderId`, `recipientId?`, `amount`, `origin`, `proposalId?` | assistant (outcome of a proposal), auditor |
| `wallet.TransferFailed` v1 | `transferId`, `senderId`, `reason` | assistant, auditor |

**Realtime frames** (module `wallet`)

| Type | Goes to | Push if offline |
| --- | --- | --- |
| `transfer.updated` | The sender | Yes on `CONFIRMED` and `FAILED`: "Payment sent" / "Payment failed" |
| `payment.received` | The recipient | Yes: "Amina sent you money" (no amount on the lock screen) |
| `wallet.ready` | The user, when the wallet turns `ACTIVE` | No |
| `setup.requested` | A contact someone wants to pay (W10) | Yes: "Amina wants to send you money. Set up your wallet" |

Wallet implements `RealtimeReplay` with `realtime_by_user`, as users does.

## Error codes

| Code | Kind → status | When |
| --- | --- | --- |
| `wallet.not_found` | NOT_FOUND → 404 | The user has no wallet yet |
| `wallet.already_exists` | CONFLICT → 409 | W2 when the wallet is already active |
| `wallet.not_ready` | CONFLICT → 409 | The wallet is still being set up |
| `wallet.on_hold` | FORBIDDEN → 403 | Outgoing payments are on hold after a support restore; `data.until` says when they resume |
| `wallet.amount_invalid` | INVALID → 400 | Zero, negative, more than 6 decimals, or an asset not offered |
| `wallet.insufficient_funds` | INVALID → 400 | Balance below the amount |
| `wallet.limit_exceeded` | INVALID → 400 | Over the per-payment or daily limit |
| `wallet.recipient_no_wallet` | CONFLICT → 409 | The contact hasn't set up a wallet |
| `wallet.address_invalid` | INVALID → 400 | Not a valid address on the wallet's chain |
| `wallet.self_transfer` | INVALID → 400 | Paying your own wallet |
| `wallet.transfer_not_found` | NOT_FOUND → 404 | No such transfer, or not this user's |
| `wallet.funds_remaining` | CONFLICT → 409 | `DeletionGuard`: the wallet still holds 0.01 USDC or more |
| `wallet.custody_unavailable` | UPSTREAM → 502 | Circle is down or the circuit is open |

## Not in v1

Chama treasuries (a group-owned wallet needs multi-party approval, which user-controlled wallets don't give us directly); payment requests and splitting a bill; recurring payments; cash in and cash out through M-PESA (`MobileMoney`); more than one chain or asset; showing balances in KES.

## Open questions

These also go into `decisions.md`.

- **Chain.** Proposed: Base, with SCA accounts and Gas Station. Alternatives: Polygon (similar), Solana (no SCA; Gas Station pays as fee payer), Arc (Circle's chain, USDC as gas) once it's on mainnet.
- **Authentication method.** Proposed: PIN with security questions, plus biometrics. Social login or email OTP instead?
- **Who pays gas.** Proposed: Circaso sponsors all gas in v1, with per-wallet daily caps in the Gas Station policy. A small fee on outside transfers later?
- **Payment hold after a support restore** (already open): keep the 48-hour hold on outgoing transfers, kept here as `PaymentHoldRow` keyed on `AccountRelinked`, or drop it because the 48-hour notice already gives the owner time to cancel? Proposed: keep it.
- **Limits and KYC.** Per-payment and daily limits for accounts that aren't KYC-verified, and higher ones for verified accounts (identity's `verification`). Linked to Kenya's Virtual Asset Service Providers Act, 2025 and whether Circaso needs a licence for user-controlled wallets: a legal question to settle before money moves in production.
- **Record retention.** How long transaction records are kept after an account is deleted (financial records usually have a statutory minimum; the Data Protection Act question already open).
- **Deletion error code.** Identity publishes `identity.wallet_not_empty` to the app (guide and mobile doc) while its `DeletionGuard` contract says the guard's own code (`wallet.funds_remaining`) comes back. One of the two has to change.
- **Showing KES.** A rate source for showing balances and amounts in KES next to USDC.
