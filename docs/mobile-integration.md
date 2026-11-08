# Circaso mobile integration guide

For **Claude Code working in the Circaso mobile app** (Kotlin Multiplatform: one shared module, Android and iOS targets). It says how to connect the app to the backend, route by route, **what is finished on the backend, and what is not**. Updated 2026-11-07 against the backend at `github.com/Dev-Pasaka/circaso-backend` (dev server `https://sandbox.circaso.com`).

## 1. How to use this file

- **The API reference is the source of truth for exact shapes**: `/docs` on the dev server (behind Cloudflare Access; ask Pasaka for the `openapi.json` file if you cannot open it). This file explains behaviour and order; section 11 repeats every request and response type so you can start offline.
- **Build against the real server, not mocks.** `https://sandbox.circaso.com` is a deployed dev server with real vendors: Google and Apple sign-in, SMS (Twilio Verify), email (Resend), push (FCM), file storage (R2) and Asha's model (Claude only, no fallback: when it is down Asha is "down for maintenance", section 9), speech-to-text and voice (Cloudflare Workers AI). Only the Android carrier check and device attestation are faked there (marked **DEV STUB** below).
- **Status words used below:** **READY** (backend finished, safe to build on), **DEV STUB** (the HTTP contract is final but a vendor behind it is faked on dev), **PARTIAL** (works, with a named gap), **MISSING** (not built in the backend; do not invent an endpoint).
- If the backend says something different from this file, the backend wins: tell Pasaka and fix the file.

> **Changed on 2026-11-08, the fee is the network cost plus Circaso's rate (no shape changes; old apps keep working):**
> 1. **One fee, gas inclusive, worked out differently.** `fee = network cost (with a 20 % safety margin, up to the cent) + rate × min(amount, cap) (half up to the cent)`, raised to the rule's minimum fee when one is set. The old "gas floor" (network cost at most a quarter of the fee, so small payments paid about 9 cents on Base Sepolia) is gone: a 7.70 USDC payment between people now pays about 1 cent instead of 9.
> 2. **The network cost is what the chain's recent payments actually cost** (the 75th percentile of the last 50 confirmed transfers on the chain), or Circle's estimate while a chain has fewer than 5.
> 3. **`minimum: true` now means only that the rule's minimum fee raised it.** Show the rate otherwise. A 0 % rule now charges the network cost alone, so a "free" payment on an EVM chain still shows a small fee; Solana still takes none.
> 4. **The person pays the network cost inside the one fee**: copy that says Circaso pays the network fee is no longer right. "One Circaso fee, network cost included." stays right.

