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
    /** The coin's symbol (`USDC`, `EURC`, `ETH`; any held coin since 2026-11-07), or `USD` for a dollar value. */
    val asset: String,
    val amountMinor: String,
    val amount: String,
)

/**
 * An amount in a request: any coin the wallet holds on the payment's chain, by its symbol as W1 lists it (2026-11-07),
 * with `amountMinor` (in the coin's own decimals) or `amount` (whole coins, at most its decimals), or both when they
 * agree.
 */
@Serializable
data class AmountRequest(val asset: String, val amountMinor: String? = null, val amount: String? = null)

/**
 * Who to pay: a Circaso user, an address on the transfer's chain, or a group's wallet (`groupId`, the group chat; you
 * must be a member). Exactly one.
 */
@Serializable
data class PayeeRequest(
    val userId: String? = null,
    val address: String? = null,
    val groupId: String? = null,
    /** A space's wallet (Spaces phase 3); you must be in the space, unless this pays its membership. */
    val spaceId: String? = null,
)

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
    /** The wallet to pay from (added 2026-11-03): null for your own; a group wallet you sign for. */
    val walletId: String? = null,
    /** From a group wallet: the approved payout request this pays (same payee, same amount). */
    val payoutRequestId: String? = null,
    /** A membership payment from SP13 (Spaces phase 3): `to.spaceId` and the amount must be exactly its own. */
    val spacePaymentId: String? = null,
    /**
     * The W17 quote the person was shown (added 2026-11-04, phase 2F): its fee is charged while it holds. Without
     * one, or once it has run out, a fee above zero answers `wallet.fee_changed` with a new quote to show first.
     */
    val quoteId: String? = null,
)

/**
 * The one Circaso fee, network cost included (phase 2F): the same fields as an amount, plus how it was priced.
 * [rateBps] (0..200) and [minimum] (the floor that covers the network cost made it more than the rate did) are null
 * on a repeated W4 request, which only knows the amount.
 */
@Serializable
data class FeeDto(
    val asset: String,
    val amountMinor: String,
    val amount: String,
    val rateBps: Int? = null,
    val minimum: Boolean? = null,
    val ruleId: String? = null,
    val ruleVersion: Int? = null,
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
    /** Circaso's fee, locked for this transfer and paid on top in the same signature (zero where none is taken). */
    val fee: FeeDto,
    /** Sign before this, or the transfer expires. */
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    /** The wallet it leaves: your user id for your own, the group wallet's id otherwise (added 2026-11-03). */
    val walletId: String? = null,
    /** What leaves the wallet: the amount and the fee (added 2026-11-04). The payee gets exactly `amount`. */
    val total: AmountDto? = null,
)

/**
 * Where a wallet stands against its limits (§7a). Limits are in US dollars: amounts in the payment's coin for USDC and
 * USDT (one dollar each), otherwise in `USD` (2026-11-07).
 */
@Serializable
data class LimitDto(
    /** One payment's cap on its amount; null when there is none. */
    val perPaymentMax: AmountDto? = null,
    /** The cap on what leaves in [windowHours], fees included; null when there is none (spaces). */
    val dailyMax: AmountDto? = null,
    val used: AmountDto,
    val left: AmountDto? = null,
    val windowHours: Int,
    /** When more becomes available: the oldest counted payment leaves the window. */
    @Serializable(with = InstantSerializer::class) val moreAt: Instant? = null,
)

/** W17 `POST /wallet/transfers/quote`: the fee for this exact payment before it is signed (GW13). */
@Serializable
data class QuoteTransferResponse(
    val blockchain: String,
    val amount: AmountDto,
    val fee: FeeDto,
    /** The amount and the fee. */
    val total: AmountDto,
    /** What the wallet holds of the asset on the chain now. */
    val balance: AmountDto,
    /** The most that can be sent now with its fee, within the balance and the limits ("Send all", GW20). */
    val maxAmount: AmountDto,
    /**
     * What limits count in: amounts of the coin for USDC and USDT, as before; for any other coin (added 2026-11-07)
     * amounts of `USD` (6 decimals), the payment counted at [usdPrice].
     */
    val limit: LimitDto,
    /** Null when it can be paid as it is; else `INSUFFICIENT_FUNDS`, `PER_PAYMENT_LIMIT` or `DAILY_LIMIT`. */
    val problem: String? = null,
    /** With `DAILY_LIMIT`: when this payment fits the window (GW14b); null when it never can. */
    @Serializable(with = InstantSerializer::class) val fitsAt: Instant? = null,
    /** Pass to W4 to be charged exactly this fee; only for the person who signs for the wallet. */
    val quoteId: String? = null,
    /** Until when the fee is held. */
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
    /**
     * What one whole coin of the payment is worth in US dollars, the price the fee was converted at and the limits
     * count the payment at (added 2026-11-07): `"1"` for USDC and USDT. A held quote keeps it with the fee.
     */
    val usdPrice: String? = null,
    /** The amount and the fee in `USD` at [usdPrice]: what the payment counts against the limits (added 2026-11-07). */
    val totalUsd: AmountDto? = null,
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
    /** Set when it left a group wallet (`from` is then that wallet's id; added 2026-11-03). */
    val fromGroupId: String? = null,
    /** Set when it paid a group wallet (`to.userId` is then that wallet's id): a contribution. */
    val toGroupId: String? = null,
    /** Who signed it. */
    val signerUserId: String? = null,
    /** The approved payout request it pays. */
    val payoutRequestId: String? = null,
    /** Set when it left a space wallet (Spaces phase 3). */
    val fromSpaceId: String? = null,
    /** Set when it paid a space wallet (`to.userId` is then that wallet's id). */
    val toSpaceId: String? = null,
    /** The membership payment (SP13) it paid. */
    val spacePaymentId: String? = null,
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

/**
 * The other side of a W6 row: [userId], [displayName], [handle] and [avatarUrl] when they are a Circaso user (the name
 * is missing when their account is gone), and the on-chain [address] whenever it is known.
 */
@Serializable
data class CounterpartyDto(
    val userId: UserId? = null,
    val displayName: String? = null,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val address: String? = null,
    /** Set when the other side is a group's wallet (added 2026-11-03); name it from the group on the phone. */
    val groupId: String? = null,
)

/** One row of W6. The fields it shares with W5's [TransferResponse] mean the same and have the same type. */
@Serializable
data class TransactionDto(
    /** The history row's id; stable, use it as the list key. */
    val id: String,
    /**
     * Set on payments you sent through Circaso, and on money received from one (open it with W5; on `IN` rows since
     * 2026-11-05, when the server could tell which payment it was). Null on money from an outside address.
     */
    val transferId: TransferId? = null,
    /** `OUT` when you sent it, `IN` when it paid you. */
    val direction: String,
    /** `SUBMITTED`, `CONFIRMED` or `FAILED`. */
    val state: String,
    val blockchain: String,
    val amount: AmountDto,
    val counterparty: CounterpartyDto,
    /** The chat the payment was made in; on `IN` rows the sender's chat, set with [transferId] (2026-11-05). */
    val chatId: String? = null,
    /** `APP` or `ASHA`; only on `OUT`. */
    val origin: String? = null,
    val txHash: String? = null,
    @Serializable(with = InstantSerializer::class) val occurredAt: Instant,
)

/** W6 `GET /wallet/transactions`. */
@Serializable
data class TransactionsResponse(
    /** Newest first. */
    val transactions: List<TransactionDto>,
    /** Pass back as `cursor` (with the same `month`, if any) for the next page; null on the last. */
    val nextCursor: String? = null,
)
