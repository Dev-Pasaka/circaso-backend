package com.circaso.wallet.presentation.mappers

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AddressCheck
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.RecipientCheck
import com.circaso.wallet.domain.models.RecipientQuery
import com.circaso.wallet.domain.models.RecipientStatus
import com.circaso.wallet.domain.models.ReminderOutcome
import com.circaso.wallet.domain.models.SeenTransfer
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.support.Amounts
import com.circaso.wallet.presentation.dto.AmountDto
import com.circaso.wallet.presentation.dto.AmountRequest
import com.circaso.wallet.presentation.dto.PayeeDto
import com.circaso.wallet.presentation.dto.PayeeRequest
import com.circaso.wallet.presentation.dto.PrepareTransferRequest
import com.circaso.wallet.presentation.dto.PrepareTransferResponse
import com.circaso.wallet.presentation.dto.RecipientResponse
import com.circaso.wallet.presentation.dto.RemindResponse
import com.circaso.wallet.presentation.dto.TransferResponse
import com.circaso.wallet.presentation.dto.ValidateAddressRequest
import com.circaso.wallet.presentation.dto.ValidateAddressResponse

// ---- request → command: shape checks answer `request.malformed`; an amount that can't be sent, the wallet's ----

/** The longest address we accept from a phone; EVM addresses are 42 characters, Solana's at most 44. */
private const val MAX_ADDRESS_LENGTH = 128

/** One W4 request: the transfer's key is derived from the request's `Idempotency-Key`, as Circle's is (W4). */
fun PrepareTransferRequest.toCommand(userId: UserId, requestKey: String): Resource<TransferCommand> {
    val chain = chainOf(blockchain) ?: return malformed("blockchain", "is not a blockchain name")
    val payee =
        when (val read = to.toPayee()) {
            is Resource.Success -> read.data
            is Resource.Error -> return read
        }
    val money =
        when (val read = amount.toMoney()) {
            is Resource.Success -> read.data
            is Resource.Error -> return read
        }
    val chat = chatId?.let { ChatId.parse(it) ?: return malformed("chatId", "is not an id") }
    // TODO(Phase B: accept proposalId checked against the assistant's proposal)
    if (proposalId != null) return malformed("proposalId", "is not accepted until Asha's proposals exist")
    val wallet =
        when (val read = walletIdOf(walletId)) {
            is Resource.Success -> read.data
            is Resource.Error -> return read
        }
    val payout = payoutRequestId?.let { Uuid7.parse(it.trim()) ?: return malformed("payoutRequestId", "is not an id") }
    val key = circleKey(userId, "wallet.transfer", requestKey)
    return Resource.success(
        TransferCommand(userId, chain, payee, money, chat, proposalId = null, key, wallet, payout),
    )
}

private fun PayeeRequest.toPayee(): Resource<Payee> {
    if (listOfNotNull(userId, address, groupId).size != 1) {
        return malformed("to", "needs exactly one of userId, address and groupId")
    }
    if (groupId != null) {
        val id = ChatId.parse(groupId) ?: return malformed("to.groupId", "is not an id")
        return Resource.success(Payee.Group(id))
    }
    if (userId != null) {
        val id = UserId.parse(userId) ?: return malformed("to.userId", "is not an id")
        return Resource.success(Payee.Person(id))
    }
    val trimmed = address.orEmpty().trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_ADDRESS_LENGTH) return malformed("to.address", "is not an address")
    return Resource.success(Payee.Address(trimmed))
}

/** An asset that isn't offered, or an amount that can't be read exactly in it, is `wallet.amount_invalid`. */
private fun AmountRequest.toMoney(): Resource<Money> {
    if (amountMinor == null && amount == null) return malformed("amount", "needs amountMinor or amount")
    val asset = Asset.entries.firstOrNull { it.name == this.asset.trim().uppercase() }
    val minor = asset?.let { Amounts.parse(it, amountMinor?.trim(), amount?.trim()) }
    if (asset == null || minor == null) return Resource.error(WalletError.AmountInvalid)
    return Resource.success(Money(minor, asset))
}

fun transferIdOf(raw: String?): Resource<TransferId> {
    val id = raw?.let { TransferId.parse(it) } ?: return malformed("transferId", "is not an id")
    return Resource.success(id)
}

fun recipientQuery(
    senderId: UserId,
    rawUserId: String?,
    rawBlockchain: String?,
    rawChatId: String?,
): Resource<RecipientQuery> {
    val recipient = rawUserId?.let { UserId.parse(it) } ?: return malformed("userId", "is not an id")
    val chain = rawBlockchain?.let { chainOf(it) ?: return malformed("blockchain", "is not a blockchain name") }
    val chat = rawChatId?.let { ChatId.parse(it) ?: return malformed("chatId", "is not an id") }
    return Resource.success(RecipientQuery(senderId, recipient, chain, chat))
}

fun recipientIdOf(raw: String?): Resource<UserId> {
    val id = raw?.let { UserId.parse(it) } ?: return malformed("userId", "is not an id")
    return Resource.success(id)
}

/** W11's request as (chain, address). */
fun ValidateAddressRequest.toQuery(): Resource<Pair<String, String>> {
    val chain = chainOf(blockchain) ?: return malformed("blockchain", "is not a blockchain name")
    val trimmed = address.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_ADDRESS_LENGTH) return malformed("address", "is not an address")
    return Resource.success(chain to trimmed)
}

// ---- model → response ----

fun PreparedTransfer.toResponse() =
    PrepareTransferResponse(
        transferId = transfer.transferId,
        state = transfer.state.name,
        blockchain = transfer.blockchain,
        amount = transfer.amount.toDto(),
        challengeId = challengeId,
        sdk = sdk?.toDto(),
        fee = Money.zero(transfer.amount.asset).toDto(),
        expiresAt = transfer.expiresAt,
        walletId = transfer.senderId.toString(),
    )

/** W5 from the side it is seen from ([SeenTransfer.side]). */
fun SeenTransfer.toResponse(): TransferResponse = transfer.toResponse(side)

/**
 * [viewer] is the side asked from: the sender sees `OUT` and how it was started (`origin`, `proposalId`); the person
 * paid sees `IN`, without them, since whether the sender used Asha is the sender's business. A group wallet's
 * members see its payments from the wallet's side.
 */
fun Transfer.toResponse(viewer: UserId): TransferResponse {
    val isSender = viewer == senderId
    return TransferResponse(
        transferId = transferId,
        direction = if (isSender) "OUT" else "IN",
        state = state.name,
        blockchain = blockchain,
        from = senderId,
        to = PayeeDto(recipientId, destinationAddress),
        amount = amount.toDto(),
        fee = Money.zero(amount.asset).toDto(),
        chatId = chatId?.toString(),
        origin = origin.name.takeIf { isSender },
        proposalId = proposalId?.toString()?.takeIf { isSender },
        txHash = txHash,
        failureReason = failureReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
        expiresAt = expiresAt,
        fromGroupId = fromGroupId?.toString(),
        toGroupId = toGroupId?.toString(),
        signerUserId = signer.toString(),
        payoutRequestId = payoutRequestId?.toString(),
    )
}

fun RecipientCheck.toResponse() =
    RecipientResponse(userId, status.name, canReceive = status == RecipientStatus.CAN_RECEIVE, blockchains)

fun ReminderOutcome.toResponse() = RemindResponse(name)

fun AddressCheck.toResponse() = ValidateAddressResponse(blockchain, address, valid)

private fun Money.toDto() = Amounts.of(this).let { AmountDto(it.asset, it.amountMinor, it.amount) }
