package com.circaso.ramp.presentation.mappers

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.domain.models.CallbackView
import com.circaso.ramp.domain.models.CodeSent
import com.circaso.ramp.domain.models.Earnings
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.OnlineView
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.models.Statement
import com.circaso.ramp.domain.models.StatementLine
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.ramp.domain.support.Amounts
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.presentation.dto.CallbackResponse
import com.circaso.ramp.presentation.dto.ChangePinRequest
import com.circaso.ramp.presentation.dto.EarningsResponse
import com.circaso.ramp.presentation.dto.GoOnlineRequest
import com.circaso.ramp.presentation.dto.MerchantFloatDto
import com.circaso.ramp.presentation.dto.MerchantOrderDto
import com.circaso.ramp.presentation.dto.MerchantOrdersResponse
import com.circaso.ramp.presentation.dto.MerchantPaymentsDto
import com.circaso.ramp.presentation.dto.MerchantResponse
import com.circaso.ramp.presentation.dto.MerchantStepsDto
import com.circaso.ramp.presentation.dto.MerchantWalletDto
import com.circaso.ramp.presentation.dto.OnlineResponse
import com.circaso.ramp.presentation.dto.PaymentsRequest
import com.circaso.ramp.presentation.dto.PaymentsSavedResponse
import com.circaso.ramp.presentation.dto.ProfileRequest
import com.circaso.ramp.presentation.dto.SignInCodeRequest
import com.circaso.ramp.presentation.dto.SignInCodeResponse
import com.circaso.ramp.presentation.dto.SignedInResponse
import com.circaso.ramp.presentation.dto.StatementLineDto
import com.circaso.ramp.presentation.dto.StatementResponse
import com.circaso.ramp.presentation.dto.StatementTotalsDto
import com.circaso.ramp.presentation.dto.SweepDto
import com.circaso.ramp.presentation.dto.VerifyCodeRequest
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Shape checks for the merchant portal's requests. A problem names the field as the request spells it.

private val EMAIL = Regex("""[^@\s]{1,64}@[^@\s]{1,190}\.[^@\s.]{2,}""")
private const val EMAIL_LENGTH = 254
private const val PIN_LENGTH = 6
private const val CODE_LENGTH = 6
private const val NAME_MIN = 2
private const val NAME_MAX = 60
private const val FIELD_MAX = 200
private const val MIN_MINUTES = 15
private const val MAX_MINUTES = 720

private fun String.isDigits(length: Int) = this.length == length && all { it in '0'..'9' }

/** The address as accounts are keyed: trimmed and lower case. */
private fun emailOf(raw: String): String? =
    raw.trim().lowercase().takeIf {
        it.length <= EMAIL_LENGTH &&
            EMAIL.matches(it)
    }

fun SignInCodeRequest.toEmail(): Resource<String> =
    emailOf(email)?.let { Resource.success(it) } ?: malformed("email", "not an email address")

/** MA2's email and code. */
fun VerifyCodeRequest.toSignIn(): Resource<Pair<String, String>> {
    val address = emailOf(email) ?: return malformed("email", "not an email address")
    val digits = code.trim()
    if (!digits.isDigits(CODE_LENGTH)) return malformed("code", "must be 6 digits")
    return Resource.success(address to digits)
}

fun ProfileRequest.toCommand(): Resource<ProfileCommand> {
    val name = displayName.trim()
    if (name.length !in NAME_MIN..NAME_MAX) return malformed("displayName", "must be $NAME_MIN to $NAME_MAX characters")
    val phone = PhoneNumber.parse(contactPhone) ?: return malformed("contactPhone", "not an E.164 number")
    return Resource.success(ProfileCommand(name, phone))
}

fun PaymentsRequest.toCommand(): Resource<PaymentAccountCommand> {
    val fields = listOf("username" to username.trim(), "password" to password, "channelId" to channelId.trim())
    fields.firstOrNull { (_, value) -> value.isEmpty() }?.let { (field, _) -> return malformed(field, "required") }
    fields
        .firstOrNull { (_, value) ->
            value.length > FIELD_MAX
        }?.let { (field, _) -> return malformed(field, "too long") }
    if (!pin.isDigits(PIN_LENGTH)) return malformed("pin", "must be 6 digits")
    val credentials = CollectionCredentials(username.trim(), password, channelId.trim())
    return Resource.success(PaymentAccountCommand(credentials, pin))
}

/** RM6's PIN and window. */
fun GoOnlineRequest.toWindow(): Resource<Pair<String, Int>> {
    if (!pin.isDigits(PIN_LENGTH)) return malformed("pin", "must be 6 digits")
    if (minutes !in MIN_MINUTES..MAX_MINUTES) return malformed("minutes", "must be $MIN_MINUTES to $MAX_MINUTES")
    return Resource.success(pin to minutes)
}

/** RM9's current and new PIN. */
fun ChangePinRequest.toPins(): Resource<Pair<String, String>> {
    if (!currentPin.isDigits(PIN_LENGTH)) return malformed("currentPin", "must be 6 digits")
    if (!newPin.isDigits(PIN_LENGTH)) return malformed("newPin", "must be 6 digits")
    if (newPin == currentPin) return malformed("newPin", "must differ from the current PIN")
    return Resource.success(currentPin to newPin)
}

/**
 * `?from=&to=`: Nairobi calendar days, inclusive. Neither given is null (the use case then takes this month); one
 * alone, a `to` before `from`, or more than a year is refused.
 */
