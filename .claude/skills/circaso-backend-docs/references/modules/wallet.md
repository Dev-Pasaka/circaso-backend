# Wallet module

Wallet answers "what do I hold, and how do I send it to someone": it gives each user a Circle user-controlled wallet on request, prepares transfers that only the user can sign, and keeps the record of every transaction for statements and audit. It never holds keys and never moves money on its own: the backend prepares, the user signs with their Circle PIN, the chain executes. Schema `wallet`, Redis prefix `wal:`, stream `events:wallet`.

Status: draft, 2026-10-25. Proposals marked **Proposed** are listed again under Open questions; Circle details are from Circle's user-controlled wallets docs and API reference as read on 2026-10-25.

## Overview

### Owns

- **Circle users and wallets**: the link between a Circaso account and its Circle user (Circle never sees our user id) and the user's wallet, created when the user taps "Create wallet" in the wallet section (decision 2026-10-25), on one or more chains (decision 2026-10-28): one Circle wallet, with its own address, per chain.
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

**Decided 2026-10-28 (Pasaka): a wallet can be on several chains, with several assets on each.** Supported at launch: **Base, Polygon, Ethereum and Solana**, USDC on each. The supported list (chains, and the assets offered on each) is server configuration in integrations (`INTEGRATIONS_CUSTODY_CHAINS`, below), so a chain or an asset is added by configuration, not by a new model. A new wallet starts on **Base** (the default chain); the user adds the other supported chains themselves from wallet management (W12), each with its own address. Removing a chain from a wallet is not offered (Open questions).

**Retiring a chain (decided 2026-10-28).** A chain is never taken out of `INTEGRATIONS_CUSTODY_CHAINS` while any wallet holds funds on it. It is retired with its receive switch, `INTEGRATIONS_CUSTODY_CHAIN_<CHAIN>_RECEIVE_DISABLED=true` (`CustodyChain.receiveEnabled = false`): it stays readable (W1 shows it with `receiveEnabled: false`), the deletion guard keeps checking it, W12 doesn't add it (and lists it only to wallets already on it), and W4 and W9 pay nobody inside Circaso on it; its wallets can still send out to an outside address, so it empties. The default chain can't be retired. A chain that is unconfigured anyway stays fail-closed: W1 shows it with nothing on it and the deletion guard refuses with `wallet.custody_unavailable`.

**Proposed: smart-contract accounts (`SCA`) on the EVM chains, gas paid by Circaso through Circle Gas Station.**

