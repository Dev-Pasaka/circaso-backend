package com.circaso.wallet.api

import kotlinx.serialization.Serializable

// What wallet's read-only agent routes (`/v1/agent/wallet/…`) answer. They live here, not in wallet's presentation,
// because the assistant reads them over HTTP with the agent token, as it reads users' (decision 2026-10-27, AI1b
// review; wallet's routes decision 2026-10-31). Amounts are strings in whole units of the asset ("20.5"), exact;
// `fiat` strings are in the currency asked for, at today's rate, rounded to its minor unit.

/** What the wallet holds of one asset, on one chain or (in `totals`) over every chain. */
@Serializable
data class AgentBalanceDto(
    val asset: String,
    val amount: String,
    val blockchain: String? = null,
    val fiat: String? = null,
)

/**
 * One chain the wallet is on, with its receiving address (public, safe to share). `receiveEnabled` is false on a
 * chain retired from receiving: its address must not be given out to be paid.
 */
@Serializable
data class AgentChainDto(
    val blockchain: String,
    val address: String,
    val state: String,
    val receiveEnabled: Boolean,
)

/** Today's rate: one US dollar is `usdRate` of `currency`, as published at `asOf` (ISO-8601). */
@Serializable
data class AgentRateDto(val currency: String, val usdRate: String, val asOf: String)

/**
 * The wallet's balances (W1's data, smaller). `status` is `NONE`, `SETTING_UP` or `ACTIVE`; `fiatTotal` is the
 * priced assets' worth in `rate.currency`, and `partial` says something held has no price.
 */
@Serializable
data class AgentWalletResponse(
    val status: String,
    val balances: List<AgentBalanceDto>,
    val totals: List<AgentBalanceDto>,
    val chains: List<AgentChainDto> = emptyList(),
    val fiatTotal: String? = null,
    val rate: AgentRateDto? = null,
    val partial: Boolean = false,
)

/** The other side of a payment: a person on Circaso (id, name, handle) and/or an on-chain address. */
@Serializable
data class AgentCounterpartyDto(
    val userId: String? = null,
    val displayName: String? = null,
    val handle: String? = null,
    val address: String? = null,
)

/**
 * One payment as the user's history shows it. `direction` is `IN` or `OUT`; `state` is `SUBMITTED` (on its way),
 * `CONFIRMED` or `FAILED`; `at` is ISO-8601 UTC; `origin` (`APP`, `ASHA`) only on `OUT`.
 */
@Serializable
data class AgentTransactionDto(
    val id: String,
    val direction: String,
    val state: String,
    val asset: String,
    val amount: String,
    val blockchain: String,
    val counterparty: AgentCounterpartyDto,
    val at: String,
    val fiat: String? = null,
    val chatId: String? = null,
    val transferId: String? = null,
    val origin: String? = null,
    val txHash: String? = null,
)

@Serializable
data class AgentTransactionsResponse(
    val transactions: List<AgentTransactionDto>,
    val nextCursor: String? = null,
    val rate: AgentRateDto? = null,
)

@Serializable
data class AgentTransactionResponse(val transaction: AgentTransactionDto, val rate: AgentRateDto? = null)

/** In and out of one asset over the range; `net` is in minus out. */
@Serializable
data class AgentFlowDto(
    val asset: String,
    val totalIn: String,
    val totalOut: String,
    val net: String,
    val countIn: Int,
    val countOut: Int,
    val fiatIn: String? = null,
    val fiatOut: String? = null,
)

@Serializable
data class AgentCounterpartyFlowDto(
    val counterparty: AgentCounterpartyDto,
    val asset: String,
    val totalIn: String,
    val totalOut: String,
    val count: Int,
)

/** One calendar month (`YYYY-MM`, UTC) of one asset. */
@Serializable
data class AgentMonthFlowDto(
    val month: String,
    val asset: String,
    val totalIn: String,
    val totalOut: String,
    val count: Int,
)

