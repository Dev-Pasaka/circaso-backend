package com.circaso.ramp.presentation.mappers

import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpCommand
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.ramp.domain.support.Amounts
import com.circaso.ramp.domain.support.MerchantTopUps
import com.circaso.ramp.presentation.dto.TopUpLimitsDto
import com.circaso.ramp.presentation.dto.TopUpRequest
import com.circaso.ramp.presentation.dto.TopUpResponse
import com.circaso.ramp.presentation.dto.TopUpsResponse
import com.circaso.shared.Resource

private const val TOPUP_PIN_LENGTH = 6

/** RM19's shape: a wallet we know, KES cents as digits, a 6-digit PIN when one is given. The range is the use case's. */
fun TopUpRequest.toCommand(): Resource<TopUpCommand> {
    val target =
        TopUpTarget.parse(wallet)
            ?: return malformed("wallet", "PAYHERO_SERVICE, FINOQ_SERVICE or FINOQ_PAYMENT")
    val amount = amountMinor.trim().toLongOrNull() ?: return malformed("amountMinor", "KES cents as digits")
    val code = pin?.trim()?.takeIf { it.isNotEmpty() }
    if (code != null && (code.length != TOPUP_PIN_LENGTH || !code.all { it in '0'..'9' })) {
        return malformed("pin", "must be 6 digits")
    }
    return Resource.success(TopUpCommand(target, amount, code))
}

fun TopUp.toResponse(): TopUpResponse {
    val before = balanceBeforeMinor
    val after = balanceAfterMinor
    return TopUpResponse(
        topupId = topUpId.toString(),
        wallet = target.name,
        state = state.name,
        failure = failure?.name,
        amount = Amounts.fiat(amountMinor),
        maskedPhone = payer.masked(),
        confirmedBy = confirmedBy?.name,
        receipt = receipt,
        balanceBefore = before?.let { Amounts.fiat(it) },
        balanceAfter = after?.let { Amounts.fiat(it) },
        added = if (before != null && after != null) Amounts.fiat(after - before) else null,
        outcome = outcome,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        closedAt = closedAt?.toString(),
    )
}

fun List<TopUp>.toTopUpsResponse(): TopUpsResponse =
    TopUpsResponse(
        topups = map { it.toResponse() },
        limits = TopUpLimitsDto(Amounts.fiat(MerchantTopUps.MIN_MINOR), Amounts.fiat(MerchantTopUps.MAX_MINOR)),
    )
