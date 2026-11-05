package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.ports.LoggedFrame
import com.circaso.spaces.domain.ports.RealtimeLog
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Lifecycle
import java.time.Clock

/**
 * identity's `AccountDeleted`: the person leaves every space; the spaces they owned are deleted (their wallets were
 * empty, or identity's deletion guard would have refused). Safe to run twice.
 */
class RemoveAccountFromSpaces(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val transactor: Transactor,
    private val lifecycle: Lifecycle,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        val now = clock.instant()
        for (space in spaces.ownedBy(userId).filterNot { it.isGone }) {
            spaces.update(space, space.copy(state = SpaceState.DELETED, updatedAt = now))?.let { lifecycle.announce(it) }
        }
        val rows = members.ofUser(userId)
        transactor.run("members.remove-account") {
            members.deleteAllOf(userId)
            rows.forEach { spaces.refreshMemberCount(it.spaceId) }
        }
        return Resource.success(Unit)
    }
}

/** The gateway's replay after a reconnect: the frames since [seq], or null when the gap is older than what is kept. */
class ReplayFrames(private val log: RealtimeLog) {
    suspend operator fun invoke(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> {
        val frames = log.since(userId, seq, LIMIT)
        if (frames.isEmpty()) {
            val latest = log.latest(userId) ?: return Resource.success(emptyList())
            if (latest > seq) return Resource.success(null)
        }
        return Resource.success(frames)
    }

    private companion object {
        const val LIMIT = 500
    }
}
