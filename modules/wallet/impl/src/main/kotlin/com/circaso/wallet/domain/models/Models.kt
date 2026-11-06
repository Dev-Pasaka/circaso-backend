package com.circaso.wallet.domain.models

import com.circaso.integrations.api.SdkSession
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
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
 * What one wallet holds of one offered asset, in the asset's smallest unit, what one whole unit is worth in US
 * dollars ([usdPrice]: one for a dollar stablecoin, the price feed's for a coin we trust, else null) and what the
 * holding is worth in the currency W1 asked for ([fiatAmount], null when none was asked or the asset has no price).
 * Never logged.
 */
data class Holding(
    val symbol: String,
    val tokenAddress: String,
    val decimals: Int,
    val usdPegged: Boolean,
    val amountMinor: BigInteger,
    val fiatAmount: BigDecimal? = null,
    val usdPrice: BigDecimal? = null,
) {
    override fun toString(): String = "Holding($symbol, ***)"
}

/**
 * One chain of the wallet and what it holds there. [receiveEnabled] is false on a chain retired from receiving
 * (decision 2026-10-28): its address must not be handed out to be paid, only emptied.
 */
data class ChainHoldings(val wallet: ChainWallet, val holdings: List<Holding>, val receiveEnabled: Boolean = true)

/**
 * The same asset added up over every chain (USDC on Base plus USDC on Solana). [usdPrice] is one unit's price when
 * every chain's holding of it has the same one, else null.
 */
data class AssetTotal(
    val symbol: String,
    val decimals: Int,
    val amountMinor: BigInteger,
    val usdPrice: BigDecimal? = null,
)

/**
 * The wallet in [currency]: [amount] is the sum of what the priced assets are worth, rounded to the currency's minor
 * unit. US dollar stablecoins are one dollar each and other coins are at the price feed's price (decision
 * 2026-11-06); [partial] says that something held was left out of [amount] because it has no price.
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

/**
 * W1: the wallet as the wallet screen shows it. [fiat] is null when no rate was asked for or none is known. [access]
 * says whose wallet it is and whether the caller signs for it (null only where no caller asked: Asha's read).
 */
data class WalletView(
    val status: WalletStatus,
    val chains: List<ChainHoldings>,
    val totals: List<AssetTotal>,
    val fiat: FiatValue?,
    val hold: PaymentHold?,
    val access: WalletAccess? = null,
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

/**
 * A supported chain, the assets offered on it, and where this user stands with it. [receiveEnabled] is false for a
 * chain retired from receiving (decision 2026-10-28): listed only while the wallet is on it, so it can be emptied.
 */
data class ChainOption(
    val blockchain: String,
    val assets: List<String>,
    val isDefault: Boolean,
    val status: ChainStatus,
    val receiveEnabled: Boolean,
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

/**
 * W12's add. Circle's key is derived in the use case from the wallet, the chain and the hour (decision 2026-10-28).
 * [walletId] is the wallet to add it to (null: the caller's own; a group wallet only by its signer).
 */
data class AddChainCommand(val userId: UserId, val blockchain: String, val walletId: WalletId? = null)

/**
 * W1's query; [currency] is an ISO 4217 code already checked by the mapper. [walletId] is the wallet to read (null:
 * the caller's own; a group wallet by any of its members).
 */
data class WalletQuery(val userId: UserId, val currency: String?, val walletId: WalletId? = null)
