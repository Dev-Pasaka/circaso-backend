package com.circaso.wallet.presentation.dto

import com.circaso.shared.InstantSerializer
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import kotlinx.serialization.Serializable
import java.time.Instant

// Amounts are strings across the whole wallet (decision 2026-10-28): `amountMinor`, the exact integer in the asset's
// smallest unit, and `amount`, the same in whole units for display.

/** An amount as the wallet answers it. */
@Serializable
data class AmountDto(
    /** `USDC`; transfers are USDC only for now. */
    val asset: String,
    val amountMinor: String,
    val amount: String,
)

/** An amount in a request: `amountMinor` or `amount` (at most the asset's decimals), or both when they agree. */
@Serializable
data class AmountRequest(val asset: String, val amountMinor: String? = null, val amount: String? = null)

/** Who to pay: a Circaso user, or an address on the transfer's chain. Exactly one. */
@Serializable
data class PayeeRequest(val userId: String? = null, val address: String? = null)

/** W4 `POST /wallet/transfers`. */
@Serializable
data class PrepareTransferRequest(
    /** The chain to send on (decision 2026-10-28), sent from the wallet's address there; see W12 for the list. */
    val blockchain: String,
    val to: PayeeRequest,
    val amount: AmountRequest,
    /** Paid from this chat: the payee may be paid as a member of it with you. */
    val chatId: String? = null,
    /** For confirming an Asha proposal (Phase B); refused with `request.malformed` until then. */
    val proposalId: String? = null,
)

@Serializable
data class PrepareTransferResponse(
    val transferId: TransferId,
    /** `AWAITING_SIGNATURE` for a new transfer; a repeated `Idempotency-Key` answers the transfer as it now is. */
    val state: String,
    val blockchain: String,
    val amount: AmountDto,
    /** Run it in the Circle SDK with [sdk]: the user signs with the PIN. Null once the transfer is past signing. */
    val challengeId: String? = null,
    val sdk: SdkDto? = null,
    /** Gas is paid by Circaso for now: always zero. */
    val fee: AmountDto,
    /** Sign before this, or the transfer expires. */
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

/** Who a transfer pays: [userId] for a Circaso user (null for an outside address), and the address on chain. */
@Serializable
data class PayeeDto(val userId: UserId? = null, val address: String)

/** W5 `GET /wallet/transfers/{transferId}`. */
@Serializable
data class TransferResponse(
    val transferId: TransferId,
    /** `OUT` when you sent it, `IN` when it pays you. */
    val direction: String,
    /** `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED` or `EXPIRED`. */
    val state: String,
    val blockchain: String,
    val from: UserId,
    val to: PayeeDto,
    val amount: AmountDto,
    val fee: AmountDto,
    val chatId: String? = null,
    /** `APP` or `ASHA`; only for the sender. */
    val origin: String? = null,
    val proposalId: String? = null,
    val txHash: String? = null,
    val failureReason: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
)

/** W9 `GET /wallet/recipients/{userId}`. */
@Serializable
data class RecipientResponse(
    val userId: UserId,
    /** `CAN_RECEIVE`, `NO_WALLET` (offer W10) or `NO_WALLET_ON_CHAIN`. */
    val status: String,
    val canReceive: Boolean,
    /** The chains they can be paid on now. */
    val blockchains: List<String>,
)

/** W10 `POST /wallet/recipients/{userId}/remind`. */
@Serializable
data class RemindResponse(
    /** `SENT`, `ALREADY_SENT` (within the last 24 hours) or `HAS_WALLET` (pay them instead). */
    val status: String,
)

/** W11 `POST /wallet/addresses/validate`. */
@Serializable
data class ValidateAddressRequest(val blockchain: String, val address: String)

@Serializable
data class ValidateAddressResponse(
    val blockchain: String,
    /** As it will be paid: EVM addresses in lowercase, Solana's exactly as given. */
    val address: String,
    val valid: Boolean,
)
