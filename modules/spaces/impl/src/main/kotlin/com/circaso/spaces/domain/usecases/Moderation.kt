package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.InviteRemovedFrame
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.Ban
import com.circaso.spaces.domain.models.InviteState
import com.circaso.spaces.domain.models.MemberFilter
import com.circaso.spaces.domain.models.MemberView
import com.circaso.spaces.domain.models.MembersQuery
import com.circaso.spaces.domain.models.MembersView
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.InviteRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.Seat
import com.circaso.spaces.domain.support.SpaceViews
import com.circaso.spaces.domain.support.profileOf
import com.circaso.users.api.Profiles
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** "Until unmuted": a mute far enough ahead to never end by itself. */
internal val FOREVER: Instant = Instant.parse("9999-12-31T00:00:00Z")

/** Whether [seat]'s holder may act on [target]: someone else, ranked below them. */
internal fun Seat.outranks(target: Membership?): Boolean {
    val mine = role ?: return false
    if (target == null) return true
    if (target.userId == membership?.userId) return false
    return mine.rank > target.role.rank
}

/**
 * SP23 (SS5, SR1): everyone who can see inside gets the team, their contacts there and the count; moderators and up
 * also get the members, newest first, filtered (`MUTED`, or `BANNED` for admins) and searched by name or handle.
 */
class ListMembers(
    private val members: MemberRepository,
    private val bans: BanRepository,
    private val gate: Gate,
    private val views: SpaceViews,
    private val profiles: Profiles,
    private val clock: Clock,
) {
    suspend operator fun invoke(query: MembersQuery): Resource<MembersView> {
        val seat =
            when (val found = gate.seat(query.spaceId, query.caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (seat.banned) return Resource.error(SpacesError.Banned)
        if (!seat.isMember && !Gate.visitorsRead(seat.space)) return Resource.error(SpacesError.MembersOnly)
        val now = clock.instant()
        val first = query.cursor == null
        val offset = query.cursor?.toIntOrNull() ?: 0
        val team =
            if (first) {
                members.team(query.spaceId).sortedWith(
                    compareByDescending<Membership> {
                        it.role.rank
                    }.thenBy { it.joinedAt },
                )
            } else {
                emptyList()
            }
        val teamIds = team.map { it.userId }.toSet()
        val contacts =
            if (first) {
                members.among(query.spaceId, views.contactsOf(query.caller)).filterNot {
                    it.userId in teamIds
                }
            } else {
                emptyList()
            }

        var page = emptyList<MemberView>()
        var next: String? = null
        if (seat.isAtLeast(Role.MODERATOR)) {
            when (query.filter) {
                MemberFilter.BANNED -> {
                    if (!seat.isAtLeast(Role.ADMIN)) return Resource.error(SpacesError.NotAdmin)
                    val banned = bans.ofSpace(query.spaceId).sortedByDescending { it.bannedAt }
                    val people = profiles.get(banned.map { it.userId }.toSet())
                    val slice = banned.drop(offset).take(query.limit)
                    page = slice.map { MemberView(people.profileOf(it.userId), null, null, null, it.bannedAt) }
                    next = (offset + query.limit).takeIf { banned.size > it }?.toString()
                }

                else -> {
                    val mutedAfter = if (query.filter == MemberFilter.MUTED) now else null
                    val rows =
                        if (query.query.isNullOrBlank()) {
                            members.page(query.spaceId, mutedAfter, offset, query.limit + 1)
                        } else {
                            matching(query, mutedAfter).drop(offset).take(query.limit + 1)
                        }
                    page = viewsOf(rows.take(query.limit), now)
                    next = (offset + query.limit).takeIf { rows.size > query.limit }?.toString()
                }
            }
        }
        return Resource.success(
            MembersView(viewsOf(team, now), viewsOf(contacts, now), page, seat.space.memberCount, next),
        )
    }

    /** Everyone active (capped), filtered by a name or handle starting with the query. */
    private suspend fun matching(query: MembersQuery, mutedAfter: Instant?): List<Membership> {
        val all = members.page(query.spaceId, mutedAfter, 0, SEARCH_CAP)
        val people = profiles.get(all.map { it.userId }.toSet())
        val needle =
            query.query
                .orEmpty()
                .trim()
                .removePrefix("@")
                .lowercase()
        return all.filter {
            val profile = people.profileOf(it.userId)
            profile.displayName
                .lowercase()
                .split(' ')
                .any { word -> word.startsWith(needle) } ||
                profile.displayName.lowercase().startsWith(needle) ||
                profile.handle?.lowercase()?.startsWith(needle) == true
        }
    }

    private suspend fun viewsOf(rows: List<Membership>, now: Instant): List<MemberView> {
        if (rows.isEmpty()) return emptyList()
        val people = profiles.get(rows.map { it.userId }.toSet())
        return rows.map {
            MemberView(
                people.profileOf(it.userId),
                it.role,
                it.joinedAt,
                it.mutedUntil?.takeIf { m ->
                    m.isAfter(now)
                },
                null,
            )
        }
    }

    private companion object {
        const val SEARCH_CAP = 5_000
    }
}

/**
 * SP24: the owner sets any role but their own; admins make or unmake moderators. The person hears
 * `space.membership_updated` (`ROLE_CHANGED`), and is pushed when promoted.
 */
class SetRole(
    private val members: MemberRepository,
    private val gate: Gate,
    private val profiles: Profiles,
    private val frames: FrameSender,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
        role: Role,
    ): Resource<MemberView> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (role == Role.OWNER) return Resource.error(SpacesError.CannotModerate)
        val membership =
            members.find(spaceId, target)?.takeIf { it.isActive } ?: return Resource.error(SpacesError.NotMember)
        if (!seat.outranks(membership)) return Resource.error(SpacesError.CannotModerate)
        if (seat.role != Role.OWNER && role == Role.ADMIN) return Resource.error(SpacesError.NotOwner)
        val updated = membership.copy(role = role)
        members.save(updated)
        frames.send(listOf(target), MembershipUpdatedFrame(spaceId, seat.space.name, "ROLE_CHANGED", role = role.name))
        val person = profiles.get(setOf(target)).profileOf(target)
        return Resource.success(MemberView(person, role, updated.joinedAt, updated.mutedUntil, null))
    }
}

