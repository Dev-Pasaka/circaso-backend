package com.circaso.share.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.share.api.ShareCode
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.math.BigDecimal
import java.time.Instant

/** What a link shows (decision 2026-11-06). Moments are end-to-end encrypted, so they are never one of these. */
enum class ShareKind {
    RECEIPT,
    STATEMENT,
    INVITE,
    PAY_ME,
    ADDRESS,
}

/**
 * One share link. [content] is the snapshot the page shows, built when the link was made from the owning modules'
 * records and holding only what the sharer chose; null once the link is revoked, so nothing of it stays behind.
 * [endsAt] is when the sharer said it stops working (null: never); [revokedAt] when they turned it off.
 */
data class ShareLink(
    val code: ShareCode,
    val ownerId: UserId,
    val kind: ShareKind,
    val content: ShareContent?,
    val createdAt: Instant,
    val endsAt: Instant?,
    val revokedAt: Instant?,
) {
    fun isLive(now: Instant): Boolean = revokedAt == null && content != null && (endsAt == null || endsAt.isAfter(now))

    override fun toString(): String = "ShareLink($kind)"
}

/** A fiat value worked out when the link was made, with the rate's own date. */
data class FiatValue(val currency: String, val amount: BigDecimal, val asOf: Instant)

/** One US dollar in [currency], as the rate source published it at [asOf]. */
data class FiatRate(val currency: String, val rate: BigDecimal, val asOf: Instant)

/** The snapshot behind a page. Every type is what the sharer chose to show, and nothing of any chat. */
sealed interface ShareContent {
    /**
     * A payment from the sharer's side: [isSent] when they sent (or signed) it. Names only if they left "show names"
     * on; [counterpartAddress] is an outside address's short form, under the same switch. [transferId] is kept to
     * refresh the status and to open the payment in the app; it is never shown on the page.
     */
    data class Receipt(
        val transferId: TransferId,
        val isSent: Boolean,
        val amount: String,
        val asset: String,
        val fiat: FiatValue?,
        val status: String,
        val blockchain: String,
        val txHash: String?,
        val at: Instant,
        val fromName: String?,
        val toName: String?,
    ) : ShareContent

    /** A group's target and ledger, USDC minor units. Names and notes only with "show names" on. */
    data class Statement(
        val chatId: ChatId,
        val goalName: String,
        val targetMinor: Long,
        val contributedMinor: Long,
        val paidOutMinor: Long,
        val heldMinor: Long,
        val memberCount: Int,
        val deadline: Instant?,
        val fiat: FiatRate?,
        val lines: List<StatementLine>,
        val asOf: Instant,
    ) : ShareContent

    /**
     * The sharer as a person to chat with ([inviteToken] set, an INVITE) or to pay (a PAY_ME, by [handle]). The
     * token is never shown on the page; a signed-in viewer's app connects with it.
     */
    data class Person(
        val userId: UserId,
        val displayName: String,
        val handle: String?,
        val inviteToken: String?,
    ) : ShareContent

    /** The sharer's own wallet address on one chain, read from the wallet's record; their name with "show names". */
    data class Address(val blockchain: String, val address: String, val name: String?) : ShareContent
}

data class StatementLine(
    val at: Instant,
    val isIncoming: Boolean,
    val amountMinor: Long,
    val name: String?,
    val note: String?,
)

/** SH1: what to share. Which fields matter depends on [kind]; the mapper checks the shape, the use case the rest. */
data class CreateShareCommand(
    val ownerId: UserId,
    val kind: ShareKind,
    val transferId: TransferId?,
    val chatId: ChatId?,
    val blockchain: String?,
    val showNames: Boolean,
    val currency: String?,
    val endsAfterDays: Int?,
)

/** Where a signed-in viewer's app can go from a link (SH4), decided for that viewer. */
sealed interface OpenTarget {
    data class Transfer(val transferId: TransferId) : OpenTarget

    data class Chama(val chatId: ChatId) : OpenTarget

    data class Invite(val userId: UserId, val displayName: String, val inviteToken: String) : OpenTarget

    data class Pay(val userId: UserId, val displayName: String, val handle: String?) : OpenTarget

    data class PayAddress(val address: String, val blockchain: String) : OpenTarget
}

/** SH4's answer: the link, whether the viewer made it, and where their app may go. */
data class OpenedLink(val link: ShareLink, val isMine: Boolean, val open: OpenTarget?)

/** One page of the owner's live links (SH2), newest first; [next] continues after the last one. */
data class LinkPage(val links: List<ShareLink>, val next: LinkCursor?)

/** Where a page of links stopped: the last link's time and code. */
data class LinkCursor(val createdAt: Instant, val code: ShareCode)

/** What the public page gets: the link when it is live, or why it isn't. */
sealed interface PageLookup {
    data class Live(val link: ShareLink) : PageLookup

    /** Revoked or past its end. */
    data object Gone : PageLookup

    data object Missing : PageLookup

    data class Throttled(val retryAfterSeconds: Long) : PageLookup
}
