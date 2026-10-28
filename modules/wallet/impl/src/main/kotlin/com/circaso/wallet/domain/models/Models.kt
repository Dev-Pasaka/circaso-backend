package com.circaso.wallet.domain.models

import com.circaso.integrations.api.SdkSession
import com.circaso.shared.UserId
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * Where a user's wallet stands as a whole: `NONE` before they ask for one, `SETTING_UP` while the first chain's wallet
 * waits for them to set the PIN, `ACTIVE` once it exists on at least one chain.
 */
enum class WalletStatus { NONE, SETTING_UP, ACTIVE }

/** The state of the wallet on one chain. `CLOSED` once the account is deleted (later slice). */
enum class ChainWalletState { ACTIVE, FROZEN, CLOSED }

/** The link between a Circaso account and its Circle user; its existence is the wallet. */
data class CircleUser(val userId: UserId, val circleUserId: UUID, val createdAt: Instant)

/** The user's wallet on one chain. [address] is stored normalised ([Addresses.normalise]). */
data class ChainWallet(
    val circleWalletId: String,
    val userId: UserId,
    val blockchain: String,
    val accountType: String,
    val address: String,
    val state: ChainWalletState,
    val createdAt: Instant,
)

/**
 * What one wallet holds of one offered asset, in the asset's smallest unit, and what that is worth in the currency
 * W1 asked for ([fiatAmount], null when none was asked or the asset has no price). Never logged.
 */
data class Holding(
    val symbol: String,
    val tokenAddress: String,
    val decimals: Int,
    val usdPegged: Boolean,
    val amountMinor: BigInteger,
    val fiatAmount: BigDecimal? = null,
) {
    override fun toString(): String = "Holding($symbol, ***)"
}

/** One chain of the wallet and what it holds there. */
data class ChainHoldings(val wallet: ChainWallet, val holdings: List<Holding>)

/** The same asset added up over every chain (USDC on Base plus USDC on Solana). */
data class AssetTotal(val symbol: String, val decimals: Int, val amountMinor: BigInteger)

/**
 * The wallet in [currency]: [amount] is the sum of what the priced assets are worth, rounded to the currency's minor
 * unit. Only US dollar stablecoins are priced for now, at one dollar each; [partial] says that something else held
 * was left out of [amount] because nothing gives it a price yet.
 */
data class FiatValue(
    val currency: String,
    val amount: BigDecimal,
    val rate: BigDecimal,
    val asOf: Instant,
    val partial: Boolean,
)

/** Outgoing payments are held until [until] after a support restore (decision 2026-10-28; the hold arrives in WS4). */
data class PaymentHold(val until: Instant)

/** W1: the wallet as the wallet screen shows it. [fiat] is null when no rate was asked for or none is known. */
data class WalletView(
    val status: WalletStatus,
    val chains: List<ChainHoldings>,
    val totals: List<AssetTotal>,
    val fiat: FiatValue?,
    val hold: PaymentHold?,
)

/** W2's answer: the challenge for the SDK, or none when Circle had nothing left to sign. */
data class WalletSetup(val status: WalletStatus, val challengeId: String?, val sdk: SdkSession)

/** How one supported chain stands for this user (W12). */
enum class ChainStatus {
    /** The wallet is on this chain. */
    ENABLED,

    /** The user asked to add it and hasn't finished in the SDK yet. */
    ADDING,

    /** Supported, not added. */
    AVAILABLE,
}

/** A supported chain, the assets offered on it, and where this user stands with it. */
data class ChainOption(
    val blockchain: String,
    val assets: List<String>,
    val isDefault: Boolean,
    val status: ChainStatus,
)

/** W12 `POST /wallet/chains`: the challenge that adds [blockchain], or none when it is already on the wallet. */
data class ChainAddition(
    val blockchain: String,
    val status: ChainStatus,
    val challengeId: String?,
    val sdk: SdkSession?,
)

/** A request that asks Circle for a challenge; [idempotencyKey] is derived from the request's `Idempotency-Key`. */
data class CreateWalletCommand(val userId: UserId, val idempotencyKey: UUID)

data class AddChainCommand(val userId: UserId, val blockchain: String, val idempotencyKey: UUID)

/** W1's query; [currency] is an ISO 4217 code already checked by the mapper. */
data class WalletQuery(val userId: UserId, val currency: String?)
