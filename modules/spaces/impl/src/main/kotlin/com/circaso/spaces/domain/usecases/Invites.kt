package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.spaces.api.InviteAcceptedFrame
import com.circaso.spaces.api.InviteRemovedFrame
import com.circaso.spaces.api.InvitedFrame
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.RequestNewFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.Invite
import com.circaso.spaces.domain.models.InviteOutcome
import com.circaso.spaces.domain.models.InviteResult
import com.circaso.spaces.domain.models.InviteState
import com.circaso.spaces.domain.models.InvitesView
import com.circaso.spaces.domain.models.JoinAsk
import com.circaso.spaces.domain.models.JoinRequestView
import com.circaso.spaces.domain.models.MeView
import com.circaso.spaces.domain.models.ReceivedInviteView
import com.circaso.spaces.domain.models.RequestState
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.SentInviteState
import com.circaso.spaces.domain.models.SentInviteView
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.InviteRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.RequestRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.SpaceViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.support.profileOf
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Joins [userId] to [space] for free (an accepted invite or request), in one transaction with the count. */
private suspend fun join(
    spaces: SpaceRepository,
    members: MemberRepository,
    transactor: Transactor,
    space: Space,
    userId: UserId,
    invitedBy: UserId?,
    now: Instant,
) = transactor.run("members.join") {
    val existing = members.find(space.spaceId, userId)
    val row = freeMembership(space.spaceId, userId, now, invitedBy)
    if (existing == null) {
        members.insert(row)
    } else if (!existing.isActive) {
        members.save(row)
    }
    spaces.refreshMemberCount(space.spaceId)
}

/**
 * SP14 `POST /spaces/{spaceId}/requests` (SS3): ask to join an invite-only space. A live invite is accepted instead. A
 * declined request may ask again after 30 days. The owner and admins hear `space.request_new` (and a push).
 */
class RequestToJoin(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val requests: RequestRepository,
    private val invites: InviteRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val views: SpaceViews,
    private val profiles: Profiles,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<MeView> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val space = seat.space
        if (seat.banned) return Resource.error(SpacesError.Banned)
        if (seat.isMember) return Resource.error(SpacesError.AlreadyMember())
        if (!space.isLive) return Resource.error(SpacesError.SpaceNotFound)
        if (space.visibility != Visibility.INVITE_ONLY) return Resource.error(SpacesError.NotInviteOnly)
        val now = clock.instant()
        val invite = invites.of(spaceId, caller)?.takeIf { it.isLive(now) }
        if (invite != null) {
            join(spaces, members, transactor, space, caller, invite.inviterId, now)
            invites.save(invite.copy(state = InviteState.ACCEPTED, answeredAt = now))
            return Resource.success(views.me(space, caller, members.find(spaceId, caller), banned = false))
        }
        val previous = requests.find(spaceId, caller)
        if (previous?.state ==
            RequestState.PENDING
        ) {
            return Resource.success(views.me(space, caller, seat.membership, false))
        }
        if (previous?.state == RequestState.DECLINED) {
            val again = (previous.answeredAt ?: previous.requestedAt).plus(config.requestRetryAfter)
            if (now < again) return Resource.error(SpacesError.RequestTooSoon(again.toString()))
        }
        requests.save(JoinAsk(spaceId, caller, RequestState.PENDING, now, answeredAt = null))
        val name = profiles.get(setOf(caller)).profileOf(caller).displayName
        val admins = members.team(spaceId).filter { it.role.atLeast(Role.ADMIN) }.map { it.userId }
        frames.send(admins, RequestNewFrame(spaceId, space.name, caller, name))
        return Resource.success(views.me(space, caller, seat.membership, banned = false))
    }
}

/** SP15: Cancel Request (SS3b). */
class CancelJoinAsk(
    private val requests: RequestRepository,
    private val gate: Gate,
    private val views: SpaceViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<MeView> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val request =
            requests.find(spaceId, caller)?.takeIf { it.state == RequestState.PENDING }
                ?: return Resource.error(SpacesError.RequestNotFound)
        requests.save(request.copy(state = RequestState.CANCELLED, answeredAt = clock.instant()))
        return Resource.success(views.me(seat.space, caller, seat.membership, seat.banned))
    }
}

/**
 * SP16 (SI4): requests waiting, oldest first, then invites from the last 90 days, newest first. A declined invite stays
 * `INVITED` until it expires: the inviter is never told about a decline.
 */
