package com.circaso.wallet.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.map
import com.circaso.wallet.api.AgentAssetValueDto
import com.circaso.wallet.api.AgentAssetValuesResponse
import com.circaso.wallet.api.AgentFeeQuoteResponse
import com.circaso.wallet.api.AgentLimitDto
import com.circaso.wallet.api.AgentRateDto
import com.circaso.wallet.api.AgentWalletListingDto
import com.circaso.wallet.api.AgentWalletsResponse
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AssetValueQuery
import com.circaso.wallet.domain.models.AssetValues
import com.circaso.wallet.domain.models.FeeEstimate
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.QuoteProblem
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletListing
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.support.Amounts
import com.circaso.wallet.domain.support.Valuation
import java.math.BigDecimal
import java.util.UUID

// Asha's value and fee routes (decision 2026-11-05): query strings in, the agent's small answers out. A shape that
// doesn't read answers `request.malformed` on the field, so the model sees which argument it got wrong.

/** At most this many asset names in one ask; more is the model looping. */
private const val MAX_ASSETS = 10
private const val MAX_ASSET_NAME = 16
private const val MAX_ADDRESS_LENGTH = 128

/** The answers' `source`: the wallet's own rate, never a market feed. */
private const val SOURCE = "circaso"

/** `?assets=USDC,USDT&amount=20&currency=KES`: the names as asked (capitals), an optional amount, a currency. */
fun assetValueQuery(
    assets: String?,
    amount: String?,
    currency: String?,
): Resource<AssetValueQuery> {
    val names =
        assets
            ?.split(',')
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    if (names.size > MAX_ASSETS || names.any { it.length > MAX_ASSET_NAME }) {
        return malformed("assets", "is more than 10 short asset names")
    }
    val whole =
        amount?.let {
            it.trim().toBigDecimalOrNull()?.takeIf { value -> value.signum() >= 0 }
                ?: return malformed("amount", "is not a positive number")
        }
    return currencyCodeOf(currency).map { AssetValueQuery(names, whole, it) }
}

/** The query parameters of `/agent/wallet/fee`, as they came. */
data class AgentFeeParams(
    val asset: String? = null,
    val amount: String? = null,
    val blockchain: String? = null,
    val toUserId: String? = null,
    val toAddress: String? = null,
    val toGroupId: String? = null,
    val walletId: String? = null,
    val currency: String? = null,
)

/** A W17 command for a payment that may never be made: nothing is created, so its key is only well formed. */
fun AgentFeeParams.toCommand(userId: UserId): Resource<TransferCommand> {
    val chain = blockchain?.let { chainOf(it) } ?: return malformed("blockchain", "is not a blockchain name")
    val payee =
        when (val read = payee()) {
            is Resource.Success -> read.data
            is Resource.Error -> return read
        }
    val coin = Asset.entries.firstOrNull { it.name == (asset ?: Asset.USDC.name).trim().uppercase() }
    val minor = coin?.let { Amounts.parse(it, null, amount?.trim() ?: return malformed("amount", "is missing")) }
    if (coin == null || minor == null) return Resource.error(WalletError.AmountInvalid)
    return walletIdOf(walletId).map { wallet ->
        TransferCommand(
            senderId = userId,
            blockchain = chain,
            payee = payee,
            amount = Money(minor, coin),
            chatId = null,
            proposalId = null,
            idempotencyKey = UUID.randomUUID(),
            walletId = wallet,
        )
    }
}

/** The display currency asked for the fee's fiat values. */
fun AgentFeeParams.currencyCode(): Resource<String?> = currencyCodeOf(currency)

private fun AgentFeeParams.payee(): Resource<Payee> {
    if (listOfNotNull(toUserId, toAddress, toGroupId).size != 1) {
        return malformed("to", "needs exactly one of toUserId, toAddress and toGroupId")
    }
    toUserId?.let { raw ->
        return UserId.parse(raw.trim())?.let { Resource.success(Payee.Person(it)) }
            ?: malformed("toUserId", "is not an id")
    }
    toGroupId?.let { raw ->
        return ChatId.parse(raw.trim())?.let { Resource.success(Payee.Group(it)) }
            ?: malformed("toGroupId", "is not an id")
    }
    val address = toAddress.orEmpty().trim()
    if (address.isEmpty() || address.length > MAX_ADDRESS_LENGTH) return malformed("toAddress", "is not an address")
    return Resource.success(Payee.Address(address))
}

fun AssetValues.toAgentResponse(): AgentAssetValuesResponse =
    AgentAssetValuesResponse(
        currency = currency,
        source = SOURCE,
        values =
            values.map {
                AgentAssetValueDto(
                    asset = it.name,
                    priced = it.usdPrice != null,
                    usdPrice = it.usdPrice?.toPlainString(),
                    unitValue = it.unitValue?.toPlainString(),
                    value = it.value?.toPlainString(),
                )
            },
        amount = amount?.stripTrailingZeros()?.toPlainString(),
        rate = rate?.let { AgentRateDto(it.currency, it.rate.toPlainString(), it.asOf.toString()) },
    )

fun FeeEstimate.toAgentResponse(): AgentFeeQuoteResponse {
    val asset = quote.amount.asset

    fun whole(minor: Long) = Amounts.whole(Money(minor, asset))

    fun fiat(minor: Long): String? =
        rate?.let { Valuation.fiatOrDollars(BigDecimal(minor.toBigInteger(), asset.decimals), it).toPlainString() }

    val allowance = quote.allowance
    return AgentFeeQuoteResponse(
        blockchain = quote.blockchain,
        asset = asset.name,
        amount = whole(quote.amount.amountMinor),
        fee = whole(quote.fee.amountMinor),
        rateBps = quote.fee.rateBps,
        minimum = quote.fee.minimum,
        total = whole(quote.totalMinor),
        balance = whole(quote.balanceMinor),
        maxAmount = whole(quote.maxAmountMinor),
        limit =
            AgentLimitDto(
                perPaymentMax = allowance.perPaymentMaxMinor?.let(::whole),
                dailyMax = allowance.dailyMaxMinor?.let(::whole),
                used = whole(allowance.usedMinor),
                left = allowance.leftMinor?.let(::whole),
                windowHours = allowance.window.toHours().toInt(),
                moreAt = allowance.moreAt?.toString(),
            ),
        noFeeReason = quote.fee.reason?.name,
        problem = quote.problem?.name,
        fitsAt = if (quote.problem == QuoteProblem.DAILY_LIMIT) quote.fitsAt?.toString() else null,
        fiatAmount = fiat(quote.amount.amountMinor),
        fiatFee = fiat(quote.fee.amountMinor),
        fiatTotal = fiat(quote.totalMinor),
        rate = rate?.let { AgentRateDto(it.currency, it.rate.toPlainString(), it.asOf.toString()) },
    )
}

/** Spaces are archived (decision 2026-11-05): a space's wallet is never listed to Asha. */
fun List<WalletListing>.toAgentResponse(): AgentWalletsResponse =
    AgentWalletsResponse(
        filter { it.owner !is WalletOwner.Space }.map {
            AgentWalletListingDto(
                walletId = it.owner.walletId.toString(),
                type = it.owner.type.name,
                status = it.status.name,
                canSign = it.canSign,
                blockchains = it.blockchains,
                chatId = (it.owner as? WalletOwner.Group)?.chatId?.toString(),
            )
        },
    )
