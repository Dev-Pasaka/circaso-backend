package com.circaso.ops.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupReports
import com.circaso.messaging.api.OpsGroup
import com.circaso.ops.domain.models.Party
import com.circaso.ops.domain.models.PartyKind
import com.circaso.ops.domain.models.TransferView
import com.circaso.shared.UserId
import com.circaso.wallet.api.OpsTransfer

/**
 * Both sides of transfers as staff see them: a person by their profile, a group's wallet as that group (messaging's
 * staff contract), an outside address as itself, and Circaso's fee wallet as Circaso's fee. Every lookup is batched
 * per page: one profile read and one group read, never one per row. Which side is a group or the fee wallet comes from
 * the wallet's own record (`from_group_id`, `to_group_id`, the configured fee address), never from a name.
 */
class TransferParties(private val views: UserViews, private val groups: GroupReports) {
    suspend fun of(transfers: List<OpsTransfer>): List<TransferView> {
        if (transfers.isEmpty()) return emptyList()
        val people =
            transfers
                .flatMap { listOfNotNull(it.senderId.takeIf { _ -> it.fromGroupId == null }, personPaid(it)) }
                .toSet()
        val names = views.names(people)
        val groupIds = transfers.flatMap { listOfNotNull(it.fromGroupId, it.toGroupId) }.toSet()
        val found = if (groupIds.isEmpty()) emptyMap() else groups.groupsByIds(groupIds)
        return transfers.map { view(it, names, found) }
    }

    suspend fun of(transfer: OpsTransfer): TransferView = of(listOf(transfer)).single()

    private fun personPaid(transfer: OpsTransfer): UserId? = transfer.recipientId.takeIf { transfer.toGroupId == null }

    private fun view(
        transfer: OpsTransfer,
        names: Map<UserId, String>,
        found: Map<ChatId, OpsGroup>,
    ): TransferView {
        val sender =
            transfer.fromGroupId?.let { group(it, found) }
                ?: Party(PartyKind.USER, transfer.senderId.toString(), names[transfer.senderId])
        val recipient =
            when {
                transfer.toGroupId != null -> {
                    group(transfer.toGroupId!!, found)
                }

                transfer.recipientId != null -> {
                    Party(PartyKind.USER, transfer.recipientId.toString(), names[transfer.recipientId])
                }

                transfer.toFeeWallet -> {
                    fee(transfer.destinationAddress)
                }

                else -> {
                    Party(PartyKind.EXTERNAL, transfer.destinationAddress, short(transfer.destinationAddress))
                }
            }
        val feeLeg = transfer.feeAddress?.takeIf { transfer.feeMinor.signum() > 0 }?.let(::fee)
        return TransferView(transfer, sender, recipient, feeLeg)
    }

    private fun group(chatId: ChatId, found: Map<ChatId, OpsGroup>) =
        Party(PartyKind.GROUP, chatId.value.toString(), null, found[chatId]?.memberCount)

    private fun fee(address: String) = Party(PartyKind.FEE, address, FEE_NAME)

    companion object {
        const val FEE_NAME = "Circaso fee"
        private const val HEAD = 6
        private const val TAIL = 4

        /** `0x12ab…9f3e`: enough to recognise an address, short enough for a table. */
        fun short(address: String): String =
            if (address.length <= HEAD + TAIL + 1) address else "${address.take(HEAD)}…${address.takeLast(TAIL)}"
    }
}
