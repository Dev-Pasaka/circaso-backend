package com.circaso.status.domain.support

import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy

/**
 * The one rule the server applies to who sees a moment (Moments API → Model): the poster's phone chooses the
 * audience from its own contacts, and the server only checks, through users' [ReachPolicy], that each pair can reach
 * each other both ways. A pair that can't is dropped silently, so a block is never revealed.
 */
class MomentReach(private val reach: ReachPolicy) {
    /** The part of [audience] that [poster] can reach and that can reach [poster], in the order given. */
    suspend fun mutual(poster: UserId, audience: Collection<UserId>): List<UserId> {
        val forward = reach.reachable(poster, audience.toSet())
        return audience.filter { it in forward && reach.canReach(it, poster) }
    }

    /** Whether [viewer] may still see [poster]'s moments: neither has blocked the other since it was posted. */
    suspend fun stillVisible(viewer: UserId, poster: UserId): Boolean = !reach.blockedEitherWay(viewer, poster)
}
