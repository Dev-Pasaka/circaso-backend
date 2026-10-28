package com.circaso.wallet.presentation.mappers

import com.circaso.integrations.api.SdkSession
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.AssetTotal
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.ChainOption
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.FiatValue
import com.circaso.wallet.domain.models.Holding
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.models.WalletSetup
import com.circaso.wallet.domain.models.WalletView
import com.circaso.wallet.presentation.dto.AddChainRequest
import com.circaso.wallet.presentation.dto.AddChainResponse
import com.circaso.wallet.presentation.dto.AssetDto
import com.circaso.wallet.presentation.dto.AssetTotalDto
import com.circaso.wallet.presentation.dto.ChainDto
import com.circaso.wallet.presentation.dto.ChainOptionDto
import com.circaso.wallet.presentation.dto.ChainsResponse
import com.circaso.wallet.presentation.dto.CreateWalletResponse
import com.circaso.wallet.presentation.dto.FiatDto
import com.circaso.wallet.presentation.dto.HoldDto
import com.circaso.wallet.presentation.dto.SdkDto
import com.circaso.wallet.presentation.dto.WalletResponse
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Currency
import java.util.UUID

// ---- request → command: shape checks live here and answer `request.malformed` ----

/** The longest chain name we accept from a phone; Circle's are short (`BASE-SEPOLIA`). */
private const val MAX_CHAIN_LENGTH = 40

private val CHAIN = Regex("^[A-Z0-9-]{1,$MAX_CHAIN_LENGTH}$")

/** The ISO 4217 codes the JDK knows; any other `currency` is malformed (decision 2026-10-28). */
private val CURRENCIES: Set<String> = Currency.getAvailableCurrencies().map { it.currencyCode }.toSet()

private fun malformed(field: String, why: String) = Resource.error(SharedError.Malformed(field, why))

/** W1's `?currency=`: absent, or an ISO 4217 code (any case; answered in capitals). */
fun walletQuery(userId: UserId, currency: String?): Resource<WalletQuery> {
    if (currency == null) return Resource.success(WalletQuery(userId, null))
    val code = currency.trim().uppercase()
    if (code !in CURRENCIES) return malformed("currency", "is not an ISO 4217 currency code")
    return Resource.success(WalletQuery(userId, code))
}

fun createWalletCommand(userId: UserId, requestKey: String): CreateWalletCommand =
    CreateWalletCommand(userId, circleKey(userId, "wallet.create", requestKey))

fun AddChainRequest.toCommand(userId: UserId, requestKey: String): Resource<AddChainCommand> {
    val chain = blockchain.trim().uppercase()
    if (!CHAIN.matches(chain)) return malformed("blockchain", "is not a blockchain name")
    return Resource.success(AddChainCommand(userId, chain, circleKey(userId, "wallet.add-chain:$chain", requestKey)))
}

/**
 * Circle's idempotency key for a request, derived from its `Idempotency-Key` (Wallet → W4): the same request sent
 * again asks Circle with the same key, a new request with a new one. Circle wants a UUID; a phone may send any text,
 * so the key is a name-based UUID of the user, the operation and the phone's key.
 */
private fun circleKey(
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
    )

fun WalletSetup.toResponse() = CreateWalletResponse(status.name, challengeId, sdk.toDto())

fun List<ChainOption>.toResponse() =
    ChainsResponse(
        defaultChain = first { it.isDefault }.blockchain,
        chains = map { ChainOptionDto(it.blockchain, it.assets, it.status.name) },
    )

fun ChainAddition.toResponse() = AddChainResponse(blockchain, status.name, challengeId, sdk?.toDto())

fun SdkSession.toDto() = SdkDto(appId, userToken, encryptionKey, expiresAt)

private fun ChainHoldings.toDto() =
    ChainDto(
        blockchain = wallet.blockchain,
        address = wallet.address,
        state = wallet.state.name,
        assets = holdings.map { it.toDto() },
    )

private fun Holding.toDto() =
    AssetDto(
        symbol = symbol,
        tokenAddress = tokenAddress,
        decimals = decimals,
        amountMinor = amountMinor.toString(),
        amount = whole(amountMinor, decimals),
        fiatAmount = fiatAmount?.toPlainString(),
    )

private fun AssetTotal.toDto() = AssetTotalDto(symbol, decimals, amountMinor.toString(), whole(amountMinor, decimals))

private fun FiatValue.toDto() = FiatDto(currency, amount.toPlainString(), rate.toPlainString(), asOf, partial)

/** "20.5" for 20 500 000 of a 6-decimal asset; "0" for nothing. */
private fun whole(amountMinor: BigInteger, decimals: Int): String =
    BigDecimal(amountMinor, decimals).stripTrailingZeros().toPlainString()