fun periodOf(from: String?, to: String?): Resource<Period?> {
    if (from.isNullOrBlank() && to.isNullOrBlank()) return Resource.success(null)
    val first =
        from?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() } ?: return malformed("from", "not a date")
    val last =
        to?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() } ?: return malformed("to", "not a date")
    if (last.isBefore(first)) return malformed("to", "is before from")
    if (ChronoUnit.DAYS.between(first, last) >= Periods.MAX_DAYS) return malformed("to", "more than a year after from")
    return Resource.success(Period(first, last))
}

fun CodeSent.toResponse() = SignInCodeResponse(sent = true, retryAfterSeconds = retryAfterSeconds)

fun SignedIn.toResponse() = SignedInResponse(token, expiresAt.toString(), merchant.toResponse())

fun MerchantOverview.toResponse() =
    MerchantResponse(
        merchantId = merchant.merchantId.toString(),
        email = merchant.email,
        displayName = merchant.displayName.ifEmpty { null },
        contactPhone = merchant.contactPhone.ifEmpty { null },
        state = merchant.state.name,
        network = network.name,
        steps =
            MerchantStepsDto(
                profile = merchant.state != MerchantState.NEW,
                approved = merchant.state == MerchantState.APPROVED,
                wallet = wallet != null,
                payments = payments != null && paymentsCurrent,
            ),
        wallet = wallet?.let { MerchantWalletDto(it.blockchain, it.address) },
        float =
            float?.let {
                MerchantFloatDto(
                    balance = Amounts.usdc(it.balanceMinor),
                    reserved = Amounts.usdc(it.reservedMinor),
                    free = Amounts.usdc(it.freeMinor),
                )
            },
        payments =
            payments?.let {
                MerchantPaymentsDto(it.provider, connected = paymentsCurrent, it.channelId, it.callbackUrl)
            },
        pinSet = pinSet,
        online = OnlineResponse(online, merchant.onlineUntil?.takeIf { online }?.toString()),
        canGoOnline = canGoOnline,
        missing = missing,
    )

fun OnlineView.toResponse() = OnlineResponse(online, until?.toString())

fun CallbackView.toSavedResponse() = PaymentsSavedResponse(connected = true, callbackUrl = callbackUrl)

fun CallbackView.toResponse() = CallbackResponse(callbackUrl)

/** A page of the merchant's orders; `next` is the cursor for the page after it, null on the last one. */
fun List<Order>.toMerchantResponse(limit: Int) =
    MerchantOrdersResponse(
        orders =
            map {
                MerchantOrderDto(
                    orderId = it.orderId.toString(),
                    direction = it.direction.name,
                    state = it.state.name,
                    pay = Amounts.fiat(it.fiat),
                    receive = Amounts.usdc(it.assetMinor),
                    fee = Amounts.fiat(it.feeMinor),
                    maskedPhone = it.payerPhone.masked(),
                    createdAt = it.createdAt.toString(),
                    updatedAt = it.updatedAt.toString(),
                )
            },
        next = lastOrNull()?.takeIf { size >= limit }?.let { cursor(it.createdAt, it.orderId.value) },
    )

private fun Settlement.toDto() =
    SweepDto(
        settlementId = settlementId.toString(),
        day = day.toString(),
        amount = Amounts.usdc(amountMinor),
        state = state.name,
        orders = orders,
        txHash = txHash,
    )

fun Earnings.toResponse() =
    EarningsResponse(
        from = period.from.toString(),
        to = period.to.toString(),
        network = network.name,
        orders = totals.orders,
        collected = Amounts.fiat(totals.collectedMinor),
        sold = Amounts.usdc(totals.soldMinor),
        fees = Amounts.fiat(totals.feesMinor),
        yourShare = Amounts.fiat(totals.merchantMinor),
        circasoShare = Amounts.fiat(totals.platformMinor),
        circasoShareUsdc = Amounts.usdc(totals.platformAssetMinor),
        swept = Amounts.usdc(totals.sweptAssetMinor),
        held = Amounts.usdc(totals.heldAssetMinor),
        feeRatePercent = Amounts.percent(feeBps),
        yourSharePercent = Amounts.percent(merchantShareBps),
        sweeps = sweeps.map { it.toDto() },
    )

private fun StatementLine.toDto() =
    StatementLineDto(
        orderId = orderId.toString(),
        completedAt = completedAt.toString(),
        maskedPhone = payerPhone.masked(),
        paid = Amounts.fiat(paidMinor),
        released = Amounts.usdc(releasedMinor),
        rate = rate.stripTrailingZeros().toPlainString(),
        fee = Amounts.fiat(feeMinor),
        yourShare = Amounts.fiat(merchantMinor),
        circasoShare = Amounts.fiat(platformMinor),
        circasoShareUsdc = Amounts.usdc(platformAssetMinor),
        settlementId = settlementId?.toString(),
        txHash = txHash,
    )

private fun StatementTotals.toDto() =
    StatementTotalsDto(
        orders = orders,
        collected = Amounts.fiat(collectedMinor),
        sold = Amounts.usdc(soldMinor),
        fees = Amounts.fiat(feesMinor),
        yourShare = Amounts.fiat(merchantMinor),
        circasoShare = Amounts.fiat(platformMinor),
        circasoShareUsdc = Amounts.usdc(platformAssetMinor),
    )

/** A page of the statement; `next` is the cursor for the page after it, null on the last one. */
fun Statement.toResponse(limit: Int) =
    StatementResponse(
        from = period.from.toString(),
        to = period.to.toString(),
        network = network.name,
        lines = lines.map { it.toDto() },
        totals = totals.toDto(),
        next = lines.lastOrNull()?.takeIf { lines.size >= limit }?.let { cursor(it.completedAt, it.orderId.value) },
    )
