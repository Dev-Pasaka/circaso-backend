package com.circaso.wallet.presentation.mappers

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.Counterparty
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.HistoryEntry
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.support.Amounts
import com.circaso.wallet.presentation.dto.AmountDto
import com.circaso.wallet.presentation.dto.CounterpartyDto
import com.circaso.wallet.presentation.dto.TransactionDto
import com.circaso.wallet.presentation.dto.TransactionsResponse
import java.time.Instant
import java.time.YearMonth
import java.util.Base64
import java.util.UUID

/** Conventions → Pagination: `limit` defaults to 50, at most 200. */
private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200
private val MONTH = Regex("""\d{4}-\d{2}""")
private const val CURSOR_SEPARATOR = '|'

/**
 * W6's query string: `month` (`YYYY-MM`, optional), `cursor` (a `nextCursor` from this route) and `limit` (1 to 200;
 * more is read as 200). Anything else malformed is `request.malformed` on that field.
 */
fun historyQuery(
    userId: UserId,
    rawMonth: String?,
    rawCursor: String?,
    rawLimit: String?,
): Resource<HistoryQuery> {
    val month =
        rawMonth?.let { raw ->
            val parsed =
                raw
                    .trim()
                    .takeIf {
                        MONTH.matches(
                            it,
                        )
                    }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            parsed ?: return malformed("month", "is not a month as YYYY-MM")
        }
    val after = rawCursor?.let { decodeCursor(it) ?: return malformed("cursor", "is not a cursor from this list") }
    val limit =
        if (rawLimit == null) {
            DEFAULT_LIMIT
        } else {
            val parsed = rawLimit.trim().toIntOrNull()
            if (parsed == null || parsed < 1) return malformed("limit", "is not a number from 1 to $MAX_LIMIT")
            parsed.coerceAtMost(MAX_LIMIT)
        }
    return Resource.success(HistoryQuery(userId, month, after, limit))
}

/** The cursor is opaque to the app: the last row's time and id, base64url so it sits in a query string as is. */
internal fun encodeCursor(position: HistoryPosition): String =
    Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString("${position.occurredAt}$CURSOR_SEPARATOR${position.id}".toByteArray())

internal fun decodeCursor(raw: String): HistoryPosition? =
    runCatching {
        val text = String(Base64.getUrlDecoder().decode(raw.trim()))
        val at = text.substringBefore(CURSOR_SEPARATOR)
        val id = text.substringAfter(CURSOR_SEPARATOR, missingDelimiterValue = "")
        HistoryPosition(Instant.parse(at), UUID.fromString(id))
    }.getOrNull()

fun HistoryPage.toResponse() = TransactionsResponse(entries.map { it.toDto() }, next?.let(::encodeCursor))

/** `origin` only on `OUT`, as in W5: how the sender started a payment is the sender's to know. */
private fun HistoryEntry.toDto(): TransactionDto {
    val amount = Amounts.of(transaction.amount)
    return TransactionDto(
        id = transaction.id.toString(),
        transferId = transaction.transferId,
        direction = transaction.direction.name,
        state = transaction.state.name,
        blockchain = transaction.blockchain,
        amount = AmountDto(amount.asset, amount.amountMinor, amount.amount),
        counterparty = counterparty.toDto(),
        chatId = transaction.chatId?.toString(),
        origin = transaction.origin.name.takeIf { transaction.direction == Direction.OUT },
        txHash = transaction.txHash,
        occurredAt = transaction.occurredAt,
    )
}

private fun Counterparty.toDto() = CounterpartyDto(userId, displayName, handle, avatarUrl, address)
