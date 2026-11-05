package com.circaso.spaces.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.InviteRemovedFrame
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.PriceChangedFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.CreateSpaceCommand
import com.circaso.spaces.domain.models.HandleCheck
import com.circaso.spaces.domain.models.InviteState
import com.circaso.spaces.domain.models.LastPost
import com.circaso.spaces.domain.models.MediaPurpose
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.MySpaceView
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.SearchView
import com.circaso.spaces.domain.models.Space
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.models.SuggestionReason
import com.circaso.spaces.domain.models.SuggestionView
import com.circaso.spaces.domain.models.Topic
import com.circaso.spaces.domain.models.UpdateSpaceCommand
import com.circaso.spaces.domain.models.UpdatedSpace
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.InviteRepository
import com.circaso.spaces.domain.repositories.MediaRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.Handles
import com.circaso.spaces.domain.support.Lifecycle
import com.circaso.spaces.domain.support.SpaceRules
import com.circaso.spaces.domain.support.SpaceViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.support.framePrice
import com.circaso.spaces.domain.support.profileOf
import com.circaso.users.api.Profiles
import com.circaso.users.api.ReachPolicy
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Duration

/** SP1: whether a handle is free, and three free ones when it isn't (SN1b). */
class CheckHandle(private val spaces: SpaceRepository) {
    suspend operator fun invoke(raw: String): Resource<HandleCheck> {
        val handle = Handles.normalise(raw)
        val valid = Handles.isValid(handle)
        val taken = valid && spaces.byHandle(handle) != null
        if (valid && !taken) return Resource.success(HandleCheck(handle, true, emptyList()))
        if (!valid && handle.length < 3) return Resource.error(SpacesError.HandleInvalid)
        return Resource.success(HandleCheck(handle, false, SpaceRules.freeHandles(spaces, handle, handle)))
    }
}

/**
 * SP2 `POST /spaces` (SN1-SN3): the space `SETTING_UP` with the caller as its owner and the team as members with their
 * roles; it goes live once its wallet (W18) is active. Accounts younger than 7 days can't, and nobody makes more than
 * 3 in 24 hours (SN7c). The create key (the request's `Idempotency-Key`) makes Try Again return the first space.
 */