class ListInvitesAndRequests(
    private val members: MemberRepository,
    private val requests: RequestRepository,
    private val invites: InviteRepository,
    private val gate: Gate,
    private val profiles: Profiles,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<InvitesView> {
        when (val seat = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
            is Resource.Error -> return seat
            is Resource.Success -> Unit
        }
        val now = clock.instant()
        val waiting = requests.pending(spaceId).sortedBy { it.requestedAt }
        val sent =
            invites
                .ofSpace(
                    spaceId,
                    now.minus(config.inviteHistory),
                ).filter { it.state != InviteState.CANCELLED }
        val joined = members.among(spaceId, sent.map { it.inviteeId }).associateBy { it.userId }
        val people =
            profiles.get(
                (waiting.map { it.userId } + sent.flatMap { listOf(it.inviteeId, it.inviterId) }).toSet(),
            )
        val views =
            sent.sortedByDescending { it.invitedAt }.map { invite ->
                val state =
                    when {
                        invite.state == InviteState.ACCEPTED -> SentInviteState.JOINED
                        now >= invite.expiresAt -> SentInviteState.EXPIRED
                        else -> SentInviteState.INVITED
                    }
                SentInviteView(
                    invite,
                    people.profileOf(invite.inviteeId),
                    people.profileOf(invite.inviterId),
                    state,
                    joinedAt = joined[invite.inviteeId]?.joinedAt?.takeIf { state == SentInviteState.JOINED },
                )
            }
        return Resource.success(
            InvitesView(waiting.map { JoinRequestView(people.profileOf(it.userId), it.requestedAt) }, views),
        )
    }
}

/**
 * SP17: an admin accepts (the person becomes a member, with a frame and push) or declines (a frame, no push; a new
 * request waits 30 days).
 */
class AnswerJoinAsk(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val requests: RequestRepository,
    private val bans: BanRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        userId: UserId,
        accept: Boolean,
    ): Resource<RequestState> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val request =
            requests.find(spaceId, userId)?.takeIf { it.state == RequestState.PENDING }
                ?: return Resource.error(SpacesError.RequestNotFound)
        val now = clock.instant()
        if (accept && bans.find(spaceId, userId) == null) {
            join(spaces, members, transactor, seat.space, userId, invitedBy = null, now)
            requests.save(request.copy(state = RequestState.ACCEPTED, answeredAt = now))
            frames.send(listOf(userId), MembershipUpdatedFrame(spaceId, seat.space.name, "JOINED"))
            return Resource.success(RequestState.ACCEPTED)
        }
        requests.save(request.copy(state = RequestState.DECLINED, answeredAt = now))
        frames.send(listOf(userId), MembershipUpdatedFrame(spaceId, seat.space.name, "REQUEST_DECLINED"))
        return Resource.success(RequestState.DECLINED)
    }
}

/**
 * SP18 (SI1): admins invite 1 to 50 people to a public or invite-only space. Each new or refreshed invite lives 14
 * days and reaches the person as `space.invited` and a push; people already in, banned or out of reach are reported.
 */
class InvitePeople(
    private val members: MemberRepository,
    private val bans: BanRepository,
    private val invites: InviteRepository,
    private val gate: Gate,
    private val reach: ReachPolicy,
    private val profiles: Profiles,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        userIds: List<UserId>,
    ): Resource<List<InviteResult>> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val space = seat.space
        val people = userIds.distinct()
        val bad =
            people.isEmpty() || people.size > config.invitesPerCall || caller in people ||
                space.visibility == Visibility.MEMBERS_ONLY || !space.isLive
        if (bad) return Resource.error(SpacesError.InviteInvalid)
        val now = clock.instant()
        val inside = members.among(spaceId, people).map { it.userId }.toSet()
        val reachable = reach.reachable(caller, people.toSet())
        val known = profiles.get(people.toSet())
        val inviterName = profiles.get(setOf(caller)).profileOf(caller).displayName
        val results =
            people.map { userId ->
                val outcome =
                    when {
                        userId in inside -> InviteOutcome.ALREADY_MEMBER
                        bans.find(spaceId, userId) != null -> InviteOutcome.BANNED
                        userId !in reachable || userId !in known -> InviteOutcome.UNREACHABLE
                        else -> InviteOutcome.INVITED
                    }
                if (outcome == InviteOutcome.INVITED) {
                    val previous = invites.of(spaceId, userId)
                    val invite =
                        Invite(
                            inviteId = previous?.inviteId ?: Uuid7.generate(),
                            spaceId = spaceId,
                            inviteeId = userId,
                            inviterId = caller,
                            state = InviteState.INVITED,
                            invitedAt = now,
                            expiresAt = now.plus(config.inviteLifetime),
                            answeredAt = null,
                        )
                    invites.save(invite)
                    frames.send(
                        listOf(userId),
                        InvitedFrame(invite.inviteId.toString(), spaceId, space.name, caller, inviterName),
                    )
                }
                InviteResult(userId, outcome)
            }
        return Resource.success(results)
    }
}

