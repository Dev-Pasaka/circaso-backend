package com.circaso.users.domain.usecases

import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.ConnectionAdded
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.models.ConnectTarget
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.Connection
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.ports.ReachCache
import com.circaso.users.domain.ports.Transactor
import com.circaso.users.domain.ports.UsersCache
import com.circaso.users.domain.repositories.BlockRepository
import com.circaso.users.domain.repositories.ContactRepository
import com.circaso.users.domain.repositories.HandleRepository
import com.circaso.users.domain.repositories.InviteRepository
import com.circaso.users.domain.repositories.ProfileRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.HandleRules
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier
import java.time.Clock
import java.time.Instant

/**
 * U11: connects through a typed @handle, a QR code or an invite link. A QR code or link was handed over on
 * purpose, so the contact is created both ways; a typed handle only lets the person who typed it reach the
 * handle's owner. Blocks win over everything, and the answer never says whether it was a block.
 *
 * Everything after the rate limit is one transaction: who the value names, the target's profile (read `FOR SHARE`,
 * so an account deletion can't slip in between), the block check, and each direction's conditional write, which
 * alone decides whether this call created the contact and writes the event.
 */
class Connect(
    private val handles: HandleRepository,
    private val invites: InviteRepository,
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
    private val blocks: BlockRepository,
    private val transactor: Transactor,
    private val store: UsersStore,
    private val bus: EventBus,
    private val cache: ReachCache,
    private val usersCache: UsersCache,
    private val views: ProfileViews,
    private val notifier: UserNotifier,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        viewer: UserId,
        via: ConnectVia,
        value: String,
    ): Resource<Connection> {
        if (via == ConnectVia.HANDLE && usersCache.countHandleLookup(viewer) > MAX_LOOKUPS_PER_HOUR) {
            return Resource.error(
                SharedError.RateLimited(usersCache.handleLookupRetryAfter(viewer)?.seconds ?: 3_600),
            )
        }
        // Building an envelope is pure (no I/O): the event is written inside the transaction, published after it.
        val announce = { target: ConnectTarget ->
            bus.envelope(ConnectionAdded(viewer, target.userId, target.source.name, target.mutual))
        }
        val outcome =
            transactor.attempt("contacts.connect") {
                val target = resolve(via, value) ?: return@attempt Resource.error(notFound(via))
                connect(viewer, via, target, announce)
            }
        val (target, profile, event) =
            when (outcome) {
                is Resource.Success -> outcome.data
                is Resource.Error -> return outcome
            }

        val created = event != null
        if (event != null) {
            bus.publish(event)
            cache.invalidateTarget(target.userId)
            cache.invalidateTarget(viewer)
            if (target.mutual) {
                profiles.find(viewer)?.let { notifier.connectionAdded(target.userId, it) }
                notifier.connectionAdded(viewer, profile)
            }
        }
        return Resource.success(Connection(views.forViewer(viewer, profile), created))
    }

    /** Who [value] names: the live owner of a handle, or the owner of a valid invite. */
    private suspend fun resolve(via: ConnectVia, value: String): ConnectTarget? =
        when (via) {
            ConnectVia.HANDLE -> {
                HandleRules
                    .normalise(value)
                    ?.let { handles.find(it) }
                    ?.takeIf { it.releasedAt == null }
                    ?.let { ConnectTarget(it.userId, ContactSource.HANDLE, false) }
            }

            ConnectVia.QR, ConnectVia.INVITE -> {
                val source = if (via == ConnectVia.QR) ContactSource.QR else ContactSource.INVITE
                invites
                    .find(value.trim().substringAfterLast('/'))
                    ?.let { ConnectTarget(it.ownerId, source, true) }
            }
        }

    /**
     * Inside the transaction: checks the target may be reached this way, then makes each direction active. Answers
     * the target, its profile and, only when this call changed a contact, the event it wrote to the outbox.
     */
    private suspend fun connect(
        viewer: UserId,
        via: ConnectVia,
        target: ConnectTarget,
        announce: (ConnectTarget) -> EventEnvelope,
    ): Resource<Triple<ConnectTarget, Profile, EventEnvelope?>> {
        if (target.userId == viewer) return Resource.error(notFound(via))
        val profile = profiles.lock(target.userId)
        if (via == ConnectVia.HANDLE && profile?.privacy?.handleLookup != true) {
            return Resource.error(UsersError.HandleNotFound)
        }
        if (blocks.eitherWay(viewer, target.userId)) {
            return Resource.error(if (via == ConnectVia.HANDLE) UsersError.HandleNotFound else UsersError.NotReachable)
        }
        if (profile == null) return Resource.error(SharedError.NotFound)

        val now = clock.instant()
        val directions =
            listOfNotNull(
                contact(viewer, target.userId, target.source, now),
                contact(target.userId, viewer, target.source, now).takeIf { target.mutual },
            )
        // Rows are always written in owner-id order, so A connecting to B while B connects to A can't deadlock.
        var changed = false
        for (direction in directions.sortedBy { it.ownerId.value }) {
            if (contacts.connect(direction)) changed = true
        }
        if (!changed) return Resource.success(Triple(target, profile, null))
        val event = announce(target)
        store.commit(UsersChanges(events = listOf(event)))
        return Resource.success(Triple(target, profile, event))
    }

    private fun notFound(via: ConnectVia) =
        if (via == ConnectVia.HANDLE) UsersError.HandleNotFound else UsersError.InviteInvalid

    private fun contact(
        owner: UserId,
        other: UserId,
        source: ContactSource,
        at: Instant,
    ) = Contact(owner, other, source, ContactStatus.ACTIVE, null, at)

    private companion object {
        const val MAX_LOOKUPS_PER_HOUR = 30
    }
}