class CreateSpace(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val accounts: AccountDirectory,
    private val reach: ReachPolicy,
    private val views: SpaceViews,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreateSpaceCommand): Resource<SpaceView> {
        spaces
            .byCreateKey(
                command.ownerId,
                command.createKey,
            )?.let { return Resource.success(viewOf(it, command.ownerId)) }
        val now = clock.instant()
        refusal(command, now)?.let { return Resource.error(it) }
        val handle = Handles.normalise(command.handle)
        SpaceRules.details(command.name, command.about, handle, config)?.let { return Resource.error(it) }
        SpaceRules.price(command.visibility, command.price, config)?.let { return Resource.error(it) }
        teamRefusal(command)?.let { return Resource.error(it) }
        if (spaces.byHandle(handle) != null) {
            return Resource.error(SpacesError.HandleTaken(SpaceRules.freeHandles(spaces, handle, command.name)))
        }
        val space =
            Space(
                spaceId = SpaceId.new(),
                handle = handle,
                name = command.name.trim(),
                about = command.about.trim(),
                topic = command.topic,
                photoMediaId = command.photoMediaId,
                coverMediaId = command.coverMediaId,
                visibility = command.visibility,
                price = command.price,
                postingMode = command.postingMode,
                privateMessages = command.privateMessages,
                state = SpaceState.SETTING_UP,
                ownerId = command.ownerId,
                country = command.country,
                memberCount = 1 + command.team.size,
                lastPostAt = null,
                createdAt = now,
                updatedAt = now,
                version = 1,
                createKey = command.createKey,
            )
        val written =
            transactor.attempt("spaces.create") {
                if (!spaces.insert(space)) return@attempt Resource.success(false)
                val files = listOfNotNull(command.photoMediaId, command.coverMediaId)
                if (files.isNotEmpty() && !media.use(files, command.ownerId)) {
                    return@attempt Resource.error(SpacesError.MediaNotFound)
                }
                members.insert(membership(space, command.ownerId, Role.OWNER, now))
                command.team.forEach { members.insert(membership(space, it.userId, it.role, now, command.ownerId)) }
                Resource.success(true)
            }
        when (written) {
            is Resource.Error -> return written
            is Resource.Success -> if (!written.data) return raced(command, handle)
        }
        command.team.forEach {
            frames.send(
                listOf(it.userId),
                MembershipUpdatedFrame(space.spaceId, space.name, "ROLE_CHANGED", role = it.role.name),
            )
        }
        return Resource.success(viewOf(space, command.ownerId))
    }

    private suspend fun refusal(command: CreateSpaceCommand, now: java.time.Instant): SpacesError? {
        val status = accounts.status(command.ownerId)
        if (status != AccountStatus.ACTIVE || accounts.kind(command.ownerId) == AccountKind.SUPPORT) {
            return SpacesError.AccountRestricted
        }
        val createdAt = accounts.createdAt(command.ownerId) ?: return SpacesError.AccountRestricted
        val allowedFrom = createdAt.plus(config.accountAgeToCreate)
        if (now < allowedFrom) return SpacesError.AccountTooNew(allowedFrom.toString())
        val recent = spaces.createdSince(command.ownerId, now.minus(config.createWindow))
        if (recent.size >= config.createsPerWindow) {
            val freesAt = (recent.minOrNull() ?: now).plus(config.createWindow)
            return SpacesError.TooManySpaces(Duration.between(now, freesAt).seconds.coerceAtLeast(1))
        }
        return null
    }

    private suspend fun teamRefusal(command: CreateSpaceCommand): SpacesError? {
        val ids = command.team.map { it.userId }
        val bad =
            ids.size > config.teamMax || ids.size != ids.toSet().size || command.ownerId in ids ||
                command.team.any { it.role == Role.OWNER || it.role == Role.MEMBER }
        if (bad) return SpacesError.TeamInvalid
        if (ids.isNotEmpty() &&
            reach.reachable(command.ownerId, ids.toSet()) != ids.toSet()
        ) {
            return SpacesError.TeamInvalid
        }
        return null
    }

    /** Lost a race: the same create key won (return it) or the handle went to someone else meanwhile. */
    private suspend fun raced(command: CreateSpaceCommand, handle: String): Resource<SpaceView> {
        spaces
            .byCreateKey(
                command.ownerId,
                command.createKey,
            )?.let { return Resource.success(viewOf(it, command.ownerId)) }
        return Resource.error(SpacesError.HandleTaken(SpaceRules.freeHandles(spaces, handle, command.name)))
    }

    private suspend fun viewOf(space: Space, caller: UserId): SpaceView =
        views.space(space, caller, members.find(space.spaceId, caller), banned = false)

    private fun membership(
        space: Space,
        userId: UserId,
        role: Role,
        now: java.time.Instant,
        invitedBy: UserId? = null,
    ) = Membership(
        spaceId = space.spaceId,
        userId = userId,
        role = role,
        joinedAt = now,
        invitedBy = invitedBy,
        notifications = NotificationLevel.ALL,
        mutedUntil = null,
        paidState = null,
        period = null,
        paidUntil = null,
        paidAmountMinor = null,
        complimentary = role != Role.MEMBER && space.visibility == Visibility.MEMBERS_ONLY,
        remindedFor = null,
        readUpTo = null,
    )
}

