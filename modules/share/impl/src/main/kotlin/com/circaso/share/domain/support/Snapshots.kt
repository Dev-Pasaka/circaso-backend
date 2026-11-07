package com.circaso.share.domain.support

import com.circaso.chama.api.ChamaStatementLine
import com.circaso.chama.api.Chamas
import com.circaso.integrations.api.ExchangeRates
import com.circaso.messaging.api.ChatId
import com.circaso.share.domain.errors.ShareError
import com.circaso.share.domain.models.FiatRate
import com.circaso.share.domain.models.FiatValue
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.StatementLine
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Invites
import com.circaso.users.api.Profiles
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.Wallets
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/** A snapshot and, for an invite, the end its invite sets on the link. */
data class Built(val content: ShareContent, val endsBy: Instant? = null)

/**
 * Builds what a page shows from the owning modules' records, never from what the phone sent, so a receipt or a
 * statement on a Circaso page can't be made up (decision 2026-11-06). Each one holds only what the sharer chose: names
 * and notes only with "show names" on, nothing from any chat. Whatever isn't the sharer's own answers
 * `share.source_not_found`, so a link can't be used to learn that a payment or a group exists.
 */
class Snapshots(
    private val wallets: Wallets,
    private val chamas: Chamas,
    private val profiles: Profiles,
    private val invites: Invites,
    private val rates: ExchangeRates,
    private val clock: Clock,
) {
    suspend fun receipt(
        owner: UserId,
        transferId: TransferId,
        showNames: Boolean,
        currency: String?,
    ): Resource<Built> {
        val transfer = wallets.transfer(transferId)
        if (transfer == null || owner !in transfer.parties()) return Resource.error(ShareError.SourceNotFound)
        if (transfer.state !in SHAREABLE_STATES) return Resource.error(ShareError.NotShareable)
        val names = if (showNames) profiles.get(transfer.people()).mapValues { it.value.displayName } else emptyMap()
        val receipt =
            ShareContent.Receipt(
                transferId = transfer.transferId,
                isSent = owner == transfer.senderId || owner == transfer.signerId,
                amount = transfer.amount.amount,
                asset = transfer.amount.asset,
                fiat = fiatOf(transfer, currency),
                status = transfer.state,
                blockchain = transfer.blockchain.orEmpty(),
                txHash = transfer.txHash,
                at = transfer.settledAt(),
                fromName = if (showNames) transfer.fromName(names) else null,
                toName = if (showNames) transfer.toName(names) else null,
            )
        return Resource.success(Built(receipt))
    }

    /** A receipt whose payment was still on its way, brought up to date; null when nothing changed. */
    suspend fun refresh(receipt: ShareContent.Receipt): ShareContent.Receipt? {
        if (receipt.status in FINAL_STATES) return null
        val transfer = wallets.transfer(receipt.transferId) ?: return null
        if (transfer.state == receipt.status && transfer.txHash == receipt.txHash) return null
        return receipt.copy(status = transfer.state, txHash = transfer.txHash, at = transfer.settledAt())
    }

    suspend fun statement(
        owner: UserId,
        chatId: ChatId,
        showNames: Boolean,
        currency: String?,
    ): Resource<Built> {
        val statement = chamas.statement(chatId, owner) ?: return Resource.error(ShareError.SourceNotFound)
        val content =
            ShareContent.Statement(
                chatId = chatId,
                goalName = statement.goalName,
                targetMinor = statement.targetMinor,
                contributedMinor = statement.contributedMinor,
                paidOutMinor = statement.paidOutMinor,
                heldMinor = statement.heldMinor,
                memberCount = statement.memberCount,
                deadline = statement.deadline,
                fiat = currency?.let { rateOf(it) },
                lines = statement.lines.map { it.toLine(showNames) },
                asOf = clock.instant(),
            )
        return Resource.success(Built(content))
    }

    suspend fun invite(owner: UserId): Resource<Built> {
        val profile = profiles.get(setOf(owner))[owner] ?: return Resource.error(ShareError.SourceNotFound)
        val invite = invites.create(owner) ?: return Resource.error(ShareError.Unavailable)
        val person = ShareContent.Person(owner, profile.displayName, profile.handle, invite.token)
        return Resource.success(Built(person, endsBy = invite.expiresAt))
    }

    suspend fun payMe(owner: UserId): Resource<Built> {
        val profile = profiles.get(setOf(owner))[owner] ?: return Resource.error(ShareError.SourceNotFound)
        val handle = profile.handle ?: return Resource.error(ShareError.NotShareable)
        return Resource.success(Built(ShareContent.Person(owner, profile.displayName, handle, inviteToken = null)))
    }

    suspend fun address(
        owner: UserId,
        blockchain: String,
        showNames: Boolean,
    ): Resource<Built> {
        val address = wallets.depositAddress(owner, blockchain) ?: return Resource.error(ShareError.SourceNotFound)
        val name = if (showNames) profiles.get(setOf(owner))[owner]?.displayName else null
        return Resource.success(Built(ShareContent.Address(blockchain.uppercase(), address, name)))
    }

    private suspend fun fiatOf(transfer: TransferSummary, currency: String?): FiatValue? {
        if (currency == null || !Amounts.isDollar(transfer.amount.asset)) return null
        val rate = rateOf(currency) ?: return null
        val dollars = transfer.amount.amount.toBigDecimalOrNull() ?: return null
        return FiatValue(rate.currency, Amounts.fiat(dollars, rate.rate), rate.asOf)
    }

    private suspend fun rateOf(currency: String): FiatRate? {
        if (currency == USD) return FiatRate(USD, BigDecimal.ONE, clock.instant())
        return rates.usdRate(currency)?.let { FiatRate(it.currency, it.rate, it.asOf) }
    }

    private fun ChamaStatementLine.toLine(showNames: Boolean) =
        StatementLine(
            at = at,
            isIncoming = isIncoming,
            amountMinor = amountMinor,
            name = if (showNames) personName ?: payeeName else null,
            note = if (showNames) note else null,
        )

    private companion object {
        const val USD = "USD"
        const val GROUP_WALLET = "Group wallet"
        val SHAREABLE_STATES = setOf("SUBMITTED", "CONFIRMED", "FAILED")
        val FINAL_STATES = setOf("CONFIRMED", "FAILED", "EXPIRED")

        /** Who may share a payment: whoever sent, received or signed it. */
        fun TransferSummary.parties(): Set<UserId> = setOfNotNull(senderId, recipientId, signerId)

        fun TransferSummary.people(): Set<UserId> =
            setOfNotNull(
                senderId.takeIf { fromGroupId == null },
                recipientId?.takeIf { toGroupId == null },
            )

        fun TransferSummary.settledAt(): Instant =
            (if (state in FINAL_STATES) updatedAt else null) ?: createdAt ?: updatedAt ?: Instant.EPOCH

        fun TransferSummary.fromName(names: Map<UserId, String>): String? =
            if (fromGroupId != null) GROUP_WALLET else names[senderId]

        fun TransferSummary.toName(names: Map<UserId, String>): String? =
            when {
                toGroupId != null -> GROUP_WALLET
                recipientId != null -> recipientId?.let(names::get)
                else -> shortAddress(destinationAddress)
            }

        fun shortAddress(address: String): String =
            if (address.length <= 14) address else address.take(6) + "…" + address.takeLast(6)
    }
}
