package com.circaso.chama.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import java.time.Instant

/** What other modules may ask a chama. The assistant reads it later ("who hasn't contributed this month?"). */
interface Chamas {
    /** Whether the group chat has a shared goal set. */
    suspend fun hasGoal(chatId: ChatId): Boolean

    /**
     * The group's target and its latest [lines] ledger lines as [viewer] may see them, for a statement share link
     * (decision 2026-11-06). Null when [viewer] isn't in the group or it has no target, so nobody learns which.
     */
    suspend fun statement(
        chatId: ChatId,
        viewer: UserId,
        lines: Int = STATEMENT_LINES,
    ): ChamaStatement? = null

    companion object {
        const val STATEMENT_LINES = 50
    }
}

/** A group's target and money as a statement shows them. Amounts are USDC minor units. Never logged. */
data class ChamaStatement(
    val goalName: String,
    val targetMinor: Long,
    val contributedMinor: Long,
    val paidOutMinor: Long,
    val heldMinor: Long,
    val memberCount: Int,
    val deadline: Instant?,
    /** Newest first. */
    val lines: List<ChamaStatementLine>,
) {
    override fun toString(): String = "ChamaStatement(${lines.size} lines)"
}

/**
 * One ledger line: a contribution in ([isIncoming]) or a payout out. [personName] is the contributor or the Circaso
 * payee by their profile name; [payeeName] what the request called an outside payee; [note] the request's note.
 */
data class ChamaStatementLine(
    val at: Instant,
    val isIncoming: Boolean,
    val amountMinor: Long,
    val personName: String?,
    val payeeName: String?,
    val note: String?,
) {
    override fun toString(): String = "ChamaStatementLine($at)"
}