/** SP3: a space as the caller may see it; a space being set up turns live here if its wallet is already ready. */
class GetSpace(
    private val spaces: SpaceRepository,
    private val gate: Gate,
    private val views: SpaceViews,
    private val lifecycle: Lifecycle,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<SpaceView> {
        val space = spaces.find(spaceId) ?: return Resource.error(SpacesError.SpaceNotFound)
        val seat =
            when (val found = gate.seat(lifecycle.liveIfReady(space), caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        return Resource.success(views.space(seat.space, caller, seat.membership, seat.banned))
    }
}

/** SP4: the same as SP3, for links and QR codes (`circaso.app/s/{handle}`). */
class GetSpaceByHandle(
    private val spaces: SpaceRepository,
    private val gate: Gate,
    private val views: SpaceViews,
    private val lifecycle: Lifecycle,
) {
    suspend operator fun invoke(caller: UserId, raw: String): Resource<SpaceView> {
        val space = spaces.byHandle(Handles.normalise(raw)) ?: return Resource.error(SpacesError.SpaceNotFound)
        val seat =
            when (val found = gate.seat(lifecycle.liveIfReady(space), caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        return Resource.success(views.space(seat.space, caller, seat.membership, seat.banned))
    }
}

/**
 * SP5 `PATCH /spaces/{spaceId}`: admins change the details and who can post; only the owner the handle, who can join,
 * the price and who may message them. A price change applies from each member's next period and is told to every
 * member at once (SN8b). Becoming members-only keeps everyone already in for free; leaving it ends paid periods.
 */
class UpdateSpace(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val views: SpaceViews,
    private val lifecycle: Lifecycle,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: UpdateSpaceCommand): Resource<UpdatedSpace> {
        val seat =
            when (val found = gate.require(command.spaceId, command.caller, Role.ADMIN, SpacesError.NotAdmin)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val ownerOnly =
            command.handle != null || command.visibility != null || command.price != null ||
                command.privateMessages != null
        if (ownerOnly && seat.role != Role.OWNER) return Resource.error(SpacesError.NotOwner)
        val old = seat.space
        val handle = command.handle?.let(Handles::normalise) ?: old.handle
        val visibility = command.visibility ?: old.visibility
        val price = if (visibility == Visibility.MEMBERS_ONLY) command.price ?: old.price else null
        val next =
            old.copy(
                name = command.name?.trim() ?: old.name,
                handle = handle,
                about = command.about?.trim() ?: old.about,
                topic = command.topic ?: old.topic,
                photoMediaId = if (command.removePhoto) null else command.photoMediaId ?: old.photoMediaId,
                coverMediaId = if (command.removeCover) null else command.coverMediaId ?: old.coverMediaId,
                visibility = visibility,
                price = price,
                postingMode = command.postingMode ?: old.postingMode,
                privateMessages = command.privateMessages ?: old.privateMessages,
                updatedAt = clock.instant(),
            )
        SpaceRules.details(next.name, next.about, handle, config)?.let { return Resource.error(it) }
        if (command.price != null && command.visibility == null && old.visibility != Visibility.MEMBERS_ONLY) {
            return Resource.error(SpacesError.PriceInvalid)
        }
        SpaceRules.price(visibility, price, config)?.let { return Resource.error(it) }
        if (handle != old.handle && spaces.byHandle(handle) != null) {
            return Resource.error(SpacesError.HandleTaken(SpaceRules.freeHandles(spaces, handle, next.name)))
        }
        val files = listOfNotNull(command.photoMediaId, command.coverMediaId)
        val saved =
            transactor.attempt("spaces.update") {
                if (files.isNotEmpty() && !media.use(files, command.caller)) {
                    return@attempt Resource.error(SpacesError.MediaNotFound)
                }
                val written = spaces.update(old, next) ?: return@attempt Resource.error(SpacesError.SpaceNotFound)
                if (old.visibility == Visibility.MEMBERS_ONLY && visibility != Visibility.MEMBERS_ONLY) {
                    members.clearPaid(old.spaceId)
                }
                if (old.visibility != Visibility.MEMBERS_ONLY && visibility == Visibility.MEMBERS_ONLY) {
                    members.makeComplimentary(old.spaceId)
                }
                spaces.refreshMemberCount(old.spaceId)
                Resource.success(written)
            }
        val space =
            when (saved) {
                is Resource.Success -> saved.data
                is Resource.Error -> return saved
            }
        val notified = announce(old, space)
        val view = views.space(space, command.caller, members.find(space.spaceId, command.caller), banned = false)
        return Resource.success(UpdatedSpace(view, notified))
    }

    /** Everyone hears `space.updated`; a new price, `space.price_changed` (and a push). How many were told of it. */
    private suspend fun announce(old: Space, space: Space): Int {
        lifecycle.announce(space)
        val price = space.price ?: return 0
        if (old.price == price && old.visibility == space.visibility) return 0
        if (old.visibility != Visibility.MEMBERS_ONLY) return 0
        val told = members.active(space.spaceId).filter { it.role != Role.OWNER }.map { it.userId }
        frames.send(told, PriceChangedFrame(space.spaceId, space.name, framePrice(price), old.price?.let(::framePrice)))
        return told.size
    }
}

/**
 * SP6 `DELETE /spaces/{spaceId}`: the owner deletes the space once its wallet is empty (read from Circle). Everyone in
 * it hears `space.updated`; open invites leave the invitees' Chats. Payments stay in everyone's wallet history.
 */
class DeleteSpace(
    private val spaces: SpaceRepository,
    private val invites: InviteRepository,
    private val gate: Gate,
    private val wallets: Wallets,
    private val lifecycle: Lifecycle,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId): Resource<Unit> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.OWNER, SpacesError.NotOwner)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        when (val held = wallets.spaceWalletHoldsMoney(spaceId.value)) {
            is Resource.Error -> return held
            is Resource.Success -> if (held.data) return Resource.error(SpacesError.WalletNotEmpty)
        }
        val now = clock.instant()
        val deleted =
            spaces.update(seat.space, seat.space.copy(state = SpaceState.DELETED, updatedAt = now))
                ?: return Resource.error(SpacesError.SpaceNotFound)
        lifecycle.announce(deleted)
        invites.ofSpace(spaceId, now.minus(INVITE_WINDOW)).filter { it.isLive(now) }.forEach {
            invites.save(it.copy(state = InviteState.CANCELLED, answeredAt = now))
            frames.send(listOf(it.inviteeId), InviteRemovedFrame(it.inviteId.toString(), spaceId))
        }
        return Resource.success(Unit)
    }

    private companion object {
        val INVITE_WINDOW: Duration = Duration.ofDays(15)
    }
}

/** SP7: "Your spaces" (SD1), newest activity first, ended memberships and your own spaces being set up included. */
class ListMySpaces(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val bans: BanRepository,
    private val posts: PostRepository,
    private val views: SpaceViews,
    private val profiles: Profiles,
    private val config: SpacesConfig,
) {
    suspend operator fun invoke(
        caller: UserId,
        offset: Int,
        limit: Int,
    ): Resource<Page<MySpaceView>> {
        val mine = members.ofUser(caller).associateBy { it.spaceId }
        val found = spaces.findAll(mine.keys).filterNot { it.isGone }
        val banned = bans.spacesBanning(caller)
        val ordered =
            found
                .filter { it.spaceId !in banned }
                .sortedByDescending { it.lastPostAt ?: mine.getValue(it.spaceId).joinedAt }
        val page = ordered.drop(offset).take(limit)
        val items =
            page.map { space ->
                val membership = mine.getValue(space.spaceId)
                val me = views.me(space, caller, membership, banned = false)
                val last = posts.latest(space.spaceId)?.takeIf { membership.isActive }
                val author = last?.let { profiles.get(setOf(it.authorId)).profileOf(it.authorId).displayName }
                MySpaceView(
                    summary = views.summary(space),
                    me = me,
                    lastPost =
                        last?.let {
                            val kind = if (it.mediaIds.isEmpty()) "TEXT" else "MEDIA"
                            LastPost(
                                it.postId,
                                author.orEmpty(),
                                it.text?.take(PREVIEW),
                                kind,
                                it.createdAt,
                                it.isRemoved,
                            )
                        },
                    unreadCount = me.unreadCount.coerceAtMost(config.unreadCap),
                    activityAt = space.lastPostAt ?: membership.joinedAt,
                )
            }
        val next = (offset + limit).takeIf { ordered.size > it }?.toString()
        return Resource.success(Page(items, next))
    }

    private companion object {
        const val PREVIEW = 120
    }
}

/**
 * SP8: live public and members-only spaces the caller isn't in or banned from: the ones their contacts are in first,
 * then popular in their country, then popular anywhere (SD1, SD3).
 */
class SuggestSpaces(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val bans: BanRepository,
    private val views: SpaceViews,
) {
    suspend operator fun invoke(
        caller: UserId,
        country: String?,
        topic: Topic?,
        offset: Int,
        limit: Int,
    ): Resource<Page<SuggestionView>> {
        val want = offset + limit + 1
        val exclude = members.ofUser(caller).map { it.spaceId }.toSet() + bans.spacesBanning(caller)
        val contacts = views.contactsOf(caller)
        val byContacts = members.spacesOf(contacts).filterKeys { it !in exclude }
        val viaContacts =
            spaces
                .findAll(byContacts.keys)
                .filter { it.isDiscoverable && (topic == null || it.topic == topic) }
                .sortedWith(
                    compareByDescending<Space> {
                        byContacts
                            .getValue(
                                it.spaceId,
                            ).size
                    }.thenByDescending { it.memberCount },
                )
        val seen = exclude + viaContacts.map { it.spaceId }
        val local = if (country != null) spaces.popular(country, topic, seen, 0, want) else emptyList()
        val anywhere = spaces.popular(null, topic, seen + local.map { it.spaceId }, 0, want)
        val ordered =
            viaContacts.map { it to SuggestionReason.CONTACTS } +
                local.map { it to SuggestionReason.POPULAR_IN_COUNTRY } +
                anywhere.map { it to SuggestionReason.POPULAR }
        val page = ordered.drop(offset).take(limit)
        val items =
            page.map { (space, reason) ->
                SuggestionView(views.summary(space), reason, views.knownOf(byContacts[space.spaceId].orEmpty()))
            }
        val next = (offset + limit).takeIf { ordered.size > it }?.toString()
        return Resource.success(Page(items, next))
    }
}

/**
 * SP9: live public and members-only spaces by words (name, handle, about, topic) or, for a query starting with `@`,
 * by handle: the exact one on top, then the similar ones (SD2, SD2b). Invite-only spaces never show.
 */
class SearchSpaces(private val spaces: SpaceRepository, private val views: SpaceViews) {
    suspend operator fun invoke(
        query: String,
        topic: Topic?,
        offset: Int,
        limit: Int,
    ): Resource<SearchView> {
        val text = query.trim()
        if (text.isEmpty() ||
            text.length > MAX_QUERY
        ) {
            return Resource.error(SpacesError.DetailsInvalid("q", "1 to 60 characters"))
        }
        if (text.startsWith("@")) {
            val handle = Handles.normalise(text)
            if (handle.isEmpty()) return Resource.error(SpacesError.DetailsInvalid("q", "a handle after @"))
            val exact = spaces.byHandle(handle)?.takeIf { it.isDiscoverable }?.takeIf { offset == 0 }
            val similar = spaces.handlesStartingWith(handle, offset, limit + 1)
            return Resource.success(
                SearchView(
                    exact = exact?.let { views.summary(it) },
                    spaces = similar.take(limit).map { views.summary(it) },
                    next = (offset + limit).takeIf { similar.size > limit }?.toString(),
                ),
            )
        }
        val found = spaces.search(text, topic, offset, limit + 1)
        return Resource.success(
            SearchView(
                exact = null,
                spaces = found.take(limit).map { views.summary(it) },
                next = (offset + limit).takeIf { found.size > limit }?.toString(),
            ),
        )
    }

    private companion object {
        const val MAX_QUERY = 60
    }
}
