package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.PrivateMessages
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PaymentRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.wallet.api.MembershipCharge
import com.circaso.wallet.api.SpaceAccess
import java.time.Clock
import java.util.UUID

/**
 * The wallet's `SpaceWalletPolicy.access`: the owner and admins whatever the space's state (its wallet is set up
 * before it goes live), a member only of a live space they're in and not banned from; anyone else nothing.
 */
class GetWalletAccess(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val bans: BanRepository,
) {
    suspend operator fun invoke(spaceId: SpaceId, userId: UserId): Resource<SpaceAccess> {
        val space = spaces.find(spaceId)?.takeIf { !it.isGone } ?: return Resource.success(SpaceAccess.NONE)
        if (space.ownerId == userId) return Resource.success(SpaceAccess.OWNER)
        if (bans.find(spaceId, userId) != null) return Resource.success(SpaceAccess.NONE)
        val membership =
            members.find(spaceId, userId)?.takeIf { it.isActive } ?: return Resource.success(SpaceAccess.NONE)
        return Resource.success(
            when {
                membership.role.atLeast(Role.ADMIN) -> SpaceAccess.ADMIN
                space.isLive -> SpaceAccess.MEMBER
                else -> SpaceAccess.NONE
            },
        )
    }
}

/** The wallet's `SpaceWalletPolicy.administered`: the spaces someone is an admin of (their owner signs instead). */
class ListAdministeredSpaces(private val spaces: SpaceRepository, private val members: MemberRepository) {
    suspend operator fun invoke(userId: UserId): Resource<List<SpaceId>> {
        val admin = members.ofUser(userId).filter { it.isActive && it.role == Role.ADMIN }.map { it.spaceId }
        val live = spaces.findAll(admin).filterNot { it.isGone }.map { it.spaceId }
        return Resource.success(live)
    }
}

/** The wallet's `SpaceWalletPolicy.team`: the owner and admins, who hear that the space's wallet is ready. */
class GetSpaceTeam(private val members: MemberRepository) {
    suspend operator fun invoke(spaceId: SpaceId): Resource<Set<UserId>> =
        Resource.success(
            members
                .team(spaceId)
                .filter { it.role.atLeast(Role.ADMIN) }
                .map { it.userId }
                .toSet(),
        )
}

/** The wallet's `SpaceWalletPolicy.membershipCharge`: SP13's payment while it can be paid, for its payer only. */
class FindMembershipCharge(private val payments: PaymentRepository, private val clock: Clock) {
    suspend operator fun invoke(paymentId: UUID, payer: UserId): Resource<MembershipCharge?> {
        val payment = payments.find(paymentId) ?: return Resource.success(null)
        val usable = payment.payerId == payer && payment.confirmedAt == null && clock.instant() < payment.expiresAt
        if (!usable) return Resource.success(null)
        return Resource.success(MembershipCharge(payment.paymentId, payment.spaceId.value, payment.amountMinor))
    }
}

/**
 * Messaging's `SpacePrivateChats.allowed`: one side owns the live space; the other may message its owner (anyone who
 * can see the space, or members only when the owner chose so, and always members only for an invite-only space);
 * nobody banned from it, either way.
 */
class CheckPrivateChat(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val bans: BanRepository,
) {
    suspend operator fun invoke(
        spaceId: SpaceId,
        from: UserId,
        to: UserId,
    ): Resource<Boolean> {
        if (from == to) return Resource.success(false)
        val space = spaces.find(spaceId)?.takeIf { it.isLive } ?: return Resource.success(false)
        val other =
            when (space.ownerId) {
                to -> from
                from -> to
                else -> return Resource.success(false)
            }
        if (bans.find(spaceId, other) != null) return Resource.success(false)
        val isMember = members.find(spaceId, other)?.isActive == true
        if (space.ownerId == from) return Resource.success(isMember || space.visibility != Visibility.INVITE_ONLY)
        val allowed =
            when (space.effectivePrivateMessages) {
                PrivateMessages.ANYONE -> true
                PrivateMessages.MEMBERS -> isMember
            }
        return Resource.success(allowed)
    }
}

/** The wallet's `SpaceWalletPolicy.name`: the space's name for the "money received" push. */
class GetSpaceName(private val spaces: SpaceRepository) {
    suspend operator fun invoke(spaceId: SpaceId): Resource<String?> = Resource.success(spaces.find(spaceId)?.name)
}
