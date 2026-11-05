package com.circaso.spaces.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.MeView
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.MembershipPayment
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.PaidState
import com.circaso.spaces.domain.models.PaymentKind
import com.circaso.spaces.domain.models.PaymentView
import com.circaso.spaces.domain.models.Period
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PaymentRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.Lifecycle
import com.circaso.spaces.domain.support.Periods
import com.circaso.spaces.domain.support.SpaceViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A new free membership row. */
internal fun freeMembership(
    spaceId: SpaceId,
    userId: UserId,
    now: Instant,
    invitedBy: UserId? = null,
) = Membership(
    spaceId = spaceId,
    userId = userId,
    role = Role.MEMBER,
    joinedAt = now,
    invitedBy = invitedBy,
    notifications = NotificationLevel.ALL,
    mutedUntil = null,
    paidState = null,
    period = null,
    paidUntil = null,
    paidAmountMinor = null,
    complimentary = false,
    remindedFor = null,
    readUpTo = null,
)

/**
 * SP10 `POST /spaces/{spaceId}/follow`: a public space takes anyone who isn't banned, at once (SS1b). A members-only
 * space needs payment (SP13), an invite-only one an invite or a request (SP14). Following again answers your `me`.
 */
class FollowSpace(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val accounts: AccountDirectory,
    private val views: SpaceViews,
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
        if (seat.isMember) return Resource.success(views.me(space, caller, seat.membership, banned = false))
        if (!space.isLive) return Resource.error(SpacesError.SpaceNotFound)
        when (space.visibility) {
            Visibility.MEMBERS_ONLY -> return Resource.error(SpacesError.PaymentRequired)
            Visibility.INVITE_ONLY -> return Resource.error(SpacesError.InviteRequired)
            Visibility.PUBLIC -> Unit
        }
        if (accounts.status(caller) != AccountStatus.ACTIVE) return Resource.error(SpacesError.AccountRestricted)
        val joined = freeMembership(spaceId, caller, clock.instant())
        transactor.run("members.follow") {
            if (seat.membership != null) members.save(joined) else members.insert(joined)
            spaces.refreshMemberCount(spaceId)
        }
        return Resource.success(views.me(space, caller, members.find(spaceId, caller), banned = false))
    }
}

/**
 * SP11 `DELETE /spaces/{spaceId}/membership`: unfollow or leave (SS1c, SM6). A paid period ends now and isn't refunded.
 * The owner can't leave. An ended membership's row goes too, so the space leaves "Your spaces".
 */
class LeaveSpace(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val transactor: Transactor,
    private val gate: Gate,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<Unit> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val membership = seat.membership ?: return Resource.error(SpacesError.NotMember)
        if (membership.role == Role.OWNER) return Resource.error(SpacesError.OwnerCannotLeave)
        transactor.run("members.leave") {
            members.delete(spaceId, caller)
            spaces.refreshMemberCount(spaceId)
        }
        return Resource.success(Unit)
    }
}

/** SP12: which posts push (All, Mentions and admins, Off). */
class SetNotifications(
    private val members: MemberRepository,
    private val gate: Gate,
    private val views: SpaceViews,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId, level: NotificationLevel): Resource<MeView> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val membership = seat.membership?.takeIf { it.isActive } ?: return Resource.error(SpacesError.NotMember)
        val updated = membership.copy(notifications = level)
        members.save(updated)
        return Resource.success(views.me(seat.space, caller, updated, seat.banned))
    }
}

/**
 * SP13 `POST /spaces/{spaceId}/membership/payment` (SM1, SM3b, SM5): what joining, renewing or rejoining a
 * members-only space costs now, held 15 minutes. The phone pays it with W4 (`to: { spaceId }`, `spacePaymentId`) from
 * the personal wallet; the membership comes only from the confirmed transfer ([ConfirmMembershipPayment]).
 */
class StartMembershipPayment(
    private val payments: PaymentRepository,
    private val gate: Gate,
    private val views: SpaceViews,
    private val wallets: Wallets,
    private val accounts: AccountDirectory,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<PaymentView> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val space = seat.space
        if (seat.banned) return Resource.error(SpacesError.Banned)
        if (!space.isLive) return Resource.error(SpacesError.SpaceNotFound)
        val price = space.price?.takeIf { space.visibility == Visibility.MEMBERS_ONLY } ?: return Resource.error(SpacesError.NotPaidSpace)
        if (accounts.status(caller) != AccountStatus.ACTIVE) return Resource.error(SpacesError.AccountRestricted)
        val wallet = wallets.spaceWallet(spaceId.value)?.takeIf { it.isActive } ?: return Resource.error(SpacesError.WalletNotReady)

        val now = clock.instant()
        val membership = seat.membership
        val kind: PaymentKind
        val start: Instant
        when {
            membership == null -> {
                kind = PaymentKind.JOIN
                start = now
            }

            !membership.isActive -> {
                kind = PaymentKind.REJOIN
                start = now
            }

            membership.paidState == null || membership.complimentary || membership.period == Period.ONE_TIME ||
                membership.paidUntil == null -> {
                return Resource.error(SpacesError.AlreadyMember())
            }

            else -> {
                val paidUntil = membership.paidUntil
                val opens = views.renewFrom(paidUntil, membership.period ?: price.period)
                if (now < opens) return Resource.error(SpacesError.AlreadyMember(opens.toString()))
                kind = PaymentKind.RENEWAL
                start = if (paidUntil.isAfter(now)) paidUntil else now
            }
        }
        val payment =
            MembershipPayment(
                paymentId = Uuid7.generate(),
                spaceId = spaceId,
                payerId = caller,
                kind = kind,
                amountMinor = price.amountMinor,
                period = price.period,
                previousAmountMinor = membership?.paidAmountMinor?.takeIf { it != price.amountMinor },
                createdAt = now,
                expiresAt = now.plus(config.paymentLifetime),
                confirmedAt = null,
            )
        payments.insert(payment)
        return Resource.success(
            PaymentView(payment, start, Periods.end(start, price.period), wallet.walletId.value, wallet.blockchains),
        )
    }
}

