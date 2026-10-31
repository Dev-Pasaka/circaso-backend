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
