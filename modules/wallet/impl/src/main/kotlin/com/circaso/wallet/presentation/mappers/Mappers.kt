package com.circaso.wallet.presentation.mappers

import com.circaso.integrations.api.SdkSession
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.AssetTotal
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainOption
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.FiatValue
import com.circaso.wallet.domain.models.GroupWalletSetup
import com.circaso.wallet.domain.models.GroupWalletView
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.WalletListing
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.domain.support.Amounts
import com.circaso.wallet.presentation.dto.AddChainRequest
import com.circaso.wallet.presentation.dto.AddChainResponse
import com.circaso.wallet.presentation.dto.AssetDto
import com.circaso.wallet.presentation.dto.AssetTotalDto
import com.circaso.wallet.presentation.dto.ChainDto
import com.circaso.wallet.presentation.dto.ChainOptionDto
import com.circaso.wallet.presentation.dto.ChainsResponse
import com.circaso.wallet.presentation.dto.CreateWalletResponse
import com.circaso.wallet.presentation.dto.FiatDto
import com.circaso.wallet.presentation.dto.GroupWalletResponse
import com.circaso.wallet.presentation.dto.GroupWalletSetupResponse
import com.circaso.wallet.presentation.dto.HoldDto
import com.circaso.wallet.presentation.dto.SdkDto
import com.circaso.wallet.presentation.dto.WalletListingDto
import com.circaso.wallet.presentation.dto.WalletOwnerDto
import com.circaso.wallet.presentation.dto.WalletResponse
import com.circaso.wallet.presentation.dto.WalletsResponse
import java.util.Currency
import java.util.UUID

// ---- request → command: shape checks live here and answer `request.malformed` ----

/** The longest chain name we accept from a phone; Circle's are short (`BASE-SEPOLIA`). */
private const val MAX_CHAIN_LENGTH = 40

private val CHAIN = Regex("^[A-Z0-9-]{1,$MAX_CHAIN_LENGTH}$")

/** A chain name from a phone, in capitals, or null when it can't be one. */
internal fun chainOf(raw: String): String? = raw.trim().uppercase().takeIf { CHAIN.matches(it) }

/** The ISO 4217 codes the JDK knows; any other `currency` is malformed (decision 2026-10-28). */
private val CURRENCIES: Set<String> = Currency.getAvailableCurrencies().map { it.currencyCode }.toSet()

internal fun malformed(field: String, why: String) = Resource.error(SharedError.Malformed(field, why))

/** W1's `?currency=`: absent, or an ISO 4217 code (any case; answered in capitals); `?walletId=` as [walletIdOf]. */
fun walletQuery(
    userId: UserId,
    currency: String?,
    walletId: String? = null,
): Resource<WalletQuery> =
    walletIdOf(walletId).flatMap { id -> currencyCodeOf(currency).map { WalletQuery(userId, it, id) } }

/**
 * `?walletId=` (decision 2026-11-03): absent for your own wallet, or a wallet's id (your user id, or a group wallet's
 * id from W15 or W16).
 */
fun walletIdOf(raw: String?): Resource<WalletId?> {
    if (raw == null) return Resource.success(null)
    return WalletId.parse(raw.trim())?.let { Resource.success(it) } ?: malformed("walletId", "is not an id")
}

/** W14's and W15's `{chatId}`. */
fun groupIdOf(raw: String?): Resource<ChatId> =
    raw?.let { ChatId.parse(it) }?.let { Resource.success(it) } ?: malformed("chatId", "is not an id")

fun createGroupWalletCommand(
    userId: UserId,
    chatId: ChatId,
    requestKey: String,
): CreateGroupWalletCommand =
    CreateGroupWalletCommand(userId, chatId, circleKey(userId, "wallet.create-group:$chatId", requestKey))

/** A `currency` query value: null when absent, the ISO 4217 code in capitals, or `request.malformed`. */
internal fun currencyCodeOf(currency: String?): Resource<String?> {
    if (currency == null) return Resource.success(null)
    val code = currency.trim().uppercase()
    if (code !in CURRENCIES) return malformed("currency", "is not an ISO 4217 currency code")
    return Resource.success(code)
}

