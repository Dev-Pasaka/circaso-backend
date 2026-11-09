package com.circaso.chama.domain.ports

import com.circaso.chama.api.RequestId
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletAmount

/** A frame the domain names; `data/cache` words it on the wire and keeps it for replay. */
sealed interface ChamaFrame {
    data class GoalUpdated(
        val chatId: ChatId,
        val name: String,
        val target: WalletAmount,
        val contributed: WalletAmount,
        val byUserId: UserId,
    ) : ChamaFrame

    data class ContributionAdded(
        val chatId: ChatId,
        val userId: UserId,
        val displayName: String,
        val goalName: String,
        val amount: WalletAmount,
        val contributed: WalletAmount,
        val target: WalletAmount,
    ) : ChamaFrame

    data class RequestUpdated(
        val requestId: RequestId,
        val chatId: ChatId,
        val state: String,
        val requestedBy: UserId,
        val requesterName: String,
        val goalName: String,
        val amount: WalletAmount,
        val approvals: Int,
        val approvalsNeeded: Int,
        val waitingOn: List<UserId>,
        val signerId: UserId? = null,
    ) : ChamaFrame

    /** The group's money settings changed or its target was removed: reload CH7 (decision 2026-11-03). No push. */
    data class GroupUpdated(val chatId: ChatId, val byUserId: UserId) : ChamaFrame

    /** Money moved in or out of the group's wallet, confirmed: every member reloads CH7 and the wallet. No push. */
    data class MoneyUpdated(
        val chatId: ChatId,
        val transferId: String,
        val kind: String,
        val amount: WalletAmount,
        val userId: UserId? = null,
        val requestId: RequestId? = null,
        /** The coin a contribution was paid in, in its own units (2026-11-09). */
        val paid: WalletAmount? = null,
    ) : ChamaFrame

    /** A member's payment into the group's wallet is on its way, not yet confirmed (2026-11-09). No push. */
    data class ContributionSubmitted(
        val chatId: ChatId,
        val transferId: String,
        val userId: UserId,
        val displayName: String,
        val paid: WalletAmount,
        val amount: WalletAmount?,
    ) : ChamaFrame
}

/** Sends a frame to the members named: to their sockets, or as a push where the gateway words one. */
interface FrameSender {
    suspend fun send(userIds: Collection<UserId>, frame: ChamaFrame)
}

/** The frames kept for replay after a reconnect. */
interface RealtimeLog {
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)

/** Chama's outbox rows: written with the change that caused the event, then published. */
interface ChamaOutbox {
    suspend fun write(events: List<com.circaso.shared.EventEnvelope>)
}
