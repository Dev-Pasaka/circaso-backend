# Decision: pay any coin the wallet holds (2026-11-07)

**Status:** decided by Pasaka 2026-11-07 (premium standard); built on `feat/any-held-asset`.

## Problem

In the app a person tags a coin they hold (`#500 @eurc` in a chat) and Pay stays disabled. The server refused it:
`shared.Asset` was `enum { USDC(6), USDT(6) }`, so W17 (fee quote) and W4 (transfer) answered `wallet.amount_invalid`
for EURC, ETH, POL, SOL, WETH and every other coin, although the app already sends `amount: { asset, amountMinor }` in
the coin's own decimals on the chain that holds it, and W1 lists every coin the wallet holds.

## Decision

1. **Any coin the wallet holds on the payment's chain can be paid**, through the same W17 → W4 → Circle SDK flow, by a
   person or a group wallet's owner, to a Circaso user, a group (a contribution) or an outside address.
   - An asset offered on the chain (configuration) is found without calling Circle, as before.
   - Any other coin is read from Circle now (never the cache) among the wallet's holdings on that chain.
   - Only a **trusted** coin is paid, priced or put in the history (`CoinTrust`): an offered asset by its contract, the
     chain's own coin (no contract), or a token whose contract is on the known list (WETH, WBTC, cbBTC, EURC), known by
     the symbol we have for it. A token that only calls itself `EURC` is never trusted.
   - Circle is asked by contract (`tokenAddress` + `blockchain`) for a token, and by Circle's **token id** for the
     chain's own coin (it has no contract; the id is read from the wallet's balances). In a split batch a token leg is
     `(token, 0, transfer(to, amount))` and a native leg `(to, amount, 0x)`.
2. **One fee, gas included, priced in US dollars.** The rule works out the fee on the payment's dollar value exactly as
   before. For a coin that isn't a dollar stablecoin the fee is **charged in the coin being sent**: the dollar fee
   converted at the `CryptoPrices` feed price (CoinGecko, PR #48; dollar stablecoins at 1) and **rounded up** in the
   coin's minor units, so the coin never covers less than the dollar fee. The fee leg goes to the same fee address.
3. **The quote holds the converted fee and its price.** W4 with a held `quoteId` charges exactly that fee and counts
   the payment at that price, so a price move after the quote changes nothing that is signed. Without a held quote, a
   price fall that raises the fee in the coin is `wallet.fee_changed`, as any higher fee.
4. **No price, no payment.** A coin with no price right now (feed off or down, or untrusted) is refused with
   `wallet.asset_unpriced` (409); one the wallet doesn't hold above zero on the chain with `wallet.asset_not_held`
   (400); one the custody provider can't send with `wallet.asset_not_supported` (400). `wallet.amount_invalid` keeps
   its meaning: an amount that can't be read in the coin. An asset offered on the chain held at zero stays
   `wallet.insufficient_funds`, as USDC at zero always was.
5. **Limits count dollars at the same price.** One payment's cap and the rolling daily window count each payment's
   amount and fee in micro-dollars at its price; each transfer stores that weight (`usd_total_minor`).
6. **Nothing changes for USDC (and USDT).** A 6-decimal coin at one dollar converts by the identity: the same fee, the
   same paths, the same numbers, and the price feed is never asked.
7. **Group wallets.** A contribution in any coin is allowed (the group wallet receives that coin). The group's goal,
   `contributed` and `held` keep counting **in dollars**, a non-dollar coin at the price it was paid at
   (`TransferConfirmed.usdValueMinor`). USDT already counted 1:1, so this extends the same rule.

## Persistence: why symbol + decimals and numeric amounts, not a wider enum

A wider enum would need a release for every coin a person might hold, and the point is "whatever is in the wallet".
So `shared.Asset` is now a value (`symbol`, `decimals`) with `USDC` and `USDT` as constants, and `Money.amountMinor` is
a `BigInteger`: an 18-decimal coin passes a 64-bit integer at about 9 whole coins (9 POL is a few dollars), so a Long
would have made most native coins unpayable. Rows keep the symbol and add the decimals; amounts become
`numeric(78,0)` (a uint256 has 78 digits). Fees, limits and gas stay `Long` micro-dollars.

Forward-only migrations, every existing row valid (all USDC/USDT, 6 decimals, one dollar):

- **wallet `V8__any_held_coin.sql`**: `amount_minor`/`fee_minor` → `numeric(78,0)` on `transfers`, `transactions`,
  `fee_quotes`, `fee_ledger`; `asset_decimals smallint DEFAULT 6` on all four; `transfers.token_address`,
  `transfers.usd_price`, `transfers.usd_total_minor` (backfilled `amount + fee`); `fee_quotes.usd_price`,
  `fee_quotes.fee_usd_minor` (backfilled); `fee_ledger.usd_price`, `fee_ledger.fee_usd_minor` (backfilled).
- **chama `V4__paid_coin.sql`**: `paid_asset`, `paid_decimals`, `paid_amount_minor` on `contributions` and
  `direct_payouts` (the coin that actually moved; `amount_minor` stays micro-dollars).

## Open

- The chama frames for a contribution or payout in another coin name its dollar value as `USDC`; naming the coin paid
  (`paid_*` is stored) needs a field on `chama` frames and an app change.
- Asha's history summaries total each coin in its own units; their fiat values are given only for dollar stablecoins.
- The known-token list (`CoinTrust.KNOWN_TOKENS`) is hand-kept from issuers' lists; any other ERC-20/SPL is unpriced and
  so not payable until it is added.
- Solana still carries no fee leg (0 % rule there), so SOL and SPL tokens send as plain transfers, as USDC on Solana.
- Circle's native-coin transfer by token id and the native `executeBatch` leg are covered by contract tests against
  Circle's documented shapes; they need one device test on testnet (ETH on Base Sepolia, POL on Amoy) before mainnet.