fun createWalletCommand(userId: UserId, requestKey: String): CreateWalletCommand =
    CreateWalletCommand(userId, circleKey(userId, "wallet.create", requestKey))

/** W12's add; Circle's key comes from the user, the chain and the hour, not the request (decision 2026-10-28). */
fun AddChainRequest.toCommand(userId: UserId, walletId: WalletId? = null): Resource<AddChainCommand> {
    val chain = chainOf(blockchain) ?: return malformed("blockchain", "is not a blockchain name")
    return Resource.success(AddChainCommand(userId, chain, walletId))
}

/**
 * Circle's idempotency key for a request, derived from its `Idempotency-Key` (Wallet → W4): the same request sent
 * again asks Circle with the same key, a new request with a new one. Circle wants a UUID; a phone may send any text,
 * so the key is a name-based UUID of the user, the operation and the phone's key.
 */
internal fun circleKey(
    userId: UserId,
    operation: String,
    requestKey: String,
): UUID = UUID.nameUUIDFromBytes("$userId|$operation|$requestKey".toByteArray())

// ---- model → response ----

fun WalletView.toResponse() =
    WalletResponse(
        status = status.name,
        chains = chains.map { it.toDto() },
        totals = totals.map { it.toDto() },
        fiat = fiat?.toDto(),
        hold = hold?.let { HoldDto(it.until) },
        walletId = access?.owner?.walletId?.toString(),
        owner = access?.owner?.toDto(),
        canSign = access?.canSign ?: true,
    )

fun WalletSetup.toResponse() = CreateWalletResponse(status.name, challengeId, sdk.toDto())

fun WalletOwner.toDto() =
    when (this) {
        is WalletOwner.Person -> WalletOwnerDto(type.name, userId = userId.toString(), signerUserId = "$signerId")
        is WalletOwner.Group -> WalletOwnerDto(type.name, chatId = chatId.toString(), signerUserId = "$signerId")
    }

fun GroupWalletSetup.toResponse() =
    GroupWalletSetupResponse(
        walletId = owner.walletId.toString(),
        chatId = owner.chatId.toString(),
        status = setup.status.name,
        challengeId = setup.challengeId,
        sdk = setup.sdk.toDto(),
    )

fun WalletListing.toDto() =
    WalletListingDto(owner.walletId.toString(), owner.toDto(), status.name, canSign, blockchains)

fun GroupWalletView.toResponse() =
    GroupWalletResponse(chatId.toString(), listing?.status?.name ?: WalletStatus.NONE.name, listing?.toDto())

fun List<WalletListing>.toResponse() = WalletsResponse(map { it.toDto() })

fun List<ChainOption>.toResponse() =
    ChainsResponse(
        defaultChain = first { it.isDefault }.blockchain,
        chains = map { ChainOptionDto(it.blockchain, it.assets, it.status.name, it.receiveEnabled) },
    )

fun ChainAddition.toResponse() = AddChainResponse(blockchain, status.name, challengeId, sdk?.toDto())

fun SdkSession.toDto() = SdkDto(appId, userToken, encryptionKey, expiresAt)

private fun ChainHoldings.toDto() =
    ChainDto(
        blockchain = wallet.blockchain,
        address = wallet.address,
        state = wallet.state.name,
        assets = holdings.map { it.toDto() },
        receiveEnabled = receiveEnabled,
    )

private fun Holding.toDto() =
    AssetDto(
        symbol = symbol,
        tokenAddress = tokenAddress,
        decimals = decimals,
        amountMinor = amountMinor.toString(),
        amount = Amounts.whole(amountMinor, decimals),
        fiatAmount = fiatAmount?.toPlainString(),
    )

private fun AssetTotal.toDto() =
    AssetTotalDto(symbol, decimals, amountMinor.toString(), Amounts.whole(amountMinor, decimals))

private fun FiatValue.toDto() = FiatDto(currency, amount.toPlainString(), rate.toPlainString(), asOf, partial)
