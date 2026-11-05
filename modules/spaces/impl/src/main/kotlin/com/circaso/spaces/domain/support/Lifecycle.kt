package com.circaso.spaces.domain.support

import com.circaso.spaces.api.SpaceUpdatedFrame
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.wallet.api.Wallets
import java.time.Clock

/**
 * A space goes live the moment its wallet is active (plan §7): from the wallet's `WalletCreated`, or when its owner
 * opens it and the wallet turns out to be ready (should the event be late). Its team hears `space.updated`.
 */
class Lifecycle(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val wallets: Wallets,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    /** [space] live if its wallet is active now; the space as it then is. */
    suspend fun liveIfReady(space: Space): Space {
        if (space.state != SpaceState.SETTING_UP) return space
        val wallet = wallets.spaceWallet(space.spaceId.value) ?: return space
        if (!wallet.isActive) return space
        return goLive(space)
    }

    suspend fun goLive(space: Space): Space {
        var current = space
        repeat(RETRIES) {
            if (current.state != SpaceState.SETTING_UP) return current
            val live = current.copy(state = SpaceState.LIVE, updatedAt = clock.instant())
            val saved = spaces.update(current, live)
            if (saved != null) {
                announce(saved)
                return saved
            }
            current = spaces.find(space.spaceId) ?: return current
        }
        return current
    }

    /** `space.updated` to everyone in the space. */
    suspend fun announce(space: Space) {
        val ids = members.active(space.spaceId).map { it.userId }
        frames.send(ids, SpaceUpdatedFrame(space.spaceId, space.version, space.state.name))
    }

    private companion object {
        const val RETRIES = 3
    }
}
