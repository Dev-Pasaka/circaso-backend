package com.circaso.wallet.presentation.dto

import com.circaso.shared.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

// Amounts of crypto assets travel as strings: `amountMinor` is the integer in the asset's smallest unit (an
// 18-decimal token passes what a JSON number holds exactly) and `amount` the same value in whole units for display
// ("20.5"). Fiat amounts are decimal strings in the currency's own minor unit ("2650.13" KES, "75000" UGX).

/** W1 `GET /wallet`. */
@Serializable
data class WalletResponse(
    /** `NONE`, `SETTING_UP` or `ACTIVE`. */
    val status: String,
    /** Each chain the wallet is on, oldest first, with every asset offered there; empty until `ACTIVE`. */
    val chains: List<ChainDto>,
    /** Each asset added up over all chains. */
    val totals: List<AssetTotalDto>,
    /** With `?currency=`: the priced assets' worth in that currency; null when no rate is known. */
    val fiat: FiatDto? = null,
    /** Outgoing payments on hold after a support restore; null when there is none. */
    val hold: HoldDto? = null,
)

@Serializable
data class ChainDto(
    val blockchain: String,
    val address: String,
    /** `ACTIVE` or `FROZEN`. */
    val state: String,
    val assets: List<AssetDto>,
    /** False on a chain retired from receiving: don't show its address to be paid; it can still send out. */
    val receiveEnabled: Boolean = true,
)

@Serializable
data class AssetDto(
    val symbol: String,
    /** The token's contract on this chain (EVM address, Solana mint); empty for the chain's own coin. */
    val tokenAddress: String,
    val decimals: Int,
    val amountMinor: String,
    val amount: String,
    /** With `?currency=`: this asset's worth in it; null for an asset with no price (only USD stablecoins have one). */
    val fiatAmount: String? = null,
)

@Serializable
data class AssetTotalDto(
    val symbol: String,
    val decimals: Int,
    val amountMinor: String,
    val amount: String,
)

@Serializable
data class FiatDto(
    /** ISO 4217, as asked. */
    val currency: String,
    /** What the priced assets are worth, rounded to the currency's minor unit. */
    val amount: String,
    /** One US dollar in [currency]; USDC counts as one dollar. */
    val rate: String,
    @Serializable(with = InstantSerializer::class) val asOf: Instant,
    /** True when the wallet holds something that has no price yet and is left out of [amount]. */
    val partial: Boolean,
)

@Serializable
data class HoldDto(
    @Serializable(with = InstantSerializer::class) val until: Instant,
)

/** What the Circle SDK on the phone runs under. For the app only; never logged, never for the agent token. */
@Serializable
data class SdkDto(
    val appId: String,
    val userToken: String,
    val encryptionKey: String,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

/** W2 `POST /wallet`. */
@Serializable
data class CreateWalletResponse(
    /** `SETTING_UP`. */
    val status: String,
    /** Run it with the SDK; null when Circle had nothing left to sign (the PIN is set, the wallet on its way). */
    val challengeId: String? = null,
    val sdk: SdkDto,
)

/** W12 `GET /wallet/chains`. */
@Serializable
data class ChainsResponse(
    /** The chain a new wallet starts on. */
    val defaultChain: String,
    val chains: List<ChainOptionDto>,
)

@Serializable
data class ChainOptionDto(
    val blockchain: String,
    /** The symbols of the assets offered on this chain. */
    val assets: List<String>,
    /** `ENABLED` (the wallet is on it), `ADDING` (asked, not signed yet) or `AVAILABLE`. */
    val status: String,
    /** False on a chain retired from receiving, listed only while the wallet is on it so it can be emptied. */
    val receiveEnabled: Boolean = true,
)

/** W12 `POST /wallet/chains`. */
@Serializable
data class AddChainRequest(val blockchain: String)

@Serializable
data class AddMissingChainsResponse(val chains: List<AddChainResponse>)

@Serializable
data class AddChainResponse(
    val blockchain: String,
    /** `ADDING` with a challenge to run, or `ENABLED` when the wallet is already on this chain. */
    val status: String,
    val challengeId: String? = null,
    val sdk: SdkDto? = null,
)
