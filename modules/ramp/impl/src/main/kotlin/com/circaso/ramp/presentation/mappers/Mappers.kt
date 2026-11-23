package com.circaso.ramp.presentation.mappers

import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampStatus
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.support.Amounts
import com.circaso.ramp.presentation.dto.KycResponse
import com.circaso.ramp.presentation.dto.KycSessionDto
import com.circaso.ramp.presentation.dto.LimitsDto
import com.circaso.ramp.presentation.dto.OrderResponse
import com.circaso.ramp.presentation.dto.OrdersResponse
import com.circaso.ramp.presentation.dto.QuoteRequest
import com.circaso.ramp.presentation.dto.QuoteResponse
import com.circaso.ramp.presentation.dto.RampStatusResponse
import com.circaso.ramp.presentation.dto.StartOnRampRequest
import com.circaso.ramp.presentation.dto.VerificationDto
import com.circaso.shared.FiatAmount
import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

internal fun <T> malformed(field: String, problem: String): Resource<T> =
    Resource.error(SharedError.Malformed(listOf(FieldError(field, problem))))

fun orderIdOf(raw: String?): Resource<RampOrderId> =
    raw?.let(RampOrderId::parse)?.let { Resource.success(it) } ?: malformed("orderId", "not an id")

/** A page's cursor on the wire: `<time>~<id>` of its last row. Anything else starts from the top. */
fun cursorOf(raw: String?): PageCursor? {
    val (at, id) = raw?.split(CURSOR_SEPARATOR)?.takeIf { it.size == 2 } ?: return null
    return runCatching { PageCursor(Instant.parse(at), UUID.fromString(id)) }.getOrNull()
}

fun cursor(at: Instant, id: UUID): String = "$at$CURSOR_SEPARATOR$id"

private const val CURSOR_SEPARATOR = "~"

/** Shape checks for R2: a known direction, and exactly one side given as an amount above zero. */
fun QuoteRequest.toCommand(userId: UserId, network: Network): Resource<QuoteCommand> {
    val side = Direction.entries.firstOrNull { it.name == direction } ?: return malformed("direction", "unknown")
    if (blockchain.isBlank()) return malformed("blockchain", "required")
    if ((fiat == null) == (crypto == null)) return Resource.error(RampError.AmountInvalid)

    val fiatMinor = fiat?.let { Amounts.parse(it.amountMinor, it.amount, FiatAmount.MINOR_DIGITS) }
    val assetMinor = crypto?.let { Amounts.parse(it.amountMinor, it.amount, RampSettings.ASSET_DECIMALS) }
    if (fiatMinor == null && assetMinor == null) return Resource.error(RampError.AmountInvalid)
    return Resource.success(QuoteCommand(userId, network, side, blockchain.trim().uppercase(), fiatMinor, assetMinor))
}

fun StartOnRampRequest.toCommand(userId: UserId, network: Network): Resource<StartOnRampCommand> {
    val quote = QuoteId.parse(quoteId) ?: return malformed("quoteId", "not an id")
    val number = PhoneNumber.parse(phone) ?: return malformed("phone", "not an E.164 number")
    return Resource.success(StartOnRampCommand(userId, network, quote, number))
}

fun Quote.toResponse() =
    QuoteResponse(
        quoteId = quoteId.toString(),
        direction = direction.name,
        network = network.name,
        blockchain = blockchain,
        pay = Amounts.fiat(fiat),
        receive = Amounts.usdc(assetMinor),
        rate = rate.stripTrailingZeros().toPlainString(),
        fee = Amounts.fiat(feeMinor),
        expiresAt = expiresAt.toString(),
    )

/** An order for its user: the payer number masked, the merchant by display name only. */
fun OrderView.toResponse() =
    OrderResponse(
        orderId = order.orderId.toString(),
        direction = order.direction.name,
        state = order.state.name,
        network = order.network.name,
        blockchain = order.blockchain,
        pay = Amounts.fiat(order.fiat),
        receive = Amounts.usdc(order.assetMinor),
        rate = order.rate.stripTrailingZeros().toPlainString(),
        fee = Amounts.fiat(order.feeMinor),
        maskedPhone = order.payerPhone.masked(),
        merchantName = merchantName,
        txHash = order.txHash,
        failureReason = order.failureReason,
        expiresAt = order.paymentDeadline.toString(),
        createdAt = order.createdAt.toString(),
        updatedAt = order.updatedAt.toString(),
    )

/** A page of orders; `next` is the cursor for the page after it, null on the last one. */
fun List<OrderView>.toResponse(limit: Int) =
    OrdersResponse(
        orders = map { it.toResponse() },
        next = lastOrNull()?.takeIf { size >= limit }?.let { cursor(it.order.createdAt, it.order.orderId.value) },
    )

/** The state without the link: R1's `verification`, RM2's `kyc`. */
fun KycView.toDto() =
    VerificationDto(
        status = state.name,
        required = required,
        canStart = canStart,
        reason = reason?.code,
        retryAt = retryAt?.toString(),
        submitted = submitted,
    )

/** R0 and RM3: the state, and the open check's link when there is one. */
fun KycView.toResponse() =
    KycResponse(
        status = state.name,
        required = required,
        canStart = canStart,
        reason = reason?.code,
        retryAt = retryAt?.toString(),
        submitted = submitted,
        session = link?.let { KycSessionDto(it.token, it.url, it.expiresAt?.toString()) },
    )

fun RampStatus.toResponse() =
    RampStatusResponse(
        canTrade = kyc != KycStanding.NONE && !blocked && accountActive && merchantOnline && openOrderId == null,
        network = network.name,
        kyc = kyc.name,
        accountActive = accountActive,
        blockchains = blockchains,
        merchantOnline = merchantOnline,
        offRampAvailable = false,
        openOrderId = openOrderId?.toString(),
        limits =
            LimitsDto(
                currency = limits.currency,
                minOrder = Amounts.fiat(limits.minOrderMinor),
                maxOrder = Amounts.fiat(limits.maxOrderMinor),
                daily = Amounts.fiat(limits.dailyMinor),
            ),
        feePercent = Amounts.percent(feeBps),
        verification = verification.toDto(),
        blocked = blocked,
    )