/** SP19: an admin cancels an invite; its card leaves the invitee's Chats. */
class CancelInvite(
    private val invites: InviteRepository,
    private val gate: Gate,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        inviteId: UUID,
    ): Resource<Unit> {
        when (val seat = gate.require(spaceId, caller, Role.ADMIN, SpacesError.NotAdmin)) {
            is Resource.Error -> return seat
            is Resource.Success -> Unit
        }
        val invite =
            invites.find(inviteId)?.takeIf { it.spaceId == spaceId && it.state != InviteState.ACCEPTED }
                ?: return Resource.error(SpacesError.InviteNotFound)
        invites.save(invite.copy(state = InviteState.CANCELLED, answeredAt = clock.instant()))
        frames.send(listOf(invite.inviteeId), InviteRemovedFrame(inviteId.toString(), spaceId))
        return Resource.success(Unit)
    }
}

/** SP20 (SI2): the caller's live invites, newest first, for the cards at the top of Chats. */
class ListMyInvites(
    private val spaces: SpaceRepository,
    private val invites: InviteRepository,
    private val bans: BanRepository,
    private val views: SpaceViews,
    private val profiles: Profiles,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId): Resource<List<ReceivedInviteView>> {
        val live = invites.liveFor(caller, clock.instant())
        val found = spaces.findAll(live.map { it.spaceId }).filter { it.isLive }.associateBy { it.spaceId }
        val inviters = profiles.get(live.map { it.inviterId }.toSet())
        val contacts = views.contactsOf(caller)
        val items =
            live
                .filter { it.spaceId in found && bans.find(it.spaceId, caller) == null }
                .sortedByDescending { it.invitedAt }
                .map {
                    val space = found.getValue(it.spaceId)
                    ReceivedInviteView(
                        it,
                        views.summary(space),
                        inviters.profileOf(it.inviterId),
                        views.known(space.spaceId, contacts),
                    )
                }
        return Resource.success(items)
    }
}

/** SP21 (SI3): accept an invite; the inviter hears `space.invite_accepted` (no push). */
class AcceptInvite(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val invites: InviteRepository,
    private val bans: BanRepository,
    private val transactor: Transactor,
    private val views: SpaceViews,
    private val profiles: Profiles,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, inviteId: UUID): Resource<SpaceView> {
        val now = clock.instant()
        val invite =
            invites.find(inviteId)?.takeIf { it.inviteeId == caller && it.isLive(now) }
                ?: return Resource.error(SpacesError.InviteNotFound)
        val space =
            spaces.find(invite.spaceId)?.takeIf { it.isLive } ?: return Resource.error(SpacesError.InviteNotFound)
        if (bans.find(space.spaceId, caller) != null) return Resource.error(SpacesError.Banned)
        join(spaces, members, transactor, space, caller, invite.inviterId, now)
        invites.save(invite.copy(state = InviteState.ACCEPTED, answeredAt = now))
        val name = profiles.get(setOf(caller)).profileOf(caller).displayName
        frames.send(listOf(invite.inviterId), InviteAcceptedFrame(space.spaceId, space.name, caller, name))
        val fresh = spaces.find(space.spaceId) ?: space
        return Resource.success(views.space(fresh, caller, members.find(space.spaceId, caller), banned = false))
    }
}

/** SP22 (SI3b): decline an invite. Nobody is told; admins keep seeing it as invited until it expires. */
class DeclineInvite(private val invites: InviteRepository, private val clock: Clock) {
    suspend operator fun invoke(caller: UserId, inviteId: UUID): Resource<Unit> {
        val now = clock.instant()
        val invite =
            invites.find(inviteId)?.takeIf { it.inviteeId == caller && it.isLive(now) }
                ?: return Resource.error(SpacesError.InviteNotFound)
        invites.save(invite.copy(state = InviteState.DECLINED, answeredAt = now))
        return Resource.success(Unit)
    }
}