> **Changed on 2026-11-08, support tickets (new routes SU1–SU4, new frames and push; additive; old apps keep working):**
> 1. **Users can ask Circaso for help** from the app: SU1 opens a ticket, SU2 lists theirs, SU3 reads one with its replies, SU4 replies. See section 7 → Support tickets. The ticket screens are the app team's to build.
> 2. **New socket frames** `support` / `ticket.replied` and `support` / `ticket.updated` `{ ticketId, number, status }`, replayed 7 days like `users`; send `{ "module": "support", "seq": … }` in `last`.
> 3. **New push `kind: "support.ticket"`** with `ticketId`, title "Circaso Support", body "We replied to your ticket CS-000123." (never the reply's text). A tap opens the ticket.
> 4. **Asha can open a ticket** (new server tool `openSupportTicket`, label "Opening a support ticket…") only after she tried to help and the person agreed; she tells them its number. It appears in SU2 with `source: "ASHA"`.

> **Changed on 2026-11-07, the phone may stop Asha itself (new phone frame `interrupt`; additive; old apps keep working):**
> 1. **Client barge-in.** A phone that runs its own voice activity detector (Circaso runs Silero VAD on the echo-cancelled microphone) stops her audio the moment it is sure the person has started talking, goes to listening, and sends `{ "type": "interrupt", "at": "<ISO-8601 time of the onset>" }`. The server treats it exactly as a confirmed barge-in: the answer being generated and her speech are cancelled, the cut-off reply gets `reply_done` with `spoken: false`, and the person's following speech is the next `turn`. It answers with `interrupted` (also when she was already listening, so the phone knows the audio after it belongs to the next reply).
> 2. **The word rule stays** as the backstop for apps without a detector; with one, it should rarely fire first. `interrupt` frames are limited to 5 at once per conversation, then one more every 3 s; one over the limit is ignored (no answer, no error). `at` is only logged.

> **Changed on 2026-11-07, talking over Asha is quick again (no frame changes; old apps keep working):**
> 1. **One clear word interrupts her** (was two settled words and 400 ms). While her voice plays or is in its 1.5 s echo tail: one word that isn't a filler or backchannel, settled (a final, or an interim word the next interim keeps or that is already followed by another word), not her echo, with at least 300 ms of speech. While she is only thinking: one such word. See `interrupted` in the A11 frames.
> 2. **The phone should stream the microphone all the time**, her turn included, and duck her playback locally the moment it hears the person (restore it if no `interrupted` follows within about a second). A loudness gate on the phone keeps a normal voice from ever reaching the server once the echo canceller is on.

> **Changed on 2026-11-07, chat attachments up to 100 MiB (limit raised; no route changes; old apps keep working):**
> 1. **AT1 takes `sizeBytes` up to 100 MiB** of ciphertext (was 32 MiB), so a chat can share a video or document as it is, untrimmed (Pasaka, 2026-11-07: "trimming is for moments"). The server's value is `MESSAGING_MAX_ATTACHMENT_BYTES` (default 104857600); over it AT1 still answers `messaging.attachment_too_large` ("An attachment can be at most 100 MB."). **Group photos keep 2 MiB and moments (ST1) keep 32 MiB.**
> 2. **AT1's upload URL now works for an hour** (was 15 minutes; `expiresAt` says when). Storage checks it when the PUT starts, so retry a dropped upload with the same URL while it is valid, and ask AT1 again after. AT3's download link stays 15 minutes: ask AT3 again for each download, and resume a cut-off download with `Range: bytes=<have>-` on a fresh link.
> 3. **Still one PUT per file, no multipart.** Stream the body from a file with `Content-Length` (never hold 100 MiB in memory); the PUT goes straight to R2, not through our API host (whose 12 MB body limit doesn't apply).
> 4. **The app's file format above 32 MiB (client convention, the server never sees it).** A file whose ciphertext would be at most 32 MiB is sealed exactly as before (one AES-256-GCM box, `mediaKey` and `digest` in the message), so every app opens it. A larger one is sealed in **1 MiB chunks**: chunk *i* is AES-256-GCM (`nonce ‖ ciphertext ‖ tag`, 28 bytes over the chunk) under one fresh file key with associated data = *i* as 8 bytes big-endian ‖ 1 byte (1 on the last chunk, else 0), so chunks can't be reordered, dropped or cut short. Its message carries the key as **`chunkedKey`** and no `mediaKey` or `digest`: an older app reads it as incomplete and shows "a message this phone can't show" instead of downloading a file it can't hold in memory.

> **Changed on 2026-11-07, Asha down for maintenance (additive; old apps see a new error code):**
> 1. **No fallback model any more: Asha is Claude only.** A turn Claude can't answer is retried once on the server (300 ms for voice, 1 s for chat); if it still fails, the turn fails with the new code **`assistant.unavailable`** (502), as the envelope or as the stream's `error` event on A2 and A9. Show a calm "Asha is down for maintenance" state on her screens, not an error toast: disable the composer and the voice button, keep the question in the composer, show no code. Nothing was saved. `assistant.model_unavailable` remains for a refusal and for a failure after the first words.
> 2. **Live voice:** Asha *says* "I'm down for maintenance at the moment. I'll let you know as soon as I'm back." (normal `reply` frames and audio), then a `closing` frame with reason `assistant.unavailable` and close code 1013. Let the line play, then show the same maintenance state.
> 3. **New A14 `GET /v1/assistant/status`** → `{ "available": Boolean, "since": String? }` (ISO-8601; when it last changed, null if never down). Read it when Asha's screens open or come back to the foreground, and every 30 s or so while showing the maintenance state; clear the state when `available` is true.
> 4. **New socket frame `assistant` / `status`** `{ "available": true, "since" }`, not replayed (`seq` 0), to each person who met Asha down, once she is back. Clear the maintenance state on it.
> 5. **New push `kind: "asha.back"`**, title "Asha is back", body "Ask me anything." (no personal data, shown as is), to the same people when the app has no socket open. At most one per person per 6 hours. A tap opens Asha (your `asha*` routing already does).

> **Changed on 2026-11-07, Asha's money maths always comes from tools (nothing to change in the app):**
> 1. **Asha never guesses a price or does money maths herself**, in chat or in voice (prompt `asha-2026-11-07-v1`). Three new server tools, offered with or without an agent token; the app sees only their `tool_status` labels:
>    - `getExchangeRate` ("Checking the USD to KES rate…"): the fiat rate between any two currencies the rates source has (ISO codes or names: "shillings" is KES, "dollars" USD, "euro" EUR), with the inverse, its as-of time and source (**ExchangeRate-API**, the same open.er-api.com table the wallet's `fiat` uses).
>    - `convertAmount` ("Converting BTC to KES…"): any amount between any two currencies or coins (currency↔currency at those rates, coin↔currency at **CoinGecko**'s dollar price × the dollar rate, coin↔coin through dollars), exact decimal maths, currencies to 2 decimals and coins to their own decimals, with every rate and price used. A leg with no rate or price is named and no number is given; she then says she couldn't get it right now.
>    - `calculate` ("Working it out…"): + − × ÷, brackets, decimals, thousands commas and percentages ("15% of 2,400", "2,400 + 15%"), worked out exactly on the server; anything else is refused.
> 2. What the person's own coins and balances are worth still comes from `getWallet` and `getAssetValues`, so her numbers match the wallet and the money cards. In voice she says numbers naturally ("about two thousand three hundred shillings"), rounded from the tool's result. Keep the "Rates by Exchange Rate API" and "Prices by CoinGecko" credits where her answers are shown.

> **Changed on 2026-11-07, pay any coin the wallet holds (additive; old apps keep working):**
> 1. **W17 and W4 accept any coin the wallet holds on the payment's chain**, not only USDC: `amount: { asset: "EURC", amountMinor: "500000000" }` with `asset` the symbol W1 lists for it (`EURC`, `USDT`, `ETH`, `POL`, `SOL`, `AVAX`, `WETH`, `WBTC`, `CBBTC`…) and `amountMinor` in **that coin's own decimals** (W1's `decimals`: 18 for ETH, 9 for SOL); or `amount` in whole coins. `amountMinor` may be up to 78 digits (an 18-decimal coin passes a 64-bit integer at about 9 coins). The same W17 → W4 → Circle SDK flow, from a person's wallet or a group wallet's owner, to a Circaso user, a group (a contribution) or an outside address. A token goes by its contract on the chain; the chain's own coin (ETH, POL, SOL) by Circle's token id; on EVM chains the fee leg rides in the same `executeBatch` (a token's `transfer`, or the coin itself as the call's value).
> 2. **One fee, gas included, still priced in US dollars.** For a coin that isn't a dollar stablecoin the fee is **charged in the coin being sent**: the dollar fee converted at the price feed's price (CoinGecko, the same `usdPrice` W1 shows) and **rounded up** to the coin's minor unit. `fee`, `total`, `balance` and `maxAmount` are amounts of the coin. The W17 quote holds the converted fee **and the price**: W4 with its `quoteId` charges exactly that fee, at that price, for 15 minutes, whatever the price does meanwhile. Without a held quote, a price fall that raises the fee in the coin is `wallet.fee_changed` as usual.
> 3. **Limits count dollars.** One payment's cap and the rolling 24 hours count each payment at its price (fee included). W17's `limit` amounts are in the coin for USDC and USDT (unchanged) and in **`USD`** (`asset: "USD"`, 6 decimals) for any other coin; `wallet.limit_exceeded`'s `limitMinor`, `usedMinor` and `leftMinor` are micro-dollars (the same digits as before for USDC), while `maxAmountMinor` stays in the payment's coin. W17 gains **`usdPrice`** (the price used, `"1"` for USDC/USDT) and **`totalUsd`** (what the payment counts against the limits).
> 4. **New refusals** (W17 and W4; nothing is prepared):
>    - **`wallet.asset_not_held`** (400, "Your wallet doesn't hold that coin on this blockchain."): the coin isn't held above zero on that chain. (A coin offered on the chain, like USDC, at zero is still `wallet.insufficient_funds`.)
>    - **`wallet.asset_unpriced`** (409, "That coin can't be sent right now because we can't get its price. Try again in a little while."): the price feed has no price for it right now, or it is a token we can't vouch for (one that only calls itself `EURC` or `ETH`). Show the message with Try Again; the same coin with `usdPrice: null` on W1 can't be paid.
>    - **`wallet.asset_not_supported`** (400, "That coin can't be sent from Circaso on this blockchain yet."): the custody provider can't send it on that chain.
>    `wallet.amount_invalid` now means only an amount that can't be read (or isn't above zero) in that coin.
> 5. **Nothing changes for USDC**: the same fee, the same paths, the same numbers. W5, W6 and `transfer.updated` / `payment.received` carry the coin's own symbol and minor units (they always had `asset`). Money in any coin we trust (an offered asset, a chain's own coin, a known token) now reaches the history; a token that only calls itself a known coin does not.
> 6. **Into a group wallet**, any coin is a contribution too: the group's wallet receives that coin, and its goal, `contributed` and `held` count it **in dollars at the price it was paid at** (as USDC minor units, the unit they always used). The contribution's `chama` frames still name it in those dollars (`asset: "USDC"`); naming the coin paid there is open.

> **Changed on 2026-11-06, coin icons by symbol (additive; old apps keep working):**
> 1. **IC1 `GET /v1/assets/icons/{symbol}`** (section 7, Wallet) answers a coin's mark as `image/svg+xml`: the **Web3 Icons mono set** (MIT, `@web3icons/core` 4.0.58, 1,542 tokens), cleaned to one colour in a 24 × 24 box, for the app to paint in its theme (decision 2026-11-06: no brand-coloured logos). **No token needed**; any case (`usdc`); `wallet.icon_symbol_invalid` (400) for a symbol that isn't 1–15 letters, digits or dots, `wallet.icon_not_found` (404) for one the set doesn't have. Cached a week (`public, max-age=604800, immutable`) with an `ETag` (`If-None-Match` → 304). Its own rate limit: 600 per IP per minute.
> 2. The app bundles its common coins and its chains' network marks (generated by `CircasoApp/scripts/coin-icons/generate.py`, which also vendors this route's SVGs) and maps wrapped and renamed coins to the original first (`WETH` → `ETH`, `MATIC` → `POL`, `cbBTC` → `BTC`). Credit "Web3 Icons" with its MIT notice (the app's You → Acknowledgements).

> **Changed on 2026-11-06, the group wallet's owner spends directly (behaviour change; old apps keep working):**
> 1. **W4 from a group wallet no longer needs `payoutRequestId`.** The wallet's **signer, the group's owner**, sends straight from it with no request and no approvals: to a member, to **themselves** (`to: { userId: <their own id> }`, the group's money into their personal wallet), to anyone they can reach, to another group's wallet, or **to an outside address** (`to: { address }`, checked on its network exactly like a personal send to an address; `wallet.address_invalid` for a bad one). The same one fee (gas included), the same daily cap (groups 10,000 USDC in 24 hours) and the same W17 quote apply. A `payoutRequestId` that is sent is still held to its request (`wallet.payout_not_approved`, `wallet.payout_mismatch`, `wallet.payout_in_progress`), so an app that still pays approved requests keeps working.
> 2. **Only the owner moves the group's money.** Anyone else (admins included) gets **`wallet.not_signer`** (403, "Only the wallet's owner can send from it or change it."); someone outside the group `wallet.not_found`. Members only contribute (W4 `to: { groupId }`). Hide every spend action for anyone but the signer (`canSign` on W1/W16, `isSigner` on CH7).
> 3. **Payout requests and approvals are parked**, not deleted: `WALLET_GROUP_PAYOUT_APPROVALS=true` on the server brings option A back (W4 then answers `wallet.payout_required` again). CH4, CH5 and CH9 still answer, but the app should not offer Request Payout while it is off.
> 4. **Every payment out of the group wallet is told to every member**: `chama` / `money.updated` with `kind: "PAYOUT"` and no `requestId` once it confirms, and CH1/CH7's `paidOut` (and `held`) count it. The money card in the group chat is still the app's own end-to-end message: send it into the **group's** chat after W4, naming where the money went (see "Group wallets").

> **Changed on 2026-11-06, coin prices and Asha's crypto answers (additive; old apps keep working):**
> 1. **W1 `AssetDto` and `AssetTotalDto` gain `usdPrice`** (string or null, with or without `?currency=`): one whole unit in US dollars. `"1"` for a dollar stablecoin (USDC, USDT), CoinGecko's price for another coin we trust (ETH, POL, SOL, AVAX, BTC, WETH, WBTC, cbBTC, EURC; testnet coins at their mainnet coin's price), null when it has no price (the feed off or down, or a token we can't vouch for: a token that only calls itself `ETH` is never priced). Prices are kept about a minute. Use it to show a coin in the person's money (`usdPrice × fiat.rate`) and to turn an amount typed in their money into the coin; **never** to work out a payment or a fee, which stay in coin units. A group wallet's W1 (`?walletId=`) carries it the same way.
> 2. **`fiatAmount` and `fiat.amount` now include priced coins**, not only stablecoins, so `fiat.partial` is `false` once everything held has a price. Credit "Prices by CoinGecko" next to a coin's fiat value (the feed's terms), as well as "Rates by Exchange Rate API".
> 3. **Asha answers every crypto question from the feed** (prompt `asha-2026-11-06-v1`): new server tools `getCryptoMarket` ("Checking “ETH” on the market…": any coin's price, change, market cap, volume, all-time high, supply and description) and `getTrendingCrypto` ("Checking what's trending in crypto…"). She never answers a coin's price from memory; if the feed can't answer she says so. `getAssetValues` now also values the person's other coins at the same prices. Nothing to change in the app.

> **Changed on 2026-11-05, group photos are kept (additive; old apps keep working):**
> 1. **M8 `PATCH /v1/chats/{chatId}` takes `avatarMediaId`**: the attachment id of the group photo you just uploaded to **this group** (AT1 with `kind: "image"` → PUT → AT2). Send it in the same `PATCH` as the new `sealedMeta` that carries the photo's key, digest and thumbnail: `{ "version": 7, "sealedMeta": "…", "avatarMediaId": "0193…" }`. `"avatarMediaId": null` clears the photo; leaving the field out keeps it. Admins only, like the name.
> 2. **The group photo is never purged while it is the photo** (other attachments still go after 30 days). The photo it replaces, or one you clear, is deleted **24 hours** later, so a member fetching it right then still gets it; deleting the group (the last member leaves) deletes it at the next hourly purge. Fetch it with AT3 as before: current members only.
> 3. **The photo must be** a completed (AT2), unexpired `image` attachment of this group, at most **2 MB** of ciphertext; anything else answers **`messaging.avatar_invalid`** (400) and nothing changes. A bad id is `request.malformed`.
> 4. **ChatDto gains `avatarMediaId`** (string or null) on every chat response (M4–M11, `GET /v1/chats`), and **`chat.updated` gains `avatarMediaId`**, so a phone that missed the change, or joins later, fetches the current photo without waiting for a frame. M5 doesn't take it: upload after the create (AT1 needs the group), then `PATCH`.

> **Changed on 2026-11-06, pushes carry ids for the tap and the phone's own wording (old apps keep working, see 3):**
> 1. **Every push now names what it is about** in its data (FCM `data`, APNs top-level keys), ids only, never an amount or chat text: `kind` plus, where they apply, `chatId`, `messageId`, `callId`, `transferId`, `transactionId`, `walletId`, `requestId`, `userId`. Kinds: `payment.received` (`transferId`?, `transactionId`, `chatId`?, `walletId`?), `transfer.updated` ("Payment sent"/"Payment failed"; `transferId`, `walletId`?), `chama.contribution` (`chatId`, `userId`), `chama.request.pending|approved|payable|declined|paid` (`chatId`, `requestId`; `payable` is the group wallet's signer hearing an approved payout), `call.ringing`, `call.missed`, `call.ended`, `moment.loved`, `contact.joined` and `connection.added` (`userId`), `wallet.setup`, `session.revoked`. A message push still has `chatId` and `messageId` and no `kind`; being added to a group has `chatId` alone.
> 2. **Now data-only**, so the phone words them (in the person's currency, naming the sealed group name, honouring the app's Notification privacy): `chama.contribution`, every `chama.request.*`, and `call.missed`. Their `title`/`body` stay in the data and as the iOS alert's fallback.
> 3. An app from before this change shows a data-only `chama.*` push as "You were added to a group" (it has `chatId` and no `messageId`) and doesn't show `call.missed` at all; update the app with the server.
> 4. **Taps:** money opens the chat at the payment's card (W5 details when there's no chat), a message its chat at the message, `chama.*` the group chat, a missed call the chat at its call row, `moment.loved` my moments, `contact.joined` contacts, `wallet.setup` the wallet, `transfer.updated` the payment.

> **Changed on 2026-11-05, Asha: usage menu, character, tools and web search (additive; old apps keep working):**
> 1. **A7 `GET /v1/assistant/usage` gains the usage menu's shape** next to its old flat fields (which are unchanged): `periodStart`, `periodEnd` (the UTC day; `periodEnd` equals `resetsAt`) and three meters, `messages`, `voiceSeconds` and `searches` (Asha's web searches), each `{ "used": Int, "limit": Int? }`. `limit` is null when there is no limit (the testing switch `ASSISTANT_LIMITS_UNLIMITED`). The plan is `plan` (`FREE` or `PLUS`). There is no separate credits endpoint: this is it. Example: `{ "plan": "FREE", "textTurnsUsed": 3, "textTurnsLeft": 17, "voiceSecondsUsed": 40, "voiceSecondsLeft": 80, "resetsAt": "2026-11-06T00:00:00Z", "periodStart": "2026-11-05T00:00:00Z", "periodEnd": "2026-11-06T00:00:00Z", "messages": { "used": 3, "limit": 20 }, "voiceSeconds": { "used": 40, "limit": 120 }, "searches": { "used": 1, "limit": 10 } }`.
> 2. **Asha's character:** a happy, warm assistant who is glad to help, upbeat but concise, never flippant about money (prompt `asha-2026-11-05-v1`, text and voice). Nothing to change in the app.
> 3. **The mascot is no longer asked of the model.** The `mascot` SSE event and socket frame, and the mascot fields on `tool_status` and `tool_done`, still arrive with the same shape, now with neutral values (`scene: "office"`, `mood: "calm"` or `"caring"`, `activity` from the work, `reaction: null`). Apps that removed the mascot can ignore them; nothing was taken away.
> 4. **New server tools** (the app sees only their `tool_status` labels): `getAssetValues` ("Checking today's rate…": what USDC and USDT are worth, from the wallet's own rate, the one balances, payments and fees use; never a market feed), `getFeeQuote` ("Working out the fee…": W17's fee, total and limits for a payment the user is thinking of, nothing held), `listWallets` ("Looking at your wallets…": own and group wallets; `getWallet` now opens a group's by `walletId`) and `getMyMoments` ("Looking at your moments…": own live moments' views and reactions, never their content). `searchWeb` now runs before Asha states an uncertain or time-sensitive fact, at most 2 per answer and a daily number per plan (free 10, Plus 60), counted in `searches` above; results may carry a published date.
> 5. **New agent routes** (agent token only; the app never calls them): `GET /v1/agent/wallet/values`, `GET /v1/agent/wallet/fee`, `GET /v1/agent/wallet/wallets`, `GET /v1/agent/status/moments`, and `walletId` on `GET /v1/agent/wallet`.

> **Changed on 2026-11-05, money received names its payment (additive; old apps keep working):**
> 1. **`wallet` / `payment.received` gains `transferId`? and `chatId`?** (section 8): the Circaso payment that paid you and the chat it was sent from. Build the chat's **Received** card from them when the end-to-end payment card can't be opened (or hasn't arrived), deduped with it by `transferId`; W5 `GET /v1/wallet/transfers/{transferId}` serves you (`direction: IN`) with the amount, state and `from`.
> 2. **W6 `IN` rows carry `transferId` and `chatId`** the same way (section 7, Wallet). `OUT` rows are unchanged.
> 3. **Both are null when the server can't be certain**: money from an outside address, or a sender with two payments of the same amount to you on that chain in the last 24 hours and no transaction hash to tell them apart. The server links by the on-chain transaction hash, else only when exactly one of the sender's payments fits; it never guesses.

> **Changed on 2026-11-04, fees and limits (Spaces phase 2F; read "Fees and limits" in section 7):**
> 1. **Every payment between Circaso wallets or to an outside address can carry one Circaso fee**, network cost included, paid by the sender on top (the payee gets exactly the amount). Seeded rates: 0.5 % between people, 1 % to or from a group or a space, 2 % to an outside address, 0 % on Solana; at most the first $10,000 counts, and a small payment pays a minimum that keeps the network cost under a quarter of the fee (replaced on 2026-11-08: the network cost plus the rate, see above). Rules live in the database and change without a release.
> 2. **New route W17 `POST /v1/wallet/transfers/quote`** (W4's body): the fee for exactly this payment, the total, the balance, the most that fits (`maxAmount`), the wallet's limit and a `problem` (`INSUFFICIENT_FUNDS`, `PER_PAYMENT_LIMIT`, `DAILY_LIMIT` with `fitsAt`). For the person who signs, a `quoteId` held 15 minutes.
> 3. **W4 takes `quoteId`** and charges exactly that fee while it holds. **Breaking for an app that doesn't send it:** a W4 whose fee is above zero without a valid quote (none, run out, or another payment's) answers **`wallet.fee_changed`** (409) with a new `quoteId` in `details.fields`; nothing is prepared. W4's `fee` gains `rateBps`, `minimum`, `ruleId`, `ruleVersion`, and the answer gains `total`. W5's `fee` is the transfer's fee.
> 4. **On EVM chains a payment with a fee is one contract-execution challenge** (`executeBatch` on your own wallet, two USDC transfers: the payee and Circaso's fee wallet). Sign it in the Circle SDK exactly like a transfer challenge.
> 5. **Limits are rules now**, over a **rolling 24 hours**, fees included: people 500 USDC a payment and 1,000 in 24 hours (placeholders, as before), **groups 10,000 USDC in 24 hours** (payouts to members count), spaces none. `wallet.limit_exceeded` and `wallet.insufficient_funds` carry their numbers in `details.fields` (`{ field: <name>, problem: <value> }` pairs; see "Fees and limits").

> **Changed on 2026-11-04, Asha's mascot hints (additive; old apps keep working):**
> 1. **New SSE event `mascot`** on A2 and A9, and **new frame `type: "mascot"`** on the live voice socket (A11), with `{ "scene", "mood", "activity", "reaction", "sensitive" }`. One comes on every turn before its first `message` (or `reply`) text; another when the hints change (a chart or statement being written, the model's tag arriving later in the turn); and a last one with `activity: "done"` just before `done` on SSE, or just after `reply_done` on the socket (so she finishes when her voice does). Values only from fixed lowercase lists (section 9, Asha's mascot); never a number, an amount or a name.
> 2. **`tool_status` and `tool_done` carry the same five fields** (on SSE and the socket): `activity` is the tool's work on `tool_status` (`searching_web`, `reading_transactions`, else `thinking`) and `thinking` on `tool_done`; `reaction` is always null there.
> 3. The scene changes only on a sustained topic change (the server keeps it per conversation, with hysteresis); `sensitive: true` turns are never `sassy` (the server sends `caring`), and neither is a support conversation. The app still applies the user's sass setting.
> 4. Asha's prompt is now `asha-2026-11-04-v1`.

> **Changed on 2026-11-08, Moments key request answered with the app closed (additive; old apps keep working):**
> 1. **`status.key_requested` is pushed silently** to a poster without a socket: `kind` `moment.keys_requested`, `userId` the requester, no title, no body, nothing to show. Android gets a data message; iOS a background push (`content-available`, push type `background`), which iOS may hold or drop, so answer it on the next open too (replay still brings the frame). On it, do what the frame says: seal fresh copies and send ST10.
> 2. **ST10 takes `answered`** (optional, up to 100 user ids): the people whose request this answers, copies or not, and `copies` may be empty when `answered` isn't. Name the requester there always. Someone answered with no copy (your moments to them were posted from an earlier phone, which this one can't seal again) still gets `status.keys_ready`, so their phone stops waiting: whatever of yours it still can't open after reading ST4 never will open there, and it should say so rather than "waiting".

> **Changed on 2026-11-04, Moments key request (additive; old apps keep working):**
> 1. **A new phone can ask for the keys to moments it can't open** (section 7, Moments): `POST /v1/moments/keys/request` `{ "posterIds" }` sends each poster `status.key_requested` (at most once every 10 minutes per poster); the poster's phone answers with `POST /v1/moments/key-copies` `{ "copies": [{ "momentId", "userId", "ciphertext" }] }`, each the moment's body sealed again over the 1:1 session, which replaces that person's `keyCopy` in ST4, and the person gets `status.keys_ready`.
> 2. **`keyCopy` in ST4 can now carry the moment's body itself** (a sealed 1:1 message whose plaintext is a `momentBody` payload, see Moments) as well as a sender-key copy. Open it over the 1:1 session and look at the payload's type.
> 3. **New frames:** `status` / `status.key_requested` `{ requesterId }` (to a poster) and `status.keys_ready` `{ posterId }` (to a viewer). Neither is pushed; replay brings them.
> 4. **New error code:** `status.keys_invalid` (400).

> **Changed on 2026-11-03, Moments (new routes and frames):**
> 1. **Moments are in** (section 7, Moments): WhatsApp-style statuses that live 24 hours, end to end encrypted on the phone. ST1 `POST /v1/moments/media`, ST2 `POST /v1/moments/media/{mediaId}/complete`, ST3 `POST /v1/moments`, ST4 `GET /v1/moments`, ST5 `POST /v1/moments/{momentId}/view`, ST6 `GET /v1/moments/{momentId}/views`, ST7 `DELETE /v1/moments/{momentId}`, ST8 `GET /v1/moments/media/{mediaId}`. The server stores ciphertext, the audience, views and expiry; the phone chooses the audience from its own contacts and the server drops, without saying so, anyone who can't reach the poster or whom the poster can't reach.
> 2. **New frames** (section 8): `status` / `status.posted`, `status.deleted`, `status.viewed`. Send `{"module":"status","seq":…}` in `last` like the others. One push (2026-11-03): **a reaction on your moment** pushes "Name reacted 😂 to your moment" (`kind` `moment.loved`) when you're offline, whenever someone adds or changes their emoji; plain views and cleared reactions never push. A reply to a moment is an ordinary M12 chat message quoting it, and pushes as one.
> 3. **New error codes:** `status.audience_invalid` (400), `status.moment_too_large` (400), `status.media_invalid` (400), `status.media_not_found` (404), `status.moment_not_found` (404), `status.not_poster` (403), `status.too_many_moments` (429, with `Retry-After`), `status.reaction_invalid` (400), `status.keys_invalid` (400, 2026-11-04).
> 4. **Retention:** a sweep every 10 minutes hard-deletes moments past `expiresAt` with their views and files; a file not attached to a moment within an hour of ST1 is deleted. Deleting the account removes its moments at once.
> 5. **A reaction is any emoji, not just a heart** (additive, later the same day): ST5 takes `reaction` (one emoji: set it; `"reaction": null` clears it; leave it out to keep it). `reacted: true` alone still means `"❤️"` and `false` clears, so an app that only sends `reacted` keeps working, and `reacted` in every answer is `reaction != null`. ST4's `Moment` gains `reaction` (your own, on someone else's moment) and `reactionCount` (on yours, next to `viewCount`); ST6's views and the `status.viewed` frame gain `reaction`. Hearts given before are `"❤️"`.

> **Changed on 2026-10-31, Asha conversations (additive; old apps keep working):**
> 1. **Separate conversations with Asha**, like a chat list (section 7, Asha chat; section 9): C1 `GET /v1/assistant/conversations`, C2 `POST /v1/assistant/conversations`, C3 `PATCH /v1/assistant/conversations/{conversationId}`, C4 `DELETE /v1/assistant/conversations/{conversationId}`, C5 `GET /v1/assistant/conversations/{conversationId}/messages`. **Asha now reads only the conversation she is asked in**, so a new conversation starts with a clean context.
> 2. **A2 takes `conversationId`** (optional). Without it the message goes to the user's most recent conversation, or a new one if they have none, so an app that doesn't send it keeps one thread. The `done` event gains `conversationId` and `conversation` (the conversation as saved, with its title); `tool_request` gains `conversationId`.
> 3. **Live voice:** the `auth` frame takes `conversationId` (optional, first frame only), with the same fallback; every turn of the call is saved in that one conversation.
> 4. **Every `ChatMessageDto` carries `conversationId`** (A1, C5, `done`, `reply_done`). A1 still returns every message of every conversation; A5 deletes every conversation too.
> 5. **Titles:** a conversation is `"New chat"` until its first exchange, then titled from the first question (at most six words, greetings dropped), unless the user named it (C2's `title`, or C3). Existing history became one conversation, titled from its first question (`"Earlier chat"` if Asha spoke first).
> 6. **New error code** `assistant.conversation_not_found` (404) on C3–C5, on A2 with a `conversationId` that isn't the user's, and as a live voice `error` frame.
> 7. **No realtime frames** for conversations: the app pulls C1 (on opening the Asha list, and after a `done`, which already carries the conversation).
>
> **Changed on 2026-10-31, Asha's voices (additive):**
> 1. **The user can choose Asha's voice.** A12 `GET /v1/assistant/voices` → `VoicesResponse { voices: [VoiceDto { id, name, character, gender, default }] }`: six voices, `default: true` on one (Luna, the voice so far). Keep the user's choice on the phone and send its `id` as **`voice`** on A4 (`SpeechRequest.voice`) and on A11's first `auth` frame. Missing or unknown is the default voice, never an error, so an app that sends nothing keeps working. A11's `ready` frame now says which `voice` the conversation uses.
> 2. **Preview before choosing:** A13 `GET /v1/assistant/voices/{voiceId}/sample` → MP3 bytes of a fixed line in that voice. Free (not counted against the voice allowance). Recipe in section 9 (Voice picker).
>
> **Changed on 2026-10-31, Circaso support accounts (additive):**
> 1. **Profiles say who is Circaso support.** `PublicProfileDto` (U9, U10, contacts, connections, blocks, sync matches) and `ChatProfileDto` (chat members) gain **`support: boolean`**, `true` only on Circaso's own support account and `false` (or absent, from an older server) for everyone else. Show "Verified Circaso support" from this flag instead of comparing the user id with a pinned one; a person can't set it, and only a support account can hold a reserved handle such as `@circaso`.
> 2. **`@circaso` can be looked up.** U9 `GET /v1/users/by-handle/circaso` and a U11 handle connect now accept reserved names, which only support accounts hold, so the app can find the support account by its handle.
> 3. **New error codes.** `identity.support_account_not_on_mobile` (403) on I2, I3, I4, I5, I6 and I7: a support account can't use the app (the support team uses its own dashboard). `wallet.support_account_has_no_wallet` (403) on W9 (support can't be paid; an error, not a new `status`), W4 (paying support), W10 (reminding support), and W2, W12, W13 for a support account. Show the message as given; the app never needs to sign in as support.
> 4. **Not yet:** the dedicated support account isn't live until the support service that answers its chats exists (a later phase). Until then keep `Support.UserId` (it points at Pasaka's own account, a person's account, so its profile says `support: false`). See section 10.
>
> **Changed on 2026-10-31, payment history (new route):**
> 1. **W6 `GET /v1/wallet/transactions` is in** (section 7, Wallet): your payments, newest first, as `TransactionsResponse { transactions: [TransactionDto], nextCursor }`. Without `month` it pages back through every month (the wallet tab's "Recent"); with `month=YYYY-MM` only that UTC month. Each row carries `counterparty { userId?, displayName?, handle?, avatarUrl?, address? }` so a row needs no further call. Its own rate limit: 60 per user per minute.
>
> **Changed on 2026-10-30, encrypted chat attachments (new routes):**
> 1. **Attachments are in** (section 7, Attachments): AT1 `POST /v1/chats/{chatId}/attachments`, AT2 `POST /v1/attachments/{attachmentId}/complete`, AT3 `GET /v1/attachments/{attachmentId}`. Signal-style: encrypt each file on the phone with a fresh AES-256-GCM key, PUT the ciphertext to the pre-signed URL, complete it, then send an ordinary M12 message whose ciphertext carries the `attachmentId`, key, digest and real type. Up to 32 MiB; kept 30 days. No new frames.
> 2. This replaces the M15–M17 media design (500 MB multipart, `/v1/media/…`), which will not be built.

> **Changed on 2026-10-29, chamas (new routes and frames):**
> 1. **A group's shared goal is in** (section 7, Chama): CH1–CH6 under `/v1/chama`, with `chama` / `goal.updated`, `contribution.added`, `request.updated` frames (section 8). Send `{"module":"chama","seq":…}` in `last`. The money sits in the treasurer's wallet: contributions are W4 payments to the treasurer with `chatId`.
> 2. **`wallet` / `transfer.updated` is unchanged**; `TransferConfirmed` (an event, not a frame) now carries `chatId`.

> **Changed on 2026-10-28, wallet payments (new routes and frames; W12 and W1 gain a field):**
> 1. **Payments are in** (section 7, Wallet): W4 `POST /v1/wallet/transfers`, W5 `GET /v1/wallet/transfers/{transferId}`, W9 `GET /v1/wallet/recipients/{userId}`, W10 `POST /v1/wallet/recipients/{userId}/remind`, W11 `POST /v1/wallet/addresses/validate`. **A payment names its chain** (`blockchain`) and leaves from your wallet's address on that chain; a person must have a wallet on that chain (W9 says `NO_WALLET_ON_CHAIN` otherwise), an outside address must be valid on it. Amounts are **strings** everywhere in the wallet: send `amount: { asset: "USDC", amountMinor: "20000000" }` or `{ asset, amount: "20" }`.
> 2. **Wallet frames and pushes** (section 8): `wallet` / `transfer.updated`, `payment.received`, `wallet.ready`, `setup.requested`. Send `{"module":"wallet","seq":…}` in `last` like the others.
> 3. **Retired chains:** W1's chains and W12's list carry `receiveEnabled`. `false` means the chain is retired from receiving: don't show its address to be paid; it can still send out. W12 no longer offers a retired chain to add.
> 4. **Deleting the account** is now refused when your US dollar stablecoins **added up over every chain** come to 0.01 or more (0.009 USDC on each of four chains is refused).
>
> **Changed on 2026-10-28, wallet (new routes; one error code renamed):**
> 1. **The wallet's first routes are in** (section 7, Wallet): W1 `GET /v1/wallet`, W2 `POST /v1/wallet`, W3 `POST /v1/wallet/session`, and W12 `GET`/`POST /v1/wallet/chains`. A wallet can be on **several chains** (Base, Polygon, Ethereum and Solana, USDC on each; the list is server configuration): it starts on Base, and the user adds others from wallet management. Each chain has its own address. Amounts are **strings** (`amountMinor` in the smallest unit, `amount` in whole units), because some tokens pass what a JSON number holds.
> 2. **Fiat balance:** `GET /v1/wallet?currency=KES` (the person's country-of-origin currency, any ISO 4217 code) adds `fiat { currency, amount, rate, asOf, partial }` at USDC = 1 USD. `fiat` is null when no rate is known (never an error); a code that isn't ISO 4217 answers `request.malformed`. Show "Rates by Exchange Rate API" wherever the fiat value appears (the source's terms).
> 3. **Deleting the account** (I14) now answers **`wallet.funds_remaining`** (409) while the wallet holds 0.01 or more of a US dollar stablecoin (USDC), or any amount of any other asset, on any chain, and `wallet.custody_unavailable` (502) when the wallet can't check (Circle down, or a wallet on a chain no longer supported); **`identity.wallet_not_empty` is gone** (it was never sent).
>
> **Changed on 2026-10-28, later (update the app):** `PUT /v1/keys` now **requires** `kyberPreKey { keyId, publicKey, signature }` (a signed ML-KEM 1024 prekey, about 1.5 KB base64, signed by the identity key and rotated with the signed prekey) and `GET /v1/keys/{userId}` returns it. libsignal 0.106 opens sessions with PQXDH only and refuses a bundle without one. Every bundle published before this change was removed: publish again.
>
> **Changed on 2026-10-28 (update the app):**
> 1. **The dev server's data was reset.** The backend moved to a new database: every account, profile, contact, Asha chat, key bundle and message on `sandbox.circaso.com` is gone. Clear the app's local data (or reinstall), sign up again, and publish encryption keys again (`PUT /v1/keys`). Old tokens answer `401`/`identity.session_revoked`: treat that as signed out.
> 2. **Real vendors on dev.** Asha's replies, voice clips, spoken replies and live voice now run on the real models (no more canned replies or text-as-audio); email and push are really sent. Live voice now needs real Opus audio; the old "binary frame of UTF-8 words" trick no longer works.
> 3. **New answers to handle:**
>    - any route can answer `503` `service.unavailable`: retry with backoff and the **same** `Idempotency-Key` (section 3);
>    - I3 and I15 answer `identity.phone_invalid` (was `request.malformed`) for a number that isn't E.164;
>    - I6 can answer `409` `request.in_progress` when two registrations race: keep the other response, don't retry;
>    - U5 (handle availability) now counts toward the 30-per-hour handle-lookup limit and can answer `request.rate_limited`;
>    - U14 answers `request.not_found` for an id that is no account;
>    - U11 through the invite of a deleted or suspended person answers `users.invite_invalid`.
> 4. **Deleted and suspended people disappear.** Their profile looks like not found (U9, U11 by @handle), they leave your contacts list and block list, U10 answers `users.not_reachable`, and other screens get no name, handle or avatar for them. They come back if the account becomes active again (section 7).
> 5. **Account deletion now completes:** 30 days after `DELETE /v1/account` the account and all its data are erased (section 7, I14). There is no restore route yet.
> 6. **Messaging details** (section 7): `GET /v1/chats` `lastMessageId` is null for a chat with no message in the last 30 days; a retried group create returns the group as it is (and answers `messaging.chat_not_found` if you left it meanwhile); after a refused prekey top-up you may delete that request's private keys at once (section 5); the `read` frame's `upToMessageId` must be the newest `MESSAGE` from someone else; a negative `seq` in the socket's `last` closes with `invalid last`.
> 7. **Push now arrives** for new messages (`pushable: true`) and for being added to a group: data-only on Android, an alert through APNs on iOS (section 8).
>
> **Changed on 2026-10-27 (breaking, update the app):**
> 1. `details.fields` in the error envelope is now a **list** `[{ "field": "displayName", "problem": "required" }]`, no longer an object of field name to problem (section 3).
> 2. The realtime auth frame's `last` is now a **list** `[{ "module": "users", "seq": 1042 }]`, no longer an object `{ "users": 1042 }`; the old form is refused with `invalid last` (section 8).
> 3. `profile.updated`'s `version` is now a JSON **number**, not a string (section 8).

## 2. Status at a glance

Of the **110** routes the phone calls: **82 READY**, **27 PARTIAL**, **1 DEV STUB** (the carrier check).

| Area | Status | What it means for the app |
| --- | --- | --- |
| Sign in with Google (Android, iOS) | **READY** | Use the Web client id as `serverClientId`. Works against dev today. |
| Sign in with Apple (iOS) | **READY** (untested with a real token) | Bundle id `com.circaso.app` is configured on the server. |
| Phone number by SMS code | **PARTIAL** | Built on Twilio Verify; a real end-to-end send is not confirmed yet. Test and report the error `code`. |
| Phone number by carrier (Android one-tap) | **DEV STUB** | Real Firebase check not built. Keep SMS as the fallback. |
| Device registration and device key | **PARTIAL** | Works on dev. In production, Play Integrity / App Attest checks are not built, so no device could register yet. |
| Session refresh, sign out, forget device | **READY** | Needs the device-key signature (section 5). |
| Profile, @handle, avatar | **READY** | |
| Contacts sync, connections, invites, blocks, reach | **READY** | |
| Realtime socket | **READY**, few frames | Only the frame types in section 8 exist. |
| Asha chat (text), voice, speech | **READY** | Real models on dev (Claude Haiku 5.5 for Asha's replies, no fallback model; Cloudflare Workers AI for speech). When Claude is down Asha is "down for maintenance" (`assistant.unavailable`, A14). Separate conversations (C1–C5), each its own context. The free allowance numbers and the paid plan are not decided: show what the server returns. |
| Asha live voice (socket) | **READY** | Section 9. Real speech-to-text (Opus in) and voice (mulaw 8 kHz out) on dev; English only. |
| Push notifications | **PARTIAL** | FCM sends for new messages (`pushable`), for being added to a group, for wallet payments (sent, failed, received) and wallet reminders, and for calls (an incoming call, data-only and high priority; a missed call). iOS needs the APNs key uploaded in the Firebase console. Nothing else pushes yet. |
| Account security, linked logins, security log | **READY** | |
| Account deletion | **PARTIAL** | Completes 30 days after the request. No route restores an account during those 30 days yet (undecided); Apple access is not revoked. |
| Account recovery | **PARTIAL** | A case can be opened and read; no support tool resolves it yet. |
| Encryption keys (publish, top up, status, fetch a bundle) | **READY** | Uploads are signed with the device key in `X-Device-Timestamp` / `X-Device-Signature` headers over the raw, uncompressed body (section 5). A bundle can be fetched by someone who can reach the person or shares a group with them. |
| Chats and members (direct chats, groups, roles) | **READY** | |
| Messages between people (sending, inbox, receipts, typing, delete for everyone) | **READY** | End-to-end encrypted on the phone; the server stores ciphertext until it is acknowledged. |
| Chat attachments (photos, voice notes, video, files) | **READY** | AT1–AT3: encrypted on the phone, ciphertext up to 100 MiB (2026-11-07) straight to R2 through pre-signed URLs, kept 30 days. On a local server (`STORAGE_PROVIDER=local`) the upload URL is a placeholder, so test against the sandbox. |
| Moments (24-hour statuses: post, list, views, delete, encrypted media) | **READY** | ST1–ST8 and the `status` frames. Ciphertext only; the body is encrypted once under your moments sender key and new audience members get a key copy over their 1:1 session. Files go like chat attachments (up to 32 MiB of ciphertext to R2). No pushes. On a local server (`STORAGE_PROVIDER=local`) the upload URL is a placeholder, so test files against the sandbox. |
| Chat history and its key backup | **MISSING** | Designed; being built in slices. `mediaIds` on a message are stored as given. When an account is deleted, its messaging data (keys, memberships, inbox) is not cleaned up until chat history ships. |
| Wallet (create, PIN session, balance per chain, fiat balance, add a chain) | **PARTIAL** | W1–W3 and W12 work. On dev the custody provider is Circle on testnet (Base Sepolia, Polygon Amoy, Ethereum Sepolia, Solana Devnet): the challenges are real, so run them in the Circle SDK, where the user sets the PIN and the wallet appears on Circle's testnet. Test USDC comes from Circle's testnet faucet (faucet.circle.com), sent to the chain's address from W1. |
| Payments (prepare, follow, who can be paid, remind, check an address) | **PARTIAL** | W4, W5, W9–W11 and the wallet frames work against Circle's testnet: complete the payment's challenge in the Circle SDK with the PIN, and `transfer.updated` and `payment.received` arrive as Circle's webhooks report the transaction (testnet USDC from Circle's faucet). Fees and limits are rules in the database (phase 2F, "Fees and limits"): the fee is quoted by W17 and charged by W4 in the same signature; limits run over a rolling 24 hours. |
| Transaction history (W6) | **PARTIAL** | Your payments, newest first, paged; recent across months or one month. Lists what Circaso recorded: inbound payments appear once Circle reports them (webhook, or the reconcile job within minutes). |
| Statements, PIN change and restore | **MISSING** | Designed (wallet W7, W8); being built in slices. |
| Share links (receipts, statements, invites, pay me, addresses) | **READY** | SH1–SH4 and the public page at `<SHARE_BASE_URL>/s/{code}` (sandbox: `https://sandbox.circaso.com`). App Links need the signing certificate's SHA-256 in `SHARE_ANDROID_CERT_SHA256`; Universal Links need the Associated Domains capability on the App ID. |
| Support tickets (open, list, read, reply) | **READY** | SU1–SU4, the `support` frames and the `support.ticket` push (2026-11-08). Staff answer from the ops dashboard, which is being built; until it is live, replies arrive only from the server side. |
| Calls (voice and video, up to 8 people) | **PARTIAL** | CL1–CL9 and the `calls` frames work. On dev the SFU is Cloudflare Realtime once `INTEGRATIONS_CALLS_PROVIDER=cloudflare` is set on the sandbox; with the `log` provider the routes answer with made-up SDP, so no media flows. Show "Secure call", never "end-to-end encrypted" (decision 2026-10-29). |
| Notification inbox, identity verification (KYC) | **MISSING** | Not designed yet. |

## 3. Conventions every call follows

| | |
| --- | --- |
| Base URL | Dev `https://sandbox.circaso.com`; production `https://api.circaso.com` (not live yet). Make it a build setting. Every route is under `/v1`. |
| `Authorization` | `Bearer <accessToken>` on every route whose scope is not `none`. |
| `X-Client-Version` | **On every request**: `android/1.4.0` or `ios/1.4.0` (platform, slash, app version). Below the server's minimum it answers `426` `request.client_outdated`: show a force-update screen. |
| `Idempotency-Key` | A fresh UUID v4 on **every `POST`, `PUT`, `PATCH`, `DELETE`**. Missing → `400` `request.malformed`. The first answer is kept 24 hours. |
| Content | JSON, camelCase, unknown fields ignored (`Json { ignoreUnknownKeys = true }`). Times ISO-8601 UTC, ids UUIDs, phone numbers E.164 (`+254712345678`). |

**The envelope.** Every answer (except health, the Asha event stream and speech audio) is:
```json
{ "httpStatusCode": 200, "status": true, "message": "Success", "code": null,
  "details": null, "requestId": "…", "data": { } }
```
On failure `status` is `false`, `data` is `null`, and `code` is a stable string such as `identity.code_invalid`. **Key your UI text on `code`, never on `message`.** `details.retryAfterSeconds` (also the `Retry-After` header) and `details.fields` may be set: a list of the fields that are wrong, each `{ "field": "displayName", "problem": "required" }` (changed on 2026-10-27; it was an object of field name to problem). Log `requestId` and show it on error screens; Pasaka can find the exact trace from it.

**What the app does with the common failures**

| Answer | Do |
| --- | --- |
| `401` `auth.token_invalid` / `auth.token_missing` | Refresh once (section 5), retry the call once. If refresh fails, sign out locally. |
| `401` `identity.session_revoked` | The session ended (signed out elsewhere, replaced by a new phone). Clear tokens, show sign-in. |
| `403` `auth.scope_insufficient` | A bug in the app: the wrong token for this route. |
| `426` `request.client_outdated` | Force-update screen. |
| `429` `request.rate_limited` | Wait `Retry-After` seconds; show the wait, do not loop. |
| `409` `request.in_progress` | The same `Idempotency-Key` is still running: wait a moment and retry **with the same key**. |
| `503` `service.unavailable` | The server is briefly overloaded (no database connection came free in time). Retry after a second or two, with backoff, using the **same** `Idempotency-Key`. |
| `5xx`, timeouts | Retry only with the **same** `Idempotency-Key`; never mint a new key for a repeat of the same action. |

**Tokens.** Access tokens are JWTs valid **15 minutes**. Scopes: `onboarding` (after social sign-in, before the phone and device are verified: phone verification, device registration, recovery only), `app` (everything about your own account), `agent` (read-only, issued with `app`; only used for Asha, see section 9). Refresh before expiry (for example at 12–13 minutes) and on a `401`, **one refresh at a time** (single-flight): the refresh token rotates, so two parallel refreshes would revoke each other.

**Pages.** Lists take `?cursor=<opaque>&limit=<n>` and answer `nextCursor` (`null` at the end). Never build a cursor yourself.

**Rate limits worth designing around:** SMS codes 3 per number per 10 minutes (10 per phone per day); 5 wrong codes lock the account for 10 minutes; contact sync 5 per hour; handle lookups 30 per hour; everything else 120 per minute.

## 4. Shape of the KMP app

Follow the same standards as the backend: **Ktor client** for all HTTP, kotlinx.serialization, one version catalog, no second networking library.

**`commonMain`** (shared by both platforms):
- `CircasoApi`: a Ktor `HttpClient` with `ContentNegotiation(json)`, `WebSockets`, and one request interceptor that adds `X-Client-Version`, `Authorization` (when a session exists) and a new `Idempotency-Key` for every non-`GET`.
- `@Serializable` DTOs named exactly like the schemas in section 11 (`SocialSignInRequest`, `SessionDto`, …), and an `Envelope<T>` wrapper.
- `AuthManager`: the state machine of section 6 (`next` values), single-flight refresh, session storage.
- Repositories per area (profile, contacts, connections, assistant, account).
- A realtime client (section 8) and an SSE reader for Asha (section 9).

**Platform pieces behind `expect`/`actual` (or interfaces injected per platform):**

| Interface | Android | iOS |
| --- | --- | --- |
| `DeviceKeyStore` (section 5) | Android Keystore, EC P-256, StrongBox when available | Secure Enclave via Security.framework |
| `SocialSignIn` (`google(nonce)`, `apple(nonce)`) | Credential Manager + Google ID option | GoogleSignIn SDK, AuthenticationServices |
| `SecureStorage` (tokens, deviceId) | Keystore-backed encrypted storage | Keychain |
| `PushTokenProvider` | Firebase Cloud Messaging | APNs |
| `ContactsReader` | ContactsContract | Contacts.framework |
| `AudioRecorder`, `AudioPlayer` | MediaRecorder / ExoPlayer | AVFoundation |
| `ClientInfo` | platform = `android`, model, OS version, app version | platform = `ios`, … |

Ktor engines: OkHttp on Android, Darwin on iOS.

## 5. The device key (get this exactly right)

Each phone holds one **hardware-backed ECDSA P-256 key**. Its public half is registered once (`POST /v1/devices`); the private half never leaves the phone. It signs three things: the **refresh** request, the **same-phone sign-in** proof, and **encryption-key uploads**. A wrong format fails with no hint, `identity.device_proof_invalid` for sign-in and refresh and `messaging.keys_proof_invalid` for key uploads, so follow this literally.

- **Algorithm:** `ES256` (P-256, SHA-256). Send `keyAlgorithm: "ES256"`.
- **`publicKey`:** standard Base64 (with padding) of the **DER `SubjectPublicKeyInfo`** (the X.509 public key encoding).
  - Android: `keyStore.getCertificate(alias).publicKey.encoded` is already SPKI.
  - **iOS gotcha:** `SecKeyCopyExternalRepresentation` returns only the raw 65-byte point `04‖X‖Y`. Prepend this fixed 26-byte header, then Base64 the 91 bytes: `3059301306072A8648CE3D020106082A8648CE3D030107034200` (hex).
- **Signatures:** standard Base64 of the **DER-encoded ECDSA signature** (an ASN.1 sequence of r and s), over the UTF-8 bytes of the payload below.
  - Android `Signature.getInstance("SHA256withECDSA")` already produces DER.
  - iOS: `SecKeyCreateSignature` with `.ecdsaSignatureMessageX962SHA256` produces DER.
  - If a library hands you raw 64-byte `r‖s`, convert it to DER first.
- **Payloads (concatenate exactly; no spaces or newlines, except the single `\n` in key uploads):**
  - Same-phone sign-in (`deviceProof` on `POST /v1/auth/social`): `nonce + timestamp`.
  - Refresh (`POST /v1/auth/refresh`): `refreshToken + "|" + timestamp`.
  - Key uploads (`PUT /v1/keys` and `POST /v1/keys/one-time`): `timestamp + "\n" + hex(SHA-256(body))`, where `body` is the **exact bytes you send** as the request body and the hash is written as 64 lowercase hex characters. This is the one payload with a newline: exactly one `\n` byte (0x0A), nothing else between the parts. The proof goes in two headers, never in the body: `X-Device-Timestamp: <timestamp>` and `X-Device-Signature: <base64 DER signature>`. Serialise the body once, keep those bytes, hash them, sign, and send the same bytes (any re-serialisation after signing breaks it). **Send key uploads uncompressed** (no `Content-Encoding`): the server hashes the body after any decoding. Missing headers, a bad signature or a timestamp more than 5 minutes off answer `messaging.keys_proof_invalid` (403).
    - Example (`POST /v1/keys/one-time` at `2026-10-25T09:00:00Z`), body of 93 bytes: `{"oneTimePreKeys":[{"keyId":101,"publicKey":"BQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g"}]}`
    - Its SHA-256 in hex: `94a80dfab795f31ad61df91575b9a16e6c60ba7e0b056b80b7ed87536b08660a`
    - The 85-byte string to sign (the first line, one 0x0A byte, the second line):

      ```
      2026-10-25T09:00:00Z
      94a80dfab795f31ad61df91575b9a16e6c60ba7e0b056b80b7ed87536b08660a
      ```

    - Headers: `X-Device-Timestamp: 2026-10-25T09:00:00Z` and `X-Device-Signature: <base64 DER signature over those 85 bytes>`.
  - **After a refused top-up** (`messaging.prekey_id_exists` or `messaging.too_many_prekeys`), nothing from that request was ever stored or handed out: the server checks and inserts in one transaction and refuses it whole. You can delete that request's private keys at once, and send a new top-up with new ids.
- **`timestamp`:** an ISO-8601 UTC string, for example `2026-10-24T18:58:25Z`. **Send exactly the string you signed.** The server rejects a proof more than **5 minutes** away from its own clock, so a phone with a wrong clock cannot sign in or upload keys: show a "check your date and time" message for `identity.device_proof_invalid` and `messaging.keys_proof_invalid`.
- **`deviceId`:** a UUID the app generates **once per install** and stores next to the key. Reuse it in every request. On Android it is lost with the app data; on iOS the Keychain may keep it across reinstall, and that is fine.
- A lost or replaced key is not an error to fix in code: the next social sign-in answers `next: REVERIFY_PHONE` and the person verifies the number again.

## 6. Flows, step by step

All calls need `X-Client-Version`; every non-`GET` needs `Idempotency-Key`.

### A. First sign-in (new account)

1. `POST /v1/auth/nonce` → `nonce` (valid 5 minutes, single use).
2. Run the platform sign-in with that nonce (Google: nonce as given, `serverClientId` = the **Web** client id; Apple: pass Apple the SHA-256 hex of the nonce, the raw nonce is also accepted by the server).
3. `POST /v1/auth/social` with:
   ```json
   { "provider": "google",
     "idToken": "…", "nonce": "…",
     "appleName": { "given": "Amina", "family": "Wanjiru" },
     "device": { "deviceId": "<uuid>", "platform": "android", "model": "Pixel 8", "osVersion": "15", "appVersion": "1.4.0" } }
   ```
   `provider` is `google` or `apple`. Apple gives the person's name **only the first time**: send it as `appleName` immediately. The answer holds `next`, an **onboarding** session and `maskedPhone`.
4. Branch on `next`:

| `next` | Do |
| --- | --- |
| `VERIFY_PHONE` | Section B, then C. |
| `REVERIFY_PHONE` | A known account on an unknown phone: section B with the account's own number, then C. The old phone is signed out. |
| `REGISTER_DEVICE` | The number is verified on this phone but it has no key: section C. |
| `DONE` | This phone is registered and you proved it with its key (section D): you already have an **app** session. |

### B. Verify the phone

1. `POST /v1/auth/phone/start` `{ "phone": "+254712345678" }` → `verificationId`, `codeLength` (6), `expiresInSeconds` (300), `resendAfterSeconds`. Disable the resend button until that many seconds pass.
2. `POST /v1/auth/phone/verify` `{ "verificationId", "code" }` → `phoneVerified`, `next`.
   - `identity.code_invalid` (message says tries left), `identity.code_expired`, `identity.code_locked` (wait 10 minutes), `identity.phone_taken`, `identity.phone_mismatch`, `identity.sms_unavailable` (offer retry).
3. Android only, optional: `POST /v1/auth/phone/carrier` `{ "carrierToken" }` instead of SMS. Fall back to SMS on `identity.carrier_unsupported`.

### C. Register the device (activates the account)

`POST /v1/devices` with the **onboarding** token:
```json
{ "deviceId": "<uuid>", "publicKey": "<base64 SPKI>", "keyAlgorithm": "ES256",
  "pushToken": "<fcm or apns token, if you have it>", "attestation": null, "model": "Pixel 8" }
```
The answer holds the **app** session, the **agent** session and the user state. Store the refresh token securely. The account is now active.

### D. Signing back in on the same phone

Same as A, but add the proof to step 3: `"deviceProof": { "timestamp": "<iso>", "signature": "<base64 DER over nonce + timestamp>" }`. If the key matches this account, `next` is `DONE` and the response already carries the app session: no phone step.

### E. Keeping the session alive

Before the access token ends (and on a `401`), `POST /v1/auth/refresh`:
```json
{ "refreshToken": "…", "deviceId": "<uuid>", "timestamp": "<iso>", "signature": "<base64 DER over refreshToken|timestamp>" }
```
→ new `session` (access and refresh) and `agentSession`. The old refresh token is dead the moment this succeeds: **store the new one before using it**, and refresh single-flight.

### F. Ending a session

- `POST /v1/auth/signout`: ends the session, the phone stays registered (the next sign-in is one tap, flow D).
- `DELETE /v1/devices/current`: forget this phone entirely; the next sign-in verifies the number again.
- A `session.revoked` realtime frame or `identity.session_revoked` means the server ended it: clear tokens and show sign-in.

### G. Profile picture

1. `POST /v1/me/avatar/upload-url` `{ "contentType": "image/jpeg", "sizeBytes": 204800 }` (JPEG, PNG or WebP, up to 5 MB) → a pre-signed `PUT` URL and an `objectKey`.
2. `PUT` the image bytes straight to that URL (not to the API; send the `Content-Type` you declared).
3. `POST /v1/me/avatar` `{ "objectKey" }` → the updated profile. The picture URL in profiles is a pre-signed link valid **1 hour**: reload the profile instead of caching the URL.

### H. Contacts

- `POST /v1/contacts/sync` with `{ "syncId": "<uuid for this whole sync>", "batch": 1, "final": false, "phones": ["+2547…", …] }` in batches of up to **1,000** numbers; keep the same `syncId`, count `batch`, and set `final: true` on the last one. Send E.164 numbers only; names never leave the phone. The first full sync adds contacts; later syncs only send changes. 5 syncs per hour.
- Show people with `GET /v1/contacts` (paged). Connect with someone outside the phone book through `POST /v1/connections` (`via`: `handle`, `qr` or `invite`; `value`).


## 7. Endpoint catalogue

Every route the phone calls, in the order a person meets them. **Scope** is the token the call needs (`none` = no token). The status says whether the backend behind it is finished. Exact field lists for every request and response are in the schema appendix (section 11); the live source of truth is `/docs/openapi.json`.


### Sign in

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/auth/nonce`<br>**I1** One-time nonce for the Google or Apple request, valid 5 minutes | `none` | — → NonceResponse | **READY** | Valid 5 minutes, single use. |
| `POST /v1/auth/social`<br>**I2** Verify the Google or Apple ID token and learn the next step | `none` | SocialSignInRequest → SignInResponse | **READY** | `next` is `VERIFY_PHONE`, `REVERIFY_PHONE`, `REGISTER_DEVICE` or `DONE`; the session is an `onboarding` session unless `next` is `DONE`. Google verified on the dev server. Apple: the iOS bundle id `com.circaso.app` is configured, not yet tried with a real token. Errors: `identity.nonce_invalid`, `identity.provider_token_invalid`, `identity.recovery_pending`, `identity.account_suspended`, `identity.account_blocked`, `identity.account_deleted`, `identity.support_account_not_on_mobile`. |
| `POST /v1/auth/refresh`<br>**I7** New token pair for a signed refresh request; the old refresh token dies | `none` | RefreshRequest → RefreshResponse | **READY** | No bearer token; the phone proves itself by signing `refreshToken\|timestamp` with its device key. Needs the device-key signature (section 5). Errors: `identity.session_revoked`, `identity.device_proof_invalid`, `identity.support_account_not_on_mobile`. |
| `POST /v1/auth/signout`<br>**I8** End this session; the phone stays registered | `app` | — → NoBody | **READY** | The phone stays registered. The server then sends `system` / `session.revoked` on this session's socket and closes it (section 8); no push follows. |
| `GET /v1/.well-known/jwks.json`<br>Public keys the access tokens are signed with | `none` | — → JwksResponse | **READY** | The app does not need it; the server verifies its own tokens. |

### Phone verification

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/auth/phone/start`<br>**I3** Send a 6-digit SMS code | `onboarding` | StartPhoneRequest → StartPhoneResponse | **PARTIAL** | 3 codes per number per 10 minutes, 10 per phone per day. Built on Twilio Verify. A real SMS send has not been confirmed end to end yet (the first attempts failed provider-side), so test with a real number and report the `code` you get. Errors: `identity.phone_invalid` (not an E.164 number), `identity.phone_mismatch`, `identity.code_locked`, `identity.sms_unavailable`, `request.rate_limited`, `identity.support_account_not_on_mobile`. |
| `POST /v1/auth/phone/verify`<br>**I4** Check the SMS code; claims the number on sign-up | `onboarding` | VerifyCodeRequest → PhoneVerifiedResponse | **PARTIAL** | Five wrong codes lock the account for 10 minutes. Same as above: `code` is checked by Twilio on the dev server. Errors: `identity.code_invalid`, `identity.code_expired`, `identity.code_locked`, `identity.phone_taken`, `identity.phone_mismatch`, `identity.support_account_not_on_mobile`. |
| `POST /v1/auth/phone/carrier`<br>**I5** Same as I4 from a carrier token, with no SMS | `onboarding` | CarrierRequest → PhoneVerifiedResponse | **DEV STUB** | Android-only one-tap check. On the dev server the provider is `log`: the `carrierToken` is simply the phone number in E.164. The real Firebase phone-number verification is not built, so keep SMS as the fallback. Errors: `identity.carrier_unsupported`, `identity.phone_taken`, `identity.phone_mismatch`, `identity.support_account_not_on_mobile`. |
| `POST /v1/auth/phone/release`<br>**I16** Free a number held by a lost, unverified account after a 7-day notice | `onboarding` | ReleaseRequest → ReleaseResponse | **READY** | Needs a verification that proved the SIM (I3 and I4). Rare path. There is no route yet for the old phone to cancel a release. Errors: `identity.release_not_allowed`, `identity.code_expired`. |

### Register device

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/devices`<br>**I6** Register this phone's key; activates the account on sign-up and issues the app session | `onboarding` | RegisterDeviceRequest → RegisterDeviceResponse | **PARTIAL** | Send `X-Client-Version` (`android/1.0.0`): it carries the platform and app version. Works on dev (attestation is only logged). In production no device can register until Play Integrity / App Attest verification is built; the format of `attestation` is not decided yet, so send the platform token if you have one and expect it to be ignored for now. Errors: `identity.device_attestation_failed`, `request.malformed`, `request.in_progress` (409: a second registration racing the first; keep the other response; don't retry this one), `identity.support_account_not_on_mobile`. |

### Profile

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/me/profile`<br>**U1** Your full profile, privacy settings included | `app` | — → MyProfileResponse | **READY** | `avatarUrl` is a pre-signed link valid for 1 hour: do not cache it longer, reload the profile. |
| `PATCH /v1/me/profile`<br>**U2** Change name, about and privacy | `app` | UpdateProfileRequest → MyProfileResponse | **READY** | Send only the fields being changed, plus the `version` you last read. An `about` of `""` clears it. Send only changed fields plus the `version` you last read (409 `users.version_conflict` means reload). Errors: `users.profile_invalid`, `users.version_conflict`. |
| `POST /v1/me/avatar/upload-url`<br>**U3** A pre-signed URL to upload a JPEG, PNG or WebP up to 5 MB | `app` | UploadUrlRequest → UploadUrlResponse | **READY** | Upload the bytes straight to the returned URL with `PUT`, then confirm. Errors: `users.avatar_invalid`. |
| `POST /v1/me/avatar`<br>**U4** Confirm an uploaded picture | `app` | SetAvatarRequest → MyProfileResponse | **READY** | Confirms the upload with the `objectKey` from the upload-url step. Errors: `users.avatar_invalid`. |
| `DELETE /v1/me/avatar`<br>**U4** Remove your picture | `app` | — → MyProfileResponse | **READY** |  |
| `GET /v1/users/{userId}`<br>**U10** The public profile of someone you can reach | `app` | — → PublicProfileDto | **READY** | Only people you can reach. Errors: `users.not_reachable`. |

### Handles

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/handles/{handle}/availability`<br>**U5** Whether a @handle is free, taken or invalid, with a suggestion | `app` | — → HandleAvailabilityResponse | **READY** | 120 per hour, its own count (checking as you type). Errors: `request.rate_limited`. |
| `PUT /v1/me/handle`<br>**U6** Claim or change your @handle whenever the new one is free | `app` | ClaimHandleRequest → MyProfileResponse | **READY** | Whenever the new handle is free; at most 10 changes a day (`request.rate_limited`). The old handle stays held for you for 30 days. Errors: `users.handle_invalid`, `users.handle_taken`, `request.rate_limited`. |
| `GET /v1/users/by-handle/{handle}`<br>**U9** Exact-match @handle lookup | `app` | — → PublicProfileDto | **READY** | 30 lookups per user per hour. Exact match; reserved names (`@circaso`) find only Circaso's support account, whose profile has `support: true`. An owner who turned lookup off, or whose account is deleted or suspended, looks like not found. Errors: `users.handle_not_found`, `request.rate_limited`. |

### Contacts

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/contacts/sync`<br>**U7** Upload a batch of phone-book numbers (up to 1,000) and get back the ones on Circaso | `app` | SyncRequest → SyncResponse | **READY** | 5 syncs per user per hour. Names from the phone book never leave the phone. Up to 1,000 numbers per batch, 5 syncs per hour. Send E.164 numbers only; names never leave the phone. Errors: `users.sync_too_large`, `request.rate_limited`. |
| `GET /v1/contacts`<br>**U8** Your contacts with public profiles | `app` | — → ContactsResponse | **READY** | Query: `cursor`, `limit` (default 50, at most 200). Paged with `cursor` and `limit`. A contact whose account is deleted or suspended is left out until it is active again. |
| `DELETE /v1/contacts/{userId}`<br>**U16** Remove a contact; a sync won't add them back | `app` | — → RemovedResponse | **READY** | Errors: `users.contact_not_found`. |

### Connections and invites

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/connections`<br>**U11** Connect through a @handle, QR code or invite link | `app` | ConnectRequest → ConnectResponse | **READY** | `via` is `handle`, `qr` or `invite`. A QR code or invite connects both ways; a typed @handle only one way. Handle, QR payload or invite link. Errors: `users.handle_not_found`, `users.invite_invalid`, `users.not_reachable`. |
| `POST /v1/invites`<br>**U12** A shareable link and QR payload, valid 7 days | `app` | — → InviteResponse | **READY** | Valid 7 days. |
| `DELETE /v1/invites/{token}`<br>**U12** Revoke an invite you made | `app` | — → NoBody | **READY** | Errors: `users.invite_invalid`. |

### Blocks and reach

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/blocks`<br>**U13** People you have blocked | `app` | — → BlocksResponse | **READY** |  |
| `PUT /v1/blocks/{userId}`<br>**U14** Block someone; a block overrides every other way of reaching a person | `app` | — → NoBody | **READY** | Errors: `users.cannot_block_self`, `request.not_found` (no such account). |
| `DELETE /v1/blocks/{userId}`<br>**U14** Lift a block | `app` | — → NoBody | **READY** |  |
| `GET /v1/me/reach/{userId}`<br>**U15** Whether you can reach that person | `app` | — → ReachResponse | **READY** | Never says why not. |

### Encryption keys

Chats are end-to-end encrypted on the phone with libsignal (PQXDH, double ratchet, sender keys); the server only stores public keys and ciphertext. Keys are standard base64.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `PUT /v1/keys`<br>**M1** Publish this phone's identity key, signed prekey, signed Kyber prekey and up to 100 one-time prekeys | `app` | PublishKeysRequest → OneTimePreKeysLeftResponse | **READY** | Device-signed in the `X-Device-Timestamp` and `X-Device-Signature` headers (section 5, key uploads). Replaces the whole set: one-time prekeys from an earlier upload are deleted, so send fresh ones with it. Publish after registering the device, and again every 7 days to rotate the signed prekey. Errors: `messaging.keys_proof_invalid`, `messaging.too_many_prekeys` (more than 100 keys), `request.malformed`. |
| `POST /v1/keys/one-time`<br>**M1** Top up one-time prekeys | `app` | AddOneTimeKeysRequest → OneTimePreKeysLeftResponse | **READY** | Device-signed in the same headers. Top up when `GET /v1/keys/me` shows fewer than 20. At most 100 are kept at once; an upload that would pass that is refused whole with `messaging.too_many_prekeys`. Resending a key id that is already stored is refused whole with `messaging.prekey_id_exists` (409): use new ids. The 100 cap is exact, also for top-ups sent at the same moment (they run one after another on the server). Errors: `messaging.keys_proof_invalid`, `messaging.keys_not_found` (this phone has not published with `PUT /v1/keys`), `messaging.too_many_prekeys`, `messaging.prekey_id_exists`, `request.malformed`. |
| `GET /v1/keys/me`<br>**M2** One-time prekeys left and when the signed prekey was set | `app` | — → KeyStatusResponse | **READY** | `messaging.keys_not_found` until this phone has published its own keys (also after a new phone, whose keys must be published again). |
| `GET /v1/keys/{userId}`<br>**M3** A person's key bundle, to start an encrypted session | `app` | — → KeyBundleResponse | **READY** | Uses up one of their one-time prekeys; `oneTimePreKey` is missing or null when none are left, which PQXDH allows; `kyberPreKey` is always present. You must be able to reach the person, or share a group with them (group members start sessions with each other to hand over sender keys). 60 bundles per user per hour. Errors: `users.not_reachable`, `messaging.keys_not_found`, `messaging.rate_limited`. |

### Chats and members

A direct chat is exactly two people, one chat per pair. A group holds up to 1,000 people with one `OWNER`, any number of `ADMIN`s and `MEMBER`s. The group's name and avatar are encrypted on the phone under the group metadata key and sent as `sealedMeta` (base64, at most 4 KB decoded); the server stores it and never reads it. Membership changes arrive as `member.added` / `member.removed` frames (section 8), role changes as `chat.updated`: on `member.removed`, every remaining phone makes a new sender key before its next group message; on `member.added`, send the new member your current sender key. Every response is a ChatDto: `members` carry public profiles and roles, `version` is what `PATCH` sends back, `metaVersion` rises each time the name is re-sealed.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/chats/direct`<br>**M4** Open the direct chat with a person, or get the one you already have | `app` | OpenDirectChatRequest → ChatDto | **READY** | You must be able to reach the person. `created` is true on the first open, false after; two people opening it at the same moment get the same chat. `sealedMeta` is null. Errors: `users.not_reachable`, `request.malformed` (your own id). |
| `POST /v1/chats/groups`<br>**M5** Create a group | `app` | CreateGroupRequest → ChatDto | **READY** | You become the owner. People you can't reach are left out and listed in `notAdded`, with no reason (don't show one). At most 1,000 people, you included, counting everyone you asked for. Admins add members by default (`settings.membersCanAdd` false). The group's id is made from your `Idempotency-Key`: if the call fails or times out, retry with the **same** key and it carries on with the same group (never a second one). If the group changed in the meantime (someone left, say), the retry returns it as it is and adds nobody. Errors: `messaging.group_invalid`, `messaging.group_full`, `request.malformed`. |
| `GET /v1/chats`<br>**M6** Every chat you are in | `app` | — → ChatsResponse | **READY** | With members' public profiles and `lastMessageId`: the newest `MESSAGE` sent in the chat in the last 30 days, null before the first and for a chat with no message in the last 30 days (keep the last id you saw). Sort and count unread on the phone. |
| `GET /v1/chats/{chatId}`<br>**M7** One chat with members and roles | `app` | — → ChatDto | **READY** | Errors: `messaging.chat_not_found` (also when you're not in it). |
| `PATCH /v1/chats/{chatId}`<br>**M8** Change the sealed name and avatar, the group photo, or `settings.membersCanAdd` | `app` | UpdateGroupRequest → ChatDto | **READY** | Admins only. Send the `version` you last read; any change to the group since (members included) answers `messaging.version_conflict`: reload with `GET` and retry. A new `sealedMeta` raises `metaVersion`. `avatarMediaId` makes a completed `image` attachment of this group (at most 2 MB) its photo, kept until replaced; `"avatarMediaId": null` clears it, leaving it out keeps it; the old photo is deleted 24 hours later. Members get `chat.updated` with `avatarMediaId`. Errors: `messaging.chat_not_found`, `messaging.not_admin`, `messaging.group_invalid`, `messaging.avatar_invalid`, `messaging.version_conflict`, `request.malformed`. |
| `POST /v1/chats/{chatId}/members`<br>**M9** Add people to a group | `app` | AddMembersRequest → ChatDto | **READY** | Admins, or anyone when `settings.membersCanAdd` is on. People you can't reach are listed in `notAdded`; people already in are skipped. Every member gets one `member.added` frame listing everyone added in `userIds`. Errors: `messaging.chat_not_found`, `messaging.not_admin`, `messaging.group_full`. |
| `DELETE /v1/chats/{chatId}/members/{userId}`<br>**M10** Remove someone, or leave (your own id) | `app` | — → MemberRemovedResponse | **READY** | Admins remove admins and members; nobody removes the owner. When the owner leaves, ownership passes to the longest-serving admin, or else the longest-serving member, and the others get `chat.updated`. The last member leaving deletes the group. After a removal, the admin's phone makes a new group metadata key and re-seals the name (`PATCH`); after a leave, the owner's phone does (the new owner's, when the owner left). A direct chat can't be left and has nobody to remove (`messaging.not_a_group`). Errors: `messaging.chat_not_found` (also when the person was removed a moment ago), `messaging.not_admin`, `messaging.not_a_group`. |
| `PUT /v1/chats/{chatId}/members/{userId}/role`<br>**M11** Make someone an admin or a member, or hand over ownership | `app` | SetRoleRequest → ChatDto | **READY** | Admins set `ADMIN` or `MEMBER` on anyone but the owner (an admin may step down). Only the owner sets `OWNER`, and becomes an admin. A change moves the chat's `version` on and every member gets `chat.updated`. Errors: `messaging.chat_not_found`, `messaging.not_admin`, `request.malformed`. |

### Asha chat

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/assistant/chat/messages`<br>**A2** Ask Asha; the reply streams as server-sent events | `app` | SendMessageRequest → NoBody | **READY** | (the user token). `conversationId` (C1, C2) picks the conversation; without it the message goes to the most recent one, or a new one, and Asha reads only that conversation as context. Send the user's agent token in `X-Agent-Token` so Asha's tools can read the user's own data, and `currency` (the display currency, ISO 4217) so amounts come in the user's money. Replies are markdown with ```chart, ```statement and ```diagram blocks (section 9, Rich replies); `tool_status` and `tool_done` events bracket each tool call. The response is `text/event-stream`, not the envelope (errors before any text are the envelope): `message` events carry `{delta}`, each piece as the model writes it; a `tool_request` event `{turnId, callId, name, arguments}` asks the phone to run a device tool and answer with A9; `done` carries the saved reply with `conversationId` and `conversation`; an `error` event `{code, message}` means the model failed after text had already gone out (nothing is saved). Errors: `assistant.quota_exceeded`, `assistant.model_unavailable`, `assistant.conversation_not_found`, `auth.token_invalid`, `request.malformed`. |
| `DELETE /v1/assistant/chat`<br>**A5** Delete the whole history with Asha | `app` | — → NoBody | **READY** | (the user token). Every conversation and every message. |
| `GET /v1/assistant/chat`<br>**A1** The Asha chat, newest first | `app` | — → ChatResponse | **READY** | (the user token). Query: `cursor`, `limit` (default 50, at most 100). Every conversation's messages, each with its `conversationId`; for one conversation use C5. |
| `GET /v1/assistant/conversations`<br>**C1** Your conversations with Asha, newest first | `app` | — → ConversationsResponse | **READY** | (the user token). Query: `cursor`, `limit` (default 50, at most 100). Newest first by `lastMessageAt` (an empty conversation counts from its creation). Errors: `request.malformed` (a bad cursor). |
| `POST /v1/assistant/conversations`<br>**C2** Start a new conversation | `app` | CreateConversationRequest (optional) → ConversationDto | **READY** | (the user token). Body `{}` or empty, or `{ "title": "Budget" }` (1 to 80 characters). Without a title it is `"New chat"` until its first question titles it. It becomes the most recent conversation. Errors: `request.malformed`. |
| `PATCH /v1/assistant/conversations/{conversationId}`<br>**C3** Rename a conversation | `app` | RenameConversationRequest → ConversationDto | **READY** | (the user token). `{ "title": "December budget" }`, 1 to 80 characters after trimming; a title the user chose is never replaced. Errors: `assistant.conversation_not_found`, `request.malformed`. |
| `DELETE /v1/assistant/conversations/{conversationId}`<br>**C4** Delete a conversation | `app` | — → NoBody | **READY** | (the user token). Deletes the conversation and every message in it; Asha keeps no copy. Errors: `assistant.conversation_not_found` (also on a repeat), `request.malformed`. |
| `GET /v1/assistant/conversations/{conversationId}/messages`<br>**C5** One conversation's messages, newest first | `app` | — → ChatResponse | **READY** | (the user token). Query: `cursor`, `limit` (default 50, at most 100), as A1. Errors: `assistant.conversation_not_found`, `request.malformed`. |
| `DELETE /v1/assistant/chat/messages/{messageId}`<br>**A6** Delete one exchange | `app` | — → NoBody | **READY** | (the user token). |
| `POST /v1/assistant/chat/messages/{messageId}/tool-results`<br>**A9** The phone's answer to a device tool; the reply then continues as server-sent events | `app` | ToolResultsRequest → NoBody | **READY** | (the user token). `messageId` is the `turnId` from the `tool_request` event. At most 30 short snippets; they are used for this turn and never stored. Answer a `tool_request` within 10 minutes. The response is a new `text/event-stream` carrying on the same reply (errors before any text are the envelope): `message` events `{delta}` as the model writes (append them to what was shown before the pause), another `tool_request` if Asha needs the phone again, `done` with the saved reply (it includes what was said before the pause), or an `error` event `{code, message}` if the model fails after text has gone out (nothing is saved). Errors: `assistant.turn_not_found`, `assistant.model_unavailable`, `auth.token_invalid`, `request.malformed`. |

### Asha voice and usage

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/assistant/voice`<br>**A3** Transcribe a voice clip | `app` | — → TranscriptResponse | **READY** | (the user token). The clip is the raw body (up to 60 s, under 3 MB); send `Content-Type` of the audio and `X-Duration-Seconds`, the clip's length in seconds as the recorder reports it (a decimal such as `5.5` is fine; it is rounded up, so `60.2` is over the limit). A missing or non-positive length and a clip over 3 MB are `request.malformed`, each named on its own in `details.fields` (`X-Duration-Seconds`, `audio`). Errors: `assistant.voice_too_long`, `assistant.quota_exceeded`, `request.malformed`. |
| `POST /v1/assistant/speech`<br>**A4** Turn a reply into audio | `app` | SpeechRequest → NoBody | **READY** | (the user token). The response is the audio itself; an error is the envelope. Returns audio, not the envelope. Send the user's chosen `voice` (an id from A12); missing or unknown is the default voice, never an error. The server voice (Workers AI Aura) speaks English only: speak a Swahili reply with the phone's own text-to-speech instead of calling this route (with `"language": "sw"` the server answers `assistant.language_not_supported`, 400, and nothing is counted against the allowance). That refusal already works on dev, exactly as in production, so the Swahili path can be tested now. Errors: `assistant.quota_exceeded`, `assistant.language_not_supported`, `assistant.model_unavailable`. |
| `GET /v1/assistant/usage`<br>**A7** Today's allowance used and left, and the plan | `app` | — → UsageResponse | **PARTIAL** | (the user token). Works; the free allowance numbers and the paid plan are not decided yet, so show what the server returns and nothing hard-coded. Since 2026-11-05 also `periodStart`, `periodEnd` and the meters `messages`, `voiceSeconds`, `searches` (`{ used, limit }`, `limit` null when unlimited) for the usage menu. |
| `GET /v1/assistant/voices`<br>**A12** The voices Asha can speak in | `app` | — → VoicesResponse | **READY** | (the user token). Six voices; `default: true` marks the one used when the app sends no `voice`. `gender` is `female` or `male`, a hint for the picker. Show the list the server returns (it is config, and may change): don't hard-code it. |
| `GET /v1/assistant/voices/{voiceId}/sample`<br>**A13** Hear a voice before choosing it | `app` | — → NoBody | **READY** | (the user token). The response is MP3 bytes, not the envelope; an error is the envelope. The same short line ("Hi, I'm Asha. I can help you understand your money and payments.") in that voice. Not counted against the voice allowance; `Cache-Control: private, max-age=86400`, so cache it. Errors: `assistant.voice_not_found` (404: refresh A12), `assistant.model_unavailable`. |
| `GET /v1/assistant/status`<br>**A14** Whether Asha can answer now | `app` | — → AssistantStatusResponse | **READY** | (the user token). `available: false` is Asha down for maintenance (her model can't answer; A2, A9 and A11 say `assistant.unavailable`); `since` is when that last changed (ISO-8601), null if she has never been down. Read on opening and resuming Asha's screens, and every 30 s or so while showing her as down; the socket's `assistant` / `status` frame and the `asha.back` push also say she is back. Added 2026-11-07. |

### Wallet

A Circle user-controlled wallet: the server never holds keys and never moves money; the user signs every action with the Circle PIN in the Circle SDK. Each chain the wallet is on has its own address (EVM chains: lowercase `0x…`; Solana: base58). Amounts are strings: `amountMinor` (integer in the asset's smallest unit) and `amount` (whole units, for display).

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/wallet`<br>**W1** Your wallet: status, chains, assets, totals, fiat value | `app` | — → WalletResponse | **PARTIAL** | `status` is `NONE` (show "Create wallet"), `SETTING_UP` (the PIN isn't set yet: poll this route after the SDK finishes) or `ACTIVE`. `chains` lists each chain with its address and every asset offered there (zero included); `totals` adds each asset up over all chains. `?currency=KES` (any ISO 4217 code) adds `fiat` at USDC = 1 USD and other coins at their `usdPrice` (CoinGecko), null when no rate is known; `partial: true` means something held has no price. Each asset carries `usdPrice` (display and typing only, never a payment). Credit "Rates by Exchange Rate API" and "Prices by CoinGecko" next to the fiat value. `hold` is always null for now (the payment hold arrives with WS4). A chain with `receiveEnabled: false` is retired: don't show its address to be paid. Errors: `wallet.custody_unavailable`, `request.malformed`. |
| `POST /v1/wallet`<br>**W2** Create your wallet | `app` | — → CreateWalletResponse | **PARTIAL** | Run `challengeId` in the Circle SDK with `sdk` (`appId`, `userToken`, `encryptionKey`): the user sets the PIN and security questions, and Circle creates the wallet on the default chain (Base). Calling again while `SETTING_UP` gives a fresh challenge; `challengeId` is null when the PIN is already set and the wallet is on its way. On dev Circle runs on testnet: the challenge is real, so complete it in the Circle SDK, then poll W1 (or wait for `wallet.ready`). Errors: `wallet.already_exists`, `wallet.custody_unavailable`, `wallet.support_account_has_no_wallet`. |
| `POST /v1/wallet/session`<br>**W3** The Circle SDK's session values | `app` | — → SdkDto | **PARTIAL** | `appId`, `userToken`, `encryptionKey`, `expiresAt` (an hour at most). Keep them in memory only, never log them. Errors: `wallet.not_found`, `wallet.custody_unavailable`. |
| `GET /v1/assets/icons/{symbol}`<br>**IC1** A coin's icon, by symbol | none | — → `image/svg+xml` (not the envelope) | **READY** | The Web3 Icons mono mark (MIT), one colour in a 24 × 24 box: paint it with a theme colour (a `ColorFilter`), never brand colours. `symbol` in any case, 1–15 letters, digits or dots. `Cache-Control: public, max-age=604800, immutable` and an `ETag` (`If-None-Match` → 304); keep it in the image loader's disk cache. Map wrapped and renamed coins first (`WETH` → `ETH`, `MATIC` → `POL`, `cbBTC` → `BTC`). 600 requests per IP per minute. Errors (envelope): `wallet.icon_symbol_invalid` (400), `wallet.icon_not_found` (404). |
| `GET /v1/wallet/chains`<br>**W12** The supported chains, and which the wallet is on | `app` | — → ChainsResponse | **PARTIAL** | Each chain is `ENABLED`, `ADDING` (asked for, not signed yet) or `AVAILABLE`, with the asset symbols offered on it; `defaultChain` is where a new wallet starts. A chain retired from receiving (`receiveEnabled: false`) is listed only while your wallet is on it, so it can be emptied. For the wallet-management screen. |
| `POST /v1/wallet/chains`<br>**W12** Add a supported chain | `app` | AddChainRequest → AddChainResponse | **PARTIAL** | `{ "blockchain": "SOL-DEVNET" }` (a name from the list above). Answers `ADDING` with a challenge to run in the SDK (the PIN creates the wallet there), or `ENABLED` with no challenge when the wallet is already on it, so a repeat is harmless. The chain shows in W1 once Circle has made it. Adds of the same chain within the hour (two taps, two phones) get the same challenge. A retired chain can't be added (`wallet.chain_unsupported`). Removing a chain isn't possible. Errors: `wallet.chain_unsupported`, `wallet.not_found`, `wallet.not_ready`, `wallet.custody_unavailable`, `request.malformed`, `wallet.support_account_has_no_wallet`. |
| `POST /v1/wallet/transfers`<br>**W4** Prepare a payment | `app` | PrepareTransferRequest → PrepareTransferResponse | **PARTIAL** | `{ "blockchain": "BASE-SEPOLIA", "to": { "userId": "…" } \| { "address": "0x…" }, "amount": { "asset": "USDC", "amountMinor": "20000000" } }` (or `"amount": "20"`; both only if they agree; since 2026-11-07 `asset` is any coin the wallet holds on that chain, in its own decimals), optional `chatId` (paying from that chat: sharing it stands in for reach). The payment leaves from your wallet on that chain. Checks, in this order: account active, your wallet on the chain, hold, amount and limits, the payee, your balance there. Run `challengeId` in the Circle SDK with `sdk` before `expiresAt` (15 minutes); follow it with `transfer.updated` frames or W5. Always the same `Idempotency-Key` for one payment: a retry returns the same transfer (and the same challenge while it can be signed). Send W17's `quoteId`: its fee is charged on top, and `total` is what leaves the wallet (phase 2F, "Fees and limits"). Show a warning before paying an address: payments on chain can't be reversed. On dev Circle runs on testnet: sign the challenge in the SDK, and the frames follow from Circle's webhooks. `proposalId` is refused (`request.malformed`) until Asha's payment proposals exist. Errors: `wallet.account_restricted`, `wallet.chain_unsupported`, `wallet.not_found`, `wallet.not_ready`, `wallet.not_on_chain`, `wallet.on_hold`, `wallet.amount_invalid`, `wallet.asset_not_held`, `wallet.asset_unpriced`, `wallet.asset_not_supported`, `wallet.limit_exceeded`, `wallet.self_transfer`, `users.not_reachable`, `wallet.recipient_no_wallet`, `wallet.recipient_not_on_chain`, `wallet.address_invalid`, `wallet.insufficient_funds`, `wallet.pin_locked`, `wallet.custody_unavailable`, `request.malformed`, `wallet.support_account_has_no_wallet`, `wallet.fee_changed`. |
| `GET /v1/wallet/transfers/{transferId}`<br>**W5** One payment and its state | `app` | — → TransferResponse | **PARTIAL** | For the sender (`direction: OUT`) and the person paid (`IN`), so the chat's payment card works on both sides. `origin` and `proposalId` are only shown to the sender (null for the person paid). `state`: `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED` (see `failureReason`) or `EXPIRED`. Errors: `wallet.transfer_not_found`, `request.malformed`. |
| `GET /v1/wallet/transactions`<br>**W6** Your payments, newest first | `app` | — → TransactionsResponse | **PARTIAL** | Query: `month` (optional, `YYYY-MM`, a calendar month in UTC), `cursor`, `limit` (default 50, at most 200). **Without `month`** the list runs back through every month: use it for the wallet tab's "Recent" and its infinite scroll. **With `month`** only that month; pass the same `month` with each `cursor`. `nextCursor` is null on the last page. Only payments that were signed appear (`SUBMITTED`, `CONFIRMED`, `FAILED`): an abandoned PIN sheet (`AWAITING_SIGNATURE`, `EXPIRED`) is not a payment. `direction` is `OUT` (you sent it; `transferId` set, open it with W5) or `IN` (it paid you). Since 2026-11-05 an `IN` row from a Circaso payment carries that payment's `transferId` (open it with W5, which serves the person paid) and its `chatId`, once the server has linked it; null for money from an outside address, or when it can't tell which of the sender's payments it was. `counterparty` has `userId`, `displayName`, `handle`, `avatarUrl` when the other side is on Circaso (no name when their account is gone) and the on-chain `address` whenever known: show the name, else a shortened address. `origin` only on `OUT`. Use `id` as the list key. Refresh the first page on `wallet` / `transfer.updated` and `payment.received` frames. 60 requests per user per minute. Errors: `request.malformed` (`month`, `cursor` or `limit`), `request.rate_limited`. |
| `GET /v1/wallet/recipients/{userId}`<br>**W9** Can this person be paid, and where | `app` | — → RecipientResponse | **PARTIAL** | Query `blockchain` (optional, the chain you mean to pay on) and `chatId` (optional). `status`: `CAN_RECEIVE`, `NO_WALLET` (offer W10: "Amina hasn't set up her wallet yet") or `NO_WALLET_ON_CHAIN` (pick one of `blockchains`, where they can be paid now). Errors: `users.not_reachable`, `wallet.self_transfer`, `wallet.chain_unsupported`, `request.malformed`, `wallet.support_account_has_no_wallet`. |
| `POST /v1/wallet/recipients/{userId}/remind`<br>**W10** Remind a contact to set up a wallet | `app` | — → RemindResponse | **PARTIAL** | They get a `wallet` / `setup.requested` frame and a push. `status`: `SENT`, `ALREADY_SENT` (once per person per 24 hours) or `HAS_WALLET` (pay them instead). Errors: `users.not_reachable`, `wallet.self_transfer`, `request.malformed`, `wallet.support_account_has_no_wallet`. |
| `POST /v1/wallet/addresses/validate`<br>**W11** Check an outside address on a chain | `app` | ValidateAddressRequest → ValidateAddressResponse | **PARTIAL** | `{ "blockchain", "address" }` → `valid` (an answer, not an error) and `address` as it will be paid (EVM lowercase). Check before W4 to an address. Errors: `wallet.chain_unsupported`, `wallet.custody_unavailable`, `request.malformed`. |

### Chama (a group's shared goal)

**Changed by group wallets (Spaces phase 2, below): the treasurer model is retired; the money sits in the group's own wallet and the goal is an optional target.** What follows describes the treasurer model for the record. A chama is a group chat with a goal. The money sits in the **treasurer's** own wallet (one member; the goal names who): contributing is an ordinary W4 payment to the treasurer with `chatId` = the group, after which you send the payment card into the chat; the server counts it from the confirmed transfer. Paying someone from the group needs the admins' approval, then the treasurer pays it (W4 from the chat) and links the transfer. Amounts are strings as across the wallet.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `GET /v1/chama/chats/{chatId}`<br>**CH1** The goal, totals and open requests | `app` | — → ChamaResponse | **READY** | Members only. `held` = `contributed` − `paidOut`. `chama.no_goal` when the group has none yet (offer the admins "Set a goal"). Errors: `chama.not_a_member`, `chama.no_goal`. |
| `PUT /v1/chama/chats/{chatId}/goal`<br>**CH2** Set or change the goal | `app` | SetGoalRequest → GoalDto | **READY** | Admins only. `treasurerUserId` defaults to the caller and must be a member. Everyone gets `chama` / `goal.updated`. Errors: `chama.not_admin`, `chama.goal_invalid`, `chama.treasurer_not_member`. |
| `GET /v1/chama/chats/{chatId}/ledger`<br>**CH3** Contributions in, payments out | `app` | — → LedgerResponse | **READY** | `?limit=` (≤200), `?before=` from the previous page's `next`. |
| `POST /v1/chama/chats/{chatId}/requests`<br>**CH4** Ask the group to pay someone | `app` | CreateRequestRequest → RequestDto | **READY** | Any member. Needs two admins' approval (all admins when fewer); an admin asking counts as their own. Open 48 hours, then `EXPIRED`. Everyone gets `chama` / `request.updated`; admins still to decide are pushed. Errors: `chama.no_goal`, `chama.amount_invalid`, `chama.payee_invalid`. |
| `POST /v1/chama/requests/{requestId}/approve` and `/decline`<br>**CH5** Decide | `app` | — → RequestDto | **READY** | Admins, once each. Enough approvals → `APPROVED`; one decline → `DECLINED`. Errors: `chama.not_admin`, `chama.request_closed`, `chama.already_decided`. |
| `POST /v1/chama/requests/{requestId}/payment`<br>**CH6** Link the treasurer's payment | `app` | AttachPaymentRequest → RequestDto | **READY** | Treasurer only, for an `APPROVED` request: pay with W4 (`to` = the request's payee, the same amount, `chatId` = the group), then send the `transferId` here. `PAID` once confirmed on chain. Errors: `chama.not_treasurer`, `chama.not_approved`, `chama.transfer_mismatch`. |

### Group wallets (Spaces phase 2)

Spaces phase 2 (decisions of 2026-11-03, the app's `spaces-plan.md` §7 and §11): **a wallet belongs to a person or a group**. A group may have its own wallet (set up when the group is created, or later from group info); it is separate from everyone's personal money, it is **its own Circle user** (so its own PIN, and its own biometrics on the owner's phone), and **option A** applies: the group's owner sets the PIN and becomes the wallet's **signer**, the only person who can send from it. **Since 2026-11-06 the signer sends directly** (to anyone, themselves, or an outside address), with no payout request; approvals are parked behind `WALLET_GROUP_PAYOUT_APPROVALS`. Chamas and groups are one thing: a group with a wallet may have an optional **target** (the old chama goal). The **treasurer model is retired**.

**Wallet ids.** Every wallet has a `walletId`. Your own wallet's id is **your user id** (nothing changes for personal wallets: every route without `walletId` works exactly as before). A group wallet has its own id, from W14, W15 or W16. A group wallet's name and picture are the group's own, which are sealed (end to end): **the server never knows the name**, so the phone names a group wallet from its group (`owner.chatId`) on every screen, PIN and biometric prompt ("Approve from Kilimani Chama").

**What needs a connection:** everything here (money is never queued). Fees and the group daily cap: see "Fees and limits" below (phase 2F).

#### New wallet routes

| Call | Scope | Request → response | What to know |
| --- | --- | --- | --- |
| `POST /v1/wallet/groups/{chatId}`<br>**W14** Set up the group's wallet | `app` | — → GroupWalletSetupResponse `{ walletId, chatId, status, challengeId?, sdk }` | **The group's owner only.** Send an `Idempotency-Key`. Run `challengeId` in the Circle SDK with **this answer's `sdk`** (the group wallet's own session, not your personal one), with the Circle PIN texts naming the group (GW2/GW3). Then offer biometrics for this wallet (GW4) with a W3 session for this `walletId`, and call W13 with `?walletId=` to put it on every network (GW5). Calling again while `SETTING_UP` gives a fresh challenge (GW6b "Finish Wallet Setup"); once `ACTIVE`, `wallet.already_exists`. Every member gets `wallet` / `wallet.ready` with `walletId` when it turns active. Errors: `wallet.group_not_found` (not a group, or you're not in it), `wallet.not_group_owner`, `wallet.not_signer` (someone else started the set-up and ownership has moved since), `wallet.already_exists`, `wallet.account_restricted`, `wallet.support_account_has_no_wallet`, `wallet.custody_unavailable`, `request.malformed`. |
| `GET /v1/wallet/groups/{chatId}`<br>**W15** The group's wallet | `app` | — → GroupWalletResponse `{ chatId, status, wallet? }` | Members only. `status` `NONE` (no wallet: the owner sees Add a Group Wallet, members see no money at all, GW6), `SETTING_UP` (owner: Finish Wallet Setup; members: "Wallet being set up", GW6b) or `ACTIVE`. `wallet` is a WalletListingDto `{ walletId, owner { type: "GROUP", chatId, signerUserId }, status, canSign, blockchains }`. Read from the server's rows only, so it is cheap to poll during set-up. Errors: `wallet.group_not_found`, `request.malformed`. |
| `GET /v1/wallets`<br>**W16** Every wallet you can see | `app` | — → WalletsResponse `{ wallets: [WalletListingDto] }` | For the **Paying from** switcher (GW10) and the Wallet tab's title switcher (GW16). Your own wallet first (`owner.type: "PERSON"`, `status` may be `NONE`), then the wallets of the groups you are in, the ones you sign for (`canSign`) first. Show the chip only when the list has more than your own. Only `canSign` wallets can be picked to pay from; picking a group wallet pays from it directly (2026-11-06; with approvals switched back on it would be **Request Payout**, CH4). Balances: W1 with `?walletId=` for each. |

#### Changed wallet routes (all additive)

| Call | What changed |
| --- | --- |
| **W1** `GET /v1/wallet?walletId=` | Reads a group wallet for any member (GW8, GW16). The response gains `walletId`, `owner` (`{ type, userId? , chatId?, signerUserId }`) and `canSign`. A wallet you can't see answers `wallet.not_found`; a malformed id `request.malformed`. |
| **W3** `POST /v1/wallet/session?walletId=` | The group wallet's own Circle session, **for its signer only**: use it for that wallet's challenges and for `WalletSigner.enableBiometrics(session)` (biometrics per wallet; if Circle reports biometrics lost for it, only that wallet falls back to its PIN, GW8b). New errors: `wallet.not_signer`, `wallet.signer_left` (the signer has left the group: nobody can send from it until they're back), `request.malformed`. |
| **W4** `POST /v1/wallet/transfers` | `to` takes **`{ "groupId": "<chatId>" }`**: pay into a group's wallet (members only; with `chatId` = the group, this is a **contribution**, counted towards its target if it has one; GW9a2/GW9d). New body fields `walletId` (pay **from** a group wallet you sign for) and `payoutRequestId` (optional since 2026-11-06: when sent, a CH4 request the admins approved, and `to` and `amount` must be exactly the request's payee and amount, GW9; required only with `WALLET_GROUP_PAYOUT_APPROVALS`). The answer gains `walletId` (the wallet it leaves). New errors: `wallet.group_not_found` (paying a group you're not in), `wallet.recipient_no_wallet` / `wallet.recipient_not_on_chain` (the group has no wallet, or none on that chain), `wallet.not_signer` (anyone but the owner), `wallet.signer_left`, `wallet.payout_required` (only with approvals on), `wallet.payout_not_approved` (not approved, or already paid), `wallet.payout_mismatch`, `wallet.payout_in_progress` (one payment for this payout can still be signed or has gone through; a failed or expired one doesn't count, so Try Again works with a new key, GW19). The same `Idempotency-Key` still answers the same transfer. |
| **W5** `GET /v1/wallet/transfers/{id}` | Every member of a group whose wallet is on either side sees the transfer, from the wallet's side (`direction` `OUT` for a payout, `IN` for a contribution). New fields `fromGroupId`, `toGroupId`, `signerUserId`, `payoutRequestId`. |
| **W6** `GET /v1/wallet/transactions?walletId=` | A group wallet's history for any member (statements per wallet). `counterparty.groupId` is set when the other side is a group's wallet (name it from the group on the phone). |
| **W12** `GET/POST /v1/wallet/chains?walletId=` and **W13** `POST /v1/wallet/chains/missing?walletId=` | The group wallet's networks; adding one is for its signer only (`wallet.not_signer`). |
| Deleting an account (I14) | Also refused with **`wallet.group_funds_remaining`** while a group wallet you sign for still holds money ("Pay it out before deleting the account"). |

#### Group money routes (chama, changed and new)

| Call | Scope | Request → response | What to know |
| --- | --- | --- | --- |
| `GET /v1/chama/chats/{chatId}/money`<br>**CH7** The group's money, target or not | `app` | — → GroupMoneyResponse | Members only, any group. `wallet` (`{ walletId, status, signerUserId }`) is null until the owner sets one up: **show no money actions then**. `approvalPolicy` `TWO_ADMINS` or `ANY_ADMIN`. `goal` (the target) is null without one. `contributed`/`myContribution`/`contributors` count towards the target since it was set. `isAdmin`, `isOwner` (may change who approves, CH9), `isSigner` (pays approved requests). Use this instead of CH1 for group info (GW6–GW8) and the thread. Reload on `chama` / `group.updated`, `goal.updated`, `contribution.added`, `request.updated`, `money.updated`. Errors: `chama.not_a_member`, `request.malformed`. |
| `PUT /v1/chama/chats/{chatId}/settings`<br>**CH9** Who approves payouts | `app` | `{ "approvalPolicy": "TWO_ADMINS" \| "ANY_ADMIN" }` → GroupSettingsDto | **The group's owner only.** Send it right after W14 with what was picked in New Group (GW1b "Who approves payouts"); the default is `TWO_ADMINS` (every admin when there are fewer). Requests already made keep theirs. Every member gets `chama` / `group.updated`. Errors: `chama.not_a_member`, `chama.not_owner`, `request.malformed`. |
| `DELETE /v1/chama/chats/{chatId}/goal`<br>**CH8** Remove the target | `app` | — → (no data) | Admins only. The wallet, its money, the ledger and open requests stay; a new target counts from when it is set. Everyone gets `chama` / `group.updated`. Errors: `chama.not_admin`, `chama.no_goal`. |
| **CH1** `GET /v1/chama/chats/{chatId}` | | | Still `chama.no_goal` without a target. Gains `walletId` and `approvalPolicy`; `isTreasurer` now means "you send from the group wallet", `goal.treasurerUserId` is the wallet's signer (null before a wallet). `held` is what the ledger says; W1 has the real balance. |
| **CH2** `PUT …/goal` | | | **A target needs the group's active wallet**: `chama.no_wallet` otherwise (GW7: walk the owner through W14 first, then GW7b). `treasurerUserId` is ignored. `chama.treasurer_not_member` is gone. |
| **CH3** `GET …/ledger` | | | No longer needs a target. Contributions from before group wallets are kept (marked legacy server-side) and never count towards a target. |
| **CH4** `POST …/requests` | | | Needs the group's **active wallet** (`chama.no_wallet`), not a target. Approvals follow CH9. The `request.updated` frame gains `signerId`; the signer is pushed when a request is approved ("It's ready for you to pay"). |
| **CH6** `POST /v1/chama/requests/{id}/payment` | | | **Optional now**: W4 with `payoutRequestId` ties the payment to the request, and the request turns `PAID` when the wallet confirms it. Linking early shows the others it's being paid. For the wallet's signer only (`chama.not_signer` replaces `chama.not_treasurer`; `chama.no_wallet`). |

#### Frames

| Module / type | Change |
| --- | --- |
| `wallet` / `transfer.updated`, `payment.received` | A group wallet's go to its **signer**, with `walletId` set (null for your personal wallet). The push for money into a group wallet says "… paid into a group wallet". |
| `wallet` / `wallet.ready` | For a group wallet: to **every member**, with `walletId`. |
| `chama` / `group.updated` (new) | `{ chatId, byUserId }`: who approves payouts changed, or the target was removed: reload CH7. No push. |
| `chama` / `request.updated` | Gains `signerId`. `goalName` is empty for a group without a target (say "the group wallet"). |
| `chama` / `contribution.added` | Only for a group with a target (a contribution to a group without one is recorded quietly; the payment card in the chat shows it). |
| `chama` / `money.updated` (new, 2026-11-04) | `{ chatId, transferId, kind: "CONTRIBUTION" \| "PAYOUT", amount, userId?, requestId? }` to **every member** once money moved into or out of the group's wallet is confirmed on chain: a contribution (target or not; `userId` paid it) or money paid out: for an approved request (`requestId`), or sent straight by the owner (no `requestId`, 2026-11-06). Reload CH7 and the group wallet (W1 and W6 with `?walletId=`) so the balance and the target move while the screen is open. No push. Sent once per transfer. |

#### Migration of existing chamas (dev is testnet)

Goals made under the treasurer model stay as targets with `walletId` null, waiting for their group's wallet (GW7); setting a target again after the wallet exists starts it over. Their contributions stay in the ledger but count towards nothing (the money is in the old treasurer's wallet). Requests that were still open became `EXPIRED`.

#### Suggested order in the app

1. Owner model in `feature/wallet`: `WalletOwner`, `walletId` on every read, W16 switcher, W1/W6 per wallet, sessions per wallet (W3 `?walletId=`) and biometrics per wallet.
2. New Group with the wallet switch (GW1) → messaging create → W14 → Circle PIN (GW2/GW3) → biometrics offer (GW4) → W13 → CH9 → CH2 if a target was typed (GW5).
3. Group info (GW6–GW8) from W15 + CH7.
4. Contributions: `#` in a group with a wallet → W4 `to: { groupId }` (GW9a2, GW9d).
5. Payouts: Request Payout (CH4, GW9c) and Pay Payout (W4 `walletId` + `payoutRequestId`, GW9, GW11, GW12, GW17–GW19), replacing the treasurer's flow and CH6.

### Fees and limits (Spaces phase 2F)

Decisions of 2026-11-03 (the app's `spaces-plan.md` §7a and §7b), the fee's formula replaced on 2026-11-08. **One Circaso fee per payment, network cost included; the sender pays it on top and the payee gets exactly the amount.** Circle Gas Station pays the chain, and the fee passes that network cost on: people never see gas as its own line, only the one fee. Money arriving from outside Circaso is never charged. **Everything here needs a connection; money is never queued.**

**The fee (since 2026-11-08).** `fee = ceil_to_cent(network cost × (1 + margin)) + half_up_to_cent(rate × min(amount, cap))`, then raised to the rule's `min_fee` when it sets one. The **network cost** is what the chain's recent payments actually cost (the 75th percentile of the last 50 confirmed transfers on the chain and coin, or the chain's coins together, over 30 days), or Circle's estimate for the exact call while the chain has fewer than 5; the **margin** is the rule's (20 %). The rate comes from the most specific rule in force (a rule can name the kind, `INTERNAL` between Circaso wallets or `EXTERNAL` to an outside address, the owner type on either side, the chain, the asset or the sending wallet). Seeds: **0.5 %** between people, **1 %** to or from a group, **2 %** to an outside address, **0 % on Solana** (one Solana challenge can't carry a fee leg yet, so nothing is charged there); the cap is $10,000 unless a rule sets less. `minimum: true` only when `min_fee` raised the fee: show "minimum" instead of the rate then (GW13b). A 0 % rule charges the network cost alone. A chain with no Circaso fee wallet configured takes no fee. Example on Base Sepolia today (rule 5 bps, cap $100): 7.70 USDC pays $0.01, $100 pays $0.06.

**The quote, then the payment.**
1. While the person reviews (the chat's money card, the Pay screen), call **W17** with W4's body, again after a short pause whenever the amount, asset, network or Paying from changes (GW13: "Working it out", Pay waits). Show `fee` (with `rateBps` or "minimum"), `total` and "One Circaso fee, network cost included." Never show a fee you didn't get from W17 (GW13d: if it fails, nothing can be signed; show Try Again and the reference).
2. **W4 with that `quoteId`** charges exactly that fee for 15 minutes (`expiresAt`), whatever happens to the rules meanwhile. The quote is for exactly one payment: the same wallet, chain, payee and amount.
3. If W4 answers **`wallet.fee_changed`** (the quote ran out and the fee is now higher, or none was sent), nothing was prepared: show the new fee and total from `details.fields` (GW13c: "The fee changed: KES 120 → KES 125"), and send W4 again with the new `quoteId` and a **new** `Idempotency-Key` once the person taps Pay again. A fee that stayed the same or fell goes through without asking.
4. Sign the challenge as always. On EVM chains a payment with a fee is one **contract-execution** challenge (`executeBatch` on your own smart wallet with two USDC transfers, the payee and Circaso's fee wallet, all or nothing); Circaso's fee address is server configuration, never sent by the app. W5 and `transfer.updated` follow it as before.

**Limits.** Rules too, over a **rolling 24 hours**, counting what leaves the wallet **fees included** (payments awaiting a signature, submitted or confirmed; a failed or expired one stops counting). Seeds: people **500 USDC a payment and 1,000 in 24 hours** (placeholders until KYC tiers); **groups 10,000 USDC in 24 hours** (payouts to members count, so "pay a member, member sends it on" can't get round it); spaces **none**. Raising a group's limit is a request to Circaso for now.

**W17 reports, W4 refuses.** W17's `problem` says what stops the payment and still gives the fee: `INSUFFICIENT_FUNDS` (GW20: "You have KES 18,240. With the fee, the most you can send is KES 18,149", offer `maxAmount`), `PER_PAYMENT_LIMIT`, `DAILY_LIMIT` (GW14b: "This payout fits the daily limit from 14:30", `fitsAt`; `limit.left` for GW14's "can send KES X more today"). Members of a group may call W17 for its wallet (a Request Payout shows the fee and the limit as they stand, GW9c/GW14; the D5 card shows "waits for the limit" until `fitsAt`, GW14c); only the signer gets a `quoteId`. W4 refuses the same cases with:

| Code | `details.fields` (`{ field, problem }` = a name and its value) |
| --- | --- |
| `wallet.limit_exceeded` (400) | `limit` (`PER_PAYMENT` or `DAILY`), `limitMinor`, `usedMinor`, `leftMinor`, `maxAmountMinor` (the most that fits with its fee), `windowHours`, `resetsAt` (when this payment fits; absent when it never can), `moreAt` (when more becomes available) |
| `wallet.insufficient_funds` (400) | `balanceMinor`, `feeMinor`, `maxAmountMinor` (absent when Circle refused for its own reasons) |
| `wallet.fee_changed` (409) | `quotedFeeMinor`, `feeMinor`, `totalMinor`, `rateBps`, `minimum`, `quoteId`, `expiresAt` |

Amounts are strings in minor units, like everywhere in the wallet: the payment's coin for `balanceMinor`, `feeMinor`, `maxAmountMinor`, `quotedFeeMinor` and `totalMinor`; micro-dollars (USD, 6 decimals) for `limitMinor`, `usedMinor` and `leftMinor`. For USDC both are its minor units, as before.

**Any held coin (2026-11-07).** A payment can be in any coin the wallet holds on its chain. Fees and limits stay in dollars: the fee is worked out on the payment's dollar value and charged **in the coin**, converted at the price feed's price and rounded up to the coin's minor unit; the limits count the payment (and its fee) at that price. A held W17 quote keeps the fee and the price, so a price move before signing changes nothing. A coin with no price right now is `wallet.asset_unpriced`; one not held on the chain `wallet.asset_not_held`.

| Route | Scope | Request → Response | Status | Notes |
| --- | --- | --- | --- | --- |
| `POST /v1/wallet/transfers/quote`<br>**W17** The fee for a payment before you sign it | `app` | PrepareTransferRequest → QuoteTransferResponse | **READY** | W4's body (`quoteId` ignored; send an `Idempotency-Key` like any POST). Runs W4's checks on the sender and payee (their errors are W4's), then answers `{ blockchain, amount, fee: FeeDto, total, balance, maxAmount, limit: LimitDto, problem?, fitsAt?, quoteId?, expiresAt, usdPrice?, totalUsd? }` (`usdPrice` and `totalUsd` since 2026-11-07). Nothing moves. `quoteId` only for the wallet's signer. Errors: `wallet.account_restricted`, `wallet.chain_unsupported`, `wallet.not_found`, `wallet.not_ready`, `wallet.not_on_chain`, `wallet.on_hold`, `wallet.amount_invalid`, `wallet.asset_not_held`, `wallet.asset_unpriced`, `wallet.self_transfer`, `wallet.support_account_has_no_wallet`, `users.not_reachable`, `wallet.recipient_no_wallet`, `wallet.recipient_not_on_chain`, `wallet.address_invalid`, `wallet.group_not_found`, `wallet.custody_unavailable` (the network cost couldn't be estimated: show GW13d), `request.malformed`. A server without phase 2F answers `request.not_found` (404): treat that as no fee. |

Types: `FeeDto { asset, amountMinor, amount, rateBps?, minimum?, ruleId?, ruleVersion? }` (W4's `fee` too; the same fields as an amount, so an old app reads it as one). `LimitDto { perPaymentMax?, dailyMax?, used, left?, windowHours, moreAt? }` (amounts as `AmountDto`; null caps are no cap). W4's request gains `quoteId`, its answer `total`.

**Not yet:** a live price feed for the native coins (configured prices for now, deliberately high; the network cost is converted with them); Solana fees (a second spike). Staff edit rules in the admin portal (`/v1/ops/fees`).

### Calls

Voice and video calls in a chat (direct or group), at most 8 people in a call at once. Each phone holds **one** WebRTC peer connection to Cloudflare Realtime's SFU; the server only brokers session descriptions and rings people. A chat has one call at a time. Media is encrypted in transit (DTLS-SRTP) but decrypted at the SFU: say **"Secure call"**, never "end-to-end encrypted" (decision 2026-10-29). Every state change sends an `Idempotency-Key`.

The flow: the caller **CL1** (gets `iceServers`), builds the peer connection with them, adds its tracks, creates an offer and **CL3** joins with it (set `answer` as the remote description). The callee gets `call.ringing` (or the push), reads the call with **CL2** (for `iceServers`) and joins the same way, or **CL6** declines. On every `call.updated`, **CL4** pulls the tracks of the people `JOINED` that you don't receive yet (by `userId` and `trackName` from `participants`); when `requiresRenegotiation`, set `offer` as the remote description, create an answer and send it with **CL5**. **CL8** for mute and camera, **CL7** to hang up. Name tracks uniquely in your own session, for example `audio-<yourUserId>` and `video-<yourUserId>`.

**CallResponse**: `callId`, `chatId`, `kind` (`VOICE` \| `VIDEO`), `state` (`RINGING` \| `ACTIVE` \| `ENDED`), `startedBy`, `createdAt`, `startedAt` (when a second person joined; null before), `endedAt`, `endReason` (`COMPLETED` \| `CANCELLED` \| `MISSED` \| `DECLINED`; null until ended), `participants` [{`userId`, `displayName`, `state` (`INVITED` \| `JOINED` \| `DECLINED` \| `MISSED` \| `LEFT`), `audio`, `video`, `tracks` [{`trackName`, `kind`}], `joinedAt`}], `iceServers` [{`urls`, `username`, `credential`}] (STUN always; TURN with credentials valid for a day when configured; empty in frames). Other people's SFU sessions never appear.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/calls`<br>**CL1** Call a chat | `app` | `{ "chatId", "video": false }` → CallResponse | **PARTIAL** | Everyone in the chat is `INVITED`, the caller too until they join (CL3). The others get `call.ringing`. A chat with a call ringing or active answers `calls.already_in_call` (409): fetch it with `GET /v1/chats/{chatId}/call` and join it. Errors: `calls.not_a_member`, `calls.already_in_call`, `request.malformed`. |
| `GET /v1/calls/{callId}`<br>**CL2** A call | `app` | — → CallResponse | **PARTIAL** | The chat's members only. Errors: `calls.not_found`, `calls.not_a_member`, `request.malformed`. |
| `GET /v1/chats/{chatId}/call`<br>**CL2** The chat's call going on | `app` | — → CallResponse | **PARTIAL** | The call `RINGING` or `ACTIVE` in the chat, for a group's "Join call" bar; `calls.not_found` (404) when there is none. Errors: `calls.not_found`, `calls.not_a_member`. |
| `POST /v1/calls/{callId}/join`<br>**CL3** Join with your offer | `app` | `{ "offer": "<sdp>", "tracks": [{ "mid": "0", "trackName": "audio-<userId>", "kind": "audio" }] }` → `{ "sessionId", "answer": "<sdp>", "call": CallResponse }` | **PARTIAL** | 1 to 4 tracks, `kind` `audio` or `video`, `mid` as in the offer. Keep `sessionId` for CL4 and CL5. The second person in makes the call `ACTIVE`; everyone gets `call.updated`. Joining again (after the app restarted, or after leaving a group call still going) gives a new session and drops the old one. A member who was `MISSED` or `DECLINED` in a group call can still join. Errors: `calls.not_found`, `calls.not_a_member`, `calls.call_ended`, `calls.call_full`, `calls.media_unavailable`, `request.malformed`. |
| `POST /v1/calls/{callId}/pull`<br>**CL4** Receive others' tracks | `app` | `{ "sessionId", "tracks": [{ "userId", "trackName" }] }` → `{ "offer": "<sdp>" \| null, "requiresRenegotiation": true, "tracks": [{ "userId", "trackName", "mid" }] }` | **PARTIAL** | The server finds each person's session. A track that isn't available (they left, or don't publish it) is left out of `tracks`; nothing available → `offer: null`, `requiresRenegotiation: false`. `mid` is the transceiver in the SFU's offer, to match the remote track to its tile. Errors: `calls.not_found`, `calls.call_ended`, `calls.not_in_call`, `calls.session_mismatch`, `calls.media_unavailable`, `request.malformed`. |
| `POST /v1/calls/{callId}/renegotiate`<br>**CL5** Answer the SFU's offer | `app` | `{ "sessionId", "answer": "<sdp>" }` → `{}` | **PARTIAL** | After a CL4 with `requiresRenegotiation`. Errors: as CL4. |
| `POST /v1/calls/{callId}/decline`<br>**CL6** Decline | `app` | — → CallResponse | **PARTIAL** | A direct call ends (`DECLINED`; both get `call.ended`). In a group only you become `DECLINED`; the call ends when nobody is left to ring or talk to. Declining a call that no longer rings you changes nothing; the caller declining is CL7. Errors: `calls.not_found`, `calls.call_ended`. |
| `POST /v1/calls/{callId}/leave`<br>**CL7** Hang up | `app` | — → CallResponse | **PARTIAL** | Your tracks are closed at the SFU. The caller hanging up before anyone answered → `CANCELLED`. Otherwise the call ends (`COMPLETED`) when fewer than two people are left in it. Everyone gets `call.ended` with the final call. Leaving an ended call returns it. Errors: `calls.not_found`. |
| `POST /v1/calls/{callId}/media`<br>**CL8** Microphone and camera | `app` | `{ "audio": true, "video": false }` → `{}` | **PARTIAL** | Sets `audio`/`video` on your tile for everyone (`call.updated`). Keep the tracks; stop sending on them (`track.enabled = false`). Errors: `calls.not_found`, `calls.call_ended`, `calls.not_in_call`. |
| `POST /v1/calls/{callId}/ring`<br>**CL9** Ring people again ("Add people") | `app` | `{ "userIds": [] }` → CallResponse | **PARTIAL** | Group calls only, by someone `JOINED`. Members who `MISSED`, `DECLINED` or `LEFT` become `INVITED` and get `call.ringing` (pushed when offline); `userIds` narrows it, empty is everyone not on the call. Each person's ring lasts 45 seconds from when they were rung. Errors: `calls.not_found`, `calls.call_ended`, `calls.not_in_call`, `calls.not_a_group`. |

**Timeouts.** A person rung for 45 seconds without answering is `MISSED`; a call nobody answered ends `MISSED` and a direct callee is pushed "Missed call from {name}". The server checks every 15 seconds, so show your own 45-second ring and stop at `call.ended`. An `ACTIVE` call with no join, leave, pull or media change for 2 hours is ended. **Pushes:** see `calls` frames in section 8. iOS: the ring arrives as an FCM alert with `mutable-content` (a Notification Service Extension can enrich it); CallKit's VoIP pushes (PushKit) are not sent yet.

### Account and security

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `PUT /v1/devices/current/push-token`<br>**I9** Store this phone's FCM or APNs token | `app` | PushTokenRequest → NoBody | **READY** | Send the FCM token on both platforms (on iOS, FCM's token, which Firebase maps to APNs). Used for message and group-add pushes (section 8). Update it whenever the platform rotates it. |
| `DELETE /v1/devices/current`<br>**I10** Forget this phone; the next sign-in verifies the number again | `app` | — → NoBody | **READY** | Forget this phone; the next sign-in verifies the number again. |
| `GET /v1/account/security`<br>**I11** Phone, linked sign-in accounts, this phone and verification status | `app` | — → SecurityOverviewResponse | **READY** |  |
| `POST /v1/account/providers`<br>**I12** Link a Google or Apple login | `app` | LinkProviderRequest → NoBody | **READY** | Link Google or Apple. Errors: `identity.provider_in_use`, `identity.nonce_invalid`. |
| `DELETE /v1/account/providers/{provider}`<br>**I12** Unlink a login, only if the other one stays | `app` | — → NoBody | **READY** | Only while the other login stays. Errors: `identity.provider_last`. |
| `GET /v1/account/security-log`<br>**I13** Security activity, newest first | `app` | — → SecurityLogResponse | **READY** | Query: `cursor`, `limit` (default 50, at most 200). Paged. |
| `DELETE /v1/account`<br>**I14** Schedule account deletion once every guard agrees | `app` | — → NoBody | **PARTIAL** | Schedules deletion: the account turns `DELETED` at once (sign-in answers `identity.account_deleted`) then 30 days later the server erases the account and its data (profile, contacts, blocks, invites, Asha chat), and other people stop seeing the person at once. There is no restore route yet: tell the user deletion is final after 30 days and can't be undone from the app. Revoking Apple access at deletion is not built. Refused while the wallet's US dollar stablecoins (USDC), added up over every chain, come to 0.01 or more, or it holds any amount of any other asset: tell the user to send it out first. Errors: `wallet.funds_remaining`, `wallet.custody_unavailable` (the wallet couldn't check: Circle down, or a wallet on a chain no longer configured; try again later). |

### Account recovery

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/recovery/requests`<br>**I15** Open a support case for a lost Google or Apple login | `onboarding` | RecoveryRequest → RecoveryResponse | **PARTIAL** | Opens a support case. The support-side tools to resolve it are not built, so nothing completes it yet. Errors: `identity.phone_invalid`, `identity.recovery_not_eligible`, `request.rate_limited`. |
| `wss://<host>/v1/assistant/voice/live`<br>**A11** Live voice with Asha (WebSocket) | `app` (in the first frame) | frames, see section 9 | **READY** | A second socket, open only while talking to Asha hands-free. Authenticate with the first frame, not a header, and send fresh tokens in a new `auth` frame before the access token's 15 minutes are up. Opus in, mulaw 8 kHz out. Swahili is refused (close reason `assistant.language_not_supported`): use A3 and the phone's own voice. Close reasons: `unauthorized` and `auth required` (1008, no frame), `Frame is too big: N` (1009, Ktor's reason, no frame), and after a `closing` frame `assistant.language_not_supported`, `assistant.quota_exceeded`, `assistant.model_unavailable`, `silence`, `ended`. |
| `GET /v1/recovery/requests/{requestId}`<br>**I17** Status of your own support case | `onboarding` | — → RecoveryResponse | **PARTIAL** | Status of your own case. |

### Messages and delivery

Every message is encrypted on the phone (libsignal); the server holds the ciphertext only until each recipient's phone acknowledges it, and 30 days at most. A direct message carries one copy per recipient in `recipients` (the other person); a group `MESSAGE` or `CONTROL` carries one `ciphertext` for every member (sender keys); a `SENDER_KEY` message hands your new sender key to chosen members, each their own copy over your 1:1 sessions. Ciphertext is standard base64, at most 64 KB decoded. Message ids are made by the server and sort in sending order.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/chats/{chatId}/messages`<br>**M12** Send one encrypted message | `app` | SendChatMessageRequest → SentMessageResponse | **READY** | Make `clientMessageId` (a UUID) on the phone and keep it with the message: a retry with the same one, within 24 hours, returns the first `messageId` and sends nothing, so resend freely after a timeout. A direct message needs reach both ways. Set `pushable: true` on a `MESSAGE` to wake offline recipients (a data-only push; never for `SENDER_KEY` or `CONTROL`). Recipients get `message.new` (section 8). Errors: `messaging.chat_not_found`, `messaging.message_too_large`, `messaging.recipients_mismatch` (reload the chat's members), `users.not_reachable`, `request.malformed`. |
| `GET /v1/inbox`<br>**M13** Messages waiting for you, oldest first | `app` | — → InboxResponse | **READY** | `?after=<messageId>&limit=100` (at most 100). Call it after connecting the socket and after a push wake; page with `next` until it is absent. Each message has its ciphertext (a group's shared one included). |
| `POST /v1/inbox/ack`<br>**M13** Acknowledge messages you have | `app` | AckRequest → AckResponse | **READY** | 1 to 100 ids, once each message is decrypted and saved to your own history. The messages leave your inbox and their senders get a delivered receipt. With the socket open, send the `ack` frame instead (section 8). `acked` counts the ids that were still waiting. |
| `DELETE /v1/chats/{chatId}/messages/{messageId}`<br>**M14** Delete a message for everyone | `app` | — → MessageDeletedResponse | **READY** | Sender only, within 48 hours of sending, delivered or not. Copies not yet delivered are dropped and the other members get `message.deleted`. Errors: `messaging.chat_not_found`, `messaging.delete_window_passed`. |
| `PATCH /v1/messaging/settings`<br>**M22** Read receipts and typing indicators on or off | `app` | MessagingSettingsRequest → MessagingSettingsResponse | **READY** | Both on until turned off; a field left out stays as it is, so `{}` reads the current settings. Off means nobody gets your `read` receipts / sees you typing. |

**A replaced phone.** When someone moves to a new phone, their old keys are deleted, everyone who shares a chat with them gets `keys.changed` ("Amina's security code changed": fetch their new bundle), and messages still waiting for the old phone are dropped: each sender gets `message.undeliverable` and should resend from its own copy once the new keys are there.

### Attachments

Photos, voice notes, video and files travel Signal-style. The phone makes a fresh random AES-256-GCM key for each file, encrypts it, and uploads only the ciphertext, straight to storage (R2) through a pre-signed URL; the server never streams file bytes and never sees the key. The **key, the digest (SHA-256 of the ciphertext) and the real content type go inside the E2EE message** that refers to the attachment: send it with M12 like any other message (put the `attachmentId` in the encrypted payload; `mediaIds` is stored as given and not checked). Recipients decrypt the message, call AT3, download, check the digest, then decrypt the file. Order: **AT1 → PUT → AT2 → M12**. No socket frames: the message is the notification. Every `POST` sends an `Idempotency-Key`.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/chats/{chatId}/attachments`<br>**AT1** Start an encrypted upload | `app` | `{ "sizeBytes": 123456, "kind": "image" }` → `{ "attachmentId", "uploadUrl", "uploadHeaders": { "Content-Type": "application/octet-stream" }, "expiresAt" }` | **READY** | Members of the chat only. `kind` is `image`, `voice`, `video` or `file` (lowercase). `sizeBytes` is the exact size of the **ciphertext** (with the GCM tag), 1 byte to 100 MiB (`MESSAGING_MAX_ATTACHMENT_BYTES`). Then `PUT` the ciphertext bytes to `uploadUrl` with exactly the `uploadHeaders` (no auth header, no envelope), streamed from a file, starting before `expiresAt` (an hour); on expiry start again with AT1. The file is deleted 30 days after this call, or after a day if never completed. Errors: `messaging.chat_not_found` (no such chat, or you're not in it), `messaging.attachment_too_large`, `request.malformed`. |
| `POST /v1/attachments/{attachmentId}/complete`<br>**AT2** Complete the upload | `app` | — → `{ "attachmentId", "sizeBytes" }` | **READY** | The uploader only, after the PUT returned 2xx. The server checks the object is there at exactly AT1's `sizeBytes`. Completing twice answers the same, so retry freely. Then send the M12 message. Errors: `messaging.attachment_incomplete` (nothing uploaded, or a different size: PUT again while the URL works, or start over), `messaging.attachment_not_found` (not yours, unknown or expired), `request.malformed`. |
| `GET /v1/attachments/{attachmentId}`<br>**AT3** Download link | `app` | — → `{ "downloadUrl", "expiresAt", "sizeBytes" }` | **READY** | Anyone **currently** in the attachment's chat, once it is complete. `GET` `downloadUrl` (no auth header) before `expiresAt` (15 minutes); don't store the link, ask again. Verify the digest from the message before decrypting. A group's current photo (M8 `avatarMediaId`) is the exception: it stays until replaced or cleared, then 24 hours more. After 30 days, or after you leave the chat, show "No longer available". Errors: `messaging.attachment_not_found` (also for a pending attachment or a chat you aren't in: the server doesn't say which), `request.malformed`. |

### Moments

Moments are statuses that live **24 hours** from `postedAt`, end to end encrypted the way WhatsApp's are (Moments API, decision 2026-11-03). Each poster has one **moments sender key** on the phone (a libsignal `SenderKeyDistributionMessage` under a fixed `distributionId` per poster); the body is encrypted **once** with `GroupCipher` and sent as `ciphertext`. Audience members who don't have the current distribution yet get a **key copy**: the distribution message encrypted over their ordinary Signal session, in `keyCopies`. When someone leaves the audience (a privacy change, a contact removed), rotate: a new distribution, and copies for everyone in the new audience with the next post. The phone chooses the audience from its own Circaso contacts and its privacy setting; the server never sees the address book and only checks that each person and the poster can reach each other both ways (`users.api.ReachPolicy`: no block either way, and each has the other saved). Show a moment only from people in your own contacts. Every state change sends an `Idempotency-Key`.

**The encrypted body** (inside `ciphertext`, JSON, the app's own format): `{ "v": 1, "kind": "TEXT|IMAGE|VIDEO|VOICE", "text", "background": "INK|METAL|PAPER|GRAPHITE", "style": "BOLD|REGULAR", "caption", "media": { "mediaId", "key", "digest", "mime", "sizeBytes", "durationMs", "width", "height" }, "thumb" }`. A file is encrypted on the phone with a fresh AES-256-GCM key, exactly as chat attachments (AT1–AT3); its key, digest, real type, the caption and a tiny blurred thumbnail travel only inside the body.

The flow for a photo: **ST1** (size of the ciphertext) → `PUT` the ciphertext to `uploadUrl` with exactly `uploadHeaders` → **ST2** → **ST3** with the `mediaId` in `mediaIds` and in the body, within an hour of ST1. Viewers get `status.posted`, read **ST4**, decrypt (with `keyCopy` first when there is one), call **ST8** for the file and **ST5** when they open it.

**Moment** (ST4's items): `momentId`, `posterId`, `ciphertext`, `keyCopy` (your copy of the poster's sender key when one came with this moment, else null), `mediaIds` (0 or 1), `postedAt`, `expiresAt`, `mine`, `viewed`, `reacted` (= `reaction != null`), `reaction` (your own emoji on someone else's moment, else null), `viewCount` and `reactionCount` (how many viewed it and how many of them reacted: numbers on your own moments only, null on others').

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/moments/media`<br>**ST1** Start an encrypted moment upload | `app` | `{ "sizeBytes": 123456, "kind": "image" }` → `{ "mediaId", "uploadUrl", "uploadHeaders": { "Content-Type": "application/octet-stream" }, "expiresAt" }` | **READY** | `kind` is `image`, `video` or `voice` (lowercase). `sizeBytes` is the exact size of the ciphertext (with the GCM tag), 1 byte to 32 MiB. `PUT` to `uploadUrl` (no auth header) before `expiresAt` (15 minutes). A file not posted with ST3 within an hour is deleted. Errors: `status.media_invalid` (size or kind), `request.malformed`. |
| `POST /v1/moments/media/{mediaId}/complete`<br>**ST2** Complete the upload | `app` | — → `{ "mediaId", "sizeBytes" }` | **READY** | Yours only, after the PUT returned 2xx. The object must be there at exactly ST1's `sizeBytes`. Completing twice answers the same. Errors: `status.media_invalid` (nothing uploaded, or another size: PUT again while the link works, or start over), `status.media_not_found` (not yours, unknown or past its hour), `request.malformed`. |
| `POST /v1/moments`<br>**ST3** Post a moment | `app` | `{ "clientMomentId", "ciphertext", "audience": [userId], "keyCopies": [{ "userId", "ciphertext" }], "mediaIds": [mediaId] }` → `{ "momentId", "postedAt", "expiresAt" }` | **READY** | Make `clientMomentId` (a UUID) on the phone: a retry with the same one returns the first answer and posts nothing. `ciphertext` and each key copy are standard base64; the body at most 64 KiB decoded. `audience` 1 to 1,024 people, never yourself; people who can't reach you are dropped silently (a block is never revealed), with their key copies. `keyCopies` only for people in `audience`, one each. At most one **completed** file of yours that no other moment carries. At most 50 moments in 24 hours. The audience gets `status.posted`. Errors: `status.audience_invalid`, `status.moment_too_large`, `status.media_invalid` (more than one file), `status.media_not_found` (a file not completed, not yours, already used or past its hour), `status.too_many_moments` (429, `Retry-After` in seconds), `request.malformed`. |
| `GET /v1/moments`<br>**ST4** The moments you may see, and your own | `app` | — → `{ "moments": [Moment], "next" }` | **READY** | `?after=<momentId>&limit=50` (at most 200). Every live moment shown to you plus your own, by moment id, so oldest first within a poster: group them by `posterId` for the rings. Page with `next` until it is null. Call it on opening Moments and after `status.posted`. A poster you blocked (or who blocked you) since posting is left out. Errors: `request.malformed`. |
| `POST /v1/moments/{momentId}/view`<br>**ST5** Record that you viewed it | `app` | `{ "reaction": "😂" }` (optional; `{}` or no body) → `{ "viewed": true }` | **READY** | The audience only. The first call records the view; later ones change your reaction when they carry it. `reaction` is one emoji, 1 to 16 bytes of UTF-8 with no letters, digits or spaces (a keycap like `1️⃣` is fine): sending it sets or changes it, `"reaction": null` clears it, leaving it out keeps it. `reaction` wins over `reacted`; without it, `reacted: true` means `"❤️"` and `false` clears (older apps). Repeating the same call changes nothing and tells nobody. The poster gets `status.viewed` when something changed, and a push when you add or change a reaction while they're offline. Errors: `status.reaction_invalid` (400: not one emoji), `status.moment_not_found` (missing, expired, deleted, not shown to you, or a block since), `request.malformed` (`reaction` not a string or null, `reacted` not a boolean). |
| `GET /v1/moments/{momentId}/views`<br>**ST6** Who viewed your moment | `app` | — → `{ "views": [{ "userId", "viewedAt", "reacted", "reaction" }] }` | **READY** | Yours only, in the order people viewed it; `reaction` is their emoji or null. Errors: `status.not_poster` (403: you're in its audience but didn't post it), `status.moment_not_found`, `request.malformed`. |
| `DELETE /v1/moments/{momentId}`<br>**ST7** Delete your moment | `app` | — → `{ "deleted": true }` | **READY** | Yours only. The moment, its views and its file go; the audience gets `status.deleted`. Errors: `status.not_poster`, `status.moment_not_found`, `request.malformed`. |
| `GET /v1/moments/media/{mediaId}`<br>**ST8** Download link for a moment's file | `app` | — → `{ "downloadUrl", "expiresAt", "sizeBytes" }` | **READY** | The poster, or anyone in the audience of the live moment carrying it. `GET` `downloadUrl` (no auth header) before `expiresAt` (15 minutes); ask again rather than storing it. Verify the digest from the body before decrypting. Errors: `status.media_not_found` (also when you may not have it, or it is pending or expired: the server doesn't say which), `request.malformed`. |

**A new phone (key request).** A key copy is sealed for the Signal identity the person had then, and signing in on a new phone makes a new identity, so the moments already posted (and, until the poster's phone notices, the next ones) can't be opened there. A sender-key copy made now would not help: a sender key's chain only moves forward, so it opens later moments, never earlier ones. So the viewer asks, and each poster seals the **body** of each live moment again for them: the viewer calls `POST /v1/moments/keys/request` for posters whose live moments it can't open; each poster gets `status.key_requested`, fetches the requester's bundle (K3; a changed identity drops the old session), and sends `POST /v1/moments/key-copies` with, per live moment, a 1:1 message whose plaintext is `{ "type": "momentBody", "momentId", "text": "<the body JSON>" }`. It also forgets that the requester holds its sender key, so its next moment carries a sender-key copy again. The viewer gets `status.keys_ready`, reads ST4 and opens `keyCopy` over the 1:1 session: a `senderKey` payload is a sender-key copy, a `momentBody` payload is that moment's body. A poster's own moments posted from an earlier phone can't be recovered (no other phone of theirs holds them).

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/moments/keys/request`<br>**ST9** Ask posters for keys | `app` | `{ "posterIds": [userId] }` → `{ "requested": [userId] }` | **READY** | `posterIds` 1 to 100, distinct, never yourself. Each poster with a live moment shown to you, and no block either way, gets `status.key_requested` with your id, at most once every 10 minutes per poster; the others are skipped without a word. `requested` names the posters asked. A poster without a socket gets a silent push (`kind` `moment.keys_requested`, `userId` you; 2026-11-08), and replay brings it otherwise. Errors: `status.keys_invalid` (400), `request.malformed`. |
| `POST /v1/moments/key-copies`<br>**ST10** Send fresh copies | `app` | `{ "copies": [{ "momentId", "userId", "ciphertext" }], "answered": [userId] }` → `{ "copied": 2 }` | **READY** | Your answer to `status.key_requested`. Up to 200 copies, one per (moment, person), never yourself; `answered` (optional, up to 100, 2026-11-08) names whose request this answers, and they are told even with no copy (moments from your earlier phone), while one of your live moments is shown to them; at least one copy or one person; each standard base64, at most 72 KiB decoded. Each replaces that person's `keyCopy` on that moment. Copies for a moment that isn't yours or isn't live, for someone not in its audience, or across a block are skipped. Everyone who got one or is answered is sent `status.keys_ready`. Repeating it simply replaces the copies again. Errors: `status.keys_invalid` (400), `request.malformed`. |

**Retention.** `status.purge-expired` runs every 10 minutes and hard-deletes moments past `expiresAt` with their views and files, so reads hide a moment the moment it expires and the rows go within 10 minutes. A deleted account's moments are removed at once (its audiences get `status.deleted`).

### Share links

Every share in the app sends a **short link** instead of plain text (decision 2026-11-06): `<SHARE_BASE_URL>/s/{code}`, a public page showing what the sharer chose. Make the link with **SH1**, then open the system share sheet with the `url` and one short line (`title` is a good subject). The page is built on the server from its own records (the wallet's transfer, the chama's ledger, the profile), never from what the phone sends, so a Circaso receipt can't be forged. It holds only what the sharer chose: names (and a statement's notes) only with `showNames`, and **nothing from any chat** (chats and moments are end-to-end encrypted, so they are never shareable).

**Links open the app.** The page's host serves `/.well-known/assetlinks.json` (Android App Links, package `com.circaso.app`) and `/.well-known/apple-app-site-association` (iOS Universal Links, `985W5Z7984.com.circaso.app`, paths `/s/*`). With the app installed, `https://<host>/s/{code}` opens it; the page's "Open in Circaso" button is `circaso://s/{code}`. Either way the app calls **SH4** and shows the link natively, with what `open` allows. Without the app, the page has "Get Circaso".

**The page** is `noindex`, never cached (`Cache-Control: no-store`), carries Open Graph and Twitter tags for the link preview (title, one-line summary, the Circaso icon), follows light and dark mode, and loads no script. Each caller address may open 60 pages a minute (`429` page after that). A turned-off or ended link answers `410` "This link was turned off"; an unknown code `404`.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/share/links`<br>**SH1** Make a share link | `app` | `{ "kind": "receipt", "transferId", "chatId", "blockchain", "showNames": true, "currency": "KES", "expiresInDays": 30 }` → `{ "code", "url", "kind", "title", "summary", "createdAt", "expiresAt" }` | **READY** | `kind`: `receipt` (`transferId`: a payment you sent, received or signed, once submitted), `statement` (`chatId`: a group you're in with a target), `invite` (a fresh 7-day invite; the link ends with it), `pay_me` (needs your @handle) or `address` (`blockchain`: your own address there). `currency` (ISO 4217) adds the value in it. `expiresInDays` 1 to 365; without it the link opens until turned off. At most 30 an hour. Errors: `share.request_invalid`, `share.source_not_found` (not yours, or doesn't exist: the same answer), `share.not_shareable` (unsigned payment, no target, no handle), `share.unavailable`, `request.rate_limited`, `request.malformed`. |
| `GET /v1/share/links`<br>**SH2** Your share links | `app` | — → `{ "links": [link], "nextCursor" }` | **READY** | `?cursor=&limit=50` (at most 200). Links that still open, newest first. Errors: `request.malformed`. |
| `DELETE /v1/share/links/{code}`<br>**SH3** Turn a link off | `app` | — → `{ "revoked": true }` | **READY** | Yours only. What the page showed is deleted at once; the page then says the link was turned off. Errors: `share.link_not_found` (someone else's, unknown or already off). |
| `GET /v1/share/links/{code}`<br>**SH4** Open a link in the app | `app` | — → `{ "code", "url", "kind", "title", "summary", "createdAt", "expiresAt", "mine", "receipt", "statement", "person", "address", "open" }` | **READY** | One of `receipt` (`direction` sent/received, `amount`, `asset`, `fiat`, `status`, `blockchain`, `txHash`, `explorerUrl`, `at`, `fromName`, `toName`), `statement` (`goalName`, `asset`, `target`, `raised`, `paidOut`, `held`, `fiat` {currency, rate}, `memberCount`, `deadline`, `lines` [{`at`, `direction` in/out, `amount`, `name`, `note`}]), `person` (`displayName`, `handle`) or `address` (`blockchain`, `address`, `asset`, `name`). `open.screen`: `transfer` (`transferId`, your own payment), `chama` (`chatId`, a group you're in), `invite` (`inviteToken`, `userId`, `displayName`: connect with U11 `via: invite`), `pay` (`userId`, `handle`, `displayName`) or `pay_address` (`address`, `blockchain`); null when nowhere. Errors: `share.link_not_found` (unknown, off or ended). |

**Retention.** A turned-off or ended link's row stays 30 days (so its page says it was turned off), then `share.purge-expired` deletes it. A deleted account's links all go at once.

### Support tickets

A person asks Circaso for help from the app (decision 2026-11-08). A ticket has a short **subject**, a **description** of what happened and a **category**, and may name the one payment or chat it is about. Its **number** (`CS-000123`) is what the person reads out to support; show it on every ticket screen. Staff answer from the ops dashboard: their replies show as **"Circaso Support"** (never a person's name, id or email), and staff's internal notes never reach the app.

**Statuses.** `OPEN` (with us, `waitingOn: "STAFF"`), `PENDING_USER` (we replied: their turn, `waitingOn: "USER"`; badge it), `RESOLVED` and `CLOSED` (`waitingOn: null`). Replying on a `RESOLVED` ticket, or one waiting on the person, opens it again (`OPEN`); a `CLOSED` ticket takes no more replies (`support.ticket_closed`, 409): offer "Open a new ticket" instead.

| Call | Scope | Request → response | Status | What to know |
| --- | --- | --- | --- | --- |
| `POST /v1/support/tickets`<br>**SU1** Open a ticket | `app` | `{ "subject", "description", "category", "transferId"?, "chatId"? }` → TicketDetailResponse | **READY** | **Send an `Idempotency-Key`.** `subject` 3 to 120 characters (one line), `description` 10 to 4,000 (both trimmed). `category`: `ACCOUNT`, `PAYMENTS`, `WALLET`, `GROUPS`, `SECURITY`, `ASHA` or `OTHER`. `transferId`: a payment the person sent, received or signed (from W6 or a payment card); `chatId`: a chat they are in; anything else is `support.context_invalid`. **At most 5 new tickets a day** per person, counted from their first (Asha's count too): `support.rate_limited` (429) with `retryAfterSeconds`; the gateway also allows 10 SU1 calls an hour. Errors: `support.subject_invalid`, `support.description_invalid`, `support.category_invalid`, `support.context_invalid`, `support.rate_limited`, `request.malformed` (a missing field, an id that isn't a UUID). |
| `GET /v1/support/tickets`<br>**SU2** Your tickets | `app` | — → `{ "tickets": [TicketDto], "nextCursor" }` | **READY** | `?cursor=&limit=50` (at most 200). Newest first, all statuses, those Asha opened too (`source: "ASHA"`). Errors: `request.malformed` (a cursor you didn't get from us, a limit out of range). |
| `GET /v1/support/tickets/{ticketId}`<br>**SU3** Read a ticket | `app` | — → TicketDetailResponse | **READY** | The ticket, what the person wrote first (`description`) and the replies oldest first. Someone else's ticket, or an id that isn't one, answers `support.ticket_not_found` (404). |
| `POST /v1/support/tickets/{ticketId}/replies`<br>**SU4** Reply | `app` | `{ "body" }` → TicketDetailResponse | **READY** | **Send an `Idempotency-Key`.** `body` 1 to 4,000 characters. Reopens a `RESOLVED` or `PENDING_USER` ticket. Errors: `support.ticket_not_found`, `support.body_invalid`, `support.ticket_closed` (409), `request.malformed`. |

SU1 (the new ticket has no replies yet), SU3 and SU4 answer the same shape:
```json
{
  "ticket": {
    "ticketId": "0193f2c4-…",
    "number": "CS-000123",
    "subject": "Payment to Baraka not received",
    "category": "PAYMENTS",
    "status": "PENDING_USER",
    "source": "APP",
    "waitingOn": "USER",
    "transferId": "0193e1aa-…",
    "chatId": null,
    "createdAt": "2026-11-08T09:12:03.120Z",
    "lastMessageAt": "2026-11-08T10:40:51.004Z"
  },
  "description": "I sent 20 USDC on Base yesterday. My wallet says confirmed but Baraka has nothing.",
  "messages": [
    { "messageId": "0193f30b-…", "author": "STAFF", "authorName": "Circaso Support", "body": "Thanks! We're checking with the network. Did Baraka give you the address himself?", "createdAt": "2026-11-08T10:02:17.551Z" },
    { "messageId": "0193f32e-…", "author": "USER", "authorName": null, "body": "Yes, he sent it in our chat.", "createdAt": "2026-11-08T10:40:51.004Z" }
  ]
}
```
`author` is `USER` (the person), `STAFF` or `ASHA`; `authorName` is `"Circaso Support"` for staff, `"Asha"` for Asha and null for the person's own. `lastMessageAt` moves with every reply either way (sort and badge by it).

**Realtime and push.** Module `support`, replayed like `users` (send `{ "module": "support", "seq": … }` in `last`; a `resync` means reload SU2):

| `module` / `type` | `data` | Do |
| --- | --- | --- |
| `support` / `ticket.replied` | `ticketId`, `number`, `status` (`PENDING_USER`) | Staff replied: reload the ticket (SU3) if it is open, else mark it in the list. **Pushed** when the app has no socket: title "Circaso Support", body "We replied to your ticket CS-000123.", data `kind: "support.ticket"`, `ticketId`. The push never carries the reply's text; a tap opens the ticket. |
| `support` / `ticket.updated` | `ticketId`, `number`, `status` (`OPEN` \| `RESOLVED` \| `CLOSED`) | Staff resolved, closed or reopened it: update its status. No push. |

The person's own reply sends no frame (the SU4 answer is the new state). Staff assigning a ticket or writing an internal note sends nothing.

**Asha.** When Asha can't solve something herself, she offers a ticket, says what it will say, and opens it only after the person agrees (server tool `openSupportTicket`, `tool_status` label "Opening a support ticket…", at most once per answer). She tells them the number; the ticket appears in SU2 with `source: "ASHA"` and its replies arrive like any other. Her tickets count toward the same 5 a day.

**Retention.** Tickets stay until the account is deleted for good (`identity.AccountDeleted`, 30 days after the request), then go with their messages.

### Spaces (phase 3, Spaces core; branch `feat/spaces`, not deployed)

> **Spaces (archived 2026-11-05).** The feature is stopped and archived, not deleted; the apps remove it. The server
> keeps the code, the tables and their migrations, but Spaces is **off unless `SPACES_ENABLED=true`**. While off:
> every route under `/v1/spaces`, `/v1/ops/spaces` and `/v1/wallet/spaces` (SP1-SP37, SPO1-SPO2, W18-W19) answers
> **404 `spaces.unavailable`**; the `spaces.memberships` and `spaces.purge-expired` jobs, the `events:wallet` and
> `events:identity` consumers and the `spaces` replay aren't bound, so no `spaces` frame or push is sent; W16 lists no
> space wallet and nobody can see, sign for or pay one; a chat opened via a space (`viaSpaceId`) is an ordinary direct
> chat (the reach rule, both ways), and M4 with `viaSpaceId` opens none (`messaging.not_reachable`). Group wallets,
> fees, limits and Moments are not part of the archive. **To re-enable:** set `SPACES_ENABLED=true` and restart; the
> schema is still migrated on every deploy, so the data is where it was.

The full contract is the app repo's `.claude/skills/circaso-mobile/references/spaces-api.md` (SP1-SP37, SPO1-SPO2,
W18-W19, frames, pushes, `spaces.*` errors); the reader at `/docs` (tag `Spaces`) has every route. In short:

- **Spaces** (`/v1/spaces`): create (SP2, `SETTING_UP` until its wallet is active, then `LIVE`; 7-day account age, 3 a
  day), read by id or `@handle` (SP3, SP4), change (SP5; a price change is told to every member and applies from
  each one's next period), delete once the wallet is empty (SP6), yours (SP7), suggested (SP8), search by words or
  `@handle` (SP9; invite-only never listed).
- **Joining**: follow a public space (SP10), leave (SP11), notifications (SP12); a members-only space is joined by a
  payment (SP13 then W17 and W4 with `to: { spaceId }`, `spacePaymentId`, `quoteId`): a member only once the
  transfer is confirmed (`wallet.TransferConfirmed` → `space.membership_updated`). Reminder 3 days before the end (7
  for yearly), 3 days' grace, then ended (`spaces.memberships` job). Invite-only: requests (SP14-SP17, 30 days after a
  decline) and invites (SP18-SP22, 14 days; a decline is never told).
- **Moderation**: members and roles (SP23-SP27: owner, admins, moderators; mute, remove, ban), reports (SP35-SP37 to
  the space's moderators; Circaso's queue SPO1, removal SPO2, scope `ops`).
- **Space chat** (not end-to-end encrypted, stored on the server): posts with replies, files (SP33/SP34) and one-emoji
  reactions with counts, tombstones for removed posts, admins-only posting, paging and read markers (SP28-SP32).
- **Private chat with the owner**: M4 `POST /chats/direct` takes `viaSpaceId`; the chat is an ordinary end-to-end
  encrypted direct chat, separate from the pair's own, and every chat answer carries `viaSpaceId`. Allowed by the
  space's rule instead of reach (blocks still apply); a ban closes it both ways (`messaging.not_reachable`).
- **Space wallet** (owner type `SPACE`, option A): W18 `POST /wallet/spaces/{spaceId}` (owner), W19 `GET` (owner and
  admins); W1/W3/W5/W6/W12/W13 with `?walletId=`; W16 lists space wallets (signer first, then those you administer);
  W4/W17 `to: { spaceId }` (members only, or a membership payment) priced at the space fee rule (seed 1 %); the owner
  sends from it directly. Events carry `fromSpaceId`, `toSpaceId`, `spacePaymentId`; `WalletCreated` carries `spaceId`.
- **Realtime** module `spaces` (replayable 7 days): `space.post_new`, `space.post_updated`, `space.updated`,
  `space.membership_updated`, `space.price_changed`, `space.invited`, `space.invite_removed`,
  `space.invite_accepted`, `space.request_new`, `space.report_new`, `space.report_resolved`. Pushes carry `kind`
  `space.*` and `spaceId`.

## 8. Realtime socket

While the app is open, keep one WebSocket to `wss://<host>/v1/ws` (Ktor `WebSockets` plugin; the server pings every 25 seconds). The **first frame** must authenticate within a few seconds or the server closes it:
```json
{ "type": "auth", "accessToken": "<app token>", "last": [{ "module": "users", "seq": 1042 }] }
```
`last` lists the last `seq` the app saw for each module, one entry per module, for example `[{ "module": "users", "seq": 1042 }, { "module": "messaging", "seq": 17 }]` (omit it or send `[]` on first connect). Changed on 2026-10-27: it was an object `{ "users": 1042 }`, which is now refused. Only an **app**-scope token is accepted. The server then replays what was missed and sends live frames:
```json
{ "module": "users", "type": "contact.joined", "id": "0193…", "seq": 1043, "ts": "2026-10-24T18:07:12Z", "data": { "userId": "…", "displayName": "Amina" } }
```
`seq` rises per user per module: a gap means a missed frame. A `resync` frame means the gap is older than 7 days: reload that module over HTTP. `messaging` / `message.new` and `typing` are never replayed and carry `seq` 0: don't store 0 as your last `seq` (a missed message waits in `GET /v1/inbox`). Reconnect with backoff and send your `last` values again. Refresh the access token **before** reconnecting if it has expired.

**Close reasons.** When the server closes the socket, the close code and reason tell you what to do:

| Code | Reason | Do |
| --- | --- | --- |
| 1008 | `auth required` | The first frame wasn't `{"type":"auth",…}` or came after 10 seconds. Fix the client; reconnecting with the same frame fails again. |
| 1008 | `invalid last` | `last` isn't a list of `{ "module", "seq" }` with whole numbers of 0 or more, or names a module twice (`[{"module":"users","seq":"x"}]`, `[{"module":"users","seq":-1}]` and the old object form `{"users":5}` are refused). Send `[]` or valid entries. |
| 1008 | `unauthorized` | The token is expired, revoked or not an `app` token. Refresh, then reconnect; if refresh fails, show sign-in. |
| 1008 | `session revoked` | Comes right after a `system` / `session.revoked` frame. Clear tokens, show sign-in; don't reconnect. |
| 1009 | `Frame is too big: …` | You sent a frame over 64 KB. A client bug: don't resend it. |

Any other close (network drop, server restart) means reconnect with backoff.

**Frames that exist today (everything else is MISSING):**

| `module` / `type` | `data` | Do |
| --- | --- | --- |
| `system` / `session.revoked` | empty (`{}`) | The session ended: you signed out (on this socket's session), a new phone took over, or the account was closed. Clear tokens, show sign-in. The socket then closes with `session revoked`. |
| `users` / `contact.joined` | `userId`, `displayName` | Someone from your phone book joined: add them to the contacts list. |
| `users` / `connection.added` | `userId`, `displayName` | Someone connected with you (QR or invite). |
| `users` / `profile.updated` | `userId`, `version` (number) | A contact changed their profile: reload that profile if `version` is newer than yours. |
| `messaging` / `member.added` | `chatId`, `userIds` (everyone this action added), `byUserId` | One frame per member per action (a group created, or one add of several people), to every member and the people added. Send the newcomers your current sender key; if you are in `userIds`, load the chat (`GET /v1/chats/{chatId}`). Pushed to the people added when offline ("You were added to a group"). |
| `messaging` / `member.removed` | `chatId`, `userId` (the person removed or who left), `byUserId` | To every member and the person. Remaining phones make a new sender key before their next group message; if it is you, drop the chat. |
| `messaging` / `chat.updated` | `chatId`, `metaVersion` (number), `version` (number), `avatarMediaId` (string or null) | The sealed name, avatar, group photo, settings or roles changed (a role change, a handover, or the owner leaving): reload the chat if `version` is newer than yours. `avatarMediaId` is the group photo as it now is (fetch it with AT3 if it changed). |
| `messaging` / `message.new` | `messageId`, `chatId`, `senderId`, `kind`, `pushable`, `sentAt`; `mediaIds`, and `ciphertext` when it is at most 16 K base64 characters (`MESSAGING_FRAME_CIPHERTEXT_MAX_CHARS`; null otherwise) | A message for you. Without `ciphertext`, fetch it from `GET /v1/inbox`. Decrypt, save to history, then `ack`. Not replayed (`seq` 0). When offline and `pushable`, a data-only push with `chatId` and `messageId` wakes the app instead: fetch the inbox and show the notification yourself (iOS: in the Notification Service Extension, replacing the "New message" placeholder). |
| `messaging` / `receipt` | `chatId`, `receipts`: [{`messageId`, `userId`, `status` (`DELIVERED` \| `READ`), `at`}] | To the sender only. A `READ` receipt means read up to that message. Group receipts arrive batched every 5 seconds, `at` being when the batch was sent. Keep the furthest status per person. |
| `messaging` / `typing` | `chatId`, `userId`, `on` | Show or hide "typing…". Chats of 50 people or fewer only; not replayed (`seq` 0). Hide it yourself after a few seconds without a new one. |
| `messaging` / `message.deleted` | `chatId`, `messageId` | The sender deleted it for everyone: remove it. |
| `messaging` / `message.undeliverable` | `chatId`, `messageId`, `recipientId` | That person's phone was replaced before your message reached it: resend it to them from your copy once their new keys are published. |
| `messaging` / `keys.changed` | `userId` | Their phone and keys changed: show the security-code notice and start a new session with their new bundle. |
| `wallet` / `transfer.updated` | `transferId`, `state` (`SUBMITTED` \| `CONFIRMED` \| `FAILED`), `blockchain`, `amount` {`asset`, `amountMinor`, `amount`}, `txHash`?, `failureReason`? | To the sender each time a payment moves: update the payment's screen and chat card. Pushed when offline on `CONFIRMED` ("Payment sent") and `FAILED` ("Payment failed"). |
| `wallet` / `payment.received` | `transactionId`, `blockchain`, `amount`, `fromUserId`?, `fromName`?, `walletId`?, `ownerType`?, `spaceName`?, `transferId`?, `chatId`? | Money reached you on chain: refresh the balance (W1). `fromUserId` is null for an outside address. `transferId` and `chatId` (2026-11-05) name the Circaso payment that paid you and the chat it was sent from, when the server could tell for certain: show the chat's **Received** card from them (W5 `GET /v1/wallet/transfers/{transferId}` has the details), deduped with the end-to-end payment card by `transferId`. Null for an outside address, or when the sender had two payments it could be. Pushed: "Amina sent you money" (never the amount). |
| `wallet` / `wallet.ready` | `blockchain` | Your wallet turned `ACTIVE` (its first chain): reload W1 and stop polling. No push. |
| `wallet` / `setup.requested` | `fromUserId`, `fromName` | Someone wants to pay you and you have no wallet: offer "Create wallet" (W2). Pushed. |
| `calls` / `call.ringing` | `callId`, `chatId`, `kind` (`VOICE` \| `VIDEO`), `callerId`, `callerName` | Someone is calling the chat: show the incoming-call screen (ring for 45 seconds at most). To each person rung, not the caller. Offline: a data-only, high-priority push (TTL 45 s) with `kind: "call.ringing"`, `chatId`, `callId`, `callerName`, `callKind`. |
| `calls` / `call.updated` | CallResponse (with `iceServers` empty) | Someone joined, left, declined or muted, or the call turned `ACTIVE`: redraw the tiles, and pull (CL4) the tracks of anyone newly `JOINED`. To everyone the call rang. No push. |
| `calls` / `call.ended` | CallResponse (with `iceServers` empty) | The call is over (`endReason`): close the peer connection and the call screen. Offline: `MISSED` → data-only since 2026-11-06 (`kind: "call.missed"`, fallback text "Missed call from {name}"): show it yourself; `CANCELLED` while it was still ringing you → data-only (`kind: "call.ended"`), so stop ringing and show the missed call yourself. |
| `assistant` / `status` | `available` (true), `since` (ISO-8601) | Asha is back after maintenance (decision 2026-11-07): clear the "down for maintenance" state on her screens. Only to people who met her down, once each; not replayed (`seq` 0). Offline: a push, title "Asha is back", body "Ask me anything.", `kind: "asha.back"` (opens Asha), at most one per person per 6 hours. |
| `support` / `ticket.replied` | `ticketId`, `number`, `status` | Staff replied on one of your support tickets: reload it (SU3). Pushed ("Circaso Support", `kind: "support.ticket"`, `ticketId`), never with the text. |
| `support` / `ticket.updated` | `ticketId`, `number`, `status` | Staff resolved, closed or reopened a ticket: update it. No push. |
| `status` / `status.posted` | `momentId`, `posterId` | A moment was shown to you: read ST4 (from your last `after`) and show the poster's ring if they're in your contacts. No push. |
| `status` / `status.deleted` | `momentId`, `posterId` | The poster deleted it (or their account went): drop it and its file. No push. |
| `status` / `status.viewed` | `momentId`, `viewerId`, `viewedAt`, `reacted`, `reaction` (the emoji or null), `viewerName` (only when this frame adds or changes a reaction) | To the poster, the first time someone views your moment and whenever they set, change or clear their reaction: update the viewers list, `viewCount` and `reactionCount`. A frame that adds or changes a reaction is pushed when you're offline: "Name reacted 😂 to your moment", `kind` `moment.loved` (open my moments). |
| `status` / `status.key_requested` | `requesterId` | To a poster: someone in the audience of your live moments can't open them (a new phone). If they are still in your audience, refresh your 1:1 session to their current identity, seal each live moment's body for them and send `POST /v1/moments/key-copies`; forget that they hold your sender key; name them in `answered`, even with no copies. At most once every 10 minutes per pair. Pushed silently when you have no socket (`kind` `moment.keys_requested`, `userId` the requester). |
| `status` / `status.keys_ready` | `posterId` | To a viewer: that poster answered your key request. Read ST4 and open what you couldn't; what of theirs still won't open was posted from their earlier phone and never will open here. No push. |

**Frames the phone sends** (after the auth frame only; commands stay on HTTP): `{ "module": "messaging", "type": "ack", "data": { … } }`. The server hands each to the module it names and never answers it directly; a frame for a module that takes none is dropped. **Limits:** at most 100 frames per second per socket (frames over it are dropped silently, the socket stays open, so batch acks and throttle typing) and at most 64 KB per frame (a bigger frame closes the socket with 1009). Messaging takes three:

| `type` | `data` | Does |
| --- | --- | --- |
| `ack` | `messageIds` (1 to 100) | As `POST /v1/inbox/ack`: the messages leave your inbox and their senders get a delivered receipt. |
| `read` | `chatId`, `upToMessageId` | You read the chat up to that message: moves your read marker (forward only) and sends its sender a `READ` receipt, unless you turned read receipts off. `upToMessageId` must be the newest `MESSAGE` you received from someone else in that chat: your own messages, sender keys and control messages produce no receipt (decision 2026-10-27). |
| `typing` | `chatId`, `on` | Relayed to the chat's other members, never stored; repeats within 6 seconds are dropped. Send `on: true` while typing (at most every few seconds) and `on: false` when you stop. Not relayed in chats of more than 50 people, or when you turned typing off. |

## 9. Asha (the assistant)

**Status: READY.** The routes and event format below are final, and the dev server runs the real model, speech-to-text and voice.

- **Conversations (decision 2026-10-31):** the Asha section is a list of conversations, like a chat list, and each one is its own context: Asha reads only the conversation she is asked in (its last few exchanges on the free plan, many more on Asha Plus). The app pulls; there are no realtime frames for conversations.
  - List: `GET /v1/assistant/conversations?cursor=&limit=` →
    ```json
    { "conversations": [ { "conversationId": "0193a…", "title": "How much did I send Mum", "createdAt": "2026-10-31T08:00:00Z", "updatedAt": "2026-10-31T08:02:10Z", "lastMessageAt": "2026-10-31T08:02:10Z" } ], "nextCursor": "2026-10-31T08:02:10Z_0193a…" }
    ```
    Newest first by `lastMessageAt`. `nextCursor` is opaque: pass it back as `cursor`; null means the end.
  - New chat: `POST /v1/assistant/conversations` with `{}` (or `{ "title": "Budget" }`) and an `Idempotency-Key` → a `ConversationDto` with `title: "New chat"`. Open it at once; it is empty.
  - Rename: `PATCH /v1/assistant/conversations/{conversationId}` `{ "title": "December budget" }` → the `ConversationDto`.
  - Delete: `DELETE /v1/assistant/conversations/{conversationId}` → `data: null`. Its messages go with it.
  - Read one: `GET /v1/assistant/conversations/{conversationId}/messages?cursor=&limit=` → `ChatResponse`, newest first, exactly as A1.
  - **Titles** are made by the server from the conversation's first question when its first exchange is saved (at most six words, greetings like "Hi Asha" dropped): `"How much did I send Mum"`. Until then the title is `"New chat"`. A title the user set (C2, C3) is never replaced. The `done` event carries the conversation with its new title, so update the list row from it. The history from before conversations is one conversation, titled from its first question, or `"Earlier chat"`.
  - An id that isn't one of the user's conversations (another user's, or deleted) is `assistant.conversation_not_found` (404) everywhere.
- **History:** `GET /v1/assistant/chat` (newest first, paged) is every message of every conversation, each with its `conversationId`; delete one exchange (A6) or everything (A5, every conversation too).
- **Ask:** `POST /v1/assistant/chat/messages` `{ "text", "conversationId"?, "chatReading": true|false, "askedFrom"?, "currency"? }`. Send `conversationId` for the conversation on screen; without it the message goes to the user's most recent conversation, or a new one if they have none (what an older app gets). Send `X-Agent-Token: <agentSession.accessToken>` so her tools can read the user's own data (without it she has no lookups), and `currency`, the user's display currency (`"KES"`, ISO 4217; anything else is `request.malformed`), so she gives amounts in it. Asha reads only, and only this user's data: profile, contacts, blocked people, account security (no secrets), chat list and members (never messages; those are read on the phone through the device tools), wallet balances and addresses, payments with totals, chamas, her own allowance, coin market data, exchange rates, conversions and a calculator (2026-11-07: every number she states comes from a tool), and the public web. The answer is **`text/event-stream`**, not the envelope. Read the response body as a stream and parse `event:` and `data:` lines yourself (works on every Ktor engine). Errors before any text (a used-up allowance, a bad request) arrive as the normal envelope with a non-200 status.

| Event | `data` | Do |
| --- | --- | --- |
| `message` | `{"delta": "…"}` | Append the piece to the reply on screen. Pieces arrive as the model writes them (the first within a second or two), so show them at once. A piece may start with a blank line (`"\n\n…"`): Asha said something, looked something up, and carries on. |
| `mascot` | `{"scene","mood","activity","reaction","sensitive"}` | Asha's mascot (2026-11-04; see Asha's mascot below). Comes before the turn's first `message`, again when the hints change, and last with `activity: "done"` just before `done`. Example: `{"scene":"office","mood":"concerned","activity":"speaking","reaction":"payment_failed","sensitive":true}`. Additive: ignore it if you don't animate. |
| `tool_status` | `{"turnId","callId","tool","label","scene","mood","activity","reaction","sensitive"}` | Asha is about to look something up. Show `label` (the server's own short line, such as "Checking your payments for 2026-10…" or "Searching the web for “M-Pesa fees”…") as a status chip under the reply, with a spinner. Several can come in one turn, and since 2026-11-07 several can be open at once (Asha looks up independent things together): keep one chip per `callId` and clear each on its own `tool_done`, which may come in any order. Device tools get one too, just before their `tool_request`, after the other lookups of that step are done. |
| `tool_done` | `{"turnId","callId","tool","ok","scene","mood","activity","reaction","sensitive"}` | That lookup finished (`ok: false`: it failed, and Asha will say so). Clear or tick the chip with the same `callId`. A device tool's `tool_done` comes first on the A9 stream. Both events are additive: an app that doesn't know them can ignore them. The mascot fields (2026-11-04) say what she does meanwhile: `tool_status` `activity` is the tool's work, `tool_done` `activity` is `thinking`, and `reaction` is null on both. Example: `{"turnId":"0193d…","callId":"c1","tool":"listTransactions","label":"Checking your payments…","scene":"office","mood":"calm","activity":"reading_transactions","reaction":null,"sensitive":false}`. |
| `tool_request` | `{"turnId","callId","name","arguments","conversationId"}` | Asha needs something only the phone can do (search or read chats, which stay end-to-end encrypted). Run it locally, then `POST /v1/assistant/chat/messages/{turnId}/tool-results` `{ "callId", "snippets": [...] }` (at most 30 short snippets; answer within 10 minutes or get `assistant.turn_not_found`). That request's response is a new event stream that carries on the same reply: keep the text shown so far (what Asha said before asking, such as "Let me look in your chats.") and append to it. |
| `done` | `{"message": {…}, "conversationId": "…", "conversation": {…}}` | The reply is complete and saved in `conversationId`. `message.text` is the whole reply as shown, including any text before a `tool_request`: replace what you built from the pieces with it. `conversation` is the conversation as saved (a `ConversationDto`, with the title its first exchange gave it): update that row of the list. It is null only when the conversation was deleted while Asha answered; then nothing was saved. Example: `{"message":{"messageId":"0193b…","role":"ASHA","text":"You sent Mum KES 2,000.","replyTo":"0193a…","at":"2026-10-31T08:02:10Z","conversationId":"0193c…"},"conversationId":"0193c…","conversation":{"conversationId":"0193c…","title":"How much did I send Mum","createdAt":"2026-10-31T08:00:00Z","updatedAt":"2026-10-31T08:02:10Z","lastMessageAt":"2026-10-31T08:02:10Z"}}` |
| `error` | `{"code","message"}` | The model failed after some text had gone out (`assistant.model_unavailable`); nothing was saved. Show the failure on the half-written reply and let the user ask again. `assistant.unavailable` here (or as the envelope before any text) is Asha down for maintenance: show that state instead (below). |

- **Rich replies (text turns).** Asha writes markdown (prompt `asha-2026-11-04-v1`). Render the reply as markdown as it streams: headings (`###`), **bold**, lists, tables and links (web sources are cited as `[title](url)`). Plain chit-chat comes as plain text. On top of markdown there are three fenced blocks, each holding exactly one JSON object; the app draws them natively (no images, no image URLs, no HTML ever come):

  | Fence | What to draw |
  | --- | --- |
  | ```` ```chart ```` | A bar, line or pie chart |
  | ```` ```statement ```` | A statement or report card with a **Share as PDF** action (render the PDF on the phone) |
  | ```` ```diagram ```` | A small explanation drawn as simple hand-drawn (pencil-style) boxes and arrows, top to bottom |

  **The server checks every block before sending it** (decision 2026-10-31): a block is held back while the model writes it and goes out whole, in one `message` event, already in the exact shape below (canonical JSON, one line); a block the model wrote wrong is repaired when that is safe (case, trimming, caps, dropping points, rows, nodes or edges that don't read) or dropped entirely. So a block that arrives always parses and always matches these schemas, and you never see half a block mid-stream. Still be defensive: if a block fails to parse, show nothing for it. A fence with any other name (` ```kotlin `) is an ordinary code block. At most two blocks per reply, each introduced by a sentence. The `done` event's `message.text` contains the same blocks; that is also what A1 returns for history, so render history the same way. Fields marked `?` may be absent (never `null`).

  **chart**
  ```
  { "type": "bar" | "line" | "pie",
    "title": string,                  // may be ""
    "unit"?: string,                  // what the values are in: "KES", "USDC" (at most 12 chars)
    "series": [ { "name": string,     // may be ""
                  "points": [ { "label": string, "value": number } ] } ] }
  ```
  1 to 6 series (a pie has exactly 1), 1 to 60 points each, labels at most 40 characters, values finite numbers (a pie's are never negative). Example:
  ````
  ```chart
  {"type":"bar","title":"Money out by month","unit":"KES","series":[{"name":"Sent","points":[{"label":"Sep","value":4200.0},{"label":"Oct","value":3100.5}]}]}
  ```
  ````

  **statement**
  ```
  { "title": string,
    "period"?: { "from"?: "YYYY-MM-DD", "to"?: "YYYY-MM-DD" },
    "currency"?: string,              // ISO 4217, the currency of the "fiat" fields
    "rows": [ { "date": "YYYY-MM-DD",
                "description": string,             // may be "", at most 120 chars
                "direction"?: "IN" | "OUT",
                "amount": string,                  // plain decimal, exact: "20.5"
                "asset": string,                   // "USDC"
                "fiat"?: string,                   // plain decimal in "currency", at today's rate
                "status"?: "SUBMITTED" | "CONFIRMED" | "FAILED" | "PENDING",
                "reference"?: string } ],          // the payment id, at most 64 chars
    "totals": [ { "label": string, "amount": string, "asset"?: string, "fiat"?: string } ] }
  ```
  At most 200 rows and 10 totals; at least one of the two is non-empty. Amounts are strings so nothing is lost to floating point: format them for display yourself (thousands separators, the currency symbol). Example:
  ````
  ```statement
  {"title":"October 2026","period":{"from":"2026-10-01","to":"2026-10-31"},"currency":"KES","rows":[{"date":"2026-10-02","description":"Sent to Bob Otieno","direction":"OUT","amount":"20","asset":"USDC","fiat":"2590","status":"CONFIRMED","reference":"0192e3a4-…"}],"totals":[{"label":"Money out","amount":"20","asset":"USDC","fiat":"2590"}]}
  ```
  ````
  **Share as PDF:** lay the statement out as a page (title, period, the rows as a table, the totals, "Generated by Asha on <date>"; amounts as shown) and hand it to the share sheet. Asha only reads, so the PDF holds what the block holds.

  **diagram**
  ```
  { "title": string,                  // may be ""
    "nodes": [ { "id": string, "label": string } ],              // 2 to 8 nodes, unique ids, labels at most 40 chars
    "edges": [ { "from": string, "to": string, "label"?: string } ] }   // 1 to 12, between existing nodes, never a self-loop
  ```
  Lay it out top to bottom in node order, hand-drawn style (slightly wobbly boxes and arrows, the app's pencil look), edge labels beside their arrows. Asha uses it to explain how something works (how a payment travels, how a chama collects and pays out, what a network or a fee is), never for numbers. Example:
  ````
  ```diagram
  {"title":"How your payment to Mum travels","nodes":[{"id":"a","label":"You"},{"id":"b","label":"Your wallet"},{"id":"c","label":"Mum"}],"edges":[{"from":"a","to":"b","label":"PIN"},{"from":"b","to":"c","label":"20 USDC"}]}
  ```
  ````

  **Voice turns** (A11, and anything spoken) carry no blocks and no markdown: the server drops blocks and strips `*`, `` ` `` and heading marks from what is spoken. If the user asks for a statement by voice, Asha gives the headline and offers to show it in the chat.

- **Asha's mascot (2026-11-04).** Every turn tells the app how to animate Asha (or Robbie): a `mascot` event/frame, and the same fields on `tool_status` and `tool_done`. Fields, all lowercase strings from fixed lists: `scene` one of `none`, `beach`, `beach_sunset`, `gym`, `office`, `trading`, `driving`, `saving`, `support`, `celebration`, `running`, `shopping`, `home`, `school`, `crypto`; `mood` one of `calm`, `happy`, `excited`, `concerned`, `proud`, `thinking`, `sassy`, `curious`, `focused`, `caring`, `sleepy`, `apologetic`; `activity` one of `idle`, `listening`, `thinking`, `searching_web`, `reading_transactions`, `making_chart`, `writing_statement`, `speaking`, `done`, `reading_book`, `sipping`, `watching_sunset`, `lifting`, `driving`, `singing`, `jogging`, `carrying_bags`, `yoga`, `flexing`, `juggling`, `examining_coin`, `checking_receipt`, `building_sandcastle`; `reaction` null or one of `money_received`, `payment_sent`, `payment_failed`, `thanks`, `goal_reached`, `poke`, `idle_quirk`; `sensitive` a boolean. How the server picks them, with no extra model call: the model starts its reply with a `<mascot …/>` tag that the server takes out of the text (never shown, spoken or saved) and checks against the lists (anything else is dropped); the server then decides:
  - **Scene** is kept per conversation and changes only when the same new scene is proposed on two turns in a row, or the model marks a plain change of topic; a new conversation takes its first scene at once; the default is `office`.
  - **Activity** while a tool runs comes from the tool (web search `searching_web`; payments, single payments, totals and the wallet `reading_transactions`; anything else `thinking`), while a ```` ```chart ```` or ```` ```diagram ```` block is being written `making_chart`, a ```` ```statement ```` `writing_statement`; otherwise the model's choice or `speaking`; the turn ends with `done` (go back to idle yourself after it).
  - **`sensitive: true`** when the model says the turn touches hardship or the question reads like it (low balance, failed payments, debt, fees, struggling, bereavement, medical bills, in English or Swahili). Such a turn is never `sassy` (`caring` instead), nor is a `support` scene, and a conversation gets at most one sassy turn. Apply the user's sass setting on top.
  - **`reaction`** is a one-shot: it comes on one `mascot` event only (null elsewhere); play it once and return to the mood.
  - A turn without a tag still gets its `mascot` event (the conversation's scene, `calm` or `caring`, `speaking`). An app that ignores these events and fields keeps working.
- **Voice in:** `POST /v1/assistant/voice` with the raw audio as the body, `Content-Type` of the audio and `X-Duration-Seconds` (the clip's length in seconds; a decimal such as `5.5` is fine; up to 60 s, 3 MB) → `{ text }`; send that text through the chat route.
- **Live voice (hands-free):** open `wss://<host>/v1/assistant/voice/live` for the length of the conversation and close it when the user leaves the voice screen. Not when the language is Swahili: then record clips (Voice in) and speak replies with the phone's own text-to-speech.
  1. First frame within 10 s: `{ "type": "auth", "accessToken": "<app token>", "agentToken": "<agentSession.accessToken>", "language": "en", "chatReading": true, "currency": "KES", "voice": "harmonia", "conversationId": "0193c…" }` (`currency` optional, the display currency; one that isn't ISO 4217 is ignored. `voice` optional, the user's choice from A12; missing or unknown is the default voice, and the conversation keeps this voice to the end: a `voice` on a later `auth` frame is ignored. `conversationId` optional, the conversation the call is saved in: without it, the most recent one or a new one, as on A2, and every turn of the call stays in the conversation the first went to. Only the first `auth` frame's counts; one that isn't the user's makes each turn fail with an `error` frame `assistant.conversation_not_found`). An agent token, a bad, expired or revoked token, or no auth frame closes the socket with 1008 `unauthorized` / `auth required` and no frame.
  2. The server answers `{ "type": "ready", "voiceSecondsLeft": 118, "audio": { "encoding": "mulaw", "sampleRate": 8000 }, "voice": "harmonia" }` (`voice`: the voice Asha speaks in for this conversation). Then stream the microphone as **binary** frames of Opus (16 kHz mono, one packet per frame, about 24 kbit/s), continuously, including while Asha speaks (that is how barge-in works). Every frame is at most 64 KB both ways; a bigger one closes the socket with 1009 `Frame is too big: N` (Ktor's reason, N the frame's size) and no frame.
  3. Server text frames (JSON, routed on `type`):

| `type` | Fields | Do |
| --- | --- | --- |
| `transcript` | `text`, `final` | Show the live caption; `final: false` may still change. |
| `turn` | `text` | The question Asha is answering (also saved in the Asha chat). |
| `reply` | `text` | One sentence of the answer, sent about when the audio reaches it (show it as a caption; the timing is approximate). Each sentence goes to the voice as soon as the model has written it, so the first is heard while the rest is still being written. The audio comes as **binary** frames: mulaw (G.711 µ-law), mono, at `audio.sampleRate` (8 kHz), as `audio.encoding` says. Queue the audio and play it in order. |
| `reply_done` | `message` (as in A1, with its `conversationId`), `spoken` | The answer as saved, sent once its audio has finished playing; `spoken: false` means the user talked over it (show it, don't play it). |
| `tool_request` | `turnId`, `callId`, `name`, `arguments` | As on A2 (`arguments` is a JSON **string**): run the device tool, answer on this socket within 30 s with `{ "type": "tool_results", "turnId", "callId", "snippets": [...] }`. A frame that doesn't decode, or the wrong `callId`, gets `error` `request.malformed` and the turn keeps waiting: send it again. Later than that the turn ends with an `error` frame `assistant.turn_not_found`. |
| `tool_status` | `turnId`, `callId`, `tool`, `label`, `scene`, `mood`, `activity`, `reaction`, `sensitive` | As on A2: Asha is looking something up; show `label` while she does (she may also say a few words first). |
| `tool_done` | `turnId`, `callId`, `tool`, `ok`, `scene`, `mood`, `activity`, `reaction`, `sensitive` | As on A2: that lookup finished. |
| `mascot` | `scene`, `mood`, `activity`, `reaction`, `sensitive` | As on A2's `mascot` event (2026-11-04): before the turn's first `reply`, when the hints change, and last with `activity: "done"` right after `reply_done`. Example: `{"scene":"beach","mood":"excited","activity":"speaking","reaction":null,"sensitive":false,"type":"mascot"}`. |
| `interrupted` | — | The user talked over Asha (or the phone said so with `interrupt`): drop the audio queued and stop playing at once, and go to listening. Audio and `reply` frames that arrive between the phone's own `interrupt` and this frame are the old reply's: drop them. The cut-off reply still gets its `reply_done` with `spoken: false` (show it as talked over); the person's words lead the next `turn`. Since 2026-11-07 (second pass) it takes one clear word of the person: voice activity alone never stops her, and the words heard must include at least 1 that isn't a filler or backchannel (uh, um, hmm, mm, ah, oh, you, the, a, and, yeah, ok, okay, right, sure…), settled (a final, or an interim word kept by the next interim or already followed by another word), and not her own words heard back from the loudspeaker. While her voice plays or just stopped (1.5 s echo tail) that speech must also have lasted at least 300 ms on the transcriber's clock; while she is only thinking there is no minimum. Fillers, a lone first guess or a burst shorter than 300 ms while she plays are ignored: no `interrupted`, no transcript, no turn, not charged. The phone should stream the microphone the whole time, her turn included (no loudness gate: the platform echo canceller handles her voice and this rule decides), and either duck her playback the moment it hears the person locally (restoring it if no `interrupted` follows within about a second) or, with its own voice activity detector, stop her and send `interrupt` (below). |
| `error` | `code`, `message` | That answer failed (`assistant.model_unavailable`, `assistant.turn_not_found`, `assistant.conversation_not_found`, or `request.malformed` for a bad `tool_results`); the conversation goes on. |
| `timing` | `turn`, `modelFirstTextMs`, `firstSentenceMs`, `firstAudioMs` | Once per turn: milliseconds from the end of the user's speech to Asha's first words from the model, her first sentence sent to the voice, and the first of her audio sent to the phone. A step the turn never reached (talked over, failed) is `null`. For latency logs and debug overlays; nothing to show the user, and safe to ignore. |
| `closing` | `reason`, `message`?, `retryAfterSeconds`? | The server is closing; `reason` is also the WebSocket close reason. |

  Ignore any text frame whose `type` you don't know: new frame types are added without a new version (`timing` was added this way).

  4. Phone text frames besides `auth`, `tool_results` and `end`: **`{ "type": "interrupt", "at": "2026-11-07T10:00:00.120Z" }`** (since 2026-11-07). The phone's own barge-in: send it the moment the phone's voice activity detector is sure the person is talking over Asha (thinking or speaking), right after stopping her audio and going to listening. Handled exactly like a barge-in the server heard (generation and speech cancelled, `reply_done` with `spoken: false`, the person's next words are the next `turn`), and always answered with `interrupted`. `at` (optional) is the onset on the phone's clock, for latency logs only. Limited per conversation to 5 at once, then one every 3 s; extra ones are ignored. Until `interrupted` (or the next `turn`) arrives, drop incoming audio and `reply` frames: they belong to the reply you stopped.
  5. Access tokens last 15 minutes: before yours expires, refresh it over HTTP and send `{ "type": "auth", "accessToken": "<new app token>", "agentToken": "<new agent token>" }` on this socket. The server re-checks the session at each turn and every minute, and closes with 1008 `unauthorized` (no frame) once it is signed out, replaced or expired.
  6. To hang up send `{ "type": "end" }` (the server answers `closing` `ended`) or just close the socket. The server closes on its own after 30 s with nobody speaking (`silence`, 1000), when the voice allowance runs out (`assistant.quota_exceeded`, 1008, with `retryAfterSeconds`: show the Asha Plus offer), or when a speech provider fails (`assistant.model_unavailable`, 1011: fall back to clips). When Asha's model is down she says so in her voice and the server closes with `assistant.unavailable` (1013): let the line play, then show the maintenance state (below). Voice seconds count only speech: yours, timed by the transcriber from your first word to your last, and Asha's audio as it plays (what you talk over is free); an open mic and silence are free. The agent token is checked at every turn and every `auth` frame: it must be this user's own, or the socket closes with 1008 `unauthorized` (on A2 and A9 the error is `auth.token_invalid`). Audio is never stored.
- **Asha down for maintenance (2026-11-07).** Asha is Claude only, with no fallback model. When Claude can't answer (after one quick server retry) a turn fails with `assistant.unavailable`: the envelope or the `error` event on A2/A9, and on A11 a spoken line ("I'm down for maintenance at the moment. I'll let you know as soon as I'm back.") followed by `closing` `assistant.unavailable` (1013). Show a calm maintenance state on Asha's chat and voice screens (not an error toast, no code), disable the composer and voice, and keep the person's question. Clear it when A14 `GET /v1/assistant/status` says `available: true` (on open and resume, and every 30 s or so while it shows), or on the socket's `assistant` / `status` frame. The server probes Claude 30 s after the outage starts, then 30 s, 1 min and 2 min apart (2 min at most), and marks Asha back on the first answer or the first real turn that succeeds; everyone who met her down then gets one `asha.back` push ("Asha is back" / "Ask me anything."), at most one per 6 hours.
- **Voice out:** `POST /v1/assistant/speech` `{ "text", "language"?, "voice"? }` → audio bytes (not the envelope). English only; Swahili replies are spoken by the phone's own voice (asking the server gives `assistant.language_not_supported`).
- **Voice picker (Asha's voice):**
  1. `GET /v1/assistant/voices` → `{ "voices": [ { "id": "luna", "name": "Luna", "character": "Friendly", "gender": "female", "default": true }, … ] }`. Show `name` and `character`; preselect the user's saved choice, else the one with `default: true`.
  2. Tapping a voice plays `GET /v1/assistant/voices/{id}/sample` (MP3 bytes, free, the same line in each voice). Cache each clip on the phone for a day (the response says `max-age=86400`); stop the one playing when another is tapped.
  3. Save the chosen `id` on the phone (it is a device setting; there is no server-side preference) and send it as `voice` on every A4 call and on the first `auth` frame of A11. A saved id the server no longer lists simply gets the default voice; refresh the list when the picker opens.
- **Allowance (the usage menu):** `GET /v1/assistant/usage` → the plan, the day as `periodStart` / `periodEnd` (UTC midnight to midnight; `resetsAt` is `periodEnd`), and a meter each for `messages` (text questions), `voiceSeconds` and `searches` (web searches Asha ran), `{ "used", "limit" }` with `limit` null when there is none. The older flat fields (`textTurnsUsed`, `textTurnsLeft`, `voiceSecondsUsed`, `voiceSecondsLeft`) stay. Show what the server says; the free allowance is not decided yet, so hard-code nothing. `assistant.quota_exceeded` carries `retryAfterSeconds` for the reset. A search over the day's or the answer's cap is not an error: Asha says she couldn't check.
- **What Asha can look up (2026-11-05), all read-only:** profile, contacts, blocks, account security (sign-ins, devices and sessions, recent security events), chats as the server knows them (members, never messages), own and group wallets with balances, payments with totals and single payments, fees and limits for a payment being considered, today's value of USDC and USDT from the wallet's own rate, chamas (goal, progress, contributors, open requests and approvals, treasurer), own moments' views and reactions, her own allowance, and the public web. Chat messages only through the phone's `searchChats` / `readChat` when the user allows it. Values of our coins never come from the web, so they match the app's balances, payments and fees.
- The **agent** token only works on `GET /v1/agent/...` routes and exists for Asha's own tools; the app never calls those directly.

## 10. Not built yet, and a suggested order

**Backend MISSING (do not call, do not invent endpoints; stub the UI behind a feature flag):**
1. Chat history and the history-key backup (chats, sending, the inbox, receipts and attachments exist).
2. Statements (W7), PIN change and restore (W8), the payment hold after a support restore, chamas. The wallet (W1–W3, W12) and payments (W4, W5, W9–W11, wallet frames and pushes) and the payment history (W6) are in.
3. Calls: a call log message in the chat, CallKit VoIP pushes on iOS (PushKit), screen sharing. Calls themselves (CL1–CL8) are in.
4. Push for anything other than new messages, group adds, wallet payments and calls.
5. Notification inbox, identity verification (KYC).
6. Production device attestation (Play Integrity, App Attest) and real carrier phone verification (Firebase).
7. Chat history's handling of deleted accounts, and Asha reading your chats on the server (chat history slice).
8. Support tools that resolve account-recovery cases; restoring an account during its 30-day deletion window.
9. Realtime frames beyond those in section 8.
10. The support service that answers the support account's chats, and the support dashboard. Support accounts themselves exist (ops creates them; profiles carry `support: true`), but none is live yet, and reach to and from one is decided with the support service. Until then keep `Support.UserId`; once it ships, find support with U9 `@circaso`, check `support: true`, and drop the pinned id.

**Backend behaviours that will look odd on dev:** the carrier check accepts the number itself as the token; device attestation is only logged. Everything else is real: SMS, email and push really go out, so test with your own number, email and devices.

**Open product decisions that affect screens** (ask Pasaka before building UI for them): Asha's free allowance and paid plan; how a deleted account is restored within its 30 days (no route yet); what happens when a second Google or Apple login is linked (today it replaces the first); how the old phone cancels a number release; whether short Asha replies are cached.

**Suggested build order**
1. Project skeleton: Ktor client, envelope, headers interceptor, base-URL setting, `X-Client-Version`, force-update screen.
2. `DeviceKeyStore` on both platforms, with a unit test that signs a payload and verifies it (the section 5 formats are where most bugs hide).
3. Sign in with Google (flows A and D), then Apple on iOS.
4. Phone verification (flow B), device registration (flow C), refresh and sign-out (E, F). At this point a person can sign up and stay signed in.
5. Profile, @handle, avatar (G).
6. Contacts sync, contacts list, connections, invites, blocks, reach (H).
7. Realtime socket (section 8).
8. Asha chat with streaming and tool requests, then voice (section 9).
9. Account security screens, linked logins, recovery request.
10. Push: register the FCM token (I9) and handle the data-only message push (section 8).
11. Encrypted chats: keys (M1–M3), chats and groups (M4–M11), sending, inbox and receipts (M12–M14, section 8), then attachments (AT1–AT3).

**Definition of done for each screen:** every error `code` in the catalogue's *Errors* column has a message in the app; every `POST`/`PUT`/`PATCH`/`DELETE` sends an `Idempotency-Key`; no token, code, key or phone number is ever logged; retries reuse the same `Idempotency-Key`.


## 11. Schema appendix

Generated from the spec. `?` marks an optional field. Every response is wrapped in the envelope from section 3, and the type named in the catalogue is what sits in `data`.

- **AckRequest** — `messageIds`: [string] (1 to 100)
- **AckResponse** — `acked`: integer
- **ActiveDeviceDto** — `model`: string; `activeSince`: string
- **AddChainRequest** — `blockchain`: string
- **AddChainResponse** — `blockchain`: string; `status`: string (`ADDING` | `ENABLED`); `challengeId`?: string|null; `sdk`?: SdkDto|null
- **AddMembersRequest** — `memberIds`: [string]
- **AddOneTimeKeysRequest** — `oneTimePreKeys`: [OneTimePreKeyDto]
- **AgentContactDto** — `userId`: string; `displayName`: string; `handle`?: string|null
- **AgentContactsResponse** — `contacts`: [AgentContactDto]; `nextCursor`?: string|null
- **AmountDto** — `asset`: string (`USDC`); `amountMinor`: string; `amount`: string
- **AmountRequest** — `asset`: string (`USDC`); `amountMinor`?: string (digits); `amount`?: string (whole units, at most the asset's decimals); at least one, both only if they agree
- **AppleNameDto** — `given`?: string|null; `family`?: string|null
- **AskedFromDto** — `chatId`: string; `chatName`?: string|null
- **AssetDto** — `symbol`: string; `tokenAddress`: string (empty for a chain's own coin); `decimals`: integer; `amountMinor`: string; `amount`: string; `fiatAmount`?: string|null; `usdPrice`?: string|null (one unit in US dollars; display and typing only)
- **AssetTotalDto** — `symbol`: string; `decimals`: integer; `amountMinor`: string; `amount`: string; `usdPrice`?: string|null (null when the chains' prices differ)
- **AssistantStatusResponse** — `available`: boolean; `since`?: string|null (ISO-8601)
- **AttachmentCompletedResponse** — `attachmentId`: string; `sizeBytes`: integer
- **AttachmentDownloadResponse** — `downloadUrl`: string; `expiresAt`: string; `sizeBytes`: integer
- **AttachmentUploadResponse** — `attachmentId`: string; `uploadUrl`: string; `uploadHeaders`: UploadHeadersDto; `expiresAt`: string
- **BlocksResponse** — `blocks`: [PublicProfileDto2]
- **CarrierRequest** — `carrierToken`: string
- **ChainDto** — `blockchain`: string; `address`: string; `state`: string (`ACTIVE` | `FROZEN`); `assets`: [AssetDto]; `receiveEnabled`: boolean
- **ChainOptionDto** — `blockchain`: string; `assets`: [string]; `status`: string (`ENABLED` | `ADDING` | `AVAILABLE`); `receiveEnabled`: boolean
- **ChainsResponse** — `defaultChain`: string; `chains`: [ChainOptionDto]
- **ChatDto** — `chatId`: string; `kind`: string (`DIRECT` | `GROUP`); `sealedMeta`?: string|null; `metaVersion`: integer; `members`: [ChatMemberDto]; `settings`: ChatSettingsDto; `lastMessageId`?: string|null; `version`: integer; `created`?: boolean|null (M4 only); `notAdded`?: [string]|null (M5, M9 only); `avatarMediaId`?: string|null (the group photo, an attachment for AT3)
- **ChatMemberDto** — `user`: ChatProfileDto; `role`: string (`OWNER` | `ADMIN` | `MEMBER`); `joinedAt`: string
- **ChatMessageDto** — `messageId`: string; `role`: string; `text`: string; `replyTo`?: string|null; `askedFrom`?: string|null; `readNote`?: ReadNoteDto; `at`: string; `conversationId`: string
- **ChatProfileDto** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null; `support`?: boolean (default false)
- **ChatResponse** — `messages`: [ChatMessageDto]; `nextCursor`?: string|null
- **ChatSettingsDto** — `membersCanAdd`?: boolean
- **ChatsResponse** — `chats`: [ChatDto]
- **ClaimHandleRequest** — `handle`: string
- **ConnectRequest** — `via`: string; `value`: string
- **ConversationDto** — `conversationId`: string; `title`: string (`"New chat"` until titled); `createdAt`: string; `updatedAt`: string; `lastMessageAt`: string
- **ConversationsResponse** — `conversations`: [ConversationDto]; `nextCursor`?: string|null
- **ConnectResponse** — `user`: PublicProfileDto2; `created`: boolean
- **ContactDto** — `user`: PublicProfileDto2; `source`: string; `addedAt`: string
- **ContactsResponse** — `contacts`: [ContactDto]; `nextCursor`?: string|null
- **CounterpartyDto** — `userId`?: string|null; `displayName`?: string|null; `handle`?: string|null; `avatarUrl`?: string|null; `address`?: string|null
- **CreateConversationRequest** (C2; the body may be empty) — `title`?: string|null (1 to 80 characters)
- **CreateGroupRequest** — `sealedMeta`: string; `memberIds`?: [string]
- **DeletedResponse** — `deleted`: boolean
- **DoneEvent** (SSE `done`, A2 and A9) — `message`: ChatMessageDto; `conversationId`: string; `conversation`?: ConversationDto|null
- **CreateWalletResponse** — `status`: string (`SETTING_UP`); `challengeId`?: string|null; `sdk`: SdkDto
- **DeviceDto** — `deviceId`: string; `platform`: string; `model`: string; `osVersion`?: string|null; `appVersion`: string
- **DeviceProofDto** — `timestamp`: string; `signature`: string
- **ErrorDetailsDto** — `retryAfterSeconds`?: integer|null; `fields`?: [FieldErrorDto]
- **FiatDto** — `currency`: string; `amount`: string; `rate`: string; `asOf`: string; `partial`: boolean
- **FieldErrorDto** — `field`: string; `problem`: string
- **HandleAvailabilityResponse** — `handle`: string; `available`: boolean; `status`: string; `suggestion`?: string|null
- **HealthCheckStatus** — `name`: string; `status`: string
- **HealthReport** — `status`: string; `checks`?: [HealthCheckStatus]
- **HoldDto** — `until`: string
- **InboxMessageDto** — `messageId`: string; `chatId`: string; `senderId`: string; `kind`: string (`MESSAGE` | `SENDER_KEY` | `CONTROL`); `ciphertext`: string (base64); `mediaIds`: [string]; `sentAt`: string
- **InboxResponse** — `messages`: [InboxMessageDto]; `next`?: string|null
- **InviteResponse** — `token`: string; `url`: string; `qrPayload`: string; `expiresAt`: string
- **JwkDto** — `kty`?: string; `crv`?: string; `use`?: string; `alg`?: string; `kid`: string; `x`: string
- **JwksResponse** — `keys`: [JwkDto]
- **KeyBundleResponse** — `userId`: string; `identityKey`: string; `signedPreKey`: SignedPreKeyDto; `kyberPreKey`: KyberPreKeyDto; `oneTimePreKey`?: OneTimePreKeyDto|null
- **KeyCopyDto** — `userId`: string; `ciphertext`: string (base64)
- **KeyStatusResponse** — `oneTimePreKeysLeft`: integer; `signedPreKeyAt`: string
- **KyberPreKeyDto** — `keyId`: integer; `publicKey`: string (ML-KEM 1024, up to 2,048 bytes decoded); `signature`: string
- **LinkProviderRequest** — `provider`: string; `idToken`: string; `nonce`: string
- **MediaCompletedResponse** — `mediaId`: string; `sizeBytes`: integer
- **MediaDownloadResponse** — `downloadUrl`: string; `expiresAt`: string; `sizeBytes`: integer
- **MediaUploadResponse** — `mediaId`: string; `uploadUrl`: string; `uploadHeaders`: UploadHeadersDto; `expiresAt`: string
- **MemberRemovedResponse** — `removed`: boolean
- **MessageDeletedResponse** — `deleted`: boolean
- **MessagingSettingsRequest** — `readReceipts`?: boolean|null; `typing`?: boolean|null
- **MessagingSettingsResponse** — `readReceipts`: boolean; `typing`: boolean
- **MomentDto** — `momentId`: string; `posterId`: string; `ciphertext`: string; `keyCopy`: string|null; `mediaIds`: [string]; `postedAt`: string; `expiresAt`: string; `mine`: boolean; `viewed`: boolean; `reacted`: boolean; `reaction`: string|null; `viewCount`: integer|null; `reactionCount`: integer|null
- **MomentsResponse** — `moments`: [MomentDto]; `next`: string|null
- **MyProfileResponse** — `userId`: string; `displayName`: string; `about`?: string|null; `handle`?: string|null; `handleChangeableAt`?: string|null; `avatarUrl`?: string|null; `privacy`: PrivacyDto; `version`: integer
- **NoBody** — `unused`?: string|null
- **NonceResponse** — `nonce`: string; `expiresInSeconds`: integer
- **OneTimePreKeyDto** — `keyId`: integer; `publicKey`: string
- **OneTimePreKeysLeftResponse** — `oneTimePreKeysLeft`: integer
- **OpenDirectChatRequest** — `userId`: string
- **OpenTicketRequest** (SU1) — `subject`: string (3 to 120); `description`: string (10 to 4,000); `category`: string (`ACCOUNT` | `PAYMENTS` | `WALLET` | `GROUPS` | `SECURITY` | `ASHA` | `OTHER`); `transferId`?: string|null; `chatId`?: string|null
- **PayeeDto** — `userId`?: string|null; `address`: string
- **PayeeRequest** — `userId`?: string; `address`?: string (exactly one)
- **PhoneVerifiedResponse** — `phoneVerified`: boolean; `maskedPhone`: string; `next`: string
- **PostMomentRequest** — `clientMomentId`: string (UUID); `ciphertext`: string (base64, at most 64 KiB decoded); `audience`: [string] (1 to 1024); `keyCopies`?: [KeyCopyDto]; `mediaIds`?: [string] (at most 1)
- **PostedMomentResponse** — `momentId`: string; `postedAt`: string; `expiresAt`: string
- **PrepareAttachmentRequest** — `sizeBytes`: integer (1 to 104857600); `kind`: string (`image` | `voice` | `video` | `file`)
- **PrepareMediaRequest** — `sizeBytes`: integer (1 to 33554432); `kind`: string (`image` | `video` | `voice`)
- **PrepareTransferRequest** — `blockchain`: string; `to`: PayeeRequest; `amount`: AmountRequest; `chatId`?: string; `proposalId`?: string (refused until Asha's proposals exist)
- **PrepareTransferResponse** — `transferId`: string; `state`: string; `blockchain`: string; `amount`: AmountDto; `challengeId`?: string|null; `sdk`?: SdkDto|null; `fee`: AmountDto; `expiresAt`: string
- **PrivacyChangeDto** — `avatar`?: string|null; `about`?: string|null; `handleLookup`?: boolean|null
- **PrivacyDto** — `avatar`: string; `about`: string; `handleLookup`: boolean
- **ProviderDto** — `provider`: string; `maskedEmail`?: string|null; `linkedAt`: string
- **PublicProfileDto** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null; `support`?: boolean (default false)
- **PublicProfileDto2** — `userId`: string; `displayName`: string; `handle`?: string|null; `avatarUrl`?: string|null; `about`?: string|null; `support`?: boolean (default false)
- **PublishKeysRequest** — `identityKey`: string; `signedPreKey`: SignedPreKeyDto; `kyberPreKey`: KyberPreKeyDto; `oneTimePreKeys`?: [OneTimePreKeyDto]
- **PushTokenRequest** — `pushToken`: string
- **ReachResponse** — `reachable`: boolean
- **ReadNoteDto** — `chatIds`: [string]; `chatNames`?: [string]; `messageCount`: integer
- **RecipientDto** — `userId`: string; `ciphertext`: string (base64)
- **RecipientResponse** — `userId`: string; `status`: string (`CAN_RECEIVE` | `NO_WALLET` | `NO_WALLET_ON_CHAIN`); `canReceive`: boolean; `blockchains`: [string]
- **RecoveryRequest** — `phone`: string; `contactEmail`: string; `note`?: string|null
- **RecoveryResponse** — `requestId`: string; `status`: string; `noticeSentAt`?: string|null; `relinkAfter`?: string|null
- **RefreshRequest** — `refreshToken`: string; `deviceId`: string; `timestamp`: string; `signature`: string
- **RefreshResponse** — `session`: SessionDto; `agentSession`: SessionDto
- **RenameConversationRequest** (C3) — `title`: string (1 to 80 characters)
- **RegisterDeviceRequest** — `deviceId`: string; `publicKey`: string; `keyAlgorithm`: string; `pushToken`?: string|null; `attestation`?: string|null; `model`?: string|null
- **RegisterDeviceResponse** — `user`: UserStateDto; `session`: SessionDto; `agentSession`: SessionDto; `replacedDevice`?: ReplacedDeviceDto
- **ReleaseRequest** — `verificationId`: string; `confirmAbandon`: boolean
- **ReleaseResponse** — `releaseAt`: string
- **RemindResponse** — `status`: string (`SENT` | `ALREADY_SENT` | `HAS_WALLET`)
- **RemovedResponse** — `removed`: boolean
- **ReplacedDeviceDto** — `model`: string; `lastSeenAt`: string
- **ReplyRequest** (SU4) — `body`: string (1 to 4,000)
- **SdkDto** — `appId`: string; `userToken`: string; `encryptionKey`: string; `expiresAt`: string
- **SecurityEventDto** — `eventId`: string; `type`: string; `deviceModel`?: string|null; `detail`?: string|null; `at`: string
- **SecurityLogResponse** — `events`: [SecurityEventDto]; `nextCursor`?: string|null
- **SecurityOverviewResponse** — `maskedPhone`?: string|null; `providers`: [ProviderDto]; `device`?: ActiveDeviceDto; `verification`: string
- **SendChatMessageRequest** — `clientMessageId`: string; `kind`: string (`MESSAGE` | `SENDER_KEY` | `CONTROL`); `ciphertext`?: string|null (a group's one ciphertext); `recipients`?: [RecipientDto] (direct, and `SENDER_KEY`); `mediaIds`?: [string]; `pushable`?: boolean (default false)
- **SendMessageRequest** — `text`: string; `conversationId`?: string; `askedFrom`?: AskedFromDto; `chatReading`?: boolean; `currency`?: string (ISO 4217, the user's display currency)
- **SentMessageResponse** — `messageId`: string; `sentAt`: string
- **TicketDetailResponse** (SU1, SU3, SU4) — `ticket`: TicketDto; `description`: string; `messages`: [TicketMessageDto]
- **TicketDto** — `ticketId`: string; `number`: string (`CS-000123`); `subject`: string; `category`: string; `status`: string (`OPEN` | `PENDING_USER` | `RESOLVED` | `CLOSED`); `source`: string (`APP` | `ASHA`); `waitingOn`?: string|null (`STAFF` | `USER`); `transferId`?: string|null; `chatId`?: string|null; `createdAt`: string; `lastMessageAt`: string
- **TicketMessageDto** — `messageId`: string; `author`: string (`USER` | `STAFF` | `ASHA`); `authorName`?: string|null; `body`: string; `createdAt`: string
- **TicketsResponse** (SU2) — `tickets`: [TicketDto]; `nextCursor`?: string|null
- **ToolRequestEvent** (SSE `tool_request`, A2 and A9) — `turnId`: string; `callId`: string; `name`: string; `arguments`: string (JSON); `conversationId`?: string
- **ToolStatusEvent** (SSE `tool_status`, A2 and A9) — `turnId`: string; `callId`: string; `tool`: string; `label`: string; `scene`?: string; `mood`?: string; `activity`?: string; `reaction`?: string|null; `sensitive`?: boolean
- **ToolDoneEvent** (SSE `tool_done`, A2 and A9) — `turnId`: string; `callId`: string; `tool`: string; `ok`: boolean; `scene`?: string; `mood`?: string; `activity`?: string; `reaction`?: string|null; `sensitive`?: boolean
- **MascotEvent** (SSE `mascot`, A2 and A9; A11 frame `type: "mascot"`) — `scene`: string; `mood`: string; `activity`: string; `reaction`?: string|null; `sensitive`: boolean
- **SessionDto** — `accessToken`: string; `refreshToken`?: string|null; `expiresInSeconds`: integer; `scope`: string
- **SessionDto2** — `accessToken`: string; `refreshToken`?: string|null; `expiresInSeconds`: integer; `scope`: string
- **SetAvatarRequest** — `objectKey`: string
- **SetRoleRequest** — `role`: string (`OWNER` | `ADMIN` | `MEMBER`)
- **SignInResponse** — `next`: string; `maskedPhone`?: string|null; `session`: SessionDto; `agentSession`?: SessionDto2
- **SignedPreKeyDto** — `keyId`: integer; `publicKey`: string; `signature`: string
- **SnippetDto** — `chatId`: string; `chatName`?: string|null; `sender`: string; `at`: string; `text`: string
- **SocialSignInRequest** — `provider`: string; `idToken`: string; `nonce`: string; `appleName`?: AppleNameDto; `device`: DeviceDto; `deviceProof`?: DeviceProofDto
- **SpeechRequest** — `text`: string; `language`?: string|null; `voice`?: string|null
- **StartPhoneRequest** — `phone`: string
- **StartPhoneResponse** — `verificationId`: string; `codeLength`: integer; `expiresInSeconds`: integer; `resendAfterSeconds`: integer
- **SyncMatchDto** — `phone`: string; `user`: PublicProfileDto2
- **SyncRequest** — `syncId`: string; `batch`?: integer; `final`?: boolean; `phones`: [string]
- **SyncResponse** — `matches`: [SyncMatchDto]
- **ToolResultsRequest** — `callId`: string; `snippets`?: [SnippetDto]
- **TransactionDto** — `id`: string; `transferId`?: string|null (`OUT`; on `IN` the Circaso payment that paid you, once linked, 2026-11-05); `direction`: string (`OUT` | `IN`); `state`: string (`SUBMITTED` | `CONFIRMED` | `FAILED`); `blockchain`: string; `amount`: AmountDto; `counterparty`: CounterpartyDto; `chatId`?: string|null; `origin`?: string|null (`APP` | `ASHA`; `OUT` only); `txHash`?: string|null; `occurredAt`: string
- **TransactionsResponse** — `transactions`: [TransactionDto]; `nextCursor`?: string|null
- **TranscriptResponse** — `text`: string; `seconds`: integer
- **TransferResponse** — `transferId`: string; `direction`: string (`OUT` | `IN`); `state`: string (`AWAITING_SIGNATURE` | `SUBMITTED` | `CONFIRMED` | `FAILED` | `EXPIRED`); `blockchain`: string; `from`: string; `to`: PayeeDto; `amount`: AmountDto; `fee`: AmountDto; `chatId`?: string|null; `origin`?: string|null (`APP` | `ASHA`; sender only); `proposalId`?: string|null (sender only); `txHash`?: string|null; `failureReason`?: string|null; `createdAt`: string; `updatedAt`: string; `expiresAt`: string
- **UpdateGroupRequest** — `sealedMeta`?: string|null; `settings`?: ChatSettingsDto|null; `version`: integer; `avatarMediaId`?: string|null (an id sets the group photo, `null` clears it, absent keeps it)
- **UpdateProfileRequest** — `displayName`?: string|null; `about`?: string|null; `privacy`?: PrivacyChangeDto; `version`: integer
- **UploadHeadersDto** — `Content-Type`: string (always `application/octet-stream`)
- **UploadUrlRequest** — `contentType`: string; `sizeBytes`: integer
- **UploadUrlResponse** — `uploadUrl`: string; `objectKey`: string; `expiresInSeconds`: integer
- **UsageResponse** — `plan`: string; `textTurnsUsed`: integer; `textTurnsLeft`: integer; `voiceSecondsUsed`: integer; `voiceSecondsLeft`: integer; `resetsAt`: string; `periodStart`: string (2026-11-05); `periodEnd`: string (2026-11-05); `messages`: UsageMeter; `voiceSeconds`: UsageMeter; `searches`: UsageMeter
- **UsageMeter** — `used`: integer; `limit`?: integer|null (null: no limit)
- **UserStateDto** — `userId`: string; `status`: string
- **ValidateAddressRequest** — `blockchain`: string; `address`: string
- **ValidateAddressResponse** — `blockchain`: string; `address`: string; `valid`: boolean
- **VerifyCodeRequest** — `verificationId`: string; `code`: string
- **ViewDto** — `userId`: string; `viewedAt`: string; `reacted`: boolean; `reaction`: string|null
- **ViewMomentRequest** — `reacted`?: boolean|null; `reaction`?: string|null (present and null clears; absent keeps)
- **ViewedResponse** — `viewed`: boolean
- **ViewsResponse** — `views`: [ViewDto]
- **VoiceDto** — `id`: string; `name`: string; `character`: string; `gender`: string (`female` | `male`); `default`: boolean
- **VoicesResponse** — `voices`: [VoiceDto]
- **WalletResponse** — `status`: string (`NONE` | `SETTING_UP` | `ACTIVE`); `chains`: [ChainDto]; `totals`: [AssetTotalDto]; `fiat`?: FiatDto|null; `hold`?: HoldDto|null
