package com.circaso.wallet.presentation.dto

import kotlinx.serialization.Serializable

/** W18 `POST /wallet/spaces/{spaceId}` (Spaces phase 3). */
@Serializable
data class SpaceWalletSetupResponse(
    val walletId: String,
    val spaceId: String,
    /** `SETTING_UP` until the PIN is set and Circle has made the wallet; then W19 and W1 say `ACTIVE`. */
    val status: String,
    /** Run it in the Circle SDK with [sdk] (this wallet's own session); null when the PIN is already set. */
    val challengeId: String? = null,
    val sdk: SdkDto,
)

/** W19 `GET /wallet/spaces/{spaceId}`. */
@Serializable
data class SpaceWalletResponse(
    val spaceId: String,
    /** `NONE` (no wallet yet), `SETTING_UP` or `ACTIVE`. */
    val status: String,
    val wallet: WalletListingDto? = null,
)
