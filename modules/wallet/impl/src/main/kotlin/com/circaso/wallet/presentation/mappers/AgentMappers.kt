package com.circaso.wallet.presentation.mappers

import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.AgentBalanceDto
import com.circaso.wallet.api.AgentChainDto
import com.circaso.wallet.api.AgentCounterpartyDto
import com.circaso.wallet.api.AgentCounterpartyFlowDto
import com.circaso.wallet.api.AgentFlowDto
import com.circaso.wallet.api.AgentMonthFlowDto
import com.circaso.wallet.api.AgentRateDto
import com.circaso.wallet.api.AgentSummaryResponse
import com.circaso.wallet.api.AgentTransactionDto
import com.circaso.wallet.api.AgentTransactionResponse
import com.circaso.wallet.api.AgentTransactionsResponse
import com.circaso.wallet.api.AgentWalletResponse
import com.circaso.wallet.domain.models.Counterparty
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.HistoryEntry
import com.circaso.wallet.domain.models.HistoryFilter
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.models.SummaryQuery
import com.circaso.wallet.domain.models.TransactionDetail
import com.circaso.wallet.domain.models.TransactionQuery
import com.circaso.wallet.domain.models.TransactionSummary
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.support.Addresses
import com.circaso.wallet.domain.support.Amounts
import com.circaso.wallet.domain.support.Valuation
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

// The agent routes' query strings (decision 2026-10-31). Shapes that don't read answer `request.malformed` on the
// field, so the assistant's model sees which argument it got wrong.

private const val AGENT_DEFAULT_LIMIT = 20
private const val AGENT_MAX_LIMIT = 50
private const val DEFAULT_TOP = 5
private const val MAX_TOP = 20
private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val MONTH = Regex("""\d{4}-\d{2}""")

/** The query parameters the agent's history routes read, as they came. */
data class AgentHistoryParams(
    val from: String? = null,
    val to: String? = null,
    val month: String? = null,
    val direction: String? = null,
    val counterparty: String? = null,
    val asset: String? = null,
    val blockchain: String? = null,
    val currency: String? = null,
    val cursor: String? = null,
    val limit: String? = null,
    val top: String? = null,
    /** A group wallet's id (2026-11-10): that wallet's history instead of the caller's own, for its members. */
    val walletId: String? = null,
)

/** `/agent/wallet/transactions`: W6's rows narrowed by the filter, at most 50 a page (default 20). */
fun AgentHistoryParams.toHistoryQuery(userId: UserId): Resource<HistoryQuery> {
    val range = range().orReturn { return it }
    val filter = filter().orReturn { return it }
    val code = currencyCode().orReturn { return it }
    val wallet = walletIdOf(walletId).orReturn { return it }
    val after = cursor?.let { decodeCursor(it) ?: return malformed("cursor", "is not a cursor from this list") }
    val size =
        limit
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 1 }
            ?.coerceAtMost(AGENT_MAX_LIMIT)
            ?: if (limit == null) AGENT_DEFAULT_LIMIT else return malformed("limit", "is not a number from 1 to 50")
    return Resource.success(HistoryQuery(userId, null, after, size, range.first, range.second, filter, code, wallet))
}

/** `/agent/wallet/transactions/summary`: totals over the range and filter. */
fun AgentHistoryParams.toSummaryQuery(userId: UserId): Resource<SummaryQuery> {
    val range = range().orReturn { return it }
    val filter = filter().orReturn { return it }
    val code = currencyCode().orReturn { return it }
    val wallet = walletIdOf(walletId).orReturn { return it }
    val count =
        top
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?.coerceAtMost(MAX_TOP)
            ?: if (top == null) DEFAULT_TOP else return malformed("top", "is not a number from 0 to 20")
    return Resource.success(SummaryQuery(userId, range.first, range.second, filter, code, count, wallet))
}

/** `/agent/wallet/transactions/{id}`. */
fun transactionQuery(
    userId: UserId,
    rawId: String?,
    currency: String?,
    walletId: String? = null,
): Resource<TransactionQuery> {
    val id =
        rawId?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            ?: return malformed("transactionId", "is not a payment id")
    val code = AgentHistoryParams(currency = currency).currencyCode().orReturn { return it }
    val wallet = walletIdOf(walletId).orReturn { return it }
    return Resource.success(TransactionQuery(userId, id, code, wallet))
}

/**
 * The time range as [from, until): `from` and `to` are dates (`YYYY-MM-DD`, UTC), both inclusive; `month`
 * (`YYYY-MM`) is that calendar month; given together, the overlap.
 */
private fun AgentHistoryParams.range(): Resource<Pair<Instant?, Instant?>> {
    val start = from?.let { dateOf(it) ?: return malformed("from", "is not a date as YYYY-MM-DD") }
    val end = to?.let { dateOf(it) ?: return malformed("to", "is not a date as YYYY-MM-DD") }
    val inMonth =
        month?.let {
            it.trim().takeIf(MONTH::matches)?.let { raw -> runCatching { YearMonth.parse(raw) }.getOrNull() }
                ?: return malformed("month", "is not a month as YYYY-MM")
        }
    val lower = listOfNotNull(start, inMonth?.atDay(1)).maxOrNull()
    val upper = listOfNotNull(end?.plusDays(1), inMonth?.plusMonths(1)?.atDay(1)).minOrNull()
    return Resource.success(lower?.let(::startOf) to upper?.let(::startOf))
}

