package com.circaso.spaces.domain.support

import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.InviteView
import com.circaso.spaces.domain.models.Known
import com.circaso.spaces.domain.models.MeView
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.MembershipView
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.PaidState
import com.circaso.spaces.domain.models.Period
import com.circaso.spaces.domain.models.Price
import com.circaso.spaces.domain.models.Relation
import com.circaso.spaces.domain.models.RequestState
import com.circaso.spaces.domain.models.RequestView
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceSummaryView
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.models.WalletView
import com.circaso.spaces.domain.ports.MediaStorage
import com.circaso.spaces.domain.repositories.InviteRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.spaces.domain.repositories.RequestRepository
import com.circaso.users.api.ContactBook
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Where a file lives in spaces' bucket prefix. */
fun mediaKey(mediaId: UUID): String = "media/$mediaId"

/** A profile, or a neutral stand-in when users has none (an account being deleted). */
fun Map<UserId, PublicProfile>.profileOf(userId: UserId): PublicProfile =
    this[userId] ?: PublicProfile(userId, "Circaso user", null, null, null)

/**
 * Builds what the caller sees of spaces (SP3, SP7, SP8, SP9): the summary with fresh photo URLs, the contacts they
 * know there, the wallet for the owner and admins, and their own `me`.
 */
class SpaceViews(
    private val members: MemberRepository,
    private val requests: RequestRepository,
    private val invites: InviteRepository,
    private val posts: PostRepository,
    private val profiles: Profiles,
    private val contacts: ContactBook,
    private val wallets: Wallets,
    private val storage: MediaStorage,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend fun summary(space: Space, showAbout: Boolean = true): SpaceSummaryView =
        SpaceSummaryView(
            space = space,
            photoUrl = space.photoMediaId?.let { storage.downloadUrl(mediaKey(it), config.mediaUrlLifetime) },
            coverUrl = space.coverMediaId?.let { storage.downloadUrl(mediaKey(it), config.mediaUrlLifetime) },
            showAbout = showAbout,
        )

    /** The caller's contacts on Circaso, without themselves. */
    suspend fun contactsOf(caller: UserId): Set<UserId> = contacts.contactsOf(caller) - caller

    /** Of [contactIds], who is in the space, as names. */
    suspend fun known(spaceId: SpaceId, contactIds: Set<UserId>): Known {
        if (contactIds.isEmpty()) return Known.NONE
        val there = members.among(spaceId, contactIds).map { it.userId }
        return knownOf(there)
    }

    suspend fun knownOf(userIds: List<UserId>): Known {
        if (userIds.isEmpty()) return Known.NONE
        val first = userIds.take(KNOWN_NAMES)
        val found = profiles.get(first.toSet())
        return Known(first.map { found.profileOf(it).displayName }, userIds.size)
    }

    /** SP3 for [caller]: [membership] is theirs (an ended one too), [banned] whether a ban holds. */
    suspend fun space(
        space: Space,
        caller: UserId,
        membership: Membership?,
        banned: Boolean,
    ): SpaceView {
        val now = clock.instant()
        val me = me(space, caller, membership, banned, now)
        val inside = me.relation == Relation.MEMBER || me.relation == Relation.ENDED
        val showAbout = space.visibility != Visibility.INVITE_ONLY || inside || me.relation == Relation.INVITED
        val people = profiles.get(setOfNotNull(space.ownerId, membership?.invitedBy))
        val wallet =
            if (me.role?.atLeast(Role.ADMIN) == true) {
                val found = wallets.spaceWallet(space.spaceId.value)
                WalletView(found?.walletId?.value, found?.status ?: "NONE", canSign = found?.signerId == caller)
            } else {
                null
            }
        return SpaceView(
            summary = summary(space, showAbout),
            owner = people.profileOf(space.ownerId),
            known = known(space.spaceId, contactsOf(caller)),
            wallet = wallet,
            me = me,
        )
    }

    suspend fun me(
        space: Space,
        caller: UserId,
        membership: Membership?,
        banned: Boolean,
        now: Instant = clock.instant(),
    ): MeView {
        val active = membership?.takeIf { it.isActive }
        val request = if (active == null && !banned) requests.find(space.spaceId, caller) else null
        val invite =
            if (active == null &&
                !banned
            ) {
                invites.of(space.spaceId, caller)?.takeIf { it.isLive(now) }
            } else {
                null
            }
        val relation =
            when {
                banned -> Relation.BANNED
                active != null -> Relation.MEMBER
                membership != null -> Relation.ENDED
                invite != null -> Relation.INVITED
                request?.state == RequestState.PENDING -> Relation.REQUESTED
                else -> Relation.NONE
            }
        val inviter = (active?.invitedBy ?: invite?.inviterId)
        val people = inviter?.let { profiles.get(setOf(it)) }.orEmpty()
        val unread = active?.let { posts.countAfter(space.spaceId, it.readUpTo, config.unreadCap) } ?: 0
        return MeView(
            relation = relation,
            role = active?.role,
            joinedAt = active?.joinedAt,
            invitedBy = active?.invitedBy?.let { people.profileOf(it) },
            membership = membership?.let { membershipView(space, it) },
            mutedUntil = active?.mutedUntil?.takeIf { it.isAfter(now) },
            notifications = active?.notifications ?: NotificationLevel.ALL,
            request =
                request
                    ?.takeIf { it.state == RequestState.PENDING || it.state == RequestState.DECLINED }
                    ?.let {
                        RequestView(
                            it.state,
                            it.answeredAt ?: it.requestedAt,
                            canAskAgain(it.state, it.answeredAt),
                        )
                    },
            invite =
                invite?.let {
                    InviteView(
                        it.inviteId,
                        people.profileOf(it.inviterId),
                        it.invitedAt,
                        it.expiresAt,
                    )
                },
            unreadCount = unread,
            lastReadPostId = active?.readUpTo,
        )
    }

    private fun canAskAgain(state: RequestState, answeredAt: Instant?): Instant? =
        if (state == RequestState.DECLINED && answeredAt != null) answeredAt.plus(config.requestRetryAfter) else null

    /** A paid membership's dates (SM3, SM4); null for a free one. */
    fun membershipView(space: Space, membership: Membership): MembershipView? {
        val state = membership.paidState ?: return null
        val period = membership.period ?: Period.ONE_TIME
        val paidUntil = membership.paidUntil
        val price = space.price
        val nextPrice =
            price?.takeIf {
                period != Period.ONE_TIME && membership.paidAmountMinor != null &&
                    (it.period != period || it.amountMinor != membership.paidAmountMinor)
            }
        return MembershipView(
            state = state,
            period = period,
            paidUntil = paidUntil,
            graceUntil = paidUntil?.plus(config.graceAfterPeriod)?.takeIf { state != PaidState.ACTIVE },
            renewFrom = paidUntil?.let { renewFrom(it, period) },
            complimentary = membership.complimentary,
            nextPrice = nextPrice?.let { Price(it.amountMinor, it.period) },
        )
    }

    /** When Renew opens: 3 days before the end of a monthly period, 7 before a yearly one (SM3). */
    fun renewFrom(paidUntil: Instant, period: Period): Instant =
        paidUntil.minus(if (period == Period.YEARLY) config.remindYearlyBefore else config.remindMonthlyBefore)

    private companion object {
        const val KNOWN_NAMES = 3
    }
}