/**
 * SP25: moderators and up mute someone ranked below them for an hour, a day, a week or until unmuted. They read and
 * react but can't post; they're told they were muted and until when, never by whom.
 */
class MuteMember(
    private val members: MemberRepository,
    private val gate: Gate,
    private val profiles: Profiles,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
        duration: MuteDuration?,
    ): Resource<MemberView> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.MODERATOR, SpacesError.NotModerator)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val membership =
            members.find(spaceId, target)?.takeIf { it.isActive } ?: return Resource.error(SpacesError.NotMember)
        if (!seat.outranks(membership)) return Resource.error(SpacesError.CannotModerate)
        val until = duration?.let { until(it, clock.instant()) }
        members.save(membership.copy(mutedUntil = until))
        val reason = if (until == null) "UNMUTED" else "MUTED"
        frames.send(listOf(target), MembershipUpdatedFrame(spaceId, seat.space.name, reason, until?.toString()))
        val person = profiles.get(setOf(target)).profileOf(target)
        return Resource.success(MemberView(person, membership.role, membership.joinedAt, until, null))
    }

    private fun until(duration: MuteDuration, now: Instant): Instant =
        when (duration) {
            MuteDuration.HOUR -> now.plus(Duration.ofHours(1))
            MuteDuration.DAY -> now.plus(Duration.ofDays(1))
            MuteDuration.WEEK -> now.plus(Duration.ofDays(7))
            MuteDuration.UNTIL_UNMUTED -> FOREVER
        }
}

/** SP26: admins remove someone ranked below them; they may follow again, their posts stay. */
class RemoveMember(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val frames: FrameSender,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
    ): Resource<Unit> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val membership =
            members.find(spaceId, target)?.takeIf { it.isActive } ?: return Resource.error(SpacesError.NotMember)
        if (!seat.outranks(membership)) return Resource.error(SpacesError.CannotModerate)
        transactor.run("members.remove") {
            members.delete(spaceId, target)
            spaces.refreshMemberCount(spaceId)
        }
        frames.send(listOf(target), MembershipUpdatedFrame(spaceId, seat.space.name, "REMOVED"))
        return Resource.success(Unit)
    }
}

/**
 * SP27: admins ban someone ranked below them (or someone not in the space): they leave it and can't follow, join, ask,
 * be invited, pay it or message its owner. Unbanning lets them follow or ask again.
 */
class SetBan(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val bans: BanRepository,
    private val invites: InviteRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        target: UserId,
        banned: Boolean,
    ): Resource<Boolean> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val membership = members.find(spaceId, target)
        if (target == caller || membership?.role == Role.OWNER || !seat.outranks(membership)) {
            return Resource.error(SpacesError.CannotModerate)
        }
        val now = clock.instant()
        if (!banned) {
            if (bans.delete(spaceId, target)) {
                frames.send(listOf(target), MembershipUpdatedFrame(spaceId, seat.space.name, "UNBANNED"))
            }
            return Resource.success(false)
        }
        val invite = invites.of(spaceId, target)?.takeIf { it.isLive(now) }
        transactor.run("members.ban") {
            bans.insert(Ban(spaceId, target, caller, now))
            members.delete(spaceId, target)
            invite?.let { invites.save(it.copy(state = InviteState.CANCELLED, answeredAt = now)) }
            spaces.refreshMemberCount(spaceId)
        }
        invite?.let { frames.send(listOf(target), InviteRemovedFrame(it.inviteId.toString(), spaceId)) }
        frames.send(listOf(target), MembershipUpdatedFrame(spaceId, seat.space.name, "BANNED"))
        return Resource.success(true)
    }
}