/**
 * The wallet's `TransferConfirmed` for a membership payment: the person is a member from now (joining, rejoining,
 * or renewing in grace) or from the end of the period they had (renewing early), for one period. Once per payment,
 * whatever the event's repeats; a payment that isn't the payer's own, or for another amount, changes nothing.
 */
class ConfirmMembershipPayment(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val payments: PaymentRepository,
    private val transactor: Transactor,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(paymentId: UUID, payer: UserId, amountMinor: Long): Resource<Unit> {
        val payment = payments.find(paymentId) ?: return Resource.success(Unit)
        if (payment.payerId != payer || payment.amountMinor != amountMinor) return Resource.success(Unit)
        val space = spaces.find(payment.spaceId) ?: return Resource.success(Unit)
        val now = clock.instant()
        val outcome =
            transactor.run("members.paid") {
                if (!payments.confirm(paymentId, now)) return@run null
                val current = members.find(space.spaceId, payer)
                val extendsPeriod =
                    current != null && current.isActive && current.paidState == PaidState.ACTIVE &&
                        current.paidUntil?.isAfter(now) == true
                val start = if (extendsPeriod) checkNotNull(current?.paidUntil) else now
                val end = Periods.end(start, payment.period)
                val base = current?.takeIf { it.isActive } ?: freeMembership(space.spaceId, payer, now)
                val paid =
                    base.copy(
                        paidState = PaidState.ACTIVE,
                        period = payment.period,
                        paidUntil = end,
                        paidAmountMinor = payment.amountMinor,
                        complimentary = false,
                        remindedFor = null,
                        joinedAt = if (current?.isActive == true) current.joinedAt else now,
                    )
                if (current == null) members.insert(paid) else members.save(paid)
                spaces.refreshMemberCount(space.spaceId)
                (if (current?.isActive == true) "RENEWED" else "JOINED") to end
            } ?: return Resource.success(Unit)
        val (reason, end) = outcome
        frames.send(listOf(payer), MembershipUpdatedFrame(space.spaceId, space.name, reason, end?.toString()))
        return Resource.success(Unit)
    }
}

/**
 * `spaces.memberships` (every 10 minutes): the renewal reminder once per period (3 days before a monthly end, 7 before
 * a yearly one), `ACTIVE` → `GRACE` at the end of the period, `GRACE` → `ENDED` 3 days later. Never charges anything.
 * How many memberships it moved or reminded.
 */
class RunMembershipTimers(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Resource<Int> {
        val now = clock.instant()
        var done = 0
        val names = mutableMapOf<SpaceId, String>()

        suspend fun nameOf(spaceId: SpaceId): String? =
            names[spaceId] ?: spaces.find(spaceId)?.takeIf { it.state == SpaceState.LIVE }?.name?.also { names[spaceId] = it }

        for (membership in members.reminderDue(now.plus(config.remindMonthlyBefore), now.plus(config.remindYearlyBefore), BATCH)) {
            val name = nameOf(membership.spaceId) ?: continue
            members.save(membership.copy(remindedFor = membership.paidUntil))
            frames.send(
                listOf(membership.userId),
                MembershipUpdatedFrame(membership.spaceId, name, "RENEWAL_DUE", membership.paidUntil?.toString()),
            )
            done++
        }
        for (membership in members.paidDue(now.minus(config.graceAfterPeriod), now, BATCH)) {
            val name = nameOf(membership.spaceId) ?: continue
            val paidUntil = membership.paidUntil ?: continue
            val graceEnd = paidUntil.plus(config.graceAfterPeriod)
            val (state, reason, until) =
                if (membership.paidState == PaidState.ACTIVE && now < graceEnd) {
                    Triple(PaidState.GRACE, "GRACE_STARTED", graceEnd)
                } else {
                    Triple(PaidState.ENDED, "ENDED", graceEnd)
                }
            members.save(membership.copy(paidState = state))
            if (state == PaidState.ENDED) spaces.refreshMemberCount(membership.spaceId)
            frames.send(listOf(membership.userId), MembershipUpdatedFrame(membership.spaceId, name, reason, until.toString()))
            done++
        }
        return Resource.success(done)
    }

    private companion object {
        const val BATCH = 500
    }
}

/** The wallet's `WalletCreated` for a space's wallet: the space goes live if its wallet is now active. */
class GoLiveWhenWalletReady(private val spaces: SpaceRepository, private val lifecycle: Lifecycle) {
    suspend operator fun invoke(spaceId: SpaceId): Resource<Unit> {
        val space = spaces.find(spaceId) ?: return Resource.success(Unit)
        lifecycle.liveIfReady(space)
        return Resource.success(Unit)
    }
}