private fun AgentHistoryParams.filter(): Resource<HistoryFilter> {
    val way =
        direction?.trim()?.uppercase()?.let { raw ->
            Direction.entries.firstOrNull { it.name == raw } ?: return malformed("direction", "is not IN or OUT")
        }
    // Any coin the history may hold (2026-11-07), by its symbol.
    val coin =
        asset?.trim()?.uppercase()?.let { raw ->
            raw.takeIf { SYMBOL.matches(it) } ?: return malformed("asset", "is not a coin's symbol")
        }
    val chain = blockchain?.let { chainOf(it) ?: return malformed("blockchain", "is not a blockchain name") }
    val other = counterparty?.trim()?.takeIf { it.isNotEmpty() }
    val person = other?.let { Uuid7.parse(it) }?.let(::UserId)
    val address = if (person == null) other?.let(Addresses::normalise) else null
    return Resource.success(HistoryFilter(way, person, address, coin, chain))
}

private fun AgentHistoryParams.currencyCode(): Resource<String?> = currencyCodeOf(currency)

/** A step's value, or the step's error handed to [fail] (which returns from the caller). */
private inline fun <T> Resource<T>.orReturn(fail: (Resource.Error) -> Nothing): T =
    when (this) {
        is Resource.Success -> data
        is Resource.Error -> fail(this)
    }

private fun dateOf(raw: String): LocalDate? =
    raw.trim().takeIf(DATE::matches)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun startOf(day: LocalDate): Instant = day.atStartOfDay().toInstant(ZoneOffset.UTC)

// ---- model → answer ----

fun WalletView.toAgentResponse(): AgentWalletResponse =
    AgentWalletResponse(
        status = status.name,
        balances =
            chains.flatMap { chain ->
                chain.holdings.map {
                    AgentBalanceDto(
                        asset = it.symbol,
                        amount = Amounts.whole(it.amountMinor, it.decimals),
                        blockchain = chain.wallet.blockchain,
                        fiat = it.fiatAmount?.toPlainString(),
                    )
                }
            },
        totals = totals.map { AgentBalanceDto(it.symbol, Amounts.whole(it.amountMinor, it.decimals)) },
        chains =
            chains.map {
                AgentChainDto(it.wallet.blockchain, it.wallet.address, it.wallet.state.name, it.receiveEnabled)
            },
        fiatTotal = fiat?.amount?.toPlainString(),
        rate = fiat?.let { AgentRateDto(it.currency, it.rate.toPlainString(), it.asOf.toString()) },
        partial = fiat?.partial ?: false,
    )

fun HistoryPage.toAgentResponse(): AgentTransactionsResponse =
    AgentTransactionsResponse(
        transactions = entries.map { it.toAgentDto(rate) },
        nextCursor = next?.let(::encodeCursor),
        rate = rate?.toAgentDto(),
    )

fun TransactionDetail.toAgentResponse(): AgentTransactionResponse =
    AgentTransactionResponse(entry.toAgentDto(rate), rate?.toAgentDto())

fun TransactionSummary.toAgentResponse(): AgentSummaryResponse =
    AgentSummaryResponse(
        from = from?.toString(),
        until = until?.toString(),
        count = count,
        totals =
            assets.map {
                AgentFlowDto(
                    asset = it.asset.symbol,
                    totalIn = whole(it.inMinor, it.asset),
                    totalOut = whole(it.outMinor, it.asset),
                    net = whole(it.inMinor - it.outMinor, it.asset),
                    countIn = it.inCount,
                    countOut = it.outCount,
                    fiatIn = rate?.let { r -> Valuation.fiatOf(Money(it.inMinor, it.asset), r)?.toPlainString() },
                    fiatOut = rate?.let { r -> Valuation.fiatOf(Money(it.outMinor, it.asset), r)?.toPlainString() },
                )
            },
        topCounterparties =
            topCounterparties.map {
                AgentCounterpartyFlowDto(
                    counterparty = it.counterparty.toAgentDto(),
                    asset = it.asset.symbol,
                    totalIn = whole(it.inMinor, it.asset),
                    totalOut = whole(it.outMinor, it.asset),
                    count = it.count,
                )
            },
        byMonth =
            months.map {
                AgentMonthFlowDto(
                    month = it.month.toString(),
                    asset = it.asset.symbol,
                    totalIn = whole(it.inMinor, it.asset),
                    totalOut = whole(it.outMinor, it.asset),
                    count = it.count,
                )
            },
        rate = rate?.toAgentDto(),
        truncated = truncated,
    )

/** Whole units of [asset], exact; a negative net keeps its sign. */
private fun whole(minor: BigInteger, asset: Asset): String =
    if (minor.signum() < 0) "-" + Amounts.whole(Money(-minor, asset)) else Amounts.whole(Money(minor, asset))

private fun HistoryEntry.toAgentDto(rate: UsdRate?): AgentTransactionDto =
    AgentTransactionDto(
        id = transaction.id.toString(),
        direction = transaction.direction.name,
        state = transaction.state.name,
        asset = transaction.amount.asset.symbol,
        amount = Amounts.whole(transaction.amount),
        blockchain = transaction.blockchain,
        counterparty = counterparty.toAgentDto(),
        at = transaction.occurredAt.toString(),
        fiat = rate?.let { Valuation.fiatOf(transaction.amount, it)?.toPlainString() },
        chatId = transaction.chatId?.toString(),
        transferId = transaction.transferId?.toString(),
        origin = transaction.origin.name.takeIf { transaction.direction == Direction.OUT },
        txHash = transaction.txHash,
    )

private fun Counterparty.toAgentDto() =
    AgentCounterpartyDto(userId?.toString(), displayName, handle, address, groupId = groupId?.toString())

private fun UsdRate.toAgentDto() = AgentRateDto(currency, rate.toPlainString(), asOf.toString())

/** A coin's symbol as the wallet keeps it: upper case letters, digits and dots, at most 32. */
private val SYMBOL = Regex("^[A-Z0-9.]{1,32}$")
