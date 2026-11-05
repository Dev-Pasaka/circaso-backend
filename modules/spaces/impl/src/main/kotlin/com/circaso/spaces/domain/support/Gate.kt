package com.circaso.spaces.domain.support

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.SpaceRepository

/** Where the caller sits in a space: its row, their membership (an ended one too) and whether they are banned. */
data class Seat(val space: Space, val membership: Membership?, val banned: Boolean) {
    /** Their role while their membership is active; null otherwise. */
    val role: Role? get() = membership?.takeIf { it.isActive }?.role

    val isMember: Boolean get() = role != null

    fun isAtLeast(min: Role): Boolean = role?.atLeast(min) == true

    /** Inside the space: members read everything; visitors of a public space read its latest posts only. */
    val canReadAll: Boolean get() = isMember && !banned
}

/**
 * Who may see a space and from where (Spaces API → SP3): a live space is seen by everyone (an invite-only one from
 * outside only), one being set up only by its owner and team, a deleted or removed one by nobody
 * (`spaces.space_unavailable`). Every other check starts from the [Seat] this answers.
 */
class Gate(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val bans: BanRepository,
) {
    suspend fun seat(spaceId: SpaceId, caller: UserId): Resource<Seat> {
        val space = spaces.find(spaceId) ?: return Resource.error(SpacesError.SpaceNotFound)
        return seat(space, caller)
    }

    suspend fun seat(space: Space, caller: UserId): Resource<Seat> {
        if (space.isGone) {
            val reason = if (space.state == SpaceState.REMOVED) "REMOVED" else "DELETED"
            return Resource.error(SpacesError.SpaceUnavailable(reason, space.name))
        }
        val membership = members.find(space.spaceId, caller)
        val seat = Seat(space, membership, bans.find(space.spaceId, caller) != null)
        if (space.state == SpaceState.SETTING_UP && seat.role == null) return Resource.error(SpacesError.SpaceNotFound)
        return Resource.success(seat)
    }

    /** A seat with at least [min] in a space that isn't gone; [error] otherwise. */
    suspend fun require(
        spaceId: SpaceId,
        caller: UserId,
        min: Role,
        error: SpacesError,
    ): Resource<Seat> =
        when (val seat = seat(spaceId, caller)) {
            is Resource.Error -> seat
            is Resource.Success -> if (seat.data.isAtLeast(min)) seat else Resource.error(error)
        }

    /** A member of a live space who may take part (post, react, pay): not banned, membership not ended. */
    suspend fun participant(spaceId: SpaceId, caller: UserId): Resource<Seat> {
        val seat =
            when (val found = seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        return when {
            seat.banned -> Resource.error(SpacesError.Banned)
            seat.membership?.isActive == false -> Resource.error(SpacesError.MembershipEnded)
            !seat.isMember -> Resource.error(SpacesError.NotMember)
            !seat.space.isLive -> Resource.error(SpacesError.SpaceNotFound)
            else -> Resource.success(seat)
        }
    }

    companion object {
        /** Whether a visitor sees the inside at all (a public space's latest posts). */
        fun visitorsRead(space: Space): Boolean = space.visibility == Visibility.PUBLIC
    }
}
