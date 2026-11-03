package com.circaso.status.api

import com.circaso.shared.InstantSerializer
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The `data` of each realtime frame `status` sends (Moments API → Realtime), one class per type (decision
 * 2026-10-27). Every frame is kept 7 days for replay, so a phone without a socket catches up on the next connect
 * (replay) or by reading ST4. Only a `status.viewed` that adds or changes a reaction is pushed (by the gateway).
 */
object StatusFrames {
    const val MODULE = "status"
    const val POSTED = "status.posted"
    const val DELETED = "status.deleted"
    const val VIEWED = "status.viewed"
}

/** A frame status sends. [type] names it in the envelope and is not part of its `data`. */
sealed interface StatusFrame {
    val type: String
}

/** To the audience of a new moment: the phone fetches it with ST4. */
@Serializable
data class MomentPostedFrame(val momentId: MomentId, val posterId: UserId) : StatusFrame {
    override val type: String get() = StatusFrames.POSTED
}

/** To the audience of a moment its poster deleted (or whose account went): the phone drops it. */
@Serializable
data class MomentDeletedFrame(val momentId: MomentId, val posterId: UserId) : StatusFrame {
    override val type: String get() = StatusFrames.DELETED
}

/**
 * To the poster, when someone in the audience first sees a moment, and whenever they set, change or clear their
 * [reaction] (one emoji; [reacted] is `reaction != null`).
 */
@Serializable
data class MomentViewedFrame(
    val momentId: MomentId,
    val viewerId: UserId,
    @Serializable(with = InstantSerializer::class) val viewedAt: Instant,
    val reacted: Boolean,
    /** Set only when this frame adds or changes a reaction, for the poster's push ("Wanjiru reacted 😂 to …"). */
    val viewerName: String? = null,
    val reaction: String? = null,
) : StatusFrame {
    override val type: String get() = StatusFrames.VIEWED
}