/**
 * Totals over the range (`from` inclusive, `until` exclusive, ISO-8601, null when open). Only `SUBMITTED` and
 * `CONFIRMED` payments count. `truncated` means more payments matched than were read: ask for a shorter range.
 */
@Serializable
data class AgentSummaryResponse(
    val from: String? = null,
    val until: String? = null,
    val count: Int,
    val totals: List<AgentFlowDto>,
    val topCounterparties: List<AgentCounterpartyFlowDto>,
    val byMonth: List<AgentMonthFlowDto>,
    val rate: AgentRateDto? = null,
    val truncated: Boolean = false,
)

/**
 * One asset's value (`/agent/wallet/values`, decision 2026-11-05) from the wallet's own rate, the one balances,
 * payments and fees are shown with. `priced` is false for an asset the wallet doesn't hold or price (then only
 * `asset` is set). `usdPrice` is one unit in US dollars; `unitValue` one unit and `value` the amount asked, in
 * `currency`.
 */
@Serializable
data class AgentAssetValueDto(
    val asset: String,
    val priced: Boolean,
    val usdPrice: String? = null,
    val unitValue: String? = null,
    val value: String? = null,
)

/**
 * `currency` is the one the values are in: the one asked, or `USD` when none was asked or no rate for it is known.
 * `source` is always `circaso`: these are the wallet's own numbers (the rate and the price feed the app's balances
 * use, decision 2026-11-06), never a lookup of their own. `rate` is null in US dollars.
 */
@Serializable
data class AgentAssetValuesResponse(
    val currency: String,
    val source: String,
    val values: List<AgentAssetValueDto>,
    val amount: String? = null,
    val rate: AgentRateDto? = null,
)

/**
 * Where the wallet stands against its limits: the most one payment may be, the most per rolling `windowHours`, what
 * went out in that window (`used`) and what is left (null when there is no such limit); `moreAt` is when the oldest
 * payment leaves the window. Amounts in whole units of the payment's asset.
 */
@Serializable
data class AgentLimitDto(
    val perPaymentMax: String? = null,
    val dailyMax: String? = null,
    val used: String,
    val left: String? = null,
    val windowHours: Int,
    val moreAt: String? = null,
)

/**
 * The fee for a payment that may never be made (`/agent/wallet/fee`, decision 2026-11-05): W17's numbers, no quote
 * held. `fee` is Circaso's one fee, network cost included (`rateBps` its rate, `minimum` true when the minimum fee set it,
 * `noFeeReason` why none is charged); `total` is amount plus fee; `maxAmount` the most that can be sent with its fee.
 * `problem` is `INSUFFICIENT_FUNDS`, `PER_PAYMENT_LIMIT` or `DAILY_LIMIT` (then `fitsAt`, when it would fit). The
 * `fiat*` values are in `rate.currency` at today's rate, when a currency was asked.
 */
@Serializable
data class AgentFeeQuoteResponse(
    val blockchain: String,
    val asset: String,
    val amount: String,
    val fee: String,
    val rateBps: Int,
    val minimum: Boolean,
    val total: String,
    val balance: String,
    val maxAmount: String,
    val limit: AgentLimitDto,
    val noFeeReason: String? = null,
    val problem: String? = null,
    val fitsAt: String? = null,
    val fiatAmount: String? = null,
    val fiatFee: String? = null,
    val fiatTotal: String? = null,
    val rate: AgentRateDto? = null,
)

/**
 * A wallet the user can see (`/agent/wallet/wallets`): `type` `PERSON` (their own) or `GROUP` (a group's, with its
 * `chatId`); `status` `NONE`, `SETTING_UP` or `ACTIVE`; `canSign` whether they are the one who sends from it; the
 * chains it can be paid on now. Pass `walletId` to `/agent/wallet` for its balances.
 */
@Serializable
data class AgentWalletListingDto(
    val walletId: String,
    val type: String,
    val status: String,
    val canSign: Boolean,
    val blockchains: List<String>,
    val chatId: String? = null,
)

@Serializable
data class AgentWalletsResponse(val wallets: List<AgentWalletListingDto>)