- **Base:** low fees, native USDC, and Circle supports user-controlled wallets and Gas Station on it. Dev and staging use the testnets (`BASE-SEPOLIA`, `MATIC-AMOY`, `ETH-SEPOLIA`, `SOL-DEVNET`) with test USDC.
- **SCA:** an ERC-4337 account, which Gas Station needs on EVM chains. Users never hold ETH or see a gas fee. **Solana has no SCA**: its wallets are `EOA` (Gas Station pays there as fee payer). The account type is per chain in integrations' settings.
- **Gas Station:** Circaso pays the gas plus Circle's 5% fee on it, billed to the Circle account, under a sponsorship policy with per-wallet limits set in Circle's console.
- **Chain-agnostic model:** one wallet per user (`circle_users`), one row per chain under it (`wallets`: Circle wallet id, chain, address), every row storing the `blockchain` the contract returned. Addresses are compared per chain: EVM in lowercase, Solana's base58 as given.
- **Assets:** each chain offers a configured list of assets (symbol, token address or mint, decimals, whether it is a US dollar stablecoin), USDC on every chain at launch. Balances are read for every offered asset; amounts that may pass a `Long` (18-decimal tokens) are big integers. Transfers stay USDC only (`Asset.USDC` in `shared`) until a later decision says otherwise.
- **Amounts on the wire (decided 2026-10-28):** strings across the whole wallet module: `amountMinor` (the exact integer in the asset's smallest unit) and `amount` (whole units, for display), in requests and responses (W1, W4–W7), frames and events.

### Paying a person

- **A transfer names its chain (decided 2026-10-28):** W4 carries `blockchain` and the transfer is sent from the user's wallet on that chain.
- **Reach rule:** paying a Circaso user needs `users.ReachPolicy.canReach(sender, recipient)`, like a message. Inside a chat, paying a member also works through `messaging.ChatDirectory.isMember` for both people (`chatId`), unless either has blocked the other (a shared chat never overrides a block).
- **The recipient needs a wallet on that chain.** The backend can't create one for them: creating it takes their PIN. If they have none, W9 says `NO_WALLET` and the app offers to remind them ("Amina hasn't set up her wallet yet"); the reminder goes as the `wallet/setup.requested` frame with a push. If they have a wallet but not on the transfer's chain (or only on a retired one), W9 says `NO_WALLET_ON_CHAIN` and lists the chains they can be paid on, and W4 answers `wallet.recipient_not_on_chain`. Nothing is held for them in the meantime.
- **To an outside address:** an address valid on the transfer's own chain, checked with Circle's `validateAddress` first (W11 lets the app check it before W4). Paying your own address is `wallet.self_transfer`; one of our wallets on a retired chain can't be paid. The app shows a warning that payments on chain can't be reversed.
- **Pay in a chat:** the payment card is an end-to-end encrypted chat message that carries the `transferId`; the wallet only records the `chatId` (which the server already knows). A note written with the payment travels in the encrypted message and is never stored by the wallet.
- **From Asha** (Phase B): the app calls W4 with the user token and the `proposalId`; the transfer records `origin: ASHA`. The PIN is still required. Until Phase B is built, W4 refuses a non-null `proposalId` with `request.malformed` (field `proposalId`), since nothing can check it against the assistant's proposal yet.

### Contract (`wallet:api`)

```kotlin
interface Wallets {                  // read by the assistant later (Phase B proposals)
    suspend fun hasWallet(userId: UserId): Boolean
}
```

Wallet also implements identity's `DeletionGuard` (built in WS2): it refuses with `wallet.funds_remaining` while the US dollar stablecoins it holds, **added up over every chain**, come to 0.01 or more, or while it holds any amount of any other asset (decided 2026-10-28: 0.009 USDC on each of four chains is 0.036, refused). It reads Circle, never the cache, for every wallet Circle lists for the user (a second wallet on a chain, which `wallets_user_chain_key` keeps out of our rows, included) and every open row of ours, retired chains included. When Circle can't answer, or a wallet sits on a chain that is unconfigured anyway (never done while funds are on it: chains are retired instead, above), it refuses with `wallet.custody_unavailable` and, for the chain, logs a WARN with the chain and Circle's wallet id. It also implements `RealtimeReplay` (module `wallet`), and `CustodyNotifications` from integrations (below).

### Integrations used

**All Circle setup and configuration lives in integrations, as for every other vendor; the wallet module only calls the contract.** The wallet never sees the Circle API key, the app id, the account types, token ids or a Circle user token's lifetime: `CircleCustody` reads them from its own settings and `Secrets`, and the wallet stores whatever the contract returns (wallet id, address, chain). The supported chains and their assets reach the wallet only through `StablecoinCustody.supported`.

`StablecoinCustody` in `integrations:api`, implemented by `CircleCustody` in `integrations/impl/.../circle/` over `HttpClients.named("circle")`, base URL `https://api.circle.com`. Chosen and configured like the others:

| Setting | Example | Purpose |
| --- | --- | --- |
| `INTEGRATIONS_CUSTODY_PROVIDER` | `log` \| `fake` \| `circle` | `log` locally and on the sandbox, `fake` in tests, `circle` once real wallets are wanted |
| `CIRCLE_API_KEY` (secret) | `TEST_API_KEY:…` | Circle API key; testnet and mainnet keys are separate |
| `INTEGRATIONS_CUSTODY_APP_ID` | `…` | Circle app id, handed to the phone's SDK |
| `INTEGRATIONS_CUSTODY_CHAINS` | `BASE-SEPOLIA,MATIC-AMOY,ETH-SEPOLIA,SOL-DEVNET` (`BASE,MATIC,ETH,SOL` in production) | The supported chains, by Circle's names. Read by every custody provider, so `log` and `fake` offer what `circle` would; unset, the four testnets |
| `INTEGRATIONS_CUSTODY_DEFAULT_CHAIN` | `BASE-SEPOLIA` (`BASE` in production) | The chain a new wallet starts on (W2) |
| `INTEGRATIONS_CUSTODY_CHAIN_<CHAIN>_ASSETS` | `USDC:0x036C…CF7e:6:usd` | The assets offered on one chain (`<CHAIN>` with `-` as `_`), comma separated, each `SYMBOL:tokenAddress:decimals`, `:usd` after a US dollar stablecoin, empty address for the native coin. Default for the four testnets: their USDC; a mainnet chain must name its assets. Transfers name the token by address + blockchain (Circle's token ids are resolved to it once and cached) |
| `INTEGRATIONS_CUSTODY_CHAIN_<CHAIN>_ACCOUNT_TYPE` | `SCA` | Account type for wallets on that chain: `SCA` by default, `EOA` by default on Solana |
| `INTEGRATIONS_CUSTODY_CHAIN_<CHAIN>_RECEIVE_DISABLED` | `true` | Retires a chain (decided 2026-10-28): readable and checked by the deletion guard, never added by W12, nobody paid on it. **Operators never remove a chain from `INTEGRATIONS_CUSTODY_CHAINS` while any wallet holds funds on it: disable receive instead.** Not allowed on the default chain |

These replace WS1's single-chain `INTEGRATIONS_CUSTODY_BLOCKCHAIN`, `_ACCOUNT_TYPE` and `_USDC_TOKEN_ADDRESS` (changed in WS2).

Our language in and out. Circle user tokens are the vendor's business: `CircleCustody` asks Circle for one when it needs it and keeps it in memory per node until 5 minutes before it expires, so the wallet only passes our `circleUserId`. Where the phone needs to run a challenge, the result carries the SDK values with it.

```kotlin
interface StablecoinCustody {
    val supported: SupportedChains                                                            // default chain, chains, assets per chain
    suspend fun createUser(circleUserId: String): Resource<Unit>                              // already-exists is success
    suspend fun createWallet(circleUserId: String, idempotencyKey: UUID): Resource<Challenge> // initialize, on the default chain
    suspend fun addWallet(circleUserId: String, blockchain: String, idempotencyKey: UUID): Resource<Challenge> // one more chain
    suspend fun sdkSession(circleUserId: String): Resource<SdkSession>                        // appId, userToken, encryptionKey, expiresAt
    suspend fun wallets(circleUserId: String): Resource<List<CustodyWallet>>                  // one per chain: id, address, chain, state
    suspend fun balance(circleUserId: String, walletId: String, asset: Asset): Resource<Money>
    suspend fun balances(circleUserId: String, walletId: String, blockchain: String): Resource<List<AssetBalance>> // every offered asset
    suspend fun prepareTransfer(circleUserId: String, request: TransferRequest): Resource<Challenge> // blockchain, idempotencyKey, refId
    suspend fun transaction(circleUserId: String, circleTxId: String): Resource<CustodyTransaction>
    suspend fun transactions(circleUserId: String, since: Instant, limit: Int): Resource<List<CustodyTransaction>>  // the jobs: GET /v1/w3s/transactions?from=&pageSize= as the user
    suspend fun validateAddress(blockchain: String, address: String): Resource<Boolean>
    suspend fun pinChallenge(circleUserId: String, kind: PinChallenge, idempotencyKey: UUID): Resource<Challenge>
}

data class SupportedChains(val default: String, val chains: List<CustodyChain>)
data class CustodyChain(val blockchain: String, val assets: List<CustodyAsset>)
data class CustodyAsset(val symbol: String, val tokenAddress: String, val decimals: Int, val usdPegged: Boolean)
data class AssetBalance(val asset: CustodyAsset, val amountMinor: BigInteger)   // zero for an offered asset not held
data class Challenge(val challengeId: String, val sdk: SdkSession)

interface CustodyNotifications {     // implemented by wallet, wired in app; integrations never depend on wallet
    suspend fun onTransaction(update: CustodyTransaction): Resource<Unit>
}
```

**Contract details (as built in WS1).** `createWallet` for a user Circle has already initialized returns a `Challenge` with an empty `challengeId` and `needsSignature == false`; the caller then reads `wallets()`. `CustodyWallet` has id, address, blockchain, account type and state (`LIVE`, `FROZEN`, as Circle reports them; Circle lists a wallet only once it exists, so `SETTING_UP` is the wallet module's own state, not custody's). `CustodyTransaction` has circleTxId, circleUserId, walletId, blockchain, direction (`IN`, `OUT`), state, amount (`Money`), source and destination address, refId, txHash, failure reason and `updatedAt` (to ignore out-of-order updates). States: Circle's INITIATED, CLEARED, QUEUED, SENT → `SUBMITTED`; STUCK → `STUCK`; CONFIRMED, COMPLETE → `CONFIRMED`; FAILED, DENIED, CANCELLED → `FAILED`. Errors in our language: `PinLocked`, `InsufficientFunds`, `AddressInvalid`, plus `Rejected` with a vendor-neutral message and `Unavailable` naming the vendor, as every integration does. **Addresses are returned exactly as the chain gives them**; the wallet module normalises per chain when it compares or indexes them (EVM chains such as Base: lowercase; Solana: unchanged).

**Several chains (WS2).** `addWallet` is Circle's `POST /v1/w3s/user/wallets` (`idempotencyKey`, `accountType`, `blockchains`) as the user: the challenge the user signs with the PIN, after which Circle lists the new wallet. `balances` reads `GET /v1/w3s/wallets/{id}/balances` and answers one line per asset offered on the chain (configured order; zero when not held), ignoring tokens that aren't offered. A chain that isn't supported is `Rejected` without calling Circle. Transactions in assets other than USDC are not translated yet (Open questions).

The `log` provider answers with made-up wallets and challenges and prints what it was asked; `fake` records calls for tests.

Circle's error codes stay inside the vendor package. "User already initialized" (`155106`) becomes success with the existing wallet; a locked PIN, insufficient funds or an invalid address become `ErrorKind.INVALID` with a wallet code; everything else is `UPSTREAM`. Every POST to Circle carries the idempotency key in the body, so the HTTP client may retry it.

**Webhooks:** Circle posts to `/v1/hooks/circle`. Each notification is signed with ECDSA over SHA-256: the signature in `X-Circle-Signature`, the key id in `X-Circle-Key-Id`, the public key from `GET /v2/notifications/publicKey/{keyId}`, cached for good by key id (it never changes). Verified against the raw body. `CircleWebhook` translates `transactions.inbound` and `transactions.outbound` into `CustodyTransaction` and hands it to `CustodyNotifications`; a failure returns non-200 so Circle retries. `WebhookVendor.verifySignature` is `suspend` (built in WS1), so a new key id is fetched on first sight, at most 10 a minute per node.

### Depends on

`shared`, `infrastructure:api`, `integrations:api`, `identity:api` (account status, `DeletionGuard`), `users:api` (`ReachPolicy`, `Profiles`) and `messaging:api` (`ChatDirectory`, for payments inside a group). Messaging never depends on `wallet:api`, so there is no cycle: the app learns who can be paid from W9, and messaging can react to `wallet.WalletCreated` if it ever needs to.

### New in shared code

- Done in WS1: `WebhookVendor.verifySignature` is `suspend`, `WebhookVendor.answersAfterTranslation`, `HEAD /v1/hooks/{vendor}`, and `CustodyNotifications` in `integrations:api`.
- Done in WS3: push wording for wallet frames in `GatewayRealtime.pushFor` (the gateway depends on `wallet:api` for the frame classes); `CustodyChain.receiveEnabled` in `integrations:api`.

## Endpoints

Paths relative to `https://api.circaso.com/v1`, all `app` scope. Every POST takes an `Idempotency-Key`. Responses never include the Circle API key; the `userToken` and `encryptionKey` only ever go to the app, never to the agent token.

| # | Route | Use case (domain) | Does |
| --- | --- | --- | --- |
| W1 | `GET /wallet?currency=KES` | `GetWallet` | Status (`NONE`, `SETTING_UP`, `ACTIVE`), each chain the wallet is on with its address and every asset offered there (balances from Circle), the totals per asset over all chains, and any hold. With `currency` (ISO 4217, the person's country-of-origin currency, chosen by the app), also `fiat { currency, amount, rate, asOf, partial }` at USDC = 1 USD: only US dollar stablecoins are priced, `partial` says something held has no price yet; null when no rate is available, never an error. An unknown code answers `request.malformed` |
| W2 | `POST /wallet` | `CreateWallet` | Creates the Circle user if needed and the initialize challenge; returns what the SDK needs |
| W3 | `POST /wallet/session` | `OpenSession` | The SDK values (`appId`, `userToken`, `encryptionKey`) from `StablecoinCustody.sdkSession` |
| W4 | `POST /wallet/transfers` | `PrepareTransfer` | Checks the rules and creates a transfer challenge, on the chain the request names, for the user to sign |
| W5 | `GET /wallet/transfers/{transferId}` | `GetTransfer` | One transfer and its state |
| W6 | `GET /wallet/transactions?month=2026-10&cursor=&limit=` | `ListTransactions` | The user's payments, newest first, paged: one UTC month with `month`, every month without it (decision 2026-10-31) |
| W7 | `GET /wallet/statements/{month}` | `GetStatement` | Totals in and out for a month, with the transactions; the app renders or exports it |
| W8 | `POST /wallet/pin/change` / `POST /wallet/pin/restore` | `ChangePin` / `RestorePin` | PIN challenges: change with the current PIN, or set a new one through the security questions |
| W9 | `GET /wallet/recipients/{userId}?blockchain=BASE&chatId=…` | `CheckRecipient` | Whether this person can be paid: `CAN_RECEIVE`, `NO_WALLET` or `NO_WALLET_ON_CHAIN`, with the chains they can be paid on (reach failures answer `users.not_reachable`) |
| W10 | `POST /wallet/recipients/{userId}/remind` | `RequestSetup` | Tells a contact without a wallet that someone wants to pay them; once per pair per day |
| W11 | `POST /wallet/addresses/validate` | `ValidateAddress` | Checks an outside address on a chain: `{ blockchain, address }` → `valid` |
| W12 | `GET /wallet/chains` / `POST /wallet/chains` | `ListChains` / `AddChain` | The supported chains with the assets offered on each and whether the wallet is on each (`ENABLED`, `ADDING`, `AVAILABLE`); adding one returns the Circle challenge that creates the wallet there (decision 2026-10-28) |

### W1 The wallet

```json
// GET /wallet?currency=KES → data
{
  "status": "ACTIVE",
  "chains": [
    {
      "blockchain": "BASE", "address": "0x5a3c…", "state": "ACTIVE", "receiveEnabled": true,
      "assets": [ { "symbol": "USDC", "tokenAddress": "0x8335…2913", "decimals": 6,
                    "amountMinor": "20500000", "amount": "20.5", "fiatAmount": "2649.63" } ]
    },
    { "blockchain": "SOL", "address": "7xKX…", "state": "ACTIVE", "assets": [ … ] }
  ],
  "totals": [ { "symbol": "USDC", "decimals": 6, "amountMinor": "21750000", "amount": "21.75" } ],
  "fiat": { "currency": "KES", "amount": "2811.19", "rate": "129.25", "asOf": "2026-10-28T00:00:00Z", "partial": false },
  "hold": null
}
```

Amounts of crypto assets are strings (`amountMinor` in the smallest unit, `amount` in whole units for display): an 18-decimal token passes what a JSON number holds exactly. Fiat amounts are decimal strings rounded half up to the currency's minor unit. `SETTING_UP` means the Circle user exists and no chain's wallet does yet: it is derived (a `circle_users` row with no `wallets` row), not stored. While `SETTING_UP`, or while a chain is being added (`wal:adding`), W1 reads Circle's wallet list first and records any new wallet; balances come through `wal:balance` (15 s). `hold` is filled by WS4 (`payment_holds`). `receiveEnabled: false` marks a chain retired from receiving: the app doesn't show its address to be paid, but it can still send out.

### W12 Chains

```json
// GET /wallet/chains → data
{ "defaultChain": "BASE", "chains": [ { "blockchain": "BASE", "assets": ["USDC"], "status": "ENABLED", "receiveEnabled": true },
                                      { "blockchain": "SOL", "assets": ["USDC"], "status": "AVAILABLE", "receiveEnabled": true } ] }
// POST /wallet/chains ← { "blockchain": "SOL" }
// → data
{ "blockchain": "SOL", "status": "ADDING", "challengeId": "c7a3…", "sdk": { … } }
```

A chain already on the wallet answers `ENABLED` with no challenge, so a repeat is harmless. Circle's idempotency key for `addWallet` is derived from the user, the chain and the current hour (decided 2026-10-28), not from the request's `Idempotency-Key`, so two adds of the same chain at once (two taps, two phones) get the same Circle challenge. A chain retired from receiving is never added (`wallet.chain_unsupported`), and `GET` lists it only to a wallet already on it, with `receiveEnabled: false`. Otherwise Circle's wallet list is **always** read and recorded before asking for a challenge, whatever `wal:adding` says, so a user who has signed (on this phone or another) doesn't get a second challenge and a second wallet on that chain; Circle being down answers `wallet.custody_unavailable` and asks for nothing. A chain that isn't supported answers `wallet.chain_unsupported`; a wallet still `SETTING_UP` answers `wallet.not_ready`. The new chain shows in W1 once Circle has made the wallet, and `wallet.WalletCreated` goes out for it.

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

The app runs the challenge; the user sets the PIN and security questions, and Circle creates the wallet on the default chain. When Circle reports the wallet (W1 polls Circle's wallet list while `SETTING_UP`, and the first webhook confirms it), the status turns `ACTIVE` and `wallet.WalletCreated` is published. Calling W2 again while `SETTING_UP` returns a fresh challenge for the same Circle user; once `ACTIVE` it returns `wallet.already_exists`. When Circle has nothing left to sign (the PIN is set) but lists no wallet yet, W2 answers `SETTING_UP` with `challengeId: null`. Circle's idempotency key is a name-based UUID of the user, the operation and the request's `Idempotency-Key` (as W4).

### W4 Prepare a transfer

```json
// POST /wallet/transfers ← request
{
  "blockchain": "BASE",                          // the chain to send on (decided 2026-10-28)
  "to": { "userId": "0193…" },                   // or { "address": "0x…" }, valid on that chain
  "amount": { "asset": "USDC", "amountMinor": "20000000" },   // or "amount": "20"; both only if they agree
  "chatId": "0193…",                             // optional: paid from this chat
  "proposalId": null                             // set when confirming an Asha proposal
}
// → data
{ "transferId": "0193…", "state": "AWAITING_SIGNATURE", "blockchain": "BASE",
  "amount": { "asset": "USDC", "amountMinor": "20000000", "amount": "20" },
  "challengeId": "c7a2…", "sdk": { "appId": "…", "userToken": "eyJ…", "encryptionKey": "Pq1…", "expiresAt": "…" },
  "fee": { "asset": "USDC", "amountMinor": "0", "amount": "0" }, "expiresAt": "2026-10-25T09:15:00Z" }
```

Checks, in order: account `ACTIVE` (`identity.AccountDirectory`; else `wallet.account_restricted`); the chain supported and the wallet `ACTIVE` on it (`wallet.chain_unsupported`, `wallet.not_found`, `wallet.not_ready`, `wallet.not_on_chain`); no payment hold; amount above zero, an asset offered on the chain, and within the per-payment and daily limits; the recipient reachable (or a member of `chatId` with the sender, unless blocked) and holding a wallet on that chain that still receives, or the address valid on that chain; balance (from Circle, never the cache) on that chain enough. Then the amount is reserved in today's limit (`wal:sent`), and the transfer row is written as `AWAITING_SIGNATURE` with the sender's `OUT` row in one transaction, **before** Circle is asked, so two requests with one key never make two transfers. Then `StablecoinCustody.prepareTransfer` creates Circle's transfer challenge with `idempotencyKey` derived from the request's `Idempotency-Key` (the same UUID is the row's `idempotency_key`) and `refId = transferId`, so the webhook leads back to it. When Circle refuses (insufficient funds, invalid address, a locked PIN), the transfer turns `FAILED` with the wallet code as `failure_reason` and its `TransferFailed` written to the outbox in the same transaction (published after the commit, once per transfer), the reservation is given back and the code is answered; when Circle is down the transfer stays `AWAITING_SIGNATURE` and the same request asks again. If the user doesn't sign within 15 minutes the transfer becomes `EXPIRED` (WS4's job). A transfer is unique per sender and `Idempotency-Key` for good, so a reused key returns the same transfer (with Circle's same challenge while it can still be signed, else none), even after the gateway's 24-hour replay window.

Limits are settings until they are decided (`WALLET_TRANSFER_LIMIT_PER_PAYMENT`, `WALLET_TRANSFER_LIMIT_DAILY`, whole USDC, defaults 500 and 1 000; `TODO(decision)`: limits and KYC). The fee is zero: Circaso pays gas (Open questions).

**Transfer states:** `AWAITING_SIGNATURE` → `SUBMITTED` (Circle `INITIATED`, `QUEUED`, `SENT`) → `CONFIRMED` (`CONFIRMED`, `COMPLETE`) or `FAILED` (`FAILED`, `DENIED`, `CANCELLED`); `EXPIRED` if never signed; `STUCK` is shown as submitted and handled by the stuck-transfer job. The transition guard's allowed predecessors: `SUBMITTED` ← `AWAITING_SIGNATURE`, `EXPIRED`; `CONFIRMED` and `FAILED` ← `AWAITING_SIGNATURE`, `SUBMITTED`, `EXPIRED`; `EXPIRED` ← `AWAITING_SIGNATURE`. An `EXPIRED` transfer still follows Circle: if the chain says it was signed after all, the chain is right.

### W5 One transfer

`GET /wallet/transfers/{transferId}` → `{ transferId, direction, state, blockchain, from, to: { userId?, address }, amount, fee, chatId, origin?, proposalId?, txHash, failureReason, createdAt, updatedAt, expiresAt }`, amounts as strings. Shown to the sender (`direction: OUT`) and to the person paid (`IN`, so the chat's payment card works for both); `origin` and `proposalId` only to the sender (whether the sender used Asha is theirs to know); anyone else gets `wallet.transfer_not_found`.

### W9 Can this person be paid

`GET /wallet/recipients/{userId}?blockchain=BASE&chatId=…` (both optional) → `{ userId, status, canReceive, blockchains }`. `status` is `CAN_RECEIVE`, `NO_WALLET` (no wallet at all: offer W10) or `NO_WALLET_ON_CHAIN` (none on the chain asked for, or on no chain that still receives when none was asked); `blockchains` lists the chains they can be paid on now. Reach as in W4 (`chatId` stands in for it unless blocked); `users.not_reachable` otherwise, never saying why. Yourself is `wallet.self_transfer`; an unknown chain `wallet.chain_unsupported`.

### W10 Remind a contact

`POST /wallet/recipients/{userId}/remind` → `{ status }`: `SENT` (the contact gets `wallet/setup.requested` with a push), `ALREADY_SENT` (within 24 hours, `wal:remind`) or `HAS_WALLET` (they can be paid). Reach required.

### W11 Validate an address

`POST /wallet/addresses/validate` ← `{ blockchain, address }` → `{ blockchain, address, valid }`, `address` as it will be paid (EVM lowercase). `valid: false` is an answer, not an error; Circle down is `wallet.custody_unavailable`.

### W6 Transactions

```json
// GET /wallet/transactions?month=2026-10&limit=50   (month optional; cursor = the previous page's nextCursor)
// → data
{
  "transactions": [
    {
      "id": "0193…", "transferId": "0193…", "direction": "OUT", "state": "CONFIRMED", "blockchain": "BASE",
      "amount": { "asset": "USDC", "amountMinor": "20000000", "amount": "20" },
      "counterparty": { "userId": "0193…", "displayName": "Bob", "handle": "bob", "avatarUrl": "https://…",
                        "address": "0x5a3c…" },          // an outside address: { "address": "0x…" } only
      "chatId": "0193…", "origin": "APP", "txHash": "0x…",
      "occurredAt": "2026-10-25T09:02:11Z"
    }
  ],
  "nextCursor": "MjAyNi0xMC0yNVQwOTowMjoxMVp8MDE5My4uLg"
}
```

Only the caller's own rows (`user_id`), in `SUBMITTED`, `CONFIRMED` or `FAILED`: an unsigned or never-signed transfer moved no money and stays out. Newest first by `(occurred_at, id)`, paged by keyset over `transactions_user_idx`; the cursor is the last row's position, so it works with and without `month`. Without `month` the list runs back through every month (the wallet tab's recent list); with it, only `[first of month, first of next month)` in UTC. `limit` defaults to 50, at most 200 (more is read as 200). `transferId` is set on `OUT` rows (open it with W5) and null on `IN`; `origin` only on `OUT`, as in W5. `counterparty` names the other person from one batched `Profiles.get` per page (no name when their account is gone) and always carries the on-chain address when known. Its own rate-limit bucket: 60 per user per minute. Errors: `request.malformed` (`month`, `cursor`, `limit`).

### Agent routes (read-only, for Asha)

`GET /v1/agent/wallet` (status and balance) and `GET /v1/agent/wallet/transactions` (same shape as W6), so Asha can answer "How much did I send Mum yesterday?". No route under `/v1/agent/` can prepare a transfer or open a Circle session.

## Data

PostgreSQL tables in schema `wallet` (role `wallet`, Flyway location `db/wallet`), and Redis keys under `wal:`.

```sql
-- V1__wallet.sql (WS2: circle_users, wallets, outbox); V2__transfers.sql (WS3: transfers, transactions, realtime);
-- payment_holds arrives with WS4

CREATE TABLE wallet.circle_users (                      -- one per user: the wallet itself, whatever chains it is on
    user_id        uuid        PRIMARY KEY,
    circle_user_id uuid        NOT NULL UNIQUE,         -- random; what Circle knows us by, never our user id
    created_at     timestamptz NOT NULL
);

CREATE TABLE wallet.wallets (                           -- one per chain the user's wallet is on
    circle_wallet_id text        PRIMARY KEY,           -- webhooks carry Circle's wallet id
    user_id          uuid        NOT NULL,
    blockchain       text        NOT NULL,              -- Circle's name: BASE | MATIC | ETH | SOL (testnets: BASE-SEPOLIA …)
    account_type     text        NOT NULL,              -- SCA on EVM chains, EOA on Solana
    address          text        NOT NULL,              -- EVM: lowercase hex; Solana: base58 as given
    state            text        NOT NULL,              -- ACTIVE | FROZEN | CLOSED (SETTING_UP is derived: no row yet)
    created_at       timestamptz NOT NULL
);
CREATE UNIQUE INDEX wallets_user_chain_key ON wallet.wallets (user_id, blockchain);  -- a user's wallets; one per chain
CREATE UNIQUE INDEX wallets_address_key ON wallet.wallets (address, blockchain);    -- inbound webhooks and paying a contact

CREATE TABLE wallet.transfers (
    transfer_id         uuid        PRIMARY KEY,        -- Circle's refId
    sender_id           uuid        NOT NULL,
    sender_wallet_id    text        NOT NULL,
    blockchain          text        NOT NULL,           -- the chain the transfer is sent on (decision 2026-10-28)
    idempotency_key     uuid        NOT NULL,           -- derived from the W4 request's Idempotency-Key; Circle's too
    recipient_id        uuid,                           -- NULL for an outside address
    destination_address text        NOT NULL,
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    asset               text        NOT NULL,
    state               text        NOT NULL,           -- AWAITING_SIGNATURE | SUBMITTED | CONFIRMED | FAILED | EXPIRED
    circle_tx_id        text,
    tx_hash             text,
    chat_id             uuid,
    origin              text        NOT NULL,           -- APP | ASHA
    proposal_id         uuid,
    failure_reason      text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0  -- only for read-modify-write edits; states move by the transition guard
);
CREATE UNIQUE INDEX transfers_idempotency_key ON wallet.transfers (sender_id, idempotency_key);
CREATE INDEX transfers_open_idx ON wallet.transfers (state, created_at)
    WHERE state IN ('AWAITING_SIGNATURE', 'SUBMITTED');                      -- ExpireUnsignedTransfers, StuckTransfers

CREATE TABLE wallet.transactions (                      -- history and statements
    id                   uuid        PRIMARY KEY,       -- OrderedIds
    user_id              uuid        NOT NULL,
    direction            text        NOT NULL,          -- IN | OUT
    state                text        NOT NULL,
    amount_minor         bigint      NOT NULL,
    asset                text        NOT NULL,
    blockchain           text        NOT NULL,
    counterparty_id      uuid,
    counterparty_address text,
    transfer_id          uuid,                          -- set for outgoing transfers prepared here
    circle_tx_id         text,                          -- NULL until Circle reports an outgoing transfer
    tx_hash              text,
    chat_id              uuid,
    origin               text        NOT NULL DEFAULT 'APP',
    occurred_at          timestamptz NOT NULL
);
CREATE INDEX transactions_user_idx ON wallet.transactions (user_id, occurred_at DESC, id DESC);
                                                        -- W6 and W7: a month is a range of occurred_at, paged by keyset
CREATE UNIQUE INDEX transactions_circle_key ON wallet.transactions (circle_tx_id, user_id);
                                                        -- a webhook finds the rows it updates; a repeated inbound one inserts nothing
CREATE UNIQUE INDEX transactions_transfer_key ON wallet.transactions (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX transactions_occurred_idx ON wallet.transactions (occurred_at);   -- ReconcileWallets: the day's activity

CREATE TABLE wallet.payment_holds (
    user_id    uuid        PRIMARY KEY,
    reason     text        NOT NULL,                    -- RELINKED
    expires_at timestamptz NOT NULL                     -- the end of the hold
);

CREATE TABLE wallet.realtime (user_id, seq, frame, sent_at);  -- the same shape as users' realtime: daily partitions, 7 days
CREATE TABLE wallet.outbox (…);                         -- the same shape as identity's outbox: daily partitions, 7 days
```

| Table | Expiry | How the race is settled |
| --- | --- | --- |
| `circle_users` | — | W2 inserts with `insertIgnore` on the primary key and reads the winner's row in the same transaction, so two taps share one Circle user id |
| `wallets` | — | A wallet Circle reports is inserted with `insertIgnore` (primary key, `wallets_user_chain_key`, `wallets_address_key`) together with its `WalletCreated` outbox row; only the insert that lands writes and publishes the event, however many reads see the new wallet at once |
| `transfers` | — | W4 inserts with `insertIgnore` on `(sender_id, idempotency_key)`, before asking Circle; `insertedCount = 0` returns the transfer already prepared for that key. A state moves only by a **transition guard**: `UPDATE … SET state = :next WHERE transfer_id = ? AND state IN (<allowed predecessors of :next>) RETURNING …`; 0 rows means the transfer is already past that state, so a late or repeated webhook changes nothing |
| `transactions` | — (kept for the retention period) | An inbound webhook inserts the `IN` row with `insertIgnore` on `(circle_tx_id, user_id)`, so a repeated webhook adds nothing. `state` moves by the same transition guard as transfers (`WHERE id = ? AND state IN (…)`) |
| `payment_holds` | `expires_at`, purged by `wallet.purge-expired` | — |
| `realtime`, `outbox` | Daily partitions, 7 days | — |

Every read of `payment_holds` filters `expires_at > now()`.

A payment between two Circaso users writes two `transactions` rows: the sender's `OUT`, created when the transfer is prepared and updated from `transactions.outbound`, and the recipient's `IN`, created from `transactions.inbound` (the recipient is found through `wallets_address_key`). Payments from outside addresses only have the `IN` row. The transfer, the `OUT` row and the outbox row for an event are written in one transaction.

**Redis (`wal:` prefix)**

| Key | Value | Expiry | Purpose |
| --- | --- | --- | --- |
| `wal:balance:{circleWalletId}` | JSON list of the chain's holdings (symbol, token address, decimals, US dollar peg, amount in minor units as a string) | 15 s, cleared on any webhook for the wallet | W1 without calling Circle on every open; the deletion guard never reads it |
| `wal:adding:{userId}` | hash: field per chain being added (W12) | 1 h from the last add (a challenge's session lasts 60 min) | Only tells W1 and W12's list when to poll Circle's wallet list, so the new chain shows as soon as Circle has it; a field goes when its wallet is recorded. W12's add never relies on it: it reads Circle every time |
| `wal:sent:{userId}:{date}` | USDC minor units prepared today (UTC) | 2 days | Daily limit: W4 reserves a transfer's amount (`INCRBY`, given back at once if it passes the limit). A transfer's amount is given back **exactly once**: on `FAILED` from an open state (`AWAITING_SIGNATURE`, `SUBMITTED`: a refusal at prepare or Circle's update), or on `EXPIRED` by WS4's job; `FAILED` from `EXPIRED` gives nothing back. An `EXPIRED` → `SUBMITTED`/`CONFIRMED` move takes the amount again (WS4) |
| `wal:remind:{from}:{to}` | `1` | 24 h | W10 once a day per pair (`SET NX`) |
| `wal:seq:{userId}` | counter | 8 days, renewed on use | Next realtime seq for wallet frames |
| `wal:active` | hash: field per user id, value the epoch ms of their last W1 read or W3 session | the active window (30 min) from the last write; stale fields dropped by the job | Who `wallet.reconcile-active` checks at Circle; recording it never fails W1 or W3 |
| `wal:stuck:{transferId}` | `1` | 2 days | `wallet.stuck-transfers` reports a transfer stuck after an hour once (`SET NX`) |

**Scheduled jobs** (Architecture → Scheduled jobs)

| Job (scheduler name) | Every | Does |
| --- | --- | --- |
| `ExpireUnsignedTransfers` (`wallet.expire-unsigned`) | 1 min | `AWAITING_SIGNATURE` older than 15 min → `EXPIRED` through the transition guard, with its `OUT` row; the amount goes back to `wal:sent` and the sender gets `transfer.updated`. No event. Circle's later `SUBMITTED`/`CONFIRMED` still applies and takes the amount again |
| `StuckTransfers` (`wallet.stuck-transfers`) | 1 min (`WALLET_JOBS_STUCK_INTERVAL_SECONDS`; 5 min is enough in production) | `SUBMITTED` older than 30 s (`WALLET_JOBS_STUCK_AFTER_SECONDS`) and younger than 24 h: read the transaction from Circle (`StablecoinCustody.transaction`) and apply it through the webhook's own path; still stuck an hour after it was prepared → WARN once (`wal:stuck`). Telling the sender it's taking longer waits for a frame (Open questions) |
| `ReconcileWallets`, light pass (`wallet.reconcile-active`) | 2 min (`WALLET_JOBS_RECONCILE_INTERVAL_SECONDS`) | Users active in the last 30 min (a W1 read or W3 session in `wal:active`, or a `transactions` row on either side): list their Circle transactions of the last hour (`StablecoinCustody.transactions`) and apply each through the webhook's own path, so a lost inbound inserts the `IN` row and sends `payment.received` within minutes |
| `ReconcileWallets`, daily pass (`wallet.reconcile-daily`) | daily | For users with a `transactions` row that day, compare Circle's transaction list (last 26 h) with ours the same way and fill or correct missing rows (a lost webhook) |

Every job applies what Circle says through the one path the webhook takes (`CustodyUpdates`: `OutgoingUpdates` by `refId`, `IncomingUpdates` by wallet), so the transfer, its rows, its event, the frames, the push and the chat's payment card end the same whichever came first; the transition guard and the unique keys make the second one change nothing. Each run is bounded (`WALLET_JOBS_TRANSFER_BATCH`, `_ACTIVE_BATCH`, `_DAILY_BATCH`, `_TRANSACTIONS_PER_USER`); the first time Circle can't be reached a run stops and the job skips its next 1, 2, 4 … 16 runs.

## Events

**Consumed**

| Stream (group `wallet`) | Event | What wallet does |
| --- | --- | --- |
| `events:identity` | `identity.AccountRelinked` | Writes a 48-hour `payment_holds` row, if the hold is kept (Open questions) |
| `events:identity` | `identity.AccountStatusChanged` | Suspended or blocked: W4 refuses; wallets aren't frozen at Circle, which we can't do for user-controlled wallets |
| `events:identity` | `identity.AccountDeleted` | Deletion only happens once the guard has seen an empty wallet; marks the wallet `CLOSED` and keeps transaction records for the retention period (Open questions) |

**From Circle** (through `CustodyNotifications`, not a stream): an outgoing update finds its transfer by `refId` and moves it and its `OUT` row through the transition guard, writing `TransferConfirmed` or `TransferFailed` to the outbox in the same transaction; only the update that moves it publishes and sends `transfer.updated`. An update whose wallet isn't the transfer's sending wallet is not applied (a WARN names the transfer and the wallet). An outgoing transaction without a `refId` of ours is recorded as an `OUT` row only. An incoming update finds the receiving wallet by Circle's wallet id, inserts the recipient's `IN` row once per `(circle_tx_id, user_id)` (the sender is named when the source address is a Circaso wallet, `wallets_address_key`) and moves it by the guard; `payment.received` goes out once, when it is `CONFIRMED`. A wallet we haven't recorded yet is first looked up at Circle by its user (which records it and sends `wallet.ready`); a wallet that isn't ours is acknowledged with a WARN. Every update clears that wallet's cached balance. Updates are ordered by meaning (the guard), not by `updatedAt`.

**Published** (stream `events:wallet`)

| Event | `data` | Consumers |
| --- | --- | --- |
| `wallet.WalletCreated` v1 | `userId`, `blockchain` | gateway (frames). One per chain: the first when the wallet turns `ACTIVE`, then one for each chain added (W12) |
| `wallet.TransferConfirmed` v1 | `transferId`, `senderId`, `recipientId?`, `blockchain`, `amount { asset, amountMinor, amount }` (strings), `origin`, `proposalId?` | assistant (outcome of a proposal), auditor |
| `wallet.TransferFailed` v1 | `transferId`, `senderId`, `reason` (Circle's, `FAILED`, or the wallet code when Circle refused at prepare) | assistant, auditor. Exactly once for every transfer that ends `FAILED`, including a refusal at prepare |

**Realtime frames** (module `wallet`)

| Type | `data` | Goes to | Push if offline |
| --- | --- | --- | --- |
| `transfer.updated` | `transferId`, `state`, `blockchain`, `amount`, `txHash?`, `failureReason?` | The sender, each time a transfer moves | Yes on `CONFIRMED` and `FAILED`: "Payment sent" / "Payment failed" |
| `payment.received` | `transactionId`, `blockchain`, `amount`, `fromUserId?`, `fromName?` | The recipient, once the money is confirmed on chain | Yes: "Amina sent you money" ("You received money" from an outside address; no amount on the lock screen) |
| `wallet.ready` | `blockchain` | The user, when the wallet turns `ACTIVE` (its first chain only) | No |
| `setup.requested` | `fromUserId`, `fromName` | A contact someone wants to pay (W10) | Yes: "Amina wants to send you money. Set up your wallet" |

Frame classes are in `wallet:api` (`WalletFrames`), so the gateway words the pushes typed; amounts are `{ asset, amountMinor, amount }` strings.

Wallet implements `RealtimeReplay` with its `realtime` table, as users does.

## Error codes

| Code | Kind → status | When |
| --- | --- | --- |
| `wallet.not_found` | NOT_FOUND → 404 | The user has no wallet yet |
| `wallet.already_exists` | CONFLICT → 409 | W2 when the wallet is already active |
| `wallet.not_ready` | CONFLICT → 409 | The wallet is still being set up |
| `wallet.not_on_chain` | CONFLICT → 409 | W4 on a chain the wallet isn't on (or isn't active on): add it first (W12) |
| `wallet.account_restricted` | FORBIDDEN → 403 | W4 from an account that isn't `ACTIVE` (suspended, blocked, being deleted) |
| `wallet.on_hold` | FORBIDDEN → 403 | Outgoing payments are on hold after a support restore; `data.until` says when they resume |
| `wallet.amount_invalid` | INVALID → 400 | Zero, negative, more decimals than the asset has, `amountMinor` and `amount` that disagree, or an asset not offered on the chain |
| `wallet.insufficient_funds` | INVALID → 400 | Balance below the amount |
| `wallet.limit_exceeded` | INVALID → 400 | Over the per-payment or daily limit |
| `wallet.recipient_no_wallet` | CONFLICT → 409 | The contact hasn't set up a wallet |
| `wallet.recipient_not_on_chain` | CONFLICT → 409 | The contact has a wallet, but can't receive on the transfer's chain (not on it, or the chain is retired); also one of our wallets on a retired chain paid by address |
| `wallet.address_invalid` | INVALID → 400 | Not a valid address on the transfer's chain |
| `wallet.pin_locked` | INVALID → 400 | Circle locked PIN entry after wrong tries |
| `wallet.self_transfer` | INVALID → 400 | Paying your own wallet |
| `wallet.transfer_not_found` | NOT_FOUND → 404 | No such transfer, or not this user's |
| `wallet.chain_unsupported` | INVALID → 400 | A blockchain that isn't in the supported list (W4, W9, W11, W12), or W12 adding a chain retired from receiving |
| `wallet.funds_remaining` | CONFLICT → 409 | `DeletionGuard`: the US dollar stablecoins (USDC) the wallet holds, added up over every chain, come to 0.01 or more, or it holds any amount of another asset. Reaches the app as is (decision 2026-10-28) |
| `wallet.custody_unavailable` | UPSTREAM → 502 | Circle is down or the circuit is open; also the `DeletionGuard` when a wallet sits on a chain that is unconfigured (never done while funds are on it; its balance can't be read, so deletion is refused; a WARN names the chain and Circle's wallet id) |

## Not in v1

Chama treasuries (a group-owned wallet needs multi-party approval, which user-controlled wallets don't give us directly); payment requests and splitting a bill; recurring payments; cash in and cash out through M-PESA (`MobileMoney`); sending assets other than USDC.

## Exchange rates

Rates come from an `ExchangeRates` contract in `integrations` (`INTEGRATIONS_RATES_PROVIDER` = log | fake | open-er-api), the real one over `HttpClients.named("exchangerate")` calling `GET https://open.er-api.com/v6/latest/USD` once a day: the whole table is cached in Redis until the source's `time_next_update_utc`, so a balance read never waits on the source. When the source is down the last table is used while it is under 48 hours old, then the fiat part is left out. The wallet module only calls the contract.

As built (WS2): `ExchangeRates.usdRate(currency)` returns the rate or null, never an error. `OpenErApiRates` keeps the table under `fx:usd` (integrations' own Redis prefix) for 48 hours from its publication and refetches once `time_next_update_unix` has passed; after a failed fetch it doesn't ask the source again for 5 minutes (`fx:usd:failed`). Only US dollar stablecoins (`usdPegged` assets) get a fiat value, at one dollar each.

## Open questions

Decided 2026-10-28 (see decisions.md): a transfer names its chain; amounts are strings across the wallet; the deletion threshold is summed over every chain; a chain is retired with its receive switch, never removed while funded; W12's Circle key comes from user, chain and hour; `wallet.funds_remaining` is the deletion code the app sees; balances can be shown in the person's fiat currency (by country of origin, passed by the app) from an open exchange-rate source; the 48-hour payment hold after a support restore stays; a wallet can be on several chains (Base, Polygon, Ethereum and Solana at launch, USDC on each), starting on Base, the user adding others (W12).

- **Telling the sender a payment is taking longer** (`StuckTransfers`, after an hour): no frame or push for it exists yet; until one is decided the job only warns operators and the app keeps showing `SUBMITTED` (`TODO(decision: …)` in `CheckStuckTransfers`).

These also go into `decisions.md`.

- **Removing a chain.** Not offered in v1 (decision 2026-10-28). Whether a user can take a chain off the wallet (and what happens to anything still on it), or only hide it. (An operator retiring a chain is decided: the receive switch, above.)
- **Crypto price source.** Assets that aren't US dollar stablecoins have no price; their fiat value is left out and the total says `partial`. Which source prices them, if any.
- **Dust threshold for other assets.** The deletion guard ignores less than 0.01 of US dollar stablecoins summed over every chain, but refuses any amount of another asset. A threshold per asset?
- **Transactions in other assets.** `CustodyTransaction.amount` is `Money` (the `Asset` enum, USDC); a transaction in another offered asset isn't translated yet. Decide with the first non-USDC asset (WS3/WS4 record USDC only).
- **Chain.** Decided 2026-10-28: Base (default), Polygon, Ethereum and Solana. Still proposed: SCA accounts and Gas Station on the EVM chains; Solana EOA with Gas Station as fee payer; Arc (Circle's chain, USDC as gas) once it's on mainnet.
- **Authentication method.** Proposed: PIN with security questions, plus biometrics. Social login or email OTP instead?
- **Who pays gas.** Proposed: Circaso sponsors all gas in v1, with per-wallet daily caps in the Gas Station policy. A small fee on outside transfers later?
- **Limits and KYC.** Per-payment and daily limits for accounts that aren't KYC-verified, and higher ones for verified accounts (identity's `verification`). Linked to Kenya's Virtual Asset Service Providers Act, 2025 and whether Circaso needs a licence for user-controlled wallets: a legal question to settle before money moves in production.
- **Record retention.** How long transaction records are kept after an account is deleted (financial records usually have a statutory minimum; the Data Protection Act question already open).
